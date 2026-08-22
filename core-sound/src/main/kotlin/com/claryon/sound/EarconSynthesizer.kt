package com.claryon.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sintetiza cada [Earcon] em PCM 16-bit mono. Puro Kotlin (síntese aditiva),
 * testável em JVM.
 *
 * Num sistema sem display, o earcon é mais rápido, mais discreto e
 * cognitivamente mais barato que uma frase.
 *
 * **Resultado de consulta sensível sai como earcon + fala curta** — decisão humana
 * de 21/08; o KDoc anterior dizia "nunca falado". O earcon continua obrigatório e
 * distinto por restrição: ele chega em 139 ms, e a fala de uma placa custa segundos
 * porque o Piper expande número por extenso. Preempção de P1 apaga a fala, não o som.
 *
 * ## A janela de 400 a 3400 Hz, e o que ela proíbe
 *
 * O barramento é 16 kHz ([SAMPLE_RATE_HZ], travado contra `VoiceOutput.TAXA_SAIDA_HZ`),
 * mas o elo até os óculos é **HFP/SCO de banda estreita**. Duas consequências, e as
 * duas são de projeto e não de gosto:
 *
 *  - **Teto de ~3,4 kHz.** Parcial acima disso não chega ao ouvido do agente; ela
 *    só existe no gráfico. O parcial mais agudo do vocabulário é o terceiro do
 *    [Earcon.DESPERTAR], a 2517 Hz.
 *  - **Piso de ~400 Hz.** Ruído de viatura (motor, rolamento, ventilação) é grave:
 *    o que ficar embaixo disso é mascarado justamente quando o agente mais precisa
 *    ouvir. Foi por isso que o `FALHA` deixou de varrer até 300 Hz — ele era o
 *    earcon de erro, e metade da energia dele morava debaixo do motor (54 % abaixo
 *    de 400 Hz, medido).
 *
 * ## Como os onze se separam
 *
 * Sob banda estreita e ruído, a pista robusta é **morfologia** (quantos elementos,
 * separados por quanto silêncio) e **contorno** (sobe / desce / plano). Altura
 * absoluta é a primeira a cair. Por isso cada earcon tem uma assinatura
 * `(nº de elementos, contornos, ataque)` **única**, e por isso ela é *calculada do
 * PCM* pelo teste, não declarada aqui — declaração envelhece, medição não.
 *
 * | earcon | elementos | contorno | ataque |
 * |---|---|---|---|
 * | `DESPERTAR` | 1 | desce | **golpe** |
 * | `FALHA` | 1 | desce | sustentado |
 * | `CONSULTA_SEM_RESTRICAO` | 1 | plano | sustentado |
 * | `CANAL_ABERTO` | 2 | sobe, sobe | sustentado |
 * | `CANAL_FECHADO` | 2 | desce, desce | sustentado |
 * | `ACAO_EXECUTADA` | 2 | plano, plano | sustentado |
 * | `CONSULTA_RESTRICAO_ADMIN` | 2 | plano, desce | sustentado |
 * | `PRIORITARIA` | 3 | plano ×3 | sustentado |
 * | `CONSULTA_FURTO_ROUBO` | 4 | plano ×4 | sustentado |
 * | `GRAVANDO` | 8 | plano ×8 | sustentado |
 *
 * **Todo intervalo entre elementos é ≥ 40 ms** de propósito: abaixo disso o
 * detector de elementos do teste (quadros de 10 ms, dois apagados para separar)
 * emenda dois elementos num só, e a assinatura passaria a depender de arredondamento
 * em vez de desenho.
 *
 * @see DistinguibilidadeDosEarconsTest para as réguas e os números medidos.
 */
object EarconSynthesizer {

    const val SAMPLE_RATE_HZ = 16_000

    /**
     * **Cada earcon é sintetizado UMA vez por processo.**
     *
     * Medido no aparelho em 21/08: [render] estava sendo chamado a cada reprodução,
     * na Main, e o custo por earcon ia de 220 µs a 3494 µs, somando **14,5 ms**
     * para os oito de então.
     *
     * São **tons estáticos**: a mesma senóide, com a mesma frequência e a mesma
     * duração, recalculada amostra por amostra toda vez que o agente ouve um bipe.
     * `GRAVANDO` são 32 000 amostras.
     *
     * O custo total é modesto, e é justamente por isso que passou despercebido: 3 ms
     * na Main não fazem ANR, só entram na conta de tudo o mais que disputa aquela
     * thread. Guardar é trivial e o cache é seguro por construção — o mapa é
     * preenchido na primeira leitura de cada chave e o `ShortArray` nunca é mutado
     * depois, porque quem consome copia para o `AudioTrack`.
     *
     * **Memória:** os onze somam ~66 000 amostras = **132 KB**. Uma vez, para
     * sempre, contra a síntese repetida a cada reprodução.
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

    /**
     * **A duração real do earcon, derivada do PCM.** Não é tabela: é `amostras / taxa`.
     *
     * Existe porque havia uma **segunda tabela**, escrita à mão em
     * `RadioViewModel.duracaoDoEarcon`, que alimentava a janela de supressão de
     * captura — e ela já divergia de três dos dez earcons antes de o vocabulário
     * mudar. O sintoma é específico e feio: a janela fecha **antes** de o som acabar,
     * a cauda volta pelo microfone, e entra no pré-roll do PTT seguinte. Era o defeito
     * que `SupressorDeSaidaPropria` existe para impedir, reintroduzido por uma
     * constante desatualizada.
     *
     * O custo é uma consulta ao mesmo cache que a reprodução já usa.
     */
    fun duracaoMs(earcon: Earcon): Long =
        render(earcon).size * 1_000L / SAMPLE_RATE_HZ

