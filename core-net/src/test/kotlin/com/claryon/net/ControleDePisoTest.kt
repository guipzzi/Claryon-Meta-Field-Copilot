package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControleDePisoTest {

    private val gta = "gta-3"
    private fun piso() = ControleDePiso(ttlMs = 30_000)

    private fun ControleDePiso.pedirDe(
        agente: String,
        prioridade: PrioridadeTransmissao = PrioridadeTransmissao.P2_APOIO,
        agora: Long = 0,
        tx: String = "tx-$agente",
    ) = pedir(gta, agente, tx, prioridade, agora)

    @Test
    fun canalLivre_concede() {
        val r = piso().pedirDe("alfa")
        assertTrue(r is ResultadoDoPedido.Concedido)
    }

    @Test
    fun canalOcupado_recusaComODetentor() {
        // O cliente traduz isto em tom de ocupado imediato e NÃO grava.
        val p = piso()
        p.pedirDe("alfa")
        val r = p.pedirDe("bravo")

        assertTrue(r is ResultadoDoPedido.Ocupado)
        assertEquals("alfa", (r as ResultadoDoPedido.Ocupado).detentor.agenteId)
    }

    @Test
    fun emergencia_tomaOCanalDeQuemFala() {
        val p = piso()
        p.pedirDe("alfa", PrioridadeTransmissao.P2_APOIO)
        val r = p.pedirDe("bravo", PrioridadeTransmissao.P1_EMERGENCIA)

        assertTrue("emergência não espera", r is ResultadoDoPedido.Tomado)
        r as ResultadoDoPedido.Tomado
        assertEquals("alfa", r.interrompido.agenteId)
        assertEquals("bravo", r.concessao.agenteId)
    }

    @Test
    fun emergencia_naoTomaDeOutraEmergencia() {
        // Cortar quem já está numa ocorrência em curso é pior que esperar; e duas
        // P1 se revezando indefinidamente calariam as duas.
        val p = piso()
        p.pedirDe("alfa", PrioridadeTransmissao.P1_EMERGENCIA)
        val r = p.pedirDe("bravo", PrioridadeTransmissao.P1_EMERGENCIA)

        assertTrue(r is ResultadoDoPedido.Ocupado)
    }

    @Test
    fun informativo_naoTomaDeNinguem() {
        val p = piso()
        p.pedirDe("alfa", PrioridadeTransmissao.P3_INFORMATIVO)
        assertTrue(p.pedirDe("bravo", PrioridadeTransmissao.P2_APOIO) is ResultadoDoPedido.Ocupado)
    }

    @Test
    fun mesmoAgentePedindoDeNovo_renovaEmVezDeSeAutobloquear() {
        // Retry por rede instável não pode fazer o agente receber "ocupado" por
        // causa de si mesmo.
        val p = piso()
        p.pedirDe("alfa", agora = 0)
        assertTrue(p.pedirDe("alfa", agora = 1_000) is ResultadoDoPedido.Concedido)
    }

    @Test
    fun ttlExpira_eOCanalVoltaAoGrupo() {
        // Se o cliente morre no meio (app fechado, bateria), a trava não pode
        // calar a guarnição para sempre.
        val p = piso()
        p.pedirDe("alfa", agora = 0)
        assertNull("depois do TTL o canal está livre", p.detentorVigente(gta, agoraMs = 30_001))
        assertTrue(p.pedirDe("bravo", agora = 30_001) is ResultadoDoPedido.Concedido)
    }

    @Test
    fun renovar_estendeEnquantoOAgenteFala() {
        val p = piso()
        val c = (p.pedirDe("alfa", agora = 0) as ResultadoDoPedido.Concedido).concessao

        assertTrue(p.renovar(c, agoraMs = 25_000))
        // Sem a renovação teria expirado aos 30 s; agora vai até 55 s.
        assertTrue(p.detentorVigente(gta, agoraMs = 40_000) != null)
    }

    @Test
    fun renovar_falhaSeOCanalFoiTomado() {
        // É o sinal para o emissor interrompido parar de transmitir.
        val p = piso()
        val c = (p.pedirDe("alfa", PrioridadeTransmissao.P2_APOIO) as ResultadoDoPedido.Concedido).concessao
        p.pedirDe("bravo", PrioridadeTransmissao.P1_EMERGENCIA)

        assertFalse(p.renovar(c, agoraMs = 1_000))
    }

    @Test
    fun liberar_soFuncionaParaODetentor() {
        val p = piso()
        val alfa = (p.pedirDe("alfa") as ResultadoDoPedido.Concedido).concessao
        p.pedirDe("bravo", PrioridadeTransmissao.P1_EMERGENCIA) // bravo toma

        // alfa soltando o PTT depois de ter perdido o canal não pode derrubar bravo.
        p.liberar(alfa)
        assertEquals("bravo", p.detentorVigente(gta, agoraMs = 1_000)?.agenteId)
    }

    @Test
    fun talkGroupsSaoIndependentes() {
        val p = piso()
        p.pedir("gta-3", "alfa", "tx1", PrioridadeTransmissao.P2_APOIO, 0)
        val outro = p.pedir("gta-7", "bravo", "tx2", PrioridadeTransmissao.P2_APOIO, 0)

        assertTrue("uma guarnição não pode calar a outra", outro is ResultadoDoPedido.Concedido)
    }
}
