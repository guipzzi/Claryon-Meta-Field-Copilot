package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticaDeReconexaoTest {

    @Test
    fun cresceExponencialmente() {
        val p = PoliticaDeReconexao(baseMs = 500, tetoMs = 60_000)
        assertEquals(500, p.proximoAtrasoMs())
        assertEquals(1_000, p.proximoAtrasoMs())
        assertEquals(2_000, p.proximoAtrasoMs())
        assertEquals(4_000, p.proximoAtrasoMs())
    }

    @Test
    fun respeitaOTeto() {
        // Reconectar em laço numa zona sem sinal drena a bateria mais rápido que
        // qualquer outra coisa do produto.
        val p = PoliticaDeReconexao(baseMs = 500, tetoMs = 5 * 60 * 1000)
        repeat(40) { p.proximoAtrasoMs() }
        assertEquals(5 * 60 * 1000, p.proximoAtrasoMs())
    }

    @Test
    fun conectar_recomecaDoInicio() {
        val p = PoliticaDeReconexao(baseMs = 500, tetoMs = 60_000)
        repeat(5) { p.proximoAtrasoMs() }
        p.aoConectar()
        assertEquals(500, p.proximoAtrasoMs())
        assertEquals(0, PoliticaDeReconexao().tentativasSeguidas)
    }

    @Test
    fun redeVoltou_retomaEhImediata_naoEsperaOTeto() {
        // Sair de um elevador ou subsolo não pode custar 5 minutos de silêncio.
        val p = PoliticaDeReconexao(baseMs = 500, tetoMs = 5 * 60 * 1000)
        repeat(30) { p.proximoAtrasoMs() }
        assertTrue("já estava no teto", p.proximoAtrasoMs() >= 5 * 60 * 1000)

        p.aoDetectarRedeDisponivel()
        assertEquals(500, p.proximoAtrasoMs())
    }
}
