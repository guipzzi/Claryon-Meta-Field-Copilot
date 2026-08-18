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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **A bancada é repetível? A pergunta que devia ter vindo antes de todos os números.**
 *
 * Duas medições de hoje rodaram **a mesma condição** — mesmo prior `"Claryon."`,
 * mesmo áudio sem entrada de silêncio, mesmo código — e deram **29,2%** e **41,7%**.
 * Três amostras de diferença em 24. Ou uma delas está errada, ou existe uma variável
 * aleatória que eu não declarei; nos dois casos, toda comparação fina que fiz hoje
 * está sem chão.
 *
 * ## As duas fontes possíveis, e como este teste as separa
 *
 * 1. **O Piper não é determinístico.** VITS tem *stochastic duration predictor*: o
 *    modelo sorteia a duração de cada fonema a cada síntese. Se for isso, cada
 *    rodada gera **áudio diferente** e a bancada inteira tem uma variável aleatória
 *    embutida que nunca foi declarada.
 * 2. **O whisper não é determinístico.** Decodificação gulosa deveria ser fixa; se
 *    não for, o problema é de outra natureza.
 *
 * O teste mede as duas em separado: sintetiza a mesma frase três vezes e compara
 * amostra a amostra; depois transcreve **o mesmo buffer** três vezes e compara texto.
 *
 * ## O que fazer com a resposta
 *
 * Se o Piper variar, nenhuma diferença de poucos pontos entre braços é conclusão —
 * é ruído — e as medições passam a exigir intervalo de confiança e n maior. O efeito
 * de 0% para ~30% do prior sobrevive a isso, porque zero está fora de qualquer
 * intervalo; a diferença entre 29% e 42%, não.
 *
 * E há um lado bom, que muda o desenho de quem for treinar um detector: um TTS que
 * sorteia a rendição é **gerador de dados variados de graça**, que é exatamente o
 * que falta para treinar palavra de ativação com uma voz só.
 */
@RunWith(AndroidJUnit4::class)
class RepetibilidadeDaBancadaTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val frases = listOf(
        "Claryon, pedir apoio.",
        "Claryon, mudar para guarnição 3.",
    )

    /** Diferença média absoluta entre dois buffers, em LSB de 16 bits. */
    private fun distancia(a: ShortArray, b: ShortArray): Pair<Int, Double> {
        val n = minOf(a.size, b.size)
        var soma = 0.0
        var iguais = 0
        for (i in 0 until n) {
            val d = abs(a[i].toInt() - b[i].toInt())
            soma += d.toDouble() * d
            if (d == 0) iguais++
        }
        return (a.size - b.size) to sqrt(soma / n.coerceAtLeast(1))
    }

    @Test
    fun oPiperEOWhisperRepetemOMesmoResultado(): Unit = runBlocking {
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)

        // ── O Piper: três sínteses da mesma frase, pela MESMA instância ──────
        val linhas = StringBuilder()
        val buffers = LinkedHashMap<String, MutableList<ShortArray>>()
        for (f in frases) {
            repeat(3) {
                piper!!.synthesize(f).getOrNull()?.let {
                    buffers.getOrPut(f) { mutableListOf() } += it.samples
                }
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", buffers.isNotEmpty())

        var pipperVaria = false
        for ((f, bs) in buffers) {
            if (bs.size < 2) continue
            linhas.append("\n  \"${f.take(34)}\"  ${bs.size} sínteses")
            for (i in 1 until bs.size) {
                val (dTam, rms) = distancia(bs[0], bs[i])
                val identico = dTam == 0 && rms == 0.0
                if (!identico) pipperVaria = true
                linhas.append(
                    "\n      1 vs ${i + 1}: ${bs[0].size} vs ${bs[i].size} amostras (Δ %+d)  RMS da diferença %.1f  → %s"
                        .format(dTam, rms, if (identico) "IDÊNTICO" else "DIFERENTE"),
                )
            }
        }

        // ── O whisper: três transcrições do MESMO buffer ─────────────────────
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)
        whisper!!.promptDeDominio = "Claryon."
        var whisperVaria = false
        linhas.append("\n")
        for ((f, bs) in buffers) {
            val pcm = bs.first()
            val textos = (1..3).map {
                (whisper.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty().trim()
            }
            if (textos.distinct().size > 1) whisperVaria = true
            linhas.append(
                "\n  \"${f.take(34)}\" → ${textos.distinct().size} texto(s) distinto(s) em 3: " +
                    textos.distinct().joinToString(" | ") { "\"${it.take(38)}\"" },
            )
        }
        whisper.promptDeDominio = null

        android.util.Log.i(
            "ClaryonField",
            """
            |REPETIBILIDADE DA BANCADA
            |$linhas
            |
            |  Piper repete o mesmo áudio? ....... ${if (pipperVaria) "**NÃO**" else "sim"}
            |  whisper repete o mesmo texto? ..... ${if (whisperVaria) "**NÃO**" else "sim"}
            |
            |  ${
                if (pipperVaria) {
                    "Consequência: toda medição desta bancada tem uma variável aleatória.\n" +
                        "  Diferenças de poucos pontos entre braços são RUÍDO, não achado, e\n" +
                        "  daqui em diante precisam de n maior e intervalo de confiança."
                } else {
                    "A bancada é determinística; a variação de 29,2% para 41,7% tem outra causa\n  e continua sem explicação."
                }
            }
            """.trimMargin(),
        )

        // Sem asserção de valor: o resultado é o achado. A asserção é de protocolo.
        assertTrue("nada foi sintetizado para comparar", buffers.values.any { it.size >= 2 })
    }
}
