package com.claryon.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun upsample8para16_dobraOTamanho_ePreservaInicio() {
        val input = ShortArray(100) { (it * 100).toShort() }
        val out = PcmResampler.resampleLinear(input, 8_000, 16_000)
        assertEquals(200, out.size)
        assertEquals(input[0], out[0]) // âncora inicial preservada
    }

    @Test
    fun mesmaTaxa_retornaOMesmoArray() {
        val input = shortArrayOf(1, 2, 3)
        assertSame(input, PcmResampler.resampleLinear(input, 16_000, 16_000))
    }

    @Test
    fun interpolaEntreAmostras() {
        // 2 amostras a 8k → 4 a 16k; o valor do meio fica entre 0 e 1000.
        val out = PcmResampler.resampleLinear(shortArrayOf(0, 1000), 8_000, 16_000)
        assertEquals(4, out.size)
        assertTrue("interpolação esperada", out[1] in 1..999)
    }
}
