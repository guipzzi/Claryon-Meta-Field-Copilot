package com.claryon.field.radio

import android.util.Log
import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.audio.FluxoDeReproducao
import kotlinx.coroutines.channels.Channel
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Result
import com.claryon.net.AcumuladorDePcm
import com.claryon.net.ClienteDePiso
import com.claryon.net.CodecDeVoz
import com.claryon.net.DecisaoDeGatilho
import com.claryon.net.DecisaoDeSoltura
import com.claryon.net.EventoPtt
import com.claryon.net.EventoRecepcao
import com.claryon.net.GatilhoPtt
import com.claryon.net.PreRollBuffer
import com.claryon.net.PrioridadeTransmissao
import com.claryon.net.Receptor
import com.claryon.net.SessaoPtt
import com.claryon.net.SupressorDeSaidaPropria
import com.claryon.net.TelemetriaDoRadio
import com.claryon.net.TransporteAoVivo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * **Orquestrador do rádio tático: onde `core-net` encontra o produto.**
 *
 * Até aqui as peças existiam testadas e desligadas — pré-roll, jitter, piso,
 * sessão, supressor, gatilho. Este arquivo é a ligação, e o que ele resolve não
 * é encanamento: é a **coordenação entre entrada e saída de áudio no mesmo
 * aparelho**, que é onde os conflitos moram.
 *
 * ## A regra que organiza tudo: nossa saída não entra na nossa entrada
 *
 * Os alto-falantes dos óculos são *open-ear*, a centímetros do array de
 * microfones. Todo som que produzimos é um som que vamos capturar. Aqui isso se
 * traduz em três ligações concretas:
 *
 *  1. **Toda reprodução abre uma janela no [SupressorDeSaidaPropria]** — earcon,
 *     fala do copiloto, e a voz recebida de outro agente.
 *  2. **O pré-roll só é alimentado fora dessas janelas.** Sem isso, o tom de
 *     início — que é alta energia — seria detectado pelo VAD retroativo como
 *     "início da fala" e ancoraria a transmissão no bipe: o recurso que existe
 *     para não cortar a primeira sílaba passaria a cortá-la.
 *  3. **A captura de PTT descarta os quadros suprimidos.** Enquanto o
 *     alto-falante toca, o microfone capta a mistura e não há como separá-la;
 *     perder 200 ms de sobreposição é melhor que difundir a própria saída para a
 *     guarnição. No caso da cauda de uma transmissão recebida, descartar **é** a
 *     disciplina de meio-duplex.
 *
 * ## Alimentação contínua do pré-roll
 *
 * O pré-roll é preenchido **enquanto o modo Ativo dura**, não a partir do toque.
 * É o que permite recuperar a fala iniciada antes de o dedo chegar ao botão — e é
 * também o que garante que a fala durante a espera pela concessão de canal não se
 * perca.
 */
