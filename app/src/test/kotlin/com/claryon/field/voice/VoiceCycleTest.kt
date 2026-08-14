package com.claryon.field.voice

import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.Prioridade
import com.claryon.voice.EnergyVoiceActivityDetector
import com.claryon.voice.PcmAudio
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica a ORQUESTRAÇÃO do ciclo de voz de ponta a ponta (com STT/TTS fakes;
 * whisper e Piper já são provados nos testes instrumentados). Confirma:
 * VAD fecha a janela → earcon dispara → roteia → responde lacônico → reproduz.
 */
class VoiceCycleTest {

    @Test
    fun cicloCompleto_pedirApoioComPrioridade() = runBlocking {
        // silêncio → fala (tom alto) → silêncio longo (fecha a janela do VAD)
        val frames = buildList {
            repeat(3) { add(ShortArray(320)) }
            repeat(8) { add(ShortArray(320) { 4000 }) }
            repeat(6) { add(ShortArray(320)) }
        }

        var earconDisparou = false
        var reproduzido: PcmAudio? = null

        val cycle = VoiceCycle(
            pcmInput = { flowOf(*frames.toTypedArray()) },
            vad = EnergyVoiceActivityDetector(16_000, 500.0, hangoverMs = 60, minSpeechMs = 20),
            sttFn = { _, _ -> "pedir apoio suspeito armado" }, // fake STT
            router = DeterministicIntentRouter(),
            ttsFn = { texto -> PcmAudio(ShortArray(texto.length * 10) { 100 }, 16_000) }, // fake TTS
            playFn = { reproduzido = it },
            onOuviVoce = { earconDisparou = true },
            sampleRateHz = 16_000,
        )

        val r = cycle.runOnce()

        assertTrue("earcon deve disparar no fechamento do VAD", earconDisparou)
        assertTrue("intenção esperada PedirApoio", r.intent is Intent.PedirApoio)
        assertEquals(Prioridade.EMERGENCIA, (r.intent as Intent.PedirApoio).prioridade)
        assertEquals("Apoio solicitado, guarnição avisada.", r.resposta)
        assertNotNull("a resposta deve ser reproduzida por voz", reproduzido)
    }
}
