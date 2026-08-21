package com.claryon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [BaseVeicular] sobre `public.consultar_placa` (migração `0023`).
 *
 * Mesmo desenho de [ConsultaDePosicao]: uma requisição por pergunta, com o **token
 * do agente** e não a chave anônima — é o JWT que faz o servidor saber quem
 * pergunta. A função recusa `current_agent_id()` nulo com `42501`, então agente
 * revogado para de consultar sem código novo aqui.
 *
 * **O que este cliente não faz, e é o ponto:** ele não valida placa, não normaliza
 * e não decide entre "não achei" e "não era placa". Tudo isso é do servidor. Se
 * fosse feito aqui, um cliente desatualizado passaria a discordar da base sobre o
 * que existe — e a divergência apareceria como "não encontrada" para um veículo que
 * está lá.
 */
class BaseVeicularSupabase(
    private val config: ConfigRealtime,
    private val tokenDeSessao: suspend () -> String?,
    private val client: OkHttpClient = clientePadrao(),
) : BaseVeicular {

    override suspend fun consultar(placa: String): Result<ConsultaVeicular> =
        withContext(Dispatchers.IO) {
            val token = tokenDeSessao()
                ?: return@withContext Result.failure(IllegalStateException("sem sessão"))

            val corpo = JSONObject().put("placa", placa).toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${config.projetoUrl}/rest/v1/rpc/consultar_placa")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer $token")
                .post(corpo)
                .build()

            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    // **Conjunto vazio é erro de protocolo, não "nada consta".** A
                    // função é obrigada a devolver exatamente uma linha; zero linhas
                    // significa que ela foi trocada por uma versão que não cumpre o
                    // contrato. Devolver `null` ou um "sem restrição" aqui seria
                    // reintroduzir no cliente o defeito que a migração fechou no
                    // servidor.
                    if (arr.length() == 0) error("resposta vazia de consultar_placa")
                    InterpretadorVeicular.de(arr.getJSONObject(0))
                        ?: error("resposta de consultar_placa não reconhecida")
                }
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()

        /** Mesmo orçamento de [ConsultaDePosicao]: resposta tardia numa abordagem
         *  chega como interrupção sem contexto. */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Tradução JSON → [ConsultaVeicular], **pura**, para que a regra que impede o "sem
 * restrição" inventado seja testável sem rede.
 */
object InterpretadorVeicular {

    /**
     * @return `null` quando a resposta não é reconhecida — `situacao` desconhecida,
     *   ou `encontrado` com um código de restrição que este cliente não conhece.
     *
     * **Nulo aqui vira falha no chamador, nunca um resultado limpo.** É a diferença
     * entre um app que diz "consulta indisponível" e um app que diz "sem restrição"
     * porque não entendeu a resposta.
     */
    fun de(o: JSONObject): ConsultaVeicular? {
        val procedencia = Procedencia.de(o.optStringOuNulo("procedencia"))
        val fonte = o.optStringOuNulo("fonte")
        val placa = o.optStringOuNulo("placa_consultada")

        return when (o.optStringOuNulo("situacao")) {
            "encontrado" -> {
                // Restrição desconhecida NÃO degrada para SEM_RESTRICAO — ver
                // RestricaoVeicular.de. Um código novo no servidor faz o app dizer que
                // não entendeu, que é a única saída segura.
                val restricao = RestricaoVeicular.de(o.optStringOuNulo("restricao")) ?: return null
                ConsultaVeicular.Encontrado(
                    placa = placa.orEmpty(),
                    procedencia = procedencia,
                    restricao = restricao,
                    fonte = fonte,
                    marcaModelo = o.optStringOuNulo("marca_modelo"),
                    cor = o.optStringOuNulo("cor"),
                    ano = o.optStringOuNulo("ano")?.toIntOrNull(),
                )
            }

            "nao_encontrada" -> ConsultaVeicular.NaoEncontrada(placa.orEmpty(), procedencia, fonte)

            "placa_invalida" -> ConsultaVeicular.PlacaInvalida(placa)

            "base_indisponivel" -> ConsultaVeicular.BaseIndisponivel

            // Situação nova ou ausente. Não existe ramo "senão, está limpo".
            else -> null
        }
    }

    /**
     * `optString` devolve a string vazia para campo nulo, e o `JSONObject.NULL` do
     * PostgREST cairia nela. Sem isto, `fonte` nula viraria `""` e `ano` nulo viraria
     * `""` — que `toIntOrNull` transforma em nulo por acidente, não por regra.
     */
    private fun JSONObject.optStringOuNulo(chave: String): String? =
        if (isNull(chave)) null else optString(chave).takeIf { it.isNotEmpty() }
}
