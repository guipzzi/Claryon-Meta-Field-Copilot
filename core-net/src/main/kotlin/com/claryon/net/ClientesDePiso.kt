package com.claryon.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piso resolvido **no aparelho**, sobre a política pura [ControleDePiso].
 *
 * Serve para demonstração de um cliente só e para desenvolvimento sem servidor.
 * **Não serve para operação real**: dois aparelhos com instâncias locais não
 * enxergam um ao outro, e ambos se achariam donos do canal. O nome é explícito
 * para que ninguém o instale por engano achando que é o de produção.
 */
class ClienteDePisoLocal(
    private val politica: ControleDePiso = ControleDePiso(),
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) : ClienteDePiso {

    override suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido = politica.pedir(talkGroupId, agenteId, transmissaoId, prioridade, agoraMs())

    override suspend fun renovar(concessao: Concessao): Boolean = politica.renovar(concessao, agoraMs())

    override suspend fun liberar(concessao: Concessao) = politica.liberar(concessao)
}

/**
 * Piso resolvido **no servidor**, por RPC do PostgREST sobre as funções
 * `pedir_canal` / `renovar_canal` / `liberar_canal`.
 *
 * A decisão mora no Postgres, e não numa função serverless, porque a concessão
 * precisa ser **atômica**: dois agentes que apertam o PTT no mesmo instante não
 * podem ambos receber o canal. "Ler, decidir, escrever" em código de aplicação
 * abriria essa janela; `for update` + `on conflict` fecham no banco.
 *
 * A identidade **não é enviada** — o servidor a deriva do JWT. Se o agente
 * viesse por parâmetro, qualquer cliente pediria o canal em nome de outro, e o
 * controle de piso viraria vetor de negação de serviço contra a guarnição.
 *
 * @param jwt token do agente autenticado. Sem ele o servidor recusa.
 */
class ClienteDePisoRemoto(
    private val config: ConfigRealtime,
    private val jwt: () -> String,
    private val agenteIdLocal: String,
    private val ttlSegundos: Int = 30,
    private val cliente: OkHttpClient = OkHttpClient(),
) : ClienteDePiso {

    override suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido {
        val corpo = JSONObject()
            .put("p_talk_group_id", talkGroupId)
            .put("p_transmissao_id", transmissaoId)
            .put("p_prioridade", prioridade.nivel)
            .put("p_ttl_segundos", ttlSegundos)

        val linha = rpc("pedir_canal", corpo)
            // Rede caiu no pedido: **não** presumir concedido. Falar achando que
            // tem o canal produz sobreposição no ouvido de quem está numa
            // ocorrência — pior que não falar.
            ?: return ResultadoDoPedido.Ocupado(
                Concessao(talkGroupId, "?", transmissaoId, prioridade, expiraEmMs = 0),
            )

        val expira = agoraAproximado() + ttlSegundos * 1000L
        val detentorId = linha.optString("detentor_agent_id").ifEmpty { "?" }

        return when (linha.optString("resultado")) {
            "concedido" -> ResultadoDoPedido.Concedido(
                Concessao(talkGroupId, agenteIdLocal, transmissaoId, prioridade, expira),
            )
            "tomado" -> ResultadoDoPedido.Tomado(
                interrompido = Concessao(talkGroupId, detentorId, "?", PrioridadeTransmissao.P2_APOIO, 0),
                concessao = Concessao(talkGroupId, agenteIdLocal, transmissaoId, prioridade, expira),
            )
            else -> ResultadoDoPedido.Ocupado(
                Concessao(talkGroupId, detentorId, "?", PrioridadeTransmissao.P2_APOIO, expira),
            )
        }
    }

    /**
     * `false` = o canal já não é seu (expirou ou foi tomado) — sinal para parar
     * de transmitir. Falha de rede também devolve `false`: na dúvida, calar é
     * mais seguro que seguir falando sem saber se alguém ouve.
     */
    override suspend fun renovar(concessao: Concessao): Boolean {
        val corpo = JSONObject()
            .put("p_transmissao_id", concessao.transmissaoId)
            .put("p_ttl_segundos", ttlSegundos)
        return rpcBooleano("renovar_canal", corpo) == true
    }

    override suspend fun liberar(concessao: Concessao) {
        rpcBooleano("liberar_canal", JSONObject().put("p_transmissao_id", concessao.transmissaoId))
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private suspend fun rpc(funcao: String, corpo: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${config.projetoUrl.trimEnd('/')}/rest/v1/rpc/$funcao")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer ${jwt()}")
                .addHeader("Content-Type", "application/json")
                .post(corpo.toString().toRequestBody(JSON))
                .build()
            runCatching {
                cliente.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$funcao respondeu ${resp.code}")
                        return@use null
                    }
                    val texto = resp.body?.string().orEmpty()
                    when {
                        texto.trimStart().startsWith("[") ->
                            JSONArray(texto).let { if (it.length() > 0) it.getJSONObject(0) else null }
                        texto.trimStart().startsWith("{") -> JSONObject(texto)
                        else -> null
                    }
                }
            }.onFailure { Log.w(TAG, "$funcao falhou: ${it.message}") }.getOrNull()
        }

    private suspend fun rpcBooleano(funcao: String, corpo: JSONObject): Boolean? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${config.projetoUrl.trimEnd('/')}/rest/v1/rpc/$funcao")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer ${jwt()}")
                .addHeader("Content-Type", "application/json")
                .post(corpo.toString().toRequestBody(JSON))
                .build()
            runCatching {
                cliente.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()?.trim()?.equals("true", ignoreCase = true)
                }
            }.getOrNull()
        }

    private fun agoraAproximado() = System.currentTimeMillis()

    private companion object {
        const val TAG = "ClaryonField"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Nível numérico que o servidor usa (1 = emergência). */
val PrioridadeTransmissao.nivel: Int
    get() = when (this) {
        PrioridadeTransmissao.P1_EMERGENCIA -> 1
        PrioridadeTransmissao.P2_APOIO -> 2
        PrioridadeTransmissao.P3_INFORMATIVO -> 3
    }
