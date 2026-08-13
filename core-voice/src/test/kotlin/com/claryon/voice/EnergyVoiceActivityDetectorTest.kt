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
}
