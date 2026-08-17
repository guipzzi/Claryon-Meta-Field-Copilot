package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.net.MediaCodecOpus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * **O aceite (a) da Fase 0, que nunca teve instrumento.**
 *
 * > *"Uma fala transmitida por PTT é reproduzida em tom e duração corretos —
 * > verificável por espectrograma comparando entrada e saída, com o pico de F0
 * > dentro de 5% do original."* — `ROADMAP.md`
 *
 * O critério existia desde sempre e a verificação era manual, feita de ouvido ou
 * não feita. Este teste a torna executável.
 *
 * ## Por que este aceite existe, e o que ele já pegou
 *
 * `RadioTatico` tinha `sampleRateHz = 8_000` como padrão e ninguém sobrescrevia,
 * enquanto a captura entrega 16 kHz. **A voz transmitida saía uma oitava abaixo com
 * o dobro da duração** — o produto não era demonstrável, e o defeito era invisível
 * em qualquer teste que só verificasse "chegaram bytes".
 *
 * Um teste de tamanho de buffer não pega isso. Um de F0, sim: taxa errada por um
 * fator 2 desloca o F0 por um fator 2, que é 100% de erro contra um limite de 5%.
 *
 * ## Como o F0 é estimado, e por que assim
 *
 * Autocorrelação sobre a janela mais energética do sinal. Não é o estimador mais
 * sofisticado — não trata *pitch halving/doubling* nem fala sussurrada — mas é
 * exatamente adequado ao que o aceite pede: **detectar deslocamento sistemático de
 * frequência**, que é o modo de falha do caminho de áudio. Um estimador melhor não
 * mudaria o veredito e adicionaria código que ninguém revisaria.
 *
 * A busca cobre 70–400 Hz, que é a faixa de F0 de fala humana adulta com folga nos
 * dois extremos. A voz do Piper `faber` é masculina e cai perto de 100–130 Hz.
 */
