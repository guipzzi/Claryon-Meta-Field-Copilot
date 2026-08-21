package com.claryon.field.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.Utterance
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.claryon.field.oculos.CapturaDePlaca
import com.claryon.field.oculos.LeitorDeFrame
import com.claryon.field.oculos.LeituraDePlaca
import com.claryon.field.oculos.OcrDeFrame
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.Frame
import com.claryon.glasses.GlassesFacade
import com.claryon.glasses.PhotoData
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **O contrato de efemeridade dos frames, como ASSERÇÃO — não como promessa.**
 *
 * O `CLAUDE.md` §2 proíbe reconhecimento facial e base biométrica, sem versão e sem
 * flag. Uma abordagem tem gente no enquadramento, e a câmera aponta para a altura da
 * placa. A spec promete que os frames vivem em RAM e morrem quando o OCR devolve —
 * o mesmo contrato do pré-roll do PTT.
 *
 * Promessa em KDoc não impede ninguém de escrever um `File(...)`. O que impede é
 * isto: a captura roda com o **OCR de verdade** (ML Kit), e `filesDir`, `cacheDir` e
 * `getExternalFilesDir` são varridos antes e depois exigindo **zero arquivo novo**.
 *
 * E tem contra-teste, pela pergunta 3 do §6: [aVarreduraReprovaUmFrameGravadoDePROPOSITO]
 * grava um frame de propósito e exige que a **mesma** rotina de asserção reprove.
 * Sem ele, uma varredura afrouxada até cegar ficaria verde para sempre.
 */
