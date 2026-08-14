package com.claryon.voice

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVoiceActivityDetectorTest {

    private val vad = EnergyVoiceActivityDetector(
        sampleRateHz = 16_000,
        energyThreshold = 500.0,
        hangoverMs = 60,   // 960 amostras
        minSpeechMs = 20,  // 320 amostras
    )

    private fun silence(samples: Int) = ShortArray(samples) // zeros
    private fun tone(samples: Int) = ShortArray(samples) { 4000 } // alta energia

    @Test
    fun detectaUmaJanelaDeFalaEntreSilencios() = runBlocking {
        // silêncio → fala (2560 amostras) → silêncio longo (fecha a janela)
        val frames = buildList {
            repeat(3) { add(silence(320)) }
            repeat(8) { add(tone(320)) }   // ~2560 amostras de fala
            repeat(6) { add(silence(320)) } // > hangover (960)
        }
        val segmentos = vad.segment(flowOf(*frames.toTypedArray())).toList()

        assertEquals("deve fechar exatamente uma janela", 1, segmentos.size)
        // a janela contém a fala + o hangover de silêncio anexado
        assertTrue("janela curta demais", segmentos[0].pcm.size >= 2560)
        assertEquals(16_000, segmentos[0].sampleRateHz)
    }

    @Test
    fun silencioPuroNaoEmiteNada() = runBlocking {
        val frames = List(10) { silence(320) }
        val segmentos = vad.segment(flowOf(*frames.toTypedArray())).toList()
        assertTrue(segmentos.isEmpty())
    }

    @Test
    fun ruidoSustentado_fechaPeloTeto_eNaoCresceSemLimite() = runBlocking {
        // Sirene/motor: energia sempre acima do limiar. Sem teto de duração a
        // janela nunca fecharia — o copiloto ficaria mudo e a memória estouraria.
        val comTeto = EnergyVoiceActivityDetector(
            sampleRateHz = 16_000,
            energyThreshold = 500.0,
            hangoverMs = 60,
            minSpeechMs = 20,
            maxSpeechMs = 100, // 1600 amostras
        )
        val frames = List(50) { tone(320) } // 16 000 amostras contínuas
        val segmentos = comTeto.segment(flowOf(*frames.toTypedArray())).toList()

        assertTrue("o teto deve fechar janelas mesmo sem silêncio", segmentos.isNotEmpty())
        assertTrue(
            "nenhuma janela pode passar do teto",
            segmentos.all { it.pcm.size <= 1600 + 320 },
        )
    }
}
