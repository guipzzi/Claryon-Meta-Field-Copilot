package com.claryon.net

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Bancada de N pares no nível do PROTOCOLO.**
 *
 * O `servidor/par_headless.mjs` exige `PAR_EMAIL`/`PAR_SENHA`, credenciais que não
 * existem neste repositório — e adivinhar senha ou criar conta não é opção. Mas a
 * lógica que decide se o rádio é meio-duplex de verdade **não mora no servidor**:
 * mora em [ControleDePiso] (a mesma política que o Postgres replica em
 * `0005_controle_de_piso.sql`), em [SessaoPtt] e em [Receptor]. Tudo isso é JVM
 * puro, e é aqui que N aparelhos podem ser postos a brigar pelo mesmo canal.
 *
 * ## O que esta bancada cobre
 *
 *  - Arbitragem de piso com N pretendentes simultâneos, incluindo o desempate.
 *  - Difusão real entre pares: o que A põe no fio é o que B, C e D tiram dele.
 *  - Degradação por queda de rede **com a mesma semântica do cliente de produção**
 *    (ver [ClienteDePisoDoPar], que copia linha a linha o comportamento de
 *    `ClientesDePiso.kt:77-79`, `:103-108` e `:110-112`).
 *  - Entrada e saída de par no meio da fala.
 *
 * ## O que esta bancada NÃO cobre — e é preciso dizer
 *
 *  1. **O `for update` do Postgres.** [PisoServidor] serializa com um [Mutex] de
 *     corrotina. Ele prova que *uma* serialização produz um vencedor só; não prova
 *     que o `select … for update` da migração `0005` serializa de fato sob
 *     concorrência real de conexões, nem que o `on conflict` se comporta como o
 *     KDoc afirma. Isso só se mede contra um Postgres.
 *  2. **RLS e `private.current_agent_id()`.** Aqui a identidade do par é um campo;
 *     no servidor ela sai do JWT. A recusa por não-membro
 *     (`0005_controle_de_piso.sql:78-82`) e a política de `realtime.messages`
 *     (migração `0012`) **não são exercitadas**.
 *  3. **Latência, jitter e reordenação de rede reais.** O barramento entrega em
 *     ordem e instantaneamente; o que varia é o que os testes mandam variar.
 *  4. **O envelope de fio.** [ProtocoloRealtime] não entra no caminho: os eventos
 *     trafegam como objetos. Serialização/desserialização é coberta por
 *     `core-net/src/androidTest/.../ProtocoloRealtimeTest.kt`.
 *  5. **Áudio.** Nada aqui abre `AudioRecord`, `AudioTrack` ou SCO.
 */
class BarramentoTatico {

    private val pares = mutableListOf<ParDeRadio>()

    fun registrar(par: ParDeRadio) {
        pares += par
    }

    /**
     * O cadastro do grupo, do ponto de vista do árbitro.
     *
     * É o que `public.memberships` é para as funções de piso, e é consultado
     * pelos MESMOS dois pontos: `pedir_canal` desde a `0005`, `renovar_canal`
     * desde a `0024`. Sem ele, [PisoServidor] concederia e renovaria piso para
     * quem já saiu da guarnição — que foi exatamente o defeito observado.
     */
    fun ehMembro(talkGroupId: String, agenteId: String): Boolean =
        pares.any { it.agenteId == agenteId && it.talkGroupId == talkGroupId && it.noGrupo }

    /**
     * Difunde para todo par do mesmo talk group **menos o remetente**.
     *
     * Não voltar para quem enviou é o comportamento do broadcast do Supabase e é
     * do que `RadioTatico.transcreverEDifundir` depende para mostrar o texto da
     * própria fala sem esperar eco.
     *
     * Par com a rede caída ou fora do grupo **não recebe e nada é guardado para
     * ele**: não existe fila de retenção neste produto, e fingir uma aqui faria a
     * bancada mentir a favor do código.
     *
     * **Remetente fora do grupo é descartado em silêncio**, e não com erro: com
     * `config.private` ligado (`SessaoDoAgente.config`), a política de
     * `realtime.messages` da migração `0012` julga a linha no servidor, e o
     * broadcast sai com `ack: false` — o aparelho não recebe recusa nenhuma. É a
     * modelagem honesta, e é ela que torna o caso "saí do grupo com o piso na mão"
     * observável.
     */
    fun difundir(de: ParDeRadio, eventos: List<EventoDeRede>) {
        if (!de.noGrupo) return
        for (par in pares) {
            if (par === de) continue
            if (par.talkGroupId != de.talkGroupId) continue
            if (!par.rede || !par.noGrupo) continue
            for (evento in eventos) par.entregar(evento)
        }
    }
}

