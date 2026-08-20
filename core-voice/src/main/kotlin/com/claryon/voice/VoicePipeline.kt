package com.claryon.voice

import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow

/**
 * Contratos do pipeline de voz. A cascata é barata → cara:
 *   wake word (sempre ligada) → VAD → STT (caro, quase nunca).
 * Cada estágio só acorda o seguinte, poupando bateria e evitando `while(true)`.
 */

/** Transcrição produzida pelo STT. `confidence` em [0,1] quando disponível. */
data class Transcript(val text: String, val confidence: Float?)

/** Áudio PCM sintetizado pelo TTS, pronto para o AudioTrack. */
data class PcmAudio(val samples: ShortArray, val sampleRateHz: Int) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PcmAudio &&
            sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRateHz
}

/** Evento de wake word: a palavra "Claryon" foi detectada. */
// `WakeEvent` saiu junto: era o tipo de retorno da interface removida acima e não
// tinha nenhum outro uso. O escore da detecção viaja hoje em
// `CopilotService.ativacoes`, que é um `SharedFlow<Float>` de processo.

/**
 * Segmento de fala delimitado pelo VAD (janela fechada ⇒ pronto para o STT).
 *
 * @property silencioFinalMs quanto do fim de [pcm] é o **hangover** — o silêncio
 *   que o detector esperou antes de decidir que a fala acabou.
 *
 *   Existe porque sem ele a meta "fim da fala → earcon ≤ 500 ms" mede a partir do
 *   lugar errado. O consumidor só sabe do segmento quando a janela **fecha**, e a
 *   janela fecha um hangover inteiro (600 ms, no detector por energia) depois de o
 *   agente ter parado de falar. Cravar o zero no fechamento produz um número
 *   otimista em ~600 ms — e nenhum teste acusa, porque o relatório fica
 *   internamente coerente.
 *
 *   Com este campo o consumidor **desconta**: o instante do fim real da fala é
 *   `fechamento − silencioFinalMs`. É a diferença entre medir o produto e medir o
 *   próprio instrumento.
 */
data class SpeechSegment(
    val pcm: ShortArray,
    val sampleRateHz: Int,
    val silencioFinalMs: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SpeechSegment &&
            sampleRateHz == other.sampleRateHz &&
            silencioFinalMs == other.silencioFinalMs &&
            pcm.contentEquals(other.pcm))

    override fun hashCode(): Int =
        31 * (31 * pcm.contentHashCode() + sampleRateHz) + silencioFinalMs
}

/**
 * Motor de reconhecimento de fala (STT).
 * Implementações: WhisperCppStt (primária) | AndroidOnDeviceStt (fallback).
 * Whisper processa em LOTE: fechar a janela (VAD) e só então transcrever.
 */
interface SttEngine {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript>
}

/**
 * Motor de síntese de fala (TTS).
 * Implementações: PiperTts (primária, pt-BR) | AndroidTts (fallback).
 */
interface TtsEngine {
    suspend fun isAvailable(): Boolean
    suspend fun synthesize(text: String): Result<PcmAudio>
}

// A interface `WakeWordDetector` morava aqui, com zero implementações e zero
// chamadores, desde a primeira versão deste arquivo. Removida em 20/08, quando a
// palavra de ativação passou a existir de verdade.
//
// **Não foi implementada — foi apagada**, e a diferença importa. Uma abstração sem
// implementação afirma que existe um ponto de troca: que dá para pôr outro detector
// no lugar. Não dava, porque não havia nenhum. E ela custou caro além do espaço: o
// relatório de telemetria imprimia "wake word: sem produtor (WakeWordDetector é
// interface sem implementação)" e essa frase virou mentira no dia em que
// `EscutaDeAtivacao` entrou, sem ninguém mexer nela.
//
// Quem faz o trabalho hoje é `com.claryon.field.voice.EscutaDeAtivacao`, e a costura
// para teste é `OuvidoDeAtivacao` — no módulo do app, onde o detector é usado, e não
// aqui, onde ele era só prometido.

/** Detector de atividade de voz (Silero VAD): delimita e fecha a janela de fala. */
interface VoiceActivityDetector {
    fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment>
}
