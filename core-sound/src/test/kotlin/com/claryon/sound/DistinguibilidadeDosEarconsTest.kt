package com.claryon.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O vocabulário de earcons é o único canal do produto. Nada guardava que dois
 * sinais fossem distinguíveis.**
 *
 * Até 22/08 a suíte inteira de `EarconSynthesizer` cabia em duas asserções: PCM não
 * vazio, PCM não silencioso, e 32 000 amostras no `GRAVANDO`. Um `else -> tom(500.0,
 * 150)` acidental passaria verde, e o produto ficaria com onze significados e um
 * som. Não é hipótese: **dois earcons já eram idênticos bit a bit por 115 ms**
 * (1845 de 1920 amostras), e ninguém soube por meses.
 *
 * ## As cinco réguas, e por que são cinco e não uma
 *
 * 1. [nenhumParCompartilhaOInicioAmostraPorAmostra] — o prefixo literal. Cru e
 *    incontornável: pega a família de defeito em que dois ramos do `when` começam
 *    com a mesma chamada.
 * 2. [todoEarconTemAssinaturaMorfologicaUnica] — `(nº de elementos, contornos,
 *    ataque)`, **calculada do PCM**. Pega o defeito que a distância espectral NÃO
 *    pega: dois bipes planos a 4,5 semitons um do outro estão longe no espectro e
 *    são a mesma coisa no ouvido, porque sob ruído o que sobra é o ritmo.
 * 3. [osParesSeparamNoSinalInteiroENoAtaque] — distância espectrotemporal, com o
 *    **ataque** medido à parte. O ataque é onde o agente decide se aquilo é para
 *    ele; um par que só diverge no fim já custou a decisão.
 * 4. [osParesSobrevivemAoRuidoDeViatura] — a mesma distância com ruído grave
 *    somado aos dois lados. Ruído de motor não some porque o desenho é bonito.
 * 5. [todoEarconCabeNaJanelaDoHfp] — 400 a 3400 Hz. Acima do teto o elo HFP não
 *    entrega; abaixo do piso o motor mascara. Um earcon fora da janela é um earcon
 *    que existe no gráfico e não no ouvido.
 *
 * ## O contra-teste, e ele é obrigatório aqui
 *
 * `CLAUDE.md` §6 pergunta 3: *"se o teste passaria com o defeito de volta, ele não
 * testa o defeito"*. Cada régua tem um par em [ContraTesteDoVocabularioAntigo], que
 * repõe as constantes anteriores a 22/08 e **exige reprovação**. Sem esse par, um
 * limiar frouxo transformaria as cinco réguas em decoração — que é exatamente o
 * estado em que este arquivo encontrou o projeto.
 *
 * ## Números medidos em 22/08 (mínimo sobre os 55 pares)
 *
 * | régua | antes | depois | limiar |
 * |---|---|---|---|
 * | distância, sinal inteiro | 0,1735 | **0,3326** | ≥ 0,25 |
 * | distância, ataque 120 ms | 0,0036 | **0,3339** | ≥ 0,22 |
 * | distância, ataque, ruído +6 dB | 0,0033 | **0,0428** | ≥ 0,030 |
 * | energia ≥ 400 Hz (pior earcon) | 54,1 % (`FALHA`) | **97,9 %** (`DESPERTAR`) | ≥ 95 % |
 * | assinaturas colidindo | 3 pares | **0** | 0 |
 *
 * O pior par mudou de lugar com o conserto, e isso é o esperado: antes era
 * `GRAVANDO × CONSULTA_FURTO_ROUBO` (o prefixo compartilhado); hoje é
 * `CANAL_FECHADO × PRIORITARIA` no sinal inteiro e `FALHA × CONSULTA_RESTRICAO_ADMIN`
 * no ataque — dois pares que ninguém confunde no ouvido, e que são o piso natural
 * de um vocabulário de dez sinais espremido em 3 kHz.
 */
class DistinguibilidadeDosEarconsTest {

    // ------------------------------------------------------------ as réguas