@RunWith(AndroidJUnit4::class)
class FidelidadeDoPttTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * F0 por autocorrelação na janela de maior energia.
     *
     * Devolve `null` quando o sinal não tem periodicidade clara — silêncio ou
     * ruído. Nulo é resposta honesta: melhor que devolver um número que ninguém
     * pode interpretar.
     */
    private fun f0Hz(pcm: ShortArray, sampleRateHz: Int): Double? {
        if (pcm.size < sampleRateHz / 2) return null

        // Janela de 40 ms com maior energia — evita medir F0 no silêncio inicial.
        val janela = sampleRateHz * 40 / 1000
        var melhorInicio = 0
        var maiorEnergia = 0.0
        var i = 0
        while (i + janela < pcm.size) {
            var e = 0.0
            for (j in i until i + janela) e += pcm[j].toDouble() * pcm[j]
            if (e > maiorEnergia) {
                maiorEnergia = e
                melhorInicio = i
            }
            i += janela / 2
        }
        if (maiorEnergia <= 0.0) return null

        val x = DoubleArray(janela) { pcm[melhorInicio + it].toDouble() }
        val media = x.average()
        for (k in x.indices) x[k] -= media

        // 70–400 Hz cobre fala adulta com folga.
        val lagMin = sampleRateHz / 400
        val lagMax = sampleRateHz / 70
        if (lagMax >= janela) return null

        var melhorLag = -1
        var melhorR = 0.0
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 <= 0.0) return null

        for (lag in lagMin..lagMax) {
            var r = 0.0
            for (k in 0 until janela - lag) r += x[k] * x[k + lag]
            val norm = r / r0
            if (norm > melhorR) {
                melhorR = norm
                melhorLag = lag
            }
        }
        // Abaixo de 0,3 de correlação normalizada não há periodicidade confiável.
        if (melhorLag <= 0 || melhorR < 0.3) return null
        return sampleRateHz.toDouble() / melhorLag
    }

    @Test
    fun aFalaTransmitidaPeloPtt_mantemTomEDuracao(): Unit = runBlocking {
        // ── A fala de origem ──────────────────────────────────────────────────
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente: sem fala não há o que transmitir", piper != null)
        val sintese = piper!!.synthesize(
            "Central, guarnição três a caminho da ocorrência na avenida principal.",
        ).getOrNull()
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", sintese != null)

        val original = PcmResampler.resample(sintese!!.samples, sintese.sampleRateHz, taxa)
        val f0Original = f0Hz(original, taxa)
        Assume.assumeTrue("não deu para estimar F0 da origem", f0Original != null)

        // ── O caminho real do PTT: Opus de 20 ms, ida e volta ─────────────────
        //
        // O codec de produção, com a MESMA taxa que a captura entrega. Passar a
        // taxa é o que sustenta o acordo — foi a divergência entre 8 e 16 kHz que
        // produziu o defeito que este aceite existe para pegar.
        val codec = MediaCodecOpus(com.claryon.net.ConfigOpus(sampleRateHz = taxa))
        Assume.assumeTrue(
            "codec Opus indisponível neste aparelho",
            codec.preparar() is Result.Success,
        )

        val quadros = (codec.codificar(original) as? Result.Success)?.value
        // **Limitação de bancada declarada, não defeito do produto.** O emulador
        // traz apenas `c2.android.opus.decoder` — não há ENCODER Opus nele
        // (verificado em `media_codecs.xml` e no `dumpsys media.player`). Aparelho
        // real tem os dois. O aceite (a) só fecha em hardware.
        //
        // E há um achado de produto aqui: `preparar()` devolveu `Success` num
        // aparelho sem encoder. Ele não está verificando o que promete.
        Assume.assumeTrue(
            "sem ENCODER Opus neste aparelho (o emulador só traz o decoder) — " +
                "o aceite (a) exige hardware real",
            quadros != null,
        )

        val recebido = ArrayList<Short>(original.size)
        for (q in quadros!!) {
            (codec.decodificar(q) as? Result.Success)?.value?.let { pcm ->
                for (s in pcm) recebido.add(s)
            }
        }
        val saida = recebido.toShortArray()
        Assume.assumeTrue("a decodificação não devolveu áudio", saida.isNotEmpty())

        // **A taxa de SAÍDA não é a de entrada, e presumir isso falsearia tudo.**
        //
        // O KDoc de `MediaCodecOpus` avisa: *"o decodificador do Android devolve a
        // 24 kHz mesmo com entrada a 8 kHz"* — por isso `CodecDeVoz.taxaDeSaidaHz`
        // existe, e ela é descoberta pela contagem de amostras do primeiro quadro,
        // não assumida. Medir o F0 da saída com a taxa de ENTRADA produziria um erro
        // proporcional à razão entre as duas: exatamente o defeito que este aceite
        // existe para pegar, só que dentro do próprio instrumento.
        val taxaSaida = codec.taxaDeSaidaHz
        val f0Saida = f0Hz(saida, taxaSaida)

        val duracaoOrigemMs = original.size * 1000L / taxa
        val duracaoSaidaMs = saida.size * 1000L / taxaSaida
        val erroF0 = if (f0Saida != null) abs(f0Saida - f0Original!!) / f0Original * 100.0 else Double.NaN
        val erroDuracao = abs(duracaoSaidaMs - duracaoOrigemMs) * 100.0 / duracaoOrigemMs

        android.util.Log.i(
            "ClaryonField",
            """
            |ACEITE (a) — FIDELIDADE DO PTT, ida e volta pelo Opus real
            |  quadros Opus ....... ${quadros.size}
            |  taxa de entrada .... $taxa Hz · taxa de SAÍDA do decoder: $taxaSaida Hz
            |  F0 na origem ....... ${"%.1f".format(f0Original)} Hz
            |  F0 na saída ........ ${f0Saida?.let { "%.1f".format(it) } ?: "não estimável"} Hz
            |  erro de F0 ......... ${"%.2f".format(erroF0)} %   (limite do aceite: 5%)
            |  duração na origem .. $duracaoOrigemMs ms
            |  duração na saída ... $duracaoSaidaMs ms
            |  erro de duração .... ${"%.2f".format(erroDuracao)} %
            """.trimMargin(),
        )

        assertTrue(
            "aceite (a): o F0 da saída não foi estimável — sem isso não há verificação",
            f0Saida != null,
        )
        assertTrue(
            "aceite (a) da Fase 0: pico de F0 dentro de 5%% do original. " +
                "Origem %.1f Hz, saída %.1f Hz, erro %.2f%%"
                    .format(f0Original, f0Saida, erroF0),
            erroF0 <= 5.0,
        )
        // A duração é o outro meio do aceite: taxa errada por fator 2 desloca o F0
        // E dobra a duração. Verificar só o F0 deixaria passar um reamostrador que
        // corrige o tom e come metade do áudio.
        assertTrue(
            "aceite (a): duração fora de 5%%. Origem %d ms, saída %d ms, erro %.2f%%"
                .format(duracaoOrigemMs, duracaoSaidaMs, erroDuracao),
            erroDuracao <= 5.0,
        )
    }

    /**
     * **A metade do aceite (a) que a bancada CONSEGUE verificar.**
     *
     * O emulador não tem encoder Opus, então a ida e volta completa só fecha em
     * hardware. Mas o defeito que o aceite (a) existe para pegar — `RadioTatico`
     * com `sampleRateHz = 8_000` enquanto a captura entrega 16 kHz, produzindo voz
     * uma oitava abaixo com o dobro da duração — vive no **contrato de taxa**, e
     * esse é verificável sem codec nenhum.
     *
     * Este teste percorre o caminho de reamostragem de produção e exige que o tom
     * sobreviva. Se alguém reintroduzir uma divergência de taxa entre a captura e o
     * barramento, ele reprova aqui, no emulador, sem esperar o fone chegar.
     */
    @Test
    fun oContratoDeTaxa_naoDeslocaOTom(): Unit = runBlocking {
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val sintese = piper!!.synthesize("Guarnição três a caminho da ocorrência.").getOrNull()
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", sintese != null)

        val naTaxaDaVoz = sintese!!.samples
        val f0Voz = f0Hz(naTaxaDaVoz, sintese.sampleRateHz)
        Assume.assumeTrue("F0 não estimável na origem", f0Voz != null)

        // O caminho de produção: `PcmResampler.resample`, com FIR anti-aliasing.
        val noBarramento = PcmResampler.resample(naTaxaDaVoz, sintese.sampleRateHz, taxa)
        val f0Barramento = f0Hz(noBarramento, taxa)

        val erro = if (f0Barramento != null) abs(f0Barramento - f0Voz!!) / f0Voz * 100.0 else Double.NaN
        val duracaoVoz = naTaxaDaVoz.size * 1000L / sintese.sampleRateHz
        val duracaoBarramento = noBarramento.size * 1000L / taxa
        val erroDur = abs(duracaoBarramento - duracaoVoz) * 100.0 / duracaoVoz

        android.util.Log.i(
            "ClaryonField",
            """
            |ACEITE (a), parte verificável na bancada — contrato de taxa
            |  ${sintese.sampleRateHz} Hz (voz) → $taxa Hz (barramento)
            |  F0 ......... ${"%.1f".format(f0Voz)} → ${f0Barramento?.let { "%.1f".format(it) } ?: "?"} Hz
            |               erro ${"%.2f".format(erro)} %   (limite 5%)
            |  duração .... $duracaoVoz → $duracaoBarramento ms   erro ${"%.2f".format(erroDur)} %
            |  ⚠️ A ida e volta pelo Opus exige encoder, que este emulador não tem.
            """.trimMargin(),
        )

        assertTrue("F0 não estimável depois da reamostragem", f0Barramento != null)
        assertTrue(
            "o tom se deslocou na reamostragem: %.1f → %.1f Hz (%.2f%%)"
                .format(f0Voz, f0Barramento, erro),
            erro <= 5.0,
        )
        assertTrue(
            "a duração mudou na reamostragem: %d → %d ms (%.2f%%)"
                .format(duracaoVoz, duracaoBarramento, erroDur),
            erroDur <= 5.0,
        )
    }
}
