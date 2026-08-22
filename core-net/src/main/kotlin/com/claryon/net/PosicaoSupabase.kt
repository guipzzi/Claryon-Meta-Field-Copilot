package com.claryon.net

import android.util.Log
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
    /** Injetável só para o teste conseguir provar a idade sem esperar de verdade. */
    private val agoraNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
) : PublicadorDePosicao {

    @Volatile
    private var ultimaPublicacaoOk = false

    /**
     * O resultado do último POST.
     *
     * **Passou a ter consumidor em `src/main` em 21/08.** Até então `grep` do
     * símbolo devolvia só o teste instrumentado e a pré-condição de
     * `CanalDePosicoes.assinar` — que o mapa não usa desde que a recepção virou
     * sondagem de RPC. Ou seja: o único indicador de que a posição própria estava
     * subindo era lido por ninguém. Hoje o `CopilotService` o devolve ao coletor a
     * cada publicação, e ele vira o ponto de cor de "Minha posição no mapa".
     */
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
        val token = tokenDeSessao() ?: run {
            Log.w(TAG, "$nome não chamado: sem token de sessão")
            return false
        }
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
        nanosDaCorrecao: Long?,
    ) {
        // **Este `return` era silencioso, e é o primeiro dos três defeitos que
        // deixaram 20 min de coleta sumirem sem uma linha de log.** Sem sessão não
        // há a quem publicar — isso é correto — mas sair sem dizer nada faz o
        // aparelho acordar o GPS, montar a correção e jogá-la fora em segredo. O
        // `false` abaixo é o que a interface lê como "sem rede"; o log é o que
        // torna o caso reproduzível no `adb logcat -s ClaryonField`.
        val token = tokenDeSessao() ?: run {
            Log.w(TAG, "posição descartada: sem token de sessão — nada sobe até o login")
            ultimaPublicacaoOk = false
            return
        }
        withContext(Dispatchers.IO) {
            // **A idade sai daqui, e não de quem chamou.** Entre a correção do GPS
            // e este ponto existe a fila do coletor, o `withContext` e a espera
            // pelo token; calcular lá em cima congelaria um número que envelhece
            // sozinho no caminho. Aqui falta só a viagem da requisição, que é o
            // resíduo que a `0020` documenta e não tem como eliminar.
            val idadeMs = nanosDaCorrecao?.let { (agoraNanos() - it) / 1_000_000L }

            val corpo = JSONObject()
                .put("lat", lat)
                .put("lon", lon)
                // O servidor satura em [0, 1 h]; mandar nulo quando a origem não
                // soube dizer é diferente de mandar zero, que seria a afirmação
                // "medido exatamente agora" sem nada a sustentá-la.
                .put("idade_ms", idadeMs ?: JSONObject.NULL)
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
                client.newCall(req).execute().use { resposta ->
                    if (!resposta.isSuccessful) {
                        // O código HTTP separa duas causas que a interface trata
                        // diferente: `42501`/403 é turno fechado (`0019`), e 5xx ou
                        // exceção é rede. Sem esta linha as duas chegavam ao
                        // `logcat` como a mesma ausência de linha nenhuma.
                        Log.w(TAG, "publicar_posicao recusada: HTTP ${resposta.code}")
                    }
                    resposta.isSuccessful
                }
            }.getOrElse {
                Log.w(TAG, "publicar_posicao não saiu: ${it.javaClass.simpleName}: ${it.message}")
                false
            }
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
        const val TAG = "ClaryonField"
    }
}
