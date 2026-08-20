package com.claryon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

/** Uma fala já transmitida, como o servidor a guarda. */
data class FalaDoCanal(
    val id: String,
    val indicativo: String,
    val transcricao: String,
    val prioridade: Int,
    val tipo: String,
    val criadaEmIso: String,
)

/** Um par do talk group, com a idade da última posição publicada. */
data class MembroDoCanal(
    val agentId: String,
    val indicativo: String,
    /** `null` quando nunca publicou posição. */
    val idadeDaPosicaoS: Int?,
)

/**
 * **Leitura do canal: histórico e presença.**
 *
 * Existe porque o rádio sabia transmitir e não sabia mostrar o que já tinha sido
 * dito. Um agente que entra no turno com o aparelho descarregado, ou que estava
 * com as mãos ocupadas, precisa **ler** o que perdeu — é a capacidade que o rádio
 * analógico nunca deu e que justifica boa parte deste produto.
 *
 * Vai por PostgREST com o token do agente, então o RLS decide o que ele vê: só o
 * tráfego dos talk groups de que participa. Não há filtro no cliente, e é
 * deliberado — filtro no cliente exige entregar o dado primeiro.
 *
 * **A presença é derivada da idade da posição**, não de um campo "online". Um
 * booleano de presença mente com facilidade: fica `true` quando o processo morre
 * sem avisar, e um agente que sumiu apareceria disponível. A posição envelhece
 * sozinha, então "online" passa a significar algo verificável — publicou faz
 * pouco tempo.
 */
/** Uma consulta que alguém fez sobre a minha posição. Nunca traz a resposta. */
data class ConsultaRecebida(val indicativo: String, val em: String)

/**
 * Um ponto do rastro de 30 minutos, em GRANDEZA.
 *
 * Não existe latitude nem longitude aqui, e a ausência é a garantia: o tipo não
 * tem onde guardar coordenada de terceiro, então nenhum caminho futuro pode
 * acidentalmente passar a carregá-la.
 */
data class PontoDoRastro(
    val distanciaM: Double,
    val azimuteGraus: Double?,
    val idadeS: Int,
)

