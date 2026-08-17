package com.claryon.net

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Um talk group endereçável por voz. */
data class GrupoFalado(
    val id: String,
    /** Como o agente o chama em voz alta, já normalizado. Ex.: `"guarnicao 3"`. */
    val rotuloFalado: String,
    /** Como a tela o mostra. Ex.: `"GTA-3 Alfa"`. */
    val nome: String,
)

/**
 * **O léxico fechado de canais que o agente pode abrir pela fala.**
 *
 * Carregado uma vez, no login, e não a cada comando: resolver "guarnição 3" é
 * casamento contra uma lista curta que muda quando a lotação muda — não quando o
 * agente fala. Consultar a rede no caminho do gatilho somaria latência de rede à
 * meta de 1 200 ms e deixaria o comando refém do sinal, justamente onde o rádio
 * precisa funcionar.
 *
 * ## Por que a lista vem do servidor, e não de uma constante
 *
 * `public.meus_rotulos_falados()` (migração `0011`) devolve só os grupos de que
 * **este** agente é membro, com o solicitante saindo do JWT. É o que faz "falar
 * um grupo a que não pertenço" ser recusável sem o cliente precisar saber de
 * autorização: o que não está na lista não é endereçável, e o que está já passou
 * pela `memberships`.
 *
 * A validação real continua no servidor — `pedir_canal` recusa não-membro em
 * `0005_controle_de_piso.sql:78-82`. Esta lista é conveniência de UX e economia
 * de rede, **nunca** a fronteira de segurança. Um cliente adulterado que peça um
 * grupo fora dela continua sendo recusado onde importa.
 */
class RotulosFalados(
    private val config: ConfigRealtime,
    private val tokenDeSessao: suspend () -> String?,
    private val cliente: OkHttpClient = OkHttpClient(),
) {

    /**
     * Busca o léxico. `withContext(IO)` **dentro** da função, para a garantia
     * valer para todo chamador futuro — e não depender de cada um lembrar.
     */
    suspend fun carregar(): Result<List<GrupoFalado>> = withContext(Dispatchers.IO) {
        val token = tokenDeSessao()
            ?: return@withContext Result.failure(
                ClaryonError.Sync("rotulos.sem_sessao", "Sem sessão: o léxico de canais é por agente."),
            )

        val req = Request.Builder()
            .url("${config.projetoUrl.trimEnd('/')}/rest/v1/rpc/meus_rotulos_falados")
            .addHeader("apikey", config.apiKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            cliente.newCall(req).execute().use { resp ->
                val corpo = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use Result.failure(
                        ClaryonError.Sync("rotulos.http_${resp.code}", corpo.take(200)),
                    )
                }
                val arr = JSONArray(corpo)
                val saida = ArrayList<GrupoFalado>(arr.length())
                for (i in 0 until arr.length()) {
                    val o: JSONObject = arr.getJSONObject(i)
                    // `isNull` e não `optString`: para JSON nulo o `optString`
                    // devolve a STRING "null", que casaria com um comando falado
                    // e abriria um canal inexistente. Este projeto já foi mordido
                    // por isso uma vez, no fio do canal.
                    if (o.isNull("talk_group_id") || o.isNull("rotulo_falado")) continue
                    saida.add(
                        GrupoFalado(
                            id = o.getString("talk_group_id"),
                            rotuloFalado = o.getString("rotulo_falado"),
                            nome = if (o.isNull("nome")) "" else o.getString("nome"),
                        ),
                    )
                }
                Result.success(saida.toList())
            }
        }.getOrElse { e ->
            Result.failure(ClaryonError.Sync("rotulos.rede", e.message ?: "falha ao carregar léxico"))
        }
    }

    companion object {
        /**
         * Normaliza para a forma em que o léxico é comparado: minúsculas, sem
         * acento, **sem pontuação**, espaços colapsados.
         *
         * A mesma função tem de ser usada nos DOIS lados — no que vem do STT e no
         * que veio do servidor. Normalizar só um lado é como não normalizar: a
         * comparação falha em silêncio e o agente conclui que o produto não
         * entende português.
         *
         * ## A pontuação não estava aqui, e isso quebrava a feature inteira
         *
         * Medido no aparelho em 2026-08-17: o agente disse *"Claryon, mudar para
         * guarnição quatro"* e o Whisper devolveu **"...guarnição 4."** — com ponto
         * final, que é o que todo decodificador de STT produz no fim de frase. Sem
         * remover pontuação, `"guarnicao 4."` nunca casa `"guarnicao 4"`, e a troca
         * de grupo por voz recusava **toda** troca com "Não conheço essa
         * guarnição". Nenhum teste de unidade pegava: todos alimentavam rótulos
         * limpos, escritos à mão.
         *
         * Achado pelo `VerificadorDoGatilhoTest`, que é exatamente a diferença
         * entre testar cada elo e testar a corrente.
         *
         * **Dígito NÃO é colapsado**, de propósito: o léxico do servidor grava
         * `'guarnicao 3'` (migração `0011:93`) e o Whisper também produz dígito, então
         * os dois lados convergem. Traduzir "três" ↔ "3" aqui seria inventar uma
         * equivalência que o cadastro não pediu — e "três" versus "treze" é a
         * diferença entre duas viaturas.
         */
        fun normalizar(texto: String): String =
            java.text.Normalizer.normalize(texto.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[\\p{Punct}…“”‘’—–]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
