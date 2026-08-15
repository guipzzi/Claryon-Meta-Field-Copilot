package com.claryon.field

import android.util.Log
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
 * **Caos do aparelho: o que acontece quando o hardware some no meio.**
 *
 * O MockDeviceKit simula estados do dispositivo que, em campo, acontecem sem
 * aviso e no pior momento: o agente **tira os óculos** para enxugar o rosto,
 * **dobra as hastes** para guardá-los, ou a **bateria acaba**. Nenhum desses é
 * exótico — são o dia normal de quem usa o equipamento por oito horas.
 *
 * O que se verifica aqui não é que o SDK reage: é que o **nosso** código observa
 * a reação. Um app que segue transmitindo para óculos que já não estão no rosto
 * é pior que um app que para: o agente acha que foi ouvido.
 *
 * O MDK **não simula áudio** (confirmado por inspeção do artefato: há `camera`,
 * `captouch` e `permissions`, e nada de áudio). Então isto cobre sessão e
 * câmera; o caminho de voz depende de fone físico — ver
 * `docs/VERIFICACOES_COM_HARDWARE.md`.
 *
 * ## Como rodar
 *
 * `@Ignore` porque o decodificador de vídeo do MDK aborta o processo ao dividir
 * execução com outra classe. É filtro do JUnit e vale inclusive para execução por
 * classe — **remova a anotação** para rodar isolado.
 */
@Ignore("Aborta se dividir processo com outra classe (MediaCodec do MDK). Remova a anotação para rodar isolado.")
@RunWith(AndroidJUnit4::class)
class CaosDoAparelhoTest {

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

    /** Sobe registro → sessão → stream e devolve a fachada pronta. */
    private suspend fun comStreamAtivo(): DatGlassesFacade {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val m = MockDeviceController(context).also { mock = it }
        assertTrue("MDK deve parear", m.enableWithPhoneCameraFeed())

        val facade = DatGlassesFacade(s)
        withTimeout(15_000) { facade.registration.first { it == RegistrationStatus.REGISTERED } }
        facade.startSession()
        withTimeout(15_000) { facade.session.first { it == SessionStatus.STARTED } }
        facade.startCameraStream(CameraProfile.EVIDENCE)
        withTimeout(20_000) { facade.streamState.first { it == StreamStatus.STREAMING } }
        return facade
    }

    private suspend fun encerrarOrdenado(facade: DatGlassesFacade) {
        facade.stopCameraStream()
        runCatching {
            withTimeout(5_000) {
                facade.streamState.first { it == StreamStatus.STOPPED || it == StreamStatus.CLOSED }
            }
        }
    }

    @Test
    fun tirarOsOculosDoRosto_encerraOuPausaAOperacao(): Unit = runBlocking {
        // Cenário banal: o agente tira os óculos para enxugar o rosto. Se o app
        // seguir "transmitindo", ele acha que foi ouvido e não foi.
        val facade = comStreamAtivo()
        val streamAntes = facade.streamState.value
        val sessaoAntes = facade.session.value

        assertTrue("captouch/estado deve estar acessível", mock!!.tirarDoRosto())

        val mudou = withTimeoutOrNull(8_000) {
            facade.streamState.first { it != streamAntes }
        }
        delay(500)

        Log.w(
            "ClaryonField",
            "CAOS doff: stream $streamAntes → ${facade.streamState.value} · " +
                "sessão $sessaoAntes → ${facade.session.value} (mudou=${mudou != null})",
        )
        // ⚠️ MEDIDO (emulador, MDK 0.9.0): tirar do rosto NÃO muda nada. Dobrar e
        // desligar mudam; `doff` não. A doc explica: a sessão reage ao doff
        // "**when wear detection is enabled**" — e não há API para habilitar, é
        // ajuste do aparelho.
        //
        // Consequência a carregar: se o portador desligou a detecção de uso, os
        // óculos fora do rosto seguem com sessão e rota ativas, e o beamforming
        // que isola quem os veste deixa de valer. Um PTT apertado nessa condição
        // difunde a conversa ao redor. Mitigações que já existem: o PTT é
        // explícito, tem teto de 30 s, e o pré-roll nunca é persistido.
        //
        // A asserção fixa o comportamento MEDIDO. Se um SDK futuro passar a
        // notificar, este teste falha e a limitação sai da lista.
        assertTrue(
            "o comportamento mudou: `doff` agora altera o estado. Ótima notícia — " +
                "revise a limitação registrada em DECISIONS.md e no doc de hardware.",
            mudou == null && facade.session.value == sessaoAntes,
        )
        encerrarOrdenado(facade)
    }

    @Test
    fun dobrarAsHastes_encerraAOperacao(): Unit = runBlocking {
        // Guardar os óculos no bolso é o fim da operação, e o app precisa saber.
        val facade = comStreamAtivo()
        val antes = facade.streamState.value

        assertTrue(mock!!.dobrar())
        val mudou = withTimeoutOrNull(8_000) { facade.streamState.first { it != antes } }
        delay(500)

        Log.w("ClaryonField", "CAOS fold: stream $antes → ${facade.streamState.value} (mudou=${mudou != null})")
        assertTrue("dobrar as hastes tem de refletir no estado", mudou != null)
        encerrarOrdenado(facade)
    }

    @Test
    fun bateriaAcabando_naoDeixaOAppEmEstadoInconsistente(): Unit = runBlocking {
        // O pior caso: nenhum aviso, nenhuma chance de encerrar ordenado.
        val facade = comStreamAtivo()
        val antes = facade.streamState.value

        assertTrue(mock!!.desligar())
        val mudou = withTimeoutOrNull(8_000) { facade.streamState.first { it != antes } }
        delay(500)

        Log.w("ClaryonField", "CAOS powerOff: stream $antes → ${facade.streamState.value} (mudou=${mudou != null})")

        // O que importa não é PARA ONDE foi, e sim que o app não continue
        // reportando STREAMING com o aparelho desligado.
        assertTrue(
            "o app não pode continuar reportando STREAMING com o aparelho desligado",
            facade.streamState.value != StreamStatus.STREAMING || mudou != null,
        )
    }

    @Test
    fun tirarERecolocar_permiteRetomar(): Unit = runBlocking {
        // Tirar e recolocar é comum. Se o app não se recuperar, o agente fica
        // sem rádio pelo resto do turno sem entender por quê.
        val facade = comStreamAtivo()
        mock!!.tirarDoRosto()
        withTimeoutOrNull(8_000) { facade.streamState.first { it != StreamStatus.STREAMING } }

        mock!!.vestir()
        delay(1_000)

        val estado = facade.streamState.value
        Log.w("ClaryonField", "CAOS doff→don: stream terminou em $estado, sessão ${facade.session.value}")

        // Não afirmamos retomada automática — o SDK pode exigir sessão nova, e é
        // isso que precisamos DESCOBRIR aqui. O teste registra o comportamento
        // para o app saber se precisa recriar a sessão.
        // Como o `doff` é inerte (ver acima), a sessão nunca foi interrompida e
        // portanto não há o que retomar. Registrado para quando houver aparelho
        // real com detecção de uso ligada.
        assertTrue("a sessão deveria seguir coerente", estado == StreamStatus.STREAMING)
        encerrarOrdenado(facade)
    }
}
