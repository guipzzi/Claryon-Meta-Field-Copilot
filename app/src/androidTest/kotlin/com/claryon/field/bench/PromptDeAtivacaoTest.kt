package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.voice.PiperTts
import com.whispercpp.whisper.PROMPT_DE_DOMINIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * **O prior nunca disse ao whisper que "Claryon" existe. Isto mede o que acontece
 * quando ele diz.**
 *
 * O que o banco de formas mostrou (`BancoDeFormasTest`) é que a **cauda do comando
 * sai perfeita** — `mudar para a guarnição 4`, `encerrar gravação`, `solicitar
 * reforço` — e só o **nome** se desfaz, em 18 grafias diferentes e nenhuma repetida.
 * A explicação é direta: a cauda está no vocabulário do modelo e o nome não. Diante
 * de um som que não casa com nada, o decodificador escolhe a sequência de tokens
 * portugueses mais provável, e ela muda com qualquer detalhe do áudio.
 *
 * `initial_prompt` existe exatamente para esse caso: é um prior de texto que
 * precondiciona o decodificador. `PROMPT_DE_DOMINIO` traz `Central, guarnicao,
 * ocorrencia…` — e **não traz `Claryon`**. Nenhuma das medições de palavra de
 * ativação deste projeto informou o modelo de que a marca existe.
 *
 * ## Os quatro braços, e por que o falso positivo é medido junto
 *
 * | braço | prior |
 * |---|---|
 * | **A** | nenhum — a linha de base, que deu 0/18 |
 * | **B** | só a palavra |
 * | **C** | a palavra em frases naturais, que é como o prior costuma funcionar melhor |
 * | **D** | o prior de domínio de hoje **mais** a palavra |
 *
 * O prior empurra o decodificador **para dentro** do léxico, e essa é a faca de dois
 * gumes já documentada: foi o `initial_prompt` que contaminou a régua de operação
 * (20 das 52 palavras estavam nele). Aqui o mesmo mecanismo pode transformar
 * `clareou` em `Claryon` — que é abrir o canal sozinho, o pior defeito possível
 * neste produto. Por isso os negativos incluem `clareou`, `claridade`, `clara` e
 * `clarim`, e nenhum braço pode ser declarado vencedor pelo recall isolado.
 *
 * Sem acento na palavra do prior — e isto não é preferência: o tokenizador do
 * whisper (`whisper.cpp:3288`) usa `[[:alpha:]]` sob locale "C", que não casa byte
 * multibyte. `Claryon` já é sem acento, então a questão não morde aqui.
 */
@RunWith(AndroidJUnit4::class)
class PromptDeAtivacaoTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val comandos = listOf(
        "mudar para guarnição 3", "mudar para guarnição 4", "onde está a guarnição 3",
        "pedir apoio", "modo ocorrência", "iniciar gravação", "encerrar gravação",
        "consultar placa", "modo ativo", "detalhar", "repetir", "solicitar reforço",
    )

    /** Fala que NÃO é comando. As quatro primeiras são as vizinhas acústicas. */
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

    private val bracos = listOf(
        "A · sem prior" to null,
        "B · só a palavra" to "Claryon.",
        "C · a palavra em frase natural" to
            "Claryon, mudar para guarnicao 3. Claryon, pedir apoio. Claryon, consultar placa.",
        "D · dominio + palavra" to "$PROMPT_DE_DOMINIO Claryon.",
    )

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /** O portão do produto: a transcrição começa pela marca, na grafia dela. */
    private fun abre(texto: String): Boolean {
        val t = normalizar(texto)
        return t == "claryon" || t.startsWith("claryon ")
    }

    /** Mais frouxo: a marca aparece em qualquer das três primeiras palavras. */
    private fun abreFrouxo(texto: String): Boolean =
        normalizar(texto).split(" ").take(3).any { it == "claryon" }

    @Test
    fun oPriorQueNomeiaAMarcaMudaOReconhecimento(): Unit = runBlocking {
        // ── Fase 1: sintetizar tudo em duas velocidades e soltar o Piper ─────
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

        // ── Fase 2: os quatro braços sobre exatamente o mesmo áudio ──────────
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val linhas = StringBuilder()
        val amostrasPorBraco = LinkedHashMap<String, List<String>>()
        for ((nome, prior) in bracos) {
            whisper!!.promptDeDominio = prior
            var estrito = 0
            var frouxo = 0
            val vistos = mutableListOf<String>()
            for ((_, pcm) in positivos) {
                val txt = (whisper.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
                if (abre(txt)) estrito++
                if (abreFrouxo(txt)) frouxo++
                vistos += normalizar(txt).split(" ").firstOrNull().orEmpty()
            }
            var fp = 0
            val ondeFp = mutableListOf<String>()
            for ((frase, pcm) in negativosPcm) {
                val txt = (whisper.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
                if (abreFrouxo(txt)) {
                    fp++
                    ondeFp += "\"${frase.take(30)}\" → \"${txt.trim().take(34)}\""
                }
            }
            amostrasPorBraco[nome] = vistos
            val n = positivos.size
            linhas.append(
                "\n  %-30s estrito %5.1f%% (%2d/%d)   3 primeiras %5.1f%% (%2d/%d)   falso positivo %d/%d%s"
                    .format(
                        nome, estrito * 100.0 / n, estrito, n,
                        frouxo * 100.0 / n, frouxo, n, fp, negativosPcm.size,
                        if (ondeFp.isEmpty()) "" else "\n      ← " + ondeFp.joinToString("\n      ← "),
                    ),
            )
        }
        whisper!!.promptDeDominio = null

        android.util.Log.i(
            "ClaryonField",
            """
            |PRIOR QUE NOMEIA A MARCA — banda estreita 8 kHz, ${Modelos.WHISPER_ASSET}
            |  ${positivos.size} comandos com "Claryon" · ${negativosPcm.size} falas que não são comando
            |  "estrito" = a transcrição COMEÇA por claryon · "3 primeiras" = aparece cedo
            |$linhas
            |
            |  primeira palavra que cada braço produziu:
            |${
                amostrasPorBraco.entries.joinToString("\n") { (b, v) ->
                    "    %-30s %s".format(b, v.distinct().take(9).joinToString(" · "))
                }
            }
            |
            |  ⚠️ Voz do Piper em duas velocidades. O aceite continua exigindo 30
            |     pronúncias REAIS por HFP e 8 h de rádio ambiente.
            """.trimMargin(),
        )

        assertTrue("nenhum braço foi medido", amostrasPorBraco.size == bracos.size)
        assertTrue("sem negativos, o falso positivo não foi medido", negativosPcm.size >= 6)
    }
}
