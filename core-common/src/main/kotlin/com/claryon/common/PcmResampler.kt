package com.claryon.common

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reamostragem de PCM 16-bit mono.
 *
 * Duas portas, e a escolha entre elas não é gosto:
 *
 *  - [resample] — **use esta**. Decide sozinha se precisa de anti-aliasing.
 *  - [resampleLinear] — interpolação crua, sem filtro. Só é correta para
 *    **upsample** (8 → 16 kHz, do HFP para o Whisper), onde não existe banda
 *    acima da Nyquist de saída para dobrar. Continua pública porque descer sem
 *    filtro é uma escolha legítima quando já se sabe que o sinal é de banda
 *    limitada — mas quem a chamar assume o alias.
 *
 * Por que o filtro deixou de ser opcional: o Piper sintetiza a 22.050 Hz
 * (`app/src/main/assets/models/vits-piper-pt_BR-faber-medium-int8/pt_BR-faber-medium.onnx.json`,
 * `audio.sample_rate = 22050`) e carrega energia até 11.025 Hz. Amostrar isso a
 * 16.000 Hz sem filtro faz a faixa 8.000–11.025 Hz **dobrar** para 4.975–8.000 Hz.
 * Medido no pipeline real: um tom de 9,5 kHz reaparece em 6,5 kHz com 61% da
 * amplitude com que um tom de 1 kHz atravessa. Não é sutileza — é o chiado que se
 * ouve como "voz de robô", e na bancada (fallback para o alto-falante do celular)
 * é audível hoje, sem óculos nenhum.
 *
 * Reamostrar de 8 para 16 kHz **não recupera** informação que o link a 8 kHz não
 * transmitiu — só adapta o formato para o modelo.
 */
object PcmResampler {

    /**
     * Reamostra aplicando anti-aliasing **quando desce**.
     *
     * `toHz < fromHz` passa antes por [filtrarParaNyquistDe]; `toHz > fromHz` vai
     * direto para a interpolação, porque subir não dobra banda nenhuma. Essa
     * assimetria é o motivo de existir uma porta só: o chamador não precisa saber
     * de qual lado está para acertar.
     */
    fun resample(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        require(fromHz > 0 && toHz > 0) { "taxas devem ser positivas" }
        if (fromHz == toHz || input.size < 2) return input
        val fonte = if (toHz < fromHz) filtrarParaNyquistDe(input, fromHz, toHz) else input
        return resampleLinear(fonte, fromHz, toHz)
    }

