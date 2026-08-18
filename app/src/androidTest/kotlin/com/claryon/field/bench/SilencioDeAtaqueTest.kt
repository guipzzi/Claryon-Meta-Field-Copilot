package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.voice.PiperTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * **A hipótese que o padrão dos erros aponta: o que se perde é o ataque, não a palavra.**
 *
 * Com o prior nomeando a marca, o reconhecimento saiu de 0% para 29,2%. O que sobrou
 * de erro não é aleatório como eu supunha — é **sistemático no primeiro fonema**:
 *
 * ```
 * vadeon · faryon · fladeon · varian · parion · fadion · valion · fadeon · farem
 * ```
 *
 * O ataque /kl/ de *Claryon* está sendo ouvido como /v/, /f/, /p/ — labiais e
 * labiodentais. A oclusiva velar /k/ é um **transiente curto e de alta frequência**:
 * é a parte da palavra que menos sobrevive a qualquer coisa que corte o começo.
 *
 * ## Por que a bancada pode ser a culpada, e por que isso importa em produção
 *
 * O Piper devolve a frase começando no **sample zero**, sem um milissegundo de
 * silêncio antes. O whisper foi treinado em áudio com entrada — o mel dos primeiros
 * quadros não tem contexto à esquerda, e a primeira palavra é a que paga. Se for
 * isso, o defeito nunca foi da palavra: era do áudio que eu entreguei.
 *
 * E o achado não fica na bancada. Em produção o áudio do gatilho começa **onde o VAD
 * decide que começou** — quer dizer, depois do transiente que o VAD precisou ouvir
 * para decidir. Se o pré-roll não devolver o que veio antes do disparo, a produção
 * corta o ataque exatamente como esta bancada corta.
 *
 * ## O contra-teste está embutido
 *
 * Se a hipótese for falsa, as quatro colunas dão o mesmo número e o silêncio não
 * explica nada — e aí o problema é a palavra, com evidência, não com suposição.
 */
@RunWith(AndroidJUnit4::class)
class SilencioDeAtaqueTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** O prior vencedor da medição anterior: só a palavra. */
    private val prior = "Claryon."

    private val comandos = listOf(
        "mudar para guarnição 3", "mudar para guarnição 4", "onde está a guarnição 3",
        "pedir apoio", "modo ocorrência", "iniciar gravação", "encerrar gravação",
        "consultar placa", "modo ativo", "detalhar", "repetir", "solicitar reforço",
    )

    private val negativos = listOf(
        "Ele clareou a situação para o comandante ontem.",
        "A claridade do dia ajudou na identificação.",
        "É clara a necessidade de apoio nesta ocorrência.",
        "O clarim tocou no pátio do quartel de manhã.",
        "Central, aqui é a guarnição dois, estamos no local.",
        "Negativo, sem alteração no perímetro, na escuta.",
        "Atenção todas as unidades, ocorrência na área central.",
        "A perseguição terminou perto do posto de gasolina.",
    )

    /** Quanto silêncio entra antes da fala. Zero é o que a bancada fazia. */
    private val entradas = listOf(0, 200, 500, 1000)

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /** Silêncio digital puro à frente. Não é ruído: isola a variável "contexto". */
    private fun comEntrada(pcm: ShortArray, ms: Int): ShortArray =
        if (ms <= 0) pcm else ShortArray(taxa * ms / 1000) + pcm

    private fun abre(texto: String): Boolean {
        val t = normalizar(texto)
        return t == "claryon" || t.startsWith("claryon ")
    }

    private fun abreFrouxo(texto: String): Boolean =
        normalizar(texto).split(" ").take(3).any { it == "claryon" }

    @Test
    fun oSilencioAntesDaFalaDecideOAtaque(): Unit = runBlocking {
        Assume.assumeTrue("Piper ausente", Modelos.piper(ctx) != null)
        val espeak = File(ctx.filesDir, "espeak-ng-data").absolutePath

        val positivos = mutableListOf<Pair<String, ShortArray>>()
        val negativosPcm = mutableListOf<Pair<String, ShortArray>>()
        for (v in listOf(0.9f, 1.05f)) {
            val piper = PiperTts(
                assetManager = ctx.assets,
                modelDir = Modelos.PIPER_ASSET_DIR,
                modelName = Modelos.PIPER_MODELO,
                dataDir = espeak,
                speed = v,
            )
            suspend fun falar(t: String): ShortArray? = piper.synthesize(t).getOrNull()
                ?.let { porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa)) }
            for (c in comandos) falar("Claryon, $c.")?.let { positivos += "[$v] $c" to it }
            if (v == 0.9f) for (f in negativos) falar(f)?.let { negativosPcm += f to it }
            piper.release()
        }
        Assume.assumeTrue("Piper não sintetizou", positivos.size >= 12)

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)
        whisper!!.promptDeDominio = prior

        val linhas = StringBuilder()
        val primeiras = LinkedHashMap<Int, List<String>>()
        for (ms in entradas) {
            var estrito = 0
            var frouxo = 0
            val vistos = mutableListOf<String>()
            for ((_, pcm) in positivos) {
                val txt = (whisper.transcribe(comEntrada(pcm, ms), taxa) as? Result.Success)
                    ?.value?.text.orEmpty()
                if (abre(txt)) estrito++
                if (abreFrouxo(txt)) frouxo++
                vistos += normalizar(txt).split(" ").firstOrNull().orEmpty()
            }
            var fp = 0
            val ondeFp = mutableListOf<String>()
            for ((frase, pcm) in negativosPcm) {
                val txt = (whisper.transcribe(comEntrada(pcm, ms), taxa) as? Result.Success)
                    ?.value?.text.orEmpty()
                if (abreFrouxo(txt)) {
                    fp++
                    ondeFp += "\"${frase.take(28)}\" → \"${txt.trim().take(32)}\""
                }
            }
            primeiras[ms] = vistos
            val n = positivos.size
            linhas.append(
                "\n  entrada de %4d ms   estrito %5.1f%% (%2d/%d)   3 primeiras %5.1f%% (%2d/%d)   falso positivo %d/%d%s"
                    .format(
                        ms, estrito * 100.0 / n, estrito, n, frouxo * 100.0 / n, frouxo, n,
                        fp, negativosPcm.size,
                        if (ondeFp.isEmpty()) "" else "\n      ← " + ondeFp.joinToString("\n      ← "),
                    ),
            )
        }
        whisper.promptDeDominio = null

        android.util.Log.i(
            "ClaryonField",
            """
            |SILÊNCIO DE ATAQUE — prior "$prior", banda estreita 8 kHz
            |  ${positivos.size} comandos · ${negativosPcm.size} falas que não são comando
            |  linha de base sem entrada e com prior: 29,2%
            |$linhas
            |
            |  primeira palavra por entrada:
            |${
                primeiras.entries.joinToString("\n") { (ms, v) ->
                    "    %4d ms  %s".format(ms, v.distinct().take(10).joinToString(" · "))
                }
            }
            |
            |  ⚠️ Voz do Piper em duas velocidades. O aceite continua exigindo 30
            |     pronúncias REAIS por HFP e 8 h de rádio ambiente.
            """.trimMargin(),
        )

        assertTrue("nenhuma entrada foi medida", primeiras.size == entradas.size)
    }
}
