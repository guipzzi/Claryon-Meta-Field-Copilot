package com.claryon.field.vision

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.PlacaValidator
import com.claryon.agent.Utterance
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.claryon.field.oculos.CapturaDePlaca
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/** O que a cadeia inteira decidiu sobre uma imagem. */
private enum class Desfecho {
    /** O `PlacaValidator` devolveu **exatamente** a placa que a imagem tem. */
    ACERTOU,

    /**
     * O `PlacaValidator` devolveu `null` — nada foi consultado. Em imagem com placa é
     * perda de capacidade; em negativo é o desfecho **certo**.
     */
    RECUSOU,

    /**
     * O `PlacaValidator` devolveu uma placa **diferente** da que a imagem tem, ou
     * fabricou uma onde não havia placa nenhuma. É o único desfecho que faz o sistema
     * consultar a base sobre o veículo errado, e por isso é o que o teste proíbe.
     */
    ERROU,
}

private data class Medicao(
    val condicao: String,
    val grupo: String,
    val esperada: String?,
    val textoCru: String,
    val decisao: String?,
    val frames: Int,
    val latenciaMs: Long,
) {
    val desfecho: Desfecho
        get() = when {
            decisao == null -> Desfecho.RECUSOU
            decisao == esperada -> Desfecho.ACERTOU
            else -> Desfecho.ERROU
        }

    /** Uma linha da tabela do relatório. O texto cru vai numa linha só, com `¶`. */
    fun linha(): String = "%-38s %-10s %-8s %-8s %-9s %5d ms  cru=[%s]".format(
        condicao,
        grupo,
        esperada ?: "—",
        decisao ?: "—",
        desfecho,
        latenciaMs,
        textoCru.replace("\n", " ¶ ").take(90),
    )
}

/** A câmera de mentira: entrega os frames dados e nada mais. */
private class FachadaDeUmFrame(private val frames: List<Frame>) : GlassesFacade {
    override val registration: StateFlow<RegistrationStatus> =
        MutableStateFlow(RegistrationStatus.REGISTERED)
    override val session: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STARTED)
    override suspend fun ensureRegistered() = Result.success(Unit)
    override suspend fun startSession() = Result.success(Unit)
    override suspend fun capturePhoto(): Result<PhotoData> =
        Result.failure(ClaryonError.Glasses("glasses.no_stream", "não usado aqui"))

    override suspend fun withCamera(
        config: CameraProfile,
        block: suspend (Flow<Frame>) -> Unit,
    ): Result<Unit> {
        var entregues = 0
        block(flow { frames.forEach { entregues++; emit(it) } })
        return if (entregues > 0) {
            Result.success(Unit)
        } else {
            Result.failure(ClaryonError.Glasses("glasses.no_frames", "sem imagem"))
        }
    }
}

/**
 * **Quanto o OCR de placa acerta, quanto recusa e — sobretudo — quanto ERRA, em 31
 * imagens que imitam condição de viatura.**
 *
 * ## O que este teste mede, e o que ele recusa medir
 *
 * A cadeia inteira, do jeito que `CopilotoDoAgente` a chama:
 * `CapturaDePlaca` → `OcrDeFrame` → `FrameParaBitmap` → `PlacaOcr` (ML Kit) →
 * `PlacaValidator`. **Não** é o OCR isolado, e a diferença é o ponto: quem decide o
 * que vira consulta à base veicular é o `PlacaValidator`, no fim da fila. Medir o
 * reconhecedor sem o portão diria quanto texto o ML Kit lê — número interessante e
 * operacionalmente irrelevante.
 *
 * ## A métrica que manda é [Desfecho.ERROU], não a taxa de acerto
 *
 * Placa não lida custa ao agente cinco segundos e um "não consegui ler". Placa lida
 * **errada** manda a viatura consultar outro veículo — e a resposta volta com a mesma
 * confiança da certa. Por isso a asserção dura aqui é `ERROU == 0`, e a taxa de acerto
 * é relatório.
 *
 * ## Por que existe uma asserção de acerto mínimo
 *
 * Uma suíte que só exigisse `ERROU == 0` ficaria **verde com o OCR desligado**: nada
 * lido, nada errado. [oControleNitido_eLidoCorretamente] é o antídoto — se as três
 * imagens de controle deixarem de ser lidas, o corpus ou a cadeia quebraram, e o zero
 * de erros deixa de significar qualquer coisa.
 *
 * ## §6 pergunta 3 — o corpo prova o que o nome afirma?
 *
 * [oValidatorAfrouxado_fabricaPlacaQueAEstritaRecusou] é o contra-teste: pega os
 * **mesmos textos crus** e passa por duas versões afrouxadas do extrator — uma que
 * aceita 6 caracteres, outra que troca a regra de token por janela deslizante. Se o
 * afrouxamento não produzir nenhuma placa errada, o corpus é fácil demais e a asserção
 * de cima não estava provando nada; o teste falha e diz isso.
 */
