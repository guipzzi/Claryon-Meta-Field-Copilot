package com.claryon.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 * **O que a leitura SEM ESPERA do token encontrou.**
 *
 * O tipo existe para que "não tenho token agora" deixe de ser um `null` que o
 * chamador interpreta como quiser. As três respostas pedem condutas diferentes:
 * [Valido] segue, [Vencido] recusa **e a renovação já foi disparada** (a próxima
 * tentativa tende a funcionar), [SemSessao] pede login.
 *
 * Nenhum dos três caminhos toca a rede. Ver [AutenticacaoSupabase.tokenSemEsperar].
 */
sealed interface TokenSemEspera {

    /**
     * Há token utilizável **agora**.
     *
     * @param restanteMs quanto falta para expirar. Pode ser menor que
     *   [AutenticacaoSupabase.MARGEM_MS] — nesse caso uma renovação já foi
     *   disparada em segundo plano, e este token continua sendo o melhor que
     *   existe até ela voltar. Recusá-lo aqui trocaria um risco pequeno (401 no
     *   meio da viagem) por uma recusa certa.
     */
    data class Valido(val token: String, val restanteMs: Long) : TokenSemEspera

    /** Há sessão, o token venceu, e a renovação foi disparada. Esta chamada não espera. */
    data object Vencido : TokenSemEspera

    /** Nunca houve sessão neste aparelho, ou o turno foi encerrado. */
    data object SemSessao : TokenSemEspera
}

/** O token, quando há. Açúcar para quem só precisa do `String?`. */
val TokenSemEspera.tokenOuNulo: String?
    get() = (this as? TokenSemEspera.Valido)?.token

/**
 * **Autenticação do agente contra o Supabase.**
 *
 * É o que faltava para C2 fechar: `public.consultar_posicao` deriva o
 * solicitante do JWT, então sem sessão o servidor não sabe quem pergunta — e a
 * consulta responde "indisponível" por desenho.
 *
 * Quatro decisões que valem registro:
 *
 *  1. **A senha nunca é guardada.** Só o par de tokens. Uma senha em repouso no
 *     aparelho é uma senha que vaza junto com o aparelho, e o aparelho de campo
 *     é justamente o que se perde.
 *  2. **A renovação é serializada por um `Mutex`.** Sem isso, a consulta de voz e
 *     a publicação de posição batendo juntas num token expirado disparariam duas
 *     renovações concorrentes — e a segunda invalidaria o *refresh token* que a
 *     primeira acabou de usar, derrubando a sessão inteira no meio do turno.
 *  3. **Há dois acessos ao token, e eles têm contratos opostos.** [tokenSemEsperar]
 *     **nunca** toca a rede e é o único que pode ser chamado do ciclo de voz;
 *     [tokenValido] renova e **espera**, e existe para quem pode esperar (o
 *     WebSocket do rádio, o mapa, o login). Confundir os dois foi o defeito de
 *     22/08: `tokenValido` era chamado de dentro do ciclo de voz, com o comentário
 *     *"runBlocking NÃO: a renovação não pode travar o ciclo"* uma linha acima.
 *     A intenção estava lá; o caminho é que furava.
 *  4. **A espera tem teto, e o teto é medido.** Ver [clientePadrao] e
 *     [TETO_DA_CHAMADA_MS].
 *
 * ## Por que o caminho crítico deixou de encontrar token vencido
 *
 * Três camadas, e cada uma cobre a falha da anterior:
 *
 * | Camada | O que faz | Custo |
 * |---|---|---|
 * | [manterFresco] | renova em segundo plano **10 min antes** de expirar | ~1 requisição/hora |
 * | [tokenSemEsperar] | ao ver a margem estourada, **dispara** renovação e devolve o que tem | zero rede |
 * | [clientePadrao] | limita a renovação a [TETO_DA_CHAMADA_MS] | — |
 *
 * A terceira é a rede de segurança das outras duas: a corrotina que renova
 * bloqueia, e por isso ela nunca é a do agente.
 *
 * @param escopo onde a renovação de segundo plano roda. Injetável para que o
 *   teste não dependa de um escopo global — nunca para que produção troque por um
 *   escopo de tela: renovação que morre com a Activity é o defeito que este
 *   arquivo inteiro existe para não ter.
 */