@RunWith(AndroidJUnit4::class)
class FramesEfemerosTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Frames sintéticos, no formato que o DAT entrega ───────────────────────

    /**
     * A resolução do stream, medida no artefato e não lembrada: `javap -p -c` em
     * `mwdat-camera-0.9.0`, `VideoFormat.Companion.getDefaultFormat()` →
     * `H265, 504x896, 30 fps, iFrameInterval 3, 750000 bps, colorFormat 19`.
     * `19` é `COLOR_FormatYUV420Planar` (I420). Retrato, porque os óculos são.
     */
    private val largura = 504
    private val altura = 896

    /** Uma placa impressa desenhada no meio do enquadramento retrato. */
    private fun cenaComPlaca(texto: String): Bitmap {
        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(70, 70, 75)) // carroceria escura
        val placa = Paint().apply { color = Color.WHITE }
        canvas.drawRect(42f, 380f, 462f, 530f, placa)
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 86f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(texto, 252f, 490f, tinta)
        return bmp
    }

    /**
     * O que a câmera vê enquanto o agente ainda está mirando — **com texto que não é
     * placa**, de propósito.
     *
     * Um retângulo cinza liso mediria errado, e para menos: o detector do ML Kit
     * desiste em milissegundos quando não há nenhuma borda de caractere, e é
     * justamente nos frames SEM placa que a janela de 5 s é gasta. Medir o custo
     * deles com uma imagem vazia produziria um número operacional otimista por
     * construção.
     *
     * Nenhum token nem par de tokens vizinhos aqui tem sete caracteres, então
     * `PlacaValidator` não pode casar — e se um dia casar, o teste falha alto, que é
     * o comportamento certo.
     */
    private fun cenaSemPlaca(
        primeira: String = "RUA DAS FLORES",
        segunda: String = "1234 CENTRO",
    ): Bitmap {
        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(70, 70, 75))
        val letreiro = Paint().apply { color = Color.rgb(210, 210, 205) }
        canvas.drawRect(30f, 120f, 474f, 260f, letreiro)
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30)
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(primeira, 252f, 180f, tinta)
        canvas.drawText(segunda, 252f, 240f, tinta)
        return bmp
    }

    private fun luminancia(bmp: Bitmap): ByteArray {
        val pixels = IntArray(largura * altura)
        bmp.getPixels(pixels, 0, largura, 0, 0, largura, altura)
        val y = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y[i] = ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(0, 255).toByte()
        }
        return y
    }

    /** I420: Y inteiro, depois o plano U, depois o V. */
    private fun frameI420(bmp: Bitmap, ts: Long): Frame {
        val y = luminancia(bmp)
        val croma = ByteArray(y.size / 2) { 128.toByte() }
        return Frame(largura, altura, ts, y + croma)
    }

    /** NV12: Y inteiro, depois U e V **entrelaçados**. O plano Y é byte a byte o mesmo. */
    private fun frameNv12(bmp: Bitmap, ts: Long): Frame {
        val y = luminancia(bmp)
        val croma = ByteArray(y.size / 2) { if (it % 2 == 0) 120.toByte() else 136.toByte() }
        return Frame(largura, altura, ts, y + croma)
    }

    // ── A fachada de mentira ──────────────────────────────────────────────────

    private class FachadaFake(private val frames: List<Frame>) : GlassesFacade {
        override val registration: StateFlow<RegistrationStatus> =
            MutableStateFlow(RegistrationStatus.REGISTERED)
        override val session: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STARTED)
        override suspend fun ensureRegistered() = Result.success(Unit)
        override suspend fun startSession() = Result.success(Unit)
        override suspend fun capturePhoto(): Result<PhotoData> =
            Result.failure(ClaryonError.Glasses("glasses.no_stream", "não usado aqui"))

        var entregues = 0

        override suspend fun withCamera(
            config: CameraProfile,
            block: suspend (Flow<Frame>) -> Unit,
        ): Result<Unit> {
            block(flow { frames.forEach { entregues++; emit(it) } })
            return if (entregues > 0) {
                Result.success(Unit)
            } else {
                Result.failure(ClaryonError.Glasses("glasses.no_frames", "sem imagem"))
            }
        }
    }

    // ── A varredura ───────────────────────────────────────────────────────────

    /**
     * Todo arquivo sob os três diretórios privados do app. Recursiva de propósito:
     * um frame gravado em `cacheDir/ocr/2026-08-21/frame-0.jpg` escaparia de uma
     * varredura rasa, e é exatamente assim que um cache de depuração nasce.
     */
    private fun arquivos(): Set<String> =
        listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
            .flatMap { raiz ->
                raiz.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
            }
            .toSet()

    private fun exigirZeroArquivoNovo(antes: Set<String>) {
        val novos = (arquivos() - antes).sorted()
        assertEquals(
            "A captura de placa deixou arquivo no aparelho:\n" +
                novos.joinToString("\n") { "  $it" } +
                "\n\nFrame de abordagem é insumo efêmero: vive em RAM e morre quando " +
                "o OCR devolve, como o pré-roll do PTT. O que sai do fluxo é uma " +
                "string de sete caracteres, nunca imagem. Se um frame precisar virar " +
                "evidência, é outro caminho — EncryptedFile + Keystore com manifesto " +
                "de custódia —, nunca subproduto silencioso de uma consulta. " +
                "Ver CLAUDE.md §2 e specs/consulta-de-placa-por-camera.spec.md.",
            emptyList<String>(),
            novos,
        )
    }

    /**
     * O ML Kit carrega o modelo Latin na primeira inferência, e essa carga pode
     * tocar disco. Aquecer **antes** da varredura tira do teste um confundidor que
     * não é o defeito procurado — a asserção é sobre o frame, não sobre o
     * reconhecedor.
     */
    @Before
    fun aquecerOReconhecedor() = runBlocking {
        PlacaOcr().use { it.lerTexto(cenaComPlaca("ABC1D23")) }
        Unit
    }

    private fun capturar(
        frames: List<Frame>,
        leitor: LeitorDeFrame,
    ): LeituraDePlaca = runBlocking {
        val ditas = mutableListOf<Utterance>()
        CapturaDePlaca(
            facade = { FachadaFake(frames) },
            leitor = leitor,
            avisar = { ditas += it },
        ).ler().also {
            assertEquals("a instrução falada não saiu", listOf(CapturaDePlaca.INSTRUCAO), ditas)
        }
    }

    // ── O teste que é o produto ───────────────────────────────────────────────

    /**
     * **Captura completa com o OCR real: lê a placa e não deixa arquivo nenhum.**
     *
     * As três asserções prendem coisas diferentes, e nenhuma sozinha basta: a placa
     * prova que o caminho funcionou (senão o teste seria verde por não ter feito
     * nada), a varredura prova o descarte no disco, e `convertidos == reciclados`
     * prova o descarte na memória nativa — um `Bitmap` não reciclado sobrevive ao
     * `finally` e fica esperando o GC.
     */
    @Test
    fun capturaComOcrReal_leAPlaca_eNaoDeixaArquivoNenhum() {
        val ocr = PlacaOcr()
        val leitor = OcrDeFrame(ocr)
        val frames = listOf(
            frameI420(cenaSemPlaca(), 1),
            frameI420(cenaComPlaca("ABC1D23"), 2),
        )

        val antes = arquivos()
        val inicio = SystemClock.elapsedRealtime()
        val r = capturar(frames, leitor)
        val gasto = SystemClock.elapsedRealtime() - inicio
        ocr.close()

        Log.i(
            TAG,
            "OCR: $r em ${gasto}ms de parede — ${leitor.convertidos} frame(s) convertido(s), " +
                "${leitor.reciclados} bitmap(s) reciclado(s)",
        )

        assertTrue("o OCR não leu a placa: $r", r is LeituraDePlaca.Lida)
        assertEquals("ABC1D23", (r as LeituraDePlaca.Lida).placa)
        assertEquals("parou no frame errado", 2, r.frames)
        exigirZeroArquivoNovo(antes)
        assertEquals(
            "bitmap convertido e não reciclado — o frame sobrevive na memória nativa",
            leitor.convertidos,
            leitor.reciclados,
        )
    }

    /**
     * **O contra-teste: com o defeito de volta, a varredura reprova.**
     *
     * Um leitor que grava o frame antes de reconhecer é o defeito escrito à mão — a
     * forma mais provável de ele aparecer de verdade é alguém "salvando para
     * depurar". A **mesma** [exigirZeroArquivoNovo] do teste acima tem de estourar.
     *
     * Sem isto, a asserção de cima passaria também se a varredura tivesse sido
     * afrouxada até não enxergar nada — e ficaria verde para sempre.
     */
    @Test
    fun aVarreduraReprovaUmFrameGravadoDePROPOSITO() {
        val ocr = PlacaOcr()
        val real = OcrDeFrame(ocr)
        val pasta = File(ctx.cacheDir, "ocr-depuracao")

        // O defeito: guardar o frame "só para ver depois".
        val queGrava = LeitorDeFrame { frame ->
            pasta.mkdirs()
            File(pasta, "frame-${frame.timestampNanos}.yuv").writeBytes(frame.bytes)
            real.placaEm(frame)
        }

        val antes = arquivos()
        val r = capturar(listOf(frameI420(cenaComPlaca("ABC1D23"), 7)), queGrava)
        ocr.close()

        // A leitura continua funcionando — é por isso que o defeito passaria batido
        // num teste que só olhasse o resultado.
        assertTrue(r is LeituraDePlaca.Lida)

        val estouro = assertThrows(AssertionError::class.java) { exigirZeroArquivoNovo(antes) }
        assertTrue(
            "a varredura estourou por outro motivo: ${estouro.message}",
            estouro.message.orEmpty().contains("frame-7.yuv"),
        )

        pasta.deleteRecursively()
        // E, limpo o defeito, ela volta a aprovar — senão o teste seguinte herdaria
        // sujeira e falharia por motivo que não é o dele.
        exigirZeroArquivoNovo(antes)
    }

    /**
     * **A leitura só do plano Y não depende do layout de croma — e isto é medido.**
     *
     * `MediaCodec` pode devolver I420 (o `colorFormat 19` que o SDK pede), NV12, YV12
     * ou um formato flexível, e o `VideoFrame` do DAT não diz qual foi. Os dois
     * frames aqui têm **o mesmo plano Y e cromas diferentes**; se a conversão
     * dependesse de croma, um deles leria e o outro não.
     *
     * A asserção de que os bytes diferem é o que impede o teste de virar tautologia:
     * sem ela, dois frames idênticos passariam sempre.
     */
    @Test
    fun i420ENv12_produzemAMesmaLeitura() {
        val cena = cenaComPlaca("ABC1D23")
        val i420 = frameI420(cena, 1)
        val nv12 = frameNv12(cena, 1)

        assertNotEquals(
            "os dois frames têm o mesmo payload — o teste não testaria layout nenhum",
            i420.bytes.toList().takeLast(16),
            nv12.bytes.toList().takeLast(16),
        )

        val ocr = PlacaOcr()
        val a = capturar(listOf(i420), OcrDeFrame(ocr))
        val b = capturar(listOf(nv12), OcrDeFrame(ocr))
        ocr.close()

        assertEquals("I420 não leu", "ABC1D23", (a as LeituraDePlaca.Lida).placa)
        assertEquals("NV12 leu diferente de I420", a.placa, (b as LeituraDePlaca.Lida).placa)
    }

    /**
     * **Frame comprimido é recusado, e não vira placa.**
     *
     * `StreamConfiguration` tem `compressVideo` com padrão `false`, e é o padrão que
     * o nosso `toStreamConfiguration` usa — mas o dia em que alguém passar o terceiro
     * parâmetro, o payload vira NAL units de H.265. Alimentar o reconhecedor com
     * bitstream não produz erro: produz ruído. E ruído que passasse por
     * `PlacaValidator` seria uma placa **fabricada**.
     */
    @Test
    fun frameComprimido_naoEntraNoReconhecedor() {
        val ocr = PlacaOcr()
        val leitor = OcrDeFrame(ocr)
        val comprimido = frameI420(cenaComPlaca("ABC1D23"), 1).copy(comprimido = true)

        val r = capturar(listOf(comprimido), leitor)
        ocr.close()

        assertTrue("frame comprimido virou leitura de placa: $r", r is LeituraDePlaca.Ilegivel)
        assertEquals("o frame comprimido chegou a virar bitmap", 0, leitor.convertidos)
    }

    /**
     * **O custo do OCR, medido — não estimado.**
     *
     * O aceite da Fase 4 é ≤ 4 s do fim da fala até a resposta falada, e o ciclo de
     * voz em regime já gasta 945 ms disso. O que este teste publica no log é quanto
     * custa **um** frame, que é o que multiplica quando a placa não aparece de
     * primeira: com 5 s de janela, o número diz quantas tentativas cabem.
     */
    @Test
    fun custoDoOcrPorFrame() = runBlocking {
        val ocr = PlacaOcr()
        val leitor = OcrDeFrame(ocr)
        // **Sem isto o número "sem placa" pode ser o da imagem vazia.** Se o
        // reconhecedor não enxergar o letreiro, o custo medido é o de desistir cedo,
        // e não o de percorrer texto que não é placa — que é o caso operacional.
        val lidoNoLetreiro = ocr.lerTexto(FrameParaBitmap.luminancia(frameI420(cenaSemPlaca(), 0))!!)
        Log.i(TAG, "letreiro sem placa reconhecido como: ${lidoNoLetreiro.replace("\n", " | ")}")
        assertTrue(
            "o frame \"sem placa\" não tem texto reconhecível — o custo medido " +
                "abaixo seria o da imagem vazia, não o operacional",
            lidoNoLetreiro.isNotBlank(),
        )

        // **Conteúdo DIFERENTE a cada repetição, e isto não é capricho.** Medindo o
        // mesmo frame cinco vezes, a mediana caiu para 6–8 ms — número que não
        // sobrevive a nenhuma leitura honesta, porque em campo dois frames nunca são
        // idênticos. Com placas distintas o número volta para a ordem de grandeza da
        // primeira inferência, que é a que o agente paga.
        val tempos = mutableListOf<Long>()
        repeat(REPETICOES) { i ->
            val f = frameI420(cenaComPlaca("ABC${i}D2${i}"), i.toLong())
            val t = SystemClock.elapsedRealtime()
            leitor.placaEm(f)
            tempos += SystemClock.elapsedRealtime() - t
        }
        val vazios = mutableListOf<Long>()
        repeat(REPETICOES) { i ->
            val f = frameI420(cenaSemPlaca("RUA DAS FLORES", "${1000 + i} CENTRO"), 100L + i)
            val t = SystemClock.elapsedRealtime()
            leitor.placaEm(f)
            vazios += SystemClock.elapsedRealtime() - t
        }
        ocr.close()

        Log.i(
            TAG,
            "custo por frame ${largura}x$altura — com placa: ${tempos.sorted()} ms " +
                "(mediana ${tempos.sorted()[REPETICOES / 2]}), " +
                "sem placa: ${vazios.sorted()} ms " +
                "(mediana ${vazios.sorted()[REPETICOES / 2]}); " +
                "janela de ${CapturaDePlaca.JANELA_MS} ms comporta " +
                "~${CapturaDePlaca.JANELA_MS / vazios.sorted()[REPETICOES / 2].coerceAtLeast(1)} tentativas",
        )

        assertTrue(
            "um frame sozinho já não cabe na janela de ${CapturaDePlaca.JANELA_MS} ms",
            tempos.sorted()[REPETICOES / 2] < CapturaDePlaca.JANELA_MS,
        )
    }

    private companion object {
        const val TAG = "ClaryonField"
        const val REPETICOES = 5
    }
}
