package com.claryon.net

import org.json.JSONObject
import java.util.Base64

/**
 * Construção e leitura das mensagens do **Supabase Realtime** (protocolo de
 * canais sobre WebSocket).
 *
 * Isolado do transporte de propósito: o formato de fio de um serviço em evolução
 * é a parte mais provável de mudar, e concentrá-la aqui faz a correção ser de um
 * arquivo. É também a única parte testável sem rede.
 *
 * ✅ **Confirmado contra projeto real** (2026-08-14): o envelope de canais do
 * Phoenix e os nomes de evento abaixo funcionam como estão — `join` aceito e
 * broadcast entregue entre dois clientes, com o payload intacto.
 *
 * ## Por que base64
 *
 * O canal transporta JSON, então o payload Opus binário vai codificado. Base64
 * infla 33%, o que **seria** proibitivo no modelo de arquivo — mas a 12 kbps um
 * agrupamento de 60 ms tem ~90 bytes crus e ~120 codificados. É irrelevante.
 *
 * Usa `java.util.Base64` (JDK) e não `android.util.Base64`: o do JDK existe desde
 * a API 26 (nosso mínimo é 31) e funciona também fora do Android, o que mantém
 * esta camada verificável sem emulador quando o JSON não estiver no caminho.
 */
object ProtocoloRealtime {

    const val EVENTO_ANUNCIO = "fala.anuncio"
    const val EVENTO_QUADRO = "fala.quadro"
    const val EVENTO_FIM = "fala.fim"

    fun topico(talkGroupId: String): String = "realtime:tg-$talkGroupId"

    /** Entrada no canal do talk group. */
    fun join(talkGroupId: String, ref: Int): String = envelope(
        topico = topico(talkGroupId),
        evento = "phx_join",
        payload = JSONObject().put("config", JSONObject().put("broadcast", JSONObject().put("ack", false))),
        ref = ref,
    )

    /**
     * Heartbeat. Sem ele o servidor derruba o canal por inatividade — e a queda
     * só apareceria no próximo toque no PTT, que é o pior momento possível.
     */
    fun heartbeat(ref: Int): String =
        envelope("phoenix", "heartbeat", JSONObject(), ref)

    fun anuncio(talkGroupId: String, a: AnuncioDeFala, ref: Int): String = broadcast(
        talkGroupId, EVENTO_ANUNCIO, ref,
        JSONObject()
            .put("transmissaoId", a.transmissaoId)
            .put("indicativo", a.autorIndicativo)
            .put("prioridade", a.prioridade.name),
    )

    fun quadro(talkGroupId: String, q: QuadroAudio, ref: Int): String = broadcast(
        talkGroupId, EVENTO_QUADRO, ref,
        JSONObject()
            .put("transmissaoId", q.transmissaoId)
            .put("seq", q.sequencia)
            .put("t", q.capturadoEmMs)
            .put("ultimo", q.ultimo)
            .put("opus", Base64.getEncoder().encodeToString(q.payload)),
    )

    fun fim(talkGroupId: String, transmissaoId: String, ref: Int): String = broadcast(
        talkGroupId, EVENTO_FIM, ref,
        JSONObject().put("transmissaoId", transmissaoId),
    )

    /**
     * Traduz uma mensagem recebida em [EventoDeRede], ou `null` se for ruído do
     * protocolo (respostas de join, heartbeat, presença).
     *
     * **Nunca lança:** uma mensagem malformada não pode derrubar o canal de voz
     * inteiro. Mensagem que não entendemos é descartada, e o rádio segue.
     */
    fun interpretar(texto: String): EventoDeRede? = runCatching {
        val raiz = JSONObject(texto)
        val payload = raiz.optJSONObject("payload") ?: return null
        val evento = payload.optString("event").ifEmpty { raiz.optString("event") }
        val dados = payload.optJSONObject("payload") ?: return null

        when (evento) {
            EVENTO_ANUNCIO -> EventoDeRede.Anuncio(
                AnuncioDeFala(
                    transmissaoId = dados.getString("transmissaoId"),
                    autorIndicativo = dados.optString("indicativo"),
                    prioridade = runCatching {
                        PrioridadeTransmissao.valueOf(dados.optString("prioridade"))
                    }.getOrDefault(PrioridadeTransmissao.P2_APOIO),
                ),
            )

            EVENTO_QUADRO -> EventoDeRede.Quadro(
                QuadroAudio(
                    transmissaoId = dados.getString("transmissaoId"),
                    sequencia = dados.getInt("seq"),
                    capturadoEmMs = dados.optLong("t"),
                    payload = Base64.getDecoder().decode(dados.optString("opus")),
                    ultimo = dados.optBoolean("ultimo", false),
                ),
            )

            EVENTO_FIM -> EventoDeRede.FimDeTransmissao(dados.getString("transmissaoId"))

            else -> null
        }
    }.getOrNull()

    private fun broadcast(talkGroupId: String, evento: String, ref: Int, dados: JSONObject): String =
        envelope(
            topico = topico(talkGroupId),
            evento = "broadcast",
            payload = JSONObject()
                .put("type", "broadcast")
                .put("event", evento)
                .put("payload", dados),
            ref = ref,
        )

    private fun envelope(topico: String, evento: String, payload: JSONObject, ref: Int): String =
        JSONObject()
            .put("topic", topico)
            .put("event", evento)
            .put("payload", payload)
            .put("ref", ref.toString())
            .toString()
}