/**
 * Um aparelho no barramento: implementa [TransporteAoVivo] com as mesmas portas de
 * falha que [TransporteRealtime] tem.
 *
 * @param rede socket TCP de pé. `false` = túnel, avião, bateria do roteador.
 * @param autorizado o `phx_join` foi aceito. Separado de [rede] pelo mesmo motivo
 *   que em produção (`TransporteRealtime.kt:80-90`): em 18/08 um booleano só para
 *   as duas coisas mandou 168 quadros para um canal em que o app não tinha
 *   entrado. É por aqui que o teste de **JWT vencido no meio do turno** entra.
 * @param noGrupo o par ainda pertence ao talk group. Sair do grupo não derruba o
 *   socket — derruba a entrega.
 */
class ParDeRadio(
    val agenteId: String,
    private val barramento: BarramentoTatico,
    val talkGroupId: String = "gta-3",
    @Volatile var rede: Boolean = true,
    @Volatile var autorizado: Boolean = true,
    @Volatile var noGrupo: Boolean = true,
) : TransporteAoVivo {

    /** Tudo que este par TIROU do fio, na ordem de chegada. */
    val recebidos = mutableListOf<EventoDeRede>()

    /** Tudo que este par PÔS no fio e que a rede aceitou. */
    val enviados = mutableListOf<EventoDeRede>()

    /** Mensagens de fato postas no socket — com agrupamento, ~1/3 dos quadros. */
    var mensagensNoSocket = 0
        private set

    private val fluxo = MutableSharedFlow<EventoDeRede>(
        replay = 0,
        extraBufferCapacity = 4096,
    )

    fun entregar(evento: EventoDeRede) {
        recebidos += evento
        fluxo.tryEmit(evento)
    }

    init {
        barramento.registrar(this)
    }

    override suspend fun conectar(talkGroupId: String): Result<Unit> = Result.success(Unit)

    override fun eventos(): Flow<EventoDeRede> = fluxo.asSharedFlow()

    override fun conectado(): Boolean = rede && autorizado

    override var motivoDaRecusa: String? = null

    override suspend fun anunciar(anuncio: AnuncioDeFala): Result<Unit> =
        publicar(listOf(EventoDeRede.Anuncio(anuncio)))

    override suspend fun enviar(quadro: QuadroAudio): Result<Unit> =
        publicar(listOf(EventoDeRede.Quadro(quadro)))

    override suspend fun enviarGrupo(grupo: List<QuadroAudio>): Result<Unit> {
        if (grupo.isEmpty()) return Result.success(Unit)
        return publicar(grupo.map { EventoDeRede.Quadro(it) })
    }

    override suspend fun encerrar(transmissaoId: String): Result<Unit> =
        publicar(listOf(EventoDeRede.FimDeTransmissao(transmissaoId)))

    override suspend fun desconectar() {
        rede = false
        autorizado = false
    }

    override suspend fun transcrever(transmissaoId: String, texto: String): Result<Unit> =
        publicar(listOf(EventoDeRede.Transcricao(transmissaoId, texto)))

    /**
     * O portão único de saída, com as duas recusas de `TransporteRealtime.kt:278-302`
     * na mesma ordem: socket fechado primeiro, join não aceito depois.
     */
    private fun publicar(eventos: List<EventoDeRede>): Result<Unit> {
        if (!rede) return Result.failure(ClaryonError.Sync("net.desconectado", "canal fechado"))
        if (!autorizado) {
            return Result.failure(
                ClaryonError.Sync("net.nao_autorizado", motivoDaRecusa ?: "join não aceito"),
            )
        }
        mensagensNoSocket++
        enviados += eventos
        barramento.difundir(this, eventos)
        return Result.success(Unit)
    }
}

/**
 * **O árbitro.** Uma instância de [ControleDePiso] para o grupo inteiro, com a
 * serialização que o `select … for update` da migração `0005` faz no banco.
 *
 * O [Mutex] é o ponto: sem ele, "ler, decidir, escrever" abriria a janela em que
 * dois pedidos simultâneos são ambos concedidos — que é exatamente o defeito que a
 * migração cita no cabeçalho para justificar viver no Postgres em vez de numa
 * Edge Function.
 */
