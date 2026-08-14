package com.claryon.field

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.audio.GlassesAudioRoute
import com.claryon.audio.RotaDeAudioPerdidaException
import com.claryon.common.Result
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Cenários de caos da rota de áudio.**
 *
 * A pré-condição de tipo existe para que capturar pelo microfone do celular seja
 * impossível. Estes testes exercitam as três formas de tentar burlá-la: sem
 * rota, com rota de desenvolvimento em release, e com rota que caiu entre o
 * roteamento e a captura.
 *
 * Roda no emulador, que **não tem SCO** — o que é justamente o cenário adverso
 * que interessa aqui.
 */
@RunWith(AndroidJUnit4::class)
class GlassesAudioRouteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var manager: GlassesAudioManagerImpl? = null

    @After
    fun soltarRota() {
        manager?.liberarTudo()
        manager = null
        runCatching { audioManager.clearCommunicationDevice() }
    }

    @Test
    fun semScoAtivo_rotaDeProducaoEhRecusada() {
        // Sem óculos nem fone HFP, `acquire` não pode devolver prova. É o que
        // impede a captura de cair no microfone omnidirecional do celular.
        val r = GlassesAudioRoute.acquire(audioManager)
        assertTrue(
            "sem TYPE_BLUETOOTH_SCO a rota de produção tem de falhar, e falhou com: $r",
            r is Result.Failure,
        )
    }

    @Test
    fun rotaDeDesenvolvimento_ehProibidaEmRelease() {
        // O guarda é de execução, não só de convenção: um APK de release
        // capturando pelo microfone do celular é exatamente o cenário que este
        // tipo existe para impedir.
        val r = GlassesAudioRoute.acquireParaDesenvolvimento(audioManager, buildDebug = false)
        assertTrue("rota de desenvolvimento não pode existir em release", r is Result.Failure)
    }

    /**
     * Queda do HFP entre o roteamento e a captura.
     *
     * **Só é reproduzível com fone Bluetooth físico**, e a razão é instrutiva:
     * a detecção compara o id do dispositivo provado com o id do dispositivo de
     * comunicação corrente. Num aparelho com SCO real, a queda troca o
     * dispositivo (SCO → embutido), os ids diferem e a captura é recusada. No
     * emulador não há SCO: a rota de desenvolvimento já aponta para o dispositivo
     * padrão, e "limpar" devolve **o mesmo** dispositivo — mesmo id, nada a
     * detectar. O cenário adverso simplesmente não existe aqui.
     *
     * Fica com `Assume` em vez de asserção falsa: um teste que passa por não ter
     * exercitado nada é pior que um teste ausente. Roda de verdade assim que
     * houver fone HFP (verificação V4 do cronograma).
     */
    @Test
    fun rotaQueCaiuEntreORoteamentoEACaptura_naoGravaPeloCelular() = runBlocking {
        val temSco = audioManager.availableCommunicationDevices
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        Assume.assumeTrue(
            "exige fone/óculos Bluetooth com HFP — sem SCO a queda de rota não é observável",
            temSco,
        )

        val mgr = GlassesAudioManagerImpl(context, allowFallbackToDefault = false)
            .also { manager = it }

        val prova = when (val r = mgr.iniciar()) {
            is Result.Success -> r.value
            is Result.Failure -> return@runBlocking
        }

        // Simula o HFP caindo: óculos dobrados, fone desligado, ligação entrando.
        mgr.liberar()
        audioManager.clearCommunicationDevice()

        val erro = runCatching { mgr.microfonePcm(prova).first() }.exceptionOrNull()
        assertTrue(
            "com a rota caída, a captura tem de falhar em vez de gravar pelo celular. Obtido: $erro",
            erro is RotaDeAudioPerdidaException,
        )
    }
}
