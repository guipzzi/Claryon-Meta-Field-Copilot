package com.claryon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Sessão do agente. **Isto é credencial**, e o [CofreDeSessao] que a guarda tem
 * de tratá-la como tal.
 *
 * @param expiraEmMs instante absoluto, não duração. Duração exigiria saber
 *   quando foi emitida, e esse "quando" some quando o processo morre.
 */
data class Sessao(
    val accessToken: String,
    val refreshToken: String,
    val expiraEmMs: Long,
    val agentId: String?,
)

/**
 * Onde a sessão é guardada entre execuções.
 *
 * Interface, e não implementação direta, por dois motivos: o armazenamento
 * seguro é específico do Android (Keystore) e não teria como ser exercitado na
 * JVM; e a política de renovação — que é o que tem regra — fica testável sem
 * aparelho.
 */
interface CofreDeSessao {
    fun ler(): Sessao?
    fun gravar(sessao: Sessao)
    fun apagar()
}

/** Por que a autenticação falhou. Cada uma pede uma coisa diferente do agente. */
sealed interface FalhaDeLogin {
    /** Matrícula ou senha errada. */
    data object CredencialInvalida : FalhaDeLogin

    /** Autenticou, mas o usuário não está vinculado a nenhum agente no cadastro. */
    data object SemAgenteVinculado : FalhaDeLogin

    /** Rede fora. Tentar de novo resolve; trocar a senha não. */
    data object SemRede : FalhaDeLogin

    data class Servidor(val codigo: Int) : FalhaDeLogin
}

/**
 * **Autenticação do agente contra o Supabase.**
 *
 * É o que faltava para C2 fechar: `public.consultar_posicao` deriva o
 * solicitante do JWT, então sem sessão o servidor não sabe quem pergunta — e a
 * consulta responde "indisponível" por desenho.
 *
 * Três decisões que valem registro:
 *
 *  1. **A senha nunca é guardada.** Só o par de tokens. Uma senha em repouso no
 *     aparelho é uma senha que vaza junto com o aparelho, e o aparelho de campo
 *     é justamente o que se perde.
 *  2. **A renovação é serializada por um `Mutex`.** Sem isso, a consulta de voz e
 *     a publicação de posição batendo juntas num token expirado disparariam duas
 *     renovações concorrentes — e a segunda invalidaria o *refresh token* que a
 *     primeira acabou de usar, derrubando a sessão inteira no meio do turno.
 *  3. **[tokenValido] nunca bloqueia esperando rede.** Ela devolve o que tem, ou
 *     `null`. Quem chama transforma `null` em falha honesta e falada. Um ciclo de
 *     voz que trava 30 s esperando renovação é indistinguível de app morto.
 */
