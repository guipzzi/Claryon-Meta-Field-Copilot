package com.claryon.field.ui

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **O relógio da tela de auditoria.**
 *
 * `quem_me_consultou()` devolve `timestamptz` e o PostgREST serializa em UTC. A
 * primeira versão desta tela mostrava o texto cru: em Brasília, **três horas à
 * frente**. Eu conferi no aparelho, li "18:20", e dei por verificada — o número
 * estava lá, era plausível, e estava errado. Confirmado depois no banco: o bruto
 * era `18:24+00` e o local, `15:24`.
 *
 * Numa tela de auditoria isso não é cosmético. Quem vê "consultaram você às 18:20"
 * e sabe que às 18:20 estava fora de serviço conclui coisa errada sobre um colega,
 * a partir de um registro que o produto apresentou como fato.
 */
class InstanteDaConsultaTest {

    private val brasilia = ZoneId.of("America/Sao_Paulo")

    @Test
    fun oInstanteEmUTC_viraHoraLOCAL() {
        assertEquals(
            "20/08 15:24",
            instanteLocalDaConsulta("2026-08-20T18:24:09.242008+00:00", brasilia),
        )
    }

    /**
     * **O contra-teste.** Se a função devolvesse o texto cru — que é o defeito —,
     * ela daria "18:24" e este par de asserções não distinguiria nada.
     */
    @Test
    fun aHoraExibidaDIFERE_doTextoCru_quandoOFusoDifere() {
        val cru = "2026-08-20T18:24:09.242008+00:00"
        val exibida = instanteLocalDaConsulta(cru, brasilia)
        assertNotEquals(
            "a função devolveu a hora de UTC: é exatamente o defeito que ela conserta",
            "18:24",
            exibida.takeLast(5),
        )
        assertEquals("15:24", exibida.takeLast(5))
    }

    /** Fuso do aparelho igual ao do servidor: nada muda, e não pode quebrar. */
    @Test
    fun emUTC_aHoraEAMesma() {
        assertEquals(
            "20/08 18:24",
            instanteLocalDaConsulta("2026-08-20T18:24:09+00:00", ZoneId.of("UTC")),
        )
    }

    /**
     * Formato inesperado mostra o cru em vez de sumir com a linha. Uma consulta
     * omitida do registro de auditoria é pior que uma com data feia.
     */
    @Test
    fun formatoEstranho_naoDerruba_eNaoEscondeAConsulta() {
        val saida = instanteLocalDaConsulta("isto não é uma data", brasilia)
        // `take(16)` do fallback, contado: são 16 caracteres, não 18. Errei a conta
        // ao escrever a expectativa — e é por isso que ela é conferida, não estimada.
        assertEquals("isto não é uma d", saida)
    }

    @Test
    fun aVirada_deDiaEUmaHoraAntes_noHorarioDeBrasilia() {
        // 01:30 UTC do dia 21 é 22:30 do dia 20 em Brasília.
        assertEquals(
            "20/08 22:30",
            instanteLocalDaConsulta("2026-08-21T01:30:00+00:00", brasilia),
        )
    }
}
