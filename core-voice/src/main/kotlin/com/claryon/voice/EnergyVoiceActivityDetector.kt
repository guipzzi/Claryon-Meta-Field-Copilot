package com.claryon.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sqrt

/**
 * VAD por energia (RMS) — **primeira versão** do detector de atividade de voz,
 * pura em Kotlin e testável em JUnit local. Delimita a fala e **fecha a janela**
 * após um período de silêncio (hangover); é nesse fechamento que o earcon "ouvi
 * você" dispara e o STT (em lote) é chamado.
 *
 * Upgrade planejado: **Silero VAD** (neural, poucos MB) — mais robusto a ruído
 * de rua. A interface [VoiceActivityDetector] permite trocar sem reescrita.
 */
class EnergyVoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val energyThreshold: Double = 500.0,
    private val hangoverMs: Int = 600,
    private val minSpeechMs: Int = 200,
) : VoiceActivityDetector {

    override fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment> = flow {
        val hangoverSamples = sampleRateHz * hangoverMs / 1000
        val minSpeechSamples = sampleRateHz * minSpeechMs / 1000

        val acc = ArrayList<Short>()
        var inSpeech = false
        var trailingSilence = 0

        pcm.collect { frame ->
            val voiced = rms(frame) >= energyThreshold
            when {
                voiced -> {
                    inSpeech = true
                    trailingSilence = 0
                    frame.forEach(acc::add)
                }
                inSpeech -> {
                    frame.forEach(acc::add)
                    trailingSilence += frame.size
                    if (trailingSilence >= hangoverSamples) {
                        val speechSamples = acc.size - trailingSilence
                        if (speechSamples >= minSpeechSamples) {
                            emit(SpeechSegment(acc.toShortArray(), sampleRateHz))
                        }
                        acc.clear()
                        inSpeech = false
                        trailingSilence = 0
                    }
                }
            }
        }

        // Fluxo terminou com fala aberta → emite o que houver.
        if (inSpeech && acc.size - trailingSilence >= minSpeechSamples) {
            emit(SpeechSegment(acc.toShortArray(), sampleRateHz))
        }
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size)
    }
}
