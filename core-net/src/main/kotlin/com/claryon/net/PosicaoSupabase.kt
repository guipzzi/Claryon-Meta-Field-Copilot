package com.claryon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Publica a própria posição no Supabase.
 *
 * Chama `public.publicar_posicao`, que deriva o `agent_id` do JWT — não existe
 * forma de escrever a posição de outro agente, nem por engano nem de propósito.
 *
 * É esta publicação que alimenta o `pos_sol` de onde a consulta por voz mede a
 * distância. Sem ela, C2 responde "não sei de onde medir": o servidor tem a
 * posição do par, mas não a de quem perguntou.
 */
class PublicadorDePosicaoSupabase(
    private val config: ConfigRealtime,
    private val tokenDeSessao: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) : PublicadorDePosicao {

    @Volatile
    private var ultimaPublicacaoOk = false

    override fun publicando(): Boolean = ultimaPublicacaoOk

    override suspend fun iniciarTurno(): Boolean = rpcSimples("iniciar_turno")

    override suspend fun encerrarTurno() {
        rpcSimples("encerrar_turno")
    }

    /**
     * RPC sem argumento, para as duas portas de turno.
     *
     * Devolve `false` em vez de lançar: o turno é pré-condição da posição, mas a
     * posição já degrada sozinha quando não sobe (`ultimaPublicacaoOk`), e derrubar
     * o serviço porque o turno não abriu tiraria também o rádio, que não depende
     * disso.
     */
    private suspend fun rpcSimples(nome: String): Boolean {
        val token = tokenDeSessao() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching<Boolean> {
                val req = Request.Builder()
                    .url("${config.projetoUrl}/rest/v1/rpc/$nome")
                    .addHeader("apikey", config.apiKey)
                    .addHeader("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    override suspend fun publicar(
        lat: Double,
        lon: Double,
        precisaoM: Float,
        velocidadeMs: Float?,
    ) {
        val token = tokenDeSessao() ?: run {
            ultimaPublicacaoOk = false
            return
        }
        withContext(Dispatchers.IO) {
            val corpo = JSONObject()
                .put("lat", lat)
                .put("lon", lon)
                // `Float.MAX_VALUE` é o sentinela de "o aparelho não soube dizer".
                // Mandar esse número faria o servidor guardar uma precisão de
                // 3×10³⁸ metros, que num relatório pareceria um dado real.
                .put("precisao_m", if (precisaoM.isFinite() && precisaoM < 10_000f) precisaoM else JSONObject.NULL)
                .put("velocidade_ms", velocidadeMs ?: JSONObject.NULL)
                .toString()
                .toRequestBody(JSON)

            val req = Request.Builder()
                .url("${config.projetoUrl}/rest/v1/rpc/publicar_posicao")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer $token")
                .post(corpo)
                .build()

            ultimaPublicacaoOk = runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    /**
     * A assinatura do canal de pares vive no transporte Realtime, não aqui.
     * Devolve `false` para deixar explícito que este publicador **sozinho** não
     * habilita o mapa — a reciprocidade exige os dois lados, e um `true` otimista
     * abriria o espelho sem que nada fosse chegar nele.
     */
    override suspend fun assinarPares(talkGroupId: String): Boolean = false

    override suspend fun desassinarPares() = Unit

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
