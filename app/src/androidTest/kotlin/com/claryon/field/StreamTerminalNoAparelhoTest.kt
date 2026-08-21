package com.claryon.field

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import com.claryon.glasses.StreamStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O stream que morre sozinho, e a câmera seguinte.**
 *
 * O item do roadmap: *"sem `camera.stop()` o próximo `addCamera` falha"*. Os dois
 * caminhos que o app controla — o `finally` de `withCamera` e `stopCameraStream()`
 * — já paravam a câmera. O que **não** era tratado é o stream que termina por
 * conta própria: o agente dobra as hastes e guarda os óculos no bolso no meio da
 * operação. Aí ninguém chama `stop()`, `activeStream` fica apontando para um
 * stream morto, e a leitura de placa seguinte devolve `glasses.stream_busy` —
 * "já existe stream ativo" — para sempre, até o app reiniciar.
 *
 * ## O que este teste NÃO isola — dito antes de alguém confiar demais nele
 *
 * **Medido nesta rodada:** dobrar as hastes derruba o stream *e a sessão*. A
 * segunda câmera volta `glasses.no_session`, não `glasses.stream_busy`. Como
 * `cleanupSession()` já soltava as referências antes deste marco, **este teste
 * passaria com o tratamento terminal removido** — ele mede a propriedade de ponta
 * a ponta ("dobrar não deixa a câmera presa"), não o mecanismo novo.
 *
 * A razão é limitação do simulador, e foi confirmada por `javap`:
 * `MockCameraKit` expõe **três** métodos — `setCameraFeed(Uri)`,
 * `setCameraFeed(CameraFacing)`, `setCapturedImage(Uri)` — e nada mais. Não há
 * injeção de `StreamError` nem como derrubar o stream mantendo a sessão viva. O
 * caso que o tratamento terminal existe para cobrir é justamente esse: em
 * hardware, `THERMAL_HOT` e `BATTERY_LOW` param a câmera **com a sessão de pé**.
 * Isso é `docs/VERIFICACOES_COM_HARDWARE.md`, não emulador.
 *
 * A decisão de quando parar — o miolo do conserto — é provada sem aparelho em
 * `VidaDoStreamTest`, com a trilha de estados medida aqui. E `erros` sai **vazio**
 * em toda rodada: o MDK não emite `StreamError` para gesto nenhum, então a coleta
 * do `errorStream` também só se prova com óculos reais.
 *
 * Streaming pelo MDK exige processo próprio:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.StreamTerminalNoAparelhoTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StreamTerminalNoAparelhoTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null

    @Before
    fun permissoes() {
        val ua = instrumentation.uiAutomation
        ua.executeShellCommand("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT")
        ua.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
    }

    @After
    fun encerrar() {
        scope?.cancel()
        mock?.disable()
        scope = null
        mock = null
    }

    @Test
    fun streamQueMorreSozinho_naoDeixaACameraPresa(): Unit = runBlocking {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val m = MockDeviceController(context).also { mock = it }
        assertTrue("MDK deve parear", m.enableWithPhoneCameraFeed())

        val facade = DatGlassesFacade(s)
        withTimeout(15_000) { facade.registration.first { it == RegistrationStatus.REGISTERED } }
        facade.startSession()
        withTimeout(15_000) { facade.session.first { it == SessionStatus.STARTED } }

        // Assinado antes de existir stream: `Stream.errorStream` tem `replay = 0`
        // no SDK, e erro emitido sem assinante vivo some. O `replay = 1` do nosso
        // `streamErrors` não salva o que o do SDK já descartou.
        val erros = mutableListOf<String>()
        s.launch { facade.streamErrors.collect { erros += it.code } }

        val trilha = mutableListOf<StreamStatus>()
        s.launch { facade.streamState.collect { trilha += it } }

        facade.startCameraStream(CameraProfile.EVIDENCE)
        val vivo = withTimeoutOrNull(25_000) {
            facade.streamState.first { it == StreamStatus.STREAMING }
        }
        Log.w(TAG, "TERMINAL: primeira câmera=$vivo · trilha=$trilha · erros=$erros")

        // O feed do MDK é o ponto frágil do simulador, não do produto: ele falha
        // por decodificador em estado sujo, de forma não determinística e sem
        // relação com o que se testa aqui. Sem stream vivo não há terminal para
        // observar, e afirmar qualquer coisa seria afirmar sobre nada.
        // `assumeTrue`, e não `assumeNotNull`: só o primeiro tem sobrecarga com
        // mensagem. `assumeNotNull("texto", vivo)` compila, mas o texto vira mais
        // um objeto do vararg — e a razão do pulo some do relatório.
        Assume.assumeTrue(
            "MDK não abriu o feed nesta rodada (limitação do simulador) — trilha=$trilha",
            vivo != null,
        )

        // ── O agente guarda os óculos no bolso ─────────────────────────────
        assertTrue("dobrar as hastes deve funcionar no MDK", m.dobrar())

        val terminou = withTimeoutOrNull(15_000) {
            facade.streamState.first { it == StreamStatus.STOPPED || it == StreamStatus.CLOSED }
        }
        Log.w(TAG, "TERMINAL: após dobrar, estado=$terminou · trilha=$trilha · erros=$erros")
        assertNotNull("dobrar não terminou o stream — premissa do teste caiu", terminou)

        // Tempo para o `Camera.stop()` do tratamento terminal correr.
        delay(1_500)

        // ── O que o item promete ───────────────────────────────────────────
        //
        // **Contra-teste.** Sem o tratamento terminal, `activeStream` continua
        // apontando para o stream morto e a guarda de "uma câmera por vez"
        // devolve `glasses.stream_busy` aqui — a leitura de placa seguinte
        // morre sem nunca tentar. Com ele, as referências foram soltas e a
        // chamada segue para o `addCamera`.
        m.desdobrar()
        delay(500)

        val r = withTimeoutOrNull(20_000) { facade.withCamera(CameraProfile.OCR) { } }
        val codigo = (r as? Result.Failure)?.error?.code
        Log.w(
            TAG,
            "TERMINAL: segunda câmera → $r (código=$codigo · sessão=${facade.session.value} · " +
                "erros=$erros)",
        )

        assertNotEquals(
            "a câmera anterior ficou presa: o stream morreu sozinho e ninguém a desanexou",
            "glasses.stream_busy",
            codigo,
        )
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
