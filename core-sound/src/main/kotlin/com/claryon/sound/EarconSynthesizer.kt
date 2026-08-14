package com.claryon.sound

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sintetiza cada [Earcon] em PCM 16-bit mono — tons curtos (150–250 ms) com
 * significado fixo. Puro Kotlin (síntese de senóide), testável em JVM.
 *
 * Num sistema sem display, o earcon é mais rápido, mais discreto e
 * cognitivamente mais barato que uma frase. Resultado de consulta sensível sai
 * SÓ como earcon codificado (nunca falado — o alto-falante open-ear vaza som).
 */
object EarconSynthesizer {

    const val SAMPLE_RATE_HZ = 16_000

    fun render(earcon: Earcon): ShortArray = when (earcon) {
        Earcon.OUVI_VOCE -> sweep(600.0, 1000.0, 180)              // bipe curto ascendente
        Earcon.ACAO_EXECUTADA -> beeps(880.0, 2, 90, 60)          // duplo bipe curto
        Earcon.FALHA -> sweep(520.0, 300.0, 220)                  // bipe grave descendente
        Earcon.GRAVANDO -> tone(500.0, 2_000)                     // tom contínuo 2 s
        Earcon.PRIORITARIA -> beeps(1_200.0, 3, 70, 50)           // três bipes rápidos
        Earcon.CONSULTA_SEM_RESTRICAO -> tone(760.0, 150)         // 1 bipe curto e neutro
        Earcon.CONSULTA_RESTRICAO_ADMIN -> beeps(680.0, 2, 130, 90) // 2 bipes médios
        Earcon.CONSULTA_FURTO_ROUBO -> arpejo(doubleArrayOf(500.0, 700.0, 950.0), 120) // 3 tons distintos
    }

    private fun tone(freqHz: Double, durationMs: Int): ShortArray {
        val n = SAMPLE_RATE_HZ * durationMs / 1_000
        val out = ShortArray(n)
        val fade = (SAMPLE_RATE_HZ * 5 / 1_000).coerceAtMost(n / 2) // 5 ms de fade in/out
        for (i in 0 until n) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val s = sin(2.0 * PI * freqHz * i / SAMPLE_RATE_HZ) * env * 0.6
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun beeps(freqHz: Double, count: Int, msEach: Int, gapMs: Int): ShortArray {
        val beep = tone(freqHz, msEach)
        val gap = ShortArray(SAMPLE_RATE_HZ * gapMs / 1_000)
        val out = ArrayList<Short>()
        repeat(count) { i ->
            beep.forEach(out::add)
            if (i < count - 1) gap.forEach(out::add)
        }
        return out.toShortArray()
    }

    private fun sweep(fromHz: Double, toHz: Double, durationMs: Int): ShortArray {
        val n = SAMPLE_RATE_HZ * durationMs / 1_000
        val out = ShortArray(n)
        var phase = 0.0
        val fade = (SAMPLE_RATE_HZ * 5 / 1_000).coerceAtMost(n / 2)
        for (i in 0 until n) {
            val f = fromHz + (toHz - fromHz) * (i.toDouble() / n)
            phase += 2.0 * PI * f / SAMPLE_RATE_HZ
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            out[i] = (sin(phase) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun arpejo(freqs: DoubleArray, msEach: Int): ShortArray {
        val out = ArrayList<Short>()
        freqs.forEach { f -> tone(f, msEach).forEach(out::add) }
        return out.toShortArray()
    }
}
