package com.claryon.field.voice

import com.claryon.agent.Utterance
import com.claryon.common.PcmResampler
import com.claryon.sound.EarconSynthesizer
import com.claryon.sound.PrioritySoundQueue
import com.claryon.sound.Sound
import com.claryon.voice.PcmAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * **Única porta de saída do produto.** Recebe [Utterance] — que só existe a
 * partir de um resultado de ação — e a entrega à fila de prioridade.
 *
 * Fecha a ligação que faltava: `core-sound` estava pronto e testado desde o M5,
 * mas nenhum código de produção o importava. Consequência prática de ligar:
 * emergência passa a interromper o que estiver tocando, informativo é suprimido
 * em Modo Tático, e **todo caminho de falha tem earcon** — num sistema sem
 * display, silêncio é indistinguível de aplicativo morto.
 *
 * Tudo é reproduzido a [TAXA_SAIDA_HZ]: os earcons já são sintetizados nessa
 * taxa e a fala do TTS é reamostrada aqui, de modo que a fila precise conhecer
 * apenas amostras — não taxas diferentes por item.
 *
 * @param sintetizar TTS. `null` = falhou; a fila pula o item em vez de travar.
 * @param reproduzir saída de áudio (AudioTrack pela rota HFP).
 */
class VoiceOutput(
    scope: CoroutineScope,
    private val sintetizar: suspend (String) -> PcmAudio?,
    private val reproduzir: suspend (pcm: ShortArray, sampleRateHz: Int) -> Unit,
    /**
     * Onde o filtro de reamostragem roda. Padrão fora da Main.
     *
     * É parâmetro e não `Dispatchers.Default` fixo porque um dispatcher fixo
     * escapa do escalonador de teste: `withContext(Dispatchers.Default)` salta
     * para um pool real, e o teste que dirige a fila com `UnconfinedTestDispatcher`
     * nunca vê a corrotina terminar. Descobri isso quebrando dois testes com a
     * primeira versão desta correção.
     */
    private val dispatcherDeAudio: CoroutineContext = Dispatchers.Default,
    /**
     * Chamado no instante em que o PCM **começa a ser reproduzido** — não no
     * `emitir`, que só enfileira.
     *
     * É a diferença entre medir latência e medir intenção: a fila pode estar
     * ocupada com uma emergência, e o earcon de "ouvi você" sair segundos depois
     * de ter sido pedido. Um marco no enfileiramento afirmaria "400 ms" com o
     * agente esperando três segundos em silêncio.
     */
    private val aoIniciarReproducao: (Sound) -> Unit = {},
    /** Repassado à fila: mede o aceite "P1 corta em ≤ 200 ms". */
    private val aoInterromperPorEmergencia: (atrasoMs: Long) -> Unit = {},
) {

    init {
        // [TAXA_SAIDA_HZ] é o barramento inteiro, e os earcons NÃO passam por
        // [naTaxaDeSaida] — nascem já nessa taxa (ramo `Sound.Tone` abaixo).
        // Mexer numa constante sem a outra não daria erro de compilação: os oito
        // earcons simplesmente tocariam acelerados e desafinados (a 22.050 Hz,
        // PRIORITARIA sairia em ~1.654 Hz no lugar de 1.200), o que num produto
        // sem display corrompe o vocabulário inteiro de sinais. Falhar na
        // construção é infinitamente melhor que descobrir isso em campo.
        require(TAXA_SAIDA_HZ == EarconSynthesizer.SAMPLE_RATE_HZ) {
            "barramento a $TAXA_SAIDA_HZ Hz e earcons a ${EarconSynthesizer.SAMPLE_RATE_HZ} Hz"
        }
    }

    private val fila = PrioritySoundQueue(
        scope = scope,
        render = { sound ->
            when (sound) {
                is Sound.Tone -> EarconSynthesizer.render(sound.earcon)
                is Sound.Speech -> sintetizar(sound.text)?.let { naTaxaDeSaida(it) }
            }
        },
        play = { sound, pcm ->
            // ANTES de escrever no track: é este o instante que as metas de
            // latência do ciclo de voz medem.
            aoIniciarReproducao(sound)
            reproduzir(pcm, TAXA_SAIDA_HZ)
        },
        aoInterromper = aoInterromperPorEmergencia,
    )

    /** Enfileira o que o agente deve ouvir. Não bloqueia: a fila conduz a ordem. */
    fun emitir(utterance: Utterance) {
        when (utterance) {
            is Utterance.Falar ->
                fila.enqueue(Sound.Speech(utterance.texto, utterance.priority))

            is Utterance.Sinalizar ->
                fila.enqueue(Sound.Tone(utterance.earcon, utterance.priority))

            is Utterance.SinalizarEFalar -> {
                // Earcon primeiro: o sinal chega ao ouvido antes da causa, que é
                // o que permite reagir sem esperar a frase terminar.
                fila.enqueue(Sound.Tone(utterance.earcon, utterance.priority))
                fila.enqueue(Sound.Speech(utterance.texto, utterance.priority))
            }
        }
    }

    /** Modo Tático: suprime o nível 3 (informativo) inteiramente. */
    fun modoTatico(ativo: Boolean) = fila.setTacticalMode(ativo)

    fun limpar() = fila.clear()

    /**
     * O Piper sintetiza a 22.050 Hz e o barramento é 16.000: isto **desce**, e
     * descer sem anti-aliasing dobra 8–11 kHz para dentro da banda de voz. Por
     * isso [PcmResampler.resample] e não `resampleLinear` — a diferença é
     * inaudível num teste de tamanho de array e escancarada no alto-falante.
     */
    private suspend fun naTaxaDeSaida(audio: PcmAudio): ShortArray =
        if (audio.sampleRateHz == TAXA_SAIDA_HZ) {
            audio.samples
        } else {
            // **Fora da Main, e não é preciosismo.** O `render` da fila roda no
            // escopo que ela recebe, e o único construtor que sintetiza fala passa
            // `viewModelScope` — cujo dispatcher é a Main. Um FIR de 63 tapes
            // sobre uma frase inteira ali travaria o quadro exatamente quando o
            // agente espera resposta. `WhisperCppStt` já aprendeu isso e faz o
            // mesmo, pelo mesmo motivo.
            withContext(dispatcherDeAudio) {
                PcmResampler.resample(audio.samples, audio.sampleRateHz, TAXA_SAIDA_HZ)
            }
        }

    companion object {
        /**
         * Mesma taxa dos earcons — ver [EarconSynthesizer]; o `init` acima trava
         * o par.
         *
         * Não sobe para 22.050 (taxa nativa do Piper) por decisão: o elo até os
         * óculos é HFP 8 kHz mono por doc oficial do DAT, então subir aqui não
         * eliminaria reamostragem — só a transferiria para o resampler do
         * AudioFlinger, cujo filtro **não auditamos**, e obrigaria a fila a
         * carregar taxa por item (`PrioritySoundQueue.play` recebe só amostras).
         */
        const val TAXA_SAIDA_HZ = 16_000
    }
}
