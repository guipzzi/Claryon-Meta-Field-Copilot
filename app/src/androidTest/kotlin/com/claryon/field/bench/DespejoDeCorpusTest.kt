package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.voice.PiperTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Gera o corpus de ativação em WAV, para o treino do detector acústico rodar fora.**
 *
 * Não mede nada — despeja. O treino da palavra de ativação acontece em Python, na
 * máquina de desenvolvimento, e precisa de áudio; este teste é a ponte, e existe para
 * que o áudio venha do **mesmo Piper, na mesma voz e na mesma banda** de todas as
 * medições do projeto. Sintetizar em outro lugar introduziria uma variável nova
 * justamente onde o dia inteiro mostrou que variável escondida custa caro.
 *
 * Duas bandas por enunciado: cheia e 8 kHz. A de produção é a estreita — o HFP entrega
 * CVSD — mas guardar as duas permite medir quanto a banda custa ao detector, que é uma
 * pergunta que o caminho por transcrição nunca conseguiu responder direito.
 *
 * O Piper **sorteia a duração de cada fonema** a cada síntese (`RepetibilidadeDaBancadaTest`).
 * Repetir a mesma palavra N vezes não gera cópias: gera rendições. É por isso que o
 * corpus positivo é a mesma palavra pedida muitas vezes, e não um truque.
 *
 * ```
 * adb shell am instrument -w -e class com.claryon.field.bench.DespejoDeCorpusTest \
 *   com.claryon.field.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.claryon.field/files/corpus
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DespejoDeCorpusTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Quantas rendições de cada enunciado. O Piper varia sozinho a cada chamada. */
    private val rendicoes = 60

    /** A palavra de ativação, sozinha e em contexto. */
    private val positivos = listOf("Claryon.")

    /**
     * Os negativos que importam: as vizinhas acústicas de *clar-* e o vocabulário de
     * rádio que vai estar ligado o tempo todo ao lado do detector.
     */
    private val negativos = listOf(
        // vizinhas de "clar-": as que qualquer detector desta palavra vai encontrar
        "clareou", "claridade", "clara", "clarim", "clarão", "claro", "clareza",
        "clarinete", "esclarece", "declara", "clarear", "claríssimo",
        // rimas em -on/-ion, que foi por onde o portão por transcrição vazou
        "elétron", "próton", "cordon", "batom", "bombom", "cânon", "trombone",
        // vocabulário de rádio, que fica ligado ao lado do detector o tempo todo
        "câmbio", "na escuta", "guarnição", "central", "ocorrência", "carro",
        "apoio", "alerta", "atenção", "cambiando", "viatura", "deslocamento",
        "positivo", "negativo", "prossiga", "aguarde",
    )

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /** WAV PCM 16 bits mono. Cabeçalho canônico de 44 bytes, sem chunk extra. */
    private fun escreverWav(destino: File, pcm: ShortArray, taxaHz: Int) {
        val bytes = pcm.size * 2
        destino.outputStream().buffered().use { o ->
            fun i32(v: Int) = o.write(byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ))
            fun i16(v: Int) = o.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
            o.write("RIFF".toByteArray()); i32(36 + bytes); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1)
            i32(taxaHz); i32(taxaHz * 2); i16(2); i16(16)
            o.write("data".toByteArray()); i32(bytes)
            val b = ByteArray(bytes)
            for (i in pcm.indices) {
                b[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                b[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
            }
            o.write(b)
        }
    }

    @Test
    fun despejaOCorpusDeAtivacao(): Unit = runBlocking {
        Assume.assumeTrue("Piper ausente", Modelos.piper(ctx) != null)
        val espeak = File(ctx.filesDir, "espeak-ng-data").absolutePath
        val raiz = File(ctx.getExternalFilesDir(null), "corpus").apply {
            deleteRecursively()
            mkdirs()
        }
        val cheia = File(raiz, "cheia").apply { mkdirs() }
        val estreita = File(raiz, "estreita").apply { mkdirs() }

        val piper = PiperTts(
            assetManager = ctx.assets,
            modelDir = Modelos.PIPER_ASSET_DIR,
            modelName = Modelos.PIPER_MODELO,
            dataDir = espeak,
            speed = Modelos.VELOCIDADE_DE_CAMPO,
        )

        var n = 0
        suspend fun despejar(rotulo: String, texto: String, i: Int) {
            val pcm = piper.synthesize(texto).getOrNull() ?: return
            val full = PcmResampler.resample(pcm.samples, pcm.sampleRateHz, taxa)
            escreverWav(File(cheia, "%s_%03d.wav".format(rotulo, i)), full, taxa)
            escreverWav(File(estreita, "%s_%03d.wav".format(rotulo, i)), porHfp(full), taxa)
            n++
        }

        repeat(rendicoes) { i ->
            positivos.forEachIndexed { k, t -> despejar("pos${k}", t, i) }
            negativos.forEach { p ->
                despejar(
                    "neg_" + p.replace(" ", "-")
                        .replace("ã", "a").replace("â", "a").replace("ç", "c").replace("ê", "e"),
                    "$p.", i,
                )
            }
        }
        piper.release()

        android.util.Log.i(
            "ClaryonField",
            """
            |CORPUS DESPEJADO
            |  ${n} enunciados × 2 bandas = ${n * 2} arquivos
            |  positivos: ${positivos.size} textos × $rendicoes rendições
            |  negativos: ${negativos.size} palavras × $rendicoes rendições
            |  em ${raiz.absolutePath}
            |  cheia/ = 16 kHz · estreita/ = passou por 8 kHz e voltou (o que o HFP entrega)
            """.trimMargin(),
        )

        assertTrue("nada foi despejado", n >= (positivos.size + negativos.size) * rendicoes / 2)
    }
}
