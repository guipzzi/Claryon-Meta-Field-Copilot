package com.claryon.field.radio

import android.content.Context
import android.util.Log
import com.claryon.common.getOrNull
import com.claryon.field.agent.ClaryonIntentExecutor.TrocaDeGrupo
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.agent.FalhaOperacional
import com.claryon.net.GrupoFalado
import com.claryon.net.RotulosFalados
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **Dono de processo do léxico de canais e do grupo corrente.**
 *
 * ## Por que dono de processo, e não campo de ViewModel
 *
 * Dois donos precisam da mesma verdade e têm ciclos de vida diferentes:
 * `RadioViewModel` cria o `RadioTatico` e sabe *como* trocar; `CopilotoViewModel`
 * recebe o comando falado e sabe *quando*. Duplicar a lista em cada um daria duas
 * verdades — e o modo de falhar seria o agente trocar de grupo pela voz e a tela
 * continuar mostrando o anterior.
 *
 * É o mesmo padrão de `AudioDoAgente`, `SaidaUnica` e `SessaoDoAgente`: quando o
 * recurso é global, o dono é o processo.
 *
 * ## A dívida que este arquivo paga
 *
 * `RotulosFalados` existia desde a migração `0011` e **nunca foi instanciado** —
 * `grep` em `src/main` devolvia só a própria definição e o KDoc de
 * `CanalDoPiloto` prometendo que um dia seria usado. Sem chamador, a validação de
 * membership de `meus_rotulos_falados()` jamais era alcançada em runtime, e o
 * `ESTADO.md` afirmava "RotulosFalados carrega o léxico no cliente" — era falso.
 * O padrão "escrito, não construído" do `CLAUDE.md` §6, pela sétima vez.
 *
 * ## O fio é atado sempre; o que varia é a resposta
 *
 * Quem detém o `RadioTatico` é o `RadioViewModel`, e ele existe só enquanto a tela
 * da guarnição vive. Então o fio é **registrado** por quem o tem e limpo no
 * `onCleared`. Sem registro, [trocar] devolve [FalhaOperacional.RADIO_FECHADO] em
 * vez de fingir sucesso: dizer "Agora na guarnição três" com o rádio fechado seria
 * a pior resposta possível, porque o agente passaria a falar acreditando estar em
 * outro canal.
 *
 * **O registro não depende da rota de áudio, e é por isso que ele acontece antes
 * dela.** Até 22/08 [registrarRadio] morava depois do `return@launch` da falha de
 * HFP, e num aparelho sem fone pareado o fio nunca era atado: a recusa existia,
 * mas não tinha motivo para dar. Hoje o fio é atado com o predicado [noAr], e a
 * ausência de rádio sai como recusa **nomeada** — nunca como um `false` mudo, que o
 * executor traduziria para *"Canal ocupado."* num canal que não está ocupado.
 *
 * Registro em vez de injeção porque a alternativa — tornar o `RadioTatico` dono de
 * processo — é um refactor grande e com risco próprio (ele carrega escopo, socket e
 * codec). Fica registrado como o caminho melhor que não foi tomado agora.
 */
/**
 * **Qual canal adotar quando o cadastro chega.**
 *
 * Pura de propósito: o ramo que importa — o agente cuja lotação NÃO inclui o canal
 * provisório — não é alcançável no aparelho de teste, porque lá o agente pertence
 * ao grupo do piloto. Extraída, ela vira testável sem rede e sem servidor.
 *
 * A regra tem duas linhas e uma consequência de segurança em cada uma. Se o canal
 * corrente está na lista do agente, **fica** — trocar por trocar tiraria alguém de
 * uma guarnição em que ele já estava operando. Se não está, assume o primeiro da
 * lista: o agente não é daquela guarnição, e manter o id fixo o deixaria ouvindo e
 * falando com uma guarnição que não é a dele, com o nome da constante na tela
 * dizendo que está tudo certo.
 *
 * O nome vem SEMPRE do servidor, mesmo quando o id não muda: o da constante é chute
 * de código, o do cadastro é fato.
 */
fun escolherCanal(atual: String, lista: List<GrupoFalado>): GrupoFalado =
    lista.firstOrNull { it.id == atual } ?: lista.first()

object CanaisDoAgente {

    private const val TAG = "ClaryonField"

    private val mutex = Mutex()

    /** `null` = não carregou. Vazio ≠ nulo: ver [ResolvedorDeGrupo.Resultado.SemLexico]. */
    @Volatile
    private var lexico: List<GrupoFalado>? = null

    /**
     * **O grupo em que estamos, decidido pelo SERVIDOR.**
     *
     * Nasce em [CanalDoPiloto] e é substituído por [carregar] pelo primeiro grupo
     * que o cadastro do agente devolve. A constante deixou de ser o valor e virou
     * o que ela sempre deveria ter sido: o que vale enquanto a rede não respondeu,
     * e nada além disso.
     *
     * Isto não é arrumação. Enquanto o UUID era fixo, um agente de outra guarnição
     * que instalasse o APK entrava **no canal do piloto** — ouvia e falava com uma
     * guarnição que não é a dele. O servidor recusaria a escrita (RLS), mas o
     * áudio do Realtime e o mapa vinham do id que o cliente pedisse.
     */
    @Volatile
    var grupoCorrenteId: String = CanalDoPiloto.ID
        private set

