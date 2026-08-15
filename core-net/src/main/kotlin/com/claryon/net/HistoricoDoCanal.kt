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
                    transcricao = o.optString("transcricao").orEmpty(),
                    prioridade = o.optInt("prioridade", 3),
                    tipo = o.optString("tipo", "ptt"),
                    criadaEmIso = o.optString("criada_em"),
                )
            }
        }

    suspend fun membros(talkGroupId: String): Result<List<MembroDoCanal>> =
        buscar(
            caminho = "memberships" +
                "?talk_group_id=eq.$talkGroupId" +
                "&select=agent_id,agents!inner(indicativo,agent_positions(updated_at))",
        ) { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val agente = o.optJSONObject("agents")
                MembroDoCanal(
                    agentId = o.optString("agent_id"),
                    indicativo = agente?.optString("indicativo").orEmpty(),
                    // O PostgREST devolve **objeto** num embed um-para-um e
                    // **array** num um-para-muitos. `agent_positions` tem o
                    // `agent_id` como chave primária, então vem objeto — e ler só
                    // o array fazia a idade virar `null`, o par virar "offline" e
                    // a contagem mostrar 0/2 com todo mundo publicando.
                    idadeDaPosicaoS = agente?.let(::extrairAtualizadoEm)?.let(::idadeEmSegundos),
                )
            }
        }

    /**
     * Posições relativas de **todos** os pares do talk group, para o mapa.
     *
     * Sonda, e não assina. Postgres Changes seria o caminho óbvio e foi
     * descartado: ele empurra a linha inteira, incluindo `geom`, e cada aparelho
     * da guarnição passaria a receber a coordenada bruta de todos os outros. A
     * garantia de que "o aparelho de um agente jamais recebe a posição de outro"
     * viraria promessa, com o cliente descartando o que já recebeu.
     *
     * Com o mapa aberto 5% do turno, sondar a cada poucos segundos custa menos
     * que manter uma assinatura viva o tempo todo — e mantém a garantia.
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

    /** Aceita as duas formas de embed do PostgREST — objeto e array. */
    private fun extrairAtualizadoEm(agente: org.json.JSONObject): String? =
        agente.optJSONObject("agent_positions")?.optString("updated_at")
            ?: agente.optJSONArray("agent_positions")
                ?.takeIf { it.length() > 0 }
                ?.optJSONObject(0)
                ?.optString("updated_at")

    private fun idadeEmSegundos(iso: String): Int? = runCatching {
        val instante = java.time.OffsetDateTime.parse(iso).toInstant()
        java.time.Duration.between(instante, java.time.Instant.now()).seconds
            .coerceAtLeast(0L)
            .toInt()
    }.getOrNull()
}