    private fun sintetizar(earcon: Earcon): ShortArray = when (earcon) {

        // BOMMM — golpe de sino inarmônico. O único `sino` do vocabulário, e é
        // essa singularidade que faz a marca. As razões 1 : 2,76 : 5,40 são as de
        // um sino de bronze (hum, prime, tierce); harmônicos inteiros soariam a
        // órgão, que é som de qualquer coisa.
        Earcon.DESPERTAR -> sino(466.0, doubleArrayOf(1.0, 2.76, 5.40), 520, decaimentoMs = 210)

        // bipbip — dois chirps IDÊNTICOS subindo. Repetir o mesmo gesto é o que faz
        // o ouvido ler "bip-bip" como uma coisa só, e subir é a convenção de abrir.
        Earcon.CANAL_ABERTO -> concat(
            varredura(1300.0, 1900.0, 45), silencio(45), varredura(1300.0, 1900.0, 45),
        )

        // trimtrim — espelho do de cima: desce, e o segundo é mais longo e cai mais
        // fundo. Cauda longa é o que o ouvido lê como fim; um par simétrico do
        // `CANAL_ABERTO` seria distinguível por contorno e ambíguo por gesto.
        Earcon.CANAL_FECHADO -> concat(
            varredura(1500.0, 900.0, 55), silencio(45), varredura(1500.0, 750.0, 110),
        )

        // Dois degraus subindo, curto→longo: "ta-DAA" é conclusão em qualquer
        // idioma. Antes eram dois bipes IGUAIS a 880 Hz, morfologia idêntica à do
        // `CONSULTA_RESTRICAO_ADMIN`.
        Earcon.ACAO_EXECUTADA -> concat(tom(830.0, 60), silencio(60), tom(1245.0, 110))

        // Varredura descendente larga. Começava em 520 e ia a 300 Hz: 46 % da
        // energia caía abaixo de 400 Hz, ou seja, debaixo do motor da viatura — no
        // earcon que avisa que algo deu errado. Agora cai de 1000 a 430.
        Earcon.FALHA -> varredura(1000.0, 430.0, 220)

        // 8 × (145 ms de tom + 105 ms de silêncio) = 2000 ms exatos.
        Earcon.GRAVANDO -> concat(
            *Array(8) { concat(tom(560.0, 145), silencio(105)) },
        )

        Earcon.PRIORITARIA -> bipes(1_200.0, 3, 70, 50)

        Earcon.CONSULTA_SEM_RESTRICAO -> tom(1_400.0, 150)

        // Duas díades (fundamental + quinta justa). O timbre é a pista que separa
        // este do `ACAO_EXECUTADA` quando o ruído já comeu a altura absoluta.
        Earcon.CONSULTA_RESTRICAO_ADMIN -> concat(
            diade(820.0, 1230.0, 130),
            silencio(70),
            diadeVarrida(820.0, 620.0, 1230.0, 930.0, 130),
        )

        // Sirene hi-lo de quatro elementos: a morfologia mais insistente do
        // vocabulário, para o desfecho mais grave da consulta.
        Earcon.CONSULTA_FURTO_ROUBO -> concat(
            tom(1550.0, 80), silencio(40), tom(1150.0, 80), silencio(40),
            tom(1550.0, 80), silencio(40), tom(1150.0, 80),
        )
    }

    // ------------------------------------------------------------- primitivas

    private const val AMPLITUDE = 0.6
    private const val FADE_MS = 5

    private fun amostras(durationMs: Int) = SAMPLE_RATE_HZ * durationMs / 1_000

    /**
     * Envelope de 5 ms em cada ponta.
     *
     * Não é polimento: um tom que começa e termina em degrau produz um estalo de
     * banda larga, e num elo de 8 kHz o estalo é a parte do earcon que **mais**
     * atravessa — o agente ouviria o clique e não o tom.
     */
    private fun envelope(i: Int, n: Int): Double {
        val fade = (SAMPLE_RATE_HZ * FADE_MS / 1_000).coerceAtMost(n / 2)
        if (fade <= 0) return 1.0
        return when {
            i < fade -> i.toDouble() / fade
            i > n - fade -> (n - i).toDouble() / fade
            else -> 1.0
        }
    }

