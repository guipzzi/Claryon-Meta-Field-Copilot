package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Quanto o `initial_prompt` vale, medido — e não suposto.**
 *
 * O prior do domínio existe desde que o `ggml-base` entrou, e ninguém nunca soube
 * quanto ele vale, porque era **literal em C**: sem parâmetro não há braço de
 * controle, e sem braço de controle o WER do projeto não podia ser atribuído.
 * `docs/LEXICO_DO_INITIAL_PROMPT.md` prescrevia esta medição e ela era impossível.
 *
 * ## Os três braços, e por que exatamente estes
 *
 * | Braço | Prompt | O que ele isola |
 * |---|---|---|
 * | A0 | nenhum | o modelo sozinho — o piso honesto |
 * | A1 | sem acento | a forma que **tokeniza limpa** |
 * | A2 | com acento | a forma **ortograficamente correta** |
 *
 * A2 existe por causa de um defeito no tokenizador do próprio whisper: o regex de
 * `whisper.cpp:3288` usa `[[:alpha:]]` sob locale "C", que **não casa byte
 * multibyte**. "guarnição" é partida no `ç` e no `ã`, e o prior acaba enviesando
 * bytes soltos que o decoder quase nunca emite.
 *
 * A previsão que decorre disso é falsificável: **A1 deve bater A2**. Se A2 vencer,
 * a explicação do tokenizador está errada e é ela que precisa ser revista — não o
 * prompt.
 *
 * Eu tinha "corrigido" o prompt para acentuado por parecer óbvio que o prior devia
 * casar a saída desejada. Este teste existe porque óbvio não é medido.
 *
 * ## As frases
 *
 * Metade **colide** com o prior (é a régua de operação) e metade **não colide** (é
 * a régua limpa). Sem as duas, um prompt que ajuda só o que ele mesmo contém
 * pareceria ajudar em geral.
 */
@RunWith(AndroidJUnit4::class)
class BracosDoPromptTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val comColisao = listOf(
        "Central, a guarnição está a caminho da ocorrência.",
        "Solicito apoio, viatura em deslocamento.",
    )

    private val semColisao = listOf(
        "O veículo parou na esquina da avenida principal.",
        "Dois indivíduos correram para o terreno baldio.",
    )

    private val bracos = listOf(
        "A0-sem-prompt" to null,
        "A1-sem-acento" to
            "Central, guarnicao, ocorrencia, viatura, deslocamento, apoio, Sargento.",
        "A2-com-acento" to
            "Central, guarnição, ocorrência, viatura, deslocamento, apoio, Sargento.",
    )

    @Test
    fun quantoOPromptVale(): Unit = runBlocking {
        val frases = comColisao + semColisao

        // Sintetiza tudo primeiro e solta o Piper: os dois modelos no mesmo
        // processo estouram o LMK.
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val audio = mutableListOf<Pair<String, ShortArray>>()
        for (f in frases) {
            repeat(2) {
                piper!!.synthesize(f).getOrNull()?.let {
                    audio += f to PcmResampler.resample(it.samples, it.sampleRateHz, taxa)
                }
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", audio.isNotEmpty())

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val linhas = StringBuilder()
        val resumo = LinkedHashMap<String, Triple<Double, Double, Double>>()

        for ((nome, prompt) in bracos) {
            // **O mesmo áudio nos três braços.** Ressintetizar por braço trocaria a
            // variável sob teste pela estocasticidade do VITS.
            whisper!!.promptDeDominio = prompt

            val paresColisao = mutableListOf<Pair<String, String>>()
            val paresLimpos = mutableListOf<Pair<String, String>>()
            for ((frase, pcm) in audio) {
                val txt = (whisper.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
                if (frase in comColisao) paresColisao += frase to txt else paresLimpos += frase to txt
            }

            val todos = Wer.calcularCorpus(paresColisao + paresLimpos)
            val colisao = Wer.calcularCorpus(paresColisao)
            val limpos = Wer.calcularCorpus(paresLimpos)
            resumo[nome] = Triple(todos.percentual, colisao.percentual, limpos.percentual)

            linhas.append(
                "\n  %-14s geral %5.1f%%   com colisão %5.1f%%   sem colisão %5.1f%%"
                    .format(nome, todos.percentual, colisao.percentual, limpos.percentual),
            )
            paresColisao.take(1).forEach {
                linhas.append("\n       ex.: \"${it.second.trim().take(64)}\"")
            }
        }

        val a0 = resumo["A0-sem-prompt"]!!.first
        val a1 = resumo["A1-sem-acento"]!!.first
        val a2 = resumo["A2-com-acento"]!!.first

        android.util.Log.i(
            "ClaryonField",
            """
            |BRAÇOS DO INITIAL_PROMPT — ${audio.size} amostras por braço, ${Modelos.WHISPER_ASSET}
            |$linhas
            |
            |  o prior vale (A0 − A1) ... ${"%.1f".format(a0 - a1)} pontos de WER
            |  acento custa (A2 − A1) ... ${"%.1f".format(a2 - a1)} pontos
            |  previsão do tokenizador: A1 <= A2  →  ${if (a1 <= a2) "CONFIRMADA" else "REFUTADA"}
            """.trimMargin(),
        )

        // Asserção sobre o INSTRUMENTO: os três braços têm de ter produzido número.
        // O veredito de qual prompt adotar é decisão de spec, informada por isto.
        assertTrue("faltou braço", resumo.size == bracos.size)
        assertTrue("nenhuma amostra medida", audio.isNotEmpty())

        // Restaura o padrão para não contaminar os testes seguintes: `whisper` é
        // dono de processo via `Modelos`, e um prompt vazado daqui falsearia o bench
        // de qualidade que roda depois.
        whisper!!.promptDeDominio = com.whispercpp.whisper.PROMPT_DE_DOMINIO
    }
}