    /**
     * **O defeito literal: `GRAVANDO` e `CONSULTA_FURTO_ROUBO` começavam iguais.**
     *
     * Os dois ramos do `when` chamavam `tone(500.0, …)`, então as primeiras 1845
     * amostras de 1920 eram bit a bit as mesmas. Esta régua não usa métrica
     * nenhuma — compara amostras — e por isso não há como afrouxá-la sem querer.
     */
    @Test
    fun nenhumParCompartilhaOInicioAmostraPorAmostra() {
        val culpados = mutableListOf<String>()
        paraCadaPar(vocabularioAtual()) { a, b, x, y ->
            val n = min(min(x.size, y.size), ATAQUE_AMOSTRAS)
            val iguais = (0 until n).count { x[it] == y[it] }
            if (n > 0 && iguais.toDouble() / n > 0.90) {
                culpados += "$a × $b: $iguais de $n amostras idênticas (${iguais * 1000 / EarconSynthesizer.SAMPLE_RATE_HZ} ms)"
            }
        }
        assertEquals(
            "Dois earcons abrem com o MESMO PCM. Num sistema sem display, isso são " +
                "dois significados e um som — e o agente só descobre qual era depois " +
                "de o momento de reagir ter passado.\n" + culpados.joinToString("\n"),
            emptyList<String>(),
            culpados,
        )
    }

    /**
     * **A régua que a distância espectral não substitui.**
     *
     * `ACAO_EXECUTADA` e `CONSULTA_RESTRICAO_ADMIN` eram, os dois, *dois bipes
     * planos de tom puro*, a 4,5 semitons de distância. No espectro isso é longe
     * (bandas diferentes); no ouvido, dentro de uma viatura, é a mesma coisa —
     * porque a pista que sobrevive ao ruído é **quantos elementos e que forma
     * cada um tem**, não a altura absoluta.
     *
     * A assinatura é **medida do PCM**, nunca declarada: uma tabela de intenções ao
     * lado do sintetizador envelheceria na primeira mudança de constante, e o teste
     * passaria conferindo a si mesmo.
     */
    @Test
    fun todoEarconTemAssinaturaMorfologicaUnica() {
        val porAssinatura = LinkedHashMap<String, MutableList<String>>()
        for ((nome, pcm) in vocabularioAtual()) {
            porAssinatura.getOrPut(assinatura(pcm)) { mutableListOf() } += nome
        }
        val colisoes = porAssinatura.filterValues { it.size > 1 }
        assertEquals(
            "Earcons com a MESMA morfologia:\n" +
                colisoes.entries.joinToString("\n") { (a, ns) -> "  $a → $ns" } +
                "\n\nMorfologia (quantos elementos, que contorno cada um, e se o " +
                "ataque é golpe) é a pista que o ruído de viatura NÃO apaga. " +
                "Dois earcons com a mesma assinatura estão a uma frequência de " +
                "distância, e frequência é a primeira coisa que o motor come.\n\n" +
                "Assinaturas de todos:\n" + tabelaDeAssinaturas(),
            emptyMap<String, List<String>>(),
            colisoes,
        )
    }

    /**
     * Distância espectrotemporal par a par, no sinal inteiro **e** nos primeiros
     * 120 ms.
     *
     * O ataque tem régua própria porque é a janela em que o agente decide se aquele
     * som é para ele. Um par que diverge só no fim já cobrou a atenção inteira antes
     * de dizer o que era — e num P1 essa atenção é o produto.
     */
    @Test
    fun osParesSeparamNoSinalInteiroENoAtaque() {
        val v = vocabularioAtual()
        conferirDistancias(v, limite = null, minimo = MIN_INTEIRO, rotulo = "sinal inteiro")
        conferirDistancias(v, limite = ATAQUE_AMOSTRAS, minimo = MIN_ATAQUE, rotulo = "ataque 120 ms")
    }

    /**
     * **O mesmo ataque, com o motor ligado.**
     *
     * O ruído é somado aos DOIS lados do par, com a mesma realização: o ambiente é
     * um só, e o agente compara o que ouve com a memória daquele earcon *naquele*
     * ambiente. Somar ruído só a um lado mediria outra coisa — a diferença entre um
     * earcon e um chiado.
     *
     * O perfil é de viatura, não branco: passa-baixa em 700 Hz (−6 dB/oitava acima
     * disso) e passa-alta em 300 Hz, que é o piso do próprio elo SCO. É por isso que
     * energia abaixo de 400 Hz não conta como sinal neste produto.
     */
    @Test
    fun osParesSobrevivemAoRuidoDeViatura() {
        val sujo = vocabularioAtual().mapValues { (_, pcm) -> comRuido(pcm, SNR_DB, SEMENTE) }
        conferirDistancias(sujo, ATAQUE_AMOSTRAS, MIN_ATAQUE_COM_RUIDO, "ataque sob ruído +$SNR_DB dB")
    }

