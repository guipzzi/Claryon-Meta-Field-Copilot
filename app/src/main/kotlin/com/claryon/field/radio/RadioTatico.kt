package com.claryon.field.radio

import android.util.Log
import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Result
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
    private val transporte: TransporteAoVivo,
    private val codec: CodecDeVoz,
    private val piso: ClienteDePiso,
    private val pcmDoMicrofone: (GlassesAudioRoute) -> Flow<ShortArray>,
    private val reproduzir: suspend (pcm: ShortArray, taxaHz: Int) -> Unit,
    private val emitir: (Utterance) -> Unit,
    private val duracaoDoEarconMs: (Earcon) -> Long,
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
    private val sampleRateHz: Int = 8_000,
) {

    private val supressor = SupressorDeSaidaPropria()
    private val gatilho = GatilhoPtt()
    private val preRoll = PreRollBuffer(sampleRateHz = sampleRateHz)

    private val receptor = Receptor(transporte, codec, escopo)

    private var alimentacao: Job? = null
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
        escopo.launch { transporte.conectar(talkGroupId) }

        receptor.iniciar { evento -> tratarRecepcao(evento) }

        alimentacao?.cancel()
        alimentacao = escopo.launch {
            pcmDoMicrofone(rota)
                // Descarta o que foi capturado enquanto NÓS emitíamos som.
                .filter { !supressor.suprimido(agoraMs()) }
                .onEach { quadro -> medirAmplitude(quadro) }
                .onEach { supressor.podarAntesDe(agoraMs()) }
                .collect { bloco -> preRoll.escrever(bloco) }
        }
    }

    fun sairDeModoAtivo() {
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
     * Chamado ao **pressionar** o gatilho (long-press do botão de volume — o
     * toque na haste foi descartado por medição: ele pausa a sessão).
     */
    fun aoPressionar(rota: GlassesAudioRoute, prioridade: PrioridadeTransmissao = PrioridadeTransmissao.P2_APOIO) {
        when (val d = gatilho.aoPressionar(agoraMs())) {
            DecisaoDeGatilho.IgnoradoPorRepique -> {
                Log.d(TAG, "toque ignorado por repique")
                return
            }
            DecisaoDeGatilho.JaTransmitindo -> return
            DecisaoDeGatilho.Iniciar -> Unit
        }

        val transmissaoId = UUID.randomUUID().toString()
        val sessao = SessaoPtt(
            talkGroupId = talkGroupId,
            agenteId = agenteId,
            preRoll = preRoll,
            codec = codec,
            transporte = transporte,
            piso = piso,
            agoraMs = agoraMs,
            amostrasPorQuadro = sampleRateHz / 50,
        )

        transmissao = escopo.launch {
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

            is EventoPtt.Encerrada ->
                Log.i(TAG, "transmissão ${evento.transmissaoId}: ${evento.quadros} quadros em ${evento.duracaoMs} ms")
        }
    }

    private fun tratarRecepcao(evento: EventoRecepcao) {
        when (evento) {
            is EventoRecepcao.Chegando -> {
                // Abre a janela de supressão ANTES do primeiro áudio: a voz que
                // vai tocar não pode entrar na nossa resposta.
                supressor.abrir(agoraMs())
                if (evento.anuncio.prioridade == PrioridadeTransmissao.P1_EMERGENCIA) {
                    sinalizar(Earcon.PRIORITARIA, Priority.EMERGENCIA)
                }
            }

            is EventoRecepcao.Audio -> escopo.launch { reproduzir(evento.pcm, evento.taxaHz) }

            is EventoRecepcao.Terminou -> {
                // Fecha a janela; a margem do supressor cobre a cauda.
                supressor.fechar(agoraMs())
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
        const val TAG = "ClaryonField"

        /**
         * Estimativa para a janela de supressão da fala sintetizada. Grosseira de
         * propósito: errar para mais só descarta um pouco mais de captura; errar
         * para menos deixa a própria fala vazar para a guarnição.
         */
        const val DURACAO_FALA_ESTIMADA_MS = 2_000L
    }
}
