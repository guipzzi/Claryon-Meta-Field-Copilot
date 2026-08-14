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
/**
 * @param maxSpeechMs teto de duração da janela. **Não é opcional:** sem ele, um
 *   ruído sustentado acima do limiar (sirene, motor, vento) mantém `voiced`
 *   sempre verdadeiro, a janela nunca fecha, o acumulador cresce até estourar a
 *   memória e o copiloto fica mudo — justamente no ambiente para o qual foi
 *   feito. Com o teto, a janela fecha à força e o STT decide o que era. 12 s
 *   também casa com o regime de lote do Whisper.
 */
class EnergyVoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val energyThreshold: Double = 500.0,
    private val hangoverMs: Int = 600,
    private val minSpeechMs: Int = 200,
    private val maxSpeechMs: Int = 12_000,
) : VoiceActivityDetector {

    override fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment> = flow {
        val hangoverSamples = sampleRateHz * hangoverMs / 1000
        val minSpeechSamples = sampleRateHz * minSpeechMs / 1000
        val maxSamples = sampleRateHz * maxSpeechMs / 1000

        // Buffer primitivo: `ArrayList<Short>` boxeava 16 000 objetos por segundo
        // de captura — pressão de GC no caminho crítico da latência.
        val acc = PcmBuffer(sampleRateHz) // ~1 s inicial
        var inSpeech = false
        var trailingSilence = 0

        suspend fun fechar() {
            val speechSamples = acc.size - trailingSilence
            if (speechSamples >= minSpeechSamples) {
                emit(SpeechSegment(acc.toShortArray(), sampleRateHz))
            }
            acc.clear()
            inSpeech = false
            trailingSilence = 0
        }

        pcm.collect { frame ->
            val voiced = rms(frame) >= energyThreshold
            when {
                voiced -> {
                    inSpeech = true
                    trailingSilence = 0
                    acc.append(frame)
                }
                inSpeech -> {
                    acc.append(frame)
                    trailingSilence += frame.size
                }
            }
            // Fecha por silêncio (hangover) OU por teto de duração.
            if (inSpeech && (trailingSilence >= hangoverSamples || acc.size >= maxSamples)) {
                fechar()
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

/** Buffer de PCM que cresce sem boxing (o caminho é quente: 50 frames/s). */
private class PcmBuffer(capacidadeInicial: Int) {
    private var dados = ShortArray(capacidadeInicial)
    var size: Int = 0
        private set

    fun append(frame: ShortArray) {
        if (size + frame.size > dados.size) {
            var nova = maxOf(dados.size * 2, size + frame.size)
            dados = dados.copyOf(nova)
        }
        frame.copyInto(dados, size)
        size += frame.size
    }

    fun clear() {
        size = 0
    }

    fun toShortArray(): ShortArray = dados.copyOf(size)
}
