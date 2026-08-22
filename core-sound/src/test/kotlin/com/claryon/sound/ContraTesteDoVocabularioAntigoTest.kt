package com.claryon.sound

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O par obrigatório de [DistinguibilidadeDosEarconsTest]: reponha o defeito e
 * exija reprovação.**
 *
 * `CLAUDE.md` §6 pergunta 3 diz o essencial: *"se o teste passaria com o defeito de
 * volta, ele não testa o defeito"*. As cinco réguas de distinguibilidade são
 * métricas com limiar, e métrica com limiar frouxo é decoração — passa em tudo,
 * inclusive no que se propôs a barrar. A única prova de que os limiares mordem é
 * medir o vocabulário **anterior a 22/08** com as mesmas cinco e ver as cinco
 * reprovarem.
 *
 * Este arquivo carrega, portanto, as constantes antigas — não como história, mas
 * como **controle negativo permanente**. Se alguém afrouxar um limiar em
 * `DistinguibilidadeDosEarconsTest`, é aqui que a folga aparece: um destes seis
 * testes para de falhar-o-que-devia e vira vermelho.
 *
 * ### O que havia, e por que passava despercebido
 *
 * ```
 * GRAVANDO                 tone(500, 2000)                    ┐ 1845 de 1920 amostras
 * CONSULTA_FURTO_ROUBO     arpejo([500, 700, 950], 120)       ┘ idênticas — 115 ms
 * ACAO_EXECUTADA           beeps(880, 2, 90, 60)              ┐ mesma morfologia,
 * CONSULTA_RESTRICAO_ADMIN beeps(680, 2, 130, 90)             ┘ 4,5 semitons
 * FALHA                    sweep(520 → 300, 220)                46 % da energia < 400 Hz
 * ```
 *
 * A suíte de então tinha duas asserções — "não vazio" e "não silencioso" — e um
 * `assertEquals(32_000, …)`. Todas as três continuavam verdes com os defeitos
 * dentro, e é por isso que os defeitos ficaram.
 */
class ContraTesteDoVocabularioAntigoTest {

    // ---------------------------------------------------- as cinco reprovações

    @Test
    fun oVocabularioAntigoREPROVAVAnoPrefixoLiteral() {
        val v = antigo()
        var pior = 0.0
        var quem = ""
        paraCadaPar(v) { a, b, x, y ->
            val n = min(min(x.size, y.size), ATAQUE)
            val f = (0 until n).count { x[it] == y[it] }.toDouble() / n
            if (f > pior) { pior = f; quem = "$a × $b" }
        }
        assertTrue(
            "A régua do prefixo literal não enxerga mais o defeito que ela existe " +
                "para pegar: o pior par do vocabulário antigo compartilha só " +
                "${"%.1f%%".format(pior * 100)} do ataque ($quem), abaixo dos 90 % que " +
                "reprovam. Ou a síntese antiga foi transcrita errada aqui, ou a régua " +
                "de lá deixou de comparar o que diz comparar.",
            pior > 0.90,
        )
    }

    @Test
    fun oVocabularioAntigoREPROVAVAnaMorfologia() {
        val porAssinatura = LinkedHashMap<String, MutableList<String>>()
        for ((nome, pcm) in antigo()) {
            porAssinatura.getOrPut(DistinguibilidadeDosEarconsTest.assinatura(pcm)) { mutableListOf() } += nome
        }
        val colisoes = porAssinatura.filterValues { it.size > 1 }
        assertTrue(
            "A assinatura morfológica não separa mais o que não separava: " +
                "`ACAO_EXECUTADA` e `CONSULTA_RESTRICAO_ADMIN` eram os dois *dois " +
                "bipes planos*, e a régua tem de dizer isso. Colisões achadas: " +
                "$colisoes\nAssinaturas: " +
                antigo().entries.joinToString("\n") { (n, p) ->
                    "  %-26s %s".format(n, DistinguibilidadeDosEarconsTest.assinatura(p))
                },
            colisoes.size >= 3,
        )
        assertTrue(
            "A colisão específica do defeito 2 sumiu da régua: ACAO_EXECUTADA e " +
                "CONSULTA_RESTRICAO_ADMIN têm de cair na MESMA assinatura no " +
                "vocabulário antigo. Se não caem, a régua mudou de assunto.",
            colisoes.values.any {
                it.containsAll(listOf("ACAO_EXECUTADA", "CONSULTA_RESTRICAO_ADMIN"))
            },
        )
    }

    @Test
    fun oVocabularioAntigoREPROVAVAnaDistanciaDoSinalInteiro() =
        exigirReprovacao(limite = null, minimo = 0.25, rotulo = "sinal inteiro", medidoEm2208 = 0.1735)

    @Test
    fun oVocabularioAntigoREPROVAVAnaDistanciaDoAtaque() =
        exigirReprovacao(limite = ATAQUE, minimo = 0.22, rotulo = "ataque 120 ms", medidoEm2208 = 0.0036)

    @Test
    fun oVocabularioAntigoREPROVAVAnoAtaqueSobRuidoDeViatura() {
        val sujo = antigo().mapValues { (_, p) ->
            DistinguibilidadeDosEarconsTest.comRuido(p, 6.0, 7919L)
        }
        val pior = piorPar(sujo, ATAQUE)
        assertTrue(
            "Sob ruído de viatura o vocabulário antigo tinha de continuar " +
                "indistinguível (medido 0,0033 em 22/08, limiar 0,030). Aqui deu " +
                "%.4f em %s. Se subiu, o ruído deste teste deixou de ser o mesmo — " +
                "e aí o número do vocabulário NOVO também não vale.".format(pior.first, pior.second),
            pior.first < 0.030,
        )
    }