    /** Interpolação linear pura. Sem anti-aliasing — ver [resample]. */
    fun resampleLinear(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        require(fromHz > 0 && toHz > 0) { "taxas devem ser positivas" }
        if (fromHz == toHz || input.size < 2) return input

        val outLen = (input.size.toLong() * toHz / fromHz).toInt()
        if (outLen <= 0) return ShortArray(0)

        val out = ShortArray(outLen)
        val step = fromHz.toDouble() / toHz
        for (i in 0 until outLen) {
            val srcPos = i * step
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = input[idx].toInt()
            val s1 = if (idx + 1 < input.size) input[idx + 1].toInt() else s0
            out[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Passa-baixa FIR de fase linear aplicado **na taxa de entrada**, cortando na
     * Nyquist da taxa de **saída**. Devolve o sinal na mesma taxa e no mesmo
     * tamanho — quem decima é [resampleLinear], depois. Separar as duas etapas é
     * o que permite testar a rejeição isoladamente do erro de interpolação.
     *
     * Resposta calculada para 22.050 → 16.000 Hz (DFT do núcleo normalizado):
     * plano até 6,5 kHz (−0,02 dB), −0,68 dB em 7,0 kHz, −6,04 dB em 7,36 kHz e
     * **≥ 52 dB de rejeição de 8,0 kHz para cima** — exatamente a faixa que
     * dobraria. O que se perde é 7–8 kHz; na rota HFP dos óculos (8 kHz mono por
     * doc oficial do DAT, Nyquist 4 kHz) essa faixa nem sai do celular.
     *
     * Custo: uma frase operacional de 7 palavras (teto de
     * `docs/PADROES_DE_ENGENHARIA.md` §Design de áudio) tem ~44.100 amostras a
     * 22.050 Hz → ~2,78 M multiplicações-acumulações escalares, sem alocação além
     * da saída e de 63 `Double`. Roda no caminho crítico da resposta falada, mas é
     * ordens de grandeza abaixo da própria síntese do Piper. **Ordem de grandeza
     * estimada, não medida em aparelho.**
     */
    fun filtrarParaNyquistDe(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        require(fromHz > 0 && toHz > 0) { "taxas devem ser positivas" }
        if (toHz >= fromHz || input.size < 2) return input

        val h = nucleoPassaBaixa(fromHz, toHz)
        val m = h.size / 2 // núcleo ímpar → atraso de grupo = m, anulado por centrar
        val n = input.size
        val out = ShortArray(n)

        // Sinal mais curto que o núcleo: tudo é borda. Sem este ramo, o laço do
        // miolo teria limites invertidos e devolveria o array pela metade zerado.
        if (n <= 2 * m) {
            for (i in 0 until n) out[i] = amostra(convolverNaBorda(input, h, i, m))
            return out
        }

        // Bordas: **replicam** a amostra extrema em vez de zerar. Zerar acrescenta
        // um degrau de 1,4 ms no começo e no fim de cada frase, que sai como
        // clique — e num produto sem display, clique espúrio compete com earcon.
        for (i in 0 until m) out[i] = amostra(convolverNaBorda(input, h, i, m))
        for (i in (n - m) until n) out[i] = amostra(convolverNaBorda(input, h, i, m))

        // Miolo: por onde passa o áudio inteiro menos ~3 ms. Sem verificação de
        // índice por tap — ela custaria ~2,8 M comparações que já sabemos falsas.
        for (i in m until n - m) {
            var acc = 0.0
            var j = i - m
            for (k in h.indices) {
                acc += input[j].toDouble() * h[k]
                j++
            }
            out[i] = amostra(acc)
        }
        return out
    }

    private fun convolverNaBorda(input: ShortArray, h: DoubleArray, i: Int, m: Int): Double {
        var acc = 0.0
        val ultimo = input.size - 1
        for (k in h.indices) {
            acc += input[(i + k - m).coerceIn(0, ultimo)].toDouble() * h[k]
        }
        return acc
    }

    /**
     * Sinc janelado por Hamming, normalizado para ganho unitário em DC — sem a
     * normalização o filtro mexeria no volume da fala, e volume aqui é sinal
     * operacional, não estética.
     *
     * Os 63 `sin`/`cos` da construção são ~4 ordens de grandeza mais baratos que a
     * convolução que vem depois; por isso o núcleo é recalculado a cada chamada em
     * vez de cacheado num campo mutável, que só traria corrida de dados sem
     * economizar nada mensurável.
     */
    private fun nucleoPassaBaixa(fromHz: Int, toHz: Int): DoubleArray {
        val fcNormalizada = (toHz / 2.0 * FRACAO_DA_NYQUIST) / fromHz
        val m = (TAPS - 1) / 2
        val h = DoubleArray(TAPS)
        var soma = 0.0
        for (i in 0 until TAPS) {
            val x = (i - m).toDouble()
            val sinc =
                if (x == 0.0) 2.0 * fcNormalizada
                else sin(2.0 * PI * fcNormalizada * x) / (PI * x)
            val hamming = 0.54 - 0.46 * cos(2.0 * PI * i / (TAPS - 1))
            h[i] = sinc * hamming
            soma += h[i]
        }
        for (i in 0 until TAPS) h[i] /= soma
        return h
    }

    private fun amostra(acc: Double): Short =
        acc.roundToInt().coerceIn(-32_768, 32_767).toShort()

    /**
     * Ordem do FIR. **Ímpar de propósito**: fase linear com atraso de grupo
     * inteiro (31 amostras, 1,4 ms a 22.050 Hz), anulado por centrar o núcleo.
     *
     * 63 entrega **−52,2 dB no pior lóbulo** da faixa que dobra (8.079 Hz); no
     * ponto exato de 8.000 Hz a atenuação é −59,3 dB, e citar só esse número era
     * otimismo de 7 dB — o que importa é o pior caso, não o mais bonito. Com 47,
     * a largura de
     * transição de um Hamming (≈ 3,3·fs/N) só fecharia perto de 8,3 kHz e sobraria
     * alias na borda; acima de 63 se paga CPU por margem que já existe.
     */
    private const val TAPS = 63

    /**
     * Corte em 92% da Nyquist de saída (7.360 Hz para saída a 16 kHz). Os 8%
     * restantes são a banda de transição — sem essa folga, 63 taps de Hamming não
     * alcançam a rejeição plena antes de 8 kHz e sobra alias na borda.
     */
    private const val FRACAO_DA_NYQUIST = 0.92
}