    @Volatile
    var grupoCorrenteNome: String = CanalDoPiloto.NOME
        private set

    /**
     * `true` depois que o servidor disse quais são os grupos deste agente.
     *
     * Enquanto for `false`, o canal corrente é o provisório — e quem exibe estado
     * precisa poder dizer isso em vez de mostrar um nome de guarnição como se fosse
     * fato confirmado.
     */
    @Volatile
    var canalConfirmadoPeloServidor: Boolean = false
        private set

    /** O que sabe trocar de verdade. Registrado por quem detém o `RadioTatico`. */
    private var trocador: (suspend (String) -> Boolean)? = null

    /**
     * O que sabe pôr o agente no ar. Padrão que RECUSA, como o trocador: sem rádio
     * registrado, "guarnição 3 na escuta" não pode parecer que funcionou.
     */
    private var abridor: suspend () -> Boolean = { false }

    /** Verdadeiro enquanto há transmissão no ar. Ver aceite 5 da spec. */
    private var transmitindo: () -> Boolean = { false }

    /**
     * **O rádio está no ar?** Lido no instante da decisão, nunca capturado.
     *
     * ## Por que "registrado" deixou de significar "no ar"
     *
     * Até 22/08 a única pergunta que a política sabia fazer era *"alguém registrou
     * um trocador?"*, e o registro morava **depois** do `return@launch` da falha de
     * rota de áudio em `RadioViewModel.abrir`. Em qualquer aparelho sem HFP — óculos
     * não pareados, fone ausente, emulador — o registro **nunca acontecia**, e o fio
     * entre o roteador de voz e o rádio simplesmente não existia: detector, whisper
     * e roteador funcionando, e "guarnição 3 na escuta" morrendo sem chamador.
     *
     * O conserto ata o fio **antes** da rota. Mas aí "registrado" passa a ser
     * verdade sempre, e sozinho ele mentiria: com o fio atado e o rádio ainda fora
     * do ar, a guarda "já estamos lá" devolveria `Trocado`, o executor chamaria
     * [abrirTransmissao], o abridor devolveria `false` — e `false` cru vira
     * *"Canal ocupado."* na boca do copiloto. O canal não está ocupado; o rádio é
     * que não subiu. Duas coisas diferentes com a mesma frase é a definição de
     * recusa sem motivo.
     *
     * Por isso a disponibilidade é **predicado**, e não valor: entre o registro e o
     * comando falado o rádio sobe, cai e sobe de novo.
     */
    private var noAr: () -> Boolean = { false }

    private val resolvedor = ResolvedorDeGrupo { lexico }

    /**
     * A política vive fora deste `object` de propósito: a ordem das guardas é a
     * parte com consequência operacional, e aqui dentro ela seria intestável.
     * Ver [PoliticaDeTrocaDeGrupo].
     */
    private val politica = PoliticaDeTrocaDeGrupo(
        resolvedor = resolvedor,
        transmitindo = { transmitindo() },
        grupoCorrenteId = { grupoCorrenteId },
        trocador = { trocador },
        noAr = { noAr() },
    )

    /** Exposto para a tela: a lista que o agente pode abrir pela fala. */
    val grupos: List<GrupoFalado> get() = lexico.orEmpty()

    /**
     * **Ata o fio entre o roteador de voz e o rádio. Não depende de rota de áudio.**
     *
     * Chamado **antes** de o HFP ser tentado, e não depois de ele subir. O fio é
     * código que roda no celular; o que depende de rota é o rádio *funcionar*, não
     * o roteador de voz *ter para onde perguntar*. Enquanto esta chamada viveu
     * depois do `return@launch` da falha de rota, todo aparelho sem fone pareado
     * ficava com `trocador == null` e `abridor == { false }` — e a recusa não tinha
     * motivo, porque não havia nada registrado para produzir um.
     *
     * Os três lambdas são lidos **no instante do comando**, nunca capturados como
     * valor: entre o registro e a fala do agente o rádio sobe, transmite e cai.
     *
     * @param noAr `false` enquanto o rádio existe mas não pode pôr o agente no ar.
     *   Sem este predicado, "fio atado" e "rádio pronto" seriam a mesma coisa, e a
     *   recusa sairia como *"Canal ocupado."* — ver o KDoc de [noAr].
     */
    fun registrarRadio(
        trocar: suspend (String) -> Boolean,
        transmitindoAgora: () -> Boolean,
        noAr: () -> Boolean,
        abrir: suspend () -> Boolean = { false },
    ) {
        trocador = trocar
        transmitindo = transmitindoAgora
        this.noAr = noAr
        abridor = abrir
    }