class HistoricoDoCanal(
    private val config: ConfigRealtime,
    private val tokenDeSessao: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) {

    suspend fun falas(talkGroupId: String, limite: Int = 50): Result<List<FalaDoCanal>> =
        buscar(
            caminho = "transmissions" +
                "?talk_group_id=eq.$talkGroupId" +
                // `!transmissions_author_agent_id_fkey` e não `!inner`: há **dois**
                // caminhos de `transmissions` para `agents` — a autoria e a tabela
                // de entregas — e o PostgREST recusa o embed ambíguo com PGRST201.
                // Nomear a chave estrangeira diz qual dos dois se quer.
                "&select=id,tipo,prioridade,transcricao,criada_em," +
                "agents!transmissions_author_agent_id_fkey(indicativo)" +
                "&order=criada_em.asc&limit=$limite",
        ) { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FalaDoCanal(
                    id = o.getString("id"),
                    indicativo = o.optJSONObject("agents")?.optString("indicativo").orEmpty(),
                    // `isNull` ANTES de `optString`: para um valor JSON nulo o
                    // `optString` devolve a **string** `"null"`, e ela chegava à
                    // tela como se fosse a fala do agente. Vazio é a verdade —
                    // "não transcrito" —, e quem decide como exibir isso é a UI,
                    // não o parser.
                    transcricao = if (o.isNull("transcricao")) "" else o.optString("transcricao"),
                    prioridade = o.optInt("prioridade", 3),
                    tipo = o.optString("tipo", "ptt"),
                    criadaEmIso = o.optString("criada_em"),
                )
            }
        }

    /**
     * Presença dos pares, derivada de `posicoes_do_grupo`.
     *
     * A versão anterior lia `agent_positions.updated_at` por embed do PostgREST.
     * Isso parou de funcionar — e **é bom que tenha parado**: a migração 0010
     * fechou a leitura direta da tabela, porque ela entregava `geom` de toda a
     * guarnição a qualquer agente autenticado. A presença agora sai da mesma
     * função que já devolve grandezas, e não existe caminho no cliente que peça
     * coordenada de par.
     *
     * Quem nunca publicou posição simplesmente não aparece — o que é honesto:
     * presença aqui significa "está publicando", não "instalou o app".
     */
    /**
     * **O cadastro do grupo: id e indicativo de quem pode falar aqui.**
     *
     * É a fonte contra a qual o receptor resolve o autor de uma fala. Vem de
     * `public.cadastro_do_grupo` (migração `0013`), que devolve conjunto vazio
     * para grupo de que o chamador não é membro e para chamada sem sessão —
     * verificado nas três direções antes de existir chamador.
     *
     * Diferente de [membros]: aquele deriva de `posicoes_do_grupo` e só enxerga
     * quem **publicou posição**, então um colega legítimo que ainda não publicou
     * ficaria de fora. Para presença isso é aceitável; para decidir se uma fala é
     * autêntica, descartaria agente real.
     */
    suspend fun cadastroDoGrupo(talkGroupId: String): Result<Map<String, String>> =
        chamarRpc("cadastro_do_grupo", org.json.JSONObject().put("talk_group", talkGroupId)) { arr ->
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("agent_id")
                    if (id.isNotEmpty()) put(id, o.optString("indicativo"))
                }
            }
        }

    /**
     * **Quem o SERVIDOR diz que é o autor da transmissão** — `null` se não souber.
     *
     * É a prova que o payload não pode dar. `pedir_canal` grava
     * `floor_grants.agent_id` a partir do JWT, então ninguém obtém piso em nome de
     * outro; conferir contra ele fecha a personificação **entre membros do mesmo
     * grupo**, que o cadastro sozinho não fechava.
     *
     * Vale durante a fala (piso vivo) e depois dela (`transmissions`, escrito pela
     * Edge Function) — a função de servidor confere as duas janelas.
     */
    suspend fun autorDaTransmissao(transmissaoId: String): Result<String?> =
        chamarRpc(
            "autor_da_transmissao",
            org.json.JSONObject().put("p_transmissao_id", transmissaoId),
        ) { arr -> if (arr.length() > 0) arr.optString(0).takeIf { it.isNotEmpty() } else null }

    /**
     * Abre a **sessão** de consulta ao mapa e devolve o id dela.
     *
     * Sessão, e não uma linha por redesenho: o laço do mapa roda a cada 5 s, o que
     * daria ~720 registros por hora por agente — a mesma amplificação que a `0016`
     * eliminou na escrita, ressuscitada no log, e que afogaria a consulta pontual,
     * que é justamente a que interessa auditar.
     */
    suspend fun abrirMapa(talkGroupId: String): Result<Long> =
        chamarRpcEscalar("abrir_mapa", org.json.JSONObject().put("talk_group", talkGroupId))

    /** Fecha a sessão. Só o autor fecha a própria — o servidor confere. */
    suspend fun fecharMapa(sessao: Long): Result<Unit> =
        chamarRpcEscalar("fechar_mapa", org.json.JSONObject().put("sessao", sessao)).map { }

    suspend fun membros(talkGroupId: String): Result<List<MembroDoCanal>> =
        posicoesDoGrupo(talkGroupId).map { lista ->
            lista.map { p ->
                MembroDoCanal(
                    agentId = "",
                    indicativo = p.indicativo,
                    idadeDaPosicaoS = p.idadeS.takeIf { it != Int.MAX_VALUE },
                )
            }
        }

    /**
     * Posições relativas de **todos** os pares do talk group.
     *
     * Sonda, e não assina. Postgres Changes seria o caminho óbvio e foi
     * descartado: ele empurra a linha inteira, incluindo `geom`. Com o mapa aberto
     * 5% do turno, sondar a cada poucos segundos custa menos que manter uma
     * assinatura viva o tempo todo — e não entrega coordenada a ninguém.
     *
     * Desde a migração 0010 esta função é o **único** caminho pelo qual o app
     * sabe algo sobre a posição de um par: a leitura direta de `agent_positions`
     * passou a ser restrita à própria linha.
     */
    suspend fun posicoesDoGrupo(talkGroupId: String): Result<List<RespostaDePosicao>> =
        chamarRpc("posicoes_do_grupo", org.json.JSONObject().put("talk_group", talkGroupId)) { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RespostaDePosicao(
                    indicativo = o.optString("indicativo"),
                    distanciaM = o.optDouble("distancia_m", 0.0).toInt(),
                    azimuteGraus = o.optDouble("azimute").takeIf { !it.isNaN() },
                    velocidadeMs = o.optDouble("speed_mps").takeIf { !it.isNaN() }?.toFloat(),
                    idadeS = o.optInt("idade_s", Int.MAX_VALUE),
                    idadeDoSolicitanteS = o.optInt("idade_solicitante_s", Int.MAX_VALUE),
                )
            }
        }

    /**
     * RPC que devolve escalar (ou nada), e não conjunto de linhas.
     *
     * `chamarRpc` espera um `JSONArray`; `abrir_mapa` devolve `2` e `fechar_mapa`
     * devolve corpo vazio. Passar isso pelo caminho de array daria erro de parse
     * num ponto sem relação com a causa.
     */
    private suspend fun chamarRpcEscalar(nome: String, corpo: org.json.JSONObject): Result<Long> =
        withContext(Dispatchers.IO) {
            val token = tokenDeSessao()
                ?: return@withContext Result.failure(IllegalStateException("sem sessão"))
            val req = Request.Builder()
                .url("${config.projetoUrl}/rest/v1/rpc/$nome")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer $token")
                .post(corpo.toString().toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(req).execute().use { r ->
                    val corpoTexto = r.body?.string()?.trim().orEmpty()
                    if (!r.isSuccessful) {
                        error("$nome falhou (${r.code}): ${corpoTexto.take(160)}")
                    }
                    corpoTexto.toLongOrNull() ?: 0L
                }
            }.fold({ Result.success(it) }, { Result.failure(it) })
        }

    /**
     * **Quem consultou a minha posição.** A contrapartida do log de acesso.
     *
     * A `0017` criou `quem_me_consultou()` com esta justificativa: *"saber onde um
     * colega está é poder; um sistema que concede esse poder sem deixar rastro não
     * distingue a consulta legítima da vigilância de um agente sobre outro"*. E o
     * ponto dela é devolver o controle ao titular — conformidade vira
     * característica em vez de promessa em documento.
     *
     * Só que a função ficou **três dias sem um único chamador Kotlin**: capacidade
     * no banco, sem porta no produto. Um log de auditoria que o auditado não pode
     * ler é vigilância com passo extra, não transparência.
     *
     * Sem parâmetro, e isso é da função: com um `agent_id` como argumento ela
     * viraria "quem consultou fulano", e um agente leria o padrão de consultas da
     * corporação inteira.
     */
    suspend fun quemMeConsultou(): Result<List<ConsultaRecebida>> =
        chamarRpc("quem_me_consultou", org.json.JSONObject()) { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConsultaRecebida(
                    indicativo = o.optString("indicativo_de_quem_consultou"),
                    em = o.optString("em"),
                )
            }
        }

    /**
     * **O rastro do par nos últimos 30 minutos** — só em grandeza, nunca coordenada.
     *
     * Camada 2 da retenção (`0019`). Cada ponto é distância e azimute a partir de
     * ONDE EU ESTOU AGORA, com a idade: o servidor nunca devolve latitude e
     * longitude de terceiro, e por isso o rastro não reconstrói o trajeto absoluto
     * de ninguém — reconstrói o trajeto **relativo a mim**, que é o que serve para
     * ir ao encontro de um companheiro e não serve para vigiá-lo depois.
     *
     * A distância vem arredondada em faixas desde a `0021`. Sem isso, uma série de
     * distâncias exatas com azimute e instante reconstrói a trajetória — é a
     * função com mais superfície das três, e era a única sem arredondamento.
     */
    suspend fun rastroDoPar(indicativo: String): Result<List<PontoDoRastro>> =
        chamarRpc("rastro_do_par", org.json.JSONObject().put("indicativo", indicativo)) { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PontoDoRastro(
                    distanciaM = o.optDouble("distancia_m", Double.NaN),
                    azimuteGraus = if (o.isNull("azimute")) null else o.optDouble("azimute"),
                    idadeS = o.optInt("idade_s", Int.MAX_VALUE),
                )
            }.filter { it.distanciaM.isFinite() }
        }

    private suspend fun <T> chamarRpc(
        nome: String,
        corpo: org.json.JSONObject,
        mapear: (JSONArray) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val token = tokenDeSessao()
            ?: return@withContext Result.failure(IllegalStateException("sem sessão"))

        val req = Request.Builder()
            .url("${config.projetoUrl}/rest/v1/rpc/$nome")
            .addHeader("apikey", config.apiKey)
            .addHeader("Authorization", "Bearer $token")
            .post(
                corpo.toString().toRequestBody(
                    "application/json".toMediaType(),
                ),
            )
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                mapear(JSONArray(resp.body?.string().orEmpty()))
            }
        }
    }

    /**
     * GET no PostgREST. Continua servindo o histórico de falas — que é tabela e
     * não função, e cuja RLS já filtra por entrega.
     *
     * `extrairAtualizadoEm` foi removido junto com o embed de `agent_positions`:
     * era o único consumidor dele, e a migração 0010 fechou esse caminho. Posição
     * de par agora só por `chamarRpc`.
     */
    private suspend fun <T> buscar(
        caminho: String,
        mapear: (JSONArray) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val token = tokenDeSessao()
            ?: return@withContext Result.failure(IllegalStateException("sem sessão"))

        val req = Request.Builder()
            .url("${config.projetoUrl}/rest/v1/$caminho")
            .addHeader("apikey", config.apiKey)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                mapear(JSONArray(resp.body?.string().orEmpty()))
            }
        }
    }

    private fun idadeEmSegundos(iso: String): Int? = runCatching {
        val instante = java.time.OffsetDateTime.parse(iso).toInstant()
        java.time.Duration.between(instante, java.time.Instant.now()).seconds
            .coerceAtLeast(0L)
            .toInt()
    }.getOrNull()
}