@RunWith(AndroidJUnit4::class)
class OcrDePlacaEmCondicoesDeCampoTest {

    // ── O relatório por imagem, e os agregados ────────────────────────────────

    @Test
    fun oOcrDeCampo_naoAceitaNenhumaPlacaErrada() {
        val medicoes = medicoes()

        Log.i(TAG, "$MARCA ═══ ${medicoes.size} imagens · cadeia real · ${CorpusDePlacasSinteticas.fonteResolvida()}")
        Log.i(
            TAG,
            "$MARCA %-38s %-10s %-8s %-8s %-9s %8s".format(
                "condição", "grupo", "esperada", "decisão", "desfecho", "latência",
            ),
        )
        medicoes.forEach { Log.i(TAG, "$MARCA ${it.linha()}") }

        // ── taxa de acerto por grupo ──────────────────────────────────────────
        Log.i(TAG, "$MARCA ─── acerto por grupo ───")
        medicoes.groupBy { it.grupo }.toSortedMap().forEach { (grupo, itens) ->
            val acertou = itens.count { it.desfecho == Desfecho.ACERTOU }
            val recusou = itens.count { it.desfecho == Desfecho.RECUSOU }
            val errou = itens.count { it.desfecho == Desfecho.ERROU }
            val alvo = if (grupo == "negativo") "recusa" else "acerto"
            val certos = if (grupo == "negativo") recusou else acertou
            Log.i(
                TAG,
                "$MARCA %-12s %d/%d %s (%3.0f%%)  ·  acertou=%d recusou=%d ERROU=%d".format(
                    grupo, certos, itens.size, alvo, 100.0 * certos / itens.size,
                    acertou, recusou, errou,
                ),
            )
        }

        // ── latência ──────────────────────────────────────────────────────────
        val comPlaca = medicoes.filter { it.esperada != null }.map { it.latenciaMs }
        Log.i(
            TAG,
            "$MARCA ─── latência da cadeia por imagem (1 frame) ─── " +
                "n=${medicoes.size} p50=${percentil(medicoes.map { it.latenciaMs }, 50)} ms " +
                "p95=${percentil(medicoes.map { it.latenciaMs }, 95)} ms " +
                "mín=${medicoes.minOf { it.latenciaMs }} máx=${medicoes.maxOf { it.latenciaMs }} · " +
                "só imagens com placa: p50=${percentil(comPlaca, 50)} p95=${percentil(comPlaca, 95)} ms",
        )
        Log.i(TAG, "$MARCA todas as latências ordenadas: ${medicoes.map { it.latenciaMs }.sorted()}")

        // ── a asserção que manda ──────────────────────────────────────────────
        val errados = medicoes.filter { it.desfecho == Desfecho.ERROU }
        Log.i(
            TAG,
            "$MARCA ═══ PLACAS ERRADAS ACEITAS: ${errados.size} de ${medicoes.size} " +
                "(fabricadas em negativo: ${errados.count { it.esperada == null }})",
        )
        assertEquals(
            "O `PlacaValidator` aceitou placa que a imagem NÃO tem — a consulta iria " +
                "à base sobre outro veículo, com a mesma confiança de um acerto:\n" +
                errados.joinToString("\n") { "  ${it.linha()}" },
            emptyList<String>(),
            errados.map { it.condicao },
        )
    }

