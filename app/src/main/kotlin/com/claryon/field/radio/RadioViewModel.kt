package com.claryon.field.radio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.claryon.field.voice.SileroVoiceActivityDetector
import com.claryon.field.service.CopilotService
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.audio.SaidaUnica
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.sound.EarconSynthesizer
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.field.agent.ClaryonIntentExecutor.TrocaDeGrupo
import com.claryon.agent.FalhaOperacional
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.telas.CanalCorrente
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.Guarnicao
import com.claryon.field.ui.telas.GuarnicaoNaLista
import com.claryon.field.ui.telas.MembroDaGuarnicao
import com.claryon.field.ui.telas.RecusaDaTroca
import com.claryon.field.ui.telas.ResultadoDaTroca
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.net.ClienteDePiso
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.ClienteDePisoRemoto
import com.claryon.net.CodecDeVoz
import com.claryon.net.ConfigOpus
import com.claryon.net.HistoricoDoCanal
import com.claryon.net.RegistroDeTransmissao
import com.claryon.net.RespostaDePosicao
import com.claryon.net.ConfigRealtime
import com.claryon.net.MediaCodecOpus
import com.claryon.net.TransporteAoVivo
import com.claryon.net.TransporteRealtime
import com.claryon.field.voice.EscutaDoAgente
import kotlinx.coroutines.CoroutineScope
import java.io.File
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
import com.claryon.field.local.EstadoDaTransmissao
import com.claryon.field.local.TransmissaoDePosicao

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

    init {
        // **Fora da main thread, e fora do construtor de verdade.** Ler `filesDir`
        // no construtor é literalmente o defeito que custou 965 ms ao `SyncManager`
        // — e o achado de lá foi que tirar o `mkdirs()` não resolveu, porque a E/S
        // era do próprio `filesDir`. A recarga do canal só acontece depois de rede,
        // então não há corrida: o arquivo está em memória muito antes de existir um
        // balão para marcar.
        viewModelScope.launch(Dispatchers.IO) { cortesConhecidos.carregar() }
    }

    /**
     * `{indicativo → idade da posição em segundos}` — só de quem TEM posição.
     *
     * Deliberadamente separado do cadastro: as duas fontes respondem perguntas
     * diferentes e uma delas é menor que a guarnição. Ver [guarnicao].
     */
    private val _idadeDaPosicao = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val _quemFala = MutableStateFlow<String?>(null)

    /**
     * **O canal em que estamos, para a tela.**
     *
     * Existe porque `MainActivity` passava `CanalDoPiloto.NOME` — uma **constante**
     * — para o cabeçalho, enquanto este ViewModel reconciliava o canal com o
     * cadastro trinta linhas abaixo. Um agente de outra lotação lia "GTA-3 Alfa"
     * no topo estando noutra guarnição, e nada na tela denunciava: era o mesmo
     * defeito que `CanaisDoAgente` foi criado para matar, sobrevivendo na camada
     * de cima.
     *
     * É **projeção** de `CanaisDoAgente`, nunca uma segunda verdade: só é escrito
     * a partir de `grupoCorrenteId/Nome`, e só depois de o rádio aceitar a troca.
     */
    private val _canal = MutableStateFlow(
        CanalCorrente(CanalDoPiloto.ID, CanalDoPiloto.NOME, confirmadoPeloServidor = false),
    )
    val canal: StateFlow<CanalCorrente> = _canal.asStateFlow()

    /**
     * **A guarnição: cadastro completo, com a idade de quem publicou posição.**
     *
     * `null` significa "o cadastro ainda não respondeu" e vira
     * `cadastroCarregado = false` na exposição — vazio ≠ nulo, a mesma distinção
     * que `CanaisDoAgente.lexico` faz e pelo mesmo motivo: lista ausente e lista
     * vazia pedem frases diferentes.
     */
    private val _cadastro = MutableStateFlow<Map<String, String>?>(null)

    /**
     * A idade da posição **deste** aparelho — com duas fontes, na ordem de
     * [idadePropriaDe].
     *
     * A preferida é `idade_solicitante_s`, que vem na mesma resposta de
     * `posicoes_do_grupo` (`0007`) e não custa chamada nenhuma. **Mas ela só existe
     * quando existe linha**, e `0021:130-149` faz `cross join minha` filtrando
     * `a.id <> eu.id`: a resposta só traz PARES.
     *
     * Este KDoc dizia, até 22/08, que sem ela *"o portador apareceria na própria
     * guarnição como sem posição enquanto publica normalmente"* — descrevendo como
     * defeito evitado exatamente o que o código fazia numa guarnição de um, com os
     * colegas numa garagem, ou no começo do turno. A lista vinha vazia e a promessa
     * virava mentira.
     *
     * A segunda fonte é local e independente de par: `TransmissaoDePosicao`, o
     * instante do último POST aceito. Ver [idadePropriaDe] para por que o servidor
     * tem precedência.
     */
    private val _idadePropria = MutableStateFlow<Int?>(null)

    /**
     * `true` depois que o agente pediu escuta, `false` depois que pediu para sair.
     *
     * **Intenção, e nada além.** Nunca vira "está no canal": quem afirma isso é
     * [estado], que vem do rádio. A separação é a lição dos 168 quadros publicados
     * para um canal em que este aplicativo não tinha entrado, com o indicador
     * aceso — o indicador estava lendo o pedido, não o fato.
     */
    private val _pediuEscuta = MutableStateFlow(false)
    val pediuEscuta: StateFlow<Boolean> = _pediuEscuta.asStateFlow()

    /**
     * As guarnições em que este agente pode entrar. `null` = o léxico não carregou.
     *
     * `CanaisDoAgente.grupos` existia com o comentário *"Exposto para a tela"* e
     * **zero chamadores** — `grep` devolvia só a própria definição. Este é o
     * caminho até a tela.
     */
    private val _guarnicoes = MutableStateFlow<List<GuarnicaoNaLista>?>(null)
    val guarnicoes: StateFlow<List<GuarnicaoNaLista>?> = _guarnicoes.asStateFlow()

    /**
     * **Quem detém o piso agora.** `null` quando o canal está calado.
     *
     * Alimentava só `ParPresente.falando`, e por isso havia um valor que **nunca
     * chegava à tela**: `AUTOR_NAO_CONFIRMADO`. A régua de presença casa por
     * indicativo (`it.indicativo == quem`), e "Origem não confirmada" não casa com
     * nenhum par — então uma transmissão de autoria duvidosa tocava no
     * alto-falante sem absolutamente nada na tela, enquanto o `RadioTatico`
     * escrevia em log que a origem era duvidosa. O ataque real deste produto é
     * personificação; era o sinal contra ele que estava mudo.
     *
     * Exposto cru, sem casar com nada: quem decide como exibir é a tela.
     */
    val quemFala: StateFlow<String?> = _quemFala.asStateFlow()

    /**
     * **A guarnição, montada das duas fontes — e é aqui que o denominador muda.**
     *
     * Até esta entrega a tela recebia `posicoes_do_grupo` e nada mais. Aquela
     * função faz `join` com `agent_positions`, então **quem nunca publicou posição
     * não aparecia — nem como ausente**: a contagem do cabeçalho não era a
     * guarnição, era quem tem posição, e o KDoc do painel de detalhes já escrevia
     * que a lista "é menor que a guarnição, e não sabe dizer quanto menor".
     *
     * `cadastro_do_grupo` (`0013`) devolve a `memberships` inteira e **já era
     * buscada a cada dez segundos** — este ViewModel a colapsava num `Map` de
     * autoria e a tela nunca a via. O que faltava não era dado nem rede: era
     * caminho. Custo desta correção em chamadas de rede: **zero**.
     *
     * Três fontes, três cadências, combinadas só na leitura:
     *
     *  - o **cadastro** (10 s) dá quem é da guarnição;
     *  - a **posição** (10 s) dá a idade de quem publicou;
     *  - **quem fala** (milissegundos) dá o piso. Gravar `falando` na lista crua o
     *    deixaria até dez segundos atrasado — mostrando "falando" depois que a
     *    pessoa calou, que é pior que não mostrar.
     */
    val guarnicao: StateFlow<Guarnicao> =
        combine(_cadastro, _idadeDaPosicao, _idadePropria, _quemFala) { cadastro, idades, minha, quem ->
            if (cadastro == null) {
                // Sem cadastro, só resta o que a lista de posições sabe — e ela é
                // menor que a guarnição. A tela declara isso; ver `resumoDaGuarnicao`.
                Guarnicao(
                    membros = idades.map { (indicativo, idade) ->
                        MembroDaGuarnicao(indicativo, idade, proprio = false, falando = indicativo == quem)
                    },
                    cadastroCarregado = false,
                )
            } else {
                Guarnicao(
                    membros = cadastro.values.map { indicativo ->
                        val proprio = indicativo == indicativoProprio
                        MembroDaGuarnicao(
                            indicativo = indicativo,
                            // O próprio portador tem idade própria, de
                            // `idade_solicitante_s`. Sem isso ele apareceria "sem
                            // posição" na própria guarnição enquanto publica.
                            idadeDaPosicaoS = if (proprio) minha else idades[indicativo],
                            proprio = proprio,
                            falando = indicativo == quem,
                        )
                    },
                    cadastroCarregado = true,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Guarnicao())

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

    /**
     * A identidade da sessão, guardada na primeira abertura.
     *
     * Existe para [entrarNaEscuta] poder reabrir o rádio sem que a tela precise
     * guardar `agenteId` e `indicativo` — dado de sessão que sobe para a interface
     * é dado que alguém esquece de descer de volta. O **canal** não é guardado
     * aqui de propósito: ele vem de [_canal] no instante da reabertura, senão
     * reentrar depois de trocar de guarnição devolveria o agente à anterior.
     */
    private data class Credenciais(val agenteId: String, val indicativo: String)

    private var credenciais: Credenciais? = null

    // ── Ciclo de vida do rádio ────────────────────────────────────────────────

    /**
     * Abre o rádio. Chamado quando a tela da guarnição fica visível.
     *
     * Duas ordens, e nenhuma das duas pode ser invertida:
     *
     *  - **rota de áudio antes da sessão** — HFP totalmente configurado antes de
     *    qualquer streaming; o inverso produz captura de voz intermitente;
     *  - **fio de voz antes da rota de áudio** — `CanaisDoAgente.registrarRadio`
     *    roda antes de tudo, e antes de qualquer `return` desta função. Ele não
     *    depende de HFP, e enquanto dependeu, todo aparelho sem fone pareado ficava
     *    sem caminho entre o roteador de voz e o rádio. Trava:
     *    `FioDeVozSemRotaDeAudioTest`.
     */
    fun abrir(canal: String, nomeDoCanal: String, agenteId: String, indicativo: String) {
        // A INTENÇÃO é registrada antes de qualquer trabalho, e é só isso que ela
        // é: pedir escuta. Quem afirma que estamos no canal é `estado`, que vem do
        // rádio — ver o KDoc de `_pediuEscuta`.
        _pediuEscuta.value = true
        credenciais = Credenciais(agenteId, indicativo)
        _canal.value = CanalCorrente(canal, nomeDoCanal, CanaisDoAgente.canalConfirmadoPeloServidor)
        if (radio != null) return

        // **O fio entre o roteador de voz e o rádio, atado ANTES da rota de áudio —
        // e antes de qualquer outra saída desta função.**
        //
        // Ele viveu quinze linhas abaixo do `return@launch` da falha de HFP até
        // 22/08. Consequência medida por auditoria: em qualquer aparelho sem rota —
        // óculos não pareados, fone ausente, emulador — `registrarRadio` **nunca
        // rodava**, `CanaisDoAgente` ficava com `trocador == null` e
        // `abridor == { false }`, e *"Claryon, guarnição 3 na escuta"* morria sem
        // chamador com detector, whisper e roteador todos funcionando.
        //
        // Nada aqui precisa de HFP: são lambdas que rodam no celular. O que precisa
        // de rota é o rádio **funcionar**, e isso é o que `noAr` responde — no
        // instante do comando, não agora. Registrar cedo sem `noAr` seria trocar um
        // defeito por outro: o fio atado faria a política confirmar "já estamos lá"
        // e o executor chamaria o abridor, que devolveria `false` cru — falado como
        // "Canal ocupado." num canal que não está ocupado.
        //
        // `radio` é lido a cada chamada (`radio?.`) e não capturado: ele nasce no
        // `launch` abaixo, é anulado por `fechar()` e volta na reabertura. Capturar
        // o valor de agora ataria o fio a `null` para sempre.
        CanaisDoAgente.registrarRadio(
            trocar = { id -> radio?.trocarDeGrupo(id) ?: false },
            // Lambda e não valor: a transmissão começa e termina entre o registro e
            // o comando falado.
            transmitindoAgora = { radio?.transmitindo == true },
            // A única fonte da verdade sobre "dá para pôr o agente no ar": o
            // `RadioTatico` só é publicado em `radio` depois de `entrarEmModoAtivo`,
            // que exige a `GlassesAudioRoute` por tipo.
            noAr = { radio != null },
            // **A SEGUNDA instância do Silero, com teto de 30 s.**
            //
            // A do ciclo de voz tem teto de 12 s porque espera um COMANDO. Uma
            // transmissão de rádio é RELATO e pode durar os 30 s do teto duro de
            // `SessaoPtt`. Reaproveitar a instância do ciclo cortaria toda
            // transmissão aos 12 s — e o agente não teria como saber, porque do
            // lado dele o áudio continuou saindo.
            //
            // Construída por abertura, e não guardada: o modelo tem 629 KB e o
            // handle nativo tem `finalize()`. Ver `SileroVoiceActivityDetector.novoVad`.
            abrir = {
                val ativo = radio
                if (ativo == null) {
                    // Não deveria ser alcançável: a política recusa por `noAr` antes
                    // de o executor chegar aqui. Fica como rede — e recusa com log,
                    // porque um `false` sem rastro é a recusa muda que este arquivo
                    // acabou de deixar de produzir.
                    Log.w(TAG, "abertura por voz sem rádio no ar — recusando")
                    false
                } else {
                    ativo.abrirPorVoz(
                        runCatching {
                            SileroVoiceActivityDetector(
                                assets = getApplication<Application>().assets,
                                sampleRateHz = 16_000,
                                falaMaximaS = 30.0f,
                            )
                        }.onFailure {
                            // Degradação honesta: sem VAD a transmissão abre e o
                            // teto duro fecha. Recusar aqui deixaria o agente mudo
                            // justamente quando ele pediu para falar.
                            Log.w(TAG, "VAD da transmissão não subiu — fecho só pelo teto", it)
                        }.getOrNull(),
                    )
                }
            },
        )

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
                    //
                    // Pela voz o aviso sai por `CanaisDoAgente`: o fio já está atado
                    // acima, `noAr` responde `false`, e "guarnição 3 na escuta"
                    // recusa com `RADIO_FECHADO` — "Abra o rádio primeiro." — em vez
                    // de não encontrar ninguém para perguntar.
                    _estado.value = EstadoDoPtt.Indisponivel("Sem rota de áudio. Conecte os óculos.")

                    // **O léxico também não depende de HFP, e sem ele a recusa dá o
                    // motivo errado.**
                    //
                    // `carregar` mora lá embaixo, no bloco de reconciliação, e por
                    // isso não rodava aqui: `lexico` ficava nulo, `ResolvedorDeGrupo`
                    // devolvia `SemLexico`, e "guarnição 3 na escuta" era recusado
                    // com *"Sem lista. Entre de novo."* — que manda o agente refazer
                    // o login por um problema de fone. Com a lista carregada, a mesma
                    // frase é recusada por `RADIO_FECHADO`: *"Abra o rádio primeiro."*
                    //
                    // Idempotente e sem corrida com a reconciliação: uma reabertura
                    // posterior lê `CanaisDoAgente.grupoCorrenteId` já reconciliado —
                    // `MainActivity` e `entrarNaEscuta` passam justamente esse id —,
                    // então `antes == agora` e pular a troca de socket está CERTO,
                    // porque o `RadioTatico` já nasce no grupo do cadastro.
                    CanaisDoAgente.carregar(getApplication())
                    // Sem isto o cabeçalho continuaria no canal provisório enquanto
                    // `CanaisDoAgente` já sabe o do cadastro — duas verdades sobre em
                    // que guarnição estamos. `recarregar = false`: não há rádio nem
                    // histórico para derrubar.
                    adotarCanalCorrente(recarregar = false)
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
                resolverAutor = { id -> _cadastro.value?.get(id) },
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
                            // `_canal.value.id`, e **não** o parâmetro `canal`: ele é
                            // capturado na abertura e não acompanha a troca de
                            // guarnição. Registrar a transmissão no talk group
                            // anterior é escrever no livro-razão errado, e o RLS não
                            // reclama — o agente é membro dos dois.
                            talkGroupId = _canal.value.id,
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
                // Duração REAL do PCM, não tabela. Ver `EarconSynthesizer.duracaoMs`:
                // a tabela à mão que morava aqui já divergia de três earcons, e a
                // janela de supressão fechava antes do som acabar.
                duracaoDoEarconMs = { e -> EarconSynthesizer.duracaoMs(e) },
                aoMudarQuemFala = { quem -> _quemFala.value = quem },
                // Compartilhado com `SaidaUnica`: é o que faz a fala do
                // copiloto (que toca pela MESMA fila) suprimir a captura do
                // rádio também — antes só os earcons do próprio rádio
                // registravam janela. Ver o KDoc de `SaidaUnica`.
                supressor = SaidaUnica.supressor,
            )
            novo.entrarEmModoAtivo(r)
            radio = novo
            // **O portão que cala a palavra de ativação enquanto o agente
            // transmite.** Vive aqui porque é aqui que o `RadioTatico` existe; a
            // escuta mora no serviço e não tem como alcançá-lo. Um "Claryon"
            // dito no ar é conversa com pessoas, não comando para o copiloto.
            CopilotService.radioNoAr = { novo.transmitindo }
            transporteAtual = transporte

            // O fio até `CanaisDoAgente` já está atado — ele foi registrado ANTES da
            // rota, e a partir da linha `radio = novo` acima ele passa a apontar
            // para este `RadioTatico` sozinho, porque lê o campo e não uma captura.
            // Reregistrar aqui reintroduziria o defeito de 22/08 pela porta dos
            // fundos: o segundo registro capturaria `novo`, e a falha de rota (que
            // retorna antes) voltaria a deixar o aparelho sem fio nenhum.

            // O léxico é do processo e carrega uma vez. Aqui e não no login porque
            // é aqui que existe escopo suspenso com sessão garantida — e é
            // idempotente, então reabrir a tela não vai à rede de novo.
            viewModelScope.launch {
                val antes = CanaisDoAgente.grupoCorrenteId
                CanaisDoAgente.carregar(getApplication())
                // **Se o cadastro disse outro canal, muda para ele.**
                //
                // `abrir` acontece na composição, antes de a rede responder, e
                // por isso começa no canal provisório. Sem esta reconciliação o
                // agente ficaria no id fixo o turno inteiro — ouvindo e falando
                // numa guarnição que não é a dele — e nada na tela denunciaria,
                // porque o nome exibido também vinha da constante.
                val agora = CanaisDoAgente.grupoCorrenteId
                // A lista de guarnições ganha caminho até a tela. `CanaisDoAgente.grupos`
                // existia com o comentário "Exposto para a tela" e zero chamadores.
                publicarGuarnicoes()
                if (agora != antes) {
                    Log.i(TAG, "canal reconciliado com o cadastro: ${CanaisDoAgente.grupoCorrenteNome}")
                    if (novo.trocarDeGrupo(agora)) {
                        // **Adotar o canal derruba o histórico do anterior.**
                        //
                        // Antes daqui, a reconciliação trocava o socket e deixava a
                        // recarga lendo o grupo capturado no parâmetro de `abrir` —
                        // ou seja, TODA abertura em que o cadastro discordasse do
                        // canal provisório deixava a tela mostrando o histórico da
                        // guarnição errada, indefinidamente. O `Log.i` acima dizia
                        // "canal reconciliado" enquanto a tela mentia.
                        adotarCanalCorrente()
                    } else {
                        // Falha nunca é silêncio: seguir no canal errado achando
                        // que trocou é o pior desfecho desta feature.
                        Log.w(TAG, "NÃO consegui entrar no canal do cadastro — seguindo no provisório")
                    }
                } else {
                    // Mesmo sem troca, o NOME e a confirmação vêm do cadastro: o da
                    // constante é chute de código.
                    adotarCanalCorrente(recarregar = false)
                }
            }
            indicativoProprio = indicativo
            vigiaDeRede = viewModelScope.launch { vigiarRede(transporte) }

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
            iniciarRecarga(historico)
        }
    }

    /**
     * **O laço de recarga segue o canal CORRENTE, não o da abertura.**
     *
     * O defeito que isto conserta não era teórico: `carregarCanal(canal, …)`
     * capturava o `talkGroupId` do parâmetro de [abrir], e nada mais no ViewModel
     * o reescrevia. Depois de qualquer troca — a reconciliação automática com o
     * cadastro, que roda em **toda** abertura, o comando falado "guarnição N na
     * escuta", e agora o toque na lista — o socket ia para o grupo novo e a tela
     * continuava lendo o histórico, o cadastro e as posições do **grupo antigo**.
     *
     * A tela ficava impossível de desmentir: o nome no topo vinha da constante, e
     * as falas de baixo vinham do grupo anterior. Duas mentiras que se sustentavam.
     */
    private fun iniciarRecarga(historico: HistoricoDoCanal) {
        recarga?.cancel()
        recarga = viewModelScope.launch {
            while (true) {
                carregarCanal(_canal.value.id, historico)
                delay(INTERVALO_DE_RECARGA_MS)
            }
        }
    }

    /**
     * Adota em [_canal] o que `CanaisDoAgente` já decidiu, e limpa o que era do
     * grupo anterior.
     *
     * **Limpar é a parte que não pode faltar.** Sem isto, entre a troca e a
     * próxima recarga a tela mostraria por até dez segundos as falas, o cadastro e
     * as idades da guarnição anterior sob o nome da nova — que é pior que a versão
     * antiga do defeito, porque agora o cabeçalho estaria certo e o conteúdo
     * errado.
     *
     * Não é uma segunda verdade: só lê `CanaisDoAgente`, nunca escreve nele, e só
     * roda **depois** de o rádio ter aceitado a troca.
     */
    private fun adotarCanalCorrente(recarregar: Boolean = true) {
        _canal.value = CanalCorrente(
            id = CanaisDoAgente.grupoCorrenteId,
            nome = CanaisDoAgente.grupoCorrenteNome,
            confirmadoPeloServidor = CanaisDoAgente.canalConfirmadoPeloServidor,
        )
        publicarGuarnicoes()
        if (!recarregar) return
        _falas.value = emptyList()
        _cadastro.value = null
        _idadeDaPosicao.value = emptyMap()
        _idadePropria.value = null
        _quemFala.value = null
        historicoDoCanal?.let { iniciarRecarga(it) }
    }

    /** Projeta o léxico de `CanaisDoAgente` na lista que a tela desenha. */
    private fun publicarGuarnicoes() {
        val lista = CanaisDoAgente.grupos
        // Vazio aqui significa "não carregou": `CanaisDoAgente.carregar` recusa
        // adotar lista vazia e deixa o léxico nulo de propósito. Publicar `null`
        // preserva a distinção — a tela precisa dizer "não carregou", nunca "você
        // não tem guarnição".
        _guarnicoes.value = if (lista.isEmpty()) {
            null
        } else {
            lista.map {
                GuarnicaoNaLista(
                    id = it.id,
                    nome = it.nome,
                    rotuloFalado = it.rotuloFalado,
                    corrente = it.id == CanaisDoAgente.grupoCorrenteId,
                )
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

    /**
     * **A rede cortou a fala de um colega no meio.**
     *
     * Fecha o caminho que parou no meio em 22/08: `FimDaFala.CORTADA_NO_MEIO` chegava
     * ao **ouvido** — earcon mais `FALA_DO_COLEGA_CORTADA` — e não chegava ao
     * **balão**, que desenhava a fala truncada campo por campo igual à inteira. Quem
     * estava de capacete ou fora do alcance do fone não recebia o fato por caminho
     * nenhum.
     *
     * ---
     * ### O conjunto existe porque o servidor não guarda isto
     *
     * Marcar só a lista viva não bastaria, e o defeito seria pior que a ausência: o
     * `carregarCanal` faz poll a cada 10 s e **reconstrói toda fala recebida a partir
     * do servidor**, onde `cortadaPelaRede` nasce `false`. A marca apareceria e
     * sumiria sozinha na próxima volta — um sinal que pisca é menos confiável que
     * sinal nenhum, porque ensina o agente a duvidar do que a tela mostra.
     *
     * `transmissions` não tem coluna para o motivo do fim, e não é omissão a
     * consertar aqui: o corte é um fato do **receptor** — o emissor sumiu sem
     * anunciar nada, e é o `core-net/Receptor.kt` deste aparelho que conclui isso
     * depois de esperar a janela de jitter inteira. Quem observou é quem guarda.
     *
     * Vive em RAM e morre com o processo, de propósito. Não é evidência e não vai
     * para disco; e o `adotarCanalCorrente` o esvazia junto com `_falas`, porque id
     * de transmissão de outra guarnição não tem o que marcar nesta.
     *
     * Idempotente nos dois níveis — o `Set` e `comCorteDaRede` —, porque `Terminou`
     * chega uma vez por transmissão e recompor uma lista que rola custa quadro.
     */
    private fun marcarCorteDaRede(transmissaoId: String) {
        _falas.value = comCorteDaRede(_falas.value, transmissaoId)
        // Só grava quando o id é novo — `marcar` devolve `false` para repetido, e
        // uma escrita por evento reentregue seria E/S no caminho de uma lista que
        // rola. A gravação sai da main thread pelo mesmo motivo que derrubou 965 ms
        // do `SyncManager`: E/S em `filesDir` não é barata só por ser pequena.
        if (cortesConhecidos.marcar(transmissaoId)) {
            viewModelScope.launch(Dispatchers.IO) { cortesConhecidos.gravar() }
        }
    }

    /**
     * Os ids que este aparelho **ouviu** terminarem no meio — em disco, não em RAM.
     *
     * Era um `mutableSetOf` de processo, e isso era um defeito com prazo: o serviço é
     * `START_STICKY`, o sistema o recria, e depois disso uma fala cortada voltava a
     * parecer inteira sem que nada na tela dissesse que a informação se perdeu. O
     * aparelho **tinha** o fato, de primeira mão, e o esquecia.
     *
     * A escolha de gravar local em vez de mandar ao servidor está por extenso no
     * KDoc de [CortesConhecidos], e o resumo é: o corte é conclusão do receptor, e
     * dois receptores da mesma transmissão podem discordar com razão.
     *
     * **Não é limpo na troca de guarnição.** `transmissaoId` é único por transmissão,
     * então id de outro canal não casa com balão nenhum deste — e limpar faria o
     * agente que volta para a guarnição anterior perder marcas ainda válidas. Quem
     * limita o crescimento é a poda por validade e teto, não o esquecimento.
     */
    private val cortesConhecidos = CortesConhecidos(
        File(getApplication<Application>().filesDir, CortesConhecidos.NOME),
    )

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
                    // **Sobrevive à recarga.** O servidor não guarda o motivo do fim
                    // — `transmissions` não tem a coluna —, então sem esta linha a
                    // marca aplicada ao vivo sumiria na volta seguinte do poll, dez
                    // segundos depois. Ver `marcarCorteDaRede`.
                    cortadaPelaRede = f.id in cortesConhecidos,
                )
            } + locaisPendentes
        }

        // O cadastro vem ANTES da lista de posições: é ele que decide se o nome de
        // quem fala pode aparecer, e chegar atrasado significaria mostrar "origem
        // não confirmada" para colega legítimo nos primeiros segundos do turno.
        //
        // **E agora ele é o denominador da guarnição, não só a tabela de autoria.**
        // Conjunto vazio é resposta legítima de `cadastro_do_grupo` para chamada
        // sem sessão e para grupo de que não somos membro — nesse caso o cadastro
        // continua "não carregado", e a tela diz isso em vez de afirmar guarnição
        // de zero pessoas. Ver `resumoDaGuarnicao`.
        historico.cadastroDoGrupo(canal).onSuccess { mapa ->
            if (mapa.isNotEmpty()) _cadastro.value = mapa
        }

        historico.posicoesDoGrupo(canal).onSuccess { lista ->
            _idadeDaPosicao.value = lista
                .filter { it.indicativo != indicativoProprio && it.idadeS != Int.MAX_VALUE }
                .associate { it.indicativo to it.idadeS }
            // A idade da posição DESTE aparelho vem na mesma resposta
            // (`idade_solicitante_s`, migração `0007`), então não custa chamada
            // nenhuma — **quando vem alguma linha**.
            //
            // **E era exatamente aí que estava o defeito, até 22/08.** A linha era
            // `lista.firstOrNull()?.idadeDoSolicitanteS`, e `posicoes_do_grupo`
            // (`0021:126`) faz `cross join minha` **e** filtra `a.id <> eu.id`: a
            // resposta só traz linhas de PARES. Sem nenhum par com posição publicada —
            // guarnição de um, colegas numa garagem, começo de turno — a lista vem
            // vazia, `_idadePropria` virava `null`, e o portador aparecia **"sem
            // posição" na própria guarnição enquanto publicava perfeitamente**.
            //
            // O KDoc que estava aqui prometia justamente o contrário. Ele descrevia o
            // que a linha *pretendia*, não o que ela fazia.
            //
            // O conserto é trocar a fonte de evidência quando a do servidor não vem:
            // `TransmissaoDePosicao` é objeto de processo alimentado pelo
            // `ColetorDePosicao` e pelo `CopilotService`, e guarda o instante do último
            // POST **aceito**. É prova local e independente de par — que é o que faltava.
            //
            // A ordem importa: o servidor tem precedência quando responde, porque ele
            // sabe o que de fato chegou; o local só entra quando não há resposta a
            // consultar. Inverter faria o aparelho confiar no próprio otimismo.
            _idadePropria.value = idadePropriaDe(
                lista = lista,
                transmissao = TransmissaoDePosicao.estado.value,
                agoraMs = SystemClock.elapsedRealtime(),
            )
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
    private suspend fun vigiarRede(transporte: TransporteAoVivo) {
        while (true) {
            val conectado = transporte.conectado()
            val atual = _estado.value
            // Não sobrescreve o estado NoAr: a transmissão em curso tem prioridade
            // sobre o relatório de conectividade, e o pré-roll cobre a queda.
            if (atual !is EstadoDoPtt.NoAr) {
                _estado.value = if (conectado) {
                    // Lido a cada volta, não capturado: a barra de PTT escreve o
                    // nome do canal, e um nome congelado na abertura continuaria
                    // dizendo a guarnição anterior depois de uma troca.
                    EstadoDoPtt.Pronto(_canal.value.nome)
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

    // ── Entrar e sair da escuta ───────────────────────────────────────────────

    /**
     * **Reentrada no canal, pelo controle do cabeçalho.**
     *
     * Esta função foi **recusada** numa sessão anterior, e a recusa está escrita no
     * KDoc de `EntradaNoCanal`: *"um botão precisaria de uma função nova no
     * ViewModel e de um parâmetro novo ligado no `MainActivity`; sem os dois, seria
     * mais uma capacidade construída, testada e sem chamador"*. Os dois existem
     * agora, e a mudança de comportamento passou por
     * `specs/guarnicao-como-grupo.spec.md` antes do diff — que é a ordem do §7.
     *
     * **O canal vem de [canal], não de um valor guardado.** Guardar o canal da
     * primeira abertura faria reentrar depois de trocar de guarnição devolver o
     * agente à anterior, em silêncio.
     *
     * O que ela **não** faz: afirmar que entramos. Ela chama [abrir] e volta; quem
     * diz se estamos no canal é [estado], alimentado pelo vigia de rede. É a mesma
     * regra do botão de PTT — a interface reporta o que aconteceu, não o que foi
     * pedido —, e é a lição dos 168 quadros.
     */
    fun entrarNaEscuta() {
        val cred = credenciais ?: run {
            // Sem credencial não houve primeira abertura, e reabrir com identidade
            // inventada é pior que não reabrir. Falha nunca é silêncio.
            Log.w(TAG, "entrarNaEscuta sem credencial de sessão — o rádio nunca foi aberto")
            _estado.value = EstadoDoPtt.Indisponivel("Sessão não iniciada. Entre de novo.")
            return
        }
        val alvo = _canal.value
        abrir(alvo.id, alvo.nome, cred.agenteId, cred.indicativo)
    }

    /**
     * Sai da escuta por vontade do agente.
     *
     * Separado de [fechar] só pela intenção que registra — e a distinção é o que
     * permite à tela diferenciar *"você saiu"* de *"o canal recusou"*. São o mesmo
     * fato (não estamos no canal) com gestos opostos: o primeiro não é erro e não
     * pode sair na cor de falha, senão o agente aprende a ignorar a cor de falha.
     */
    fun sairDaEscuta() {
        _pediuEscuta.value = false
        fechar()
    }

    /**
     * **Entra noutra guarnição, pelo mesmo caminho do comando falado.**
     *
     * `CanaisDoAgente.trocar` e não `RadioTatico.trocarDeGrupo`: uma segunda porta
     * até o socket seriam duas verdades sobre em que grupo estamos, e é exatamente
     * para não ter isso que `CanaisDoAgente` existe. De quebra, o toque herda
     * `PoliticaDeTrocaDeGrupo` inteira — inclusive a guarda que **recusa trocar
     * durante uma transmissão**, que ninguém lembraria de escrever de novo aqui.
     *
     * A função **suspende até o rádio responder** e só então devolve. Não há
     * desfecho otimista: a tela não pode desenhar a guarnição nova como corrente
     * antes disto retornar `Entrou`.
     */
    suspend fun entrarEm(rotuloFalado: String): ResultadoDaTroca =
        when (val r = CanaisDoAgente.trocar(rotuloFalado)) {
            is TrocaDeGrupo.Trocado -> {
                adotarCanalCorrente()
                ResultadoDaTroca.Entrou(r.nome)
            }

            TrocaDeGrupo.NaoReconhecido ->
                ResultadoDaTroca.Recusada(RecusaDaTroca.NAO_RECONHECIDO)

            is TrocaDeGrupo.Falhou -> ResultadoDaTroca.Recusada(
                when (r.falha) {
                    FalhaOperacional.TRANSMISSAO_EM_CURSO -> RecusaDaTroca.TRANSMITINDO
                    FalhaOperacional.SEM_LEXICO_DE_CANAIS -> RecusaDaTroca.SEM_LEXICO
                    // `RADIO_FECHADO` é a única outra falha que a política produz.
                    // O `else` existe porque `FalhaOperacional` tem trinta valores e
                    // um `when` exaustivo aqui só criaria ruído — mas colapsar tudo
                    // em "rádio fechado" seria dizer o gesto errado, então qualquer
                    // outra causa vira log antes de virar frase.
                    else -> {
                        if (r.falha != FalhaOperacional.RADIO_FECHADO) {
                            Log.w(TAG, "troca de grupo recusada por causa inesperada: ${r.falha}")
                        }
                        RecusaDaTroca.RADIO_FECHADO
                    }
                },
            )
        }

    /** Fecha o rádio e devolve a rota. Chamado ao sair da tela ou encerrar o turno. */
    fun fechar() {
        // Desregistra: um lambda apontando para um `RadioTatico` morto deixaria a
        // escuta calada para sempre, e o sintoma seria "a palavra de ativação parou
        // de funcionar depois que fechei o rádio" — dos piores de rastrear.
        CopilotService.radioNoAr = { false }
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

    private fun canalAtual(): String = _canal.value.nome.ifBlank { CanalDoPiloto.NOME }

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
     * saber que está nesse modo.
     *
     * **E "saber" virou audível em 22/08.** Até então havia log e [pisoRemoto], e
     * nenhum dos dois alcança quem está de óculos numa ocorrência. O sinal agora
     * é o cliente **declarar** o que é (`ClienteDePiso.arbitradoPeloServidor`), e o
     * `RadioTatico` falar *"Sem servidor. Piso local."* ao entrar em modo ativo —
     * uma vez por abertura, na abertura e não no toque. O log e o estado ficam:
     * eles servem ao `logcat` e à tela, que são outros públicos.
     */
    private suspend fun pisoDoCanal(agenteId: String): ClienteDePiso {
        // **`tokenValido()`, não `tokenSemEsperar()`. E aqui ESPERAR é o certo.**
        //
        // A razão escrita aqui até 22/08 era outra e envelheceu: dizia que
        // `tokenCorrente` era "cache da última credencial já validada". Ele deixou de
        // ser campo — hoje lê o cofre e confere validade. A **escolha** continua
        // certa; só o motivo mudou, e é este:
        //
        // Este ponto roda **uma vez por abertura de canal**, fora do caminho de voz.
        // O que não pode esperar é o ciclo de voz — e é por isso que
        // `CopilotoDoAgente` usa `tokenSemEsperar()`, que responde de memória e renova
        // ao fundo. Aqui, esperar a renovação é preferível a decidir errado: a decisão
        // é tomada uma vez só e vale o turno inteiro.
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

/**
 * **A idade da posição DESTE aparelho, com duas fontes e uma ordem.**
 *
 * O servidor é a fonte preferida: `idade_solicitante_s` vem em toda linha de
 * `posicoes_do_grupo` e reflete o que de fato chegou lá. Mas ele **só responde quando
 * há par com posição** — `0021:130-149` faz `cross join minha` e filtra `a.id <> eu.id`,
 * de modo que a lista vem vazia numa guarnição de um, com os colegas numa garagem, ou
 * no começo do turno.
 *
 * Até 22/08 a linha era `lista.firstOrNull()?.idadeDoSolicitanteS`, e nesse caso o
 * portador aparecia **"sem posição" na própria guarnição enquanto publicava
 * perfeitamente**. O defeito não era de cálculo: era usar evidência sobre OS OUTROS
 * para afirmar algo sobre SI.
 *
 * [transmissao] é a segunda fonte, e é local: `ultimaPublicacaoOkMs` é o instante do
 * último POST **aceito**, escrito pelo coletor. Ela não sabe o que o servidor pensa —
 * mas sabe o que este aparelho conseguiu enviar, que é exatamente o que falta quando
 * não há a quem perguntar.
 *
 * **A ordem não é arbitrária.** O servidor tem precedência quando responde; o local só
 * entra quando não há resposta. Inverter faria o aparelho confiar no próprio otimismo:
 * um POST aceito não prova que a linha sobreviveu à retenção do outro lado.
 *
 * Devolve `null` quando nenhuma das duas sabe — e aí "sem posição" é verdade.
 */
internal fun idadePropriaDe(
    lista: List<RespostaDePosicao>,
    transmissao: EstadoDaTransmissao,
    agoraMs: Long,
): Int? {
    val doServidor = lista.firstOrNull()?.idadeDoSolicitanteS?.takeIf { it != Int.MAX_VALUE }
    if (doServidor != null) return doServidor

    val ok = transmissao.ultimaPublicacaoOkMs ?: return null
    // `elapsedRealtime` não anda para trás, mas relógio de teste e reinício de processo
    // podem produzir diferença negativa. Idade negativa não existe: vira zero.
    return ((agoraMs - ok).coerceAtLeast(0L) / 1_000L).toInt()
}
