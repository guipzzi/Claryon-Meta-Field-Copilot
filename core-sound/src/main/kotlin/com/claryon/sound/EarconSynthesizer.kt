package com.claryon.sound

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sintetiza cada [Earcon] em PCM 16-bit mono — tons curtos (150–250 ms) com
 * significado fixo. Puro Kotlin (síntese de senóide), testável em JVM.
 *
 * Num sistema sem display, o earcon é mais rápido, mais discreto e
 * cognitivamente mais barato que uma frase.
 *
 * **Resultado de consulta sensível sai como earcon + fala curta** — decisão humana
 * de 21/08; o KDoc anterior dizia "nunca falado". O earcon continua obrigatório e
 * distinto por restrição: ele chega em 139 ms, e a fala de uma placa custa segundos
 * porque o Piper expande número por extenso. Preempção de P1 apaga a fala, não o som.
 */
object EarconSynthesizer {

    const val SAMPLE_RATE_HZ = 16_000

    /**
     * **Cada earcon é sintetizado UMA vez por processo.**
     *
     * Medido no aparelho em 21/08: [render] estava sendo chamado a cada reprodução,
     * na Main, e o custo por earcon ia de 220 µs (`CONSULTA_SEM_RESTRICAO`) a
     * 3494 µs (`CONSULTA_FURTO_ROUBO`), somando **14,5 ms** para os oito.
     *
     * São **tons estáticos**: a mesma senóide, com a mesma frequência e a mesma
     * duração, recalculada amostra por amostra toda vez que o agente ouve um bipe.
     * `OUVI_VOCE` é o do ciclo de voz — o som mais frequente do produto — e o
     * `GRAVANDO` são 32 000 amostras de tom contínuo.
     *
     * O custo total é modesto, e é justamente por isso que passou despercebido: 3 ms
     * na Main não fazem ANR, só entram na conta de tudo o mais que disputa aquela
     * thread. Guardar é trivial e o cache é seguro por construção — o mapa é
     * preenchido na primeira leitura de cada chave e o `ShortArray` nunca é mutado
     * depois, porque quem consome copia para o `AudioTrack`.
     *
     * **Memória:** os oito somam ~61 000 amostras = **122 KB**. Uma vez, para
     * sempre, contra 14,5 ms de CPU por rodada de reprodução.
     */
    private val cache = HashMap<Earcon, ShortArray>(Earcon.entries.size)

    /**
     * O PCM do [earcon], sintetizado na primeira chamada e guardado depois.
     *
     * `@Synchronized` porque a fila de saída e o ciclo de voz podem pedir earcons de
     * threads diferentes, e duas sínteses simultâneas da mesma chave desperdiçariam
     * exatamente o trabalho que este cache existe para eliminar.
     */
    @Synchronized
    fun render(earcon: Earcon): ShortArray = cache.getOrPut(earcon) { sintetizar(earcon) }

    private fun sintetizar(earcon: Earcon): ShortArray = when (earcon) {
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
