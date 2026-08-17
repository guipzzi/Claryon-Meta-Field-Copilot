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
data class WakeEvent(val timestampMillis: Long, val score: Float)

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

/**
 * Detector de wake word — modelo minúsculo, sempre ligado (openWakeWord).
 * É o único componente que roda continuamente; por isso precisa ser barato.
 */
interface WakeWordDetector {
    fun detect(pcm: Flow<ShortArray>): Flow<WakeEvent>
}

/** Detector de atividade de voz (Silero VAD): delimita e fecha a janela de fala. */
interface VoiceActivityDetector {
    fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment>
}
