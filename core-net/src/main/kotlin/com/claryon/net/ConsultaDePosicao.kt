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
 * Resposta da consulta de posição, **já relativa** a quem perguntou.
 *
 * Repare no que não existe aqui: latitude e longitude. Não é omissão — é o
 * contrato. Ver [ConsultaDePosicao].
 */
data class RespostaDePosicao(
    val indicativo: String,
    val distanciaM: Int,
    /**
     * `null` quando não há rumo definido. O PostGIS devolve nulo em `ST_Azimuth`
     * para pontos coincidentes — dupla na mesma viatura, que é a configuração
     * mais comum do policiamento, não uma borda.
     */
    val azimuteGraus: Double?,
    val velocidadeMs: Float?,
    val idadeS: Int,
    /**
     * Idade da posição **do solicitante** — aquela de onde a distância acima foi
     * medida. Ver [ConsultaDePosicao.onde].
     */
    val idadeDoSolicitanteS: Int,
)

/**
 * **Consulta de posição por voz (C2) — resolvida no servidor.**
 *
 * A primeira versão disto lia o espelho local de posições ([CanalDePosicoes]), e
 * um teste derrubou a ideia: o espelho **só existe enquanto o mapa está
 * visível**, e a tela do mapa fica fechada quase o turno inteiro. Uma consulta
 * de voz que só funciona com o mapa aberto contradiz a premissa do produto —
 * mãos livres, olhos no ambiente, tela apagada.
 *
 * A correção acabou sendo melhor em três eixos ao mesmo tempo:
 *
 *  1. **Funciona com a tela apagada**, que é o caso normal.
 *  2. **Não custa assinatura nenhuma.** Uma requisição por pergunta, contra um
 *     canal aberto o turno inteiro.
 *  3. **A coordenada do par nunca chega ao aparelho.** O `ST_Distance` e o
 *     `ST_Azimuth` rodam dentro do Postgres; o que trafega já é distância e
 *     rumo. Filtrar no cliente teria exigido *entregar* a coordenada primeiro —
 *     e "o aparelho de um agente jamais recebe a posição de outro" deixaria de
 *     ser uma garantia para virar uma promessa.
 *
 * O espelho continua existindo, e continua sendo a fonte do **mapa** (C5). São
 * dois consumidores com necessidades opostas: o mapa quer fluxo contínuo
 * enquanto está aberto; a voz quer resposta pontual com a tela apagada.
 */
class ConsultaDePosicao(
    private val config: ConfigRealtime,
    private val tokenDeSessao: () -> String?,
    private val client: OkHttpClient = clientePadrao(),
) {

    /**
     * @return `null` quando o par não é localizável — fora do talk group, sem
     *   posição, ou indicativo inexistente. **As três causas colapsam de
     *   propósito**: distinguir "existe mas você não pode ver" de "não existe"
     *   entregaria, pela mensagem de erro, a informação que a política de
     *   privacidade nega pelo dado.
     *
     * Repare em [RespostaDePosicao.idadeDoSolicitanteS]: a distância e o rumo são
     * medidos a partir da **última posição publicada de quem pergunta**, não da
     * correção que o celular tem agora. As duas divergem exatamente na situação
     * em que a consulta mais importa — agente que ficou sem rede, saiu de um
     * prédio e perguntou onde está o par. Quem chama tem de olhar esse campo
     * antes de falar a distância como verdade.
     */
    suspend fun onde(indicativo: String): Result<RespostaDePosicao?> = withContext(Dispatchers.IO) {
        val token = tokenDeSessao()
            ?: return@withContext Result.failure(IllegalStateException("sem sessão"))

        val corpo = JSONObject()
            .put("indicativo", indicativo)
            .toString()
            .toRequestBody(JSON)

        val req = Request.Builder()
            .url("${config.projetoUrl}/rest/v1/rpc/consultar_posicao")
            .addHeader("apikey", config.apiKey)
            // O token do agente, não a chave anônima: é ele que faz o RLS saber
            // quem pergunta. Com a chave anônima a função não teria solicitante.
            .addHeader("Authorization", "Bearer $token")
            .post(corpo)
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) return@use null
                interpretar(arr.getJSONObject(0))
            }
        }
    }

    private fun interpretar(o: JSONObject): RespostaDePosicao = RespostaDePosicao(
        indicativo = o.getString("indicativo_alvo"),
        distanciaM = o.getDouble("distancia_m").toInt(),
        // `optDouble`, não `getDouble`: `getDouble` LANÇA quando o campo é nulo, e
        // o azimute é nulo sempre que os dois pontos coincidem. A dupla na mesma
        // viatura fazia a consulta inteira virar "Consulta indisponível."
        azimuteGraus = o.optDouble("azimute").takeIf { !it.isNaN() },
        // Mesma razão: NaN aqui viraria "deslocando a NaN m/s". Velocidade
        // ausente é ausente, não zero.
        velocidadeMs = o.optDouble("speed_mps").takeIf { !it.isNaN() }?.toFloat(),
        idadeS = o.getInt("idade_s"),
        idadeDoSolicitanteS = o.optInt("idade_solicitante_s", Int.MAX_VALUE),
    )

    private companion object {
        val JSON = "application/json".toMediaType()

        /**
         * Timeout curto e deliberado. A meta de ponta a ponta é 3 s pela rede;
         * uma consulta que demora 10 s já foi respondida pelo rádio de verdade, e
         * a resposta atrasada chegaria como interrupção sem contexto.
         */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}