    /**
     * Abre transmissão no grupo CORRENTE, sem tocar em nada.
     *
     * O grupo já foi resolvido por [trocar] antes — a ordem é dela: resolver e só
     * então abrir. Invertida, o agente ficaria no ar numa guarnição não confirmada.
     *
     * `false` quando o piso é de outro, e isso não é erro: é o rádio funcionando.
     * Quem chama transforma em fala, porque o desfecho silencioso é o pior — o
     * agente assume que está no ar e fala para ninguém.
     *
     * **`false` aqui significa só "o piso é de outro".** O executor o traduz para
     * *"Canal ocupado."*, e essa frase só é verdadeira se o rádio estiver no ar —
     * por isso [PoliticaDeTrocaDeGrupo] recusa antes, por `noAr`, e este ponto nunca
     * é alcançado com o rádio fechado. Se algum dia for, a recusa volta a ser muda
     * no sentido que importa: sem motivo verdadeiro.
     */
    suspend fun abrirTransmissao(): Boolean = abridor()

    /** Chamado no `onCleared` do dono. Sem isso, o lambda segura o ViewModel morto. */
    fun esquecerRadio() {
        trocador = null
        transmitindo = { false }
        noAr = { false }
        abridor = { false }
    }

    /**
     * Carrega o léxico. Idempotente: a segunda chamada não vai à rede.
     *
     * Chamado no login, e não a cada comando: resolver "guarnição três" é casamento
     * contra uma lista curta que muda quando a lotação muda, não quando o agente
     * fala. Consultar rede no caminho do gatilho somaria latência de rede à meta de
     * 2 s e deixaria o comando refém do sinal — justamente onde o rádio precisa
     * funcionar.
     *
     * Falha de rede deixa [lexico] nulo de propósito. O modo de falhar é *audível*
     * ("Sem lista. Entre de novo.") e nunca um fallback muto para
     * [CanalDoPiloto] — o agente crendo ter trocado e continuando no canal anterior
     * é o pior desfecho desta feature.
     */
    suspend fun carregar(context: Context): Boolean = mutex.withLock {
        if (lexico != null) return@withLock true
        if (!SessaoDoAgente.redeConfigurada) {
            Log.w(TAG, "léxico de canais: rede não configurada, seguindo com o canal do piloto")
            return@withLock false
        }
        // Lista vazia é diferente de falha: o agente existe e não está em grupo
        // nenhum. Assumir o canal do piloto aí seria inventar lotação.
        val lista = withContext(Dispatchers.IO) {
            RotulosFalados(
                config = SessaoDoAgente.config,
                tokenDeSessao = { SessaoDoAgente.tokenValido(context) },
            ).carregar().getOrNull()
        }
        if (lista != null && lista.isEmpty()) {
            Log.w(TAG, "o agente não pertence a nenhum talk group — sem canal")
            return@withLock false
        }
        if (lista == null) {
            Log.w(TAG, "léxico de canais NÃO carregou — troca por voz vai recusar, não adivinhar")
            return@withLock false
        }
        lexico = lista
        // **O canal corrente passa a ser do cadastro, não da constante.**
        //
        // Se o grupo provisório está na lista do agente, fica — e adota o nome de
        // lá, porque o nome da constante é chute de código e o do servidor é
        // cadastro. Se NÃO está, o agente não é daquela guarnição: assume o
        // primeiro grupo dele. Continuar no id fixo colocaria um agente de outra
        // lotação dentro do canal do piloto.
        val escolhido = escolherCanal(grupoCorrenteId, lista)
        if (escolhido.id != grupoCorrenteId) {
            Log.i(TAG, "canal provisório não é deste agente — assumindo ${escolhido.nome}")
        }
        grupoCorrenteId = escolhido.id
        grupoCorrenteNome = escolhido.nome
        canalConfirmadoPeloServidor = true
        Log.i(TAG, "léxico de canais carregado: ${lista.size} grupo(s) endereçável(is) por voz")
        true
    }

    /** Para teste e para o logout: volta ao estado de "não carregou". */
    fun limpar() {
        lexico = null
        grupoCorrenteId = CanalDoPiloto.ID
        grupoCorrenteNome = CanalDoPiloto.NOME
        canalConfirmadoPeloServidor = false
        esquecerRadio()
    }

    /** O caminho do comando falado até o canal. A política é [PoliticaDeTrocaDeGrupo]. */
    suspend fun trocar(rotuloFalado: String): TrocaDeGrupo {
        val d = politica.decidir(rotuloFalado)
        // O efeito colateral é aplicado aqui, e só aqui: a política é sem estado
        // justamente para não haver duas verdades sobre em que grupo estamos.
        d.novoGrupo?.let {
            grupoCorrenteId = it.id
            grupoCorrenteNome = it.nome
            Log.i(TAG, "canal trocado por voz para ${it.nome}")
        }
        return d.resultado
    }
}