    /**
     * **O antídoto contra o zero vazio.**
     *
     * `ERROU == 0` é satisfeito por um OCR que não lê nada. Estas três imagens são
     * nítidas e frontais; se elas pararem de ser lidas, não é o OCR que ficou seguro,
     * é a cadeia que quebrou — e o teste acima precisa deixar de valer.
     */
    @Test
    fun oControleNitido_eLidoCorretamente() {
        val controle = medicoes().filter { it.grupo == "controle" }
        assertEquals("o corpus de controle mudou de tamanho", 3, controle.size)
        controle.forEach {
            assertEquals(
                "imagem nítida e frontal não foi lida — a cadeia OCR→PlacaValidator " +
                    "quebrou, e `naoAceitaNenhumaPlacaErrada` passou a ser vácuo. " +
                    "Cru lido: [${it.textoCru.replace("\n", " ¶ ")}]",
                it.esperada,
                it.decisao,
            )
        }
    }

    /**
     * **Contra-teste: com o portão afrouxado, o MESMO corpus produz placa errada.**
     *
     * `CLAUDE.md` §6 pergunta 3 — *"o corpo do teste prova o que o NOME dele afirma?"*.
     * `naoAceitaNenhumaPlacaErrada` só significa alguma coisa se a rigidez do
     * `PlacaValidator` for o que segura o zero. Aqui os **mesmos textos crus**, sem
     * OCR novo, passam por duas versões afrouxadas do extrator:
     *
     *  - [extrairAceitandoSeisCaracteres] — o afrouxamento pedido: um caractere comido
     *    por barro, reflexo ou arrasto vira placa "válida" curta.
     *  - [extrairPorJanelaDeslizante] — a regra de token trocada por varredura no texto
     *    inteiro. É o defeito que o KDoc de `PlacaValidator.extrair` diz ter existido:
     *    `"código ABC12345"` produzia `ABC1234`.
     *
     * Se **nenhuma** das duas fabricar placa, o corpus não tem nenhuma imagem difícil
     * o bastante, e a asserção do outro teste estava passando por sorte.
     */
    @Test
    fun oValidatorAfrouxado_fabricaPlacaQueAEstritaRecusou() {
        val medicoes = medicoes()

        data class Fabricada(val condicao: String, val regra: String, val placa: String, val esperada: String?)

        val fabricadas = medicoes.flatMap { m ->
            listOf(
                "6 caracteres" to extrairAceitandoSeisCaracteres(m.textoCru),
                "janela deslizante" to extrairPorJanelaDeslizante(m.textoCru),
            ).mapNotNull { (regra, frouxa) ->
                // Só conta o que a versão ESTRITA não entregou: o dano do afrouxamento
                // é a placa nova, não a que já estava certa.
                if (frouxa != null && frouxa != m.decisao && frouxa != m.esperada) {
                    Fabricada(m.condicao, regra, frouxa, m.esperada)
                } else {
                    null
                }
            }
        }

        Log.i(TAG, "$MARCA ─── contra-teste: o que o afrouxamento aceitaria ───")
        fabricadas.forEach {
            Log.i(
                TAG,
                "$MARCA AFROUXADO[${it.regra}] ${it.condicao}: consultaria ${it.placa} " +
                    "(placa real: ${it.esperada ?: "NENHUMA — imagem sem placa"}) · " +
                    "a estrita recusou",
            )
        }

        assertTrue(
            "O afrouxamento do `PlacaValidator` não produziu NENHUMA placa errada neste " +
                "corpus. Então `oOcrDeCampo_naoAceitaNenhumaPlacaErrada` não está sendo " +
                "sustentado pela rigidez do portão — está sendo sustentado por sorte, e " +
                "continuaria verde com o portão aberto. O corpus precisa de imagem mais " +
                "degradada, ou o portão já foi afrouxado sem ninguém notar.",
            fabricadas.isNotEmpty(),
        )

        // E a estrita recusou todas elas — é isto que separa "o portão segurou" de
        // "o OCR não leu nada".
        val seguradasPeloPortao = fabricadas.filter { f ->
            medicoes.first { it.condicao == f.condicao }.decisao == null
        }
        assertTrue(
            "nenhuma das ${fabricadas.size} placas fabricadas foi barrada pelo portão " +
                "estrito — o mérito não é do `PlacaValidator`",
            seguradasPeloPortao.isNotEmpty(),
        )
        Log.i(
            TAG,
            "$MARCA ═══ o afrouxamento fabricaria ${fabricadas.size} consulta(s) errada(s); " +
                "${seguradasPeloPortao.size} delas em imagem que a regra estrita recusou por inteiro",
        )
    }

