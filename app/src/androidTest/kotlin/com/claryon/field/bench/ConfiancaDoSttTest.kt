package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

/**
 * **`Transcript.confidence` deixa de ser sempre `null`, e isto prova que ela discrimina.**
 *
 * O campo existia desde o primeiro dia sem nunca ser preenchido, e
 * `specs/gatilho-por-voz.spec.md` chegou a registrar como *risco aceito* que "não há
 * limiar de confiança a ajustar". A afirmação era **falsa sobre o artefato**: o
 * whisper.cpp calcula `no_speech_prob`, usa internamente para decidir se emite o
 * segmento (`whisper.cpp:7622-7640`), e a expõe em
 * `whisper_full_get_segment_no_speech_prob` (`whisper.h:766`). Faltava binding.
 *
 * ## Por que este teste não pode ser só "o campo não é nulo"
 *
 * Um campo preenchido com uma constante também não é nulo. O que precisa ser provado
 * é que ele **separa** fala de não-fala — e a única forma honesta é medir os dois
 * casos e exigir que difiram. É o contra-teste que o `CLAUDE.md` §6.3 pede.
 *
 * ## Por que isto importa mais que qualquer ajuste de decodificação
 *
 * O portão da palavra de ativação hoje seria casamento de string sobre um texto que
 * pode ter sido alucinado. Com confiança, **recusar vira decisão informada** — e num
 * produto onde o falso positivo toma o piso da guarnição, recusar é sempre melhor
 * que adivinhar.
 */
@RunWith(AndroidJUnit4::class)
class ConfiancaDoSttTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Ruído branco: energia alta, estrutura de fala nenhuma. */
    private fun ruido(ms: Int) = ShortArray(taxa * ms / 1000) {
        (Random(42 + it).nextInt(-6000, 6000)).toShort()
    }

    /** Tom puro: periódico, mas sem formantes — não é fala. */
    private fun tom(ms: Int) = ShortArray(taxa * ms / 1000) { i ->
        (sin(2 * PI * 440.0 * i / taxa) * 8000).toInt().toShort()
    }

    @Test
    fun aConfiancaSeparaFalaDeNaoFala(): Unit = runBlocking {
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val sintese = piper!!.synthesize(
            "Central, a guarnição está a caminho da ocorrência.",
        ).getOrNull()
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", sintese != null)
        val fala = PcmResampler.resample(sintese!!.samples, sintese.sampleRateHz, taxa)

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        suspend fun medir(nome: String, pcm: ShortArray): Pair<String, Float?> {
            val r = whisper!!.transcribe(pcm, taxa)
            val t = (r as? Result.Success)?.value
            android.util.Log.i(
                "ClaryonField",
                "CONFIANÇA | %-12s conf=%s  texto=\"%s\"".format(
                    nome,
                    t?.confidence?.let { "%.3f".format(it) } ?: "—",
                    t?.text?.take(52) ?: "(recusado pelo STT)",
                ),
            )
            return nome to t?.confidence
        }

        val cFala = medir("fala", fala).second
        val cRuido = medir("ruído", ruido(2500)).second
        val cTom = medir("tom 440 Hz", tom(2500)).second

        android.util.Log.i(
            "ClaryonField",
            """
            |CONFIANÇA DO STT — no_speech_prob ligado pela primeira vez
            |  fala ......... ${cFala?.let { "%.3f".format(it) } ?: "sem segmento (o whisper já recusou)"}
            |  ruído ........ ${cRuido?.let { "%.3f".format(it) } ?: "sem segmento (o whisper já recusou)"}
            |  tom 440 Hz ... ${cTom?.let { "%.3f".format(it) } ?: "sem segmento (o whisper já recusou)"}
            |
            |  Recusa por ausência de segmento também é recusa — e é a que o whisper
            |  já fazia sozinho, sem ninguém no projeto saber.
            """.trimMargin(),
        )

        // A fala TEM de produzir um número: sem isso o binding não está ligado.
        assertNotNull("a fala não produziu confiança — o binding não está ligado", cFala)

        // **RESULTADO NEGATIVO, e ele fica registrado como asserção.**
        //
        // Medido: `no_speech_prob` volta ~0 (confiança 1,000) para fala, ruído
        // branco E tom puro. O whisper alucinou *"e aí"* sobre ruído e *"O que é
        // isso?"* sobre um tom de 440 Hz — com confiança máxima.
        //
        // O binding funciona; o sinal é que não serve. `no_speech_prob` **não
        // discrimina** nesta configuração, e portanto não pode sustentar o portão
        // de recusa que eu esperava construir com ele.
        //
        // Este teste não vira verde relaxando o critério: ele registra o fato. Se
        // um dia o valor passar a discriminar — outro modelo, outros parâmetros —
        // esta asserção falha e obriga a revisitar a conclusão.
        assertTrue(
            "no_speech_prob passou a discriminar (fala=$cFala ruído=$cRuido tom=$cTom). " +
                "Se isto falhou, o resultado negativo mudou e a defesa contra ruído " +
                "pode voltar a se apoiar na confiança do STT.",
            cRuido != null && cTom != null && cRuido >= cFala!! - 0.01f,
        )
    }

    /**
     * **A defesa que de fato existe: o ruído nunca chega ao whisper.**
     *
     * O teste acima alimenta PCM direto no STT, que é um caminho que **produção não
     * tem**. No ciclo real o portão é o VAD Silero, e é ele que decide o que vira
     * invocação do whisper. Sem este segundo teste, o achado da alucinação pareceria
     * um defeito aberto do produto — quando o desenho já o cobre.
     *
     * É a mesma disciplina do resto da bancada: medir o caminho que existe.
     */
    @Test
    fun oVad_barraORuidoAntesDoWhisper(): Unit = runBlocking {
        val vad = com.claryon.field.voice.SileroVoiceActivityDetector(
            assets = ctx.assets,
            sampleRateHz = taxa,
        )

        suspend fun segmentos(nome: String, pcm: ShortArray): Int {
            val quadros = pcm.toList().chunked(320) { it.toShortArray() }
            var n = 0
            vad.segment(kotlinx.coroutines.flow.flowOf(*quadros.toTypedArray())).collect { n++ }
            android.util.Log.i("ClaryonField", "VAD BARRA | %-12s → %d segmento(s)".format(nome, n))
            return n
        }

        val nRuido = segmentos("ruído", ruido(2500) + ShortArray(taxa))
        val nTom = segmentos("tom 440 Hz", tom(2500) + ShortArray(taxa))

        assertTrue(
            "o VAD deixou ruído branco passar para o whisper — a alucinação viraria " +
                "registro operacional de algo que ninguém disse",
            nRuido == 0,
        )
        assertTrue("o VAD deixou um tom puro passar para o whisper", nTom == 0)
    }
}
