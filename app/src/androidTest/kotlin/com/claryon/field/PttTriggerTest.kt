package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **V1 — O toque na haste serve como gatilho de PTT?**
 *
 * A documentação oficial afirma, em três lugares independentes, que o toque
 * capacitivo é *gesto de sistema* ligado ao ciclo de vida da sessão: com stream
 * ativo, `tap` alterna pausa/retomada e `tapAndHold` encerra. Se isso valer aqui,
 * usar a haste como PTT **derruba a própria transmissão** — o oposto do desejado.
 *
 * ## Medido em 2026-08-14 (emulador Android 15, mwdat 0.9.0)
 *
 * ```
 * stream STREAMING → PAUSED · sessão STARTED → PAUSED
 * ```
 *
 * **Um único toque pausou o stream E a sessão.** A hipótese de usar a haste como
 * PTT está descartada por medição, não por leitura: apertar para falar
 * interromperia a própria transmissão. O gatilho primário passa a ser o
 * long-press do botão de volume — que, de quebra, é mais rápido (o evento de
 * haste viaja por Bluetooth; o botão do celular é local).
 *
 * O teste virou **asserção de regressão**: se uma versão futura do SDK passar a
 * entregar o toque ao app sem mexer na sessão, ele falha e a decisão é revisitada.
 *
 * ## Como rodar
 *
 * Está fora da suíte porque o decodificador de vídeo do MockDeviceKit aborta o
 * processo quando outra classe de teste participa da mesma execução (mesmo
 * motivo de [MockDeviceKitStreamTest]). `@Ignore` é filtro do JUnit e vale
 * **inclusive** para execução por classe — para rodar, remova a anotação:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.PttTriggerTest
 * ```
 */
@Ignore("Aborta se dividir processo com outra classe (MediaCodec do MDK). Remova a anotação para rodar isolado.")
@RunWith(AndroidJUnit4::class)
class PttTriggerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null

    @Before
    fun grantRuntimePermissions() {
        val pkg = context.packageName
        val ua = instrumentation.uiAutomation
        ua.executeShellCommand("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        ua.executeShellCommand("pm grant $pkg android.permission.CAMERA")
    }

    @After
    fun encerrarMock() {
        scope?.cancel()
        mock?.disable()
        scope = null
        mock = null
    }

    @Test
    fun toqueNaHasteComStreamAtivo_oQueAcontece(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            .also { this@PttTriggerTest.scope = it }
        val mock = MockDeviceController(context).also { this@PttTriggerTest.mock = it }

        assertTrue("MockDeviceKit deve parear o Ray-Ban simulado", mock.enableWithPhoneCameraFeed())

        val facade = DatGlassesFacade(scope)
        withTimeout(15_000) { facade.registration.first { it == RegistrationStatus.REGISTERED } }
        facade.startSession()
        withTimeout(15_000) { facade.session.first { it == SessionStatus.STARTED } }
        facade.startCameraStream(CameraProfile.EVIDENCE)
        withTimeout(20_000) { facade.streamState.first { it == StreamStatus.STREAMING } }

        val estadoAntes = facade.streamState.value
        val sessaoAntes = facade.session.value

        assertTrue("captouch deve estar acessível no mock", mock.tap())

        // Se o toque for gesto de sistema, o stream sai de STREAMING em pouco
        // tempo. Se nada mudar, ele é inerte para o app — e a hipótese de usá-lo
        // como PTT sobrevive (ainda faltando um canal para RECEBER o evento).
        val mudou = withTimeoutOrNull(5_000) {
            facade.streamState.first { it != estadoAntes }
        }
        delay(500)

        val estadoDepois = facade.streamState.value
        val sessaoDepois = facade.session.value

        android.util.Log.w(
            "ClaryonField",
            "V1 toque na haste: stream $estadoAntes → $estadoDepois (mudou=${mudou != null}) · " +
                "sessão $sessaoAntes → $sessaoDepois",
        )

        // Asserção de regressão sobre o comportamento MEDIDO (ver KDoc).
        assertTrue(
            "O toque na haste deveria pausar o stream (medido em 0.9.0: STREAMING → PAUSED). " +
                "Observado: $estadoAntes → $estadoDepois. Se o SDK mudou e o toque agora é " +
                "inerte ou entregue ao app, revisite o gatilho de PTT em DECISIONS.md.",
            mudou != null && estadoDepois != estadoAntes,
        )

        facade.stopCameraStream()
        runCatching {
            withTimeout(5_000) {
                facade.streamState.first { it == StreamStatus.STOPPED || it == StreamStatus.CLOSED }
            }
        }
    }
}
