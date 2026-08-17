package com.claryon.field.auth

import android.content.Context
import com.claryon.field.BuildConfig
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.ConfigRealtime

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

    val config = ConfigRealtime(
        projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
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
