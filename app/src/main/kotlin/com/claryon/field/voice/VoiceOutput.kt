package com.claryon.field.voice

import com.claryon.agent.Utterance
import com.claryon.common.PcmResampler
import com.claryon.sound.EarconSynthesizer
import com.claryon.sound.PrioritySoundQueue
import com.claryon.sound.Sound
import com.claryon.voice.PcmAudio
import kotlinx.coroutines.CoroutineScope

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
) {

    private val fila = PrioritySoundQueue(
        scope = scope,
        render = { sound ->
            when (sound) {
                is Sound.Tone -> EarconSynthesizer.render(sound.earcon)
                is Sound.Speech -> sintetizar(sound.text)?.let { naTaxaDeSaida(it) }
            }
        },
        play = { pcm -> reproduzir(pcm, TAXA_SAIDA_HZ) },
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

    private fun naTaxaDeSaida(audio: PcmAudio): ShortArray =
        if (audio.sampleRateHz == TAXA_SAIDA_HZ) {
            audio.samples
        } else {
            PcmResampler.resampleLinear(audio.samples, audio.sampleRateHz, TAXA_SAIDA_HZ)
        }

    companion object {
        /** Mesma taxa dos earcons — ver [EarconSynthesizer]. */
        const val TAXA_SAIDA_HZ = 16_000
    }
}
