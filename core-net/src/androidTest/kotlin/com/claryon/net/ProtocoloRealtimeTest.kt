package com.claryon.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O protocolo é a parte mais provável de divergir de um serviço em evolução.
 * Estes testes fixam o que **nós** produzimos e garantem a propriedade que
 * importa: ida e volta sem perder um byte do áudio.
 *
 * Roda instrumentado porque `org.json` só existe de verdade no Android.
 */
@RunWith(AndroidJUnit4::class)
class ProtocoloRealtimeTest {

    private val tg = "gta-3"

    @Test
    fun quadro_idaEVolta_preservaOsBytesDoAudio() {
        // Um byte trocado no áudio vira estalo no ouvido de quem está numa
        // ocorrência. O round-trip tem de ser exato, inclusive com bytes altos.
        val payload = ByteArray(120) { (it * 7 - 128).toByte() }
        val original = QuadroAudio("tx-1", sequencia = 42, capturadoEmMs = 123456, payload = payload)

        val evento = ProtocoloRealtime.interpretar(
            envelopar(ProtocoloRealtime.quadro(tg, original, ref = 1)),
        )

        assertTrue(evento is EventoDeRede.Quadro)
        val recebido = (evento as EventoDeRede.Quadro).quadro
        assertEquals(original, recebido)
        assertTrue(payload.contentEquals(recebido.payload))
    }

    @Test
    fun ultimoQuadro_sobreviveAoTransporte() {
        // Sem esta marca o receptor espera para sempre por uma fala que acabou.
        val q = QuadroAudio("tx-1", 9, 0, ByteArray(0), ultimo = true)
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.quadro(tg, q, 1)))
        assertTrue((evento as EventoDeRede.Quadro).quadro.ultimo)
    }

    @Test
    fun anuncio_carregaIndicativoEPrioridade() {
        val a = AnuncioDeFala("tx-1", "Alfa Dois", PrioridadeTransmissao.P1_EMERGENCIA)
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.anuncio(tg, a, 1)))
        assertEquals(EventoDeRede.Anuncio(a), evento)
    }

    @Test
    fun fim_ehReconhecido() {
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.fim(tg, "tx-1", 1)))
        assertEquals(EventoDeRede.FimDeTransmissao("tx-1"), evento)
    }

    @Test
    fun mensagemMalformada_ehDescartada_naoDerrubaOCanal() {
        // Uma mensagem estranha não pode calar o rádio inteiro.
        assertNull(ProtocoloRealtime.interpretar("{"))
        assertNull(ProtocoloRealtime.interpretar("{\"topic\":\"x\"}"))
        assertNull(ProtocoloRealtime.interpretar(ProtocoloRealtime.heartbeat(1)))
        assertNull(ProtocoloRealtime.interpretar(ProtocoloRealtime.join(tg, 1)))
    }

    @Test
    fun topicoIsolaTalkGroups() {
        assertTrue(ProtocoloRealtime.topico("gta-3") != ProtocoloRealtime.topico("gta-7"))
    }

    /**
     * O servidor reenvia o broadcast dentro de um envelope próprio. Reproduz esse
     * formato para o teste exercitar o caminho de LEITURA, não só o de escrita.
     */
    private fun envelopar(enviado: String): String {
        val original = JSONObject(enviado)
        return JSONObject()
            .put("topic", original.getString("topic"))
            .put("event", "broadcast")
            .put("payload", original.getJSONObject("payload"))
            .toString()
    }
}