class AutenticacaoSupabase(
    private val config: ConfigRealtime,
    private val cofre: CofreDeSessao,
    private val agora: () -> Long = { System.currentTimeMillis() },
    private val client: OkHttpClient = OkHttpClient(),
) {

    private val mutex = Mutex()

    fun autenticado(): Boolean = cofre.ler() != null

    fun agentId(): String? = cofre.ler()?.agentId

    /**
     * Entra com matrícula e senha. A matrícula vira o e-mail sintético que o
     * Supabase Auth exige — o cadastro da corporação não tem e-mail por agente, e
     * inventar um domínio real seria pior.
     */
    suspend fun entrar(matricula: String, senha: String): Result<Sessao> =
        withContext(Dispatchers.IO) {
            val corpo = JSONObject()
                .put("email", emailDe(matricula))
                .put("password", senha)
                .toString()
                .toRequestBody(JSON)

            val req = Request.Builder()
                .url("${config.projetoUrl}/auth/v1/token?grant_type=password")
                .addHeader("apikey", config.apiKey)
                .post(corpo)
                .build()

            executar(req)
        }

    /**
     * O token para usar agora, ou `null`.
     *
     * Renova quando falta menos que [MARGEM_MS] para expirar. A margem existe
     * porque um token que expira no meio da viagem já saiu válido daqui e volta
     * como 401 — e a consulta de voz não tem uma segunda chance útil.
     */
    suspend fun tokenValido(): String? {
        val atual = cofre.ler() ?: return null
        if (agora() < atual.expiraEmMs - MARGEM_MS) return atual.accessToken

        return mutex.withLock {
            // Reconferir dentro do lock: outra corrotina pode ter renovado
            // enquanto esta esperava, e renovar de novo queimaria o refresh token
            // que acabou de ser emitido.
            val depois = cofre.ler() ?: return@withLock null
            if (agora() < depois.expiraEmMs - MARGEM_MS) return@withLock depois.accessToken
            renovar(depois.refreshToken).getOrNull()?.accessToken
        }
    }

    private suspend fun renovar(refreshToken: String): Result<Sessao> =
        withContext(Dispatchers.IO) {
            val corpo = JSONObject()
                .put("refresh_token", refreshToken)
                .toString()
                .toRequestBody(JSON)

            val req = Request.Builder()
                .url("${config.projetoUrl}/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", config.apiKey)
                .post(corpo)
                .build()

            executar(req).onFailure { falha ->
                // 400/401 na renovação = refresh token morto (revogado, expirado,
                // ou já usado). Apagar é o certo: manter uma sessão que nunca vai
                // funcionar faria o app tentar para sempre em vez de pedir login.
                if ((falha as? ErroDeAutenticacao)?.recuperavel == false) cofre.apagar()
            }
        }

    fun sair() = cofre.apagar()

    private fun executar(req: Request): Result<Sessao> = runCatching {
        client.newCall(req).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ErroDeAutenticacao(
                    codigo = resp.code,
                    // 4xx é credencial; 5xx e rede são transitórios. A distinção
                    // decide se o agente troca a senha ou só tenta de novo.
                    recuperavel = resp.code >= 500,
                )
            }
            val json = JSONObject(texto)
            Sessao(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                // `expires_in` em segundos → instante absoluto.
                expiraEmMs = agora() + json.optLong("expires_in", 3600) * 1000L,
                agentId = json.optJSONObject("user")
                    ?.optJSONObject("user_metadata")
                    ?.optString("agent_id")
                    ?.takeIf { it.isNotBlank() },
            ).also(cofre::gravar)
        }
    }

    private fun emailDe(matricula: String): String =
        "${matricula.trim().lowercase()}@$DOMINIO_SINTETICO"

    private companion object {
        val JSON = "application/json".toMediaType()

        /**
         * 60 s. Um token que expira no meio da viagem sai válido daqui e volta
         * como 401, e a consulta de voz não tem segunda chance útil.
         */
        const val MARGEM_MS = 60_000L

        /**
         * Domínio reservado por RFC 2606 para exemplos — nunca resolve, nunca
         * receberá e-mail. O cadastro da corporação não tem e-mail por agente, e
         * usar um domínio real criaria endereços que parecem entregáveis.
         */
        const val DOMINIO_SINTETICO = "claryon.invalid"
    }
}

/** Falha HTTP na autenticação. [recuperavel] separa "tente de novo" de "troque a senha". */
class ErroDeAutenticacao(val codigo: Int, val recuperavel: Boolean) :
    Exception("autenticação falhou: HTTP $codigo")

/** Traduz a exceção para a causa que o agente precisa entender. */
fun falhaDeLoginDe(erro: Throwable): FalhaDeLogin = when {
    erro is ErroDeAutenticacao && erro.codigo in 400..499 -> FalhaDeLogin.CredencialInvalida
    erro is ErroDeAutenticacao -> FalhaDeLogin.Servidor(erro.codigo)
    erro is java.io.IOException -> FalhaDeLogin.SemRede
    else -> FalhaDeLogin.Servidor(0)
}