    private fun paraPcm(v: Double): Short = (v * Short.MAX_VALUE).toInt().toShort()

    private fun tom(freqHz: Double, durationMs: Int): ShortArray {
        val n = amostras(durationMs)
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = paraPcm(sin(2.0 * PI * freqHz * i / SAMPLE_RATE_HZ) * envelope(i, n) * AMPLITUDE)
        }
        return out
    }

    /** Dois tons **simultâneos** — timbre, não melodia. Pesos 0,62/0,38 para o grave dominar. */
    private fun diade(f1Hz: Double, f2Hz: Double, durationMs: Int): ShortArray {
        val n = amostras(durationMs)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val s = sin(2.0 * PI * f1Hz * i / SAMPLE_RATE_HZ) * 0.62 +
                sin(2.0 * PI * f2Hz * i / SAMPLE_RATE_HZ) * 0.38
            out[i] = paraPcm(s * envelope(i, n) * AMPLITUDE)
        }
        return out
    }

    /** Díade que desliza — as duas parciais varrem juntas, preservando o intervalo. */
    private fun diadeVarrida(
        f1De: Double,
        f1Ate: Double,
        f2De: Double,
        f2Ate: Double,
        durationMs: Int,
    ): ShortArray {
        val n = amostras(durationMs)
        val out = ShortArray(n)
        var fase1 = 0.0
        var fase2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            fase1 += 2.0 * PI * (f1De + (f1Ate - f1De) * t) / SAMPLE_RATE_HZ
            fase2 += 2.0 * PI * (f2De + (f2Ate - f2De) * t) / SAMPLE_RATE_HZ
            out[i] = paraPcm((sin(fase1) * 0.62 + sin(fase2) * 0.38) * envelope(i, n) * AMPLITUDE)
        }
        return out
    }

    private fun bipes(freqHz: Double, count: Int, msEach: Int, gapMs: Int): ShortArray {
        val partes = ArrayList<ShortArray>(count * 2 - 1)
        repeat(count) { i ->
            partes.add(tom(freqHz, msEach))
            if (i < count - 1) partes.add(silencio(gapMs))
        }
        return concat(*partes.toTypedArray())
    }

    /**
     * Varredura de frequência com fase integrada.
     *
     * Integrar a fase (e não avaliar `sin(2π f t)` com `f` variável) é o que impede
     * o salto de fase que produziria um estalo no meio do glissando.
     */
    private fun varredura(deHz: Double, ateHz: Double, durationMs: Int): ShortArray {
        val n = amostras(durationMs)
        val out = ShortArray(n)
        var fase = 0.0
        for (i in 0 until n) {
            val f = deHz + (ateHz - deHz) * (i.toDouble() / n)
            fase += 2.0 * PI * f / SAMPLE_RATE_HZ
            out[i] = paraPcm(sin(fase) * envelope(i, n) * AMPLITUDE)
        }
        return out
    }

    /**
     * Golpe de sino: ataque de 2 ms, parciais **inarmônicos**, cada um decaindo
     * mais rápido quanto mais agudo — que é o que um sino de metal faz e o que dá
     * ao som o brilho inicial seguido de um zumbido grave.
     *
     * A saída de 20 ms existe porque a cauda ainda tem nível audível em 520 ms
     * (−25 dB do pico): cortar em degrau ali seria um estalo no fim da marca.
     */
    private fun sino(
        fundamentalHz: Double,
        razoes: DoubleArray,
        durationMs: Int,
        decaimentoMs: Int,
    ): ShortArray {
        val n = amostras(durationMs)
        val ataque = amostras(2).coerceAtLeast(1)
        val saida = amostras(20).coerceAtLeast(1)
        val tau = SAMPLE_RATE_HZ * decaimentoMs / 1_000.0
        val pesos = doubleArrayOf(1.0, 0.62, 0.38, 0.22)
        val soma = razoes.indices.sumOf { pesos[it] }
        val out = ShortArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in razoes.indices) {
                val decaido = exp(-((i - ataque).coerceAtLeast(0)) / (tau / (0.6 + 0.55 * razoes[k])))
                s += pesos[k] * sin(2.0 * PI * fundamentalHz * razoes[k] * i / SAMPLE_RATE_HZ) * decaido
            }
            var env = if (i < ataque) i.toDouble() / ataque else 1.0
            if (i > n - saida) env *= (n - i).toDouble() / saida
            out[i] = paraPcm((s / soma) * env * AMPLITUDE)
        }
        return out
    }

    private fun silencio(durationMs: Int) = ShortArray(amostras(durationMs))

    private fun concat(vararg partes: ShortArray): ShortArray {
        val out = ShortArray(partes.sumOf { it.size })
        var i = 0
        for (p in partes) {
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }
}
