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
        ).single()

        assertTrue(evento is EventoDeRede.Quadro)
        val recebido = (evento as EventoDeRede.Quadro).quadro
        assertEquals(original, recebido)
        assertTrue(payload.contentEquals(recebido.payload))
    }

    @Test
    fun ultimoQuadro_sobreviveAoTransporte() {
        // Sem esta marca o receptor espera para sempre por uma fala que acabou.
        val q = QuadroAudio("tx-1", 9, 0, ByteArray(0), ultimo = true)
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.quadro(tg, q, 1))).single()
        assertTrue((evento as EventoDeRede.Quadro).quadro.ultimo)
    }

    @Test
    fun anuncio_carregaIndicativoEPrioridade() {
        val a = AnuncioDeFala("tx-1", "Alfa Dois", prioridade = PrioridadeTransmissao.P1_EMERGENCIA)
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.anuncio(tg, a, 1))).single()
        assertEquals(EventoDeRede.Anuncio(a), evento)
    }

    @Test
    fun fim_ehReconhecido() {
        val evento = ProtocoloRealtime.interpretar(envelopar(ProtocoloRealtime.fim(tg, "tx-1", 1))).single()
        assertEquals(EventoDeRede.FimDeTransmissao("tx-1"), evento)
    }

    @Test
    fun mensagemMalformada_ehDescartada_naoDerrubaOCanal() {
        // Uma mensagem estranha não pode calar o rádio inteiro. `interpretar`
        // devolve LISTA desde que `fala.quadros` passou a trazer N quadros numa
        // mensagem só — descartar é lista vazia, não `null`. Este arquivo ficou
        // para trás nessa mudança e vinha falhando cinco de seis em `HEAD`.
        assertTrue(ProtocoloRealtime.interpretar("{").isEmpty())
        assertTrue(ProtocoloRealtime.interpretar("{\"topic\":\"x\"}").isEmpty())
        assertTrue(ProtocoloRealtime.interpretar(ProtocoloRealtime.heartbeat(1)).isEmpty())
        assertTrue(ProtocoloRealtime.interpretar(ProtocoloRealtime.join(tg, 1)).isEmpty())
    }

    @Test
    fun topicoIsolaTalkGroups() {
        assertTrue(ProtocoloRealtime.topico("gta-3") != ProtocoloRealtime.topico("gta-7"))
    }

    /**
     * O servidor reenvia o broadcast dentro de um envelope próprio. Reproduz esse
     * formato para o teste exercitar o caminho de LEITURA, não só o de escrita.
     */
    // ── JWT no canal ────────────────────────────────────────────────────────

    /**
     * **O contra-teste do defeito silencioso.**
     *
     * `access_token` é irmão de `config`, não filho. Posto dentro de `config` o
     * servidor **não reclama**: ele ignora, e o canal segue autorizado só pela
     * chave anon — que é exatamente o defeito que o token existe para fechar.
     * Sem esta asserção, a regressão passaria por todos os outros testes, pelo
     * build e pela demonstração, e só apareceria quando alguém com o APK entrasse
     * na guarnição de outro.
     */
    @Test
    fun oJoinLevaOTokenNoTopoDoPayload() {
        val payload = JSONObject(ProtocoloRealtime.join("g-1", 7, "jwt.do.agente"))
            .getJSONObject("payload")

        assertEquals("jwt.do.agente", payload.getString("access_token"))
        assertNull(
            "access_token dentro de config é ignorado pelo servidor, em silêncio",
            payload.getJSONObject("config").opt("access_token"),
        )
    }

    /** Sem sessão não se inventa campo: ausente é diferente de vazio. */
    @Test
    fun oJoinSemTokenNaoDeclaraOCampo() {
        val payload = JSONObject(ProtocoloRealtime.join("g-1", 7)).getJSONObject("payload")
        assertNull(payload.opt("access_token"))
        assertEquals(false, payload.getJSONObject("config").getBoolean("private"))
    }

    /** `private` chega ao servidor, que é o que faz a política 0012 ser consultada. */
    @Test
    fun oJoinPrivadoPedeAPoliticaDeLinha() {
        val payload = JSONObject(ProtocoloRealtime.join("g-1", 7, "jwt", privado = true))
            .getJSONObject("payload")
        assertEquals(true, payload.getJSONObject("config").getBoolean("private"))
    }

    /**
     * A renovação vai no tópico do canal e como evento `access_token` — não como
     * `broadcast`. Mandada como broadcast ela viraria mensagem para os outros
     * agentes em vez de renovar coisa nenhuma, e o canal cairia no vencimento
     * exatamente como se o laço não existisse.
     */
    @Test
    fun aRenovacaoUsaOEventoDedicadoNoTopicoDoCanal() {
        val envelope = JSONObject(ProtocoloRealtime.renovarToken("g-1", "jwt.novo", 9))
        assertEquals("access_token", envelope.getString("event"))
        assertEquals(ProtocoloRealtime.topico("g-1"), envelope.getString("topic"))
        assertEquals("jwt.novo", envelope.getJSONObject("payload").getString("access_token"))
    }

    // ── Recusa de canal ─────────────────────────────────────────────────────

    /**
     * **O teste que existiria e teria evitado 168 quadros no vazio.**
     *
     * O envelope abaixo é o que o servidor devolveu de verdade quando o par
     * headless tentou entrar num grupo alheio, copiado do log — não inventado.
     * `interpretar` procurava `payload.payload`, que o `phx_reply` não tem, e
     * devolvia lista vazia: a recusa era descartada na camada de protocolo e o
     * app seguia achando que tinha canal.
     */
    @Test
    fun aRecusaDoJoinViraEventoEmVezDeSumir() {
        val recusa = """
            {"event":"phx_reply","topic":"realtime:tg-999","ref":"1","payload":{
              "status":"error",
              "response":{"reason":"Unauthorized: You do not have permissions to read from this Channel topic: tg-999"}
            }}
        """.trimIndent()

        val eventos = ProtocoloRealtime.interpretar(recusa)
        assertEquals(1, eventos.size)
        val e = eventos.single()
        assertTrue("a recusa não virou CanalRecusado", e is EventoDeRede.CanalRecusado)
        assertTrue(
            "o motivo do servidor foi perdido — a tela não teria o que dizer",
            (e as EventoDeRede.CanalRecusado).motivo.contains("Unauthorized"),
        )
    }

    /** Aceite também é evento: é ele que autoriza o transporte a deixar transmitir. */
    @Test
    fun oAceiteDoJoinViraCanalPronto() {
        val ok = """{"event":"phx_reply","topic":"realtime:tg-1","ref":"1","payload":{"status":"ok","response":{}}}"""
        assertEquals(listOf(EventoDeRede.CanalPronto), ProtocoloRealtime.interpretar(ok))
    }

    /**
     * Token vencido não chega como `phx_reply` — chega como `system`. Sem este
     * ramo, a queda por expiração falharia do mesmo jeito silencioso de antes.
     */
    @Test
    fun oErroDeSistemaTambemDerrubaOCanal() {
        val sys = """{"event":"system","topic":"realtime:tg-1","payload":{"status":"error","message":"token has expired"}}"""
        val e = ProtocoloRealtime.interpretar(sys).single()
        assertTrue(e is EventoDeRede.CanalRecusado)
        assertTrue((e as EventoDeRede.CanalRecusado).motivo.contains("expired"))
    }

    private fun envelopar(enviado: String): String {
        val original = JSONObject(enviado)
        return JSONObject()
            .put("topic", original.getString("topic"))
            .put("event", "broadcast")
            .put("payload", original.getJSONObject("payload"))
            .toString()
    }
}