    // ── As versões afrouxadas — cópias fiéis com UMA regra mexida cada ────────

    /**
     * `PlacaValidator.extrair` com a checagem de comprimento trocada de `!= 7` para
     * `!in 6..7`, e os padrões correspondentes de 6 caracteres acrescentados. **Nada
     * mais muda.**
     */
    private fun extrairAceitandoSeisCaracteres(texto: String): String? {
        val curtaAntiga = Regex("[A-Z]{3}[0-9]{3}")
        val curtaMercosul = Regex("[A-Z]{3}[0-9][A-Z][0-9]")
        val tokens = texto.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        fun candidato(bruto: String): String? {
            val p = PlacaValidator.normalizar(bruto)
            if (p.length !in 6..7) return null
            if (p.length == 7) return if (PlacaValidator.isValida(p)) p else null
            return if (curtaAntiga.matches(p) || curtaMercosul.matches(p)) p else null
        }
        for (i in tokens.indices) {
            candidato(tokens[i])?.let { return it }
            if (i + 1 < tokens.size) candidato(tokens[i] + tokens[i + 1])?.let { return it }
        }
        return null
    }

    /**
     * `PlacaValidator.extrair` sem a regra de token: janela de 7 sobre o texto inteiro
     * normalizado. É o comportamento anterior descrito no KDoc do próprio validador.
     */
    private fun extrairPorJanelaDeslizante(texto: String): String? {
        val tudo = PlacaValidator.normalizar(texto)
        for (i in 0..(tudo.length - 7)) {
            val janela = tudo.substring(i, i + 7)
            if (PlacaValidator.isValida(janela)) return janela
        }
        return null
    }

    // ── Medição ───────────────────────────────────────────────────────────────

    private fun medicoes(): List<Medicao> = cache ?: medirCorpusInteiro().also { cache = it }

    private fun percentil(valores: List<Long>, p: Int): Long {
        if (valores.isEmpty()) return -1
        val ordenado = valores.sorted()
        // Posto mais próximo (nearest-rank): com 31 amostras não há interpolação que
        // signifique alguma coisa, e inventar uma daria falsa precisão.
        val indice = (ceil(p / 100.0 * ordenado.size).toInt() - 1).coerceIn(0, ordenado.lastIndex)
        return ordenado[indice]
    }