class RadioTatico(
    private val escopo: CoroutineScope,
    private val talkGroupId: String,
    private val agenteId: String,
    private val indicativo: String,
    /**
     * Traduz o id do autor no indicativo do cadastro do grupo — `null` quando o id
     * não está lá.
     *
     * O padrão devolve `null` **de propósito**: um chamador que esqueça de ligar o
     * cadastro degrada para "origem não confirmada", que é seguro, em vez de cair
     * de volta na string livre, que é o defeito.
     */
    private val resolverAutor: (String) -> String? = { null },
    /**
     * Pergunta ao servidor quem de fato detém/deteve o piso da transmissão.
     *
     * `null` no padrão — "não sei" — e "não sei" **não** derruba o rótulo: derrubar
     * por falta de rede transformaria toda fala em suspeita durante uma queda, que
     * é justamente quando o rádio mais importa. Só a **divergência** derruba.
     */
    private val conferirAutor: suspend (String) -> String? = { null },
    /**
     * Transcreve o PCM que foi ao ar. `null` desliga a transcrição na origem sem
     * afetar nada do rádio — o balão aparece sem texto, que é o comportamento de
     * antes deste item existir.
     *
     * Quem passa decide o escopo. O roadmap pede **escopo de aplicação**: em escopo
     * de tela, trocar de aba logo depois de falar mataria a transcrição no meio, e o
     * colega ficaria sem o texto por causa de um gesto de interface.
     */
    private val transcrever: (suspend (ShortArray) -> String?)? = null,
    /** O texto da própria fala, para a tela não esperar um eco que não vem. */
    private val aoTextoProprio: (String, String) -> Unit = { _, _ -> },
    /** O texto da fala de um colega, chaveado por `transmissaoId`. */
    private val aoTextoRecebido: (String, String) -> Unit = { _, _ -> },
    private val transporte: TransporteAoVivo,
    private val codec: CodecDeVoz,
    private val piso: ClienteDePiso,
    private val pcmDoMicrofone: (GlassesAudioRoute) -> Flow<ShortArray>,
    /**
     * Abre o `AudioTrack` de longa duração da recepção.
     *
     * Antes, cada quadro de 20 ms virava `escopo.launch { reproduzir(...) }`, e
     * `reproduzir` construía e liberava um track inteiro — 50 por segundo, em
     * corrotinas concorrentes sem ordem entre si. A fala chegava com estalo a
     * cada quadro e, quando o `build()` de um demorava mais que o do seguinte,
     * fora de ordem.
     */
    private val abrirFluxoDeSaida: (taxaHz: Int) -> FluxoDeReproducao,
    /**
     * Grava a transmissão no histórico do canal.
     *
     * Separado do caminho de áudio de propósito: a fala já foi ouvida quando isto
     * roda, então falha aqui é perda de **registro**, não de comunicação. Por isso
     * é `(…) -> Unit` e não devolve resultado ao PTT — derrubar a transmissão por
     * causa do histórico seria trocar o essencial pelo acessório.
     */
    private val registrarNoHistorico: (
        transmissaoId: String, prioridade: Int, duracaoMs: Long,
    ) -> Unit = { _, _, _ -> },
    private val emitir: (Utterance) -> Unit,
    private val duracaoDoEarconMs: (Earcon) -> Long,
    /**
     * Quem está transmitindo agora, ou `null` quando o canal silencia.
     *
     * A régua de presença mostrava `falando = false` fixo — o Receptor sabia da
     * transmissão em curso e a informação morria aqui dentro. Num canal
     * meio-duplex, saber que alguém está com o piso é o que impede o agente de
     * apertar o PTT e ser recusado.
     */
    private val aoMudarQuemFala: (String?) -> Unit = {},
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
    private val sampleRateHz: Int = 8_000,
    /**
     * Compartilhado com o dono único da saída (`SaidaUnica`), e não mais
     * construído aqui.
     *
     * Enquanto era campo privado desta classe, só os earcons do próprio rádio
     * registravam janela — a fala sintetizada do copiloto tocava por outro
     * objeto inteiramente e nunca suprimia a captura, apesar de o KDoc de
     * [SupressorDeSaidaPropria] listar exatamente esse caso. Compartilhar a
     * instância é o que fecha o furo: agora qualquer som que sai pela fila única
     * — earcon do rádio ou fala do copiloto — suprime a mesma captura.
     */
    private val supressor: SupressorDeSaidaPropria = SupressorDeSaidaPropria(),
    /**
     * Medições do rádio — latência de PTT, concessão de canal, codificação,
     * recepção, e os contadores de quadro.
     *
     * A classe existia completa, testada e **sem um único chamador em
     * `src/main`** (`ESTADO.md` item 6): meta declarada sem instrumento é
     * adjetivo, não número. Vive aqui e não dentro de `SessaoPtt` porque uma
     * sessão dura uma fala; as métricas precisam atravessar o turno.
     */
    val telemetria: TelemetriaDoRadio = TelemetriaDoRadio(),
) {

    private val gatilho = GatilhoPtt()
    private val preRoll = PreRollBuffer(sampleRateHz = sampleRateHz)

    private val receptor = Receptor(
        transporte = transporte,
        codec = codec,
        escopo = escopo,
        telemetria = telemetria,
        agoraMs = agoraMs,
    )

    private var alimentacao: Job? = null

    /** Prioridade da transmissão em curso — o evento de fim não a carrega. */
    private var prioridadeCorrente: Int = 2

    /** `AudioTrack` vivo da recepção, e o único consumidor que escreve nele. */
    private var fluxoDeSaida: FluxoDeReproducao? = null
    private var consumidorDeSaida: Job? = null
    private val filaDeSaida = Channel<ShortArray>(Channel.UNLIMITED)

    /**
     * Fecha o track da recepção.
     *
     * Chamado no fim da fala **e** ao sair do modo ativo: uma transmissão que
     * termina por queda de rede nunca emite `Terminou`, e sem este segundo
     * caminho o track ficaria vivo segurando a rota de saída até o processo
     * morrer.
     *
     * `filaDeSaida` **não é recriada** aqui — ela vive enquanto durar o
     * `RadioTatico`, porque cada transmissão nova reusa o mesmo canal (só
     * `fluxoDeSaida`, o `AudioTrack`, é recriado). Sem o dreno abaixo, um quadro
     * que chegou tarde demais para o consumidor cancelado ficaria à espera na
     * cabeça do canal e seria o primeiro a tocar na transmissão SEGUINTE — a voz
     * do locutor anterior colada na frente da próxima, possivelmente numa taxa
     * diferente se `abrirFluxoDeSaida` mudou. `tryReceive` nunca suspende: seguro
     * chamar de um contexto não-suspenso.
     */
    private fun fecharFluxoDeSaida() {
        consumidorDeSaida?.cancel()
        consumidorDeSaida = null
        while (filaDeSaida.tryReceive().isSuccess) Unit
        fluxoDeSaida?.fechar()
        fluxoDeSaida = null
    }
    private var transmissao: Job? = null

    /** `true` enquanto o agente segura o PTT. */
    val transmitindo: Boolean get() = gatilho.transmitindo

    /**
     * Amplitude do último quadro capturado, de 0 a 1. Alimenta a forma de onda da
     * barra de PTT.
     *
     * É medida do PCM **real**, e não uma animação: o agente lê movimento na tela
     * como "está me captando". Uma senoide decorativa mentiria exatamente na
     * situação em que a verdade importa — microfone mudo, rota caída, mão sobre o
     * aparelho. Aqui, tela parada significa microfone parado.
     */
    @Volatile
    var amplitudeAtual: Float = 0f
        private set

    private fun medirAmplitude(quadro: ShortArray) {
        if (quadro.isEmpty()) return
        // Pico e não RMS: a forma de onda precisa reagir ao ataque da sílaba, e o
        // RMS de uma janela de 20 ms achata justamente isso.
        var pico = 0
        for (amostra in quadro) {
            val abs = if (amostra < 0) -amostra.toInt() else amostra.toInt()
            if (abs > pico) pico = abs
        }
        val bruta = pico / 32_767f
        // Suavização assimétrica: sobe rápido, desce devagar. Sem isso a barra
        // pisca entre sílabas e parece falha de captura.
        amplitudeAtual = if (bruta > amplitudeAtual) bruta else amplitudeAtual * 0.82f
    }

    // ── Modo Ativo ────────────────────────────────────────────────────────────

    /**
     * Entra em operação: conecta o canal, começa a receber e passa a alimentar o
     * pré-roll continuamente.
     */
    fun entrarEmModoAtivo(rota: GlassesAudioRoute) {
        escopo.launch { transporte.conectar(talkGroupCorrente) }

        // **Aquece o codec agora, não no primeiro toque.**
        // `MediaCodec.createEncoderByType` + `configure` + `start` aconteciam na
        // primeira `codificar()`, ou seja com o dedo já no PTT — e era isso, não
        // a rede, que punha a meta "toque → 1º quadro ≤ 120 ms" em 168-221 ms no
        // emulador. Aqui há folga: o rádio abre quando a tela da guarnição
        // aparece, segundos antes de alguém falar.
        escopo.launch { codec.preparar() }

        receptor.iniciar { evento -> tratarRecepcao(evento) }

        alimentacao?.cancel()
        alimentacao = escopo.launch {
            semDerrubarOProcesso("alimentação do pré-roll") {
            pcmDoMicrofone(rota)
                // Descarta o que foi capturado enquanto NÓS emitíamos som.
                .filter { !supressor.suprimido(agoraMs()) }
                .onEach { quadro -> medirAmplitude(quadro) }
                .onEach { supressor.podarAntesDe(agoraMs()) }
                .collect { bloco -> preRoll.escrever(bloco) }
            }
        }
    }

    /**
     * Executa captura sem deixar uma falha derrubar o processo.
     *
     * Os dois `escopo.launch` que consomem o microfone rodavam **crus**, e o
     * escopo é o `viewModelScope`: `AudioRecord` lançando — óculos desconectando,
     * `RECORD_AUDIO` revogada, `ERROR_DEAD_OBJECT` — matava o app no instante do
     * toque no PTT. Num rádio para segurança pública, isso é o pior modo de falha
     * possível: o agente aperta para pedir apoio e a tela some.
     *
     * `CancellationException` **continua propagando**. Engoli-la faria
     * `alimentacao?.cancel()` e `sairDeModoAtivo()` pararem de parar de verdade,
     * e o corpo seguiria rodando depois do escopo morto — trocaria um crash
     * visível por um vazamento invisível, que é pior.
     *
     * A falha vira earcon porque `emitir` agora tem destino. Enquanto ele foi uma
     * lambda vazia, este `catch` seria decoração.
     */
    private inline fun semDerrubarOProcesso(oQue: String, bloco: () -> Unit) {
        try {
            bloco()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "$oQue falhou: ${e.message}")
            emitirComSupressao(Utterance.Sinalizar(Earcon.FALHA, Priority.EMERGENCIA))
        }
    }

    /**
     * Troca o talk group **sem derrubar o áudio**.
     *
     * ## Por que não é "fechar e abrir de novo"
     *
     * `RadioViewModel.fechar()` termina em `audio.liberar()`, que solta a rota
     * SCO. Reabrir custa a renegociação do HFP — centenas de milissegundos em que
     * o agente não ouve nem fala — e, pior, a rota é **estado global do
     * aparelho**: derrubá-la para trocar de canal afeta qualquer outro caminho
     * que esteja capturando. Trocar de grupo é operação de REDE, e tem de custar
     * só rede.
     *
     * Por isso este método toca `transporte` e `receptor`, e nada mais. A captura
     * segue viva: `alimentacao` continua alimentando o pré-roll pelo mesmo
     * `AudioRecord`, e a `GlassesAudioRoute` nunca é devolvida.
     *
     * ## O que é descartado, e por quê
     *
     * O pré-roll é **limpo**. Ele contém a fala que o agente disse enquanto o
     * rádio estava no grupo ANTERIOR; deixá-la sobreviver faria a primeira
     * transmissão no grupo novo começar com áudio dito para outra guarnição.
     * Áudio não sobrevive à troca de contexto.
     *
     * A recepção em curso também morre: `fecharFluxoDeSaida` fecha o track e
     * drena a fila. Terminar de tocar a voz do grupo antigo depois de trocar
     * seria confundir quem escuta sobre de onde vem o quê.
     *
     * @return `false` se já estamos neste grupo — o chamador decide se avisa.
     */
    suspend fun trocarDeGrupo(novoTalkGroupId: String): Boolean {
        if (novoTalkGroupId == talkGroupCorrente) return false

        // Ordem que importa: parar de receber ANTES de reconectar, senão o
        // primeiro evento do grupo novo pode chegar enquanto o jitter ainda
        // carrega quadros do antigo.
        receptor.parar()
        fecharFluxoDeSaida()
        transmissao?.cancel()
        preRoll.limpar()
        supressor.limpar()

        talkGroupCorrente = novoTalkGroupId
        // `conectar` fecha o socket anterior quando o grupo muda — ver
        // `TransporteRealtime.conectar`. Sem isso o aparelho seguiria inscrito no
        // tópico antigo e receberia as duas guarnições misturadas.
        transporte.conectar(novoTalkGroupId)
        receptor.iniciar { evento -> tratarRecepcao(evento) }
        Log.i(TAG, "talk group trocado para $novoTalkGroupId")
        return true
    }

    /**
     * O grupo em que estamos agora.
     *
     * `var` privado e não o parâmetro do construtor: o `talkGroupId` de
     * construção é só o inicial, e `SessaoPtt` precisa do **corrente** para não
     * pedir piso ao grupo errado depois de uma troca.
     */
    private var talkGroupCorrente: String = talkGroupId

    /** Para diagnóstico e para a tela dizer em que canal o agente está. */
    val grupoAtual: String get() = talkGroupCorrente

    fun sairDeModoAtivo() {
        fecharFluxoDeSaida()
        alimentacao?.cancel()
        alimentacao = null
        transmissao?.cancel()
        receptor.parar()
        preRoll.limpar()
        supressor.limpar()
        escopo.launch { transporte.desconectar() }
    }

    // ── Emissão ───────────────────────────────────────────────────────────────

    /**
     * Chamado ao **pressionar** o gatilho — hoje, o bloco de fala da tela, e só
     * ele. O toque na haste foi descartado por medição (pausa a sessão) e o botão
     * de volume chegou a ficar documentado como primário sem nunca existir em
     * código. Ver `DECISIONS.md`.
     */
    fun aoPressionar(rota: GlassesAudioRoute, prioridade: PrioridadeTransmissao = PrioridadeTransmissao.P2_APOIO) {
        when (val d = gatilho.aoPressionar(agoraMs())) {
            DecisaoDeGatilho.IgnoradoPorRepique -> {
                Log.d(TAG, "toque ignorado por repique")
                telemetria.contar(TelemetriaDoRadio.TOQUES_IGNORADOS)
                return
            }
            DecisaoDeGatilho.JaTransmitindo -> return
            DecisaoDeGatilho.Iniciar -> Unit
        }

        val transmissaoId = UUID.randomUUID().toString()
        prioridadeCorrente = when (prioridade) {
            PrioridadeTransmissao.P1_EMERGENCIA -> 1
            PrioridadeTransmissao.P2_APOIO -> 2
            else -> 3
        }
        val sessao = SessaoPtt(
            // **Corrente, não o do construtor.** Depois de uma troca de
            // grupo, pedir o piso com o id inicial mandaria a concessão
            // para a guarnição errada — e o servidor recusaria por
            // membership, ou pior, concederia num canal que ninguém ouve.
            talkGroupId = talkGroupCorrente,
            agenteId = agenteId,
            preRoll = preRoll,
            codec = codec,
            transporte = transporte,
            piso = piso,
            agoraMs = agoraMs,
            amostrasPorQuadro = sampleRateHz / 50,
            telemetria = telemetria,
            acumulador = acumuladorDeFala,
            aoAudioTransmitido = if (transcrever == null) {
                null
            } else {
                { id, pcm -> transcreverEDifundir(id, pcm) }
            },
        )

        transmissao = escopo.launch {
            semDerrubarOProcesso("transmissão") {
            sessao.transmitir(
                transmissaoId = transmissaoId,
                prioridade = prioridade,
                indicativo = indicativo,
                // A captura ao vivo também respeita a supressão: se um earcon
                // tocar no meio da fala, aqueles quadros não vão para a rede.
                pcmAoVivo = pcmDoMicrofone(rota).filter { !supressor.suprimido(agoraMs()) },
            ) { evento -> tratarPtt(evento) }
            }
        }
    }

    /** Chamado ao **soltar** o gatilho. */
    fun aoSoltar() {
        when (val d = gatilho.aoSoltar(agoraMs())) {
            is DecisaoDeSoltura.AbortarPorToqueCurto -> {
                // Encostar no botão não pode difundir ruído para a guarnição.
                Log.d(TAG, "toque curto (${d.duracaoMs} ms) — transmissão abortada")
                transmissao?.cancel()
                preRoll.limpar()
            }
            is DecisaoDeSoltura.Encerrar -> transmissao?.cancel() // o finally encerra ordenado
            DecisaoDeSoltura.SemTransmissao -> Unit
        }
    }

    // ── Tradução de eventos em som ────────────────────────────────────────────

    private fun tratarPtt(evento: EventoPtt) {
        when (evento) {
            EventoPtt.Capturando -> Unit // háptico fica a cargo da camada de UI

            is EventoPtt.Transmitindo -> Unit // silêncio = pode falar (comportamento de rádio)

            is EventoPtt.CanalOcupado -> {
                gatilho.cancelar(agoraMs())
                sinalizar(Earcon.FALHA, Priority.RESPOSTA)
            }

            EventoPtt.CanalPerdido -> {
                // Perdeu a palavra; seguir falando é falar para o vazio.
                gatilho.cancelar(agoraMs())
                sinalizar(Earcon.FALHA, Priority.EMERGENCIA)
            }

            EventoPtt.LimiteDeDuracao -> {
                gatilho.cancelar(agoraMs())
                sinalizar(Earcon.FALHA, Priority.RESPOSTA)
            }

            is EventoPtt.QuadrosNaoEntregues ->
                Log.w(TAG, "${evento.quantidade} quadros não entregues")

            is EventoPtt.Encerrada -> {
                Log.i(TAG, "transmissão ${evento.transmissaoId}: ${evento.quadros} quadros em ${evento.duracaoMs} ms")
                // O produtor que faltava: sem esta linha `transmissions` nunca
                // recebia INSERT e o fio do canal era permanentemente vazio.
                registrarNoHistorico(
                    evento.transmissaoId,
                    prioridadeCorrente,
                    evento.duracaoMs,
                )
            }
        }
    }

    /**
     * Um acumulador por rádio, não por transmissão.
     *
     * `SessaoPtt` o zera no início de cada fala e o consome no fim, então reusar a
     * instância não mistura falas — e evita alocar ~1 MB a cada toque no PTT, que
     * numa ocorrência com muitas transmissões curtas viraria pressão de GC no
     * caminho mais sensível do produto.
     */
    private val acumuladorDeFala = AcumuladorDePcm()

    /**
     * Transcreve o que foi ao ar e difunde o texto para o grupo.
     *
     * A ordem importa: difundir **depois** de transcrever, e não em paralelo, porque
     * o evento `fala.transcricao` carrega o texto — sem ele não há o que difundir.
     * Falha em qualquer das duas etapas é registrada e engolida: a fala já foi ao ar
     * e o rádio cumpriu o papel dele. Derrubar o encerramento por causa do texto
     * seria trocar a capacidade essencial pela acessória.
     */
    private suspend fun transcreverEDifundir(transmissaoId: String, pcm: ShortArray) {
        val texto = runCatching { transcrever?.invoke(pcm) }
            .onFailure { Log.w(TAG, "transcrição na origem falhou", it) }
            .getOrNull()
        if (texto.isNullOrBlank()) return

        // O próprio agente vê o texto da própria fala sem esperar o eco do
        // servidor: o broadcast não volta para quem enviou.
        aoTextoProprio(transmissaoId, texto)
        transporte.transcrever(transmissaoId, texto)
            .let { if (it is Result.Failure) Log.w(TAG, "texto não difundido: ${it.error}") }
    }

    private fun tratarRecepcao(evento: EventoRecepcao) {
        when (evento) {
            // O texto da fala de um colega. Não toca no áudio nem no supressor: só
            // preenche o balão que já existe, casando pelo `transmissaoId`.
            is EventoRecepcao.TextoDaFala -> aoTextoRecebido(evento.transmissaoId, evento.texto)

            is EventoRecepcao.Chegando -> {
                // Abre a janela de supressão ANTES do primeiro áudio: a voz que
                // vai tocar não pode entrar na nossa resposta.
                supressor.abrir(agoraMs())
                // **Resolve, não confia.** `autorIndicativo` é string livre: quem
                // transmite escreve o nome que quiser, e até aqui a tela do colega
                // mostrava. Agora o nome sai do cadastro que o servidor filtrou
                // (`cadastro_do_grupo`), casado pelo `autorAgenteId`.
                //
                // Id que não resolve NÃO vira o nome que o emissor escreveu —
                // exibir o rótulo que o próprio forjador digitou é pior que não
                // exibir nada, porque dá autoridade à mentira. Vira origem não
                // confirmada, e o áudio toca do mesmo jeito: calar uma voz que
                // pode ser um pedido de apoio real seria a falha oposta.
                val idAlegado = evento.anuncio.autorAgenteId
                aoMudarQuemFala(resolverAutor(idAlegado) ?: AUTOR_NAO_CONFIRMADO)

                // A conferência é DEPOIS, e de propósito: o áudio já está tocando.
                // Mediar o envio poria uma ida e volta antes do anúncio, dentro do
                // orçamento de 1 200 ms até o BIP; calar enquanto confere seria pior
                // ainda, porque a fala pode ser um pedido de apoio real.
                escopo.launch {
                    val autorReal = conferirAutor(evento.anuncio.transmissaoId)
                    if (autorReal != null && autorReal != idAlegado) {
                        Log.w(
                            TAG,
                            "autoria divergente em ${evento.anuncio.transmissaoId}: " +
                                "alegou $idAlegado, o piso é de $autorReal",
                        )
                        aoMudarQuemFala(AUTOR_NAO_CONFIRMADO)
                    }
                }
                if (evento.anuncio.prioridade == PrioridadeTransmissao.P1_EMERGENCIA) {
                    sinalizar(Earcon.PRIORITARIA, Priority.EMERGENCIA)
                }
            }

            is EventoRecepcao.Audio -> {
                // Abertura preguiçosa: a taxa só se conhece no primeiro quadro
                // decodificado — `CodecDeVoz.taxaDeSaidaHz` é 0 antes disso, e um
                // track aberto em `entrarEmModoAtivo` nasceria com taxa errada.
                val fluxo = fluxoDeSaida ?: abrirFluxoDeSaida(evento.taxaHz).also { fluxoDeSaida = it }
                // Fila de um consumidor só: a ordem da fala é a ordem de chegada,
                // e ela se perde no instante em que dois `launch` disputam o
                // mesmo track.
                escopo.launch { filaDeSaida.send(evento.pcm) }
                if (consumidorDeSaida == null) {
                    consumidorDeSaida = escopo.launch {
                        // `escrever` devolve `Result` e não lança — antes o
                        // valor era descartado, e uma falha de escrita
                        // (`ERROR_INVALID_OPERATION`, rota derrubada) não
                        // chegava a earcon nem a log. "Falha nunca é silêncio"
                        // vale para o áudio recebido também. Um bipe só, não um
                        // por quadro: se a rota caiu, os quadros seguintes vão
                        // falhar todos, e repetir o earcon 50x/s seria ruído por
                        // cima de um aviso que o agente já recebeu.
                        var jaAvisou = false
                        semDerrubarOProcesso("reprodução da recepção") {
                            for (bloco in filaDeSaida) {
                                if (fluxo.escrever(bloco) is Result.Failure && !jaAvisou) {
                                    jaAvisou = true
                                    sinalizar(Earcon.FALHA, Priority.RESPOSTA)
                                }
                            }
                        }
                    }
                }
            }

            is EventoRecepcao.Terminou -> {
                // Fecha a janela; a margem do supressor cobre a cauda.
                supressor.fechar(agoraMs())
                aoMudarQuemFala(null)
                fecharFluxoDeSaida()
                Log.i(TAG, "recebida ${evento.transmissaoId}: ${evento.quadros} quadros, ${evento.perdidos} perdidos")
            }

            EventoRecepcao.CanalDegradado -> {
                // O agente precisa saber que o canal está ruim ANTES de confiar nele.
                emitirComSupressao(utteranceFor(ActionOutcome.Falhou(FalhaOperacional.SEM_REDE)))
            }
        }
    }

    private fun sinalizar(earcon: Earcon, prioridade: Priority) =
        emitirComSupressao(Utterance.Sinalizar(earcon, prioridade))

    /**
     * Emite **e registra a janela** — o passo que impede o próprio som de voltar
     * pelo microfone. Esquecer de registrar aqui é o defeito que reintroduziria
     * os quatro conflitos de uma vez.
     */
    private fun emitirComSupressao(utterance: Utterance) {
        val duracao = when (utterance) {
            is Utterance.Sinalizar -> duracaoDoEarconMs(utterance.earcon)
            is Utterance.SinalizarEFalar -> duracaoDoEarconMs(utterance.earcon) + DURACAO_FALA_ESTIMADA_MS
            is Utterance.Falar -> DURACAO_FALA_ESTIMADA_MS
        }
        supressor.registrar(agoraMs(), duracao)
        emitir(utterance)
    }

    private companion object {
        /**
         * O que a tela mostra quando o autor não resolve no cadastro do grupo.
         *
         * Frase e não vazio: espaço em branco no lugar do indicativo pareceria
         * defeito de renderização, e o agente precisa saber que a origem é que é
         * duvidosa — não a tela.
         */
        const val AUTOR_NAO_CONFIRMADO = "Origem não confirmada"
        const val TAG = "ClaryonField"

        /**
         * Estimativa para a janela de supressão da fala sintetizada. Grosseira de
         * propósito: errar para mais só descarta um pouco mais de captura; errar
         * para menos deixa a própria fala vazar para a guarnição.
         */
        const val DURACAO_FALA_ESTIMADA_MS = 2_000L
    }
}
