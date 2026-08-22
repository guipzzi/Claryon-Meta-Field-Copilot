package com.claryon.field.oculos

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.Utterance
import com.claryon.field.vision.PlacaOcr
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESCARTÁVEL — auditoria de 22/08. Apaga depois de medir.
 *
 * Nenhum androidTest do repositório roda o caminho de placa pela câmera **com o
 * MockDeviceKit pareado**: `CaminhoDaPlacaEmProducaoTest` roda de propósito SEM ele
 * (espera `glasses.no_session`), e `FramesEfemerosTest` usa fachada de mentira com
 * frames sintéticos. Este preenche o buraco: DAT de verdade + `withCamera` de
 * verdade + ML Kit de verdade, com a câmera do emulador como fonte.
 *
 * Rodar ISOLADO — o decodificador do MDK aborta se dividir processo (ver
 * `MockDeviceKitStreamTest`).
 */
@RunWith(AndroidJUnit4::class)
class CapturaDePlacaComMdkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null

    @Before
    fun permissoes() {
        val pkg = context.packageName
        val ua = instrumentation.uiAutomation
        ua.executeShellCommand("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        ua.executeShellCommand("pm grant $pkg android.permission.CAMERA")
    }

    @After
    fun encerrar() {
        scope?.cancel()
        mock?.disable()
        scope = null
        mock = null
    }

    @Test
    fun capturaDePlaca_comMdkPareado(): Unit = runBlocking {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            .also { this@CapturaDePlacaComMdkTest.scope = it }
        val m = MockDeviceController(context).also { this@CapturaDePlacaComMdkTest.mock = it }

        assertTrue("MockDeviceKit não pareou o Ray-Ban simulado", m.enableWithPhoneCameraFeed())

        val facade = DatGlassesFacade(s)
        withTimeout(20_000) { facade.registration.first { it == RegistrationStatus.REGISTERED } }
        facade.startSession()
        withTimeout(20_000) { facade.session.first { it == SessionStatus.STARTED } }
        Log.i(TAG, "SCRATCH: sessão STARTED — vai abrir a câmera pelo caminho de placa")

        val ocr = PlacaOcr()
        val leitor = OcrDeFrame(ocr)
        val ditas = mutableListOf<Utterance>()

        val t0 = System.nanoTime()
        val r = CapturaDePlaca(
            facade = { facade },
            leitor = leitor,
            avisar = { ditas += it },
        ).ler()
        val gastoMs = (System.nanoTime() - t0) / 1_000_000
        ocr.close()

        Log.i(
            TAG,
            "SCRATCH MDK: resultado=$r · parede=${gastoMs}ms · " +
                "convertidos=${leitor.convertidos} reciclados=${leitor.reciclados} · " +
                "instrucao=${ditas.size} · JANELA_MS=${CapturaDePlaca.JANELA_MS}",
        )

        // Sem asserção sobre o CONTEÚDO: a câmera do emulador não tem placa nenhuma.
        // O que interessa é se o caminho entrega frame — e quantos.
        assertTrue("a instrução falada não saiu", ditas.isNotEmpty())
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
