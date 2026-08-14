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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Aceite do M2, sem hardware: com o MockDeviceKit, o ciclo
 * registro → sessão STARTED → stream STREAMING acontece de ponta a ponta.
 *
 * A câmera do celular/emulador é a fonte simulada dos frames.
 *
 * ## Por que está `@Ignore` na suíte
 *
 * O teste **passa quando é o único da execução** e **aborta o processo** sempre
 * que outra classe de teste participa da mesma execução:
 *
 * ```
 * Fatal signal 6 (SIGABRT)
 * Abort message: 'MediaCodec.cpp:1280 CHECK_EQ(mState, UNINITIALIZED) failed: 1 vs. 0'
 * ```
 *
 * O abort é do decodificador de vídeo **do próprio MockDeviceKit** (artefato de
 * debug do SDK em developer preview), não do código do produto: nenhum
 * `MediaCodec` é nosso. Reproduzido com `MockDeviceKitStreamTest` +
 * `PlacaOcrTest`, que não toca áudio nem vídeo — logo não é interferência de
 * um teste específico, e sim reentrância do pipeline nativo do mock no mesmo
 * processo.
 *
 * Fica fora da suíte para não deixar `connectedAndroidTest` vermelho por um
 * defeito de ferramenta. **`@Ignore` é filtro do JUnit e vale inclusive para
 * execução por classe** — para rodar, remova a anotação e execute isolado:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.MockDeviceKitStreamTest
 * ```
 *
 * Reavaliar a cada atualização do `mwdat-mockdevice`.
 */
@Ignore("Aborta se dividir processo com outra classe (MediaCodec do MDK). Remova a anotação para rodar isolado.")
@RunWith(AndroidJUnit4::class)
class MockDeviceKitStreamTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantRuntimePermissions() {
        val pkg = context.packageName
        val ua = instrumentation.uiAutomation
        ua.executeShellCommand("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        ua.executeShellCommand("pm grant $pkg android.permission.CAMERA")
    }

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null

    /**
     * Teardown em `@After` (e não no fim do `@Test`) para rodar **mesmo se o
     * teste falhar**: o processo é compartilhado pelos outros testes
     * instrumentados, e deixar o MDK habilitado contamina quem vier depois.
     */
    @After
    fun encerrarMock() {
        scope?.cancel()
        mock?.disable()
        scope = null
        mock = null
    }

    // O corpo termina em Unit de propósito: se a última expressão do runBlocking
    // devolvesse valor (ex.: o Result de um runCatching), o JUnit recusaria o
    // método com "should be void".
    @Test
    fun mockDevice_streamReachesStreaming(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this@MockDeviceKitStreamTest.scope = it }
        val mock = MockDeviceController(context).also { this@MockDeviceKitStreamTest.mock = it }

        // enable() reinicializa o SDK se preciso e leva o registro a REGISTERED.
        assertTrue("MockDeviceKit deve parear o Ray-Ban simulado", mock.enableWithPhoneCameraFeed())

        val facade = DatGlassesFacade(scope)

        withTimeout(15_000) { facade.registration.first { it == RegistrationStatus.REGISTERED } }

        facade.startSession()
        withTimeout(15_000) { facade.session.first { it == SessionStatus.STARTED } }

        facade.startCameraStream(CameraProfile.EVIDENCE)
        withTimeout(20_000) { facade.streamState.first { it == StreamStatus.STREAMING } }

        // Limpeza ordenada: para o stream e espera assentar ANTES de desabilitar o
        // mock — senão a thread nativa AsyncVideoFrame do MDK pode crashar (SIGSEGV)
        // com frames em voo. O cancelamento do escopo e o disable() ficam no
        // @After, para acontecerem mesmo se algum assert acima falhar.
        facade.stopCameraStream()
        runCatching {
            withTimeout(5_000) {
                facade.streamState.first { it == StreamStatus.STOPPED || it == StreamStatus.CLOSED }
            }
        }
    }
}