    /**
     * **400 a 3400 Hz, e as duas pontas doem por motivos diferentes.**
     *
     * Teto: o elo até os óculos é HFP/SCO de banda estreita — parcial acima de
     * ~3,4 kHz não chega, e planejá-la é planejar silêncio.
     *
     * Piso: ruído de viatura é grave. O `FALHA` varria até 300 Hz e tinha **54 % da
     * energia abaixo de 400** — mais da metade do earcon de erro morava debaixo do
     * motor, no sinal que mais precisa ser ouvido quando algo dá errado.
     */
    @Test
    fun todoEarconCabeNaJanelaDoHfp() {
        val fora = mutableListOf<String>()
        for ((nome, pcm) in vocabularioAtual()) {
            val (abaixoDoTeto, acimaDoPiso) = orcamentoDeBanda(pcm)
            if (abaixoDoTeto < 0.99) fora += "$nome: só ${pct(abaixoDoTeto)} da energia abaixo de 3400 Hz"
            if (acimaDoPiso < 0.95) fora += "$nome: só ${pct(acimaDoPiso)} da energia acima de 400 Hz"
        }
        assertEquals(
            "Earcon fora da janela que o hardware entrega:\n" + fora.joinToString("\n"),
            emptyList<String>(),
            fora,
        )
    }

    /**
     * O `GRAVANDO` continua com 2 s exatos.
     *
     * Ele deixou de ser um tom contínuo e virou um trem de 8 pulsos — mas a
     * DURAÇÃO é contrato de outro lugar: `RadioTatico` reserva a janela de
     * supressão de captura por `duracaoDoEarconMs`, e `RadioViewModel` mapeia
     * `GRAVANDO → 2000L` numa segunda tabela, à mão. Mexer nos 2 s aqui abriria
     * uma fresta de eco lá, em silêncio.
     */
    @Test
    fun gravandoContinuaComDoisSegundosExatos() {
        assertEquals(32_000, EarconSynthesizer.render(Earcon.GRAVANDO).size)
    }

    @Test
    fun todosOsEarconsProduzemPcmNaoVazioENaoSilencioso() {
        for (earcon in Earcon.entries) {
            val pcm = EarconSynthesizer.render(earcon)
            assertTrue("${earcon.name} vazio", pcm.isNotEmpty())
            assertTrue("${earcon.name} silencioso", pcm.any { abs(it.toInt()) > 1000 })
        }
    }

    /**
     * **A gramática pedida existe como três earcons distintos, e a marca é o
     * único golpe.**
     *
     * Se o `DESPERTAR` deixar de ser um golpe de sino ele vira mais um bipe: a
     * identidade sonora do produto não está na frequência, está no envelope, e é
     * ela que se pretende registrar. E se abrir e fechar canal deixarem de ser
     * espelhos — um sobe, o outro desce —, a convenção que o policial já traz do
     * rádio deixa de valer e vira coisa para aprender.
     */
    @Test
    fun aGramaticaDoCanalTemIdentidadeNoDespertarEConvencaoNoParDeCanal() {
        val v = vocabularioAtual()
        val golpes = Earcon.entries.filter { ehGolpe(v.getValue(it.name)) }
        assertEquals(
            "O golpe de sino é a marca sonora e tem de ser ÚNICO. Achei: $golpes",
            listOf(Earcon.DESPERTAR),
            golpes,
        )
        assertEquals(
            "CANAL_ABERTO deixou de ser 'sobe, sobe' — a convenção Nextel de canal aberto",
            "2|SOBE,SOBE|SUSTENTADO",
            assinatura(v.getValue(Earcon.CANAL_ABERTO.name)),
        )
        assertEquals(
            "CANAL_FECHADO deixou de ser 'desce, desce' — o espelho de canal aberto",
            "2|DESCE,DESCE|SUSTENTADO",
            assinatura(v.getValue(Earcon.CANAL_FECHADO.name)),
        )
    }

    // ------------------------------------------------- infra medida, não crida

    private fun vocabularioAtual(): Map<String, ShortArray> =
        Earcon.entries.associate { it.name to EarconSynthesizer.render(it) }