    @Test
    fun oFALHAAntigoREPROVAVAnaJanelaDoHfp() {
        val (_, acimaDoPiso) = DistinguibilidadeDosEarconsTest.orcamentoDeBanda(antigo().getValue("FALHA"))
        assertTrue(
            "O `FALHA` antigo varria 520 → 300 Hz e tinha cerca de metade da energia " +
                "debaixo do ruído de motor (medido 54,1 % acima de 400 Hz, contra os " +
                "95 % que a régua exige). Aqui deu ${"%.1f%%".format(acimaDoPiso * 100)}. " +
                "Se passou, a régua do piso parou de olhar para a faixa que o motor come.",
            acimaDoPiso < 0.95,
        )
    }

    // ------------------------------------------------------------------ infra

    private fun exigirReprovacao(limite: Int?, minimo: Double, rotulo: String, medidoEm2208: Double) {
        val pior = piorPar(antigo(), limite)
        assertTrue(
            "No $rotulo o vocabulário antigo tinha de REPROVAR (medido %.4f em 22/08, " +
                "limiar %.2f). Aqui o pior par deu %.4f em %s — ou seja, o limiar de " +
                "`DistinguibilidadeDosEarconsTest` deixou de morder e passaria com o " +
                "defeito de volta.".format(medidoEm2208, minimo, pior.first, pior.second),
            pior.first < minimo,
        )
    }

    private fun piorPar(v: Map<String, ShortArray>, limite: Int?): Pair<Double, String> {
        var pior = 1.0
        var quem = ""
        paraCadaPar(v) { a, b, x, y ->
            val d = DistinguibilidadeDosEarconsTest.distancia(x, y, limite)
            if (d < pior) { pior = d; quem = "$a × $b" }
        }
        return pior to quem
    }

    private inline fun paraCadaPar(
        v: Map<String, ShortArray>,
        bloco: (String, String, ShortArray, ShortArray) -> Unit,
    ) {
        val nomes = v.keys.toList()
        for (i in nomes.indices) {
            for (j in i + 1 until nomes.size) {
                bloco(nomes[i], nomes[j], v.getValue(nomes[i]), v.getValue(nomes[j]))
            }
        }
    }

    /**
     * **A síntese como estava até 22/08**, transcrita amostra por amostra do
     * `EarconSynthesizer` anterior — mesmos `tone`/`sweep`/`beeps`/`arpejo`, mesma
     * amplitude 0,6 e mesmo fade de 5 ms.
     *
     * Cópia e não parâmetro: um sintetizador com um interruptor "modo antigo"
     * carregaria o defeito para dentro de `src/main`, que é o oposto do que este
     * arquivo quer.
     */
    private fun antigo(): Map<String, ShortArray> = linkedMapOf(
        "OUVI_VOCE" to sweep(600.0, 1000.0, 180),
        "ACAO_EXECUTADA" to beeps(880.0, 2, 90, 60),
        "FALHA" to sweep(520.0, 300.0, 220),
        "GRAVANDO" to tone(500.0, 2_000),
        "PRIORITARIA" to beeps(1_200.0, 3, 70, 50),
        "CONSULTA_SEM_RESTRICAO" to tone(760.0, 150),
        "CONSULTA_RESTRICAO_ADMIN" to beeps(680.0, 2, 130, 90),
        "CONSULTA_FURTO_ROUBO" to arpejo(doubleArrayOf(500.0, 700.0, 950.0), 120),
    )

    private companion object {

        val ATAQUE = EarconSynthesizer.SAMPLE_RATE_HZ * 120 / 1_000
        const val SR = EarconSynthesizer.SAMPLE_RATE_HZ

        fun tone(freqHz: Double, durationMs: Int): ShortArray {
            val n = SR * durationMs / 1_000
            val out = ShortArray(n)
            val fade = (SR * 5 / 1_000).coerceAtMost(n / 2)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                out[i] = (sin(2.0 * PI * freqHz * i / SR) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            return out
        }

        fun beeps(freqHz: Double, count: Int, msEach: Int, gapMs: Int): ShortArray {
            val beep = tone(freqHz, msEach)
            val gap = ShortArray(SR * gapMs / 1_000)
            val out = ArrayList<Short>()
            repeat(count) { i ->
                beep.forEach(out::add)
                if (i < count - 1) gap.forEach(out::add)
            }
            return out.toShortArray()
        }

        fun sweep(fromHz: Double, toHz: Double, durationMs: Int): ShortArray {
            val n = SR * durationMs / 1_000
            val out = ShortArray(n)
            var phase = 0.0
            val fade = (SR * 5 / 1_000).coerceAtMost(n / 2)
            for (i in 0 until n) {
                val f = fromHz + (toHz - fromHz) * (i.toDouble() / n)
                phase += 2.0 * PI * f / SR
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                out[i] = (sin(phase) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            return out
        }

        fun arpejo(freqs: DoubleArray, msEach: Int): ShortArray {
            val out = ArrayList<Short>()
            freqs.forEach { f -> tone(f, msEach).forEach(out::add) }
            return out.toShortArray()
        }
    }
}
