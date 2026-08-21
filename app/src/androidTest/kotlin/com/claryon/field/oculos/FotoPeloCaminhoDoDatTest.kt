package com.claryon.field.oculos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.FormatoDeFoto
import com.claryon.glasses.MockDeviceController
import com.claryon.glasses.PhotoData
import com.claryon.glasses.SessionStatus
import com.claryon.glasses.StreamStatus
import com.claryon.glasses.formatoDeImagem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **A foto capturada pelo caminho do DAT tem payload — e não deixa arquivo.**
 *
 * `DatGlassesFacade.capturePhoto()` devolvia
 * `Result.success(PhotoData(ByteArray(0), photo.toString()))`. Um teste que só
 * olhasse `isSuccess` ficaria verde com o defeito inteiro de volta, porque o
 * defeito **era** um sucesso — é o caso didático da pergunta 3 do §6. Por isso a
 * asserção mora em [exigirFotoDeVerdade], que olha o conteúdo, e existe
 * [aAsercaoReprovaOSucessoVazioDePROPOSITO] para provar que ela reprova o defeito.
 *
 * A imagem conhecida entra por `MockCameraKit.setCapturedImage(Uri)` — o caminho do
 * DAT, e não uma chamada direta à tradução. Confirmado por `javap` no
 * `mwdat-mockdevice-0.9.0` como o mock resolve a foto:
 *
 * ```
 * MediaStreamingWarpService.getPhotoCaptureData():
 *   if (cameraFacing != null) cameraStreamProvider.capturePhoto()?.let { return it }
 *   return captureFileUri?.let { readPhotoData(it) } ?: createDefaultPhotoData()
 * ```
 *
 * Ou seja, com a câmera do celular como feed, a imagem injetada é o **plano B** — o
 * mock só cai nela quando a câmera emulada não entrega foto. Os dois caminhos
 * servem à pergunta deste teste (payload de verdade, MIME de verdade), e quando o
 * plano B roda dá para exigir igualdade byte a byte, que é a asserção mais forte.
 *
 * ## Nada é persistido
 *
 * `filesDir`, `cacheDir` e `getExternalFilesDir` são varridos antes e depois,
 * exigindo **zero arquivo novo** — a mesma política de frame efêmero de
 * `specs/consulta-de-placa-por-camera.spec.md`, que vale igual para foto. A imagem
 * injetada é criada **antes** da varredura de referência, de propósito: ela é
 * insumo do teste, não subproduto da captura.
 */