    private fun conferirDistancias(
        vocabulario: Map<String, ShortArray>,
        limite: Int?,
        minimo: Double,
        rotulo: String,
    ) {
        val fracos = mutableListOf<Triple<Double, String, String>>()
        var pior = Triple(1.0, "", "")
        paraCadaPar(vocabulario) { a, b, x, y ->
            val d = distancia(x, y, limite)
            if (d < pior.first) pior = Triple(d, a, b)
            if (d < minimo) fracos += Triple(d, a, b)
        }
        assertTrue(
            "Pares indistinguíveis no $rotulo (limiar $minimo):\n" +
                fracos.sortedBy { it.first }
                    .joinToString("\n") { (d, a, b) -> "  %.4f  %s × %s".format(d, a, b) } +
                "\n\nPior par medido: %.4f (%s × %s).".format(pior.first, pior.second, pior.third),
            fracos.isEmpty(),
        )
    }

    private inline fun paraCadaPar(
        vocabulario: Map<String, ShortArray>,
        bloco: (String, String, ShortArray, ShortArray) -> Unit,
    ) {
        val nomes = vocabulario.keys.toList()
        for (i in nomes.indices) {
            for (j in i + 1 until nomes.size) {
                bloco(nomes[i], nomes[j], vocabulario.getValue(nomes[i]), vocabulario.getValue(nomes[j]))
            }
        }
    }

    private fun tabelaDeAssinaturas(): String =
        vocabularioAtual().entries.joinToString("\n") { (n, p) -> "  %-26s %s".format(n, assinatura(p)) }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    companion object {

        /** 120 ms — a janela em que o agente decide se o som é para ele. */
        private val ATAQUE_AMOSTRAS = EarconSynthesizer.SAMPLE_RATE_HZ * 120 / 1_000

        // Limiares com folga sobre o medido em 22/08 (0,3326 · 0,3044 · 0,0425).
        // Não são apertados de propósito: apertá-los até o medido faria qualquer
        // reafinação legítima falhar e ensinaria a mexer no limiar em vez de no som.
        private const val MIN_INTEIRO = 0.25
        private const val MIN_ATAQUE = 0.22
        private const val MIN_ATAQUE_COM_RUIDO = 0.030

        private const val SNR_DB = 6.0
        private const val SEMENTE = 7919L

        // --- análise espectrotemporal ------------------------------------------

        private const val N = 256
        private const val HOP = 128
        private const val SPAN_DB = 48.0

        private val BANDAS = arrayOf(
            400 to 600, 600 to 850, 850 to 1150, 1150 to 1500,
            1500 to 1950, 1950 to 2450, 2450 to 3000, 3000 to 3400,
        )

        private val JANELA = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }
        private val COS = DoubleArray(N) { cos(2.0 * PI * it / N) }
        private val SIN = DoubleArray(N) { sin(2.0 * PI * it / N) }

