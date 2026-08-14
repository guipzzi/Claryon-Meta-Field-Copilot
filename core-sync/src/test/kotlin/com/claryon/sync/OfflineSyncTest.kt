package com.claryon.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: String, payload: String = "{}") =
        OutboxItem(id = id, type = "ev", payload = payload, createdAtEpochMillis = 100L)

    private fun msg(situacao: String) = TacticalMessage(
        recipientType = RecipientType.INDIVIDUAL,
        recipient = "COPOM",
        agentId = "007",
        vehiclePrefix = "GTA-3",
        location = null, latitude = null, longitude = null,
        situation = situacao,
        priority = "ALTA",
        evidenceStatus = null,
    )

    @Test
    fun fila_persisteEmDisco_eSobreviveANovaInstancia() {
        val dir = tmp.newFolder("outbox")
        val a = FileOutbox(dir)
        a.enqueue(item("1", """{"s":"linha1\nlinha2"}"""))
        a.enqueue(item("2"))
        assertEquals(2, a.size())

        // Nova instância lendo o mesmo diretório (simula reinício do processo).
        val b = FileOutbox(dir)
        val pend = b.list()
        assertEquals(2, pend.size)
        assertEquals("1", pend[0].item.id) // FIFO
        assertEquals("""{"s":"linha1\nlinha2"}""", pend[0].item.payload) // payload íntegro
    }

    @Test
    fun drenar_esvaziaQuandoOnline_ePreservaQuandoOffline() = runTest {
        val outbox = FileOutbox(tmp.newFolder("outbox"))
        outbox.enqueue(item("1")); outbox.enqueue(item("2")); outbox.enqueue(item("3"))
        val gateway = FakeSyncGateway(online = false)
        val drainer = OutboxDrainer(outbox, gateway)

        // Offline: nada sai, tudo permanece, tentativa incrementada no 1º.
        val r1 = drainer.drenar()
        assertEquals(0, r1.enviados)
        assertEquals(3, outbox.size())

        // Volta a rede: drena tudo em ordem.
        gateway.online = true
        val r2 = drainer.drenar()
        assertEquals(3, r2.enviados)
        assertEquals(0, outbox.size())
        assertEquals(listOf("1", "2", "3"), gateway.recebidos.map { it.id })
    }

    @Test
    fun itemVeneno_eDescartadoAposMaxTentativas() = runTest {
        val outbox = FileOutbox(tmp.newFolder("outbox"))
        outbox.enqueue(item("x"))
        val gateway = FakeSyncGateway(online = false)
        val drainer = OutboxDrainer(outbox, gateway, maxTentativas = 3)

        repeat(2) { drainer.drenar() }
        assertEquals("ainda na fila antes do limite", 1, outbox.size())
        val r = drainer.drenar() // 3ª tentativa atinge o limite
        assertEquals(1, r.descartados)
        assertEquals(0, outbox.size())
    }

    @Test
    fun despacho_offline_enfileira_eNaoMenteQueEnviou() = runTest {
        val outbox = FileOutbox(tmp.newFolder("outbox"))
        val gateway = FakeSyncGateway(online = false)
        val dispatcher = TacticalDispatcher(
            outbox, gateway,
            novoId = { "msg-${it.agentId}" },
            agora = { 100L },
        )

        val resultado = dispatcher.despachar(msg("apoio suspeito armado"))
        assertTrue("offline deve enfileirar", resultado is Despacho.Enfileirada)
        assertEquals(1, outbox.size())
        assertTrue("não deve ter enviado nada", gateway.recebidos.isEmpty())
    }

    @Test
    fun despacho_online_envia_eNaoEnfileira() = runTest {
        val outbox = FileOutbox(tmp.newFolder("outbox"))
        val gateway = FakeSyncGateway(online = true)
        val dispatcher = TacticalDispatcher(
            outbox, gateway,
            novoId = { "msg-${it.agentId}" },
            agora = { 100L },
        )

        val resultado = dispatcher.despachar(msg("apoio"))
        assertTrue(resultado is Despacho.Enviada)
        assertEquals("msg-007", (resultado as Despacho.Enviada).id)
        assertEquals(0, outbox.size())
        assertFalse(gateway.recebidos.isEmpty())
    }
}
