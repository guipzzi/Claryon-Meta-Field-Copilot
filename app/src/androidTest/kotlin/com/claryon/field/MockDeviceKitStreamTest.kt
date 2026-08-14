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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Aceite do M2, sem hardware: com o MockDeviceKit, o ciclo
 * registro → sessão STARTED → stream STREAMING acontece de ponta a ponta.
 *
 * Roda como teste instrumentado (`connectedAndroidTest`) num emulador/dispositivo.
 * A câmera do celular/emulador é a fonte simulada dos frames.
 */
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

    @Test
    fun mockDevice_streamReachesStreaming() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mock = MockDeviceController(context)

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
        // com frames em voo. Depois cancela os coletores.
        facade.stopCameraStream()
        runCatching {
            withTimeout(5_000) {
                facade.streamState.first { it == StreamStatus.STOPPED || it == StreamStatus.CLOSED }
            }
        }
        scope.cancel()
        mock.disable()
    }
}