class PisoServidor(
    private val relogio: () -> Long,
    ttlMs: Long = ControleDePiso.TTL_PADRAO_MS,
    /**
     * De onde sai o pertencimento. `null` = não há cadastro a consultar, e a
     * política roda permissiva — é o caso dos testes que não modelam entrada e
     * saída de grupo.
     *
     * **Passar o barramento é o que torna o defeito 7 observável na bancada**, e
     * o que faz esta classe continuar espelhando o Postgres depois da `0024`.
     */
    barramento: BarramentoTatico? = null,
) {
    val politica = ControleDePiso(ttlMs) { tg, agente ->
        barramento?.ehMembro(tg, agente) ?: true
    }
    private val serializacao = Mutex()

    /** Quantos pedidos o servidor atendeu — inclusive os recusados. */
    var pedidosAtendidos = 0
        private set

    suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido = serializacao.withLock {
        pedidosAtendidos++
        politica.pedir(talkGroupId, agenteId, transmissaoId, prioridade, relogio())
    }

    suspend fun renovar(concessao: Concessao): Boolean = serializacao.withLock {
        politica.renovar(concessao, relogio())
    }

    suspend fun liberar(concessao: Concessao) = serializacao.withLock {
        politica.liberar(concessao)
    }

    fun detentor(talkGroupId: String = "gta-3"): Concessao? =
        politica.detentorVigente(talkGroupId, relogio())
}

/**
 * O cliente de piso de um par, com a **degradação de `ClienteDePisoRemoto`**
 * reproduzida linha a linha — é ela, e não o caminho feliz, que este arquivo
 * existe para exercitar:
 *
 *  - `pedir` sem rede devolve `Ocupado` com detentor `"?"` e `expiraEmMs = 0`
 *    (`ClientesDePiso.kt:77-79`): na dúvida, calar.
 *  - `renovar` sem rede devolve `false` (`ClientesDePiso.kt:103-108`, onde
 *    `rpcBooleano` devolve `null` e o `== true` o converte).
 *  - `liberar` sem rede devolve [ResultadoDaLiberacao.NaoDevolvido]. Até 22/08 ele
 *    **não fazia nada e não contava a ninguém**: o retorno de `rpcBooleano` era
 *    descartado, o `runCatching` do encerramento engolia a exceção e a assinatura
 *    da interface era `Unit` — três camadas para o mesmo fato invisível.
 */
class ClienteDePisoDoPar(
    private val par: ParDeRadio,
    private val servidor: PisoServidor,
    /**
     * Simula o `liberar_canal` que sai, chega ao servidor e volta 500 — ou não
     * volta. Distinto de [ParDeRadio.rede] porque o RPC do piso é HTTP e o rádio é
     * WebSocket: um pode falhar sem o outro.
     */
    @Volatile var liberarFalha: Boolean = false,
) : ClienteDePiso {

    /** Quantas vezes `liberar` foi chamado — inclusive as que não chegaram. */
    var liberacoesTentadas = 0
        private set

    override suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido {
        // Sem rede o pedido não alcança o árbitro. **`SemRede`, não `Ocupado`**:
        // ninguém disse que está falando — nós é que não conseguimos perguntar.
        if (!par.rede) return ResultadoDoPedido.SemRede
        // A identidade vem do PAR, nunca do parâmetro — é o que o servidor faz com
        // `private.current_agent_id()`.
        return servidor.pedir(talkGroupId, par.agenteId, transmissaoId, prioridade)
    }

    override suspend fun renovar(concessao: Concessao): Boolean =
        if (!par.rede) false else servidor.renovar(concessao)

    override suspend fun liberar(concessao: Concessao): ResultadoDaLiberacao {
        liberacoesTentadas++
        if (!par.rede) return ResultadoDaLiberacao.NaoDevolvido("sem rede")
        if (liberarFalha) return ResultadoDaLiberacao.NaoDevolvido("liberar_canal não respondeu")
        servidor.liberar(concessao)
        return ResultadoDaLiberacao.Devolvido
    }
}

/** Codec de bancada: um pacote por quadro, tamanho fixo, sem aquecimento. */
class CodecDeBancada(
    override val taxaDeSaidaHz: Int = 24_000,
    private val amostrasDecodificadas: Int = 480,
) : CodecDeVoz {
    override suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>> =
        Result.success(listOf(ByteArray(30) { 7 }))

    override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> =
        Result.success(ShortArray(amostrasDecodificadas) { if (payload == null) 0 else 1 })

    override fun liberar() = Unit
}
