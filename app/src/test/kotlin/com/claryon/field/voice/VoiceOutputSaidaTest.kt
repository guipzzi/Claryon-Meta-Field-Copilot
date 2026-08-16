package com.claryon.field.voice

import com.claryon.agent.Utterance
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.sound.EarconSynthesizer
import com.claryon.voice.PcmAudio
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que estes testes protegem: a **saída de fala** do produto passa por uma
 * conversão de taxa (Piper a 22.050 Hz → barramento a 16.000 Hz) que degrada em
 * silêncio. Sem anti-aliasing o áudio continua saindo, com a duração certa e sem
 * exceção nenhuma — só sujo. Nenhum assert de tamanho de array pega isso, e num
 * produto sem display ninguém tem outra forma de perceber senão ouvindo.
 *
 * Testam pela porta pública: entra [Utterance], sai PCM no `reproduzir`. É o
 * mesmo caminho do produto, fila de prioridade incluída.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOutputSaidaTest {

    /** PCM entregue ao "AudioTrack", com a taxa que o acompanhou. */
    private class Saida {
        val pcm = mutableListOf<ShortArray>()
        val taxas = mutableListOf<Int>()
    }

    /**
     * Escopo próprio, e **não** o `backgroundScope` do `runTest`: verificado neste
     * projeto que o laço da [com.claryon.sound.PrioritySoundQueue] não chega a
     * rodar no background scope nem após `advanceUntilIdle` — o teste passaria a
     * medir zero reprodução e não a qualidade do áudio.
     */
    private fun TestScope.comSaida(
        sintetizar: suspend (String) -> PcmAudio?,
        bloco: (VoiceOutput, Saida) -> Unit,
    ) {
        val saida = Saida()
        val despachante = UnconfinedTestDispatcher(testScheduler)
        val escopo = CoroutineScope(despachante + Job())
        try {
            bloco(
                VoiceOutput(
                    scope = escopo,
                    sintetizar = sintetizar,
                    reproduzir = { pcm, taxa -> saida.pcm += pcm; saida.taxas += taxa },
                    // Sem isto o filtro salta para o pool real e o teste mede
                    // zero reprodução em vez da qualidade do áudio.
                    dispatcherDeAudio = despachante,
                ),
                saida,
            )
        } finally {
            escopo.coroutineContext[Job]?.cancel()
        }
    }

    private fun tom(freqHz: Double, fs: Int, amostras: Int) =
        ShortArray(amostras) { (16_000.0 * sin(2.0 * PI * freqHz * it / fs)).toInt().toShort() }

    private fun rms(x: ShortArray): Double {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v.toDouble()
        return sqrt(acc / x.size)
    }

    @Test
    fun oBarramentoEOsEarconsCompartilhamATaxa() {
        // Trava estática do mesmo invariante que o `init` de VoiceOutput cobra em
        // runtime. Aqui a divergência aparece no CI; lá, na construção do app.
        assertEquals(EarconSynthesizer.SAMPLE_RATE_HZ, VoiceOutput.TAXA_SAIDA_HZ)
    }

    @Test
    fun falaDoPiperA22kChegaAoAudioTrackSemDobramento() = runTest {
        // 9,5 kHz está acima da Nyquist de saída (8 kHz). Sem filtro ele não
        // some: reaparece em 6,5 kHz, dentro da banda de voz.
        comSaida({ PcmAudio(tom(9_500.0, 22_050, 11_025), 22_050) }) { voz, saida ->
            voz.emitir(Utterance.Falar("teste", Priority.RESPOSTA))
            advanceUntilIdle()

            assertEquals(1, saida.pcm.size)
            assertEquals(VoiceOutput.TAXA_SAIDA_HZ, saida.taxas.single())
            // Sem filtro esse tom chega com RMS ~6.800 (medido). Com filtro, ~25.
            assertTrue(
                "alias chegou ao AudioTrack: RMS=${rms(saida.pcm.single())}",
                rms(saida.pcm.single()) < 500.0,
            )
        }
    }

    @Test
    fun aBandaDeVozAtravessaIntacta() = runTest {
        // O par do teste acima: um filtro que emudecesse tudo passaria naquele.
        comSaida({ PcmAudio(tom(1_000.0, 22_050, 11_025), 22_050) }) { voz, saida ->
            voz.emitir(Utterance.Falar("teste", Priority.RESPOSTA))
            advanceUntilIdle()

            assertTrue(
                "1 kHz deveria sair cheio; RMS=${rms(saida.pcm.single())}",
                rms(saida.pcm.single()) > 10_000.0,
            )
        }
    }

    @Test
    fun earconNaoPassaPelaReamostragem() = runTest {
        // O filtro é do caminho de fala. Se um dia o ramo `Sound.Tone` começar a
        // reamostrar, os oito sinais mudam de timbre sem ninguém perceber — e o
        // vocabulário de earcons é o que substitui a tela neste produto.
        comSaida({ null }) { voz, saida ->
            voz.emitir(Utterance.Sinalizar(Earcon.PRIORITARIA, Priority.EMERGENCIA))
            advanceUntilIdle()

            assertArrayEquals(EarconSynthesizer.render(Earcon.PRIORITARIA), saida.pcm.single())
        }
    }
}