@RunWith(AndroidJUnit4::class)
class FotoPeloCaminhoDoDatTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null
    private var injetada: File? = null

    @Before
    fun preparar() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in listOf("android.permission.CAMERA", "android.permission.BLUETOOTH_CONNECT")) {
            runCatching { automation.grantRuntimePermission(context.packageName, p) }
        }
    }

    @After
    fun limpar() {
        mock?.disable()
        mock = null
        injetada?.delete()
        injetada = null
        scope?.cancel()
        scope = null
    }

    // ── A asserção, que é o produto deste arquivo ─────────────────────────────

    /**
     * O que "captura bem-sucedida" significa. Quatro asserções, e nenhuma é
     * `isSuccess`: o defeito de origem satisfazia `isSuccess`.
     */
    private fun exigirFotoDeVerdade(foto: PhotoData) {
        assertTrue(
            "sucesso com ZERO bytes — é o defeito de origem de volta: " +
                "capturePhoto devolvia PhotoData(ByteArray(0), photo.toString())",
            foto.bytes.isNotEmpty(),
        )
        assertTrue(
            "o mimeType não é um MIME: \"${foto.mimeType}\" — o defeito de origem " +
                "punha aqui o toString() do objeto do SDK",
            foto.mimeType.startsWith("image/"),
        )
        assertTrue(
            "mimeType com cara de toString() de objeto: \"${foto.mimeType}\"",
            foto.mimeType in FormatoDeFoto.entries.map { it.mimeType },
        )
        assertEquals(
            "o mimeType não corresponde aos bytes — MIME por constante, não por conteúdo",
            foto.mimeType,
            formatoDeImagem(foto.bytes)?.mimeType,
        )
    }

    /**
     * **O contra-teste, e ele roda sem óculos e sem emulador de câmera.**
     *
     * Alimenta [exigirFotoDeVerdade] com exatamente o que `capturePhoto` devolvia
     * antes — `PhotoData(ByteArray(0), <toString do SDK>)` — e exige que a **mesma**
     * rotina estoure. Sem isto, a asserção do teste de cima passaria também se
     * tivesse sido afrouxada até cegar, e ficaria verde para sempre.
     */
    @Test
    fun aAsercaoReprovaOSucessoVazioDePROPOSITO() {
        // O `toString()` de `PhotoData$HEIC` é uma data class do Kotlin, então o
        // texto tem esta cara — e era ele que virava `mimeType`.
        val comoEraAntes = PhotoData(
            bytes = ByteArray(0),
            mimeType = "HEIC(data=java.nio.HeapByteBuffer[pos=0 lim=51234 cap=51234])",
        )
        val estouro = assertThrows(AssertionError::class.java) { exigirFotoDeVerdade(comoEraAntes) }
        assertTrue(
            "a asserção estourou por outro motivo: ${estouro.message}",
            estouro.message.orEmpty().contains("ZERO bytes"),
        )

        // E o meio-termo: bytes de verdade com MIME fixo, que é o outro jeito de a
        // mentira voltar — `image/heic` constante sobre um payload que é JPEG.
        val mimeFixo = PhotoData(bytes = pngConhecido(), mimeType = "image/heic")
        val segundo = assertThrows(AssertionError::class.java) { exigirFotoDeVerdade(mimeFixo) }
        assertTrue(
            "a asserção aceitou MIME que não corresponde aos bytes: ${segundo.message}",
            segundo.message.orEmpty().contains("não corresponde aos bytes"),
        )

        // E, com a tradução correta, ela aprova — senão o teste acima passaria por
        // ser impossível de satisfazer.
        exigirFotoDeVerdade(PhotoData(pngConhecido(), "image/png", orientacaoGraus = null))
    }

    // ── A captura pelo caminho do DAT ─────────────────────────────────────────

    /**
     * **Captura ponta a ponta pelo SDK com a câmera do celular como fonte.**
     *
     * O que é medido, e não presumido: se o mock chegou a `STREAMING`, se a captura
     * voltou com payload, qual MIME os bytes declaram e o que o payload declara de
     * orientação. O log carrega todos esses números — sem eles, um verde aqui não
     * distingue "funcionou" de "a bancada não respondeu".
     *
     * **Medido no emulador arm64 em 21/08:** `withCamera` devolve `no_frames` (a
     * câmera emulada não alimenta o feed de vídeo, como `CaosDoDatTest` já
     * registrava) **mas o stream chega a `STREAMING` e `capturePhoto` funciona** —
     * 10 435 bytes, `image/jpeg`, EXIF `Orientation = 6`, ou seja **90°**, sobre um
     * quadro 640×480 paisagem. Isto responde a pergunta da rotação com número, e
     * não com palpite: a foto **não** vem na orientação do stream (504×896
     * retrato), e quem ignorar o EXIF vê a imagem deitada. Vale para a câmera do
     * emulador; para os óculos, é linha de `docs/VERIFICACOES_COM_HARDWARE.md`.
     */
    @Test
    fun capturaPeloDat_temPayloadEMime_eNaoDeixaArquivo(): Unit = runBlocking {
        val png = pngConhecido()
        val arquivo = File(context.cacheDir, "foto-injetada.png").apply { writeBytes(png) }
        injetada = arquivo

        val m = MockDeviceController(context).also { mock = it }
        assertTrue("MDK não subiu", m.enableWithPhoneCameraFeed())
        assertTrue("injeção da foto conhecida falhou", m.definirFotoCapturada(Uri.fromFile(arquivo)))

        val f = DatGlassesFacade(scope!!)
        val sessao = f.startSession()
        Log.i(TAG, "sessão: $sessao")

        // A varredura de referência é tirada DEPOIS do arquivo injetado e depois de
        // a sessão subir: o insumo do teste e o que o SDK escreve ao inicializar não
        // são subproduto da captura, e confundi-los transformaria este teste num
        // detector de ruído.
        val antes = arquivos()

        var resultado: Result<PhotoData>? = null
        var chegouAStreaming = false
        val comCamera = withTimeoutOrNull(30_000) {
            f.withCamera(CameraProfile.EVIDENCE) { _ ->
                chegouAStreaming = withTimeoutOrNull(12_000) {
                    f.streamState.first { it == StreamStatus.STREAMING }
                } != null
                resultado = f.capturePhoto()
            }
        }

        val depois = arquivos()
        Log.i(
            TAG,
            "withCamera=$comCamera streaming=$chegouAStreaming captura=$resultado " +
                "arquivos novos=${(depois - antes).size}",
        )

        when (val r = resultado) {
            is Result.Success -> {
                val foto = r.value
                Log.i(
                    TAG,
                    "foto: ${foto.bytes.size} bytes, ${foto.mimeType}, " +
                        "orientação=${foto.orientacaoGraus ?: "não declarada"}, " +
                        "igual à injetada=${foto.bytes.contentEquals(png)}",
                )
                exigirFotoDeVerdade(foto)
                // Quando a câmera emulada não entrega foto, o mock cai no
                // `captureFileUri` — e aí dá para exigir o payload byte a byte, que
                // é a prova mais forte de que nada foi descartado no caminho.
                if (foto.bytes.contentEquals(png)) {
                    assertEquals("o payload injetado é PNG e o MIME saiu errado", "image/png", foto.mimeType)
                }
            }
            is Result.Failure -> {
                // **Falha é resultado aceitável aqui, sucesso vazio não é.** A
                // câmera do MDK não entrega frame neste emulador (medido em
                // `CaosDoDatTest`), e sem `STREAMING` o SDK devolve `NotStreaming`.
                // O que este teste proíbe é a terceira saída: `Success` com nada
                // dentro.
                Log.w(TAG, "captura falhou de forma tipada: ${r.error.code} — ${r.error.message}")
                assertTrue(
                    "falha sem código nosso: ${r.error.code}",
                    r.error.code.startsWith("glasses."),
                )
            }
            null -> throw AssertionError("capturePhoto ficou pendurada — nem sucesso nem falha")
        }

        exigirZeroArquivoNovo(antes, depois)
        // Sessão de verdade, para o teste não passar por não ter feito nada.
        assertEquals(
            "a sessão nem subiu — este teste não exercitou o caminho do DAT",
            SessionStatus.STARTED,
            withTimeoutOrNull(2_000) { f.session.first { it == SessionStatus.STARTED } },
        )
        f.stopSession()
    }

    // ── Varredura de disco, no padrão de FramesEfemerosTest ───────────────────

    private fun arquivos(): Set<String> =
        listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
            .flatMap { raiz -> raiz.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList() }
            .toSet()

    private fun exigirZeroArquivoNovo(antes: Set<String>, depois: Set<String>) {
        val novos = (depois - antes).sorted()
        assertEquals(
            "a captura de foto deixou arquivo no aparelho:\n" +
                novos.joinToString("\n") { "  $it" } +
                "\n\nFoto de captura é insumo efêmero, como o frame de abordagem e o " +
                "pré-roll do PTT: vive em RAM e morre depois do uso. Se precisar virar " +
                "evidência, é outro caminho — EncryptedFile + Keystore com manifesto de " +
                "custódia —, nunca subproduto silencioso. Ver CLAUDE.md §2 e " +
                "specs/consulta-de-placa-por-camera.spec.md.",
            emptyList<String>(),
            novos,
        )
    }

    // ── A imagem conhecida ────────────────────────────────────────────────────

    /**
     * PNG de verdade, gerado pelo codificador do Android — e **não** bytes mágicos
     * fabricados: a imagem tem de atravessar `ContentResolver.openInputStream` e o
     * transporte em pedaços do mock (`sendPhotoInChunks`), que é o caminho real.
     * PNG porque é o formato que a própria documentação da Meta injeta em
     * `setCapturedImage`, e porque assim o MIME que sai da tradução (`image/png`)
     * prova que ele veio dos bytes: o ramo do SDK chama-se `HEIC`.
     */
    private fun pngConhecido(): ByteArray {
        val bmp = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 24, 30))
        canvas.drawRect(20f, 20f, 140f, 76f, Paint().apply { color = Color.rgb(240, 240, 235) })
        val saida = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, saida)
        bmp.recycle()
        return saida.toByteArray()
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
