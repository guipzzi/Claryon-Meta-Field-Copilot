package com.claryon.field.auth

import android.content.Context
import com.claryon.field.BuildConfig
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.ConfigRealtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * **Dono único da sessão do agente no processo.**
 *
 * ## Por que sair do `DiagnosticsViewModel`
 *
 * A sessão do app inteiro estava ancorada num ViewModel chamado "Diagnostics", e
 * isso bloqueava a decomposição dele: **nenhum** dos pedaços propostos (mapa,
 * copiloto, evidência) é dono natural da autenticação, porque os três a usam —
 * e o `MainActivity` também, para decidir se mostra a tela de login
 * (`autenticado()`), e o `RadioViewModel` também, para o token do histórico do
 * canal. Deixar `autenticacao` dentro de qualquer um dos três faria o
 * `MainActivity` instanciar aquele ViewModel só para abrir o portão de login.
 *
 * Terceiro objeto de processo do projeto, pelo mesmo critério dos dois
 * anteriores ([com.claryon.field.audio.AudioDoAgente],
 * [com.claryon.field.audio.SaidaUnica]): **o recurso é do processo, não do grafo
 * de objetos**. Existe uma sessão por agente, guardada num cofre cifrado por
 * Keystore; duas instâncias significariam dois tokens em voo e renovações
 * concorrentes sobre o mesmo `refresh_token`, que o servidor invalida ao usar.
 *
 * ## `tokenCorrente` é cache, e a distinção importa
 *
 * [tokenCorrente] é a última credencial **já validada**, lida de forma síncrona
 * por quem não pode suspender — o `ConsultaDePosicao` do ciclo de voz. Quem
 * pode suspender chama [tokenValido], que renova. Misturar os dois era o que
 * fazia o mapa (que renova) e o copiloto (que só lê) dependerem de um campo
 * mutável compartilhado dentro do ViewModel, sem que nada no tipo dissesse isso.
 */
object SessaoDoAgente {

    /** `false` quando `local.properties` não trouxe o projeto: sem servidor, sem C2. */
    val redeConfigurada: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /**
     * A configuração do canal — **uma só**, e é de propósito.
     *
     * [tokenDoAgente] é função e não string porque o JWT do Supabase vive **60
     * min** (medido, não suposto). A doc é explícita: sem receber token novo pelo
     * canal, o cliente é desconectado no vencimento. Guardar uma cópia daria um
     * rádio que morre sozinho no meio do turno, e isso falha em campo — não na
     * bancada. `tokenValido()` já renova com margem e é seguro chamar sempre.
     *
     * [privado] liga a política da migração `0012`. Ligado depois de exercitada
     * com o par headless, na ordem que o cabeçalho da migração prescreve:
     *
     * ```
     * agente do grupo, canal privado ....... phx_reply status=ok
     * agente FORA do grupo, canal privado .. Unauthorized: You do not have
     *                                        permissions to read from this
     *                                        Channel topic
     * agente FORA do grupo, canal público .. status=ok   ← o defeito de antes
     * ```
     *
     * A terceira linha é a que justifica tudo: sem `private`, qualquer portador
     * do APK entrava em qualquer guarnição.
     */
    val config = ConfigRealtime(
        projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
        tokenDoAgente = { instancia?.tokenValido() },
        privado = true,
    )

    @Volatile
    private var instancia: AutenticacaoSupabase? = null

    /**
     * Último token **já validado**. Leitura síncrona para quem não pode
     * suspender (o ciclo de voz não pode travar esperando renovação de rede).
     *
     * `null` significa "nunca houve sessão nesta execução", e quem lê trata como
     * indisponível em vez de tentar sem credencial — a diferença entre "consulta
     * indisponível" e "companheiro não localizado", que o produto não pode
     * confundir.
     */
    @Volatile
    var tokenCorrente: String? = null
        private set

    fun de(context: Context): AutenticacaoSupabase =
        instancia ?: synchronized(this) {
            instancia ?: AutenticacaoSupabase(
                config = config,
                cofre = CofreDeSessaoCifrado(context.applicationContext),
            ).also { instancia = it }
        }

    // ── Estado da sessão: assíncrono porque a leitura É assíncrona ────────────

    /**
     * O que se sabe sobre a sessão **neste instante**.
     *
     * [Verificando] existe porque a resposta não é conhecida de imediato, e
     * fingir que é foi o defeito: `MainActivity` chamava `autenticacao.autenticado()`
     * direto na composição, e a primeira chamada dispara
     * `MasterKey.Builder.build()` → `KeyStore.containsAlias` → **468 ms de E/S de
     * Keystore na thread principal**, medidos pelo StrictMode. Meio segundo de
     * tela congelada no portão de entrada do app.
     *
     * Sem [Verificando], mover a leitura para fora da Main teria custado pior:
     * o portão veria "não autenticado" antes de a leitura terminar e piscaria a
     * tela de login na cara de um agente que já tem sessão.
     */
    sealed interface EstadoDaSessao {
        /** A leitura do cofre ainda não terminou. Não é "sem sessão". */
        data object Verificando : EstadoDaSessao
        data object Autenticado : EstadoDaSessao
        data object Ausente : EstadoDaSessao
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _estado = MutableStateFlow<EstadoDaSessao>(EstadoDaSessao.Verificando)
    val estado: StateFlow<EstadoDaSessao> = _estado.asStateFlow()

    /**
     * Lê o cofre **fora da Main** e publica o resultado. Chamado uma vez, do
     * `Application.onCreate`: é o ponto de entrada do processo, e adiantar a
     * leitura ali é o que faz o portão de login já ter resposta quando a
     * primeira composição acontecer.
     */
    fun aquecer(context: Context) {
        escopo.launch {
            val auth = de(context)
            // `autenticado()` toca o Keystore; aqui isso é legítimo, porque
            // estamos em Dispatchers.IO.
            _estado.value =
                if (auth.autenticado()) EstadoDaSessao.Autenticado else EstadoDaSessao.Ausente
        }
    }

    /** Chamado ao entrar (login bem-sucedido) e ao encerrar o turno. */
    fun anunciar(autenticado: Boolean) {
        _estado.value =
            if (autenticado) EstadoDaSessao.Autenticado else EstadoDaSessao.Ausente
    }

    /**
     * Token válido, renovando se preciso, **e atualizando o cache**.
     *
     * É o único ponto que escreve [tokenCorrente]: antes, três lugares
     * diferentes faziam `autenticacao.tokenValido()?.also { tokenCorrente = it }`
     * e bastava um esquecer o `also` para o ciclo de voz passar a consultar com
     * um token velho.
     */
    suspend fun tokenValido(context: Context): String? =
        de(context).tokenValido()?.also { tokenCorrente = it }

    /** Só para teste instrumentado — devolve o objeto ao estado virgem. */
    fun instalar(substituta: AutenticacaoSupabase?) {
        synchronized(this) {
            instancia = substituta
            tokenCorrente = null
        }
    }
}
