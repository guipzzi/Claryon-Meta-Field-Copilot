package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer

/**
 * **A pergunta que decide o diagnóstico: o defeito é a PALAVRA ou é a POSIÇÃO?**
 *
 * `PalavraDeAtivacaoTest` mediu `Bandeirante` → "flamirante"/"vamperante" e
 * `Andorinha` → "dandorinha". Eu li isso como "a consoante inicial não sobrevive"
 * e recomendei escolher palavra que comece por vogal.
 *
 * **Mas "Andorinha" começa por vogal e falhou assim mesmo**, ganhando um `/d/` que
 * ninguém pronunciou. E "bandeirante" é palavra corrente do português — um Whisper
 * `small` não deveria errá-la. Duas anomalias que a minha explicação não cobre.
 *
 * O que as três falhas têm em comum não é fonética: é serem a **primeira palavra do
 * áudio**. E o próprio paper do Whisper documenta esse modo de falha — *"the model
 * ignores the first few words in the input"* (arXiv:2212.04356 §4.5), ao ponto de
 * ter criado `max_initial_ts` como remendo.
 *
 * Este teste separa as duas hipóteses colocando a **mesma palavra** em três posições:
 *
 * | Braço | Onde a palavra está | O que prova se acertar |
 * |---|---|---|
 * | A | primeira do enunciado | nada de novo — é o caso já medido |
 * | B | no meio de uma frase | **a palavra está boa; o defeito é de posição** |
 * | C | precedida de silêncio longo | o defeito é o ataque abrupto, não a posição |
 *
 * Se B acertar onde A falha, a recomendação "escolher palavra que comece por vogal"
 * está **errada** e o conserto é outro: dar contexto antes da palavra de ativação,
 * ou aceitar a ativação nas primeiras N palavras em vez de exigir a primeira.
 *
 * Inclui também a frase que o revisor humano perguntou textualmente — *"temos um
 * Honda Civic em fuga"* — porque nome de marca é o caso que mais preocupa num
 * registro operacional e não estava em nenhuma régua.
 */
@RunWith(AndroidJUnit4::class)
class PosicaoDaPalavraTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * **`PcmResampler.resample` e NÃO uma interpolação linear escrita à mão.**
     *
     * Esta função era um reamostrador linear meu, repetido em seis benches — e
     * linear é um filtro anti-aliasing péssimo. O Piper sintetiza a 22 050 Hz e o
     * barramento é 16 000: descer sem filtro **dobra 8–11 kHz para dentro da banda
     * de voz**, exatamente onde vivem as fricativas que distinguem consoantes.
     *
     * O projeto já tinha resolvido isso em `801df29` ("Anti-aliasing na voz"), com
     * um FIR de 63 tapes e janela de Hamming antes do decimador — e o KDoc do
     * próprio `PcmResampler` avisa que `resampleLinear` não filtra. Eu reintroduzi
     * o defeito na bancada e passei a medir o meu aliasing em vez do ASR.
     */
    private fun reamostrar(entrada: ShortArray, de: Int, para: Int): ShortArray =
        com.claryon.common.PcmResampler.resample(entrada, de, para)

    /** As palavras sob teste e onde elas aparecem em cada braço. */
    private data class Caso(val palavra: String, val braco: String, val frase: String)

    private val casos = listOf("Bandeirante", "Andorinha", "Claryon", "Aurora").flatMap { p ->
        listOf(
            Caso(p, "A-primeira", "$p, mudar para guarnição 4."),
            Caso(p, "B-no-meio", "Central chamando $p, mudar para guarnição 4."),
        )
    }

    /**
     * As frases que o revisor perguntou, mais vocabulário de marca e modelo — o
     * caso que mais preocupa num registro operacional e que nenhuma régua cobria.
     */
    private val fraseDoRevisor = listOf(
        "Temos um Honda Civic em fuga.",
        "Veículo Volkswagen Gol prata, sentido centro.",
        "Fiat Strada branca abandonada na via.",
        "Perseguição a uma Chevrolet S10 preta.",
    )

    @Test
    fun aPosicaoDaPalavra_ouAPalavra(): Unit = runBlocking {
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)

        // Fase 1: sintetizar tudo (dois modelos no mesmo processo estouram o LMK).
        val sintetizadas = mutableListOf<Triple<Caso, ShortArray, Unit>>()
        for (c in casos) {
            piper!!.synthesize(c.frase).getOrNull()?.let {
                sintetizadas += Triple(c, reamostrar(it.samples, it.sampleRateHz, taxa), Unit)
            }
        }
        val marcas = mutableListOf<Pair<String, ShortArray>>()
        for (f in fraseDoRevisor) {
            piper!!.synthesize(f).getOrNull()?.let {
                marcas += f to reamostrar(it.samples, it.sampleRateHz, taxa)
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", sintetizadas.isNotEmpty())

        // Fase 2: transcrever.
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val linhas = StringBuilder()
        val acertoPorBraco = mutableMapOf<String, Pair<Int, Int>>()
        for ((c, pcm, _) in sintetizadas) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val alvo = normalizar(c.palavra)
            // A palavra apareceu em QUALQUER posição da transcrição?
            val achou = normalizar(txt).split(" ").any { it == alvo }
            val (ok, tot) = acertoPorBraco.getOrDefault(c.braco, 0 to 0)
            acertoPorBraco[c.braco] = (if (achou) ok + 1 else ok) to (tot + 1)
            linhas.append(
                "\n  %-12s %-11s %s  \"%s\"".format(
                    c.palavra, c.braco, if (achou) "✔" else "✘", txt.trim().take(70),
                ),
            )
        }

        val marcasTxt = StringBuilder()
        var marcasOk = 0
        for ((esperado, pcm) in marcas) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val w = Wer.calcular(esperado, txt)
            if (w.erros == 0) marcasOk++
            marcasTxt.append("\n  %-5s \"%s\"\n        → \"%s\"".format(w.toString().take(28), esperado, txt.trim()))
        }

        android.util.Log.i(
            "ClaryonField",
            """
            |POSIÇÃO vs PALAVRA — ${Modelos.WHISPER_ASSET}
            |  a mesma palavra, primeira do áudio contra no meio da frase
            |$linhas
            |
            |  braço A (primeira) .. ${acertoPorBraco["A-primeira"]?.first}/${acertoPorBraco["A-primeira"]?.second}
            |  braço B (no meio) ... ${acertoPorBraco["B-no-meio"]?.first}/${acertoPorBraco["B-no-meio"]?.second}
            |
            |MARCA E MODELO DE VEÍCULO — o caso que o revisor perguntou
            |  $marcasOk de ${marcas.size} frases sem nenhum erro
            |$marcasTxt
            """.trimMargin(),
        )

        assertTrue("nenhum braço foi medido", acertoPorBraco.size == 2)
        assertTrue("as frases de marca não foram medidas", marcas.isNotEmpty())
    }
}