class AutenticacaoSupabase(
    private val config: ConfigRealtime,
    private val cofre: CofreDeSessao,
    private val agora: () -> Long = { System.currentTimeMillis() },
    private val client: OkHttpClient = clientePadrao(),
    private val escopo: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val mutex = Mutex()

    // ── Espelho em memória do cofre ───────────────────────────────────────────

    /**
     * A sessão que está no cofre, espelhada em RAM.
     *
     * Existe porque [tokenSemEsperar] promete "nem rede nem E/S", e ler o cofre
     * **não é de graça**: a implementação do app é `EncryptedSharedPreferences`, e
     * cada `ler()` decifra quatro valores com AES-256-GCM. Prometer "sem espera" e
     * fazer quatro operações de criptografia por chamada seria a mesma classe de
     * meia-verdade que o KDoc anterior carregava.
     *
     * O espelho é seguro porque **este objeto é o único escritor do cofre** no
     * processo (o dono de processo em `app` garante uma instância só). Todo
     * caminho de escrita passa por [gravarSessao] ou [apagarSessao].
     */
    @Volatile
    private var emMemoria: Sessao? = null

    /** `null` em [emMemoria] é ambíguo entre "não há sessão" e "ainda não li". */
    @Volatile
    private var jaLeuOCofre = false

    private fun sessao(): Sessao? {
        if (jaLeuOCofre) return emMemoria
        return synchronized(this) {
            if (jaLeuOCofre) return@synchronized emMemoria
            cofre.ler().also { emMemoria = it; jaLeuOCofre = true }
        }
    }

    private fun gravarSessao(s: Sessao) {
        cofre.gravar(s)
        emMemoria = s
        jaLeuOCofre = true
    }

    private fun apagarSessao() {
        cofre.apagar()
        emMemoria = null
        jaLeuOCofre = true
    }

    fun autenticado(): Boolean = sessao() != null

    fun agentId(): String? = sessao()?.agentId

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

    // ── O acesso do caminho crítico ───────────────────────────────────────────

    /**
     * **O token sem esperar por nada.** É este que o ciclo de voz chama.
     *
     * Não abre socket, não entra no `Mutex`, não decifra nada: lê o espelho de
     * [emMemoria] e compara dois `Long`. O teto é uma leitura de campo `@Volatile`
     * — medido em `AutenticacaoTest.oTetoDaLeituraSemEsperaEDeMemoria`.
     *
     * Quando o token está dentro de [MARGEM_MS] (ou já venceu), **dispara** uma
     * renovação em segundo plano e devolve na hora o que tem. O agente que dá um
     * comando com o token vencido ouve a recusa tipada — "Consulta indisponível."
     * — em vez de esperar a rede dentro de um ciclo que tem 4 s de aceite; e o
     * comando seguinte já encontra o token novo.
     *
     * O par certo desta função é [manterFresco]: com ele ligado, o ramo [
     * TokenSemEspera.Vencido] só é alcançável quando o processo passou mais tempo
     * dormindo do que o token vive.
     */
    fun tokenSemEsperar(): TokenSemEspera {
        val atual = sessao() ?: return TokenSemEspera.SemSessao
        val restanteMs = atual.expiraEmMs - agora()
        // Vencido e "quase vencendo" disparam a MESMA renovação. A diferença está
        // só no que é devolvido: quase vencendo ainda serve, vencido não serve.
        if (restanteMs <= MARGEM_MS) dispararRenovacao()
        return if (restanteMs > 0) {
            TokenSemEspera.Valido(atual.accessToken, restanteMs)
        } else {
            TokenSemEspera.Vencido
        }
    }

    /**
     * Renovação em segundo plano, no máximo uma em voo.
     *
     * Sem a trava, um ciclo de voz que consultasse o token três vezes lançaria três
     * corrotinas; elas se serializariam no `Mutex` e as duas últimas viriam a ser
     * no-ops pela reconferência — mas continuariam sendo trabalho e sockets
     * agendados por nada, dentro de uma ocorrência.
     */
    private val renovacaoEmVoo = AtomicBoolean(false)

    private fun dispararRenovacao() {
        if (!renovacaoEmVoo.compareAndSet(false, true)) return
        escopo.launch {
            try {
                tokenValido()
            } finally {
                renovacaoEmVoo.set(false)
            }
        }
    }

    // ── O acesso de quem pode esperar ─────────────────────────────────────────

    /**
     * O token para usar agora, renovando se preciso, **e esperando a rede**.
     *
     * Renova quando falta menos que [MARGEM_MS] para expirar. A margem existe
     * porque um token que expira no meio da viagem já saiu válido daqui e volta
     * como 401 — e a consulta de voz não tem uma segunda chance útil.
     *
     * ## Esta função BLOQUEIA, e agora isso é uma escolha e não um acidente
     *
     * **Dentro da margem:** devolve o espelho. Sem rede, sem `Mutex`.
     *
     * **Fora da margem:** entra no `Mutex` e chama [renovar], que faz
     * `client.newCall(req).execute()` — **síncrono**. Quem chama espera; quem
     * chegar depois espera também, porque o lock é o mesmo.
     *
     * O que mudou em 22/08 não foi isso: foi o **teto** e a **lista de chamadores**.
     * O teto passou a existir ([clientePadrao]) e o ciclo de voz saiu daqui para
     * [tokenSemEsperar]. Quem pode esperar continua chamando aqui — o WebSocket do
     * rádio (que precisa de token válido para reconectar), o mapa, o próprio
     * [manterFresco].
     *
     * **Não chame isto do ciclo de voz.** A varredura
     * `TokenNaoTravaOCicloDeVozTest` reprova o build se voltar.
     */
    suspend fun tokenValido(): String? {
        val atual = sessao() ?: return null
        if (agora() < atual.expiraEmMs - MARGEM_MS) return atual.accessToken

        return mutex.withLock {
            // Reconferir dentro do lock: outra corrotina pode ter renovado
            // enquanto esta esperava, e renovar de novo queimaria o refresh token
            // que acabou de ser emitido.
            val depois = sessao() ?: return@withLock null
            if (agora() < depois.expiraEmMs - MARGEM_MS) return@withLock depois.accessToken
            renovar(depois.refreshToken).getOrNull()?.accessToken
        }
    }

    // ── Renovação antecipada ──────────────────────────────────────────────────

    @Volatile
    private var vigia: Job? = null

    /**
     * **Mantém o token fresco para que o caminho crítico nunca precise dele.**
     *
     * Dorme até faltarem [MARGEM_ANTECIPADA_MS] para o vencimento, renova, e volta
     * a dormir. Idempotente: chamar duas vezes devolve o mesmo [Job].
     *
     * ## Os números
     *
     * O JWT do Supabase vive **60 min** (medido, registrado no KDoc de
     * `SessaoDoAgente.config`). Com margem antecipada de 10 min, isto é **uma
     * requisição a cada 50 min** — 1,2/hora. Para comparação, o `RadioViewModel`
     * faz uma a cada 10 s, 360/hora. O custo de energia deste laço é ruído dentro
     * do que o app já gasta.
     *
     * Os 10 min não são gosto: são a **pista de retentativa**. Renovar faltando
     * 60 s daria uma tentativa; faltando 10 min, com [PAUSA_APOS_FALHA_MS] de
     * espera entre elas, dá dez — o suficiente para atravessar um túnel, um
     * elevador ou uma troca de célula sem o turno perder a sessão.
     *
     * ## O que ele NÃO cobre, e por isso [tokenSemEsperar] também dispara
     *
     * `delay` não roda em *doze*. Um aparelho no bolso por duas horas acorda com o
     * laço atrasado, e o token pode já estar vencido quando o agente aperta o PTT.
     * É aí que a segunda camada entra: a leitura sem espera dispara a renovação e
     * recusa **esta** consulta, não as seguintes.
     */
    fun manterFresco(): Job = vigia ?: synchronized(this) {
        vigia ?: escopo.launch {
            while (isActive) {
                val s = sessao()
                val esperaMs = if (s == null) {
                    PAUSA_SEM_SESSAO_MS
                } else {
                    (s.expiraEmMs - MARGEM_ANTECIPADA_MS - agora()).coerceAtLeast(0L)
                }
                if (esperaMs > 0) {
                    delay(esperaMs)
                    continue
                }
                // Esta corrotina PODE bloquear — é exatamente para isso que ela
                // existe. O teto dela é `TETO_DA_CHAMADA_MS`.
                tokenValido()
                val depois = sessao()
                val avancou = depois != null &&
                    depois.expiraEmMs - MARGEM_ANTECIPADA_MS - agora() > 0
                // Sem `delay` aqui, uma renovação que falha por falta de rede vira
                // laço apertado: milhares de tentativas por minuto, com o rádio
                // ligado, no meio de uma ocorrência.
                if (!avancou) delay(PAUSA_APOS_FALHA_MS)
            }
        }.also { vigia = it }
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
                if ((falha as? ErroDeAutenticacao)?.recuperavel == false) apagarSessao()
            }
        }

    fun sair() = apagarSessao()

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
            ).also(::gravarSessao)
        }
    }

    private fun emailDe(matricula: String): String =
        "${matricula.trim().lowercase()}@$DOMINIO_SINTETICO"

    companion object {

        /**
         * 60 s. Um token que expira no meio da viagem sai válido daqui e volta
         * como 401, e a consulta de voz não tem segunda chance útil.
         */
        const val MARGEM_MS = 60_000L

        /**
         * 10 min. É quando [manterFresco] acorda para renovar — bem antes da
         * [MARGEM_MS], que é o limiar de *emergência*. Ver o KDoc de [manterFresco]
         * para a conta.
         */
        const val MARGEM_ANTECIPADA_MS = 600_000L

        /**
         * **O teto de uma chamada de autenticação. 6 s, e ele não existia.**
         *
         * O cliente era `OkHttpClient()` de fábrica. Os defaults dele — conferidos
         * por leitura dos getters no próprio artefato, não de memória, em
         * `AutenticacaoTest.oClienteDeFabricaNaoTemTeto` — são
         * `callTimeoutMillis() == 0` (**sem teto**) e 10 000 ms para conectar, ler e
         * escrever, cada. Com `retryOnConnectionFailure` ligado e um host com
         * registro A **e** AAAA, o OkHttp tenta rota por rota: o pior caso não era
         * "10 s", era "10 s vezes o número de rotas", sem limite superior escrito
         * em lugar nenhum.
         *
         * 6 s = 2 s para conectar + 3 s para ler + 1 s de folga, e a folga não é
         * arredondamento: DNS e handshake TLS cabem dentro do `connectTimeout` de
         * cada rota, e o `callTimeout` é o único que cobre **a chamada inteira**,
         * incluindo retentativas e redirecionamentos.
         *
         * A escala é a mesma que `ConsultaDePosicao` já usa (2 s/3 s), e por um
         * motivo: renovar token e consultar posição concorrem pelo mesmo turno de
         * rede do mesmo aparelho. Um teto de 6 s para a renovação **não** é o
         * orçamento do ciclo de voz — o ciclo não espera por ela desde
         * [tokenSemEsperar]. É o que impede a renovação de segundo plano de segurar
         * o `Mutex` por meio minuto e transformar toda leitura seguinte em fila.
         *
         * Medido em `AutenticacaoTest.oTetoDaRenovacaoEOCallTimeout`.
         */
        const val TETO_DA_CHAMADA_MS = 6_000L

        /**
         * 60 s entre tentativas de [manterFresco] que não avançaram. Dez
         * tentativas cabem na [MARGEM_ANTECIPADA_MS] antes de o token morrer de
         * verdade.
         */
        internal const val PAUSA_APOS_FALHA_MS = 60_000L

        /**
         * 5 min. Sem sessão não há o que renovar, e o laço só precisa acordar para
         * perceber que houve login — que também é anunciado por outros caminhos.
         */
        internal const val PAUSA_SEM_SESSAO_MS = 300_000L

        /**
         * O cliente com teto. Ver [TETO_DA_CHAMADA_MS].
         *
         * `callTimeout(long, TimeUnit)` conferido por `javap` em
         * `okhttp-4.12.0.jar` (`OkHttpClient$Builder.callTimeout(long,
         * java.util.concurrent.TimeUnit)`), junto do getter
         * `OkHttpClient.callTimeoutMillis()` que o teste usa para medir.
         */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(TETO_DA_CHAMADA_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        private val JSON = "application/json".toMediaType()

        /**
         * Domínio reservado por RFC 2606 para exemplos — nunca resolve, nunca
         * receberá e-mail. O cadastro da corporação não tem e-mail por agente, e
         * usar um domínio real criaria endereços que parecem entregáveis.
         */
        private const val DOMINIO_SINTETICO = "claryon.invalid"
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