    /**
     * Roda o corpus inteiro pela cadeia real, uma vez por processo.
     *
     * O cache existe porque os três testes desta classe leem a **mesma** corrida: o
     * contra-teste precisa dos textos crus que o teste de acerto produziu, e medir
     * duas vezes daria dois corpus com latências diferentes — o relatório passaria a
     * depender de qual `@Test` o runner escolheu primeiro.
     */
    private fun medirCorpusInteiro(): List<Medicao> = runBlocking {
        val ocr = PlacaOcr()
        val leitor = OcrDeFrame(ocr)

        // **O ML Kit carrega o modelo Latin na PRIMEIRA inferência, e isso foi MEDIDO
        // em vez de presumido.** Com um único aquecimento, as duas primeiras imagens
        // do corpus custaram 90 ms e 135 ms contra 6–12 ms de todas as outras — o p95
        // publicado seria o custo de subir o reconhecedor, não o de ler placa. Três
        // aquecimentos com conteúdo diferente resolvem, e o custo da primeira vai ao
        // log como número próprio: em produção `PlacaPelaCamera.leitor` é `lazy` de
        // processo, então ele é pago UMA vez por turno, não por consulta.
        val cenas = CorpusDePlacasSinteticas.corpus()
        val aquecimento = listOf(0, 1, cenas.lastIndex).mapIndexed { n, i ->
            val bmp = FrameParaBitmap.luminancia(CorpusDePlacasSinteticas.frameDe(cenas[i], -1L - n))!!
            val t = System.nanoTime()
            ocr.lerTexto(bmp)
            ((System.nanoTime() - t) / 1_000_000).also { bmp.recycle() }
        }
        Log.i(
            TAG,
            "$MARCA primeira inferência do processo (carga do modelo Latin): ${aquecimento[0]} ms; " +
                "as duas seguintes: ${aquecimento.drop(1)} ms. A tabela abaixo é REGIME.",
        )

        val medidas = cenas.mapIndexed { i, cena ->
            val frame = CorpusDePlacasSinteticas.frameDe(cena, (i + 1).toLong())
            despejarSePedido(frame, cena.condicao, i + 1)

            val ditas = mutableListOf<Utterance>()
            val t0 = System.nanoTime()
            val r = CapturaDePlaca(
                facade = { FachadaDeUmFrame(listOf(frame)) },
                leitor = leitor,
                avisar = { ditas += it },
            ).ler()
            val latencia = (System.nanoTime() - t0) / 1_000_000

            assertEquals(
                "a instrução falada não saiu em ${cena.condicao}",
                listOf(CapturaDePlaca.INSTRUCAO),
                ditas,
            )

            // Diagnóstico, FORA da medição: a coluna "texto cru" custa uma inferência
            // extra, e contá-la inflaria a latência publicada em ~100%.
            val cru = FrameParaBitmap.luminancia(frame)!!.let { bmp ->
                ocr.lerTexto(bmp).also { bmp.recycle() }
            }

            Medicao(
                condicao = cena.condicao,
                grupo = cena.grupo,
                esperada = cena.esperada,
                textoCru = cru,
                decisao = (r as? LeituraDePlaca.Lida)?.placa,
                frames = when (r) {
                    is LeituraDePlaca.Lida -> r.frames
                    is LeituraDePlaca.Ilegivel -> r.frames
                    is LeituraDePlaca.SemCamera -> 0
                },
                latenciaMs = latencia,
            )
        }
        ocr.close()

        // O contrato de efemeridade continua valendo aqui: 31 frames convertidos, 31
        // reciclados. Um bitmap por ler é memória nativa esperando o GC.
        assertEquals(
            "bitmap convertido e não reciclado durante a medição",
            leitor.convertidos,
            leitor.reciclados,
        )
        medidas
    }

    /**
     * Grava a imagem **como o ML Kit a recebeu** (plano Y, já com ruído), para inspeção
     * visual — e só quando alguém pede.
     *
     * Desligado por padrão, e por dois motivos: um teste que grava arquivo em toda
     * execução é exatamente o que `FramesEfemerosTest` existe para proibir, e imagem
     * de placa no disco é o oposto do contrato de efemeridade. Quando ligado, escreve
     * sob o **APK de teste** (`com.claryon.field.test`), que não é o app e não é
     * varrido por aquele teste.
     *
     * O diretório externo do APK de teste **pode não existir** — medido: neste
     * emulador `getExternalFilesDir` devolveu `null`, e um `File(null, "…")` vira
     * caminho RELATIVO, que estourou `ENOENT` no meio da medição. Daí a cadeia de
     * alternativas e o `mkdirs()` conferido: um despejo de diagnóstico não pode
     * derrubar a medida que ele existe para ilustrar.
     *
     * `./gradlew :app:connectedDebugAndroidTest
     *   -Pandroid.testInstrumentationRunnerArguments.despejarCorpus=1`
     */
    private fun despejarSePedido(frame: Frame, condicao: String, indice: Int) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("despejarCorpus") != "1") return
        val instr = InstrumentationRegistry.getInstrumentation()
        val raiz = instr.context.getExternalFilesDir(null)
            ?: instr.targetContext.getExternalFilesDir(null)
            ?: instr.context.cacheDir
        val pasta = File(raiz, "corpus-placas")
        if (!pasta.isDirectory && !pasta.mkdirs()) {
            Log.w(TAG, "$MARCA despejo impossível em ${pasta.absolutePath}")
            return
        }
        val bmp = FrameParaBitmap.luminancia(frame) ?: return
        val destino = File(pasta, "%02d-%s.png".format(indice, condicao))
        destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        Log.i(TAG, "$MARCA despejo: ${destino.absolutePath}")
    }

    private companion object {
        const val TAG = "ClaryonField"

        /** Prefixo estável para `adb logcat | grep` — o relatório é longo. */
        const val MARCA = "OCR-CAMPO"

        /** A corrida do corpus, compartilhada pelos três testes da classe. */
        var cache: List<Medicao>? = null
    }
}
