package com.claryon.net

import org.json.JSONArray
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

    /**
     * Vários quadros numa mensagem só. Ver [quadros].
     *
     * Evento NOVO em vez de mudar o formato do [EVENTO_QUADRO]: um aparelho com
     * versão antiga simplesmente não entende `fala.quadros` e descarta (o `else`
     * de [interpretar] devolve lista vazia), em vez de decodificar um payload
     * com forma diferente da que espera. Trocar o significado de um evento
     * existente seria a receita para dois agentes na mesma guarnição ouvirem
     * ruído durante uma migração.
     */
    const val EVENTO_QUADROS = "fala.quadros"
    const val EVENTO_FIM = "fala.fim"

    fun topico(talkGroupId: String): String = "realtime:tg-$talkGroupId"

    /**
     * Entrada no canal do talk group.
     *
     * `access_token` vai no **topo** do payload, irmão de `config` — não dentro
     * dele. Confirmado na doc oficial do Supabase em 18/08 e anotado em
     * `DECISIONS.md`; dentro de `config` o servidor ignora em silêncio e o canal
     * segue autorizado só pela chave anon, que é exatamente o defeito que este
     * parâmetro existe para fechar. Silêncio é o pior modo de falha aqui, e é por
     * isso que `oJoinLevaOTokenNoTopoDoPayload` existe como teste.
     *
     * [privado] liga `config.private`, que faz o servidor consultar as políticas
     * de `realtime.messages` (migração `0012`). Enquanto for `false` o canal é
     * público e as políticas não são consultadas — o que permite aplicar a
     * migração e exercitá-la com o par headless antes de mexer no rádio.
     */
    fun join(
        talkGroupId: String,
        ref: Int,
        accessToken: String? = null,
        privado: Boolean = false,
    ): String = envelope(
        topico = topico(talkGroupId),
        evento = "phx_join",
        payload = JSONObject()
            .put(
                "config",
                JSONObject()
                    .put("broadcast", JSONObject().put("ack", false))
                    .put("private", privado),
            )
            .apply { if (accessToken != null) put("access_token", accessToken) },
        ref = ref,
    )

    /**
     * Renovação do JWT sem refazer o `join`.
     *
     * A doc é explícita: *"se um novo JWT nunca for recebido no canal, o cliente é
     * desconectado quando o JWT expira"*. Sem isto o rádio do agente **cai sozinho
     * no meio do turno** — e falha em campo, não na bancada, que é a pior classe
     * de defeito deste produto.
     *
     * Não há resposta em caso de sucesso; em caso de falha vem um evento `system`
     * e o canal fecha. Por isso o silêncio aqui é o resultado esperado.
     */
    fun renovarToken(talkGroupId: String, token: String, ref: Int): String = envelope(
        topico = topico(talkGroupId),
        evento = "access_token",
        payload = JSONObject().put("access_token", token),
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
            .put("agenteId", a.autorAgenteId)
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

    /**
     * **Vários quadros numa mensagem — o agrupamento.**
     *
     * Cada quadro mantém a **própria** `sequencia`. É a decisão que faz o
     * agrupamento não tocar no receptor: `BufferDeJitter` continua raciocinando
     * em quadros de 20 ms, detectando perda e ordenando exatamente como antes, e
     * [interpretar] só explode a mensagem de volta em N eventos. A alternativa —
     * numerar por mensagem — obrigaria a reescrever o jitter, o PLC e a
     * detecção de perda de uma vez, que é o que tornava este item arriscado.
     *
     * `transmissaoId` e `ultimo` sobem para o envelope porque são iguais em
     * todos os quadros do grupo; repeti-los por quadro seria pagar 36 bytes de
     * UUID três vezes, e o ponto do agrupamento é justamente o envelope.
     */
    fun quadros(talkGroupId: String, grupo: List<QuadroAudio>, ref: Int): String {
        require(grupo.isNotEmpty()) { "grupo vazio não vira mensagem" }
        val itens = JSONArray()
        for (q in grupo) {
            itens.put(
                JSONObject()
                    .put("seq", q.sequencia)
                    .put("t", q.capturadoEmMs)
                    .put("opus", Base64.getEncoder().encodeToString(q.payload)),
            )
        }
        return broadcast(
            talkGroupId, EVENTO_QUADROS, ref,
            JSONObject()
                .put("transmissaoId", grupo.first().transmissaoId)
                .put("ultimo", grupo.any { it.ultimo })
                .put("q", itens),
        )
    }

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
    fun interpretar(texto: String): List<EventoDeRede> = runCatching {
        val raiz = JSONObject(texto)
        val payload = raiz.optJSONObject("payload") ?: return emptyList()
        val evento = payload.optString("event").ifEmpty { raiz.optString("event") }

        // A resposta do `phx_join` vem ANTES do descarte abaixo, e tem de vir:
        // ela traz `{status, response}` e **não** um `payload` aninhado, então
        // caía no `return emptyList()` e a recusa do canal sumia em silêncio.
        // Ver `EventoDeRede.CanalRecusado` para o que isso custou.
        if (raiz.optString("event") == "phx_reply") {
            val status = payload.optString("status")
            return if (status == "ok") {
                listOf(EventoDeRede.CanalPronto)
            } else {
                val motivo = payload.optJSONObject("response")?.optString("reason")
                    ?.takeIf { it.isNotBlank() }
                    ?: "o servidor recusou a entrada no canal (status=$status)"
                listOf(EventoDeRede.CanalRecusado(motivo))
            }
        }
        // O servidor também derruba canal por evento `system` — token vencido é o
        // caso operacional, e ele chega assim, não como `phx_reply`.
        if (raiz.optString("event") == "system" && payload.optString("status") == "error") {
            return listOf(
                EventoDeRede.CanalRecusado(
                    payload.optString("message").ifBlank { "o canal foi encerrado pelo servidor" },
                ),
            )
        }

        val dados = payload.optJSONObject("payload") ?: return emptyList()

        when (evento) {
            EVENTO_ANUNCIO -> listOf(
                EventoDeRede.Anuncio(
                AnuncioDeFala(
                    transmissaoId = dados.getString("transmissaoId"),
                    autorIndicativo = dados.optString("indicativo"),
                    autorAgenteId = dados.optString("agenteId"),
                    prioridade = runCatching {
                        PrioridadeTransmissao.valueOf(dados.optString("prioridade"))
                    }.getOrDefault(PrioridadeTransmissao.P2_APOIO),
                    ),
                ),
            )

            EVENTO_QUADRO -> listOf(
                EventoDeRede.Quadro(
                    QuadroAudio(
                        transmissaoId = dados.getString("transmissaoId"),
                        sequencia = dados.getInt("seq"),
                        capturadoEmMs = dados.optLong("t"),
                        payload = Base64.getDecoder().decode(dados.optString("opus")),
                        ultimo = dados.optBoolean("ultimo", false),
                    ),
                ),
            )

            // Explode o grupo em N eventos de quadro. O resto do receptor não
            // sabe — nem precisa saber — que eles vieram juntos.
            EVENTO_QUADROS -> {
                val id = dados.getString("transmissaoId")
                val ultimoDoGrupo = dados.optBoolean("ultimo", false)
                val itens = dados.getJSONArray("q")
                val saida = ArrayList<EventoDeRede>(itens.length())
                for (i in 0 until itens.length()) {
                    val q = itens.getJSONObject(i)
                    saida.add(
                        EventoDeRede.Quadro(
                            QuadroAudio(
                                transmissaoId = id,
                                sequencia = q.getInt("seq"),
                                capturadoEmMs = q.optLong("t"),
                                payload = Base64.getDecoder().decode(q.optString("opus")),
                                // Só o ÚLTIMO item do grupo carrega o fim: marcar
                                // todos faria o jitter encerrar a fala no primeiro
                                // quadro do grupo e cortar os dois seguintes.
                                ultimo = ultimoDoGrupo && i == itens.length() - 1,
                            ),
                        ),
                    )
                }
                saida
            }

            EVENTO_FIM -> listOf(EventoDeRede.FimDeTransmissao(dados.getString("transmissaoId")))

            else -> emptyList()
        }
    }.getOrDefault(emptyList())

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
