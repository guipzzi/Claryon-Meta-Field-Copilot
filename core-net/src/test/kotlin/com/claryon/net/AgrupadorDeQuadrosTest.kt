package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O aceite (d) da Fase 1 pede que a contagem de mensagens caia de ~50/s para
 * ~17/s. Estes testes travam as duas metades: o agrupamento acontece, e o fim da
 * fala não fica preso nele.
 */
class AgrupadorDeQuadrosTest {

    private fun quadro(seq: Int, ultimo: Boolean = false) =
        QuadroAudio("tx1", seq, 100L, ByteArray(30) { 1 }, ultimo = ultimo)

    @Test
    fun tresQuadros_viramUmaMensagem() {
        val a = AgrupadorDeQuadros(porMensagem = 3)
        assertNull("o 1º não fecha grupo", a.oferecer(quadro(0)))
        assertNull("o 2º não fecha grupo", a.oferecer(quadro(1)))

        val grupo = a.oferecer(quadro(2))
        assertEquals("o 3º fecha", 3, grupo?.size)
        assertEquals(listOf(0, 1, 2), grupo?.map { it.sequencia })
        assertEquals("o agrupador esvazia ao entregar", 0, a.pendente)
    }

    @Test
    fun aSequencia_continua_POR_QUADRO_e_e_isso_que_poupa_o_receptor() {
        // A decisão central: agrupar NÃO renumera. `BufferDeJitter` detecta perda
        // por lacuna de `sequencia`; se a numeração passasse a ser por mensagem,
        // jitter, PLC e detecção de perda mudariam de significado ao mesmo tempo.
        val a = AgrupadorDeQuadros(porMensagem = 3)
        val enviados = mutableListOf<Int>()
        for (i in 0 until 9) a.oferecer(quadro(i))?.let { g -> enviados += g.map { it.sequencia } }

        assertEquals((0 until 9).toList(), enviados)
    }

    @Test
    fun oQuadroUltimo_fechaGrupoIncompleto_eNaoPrendeAUltimaSilaba() {
        // Segurar o `ultimo` esperando companhia deixaria o receptor aguardando
        // uma fala que já terminou — e levaria junto os pendentes.
        val a = AgrupadorDeQuadros(porMensagem = 3)
        a.oferecer(quadro(0))
        val grupo = a.oferecer(quadro(1, ultimo = true))

        assertEquals("fecha com 2, não espera o 3º", 2, grupo?.size)
        assertTrue(grupo!!.last().ultimo)
        assertEquals(0, a.pendente)
    }

    @Test
    fun descarregar_entregaOResto_eDevolveNuloQuandoVazio() {
        val a = AgrupadorDeQuadros(porMensagem = 3)
        assertNull("nada pendente, nada a descarregar", a.descarregar())
        a.oferecer(quadro(0))
        assertEquals(1, a.descarregar()?.size)
        assertNull(a.descarregar())
    }

    @Test
    fun agrupamentoUm_naoAgrupa_eContinuaCorreto() {
        // Degradação segura: `porMensagem = 1` é o comportamento antigo.
        val a = AgrupadorDeQuadros(porMensagem = 1)
        assertEquals(1, a.oferecer(quadro(0))?.size)
        assertEquals(1, a.oferecer(quadro(1))?.size)
    }
}
