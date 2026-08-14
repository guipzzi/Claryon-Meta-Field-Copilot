package com.claryon.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarconSynthesizerTest {

    @Test
    fun todosOsEarconsProduzemPcmNaoVazioENaoSilencioso() {
        for (earcon in Earcon.entries) {
            val pcm = EarconSynthesizer.render(earcon)
            assertTrue("${earcon.name} vazio", pcm.isNotEmpty())
            assertTrue("${earcon.name} silencioso", pcm.any { kotlin.math.abs(it.toInt()) > 1000 })
        }
    }

    @Test
    fun gravando_temCercaDe2Segundos() {
        val pcm = EarconSynthesizer.render(Earcon.GRAVANDO)
        // 2 s a 16 kHz = 32000 amostras
        assertEquals(32_000, pcm.size)
    }
}
