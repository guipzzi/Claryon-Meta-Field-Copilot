package com.claryon.evidence

import org.junit.Assert.assertEquals
import org.junit.Test

class HashChainTest {

    private fun construir(segmentos: List<ByteArray>): List<String> {
        val hashes = ArrayList<String>()
        var anterior: String? = null
        for (seg in segmentos) {
            val h = HashChain.sha256Hex(seg, anterior)
            hashes.add(h)
            anterior = h
        }
        return hashes
    }

    @Test
    fun cadeiaIntegra_retornaMenosUm() {
        val segs = listOf("a".toByteArray(), "b".toByteArray(), "c".toByteArray())
        val hashes = construir(segs)
        assertEquals(-1, HashChain.verificar(segs, hashes))
    }

    @Test
    fun adulterarUmByte_apontaOSegmento() {
        val segs = arrayListOf("alpha".toByteArray(), "bravo".toByteArray(), "charlie".toByteArray())
        val hashes = construir(segs)
        // adultera 1 byte do segmento 1 (índice 1)
        segs[1] = "bravX".toByteArray()
        assertEquals("deve apontar o segmento 1", 1, HashChain.verificar(segs, hashes))
    }

    @Test
    fun segmentoFaltante_apontaOIndice() {
        val segs = listOf("x".toByteArray(), "y".toByteArray())
        val hashes = construir(segs).dropLast(1) // falta o hash do último
        assertEquals(1, HashChain.verificar(segs, hashes))
    }
}