        /** |X[k]|² por DFT direta com tabelas — N é 256, o custo não importa aqui. */
        private fun espectro(x: DoubleArray): DoubleArray {
            val out = DoubleArray(N / 2 + 1)
            for (k in out.indices) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until N) {
                    val idx = (k * i) % N
                    re += x[i] * COS[idx]
                    im -= x[i] * SIN[idx]
                }
                out[k] = re * re + im * im
            }
            return out
        }

        private fun rms(x: DoubleArray, de: Int = 0, ate: Int = x.size): Double {
            if (ate <= de) return 0.0
            var s = 0.0
            for (i in de until ate) s += x[i] * x[i]
            return sqrt(s / (ate - de))
        }

        private fun paraDouble(pcm: ShortArray, limite: Int?): DoubleArray {
            val n = limite?.coerceAtMost(pcm.size) ?: pcm.size
            return DoubleArray(n) { pcm[it] / 32768.0 }
        }

        /**
         * Análise de um earcon: nível por banda (dB), centroide e RMS, quadro a
         * quadro. O sinal é normalizado para RMS 0,1 **inteiro** — earcon mais alto
         * não pode parecer mais distinto por ser mais alto.
         */
        private class Analise(val bandas: Array<DoubleArray>, val centroide: DoubleArray, val rms: DoubleArray)

        private fun analisar(x0: DoubleArray): Analise {
            val g = if (rms(x0) > 0) 0.1 / rms(x0) else 1.0
            val nq = if (x0.size >= N) (x0.size - N) / HOP + 1 else 0
            val bandas = Array(nq) { DoubleArray(BANDAS.size) }
            val centroide = DoubleArray(nq)
            val energia = DoubleArray(nq)
            val quadro = DoubleArray(N)
            for (q in 0 until nq) {
                val base = q * HOP
                for (i in 0 until N) quadro[i] = x0[base + i] * g * JANELA[i]
                energia[q] = rms(quadro)
                val esp = espectro(quadro)
                for (b in BANDAS.indices) {
                    val k0 = (BANDAS[b].first * N / EarconSynthesizer.SAMPLE_RATE_HZ).coerceAtLeast(1)
                    val k1 = (BANDAS[b].second * N / EarconSynthesizer.SAMPLE_RATE_HZ).coerceAtMost(N / 2)
                    var s = 0.0
                    for (k in k0..k1) s += esp[k]
                    bandas[q][b] = 10.0 * log10(s / N + 1e-12)
                }
                // Centroide dos BINS (62,5 Hz), não das bandas: uma díade que
                // desliza 200 Hz não muda de banda e o contorno viraria PLANO.
                var num = 0.0
                var den = 0.0
                val kIni = (300 * N / EarconSynthesizer.SAMPLE_RATE_HZ).coerceAtLeast(1)
                val kFim = (3400 * N / EarconSynthesizer.SAMPLE_RATE_HZ).coerceAtMost(N / 2)
                for (k in kIni..kFim) {
                    val f = k.toDouble() * EarconSynthesizer.SAMPLE_RATE_HZ / N
                    num += f * esp[k]
                    den += esp[k]
                }
                centroide[q] = if (den > 0) num / den else 0.0
            }
            return Analise(bandas, centroide, energia)
        }

        /**
         * Distância = diferença **média de nível por banda e por quadro**, em
         * frações de um span de 48 dB, contada só nas bandas em que há o que ouvir.
         *
         * Dividir pelas 8 bandas fixas premiaria o silêncio: dois earcons em faixas
         * opostas ficariam "parecidos" porque as seis bandas vazias concordam. E
         * nada é normalizado por quadro, de propósito — normalizar apagaria o
         * envelope, que é metade da morfologia.
         */
        fun distancia(a: ShortArray, b: ShortArray, limite: Int?): Double {
            val A = analisar(paraDouble(a, limite)).bandas
            val B = analisar(paraDouble(b, limite)).bandas
            val n = min(A.size, B.size)
            if (n == 0) return 0.0
            var teto = -1e9
            for (q in 0 until n) for (v in A[q]) if (v > teto) teto = v
            for (q in 0 until n) for (v in B[q]) if (v > teto) teto = v
            val piso = teto - SPAN_DB
            var soma = 0.0
            var contadas = 0
            for (q in 0 until n) {
                for (b2 in BANDAS.indices) {
                    val x = A[q][b2].coerceIn(piso, teto)
                    val y = B[q][b2].coerceIn(piso, teto)
                    if (x <= piso && y <= piso) continue
                    soma += abs(x - y)
                    contadas++
                }
            }
            return if (contadas == 0) 0.0 else (soma / contadas) / SPAN_DB
        }

        // --- morfologia ---------------------------------------------------------

        /** Elementos = corridas acesas separadas por ≥ 2 quadros abaixo de −30 dB do pico. */
        private fun elementos(an: Analise): List<IntRange> {
            if (an.rms.isEmpty()) return emptyList()
            val pico = an.rms.max()
            val limiar = pico * 0.0316 // −30 dB
            val aceso = BooleanArray(an.rms.size) { an.rms[it] >= limiar }
            val out = mutableListOf<IntRange>()
            var i = 0
            while (i < aceso.size) {
                if (!aceso[i]) { i++; continue }
                var j = i
                while (j < aceso.size) {
                    if (aceso[j]) { j++; continue }
                    var k = j
                    while (k < aceso.size && !aceso[k]) k++
                    if (k - j >= 2) break
                    j = k
                }
                out += i until j
                i = j
            }
            return out
        }

        /** Contorno pelo deslocamento do centroide entre o 1º e o 3º terço do elemento. */
        private fun contorno(an: Analise, el: IntRange): String {
            val n = el.last - el.first + 1
            val t = (n / 3).coerceAtLeast(1)
            var ini = 0.0
            var fim = 0.0
            for (q in el.first until el.first + t) ini += an.centroide[q]
            for (q in el.last - t + 1..el.last) fim += an.centroide[q]
            val delta = fim / t - ini / t
            return when {
                delta >= 80.0 -> "SOBE"
                delta <= -80.0 -> "DESCE"
                else -> "PLANO"
            }
        }

        /**
         * Golpe: pico nos primeiros 8 ms, decaimento de ≥ 15 dB do 1º ao 3º terço,
         * e um elemento só. É a assinatura física de uma peça de metal golpeada — e
         * no vocabulário do Claryon só o `DESPERTAR` a tem.
         */
        fun ehGolpe(pcm: ShortArray): Boolean {
            val x = paraDouble(pcm, null)
            if (elementos(analisar(x)).size != 1) return false
            var pico = 0
            for (i in x.indices) if (abs(x[i]) > abs(x[pico])) pico = i
            if (pico > EarconSynthesizer.SAMPLE_RATE_HZ * 8 / 1_000) return false
            val t = x.size / 3
            val e1 = rms(x, 0, t)
            val e3 = rms(x, x.size - t, x.size)
            if (e3 <= 0.0) return true
            return 20.0 * log10(e1 / e3) >= 15.0
        }

        fun assinatura(pcm: ShortArray): String {
            val an = analisar(paraDouble(pcm, null))
            val els = elementos(an)
            val contornos = els.joinToString(",") { contorno(an, it) }
            return "${els.size}|$contornos|${if (ehGolpe(pcm)) "GOLPE" else "SUSTENTADO"}"
        }

        // --- banda e ruído ------------------------------------------------------

        /** Fração da energia abaixo de 3400 Hz e acima de 400 Hz. */
        fun orcamentoDeBanda(pcm: ShortArray): Pair<Double, Double> {
            val x = paraDouble(pcm, null)
            var total = 0.0
            var abaixoDoTeto = 0.0
            var acimaDoPiso = 0.0
            val quadro = DoubleArray(N)
            var base = 0
            while (base + N <= x.size) {
                for (i in 0 until N) quadro[i] = x[base + i] * JANELA[i]
                val esp = espectro(quadro)
                for (k in 1..N / 2) {
                    val f = k.toDouble() * EarconSynthesizer.SAMPLE_RATE_HZ / N
                    total += esp[k]
                    if (f <= 3400) abaixoDoTeto += esp[k]
                    if (f >= 400) acimaDoPiso += esp[k]
                }
                base += HOP * 4
            }
            if (total <= 0.0) return 0.0 to 0.0
            return abaixoDoTeto / total to acimaDoPiso / total
        }

        /**
         * Ruído de viatura determinístico: LCG, passa-baixa de 1ª ordem em 700 Hz
         * (−6 dB/oitava, o perfil de motor e rolamento) e passa-alta em 300 Hz, que
         * é o piso do próprio elo SCO.
         */
        fun ruidoDeViatura(n: Int, semente: Long): DoubleArray {
            var estado = semente and 0xFFFFFFFFL
            val bruto = DoubleArray(n) {
                estado = (estado * 1103515245L + 12345L) and 0x7FFFFFFFL
                estado.toDouble() / 0x3FFFFFFF - 1.0
            }
            val alfa = 2.0 * PI * 700 / EarconSynthesizer.SAMPLE_RATE_HZ
            var y = 0.0
            val grave = DoubleArray(n) { y += alfa * (bruto[it] - y); y }
            val alfaAlta = 2.0 * PI * 300 / EarconSynthesizer.SAMPLE_RATE_HZ
            var z = 0.0
            val out = DoubleArray(n) { z += alfaAlta * (grave[it] - z); grave[it] - z }
            val r = rms(out)
            if (r > 0) for (i in out.indices) out[i] /= r
            return out
        }

        fun comRuido(pcm: ShortArray, snrDb: Double, semente: Long): ShortArray {
            val x = paraDouble(pcm, null)
            val r = rms(x)
            val ruido = ruidoDeViatura(x.size, semente)
            val g = r / exp(snrDb / 20.0 * kotlin.math.ln(10.0))
            return ShortArray(x.size) { ((x[it] + g * ruido[it]) * 32768.0).toInt().coerceIn(-32768, 32767).toShort() }
        }
    }
}
