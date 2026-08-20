package com.claryon.field.radio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.audio.SaidaUnica
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.ParPresente
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.net.ClienteDePiso
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.ClienteDePisoRemoto
import com.claryon.net.CodecDeVoz
import com.claryon.net.ConfigOpus
import com.claryon.net.HistoricoDoCanal
import com.claryon.net.RegistroDeTransmissao
import com.claryon.net.ConfigRealtime
import com.claryon.net.MediaCodecOpus
import com.claryon.net.TransporteAoVivo
import com.claryon.net.TransporteRealtime
import com.claryon.field.voice.EscutaDoAgente
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * **Liga o rádio tático à interface.**
 *
 * Este arquivo existe para consertar um defeito específico e embaraçoso: o
 * `RadioTatico` — PTT, pré-roll, supressor de eco, gatilho, controle de piso,
 * Opus, transporte — estava **construído, testado e nunca instanciado**. A
 * capacidade inteira estava desligada da tomada, e a suíte verde não dizia nada a
 * respeito, porque testes cobrem o que alguém imaginou testar.
 *
 * Três invariantes que este ViewModel sustenta:
 *
 *  1. **A rota de áudio é pré-condição de tipo.** `entrarEmModoAtivo` exige um
 *     `GlassesAudioRoute`, e a única forma de obter um é rotear de fato. Não há
 *     caminho em que o rádio suba capturando pelo microfone do celular — que é
 *     omnidirecional e, com PTT, difundiria a fala do interlocutor para a
 *     guarnição inteira.
 *
 *  2. **O botão nunca espera a rede.** `aoPressionar` chama o rádio e volta; a
 *     concessão de canal e o estado do socket correm em paralelo. Rede lenta
 *     atrasa a entrega, nunca perde fala.
 *
 *  3. **O estado da barra vem do rádio, não do botão.** Se o piso for negado, a
 *     barra mostra ocupado mesmo com o dedo pressionado — a interface reporta o
 *     que aconteceu, não o que foi pedido. É a mesma regra de `utteranceFor`,
 *     aplicada à saída visual.
 */
class RadioViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Token da sessão. Injetado de fora porque quem guarda a sessão é o cofre
     * cifrado do `app`, e `core-net` não pode depender dele.
     */
    var tokenDeSessao: suspend () -> String? = { null }

    // Dono único do processo. Duas instâncias sobre o mesmo estado global do
    // aparelho produziam captação pelo microfone do celular — ver `AudioDoAgente`.
    private val audio = AudioDoAgente.de(app)

    /**
     * Saída de som do rádio.
     *
     * Até aqui, `RadioTatico` recebia `emitir = { }` — uma lambda vazia que
     * engolia **todos** os sinais do rádio: canal ocupado, canal perdido em
     * emergência, limite de duração, alerta prioritário recebido e sem rede. Num
     * produto sem display, isso é a regra dura "falha nunca é silêncio" sendo
     * falsa enquanto a suíte fica verde.
     *
     * **A limitação que este KDoc documentava — duas filas de prioridade que não
     * se enxergam — está resolvida.** `saidaDoRadio` agora É a fila única
     * (`SaidaUnica`), a mesma que `DiagnosticsViewModel` usa para o copiloto. Um
     * P1 do rádio interrompe a fala do copiloto em curso porque os dois
     * `Sound.Tone`/`Sound.Speech` disputam o mesmo `SoundScheduler`. TTS real
     * também passa a existir para o rádio — `sintetizar` não é mais `{ null }` —
     * então o único sinal falado do rádio (`utteranceFor(SEM_REDE)`, hoje
     * descartado por render nulo) passa a soar.
     */
    private val saidaDoRadio = SaidaUnica.de(app)

    private val _estado = MutableStateFlow<EstadoDoPtt>(
        EstadoDoPtt.Indisponivel("Rádio fechado."),
    )
    val estado: StateFlow<EstadoDoPtt> = _estado.asStateFlow()

    private val _falas = MutableStateFlow<List<FalaNoGrupo>>(emptyList())
    val falas: StateFlow<List<FalaNoGrupo>> = _falas.asStateFlow()

    private val _pares = MutableStateFlow<List<ParPresente>>(emptyList())

    /** Quem detém o piso agora. Alimenta `ParPresente.falando`. */
    private val _quemFala = MutableStateFlow<String?>(null)

    /**
     * `{agentId → indicativo}` do grupo, vindo de `cadastro_do_grupo` — a fonte
     * contra a qual o autor de uma fala é resolvido.
     *
     * `@Volatile` porque é escrito na recarga do canal e lido pelo `RadioTatico`
     * na chegada de cada anúncio, que vem de outra linha de execução. Vazio
     * significa "ainda não sei", e o efeito é degradar para origem não confirmada
     * — nunca cair de volta na string livre do emissor.
     */
    @Volatile
    private var cadastroDoGrupo: Map<String, String> = emptyMap()


    /**
     * A régua de presença, com `falando` **combinado na exposição**.
     *
     * A lista crua vem da recarga de 10 s; quem detém o piso muda em milissegundos.
     * Gravar `falando` dentro de `_pares` faria o indicador ficar até dez segundos
     * atrasado — mostrando "falando" depois que a pessoa calou, que é pior que não
     * mostrar. `combine` mantém cada fonte na sua cadência e junta as duas na
     * leitura.
     */
    val pares: StateFlow<List<ParPresente>> =
        combine(_pares, _quemFala) { lista, quem ->
            lista.map { it.copy(falando = it.indicativo == quem) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** `true` enquanto transmitimos. É o que acende a moldura da tela inteira. */
    val noAr: StateFlow<Boolean> get() = _noAr
    private val _noAr = MutableStateFlow(false)

    private var radio: RadioTatico? = null
    private var rota: GlassesAudioRoute? = null
    private var cronometro: Job? = null
    private var vigiaDeRede: Job? = null
    private var transporteAtual: TransporteAoVivo? = null
    private var registro: RegistroDeTransmissao? = null
    private var inicioDaFalaMs = 0L

    private val redeConfigurada =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    // ── Ciclo de vida do rádio ────────────────────────────────────────────────

    /**
     * Abre o rádio. Chamado quando a tela da guarnição fica visível.
     *
     * A ordem é a do contrato de boot e não pode ser invertida: **rota de áudio
     * antes da sessão**. HFP totalmente configurado antes de qualquer streaming;
     * o inverso produz captura de voz intermitente.
     */
    fun abrir(canal: String, nomeDoCanal: String, agenteId: String, indicativo: String) {
        if (radio != null) return
        if (!redeConfigurada) {
            _estado.value = EstadoDoPtt.Indisponivel("Servidor não configurado.")
            return
        }

        viewModelScope.launch {
            val r = when (val res = audio.iniciar()) {
                is Result.Success -> res.value
                is Result.Failure -> {
                    // Falha nunca é silêncio, e aqui o canal de aviso é a própria
                    // barra: sem rota não há rádio, e o agente precisa saber antes
                    // de apertar o botão contando com ele.
                    _estado.value = EstadoDoPtt.Indisponivel("Sem rota de áudio. Conecte os óculos.")
                    return@launch
                }
            }
            rota = r

            val transporte = TransporteRealtime(
                // `SessaoDoAgente.config` e não uma cópia local: é ela que carrega
                // o provedor de token e o `privado`. Uma segunda ConfigRealtime
                // montada aqui entraria no canal SEM JWT e sem política — o mesmo
                // gênero de literal gêmeo que o roadmap já flagrou em três lugares
                // apontando para o mesmo UUID por digitação, não por referência.
                config = SessaoDoAgente.config,
                escopo = viewModelScope,
            )

            val novo = RadioTatico(
                escopo = viewModelScope,
                talkGroupId = canal,
                agenteId = agenteId,
                indicativo = indicativo,
                resolverAutor = { id -> cadastroDoGrupo[id] },
                // Fecha o residual que a resolução local deixou: um membro do grupo
                // reivindicando o id de outro membro. `floor_grants.agent_id` vem do
                // JWT em `pedir_canal`, então ninguém obtém piso em nome de terceiro.
                conferirAutor = { tx ->
                    historicoDoCanal?.autorDaTransmissao(tx)?.getOrNull()
                },
                // **Transcrição na origem** (pilar P1): o texto sai do PCM que foi
                // ao ar, uma vez, e é difundido — para todos os receptores exibirem
                // exatamente a mesma string. `EscutaDoAgente` mantém o whisper
                // quente, então não há os ~78 MB de carga por fala.
                transcrever = { pcm -> transcreverNaOrigem(pcm) },
                aoTextoProprio = { id, texto -> aplicarTexto(id, texto, propria = true) },
                aoTextoRecebido = { id, texto -> aplicarTexto(id, texto, propria = false) },
                transporte = transporte,
                codec = codec(),
                // **Derivada do gerente, não constante.** `RadioTatico` tinha
                // `sampleRateHz = 8_000` como padrão e ninguém sobrescrevia,
                // enquanto a captura entrega 16 kHz. A voz transmitida saía uma
                // oitava abaixo com o dobro da duração — o produto não era
                // demonstrável. Passar a taxa REAL da fonte faz o compilador
                // sustentar o acordo: se o gerente mudar, isto muda junto.
                sampleRateHz = audio.taxaDeAmostragemHz,
                // **O piso passa a ser arbitrado pelo servidor quando há sessão.**
                //
                // `ClienteDePisoRemoto` existia desde o M5, chamando a RPC
                // `pedir_canal` — e NUNCA foi instanciado: `grep` devolvia só a
                // própria definição. Era o padrão "escrito, não construído" que o
                // `CLAUDE.md` §6 diz já ter acontecido seis vezes.
                //
                // O que se perdia com o local: `pedir_canal`
                // (`0005_controle_de_piso.sql:78-82`) recusa quem não é membro do
                // talk group. Com o piso resolvido em RAM do processo, essa
                // validação nunca era alcançada — dois agentes podiam achar que
                // detinham o canal ao mesmo tempo, e a seleção de grupo por voz
                // (Fase 2) herdaria uma autorização que só existe no papel.
                piso = pisoDoCanal(agenteId),
                pcmDoMicrofone = { rotaValida -> audio.microfonePcm(rotaValida) },
                abrirFluxoDeSaida = { taxa -> audio.abrirFluxoDeReproducao(taxa) },
                registrarNoHistorico = { id, prio, dur ->
                    viewModelScope.launch {
                        registro?.registrar(
                            transmissaoId = id,
                            talkGroupId = canal,
                            // `ptt` e não "fala": o CHECK da tabela aceita
                            // `('ptt','alerta')` e a função ramifica por este
                            // campo — com um valor inventado ela caía no ramo
                            // geográfico e estourava. Valor de domínio não se
                            // escolhe por leitura agradável.
                            tipo = "ptt",
                            prioridade = prio,
                            duracaoMs = dur,
                            // Sem transcrição ainda: o STT do PTT é a Fase 3. O
                            // campo fica nulo — que significa "não transcrito" —
                            // em vez de string vazia, que significaria "silêncio".
                            transcricao = null,
                        )
                    }
                },
                // Os earcons do rádio saem do mudo. Ver `saidaDoRadio`.
                emitir = { u -> saidaDoRadio.emitir(u) },
                duracaoDoEarconMs = { e -> duracaoDoEarcon(e) },
                aoMudarQuemFala = { quem -> _quemFala.value = quem },
                // Compartilhado com `SaidaUnica`: é o que faz a fala do
                // copiloto (que toca pela MESMA fila) suprimir a captura do
                // rádio também — antes só os earcons do próprio rádio
                // registravam janela. Ver o KDoc de `SaidaUnica`.
                supressor = SaidaUnica.supressor,
            )
            novo.entrarEmModoAtivo(r)
            radio = novo
            transporteAtual = transporte

            // **O registro que torna a troca por voz alcançável em runtime.**
            //
            // Sem esta linha, `Intent.TrocarDeGrupo` compila, tem teste e recusa
            // toda troca com "Abra o rádio primeiro." — construído no sentido de
            // "escrito", que é o que o `CLAUDE.md` §6 chama de mentira.
            //
            // `transmitindoAgora` é lambda e não valor: a transmissão começa e
            // termina entre o registro e o comando falado.
            CanaisDoAgente.registrarRadio(
                trocar = { id -> novo.trocarDeGrupo(id) },
                transmitindoAgora = { novo.transmitindo },
            )

            // O léxico é do processo e carrega uma vez. Aqui e não no login porque
            // é aqui que existe escopo suspenso com sessão garantida — e é
            // idempotente, então reabrir a tela não vai à rede de novo.
            viewModelScope.launch { CanaisDoAgente.carregar(getApplication()) }
            indicativoProprio = indicativo
            nomeDoCanalAtual = nomeDoCanal
            vigiaDeRede = viewModelScope.launch { vigiarRede(nomeDoCanal, transporte) }

            registro = RegistroDeTransmissao(
                config = ConfigRealtime(
                    projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                    apiKey = BuildConfig.SUPABASE_ANON_KEY,
                ),
                tokenDeSessao = tokenDeSessao,
            )

            val historico = HistoricoDoCanal(
                config = ConfigRealtime(
                    projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                    apiKey = BuildConfig.SUPABASE_ANON_KEY,
                ),
                tokenDeSessao = tokenDeSessao,
            )
            historicoDoCanal = historico
            recarga = viewModelScope.launch {
                while (true) {
                    carregarCanal(canal, historico)
                    delay(INTERVALO_DE_RECARGA_MS)
                }
            }
        }
    }

    /**
     * Carrega o histórico e a presença do canal.
     *
     * Chamado ao abrir e a cada [INTERVALO_DE_RECARGA_MS]. Não é tempo real — o
     * tempo real é o áudio; o histórico escrito pode chegar com atraso de
     * segundos sem prejuízo, e uma assinatura permanente para texto custaria
     * bateria pelo turno inteiro por um ganho que ninguém percebe.
     */
    /**
     * Roda o whisper **em escopo de aplicação**, não no da tela.
     *
     * `viewModelScope` morre quando o agente troca de aba, e trocar de aba logo
     * depois de falar é comum — o colega ficaria sem o texto por causa de um gesto
     * de interface. `escopoDeTranscricao` sobrevive à tela; o `await` aqui só
     * espera o resultado.
     *
     * Devolve `null` sem drama quando o modelo não está embarcado: o balão aparece
     * sem texto, que é exatamente o comportamento de antes deste item.
     */
    private suspend fun transcreverNaOrigem(pcm: ShortArray): String? =
        escopoDeTranscricao.async {
            val motor = EscutaDoAgente.de(getApplication()) ?: return@async null
            (motor.transcribe(pcm, taxaDaTranscricaoHz) as? Result.Success)?.value?.text?.trim()
        }.await()

    /**
     * Põe o texto no balão certo. **Os dois casos casam por critérios diferentes, e
     * a diferença é deliberada.**
     *
     * *Recebida* casa por `transmissaoId`, sempre. Entre a fala do colega e o texto
     * dela pode ter começado outra transmissão — escrever "no último balão" poria a
     * frase de um agente embaixo do nome de outro, que num rádio é o erro que não se
     * perdoa.
     *
     * *Própria* não tem essa chave para casar: o balão local nasce em `aoSoltar()`,
     * que é o instante da soltura, e o `transmissaoId` só existe dentro da sessão.
     * Então casa com o **balão local mais recente ainda sem texto**, que é o que
     * acabou de ser criado. O risco de trocar é nulo na prática (a própria fala é
     * uma por vez) e o critério está escrito aqui em vez de virar coincidência.
     */
    private fun aplicarTexto(transmissaoId: String, texto: String, propria: Boolean) {
        _falas.value = comTexto(_falas.value, transmissaoId, texto, propria)
    }

    private suspend fun carregarCanal(canal: String, historico: HistoricoDoCanal) {
        historico.falas(canal).onSuccess { lista ->
            // As inserções otimistas que o servidor ainda não ecoou sobrevivem à
            // recarga. Sem isto, a fala própria apareceria e sumiria a cada dez
            // segundos até o servidor devolvê-la — e o agente veria a própria
            // transmissão piscar, que é pior que não mostrá-la.
            val locaisPendentes = _falas.value.filter { it.id.startsWith(PREFIXO_LOCAL) }
            _falas.value = lista.map { f ->
                FalaNoGrupo(
                    id = f.id,
                    indicativo = f.indicativo,
                    hora = horaDe(f.criadaEmIso),
                    texto = f.transcricao,
                    propria = f.indicativo == indicativoProprio,
                    // Só alerta carrega faixa de prioridade. Conversa de rotina com
                    // faixa colorida transformaria a régua em enfeite, e a cor
                    // deixaria de significar urgência.
                    prioridade = f.prioridade.takeIf { f.tipo == "alerta" },
                    entrega = FalaNoGrupo.Entrega.RECEBIDA,
                )
            } + locaisPendentes
        }

        // O cadastro vem ANTES da lista de presença: é ele que decide se o nome de
        // quem fala pode aparecer, e chegar atrasado significaria mostrar "origem
        // não confirmada" para colega legítimo nos primeiros segundos do turno.
        historico.cadastroDoGrupo(canal).onSuccess { cadastroDoGrupo = it }

        historico.membros(canal).onSuccess { lista ->
            _pares.value = lista
                .filter { it.indicativo != indicativoProprio }
                .map { m ->
                    ParPresente(
                        indicativo = m.indicativo,
                        // "Online" = publicou posição faz pouco. Um booleano de
                        // presença fica `true` quando o processo morre sem avisar,
                        // e um agente que sumiu apareceria disponível.
                        online = m.idadeDaPosicaoS?.let { it <= LIMIAR_DE_PRESENCA_S } == true,
                        // Sempre `false` aqui de propósito: quem preenche é o
                        // `combine` em `pares`. Ver o KDoc lá.
                        falando = false,
                    )
                }
        }
    }

    /** Hora local para a fala que este aparelho acabou de emitir. */
    private fun horaAgora(): String = HORA.format(java.util.Date())

    private fun horaDe(iso: String): String = runCatching {
        HORA.format(java.util.Date.from(java.time.OffsetDateTime.parse(iso).toInstant()))
    }.getOrDefault("--:--:--")

    /**
     * **Mantém a barra honesta sobre a rede.**
     *
     * A versão anterior gravava `Pronto` uma vez, ao abrir, e nunca mais olhava:
     * a barra dizia "segure para falar" com o WebSocket caído, e o agente
     * descobriria no pior momento possível — apertando o botão numa ocorrência.
     *
     * Isto não é detalhe de interface. Este produto é **PTT sobre IP**, não rádio
     * de radiofrequência: sem dados, não há canal. O rádio analógico da
     * corporação funciona em túnel, em subsolo e com a torre caída; este não. Se a
     * tela sugerir a mesma independência, ela mente sobre a única coisa que
     * diferencia os dois — e a mentira só é descoberta na hora em que a diferença
     * importa.
     */
    private suspend fun vigiarRede(canal: String, transporte: TransporteAoVivo) {
        while (true) {
            val conectado = transporte.conectado()
            val atual = _estado.value
            // Não sobrescreve o estado NoAr: a transmissão em curso tem prioridade
            // sobre o relatório de conectividade, e o pré-roll cobre a queda.
            if (atual !is EstadoDoPtt.NoAr) {
                _estado.value = if (conectado) {
                    EstadoDoPtt.Pronto(canal)
                } else {
                    // A causa muda o que o agente faz: rede se resolve andando,
                    // credencial se resolve entrando. Dizer "sem dados" quando é
                    // autorização manda ele procurar torre pelo motivo errado.
                    EstadoDoPtt.Indisponivel(
                        transporte.motivoDaRecusa?.let { "Canal negado. $it" }
                            ?: "Sem dados. O canal depende da rede.",
                    )
                }
            }
            delay(INTERVALO_DA_VIGIA_MS)
        }
    }

    private var historicoDoCanal: HistoricoDoCanal? = null

    /**
     * Escopo da transcrição — **de aplicação, não da tela**, como o roadmap pede.
     *
     * `Dispatchers.Default` porque o whisper é CPU: em `IO` ele disputaria a piscina
     * de rede com o próprio envio dos quadros.
     */
    private val escopoDeTranscricao = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** O whisper espera 16 kHz; é a taxa em que a captura já entrega. */
    private val taxaDaTranscricaoHz = 16_000
    private var recarga: Job? = null
    private var indicativoProprio: String = ""
    private var nomeDoCanalAtual: String = ""

    /** Fecha o rádio e devolve a rota. Chamado ao sair da tela ou encerrar o turno. */
    fun fechar() {
        cronometro?.cancel()
        cronometro = null
        vigiaDeRede?.cancel()
        vigiaDeRede = null
        recarga?.cancel()
        recarga = null
        historicoDoCanal = null
        transporteAtual = null
        // As medições do turno morrem junto com o `RadioTatico`. Registrar antes
        // é o que transforma um turno de uso em número consultável depois — sem
        // isto, a única forma de ver p50/p95 seria pedir por voz durante a
        // operação, e ninguém faz isso no meio de uma ocorrência.
        radio?.telemetria?.relatorio()?.let { Log.i(TAG, it) }
        // O ciclo de voz e a preempção de P1 saem no mesmo ponto: é o único
        // momento alcançável em que alguém lê os dois relatórios juntos.
        Log.i(TAG, SaidaUnica.telemetriaDoCiclo.relatorio())
        radio?.sairDeModoAtivo()
        radio = null
        rota = null
        _noAr.value = false
        _estado.value = EstadoDoPtt.Indisponivel("Rádio fechado.")
        audio.liberar()
    }

    // ── Push-to-talk ──────────────────────────────────────────────────────────

    /**
     * Dedo desceu.
     *
     * Não suspende, não espera rede, não valida nada que dependa de resposta
     * remota. A captura começa no instante do toque — a concessão de canal corre
     * atrás, e o pré-roll cobre o intervalo.
     */
    fun aoPressionar() {
        val r = rota ?: return
        val radioAtivo = radio ?: return
        inicioDaFalaMs = System.currentTimeMillis()

        radioAtivo.aoPressionar(r)
        _noAr.value = true
        _estado.value = EstadoDoPtt.NoAr(0L, 0f)

        cronometro?.cancel()
        cronometro = viewModelScope.launch {
            while (true) {
                val atual = _estado.value
                if (atual !is EstadoDoPtt.NoAr) break
                _estado.value = EstadoDoPtt.NoAr(
                    decorridoMs = System.currentTimeMillis() - inicioDaFalaMs,
                    amplitude = radioAtivo.amplitudeAtual,
                )
                // 60 ms: acompanha a voz sem recompor a barra a cada quadro de
                // áudio. A forma de onda é informação, não animação — mais rápido
                // que isso só gastaria bateria.
                delay(60)
            }
        }
    }

    /** Dedo subiu — por qualquer motivo, inclusive escorregar ou a tela apagar. */
    fun aoSoltar() {
        cronometro?.cancel()
        cronometro = null
        _noAr.value = false
        radio?.aoSoltar()

        // **Inserção otimista, e é ela que faz o balão da direita dizer se saiu.**
        //
        // Antes, `Entrega.ENVIADA` e `ENFILEIRADA` não tinham produtor nenhum:
        // `carregarCanal` gravava `RECEBIDA` fixo em toda fala, inclusive na
        // própria, então o ramo da UI que mostrava "na fila" era código morto. O
        // agente não tinha como saber se a transmissão saiu — num rádio, essa é a
        // única pergunta que importa depois de falar.
        //
        // O texto entra vazio de propósito. A transcrição da fala própria ainda
        // não existe neste caminho (o único STT vive no ciclo de voz), e escrever
        // um placeholder tipo "(sua transmissão)" seria a interface inventando
        // conteúdo. O balão aparece com hora e estado — que é o que se sabe — e a
        // recarga do canal substitui pelo texto real quando ele existir.
        inserirFalaPropria()
        // Não presume `Pronto`: a rede pode ter caído durante a fala, e afirmar
        // prontidão logo depois de transmitir é o pior instante para errar.
        _estado.value = if (transporteAtual?.conectado() == true) {
            EstadoDoPtt.Pronto(canalAtual())
        } else {
            EstadoDoPtt.Indisponivel(
                transporteAtual?.motivoDaRecusa?.let { "Canal negado. $it" }
                    ?: "Sem dados. O canal depende da rede.",
            )
        }
    }

    /**
     * Põe a própria fala no thread, com o estado de entrega que o transporte
     * sustenta neste instante.
     *
     * `conectado()` é lido **agora**, e não presumido: a rede pode ter caído
     * durante a fala, e afirmar "enviada" logo depois de transmitir é o pior
     * instante para errar.
     *
     * Sem rede a fala **não saiu e não está guardada em lugar nenhum** — não há
     * fila (`ArquivoDeFalasDiferidas` não tem chamador). O rótulo diz isso, e não
     * "na fila", porque "na fila" faria o agente seguir a ocorrência contando com
     * um apoio que nunca foi pedido.
     */
    private fun inserirFalaPropria() {
        val id = "$PREFIXO_LOCAL${System.currentTimeMillis()}"
        val entrega = if (transporteAtual?.conectado() == true) {
            FalaNoGrupo.Entrega.ENVIADA
        } else {
            FalaNoGrupo.Entrega.NAO_SAIU
        }
        _falas.value = _falas.value + FalaNoGrupo(
            id = id,
            indicativo = indicativoProprio,
            hora = horaAgora(),
            texto = "",
            propria = true,
            prioridade = null,
            entrega = entrega,
        )
    }

    private fun canalAtual(): String =
        nomeDoCanalAtual.ifBlank { CanalDoPiloto.NOME }

    // ── Telemetria ────────────────────────────────────────────────────────────

    // O relatório de telemetria sai por `fechar()`, logo acima — via
    // `adb logcat -s ClaryonField`. Houve aqui um `relatorioDeTelemetria()`
    // público e SEM CHAMADOR: função de diagnóstico que ninguém chama dá a quem
    // lê o código a impressão de que o produto exporta a métrica. O item do
    // ROADMAP pede "comando de diagnóstico"; enquanto ele não existir, o
    // honesto é não fingir que existe.

    // ── Peças auxiliares ──────────────────────────────────────────────────────

    /**
     * Opus na taxa em que o microfone de fato entrega.
     *
     * `ConfigOpus` tem `sampleRateHz = 8_000` como padrão, e usá-lo contra uma
     * fonte de 16 kHz produzia dois defeitos somados: a fala saía grave e lenta, e
     * `amostrasPorQuadro = taxa/50` partia cada bloco de 20 ms reais em dois
     * "quadros", **dobrando a taxa de pacotes na rede**.
     *
     * 16 kHz aqui é *contêiner*, não banda larga: o elo HFP até os óculos é 8 kHz
     * mono por doc oficial do DAT, então acima de 4 kHz não chega informação nova.
     * O ganho é correção de tom e duração, não fidelidade — e é isso que estava
     * quebrado.
     */
    private fun codec(): CodecDeVoz =
        MediaCodecOpus(ConfigOpus(sampleRateHz = audio.taxaDeAmostragemHz))

    /**
     * Escolhe o árbitro do piso, e **declara a degradação em vez de escondê-la**.
     *
     * Sem sessão não há JWT, e sem JWT o servidor recusa — mas o rádio precisa
     * funcionar em túnel, subsolo e com a torre caída, que é justamente quando a
     * ocorrência acontece. Então o local continua existindo como modo degradado.
     *
     * O que ele **não** pode ser é silencioso: com piso local, dois agentes podem
     * achar que detêm o canal ao mesmo tempo e falar por cima. Quem opera precisa
     * saber que está nesse modo — por isso o log e o estado, não só o `else`.
     */
    private suspend fun pisoDoCanal(agenteId: String): ClienteDePiso {
        // **`tokenValido()`, não `tokenCorrente`.** O segundo é cache da última
        // credencial JÁ validada, e logo depois do login ninguém validou nada
        // ainda: a decisão caía em LOCAL e ficava assim o turno inteiro, porque é
        // tomada uma vez só, aqui.
        //
        // O sintoma era visível e ninguém ligava os pontos: no emulador, um login
        // bem-sucedido era seguido de "piso LOCAL: sem sessão" um segundo depois.
        // Com piso local, dois agentes podem achar que detêm o canal e falar por
        // cima — que é exatamente o que `ClienteDePisoRemoto` foi ligado para
        // impedir, e ele estava sendo desligado por uma corrida de inicialização.
        //
        // Esta função já roda dentro do `launch` de `abrir`, então suspender aqui
        // não custa nada — e `tokenValido()` de quebra popula o cache que as
        // leituras síncronas seguintes vão usar.
        val token = SessaoDoAgente.tokenValido(getApplication())
        if (token == null) {
            Log.w(TAG, "piso LOCAL: sem sessão. Sem arbitragem do servidor entre aparelhos.")
            pisoRemoto = false
            return ClienteDePisoLocal()
        }
        pisoRemoto = true
        return ClienteDePisoRemoto(
            config = ConfigRealtime(
                projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                apiKey = BuildConfig.SUPABASE_ANON_KEY,
            ),
            // Lê o cache validado a cada chamada: a sessão pode ser renovada
            // durante o turno, e capturar o token de agora congelaria um que
            // expira em uma hora.
            jwt = { SessaoDoAgente.tokenCorrente.orEmpty() },
            agenteIdLocal = agenteId,
        )
    }

    /** `true` quando o piso é arbitrado pelo servidor. Diagnóstico e telemetria. */
    var pisoRemoto: Boolean = false
        private set

    private fun duracaoDoEarcon(earcon: Earcon): Long = when (earcon) {
        Earcon.GRAVANDO -> 2_000L
        else -> 320L
    }

    override fun onCleared() {
        // ANTES de `fechar()`: o lambda registrado segura este ViewModel, e um
        // ViewModel morto que ainda sabe trocar de canal é pior que nenhum.
        CanaisDoAgente.esquecerRadio()
        fechar()
        super.onCleared()
    }

    private companion object {
        const val TAG = "ClaryonField"

        /**
         * Marca a fala inserida por este aparelho e ainda não ecoada pelo
         * servidor. É por este prefixo que a recarga sabe o que preservar.
         */
        const val PREFIXO_LOCAL = "local-"


        /**
         * 2 s. A queda de rede não precisa ser detectada em milissegundos — mas
         * precisa aparecer antes de o agente apertar o botão contando com ela.
         */
        const val INTERVALO_DA_VIGIA_MS = 2_000L

        /**
         * 10 s. O histórico escrito não é tempo real — o tempo real é o áudio.
         * Recarregar mais rápido gastaria bateria por um ganho imperceptível.
         */
        const val INTERVALO_DE_RECARGA_MS = 10_000L

        /** Acima disso o par deixa de contar como presente no canal. */
        const val LIMIAR_DE_PRESENCA_S = 120
        val HORA: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
    }
}
