package com.claryon.field.radio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.field.audio.AudioDoAgente
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.voice.VoiceOutput
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.ParPresente
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.CodecDeVoz
import com.claryon.net.HistoricoDoCanal
import com.claryon.net.ConfigRealtime
import com.claryon.net.MediaCodecOpus
import com.claryon.net.TransporteAoVivo
import com.claryon.net.TransporteRealtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * Saída de som do rádio.
     *
     * Até aqui, `RadioTatico` recebia `emitir = { }` — uma lambda vazia que
     * engolia **todos** os sinais do rádio: canal ocupado, canal perdido em
     * emergência, limite de duração, alerta prioritário recebido e sem rede. Num
     * produto sem display, isso é a regra dura "falha nunca é silêncio" sendo
     * falsa enquanto a suíte fica verde. Pior que mudo: `emitirComSupressao`
     * **registra** a janela de supressão antes de emitir, então o rádio desligava
     * o microfone por 320 ms para se proteger do eco de um tom que nunca tocava.
     *
     * A justificativa antiga — "o ciclo de voz já toca" — era falsa em dois
     * níveis: o ciclo de voz não tem porta de entrada no app entregue, e mesmo
     * que tivesse, ele não sabe do rádio.
     *
     * **Limitação conhecida, e ela é deliberada.** Esta é uma segunda fila de
     * prioridade, separada da do ciclo de voz. Um alerta P1 do rádio **não**
     * interrompe uma resposta falada do copiloto, porque as duas filas não se
     * enxergam. O que tornou isso aceitável agora foi o dono único da rota
     * (`AudioDoAgente`): as duas filas disputam ordem, não mais o estado global de
     * áudio do aparelho — que era o defeito que difundia terceiros. Arbitragem de
     * prioridade entre subsistemas fica registrada em `ESTADO.md` como pendência,
     * e não se resolve aqui sem mover o TTS para o dono, que é mudança maior.
     */
    private val saidaDoRadio = VoiceOutput(
        scope = viewModelScope,
        // O rádio não fala frases próprias: dos cinco sinais, quatro são tons e o
        // quinto reusa `utteranceFor(SEM_REDE)`. Devolver `null` faz a fila tratar
        // como não-sintetizável e cair no earcon, em vez de carregar o Piper de
        // 60 MB para um caminho que só emite bipe.
        sintetizar = { null },
        reproduzir = { pcm, sr -> audio.reproduzir(pcm, sr) },
    )

    // Dono único do processo. Duas instâncias sobre o mesmo estado global do
    // aparelho produziam captação pelo microfone do celular — ver `AudioDoAgente`.
    private val audio = AudioDoAgente.de(app)

    private val _estado = MutableStateFlow<EstadoDoPtt>(
        EstadoDoPtt.Indisponivel("Rádio fechado."),
    )
    val estado: StateFlow<EstadoDoPtt> = _estado.asStateFlow()

    private val _falas = MutableStateFlow<List<FalaNoGrupo>>(emptyList())
    val falas: StateFlow<List<FalaNoGrupo>> = _falas.asStateFlow()

    private val _pares = MutableStateFlow<List<ParPresente>>(emptyList())
    val pares: StateFlow<List<ParPresente>> = _pares.asStateFlow()

    /** `true` enquanto transmitimos. É o que acende a moldura da tela inteira. */
    val noAr: StateFlow<Boolean> get() = _noAr
    private val _noAr = MutableStateFlow(false)

    private var radio: RadioTatico? = null
    private var rota: GlassesAudioRoute? = null
    private var cronometro: Job? = null
    private var vigiaDeRede: Job? = null
    private var transporteAtual: TransporteAoVivo? = null
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
                config = ConfigRealtime(
                    projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                    apiKey = BuildConfig.SUPABASE_ANON_KEY,
                ),
                escopo = viewModelScope,
            )

            val novo = RadioTatico(
                escopo = viewModelScope,
                talkGroupId = canal,
                agenteId = agenteId,
                indicativo = indicativo,
                transporte = transporte,
                codec = codec(),
                piso = ClienteDePisoLocal(),
                pcmDoMicrofone = { rotaValida -> audio.microfonePcm(rotaValida) },
                reproduzir = { pcm, taxa -> audio.reproduzir(pcm, taxa) },
                // Os earcons do rádio saem do mudo. Ver `saidaDoRadio`.
                emitir = { u -> saidaDoRadio.emitir(u) },
                duracaoDoEarconMs = { e -> duracaoDoEarcon(e) },
            )
            novo.entrarEmModoAtivo(r)
            radio = novo
            transporteAtual = transporte
            indicativoProprio = indicativo
            nomeDoCanalAtual = nomeDoCanal
            vigiaDeRede = viewModelScope.launch { vigiarRede(nomeDoCanal, transporte) }

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
    private suspend fun carregarCanal(canal: String, historico: HistoricoDoCanal) {
        historico.falas(canal).onSuccess { lista ->
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
            }
        }

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
                        falando = false,
                    )
                }
        }
    }

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
                    EstadoDoPtt.Indisponivel("Sem dados. O canal depende da rede.")
                }
            }
            delay(INTERVALO_DA_VIGIA_MS)
        }
    }

    private var historicoDoCanal: HistoricoDoCanal? = null
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
        // Não presume `Pronto`: a rede pode ter caído durante a fala, e afirmar
        // prontidão logo depois de transmitir é o pior instante para errar.
        _estado.value = if (transporteAtual?.conectado() == true) {
            EstadoDoPtt.Pronto(canalAtual())
        } else {
            EstadoDoPtt.Indisponivel("Sem dados. O canal depende da rede.")
        }
    }

    private fun canalAtual(): String =
        nomeDoCanalAtual.ifBlank { TALK_GROUP_PADRAO }

    // ── Peças auxiliares ──────────────────────────────────────────────────────

    private fun codec(): CodecDeVoz = MediaCodecOpus()

    private fun duracaoDoEarcon(earcon: Earcon): Long = when (earcon) {
        Earcon.GRAVANDO -> 2_000L
        else -> 320L
    }

    override fun onCleared() {
        fechar()
        super.onCleared()
    }

    private companion object {
        const val TALK_GROUP_PADRAO = "demo"

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
