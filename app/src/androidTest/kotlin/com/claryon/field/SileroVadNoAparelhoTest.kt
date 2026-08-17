package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.field.voice.SileroVoiceActivityDetector
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * **O que só o aparelho responde.**
 *
 * O detector inteiro depende de três coisas que nenhum teste JVM alcança: o
 * `.onnx` estar de fato no APK, o `AssetManager` conseguir abri-lo sem
 * descompressão, e o runtime nativo do sherpa-onnx carregar o modelo. Qualquer
 * uma falhando produz exceção só em runtime.
 *
 * O re-quadrador 320 → 512 também é verificado aqui, e é o ponto mais fácil de
 * errar em silêncio: alimentar o nativo com janela de tamanho errado não dá erro,
 * dá comportamento indefinido.
 */
@RunWith(AndroidJUnit4::class)
class SileroVadNoAparelhoTest {

    private val taxa = 16_000
    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    /** Quadros de 320 amostras — exatamente o que a captura do rádio entrega. */
    private fun quadrosDe(amostras: ShortArray): List<ShortArray> =
        amostras.toList().chunked(320) { it.toShortArray() }

    private fun silencio(ms: Int) = ShortArray(taxa * ms / 1000)

    /**
     * Um tom não é voz — e é justamente esse o ponto do Silero contra o RMS.
     * Para exercitar o caminho de detecção usamos um sinal modulado, mais
     * parecido com fala do que uma senoide pura.
     */
    private fun vozSintetica(ms: Int) = ShortArray(taxa * ms / 1000) { i ->
        val t = i.toDouble() / taxa
        val envelope = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)      // sílabas ~4 Hz
        val f0 = sin(2 * PI * 130.0 * t) + 0.5 * sin(2 * PI * 260.0 * t)
        (f0 * envelope * 9000).toInt().toShort()
    }

    @Test
    fun oModeloCarregaNoAparelho_eOReQuadradorNaoQuebra(): Unit = runBlocking {
        val vad = SileroVoiceActivityDetector(assets = assets, sampleRateHz = taxa)

        // 320 NÃO divide 512 — é exatamente por isso que o acumulador existe.
        // Este fluxo força o caso: sobra parcial em quase todo quadro.
        val entrada = silencio(300) + vozSintetica(1200) + silencio(900)
        val quadros = quadrosDe(entrada)
        assertEquals("a captura do rádio entrega quadros de 320", 320, quadros[0].size)

        // O que se prova aqui é que o modelo carregou e o pipeline correu sem
        // exceção nativa. Quantos segmentos saem depende do modelo julgar a voz
        // sintética como voz — e isso é decisão dele, não asserção nossa.
        val segmentos = vad.segment(flowOf(*quadros.toTypedArray())).toList()

        android.util.Log.i(
            "ClaryonField",
            "SILERO NO APARELHO: ${segmentos.size} segmento(s); " +
                segmentos.joinToString { "${it.pcm.size} amostras, ${it.silencioFinalMs}ms de silêncio" },
        )
        segmentos.forEach {
            assertEquals(taxa, it.sampleRateHz)
            assertTrue("segmento vazio não deveria ser emitido", it.pcm.isNotEmpty())
            assertTrue("o silêncio final tem de ser declarado", it.silencioFinalMs > 0)
        }
    }

    /**
     * **A prova que o sinal sintético não dá: fala de verdade abre janela.**
     *
     * Uma senoide modulada não é voz para o Silero — e ele está certo, é para isso
     * que ele existe. Mas um teste que só alimenta não-voz passa mesmo se o
     * detector estiver morto, e foi exatamente o que aconteceu na primeira rodada:
     * verde com zero segmentos.
     *
     * A testemunha honesta já está no APK. O Piper sintetiza português de verdade,
     * no aparelho, sem rede — formantes, prosódia e envelope que o Silero foi
     * treinado para reconhecer. É o único jeito de exercitar o caminho feliz sem
     * depender de um `.wav` versionado nem de hardware.
     */
    @Test
    fun falaDeVerdade_abreJanela(): Unit = runBlocking {
        // `Modelos.piper` e NÃO `PiperTts(assets)`: o construtor cru com o
        // diretório errado aborta o processo no nativo. Foi o que derrubou a
        // primeira rodada deste teste, e o motivo do aviso no KDoc do PiperTts.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val piper = Modelos.piper(ctx)
        val dito = piper?.synthesize("Central, guarnição três em deslocamento para a ocorrência.")
        piper?.release()

        val audio = dito?.getOrNull()
        org.junit.Assume.assumeTrue(
            "Piper não carregou neste aparelho; sem ele não há fala real para provar o VAD",
            audio != null,
        )
        audio!!

        // O Silero é treinado a 16 kHz e o Piper gera na taxa da voz (22 050 Hz
        // no faber-medium). Alimentar 22 kHz declarando 16 kHz não dá erro: dá
        // uma fala 1,4× mais aguda, que é justamente o defeito que este projeto
        // já teve no PTT. Reamostra-se.
        val fala = reamostrar(audio.samples, audio.sampleRateHz, taxa)
        val entrada = silencio(300) + fala + silencio(900)

        val vad = SileroVoiceActivityDetector(assets = assets, sampleRateHz = taxa)
        val segmentos = vad.segment(flowOf(*quadrosDe(entrada).toTypedArray())).toList()

        val duracaoMs = fala.size * 1000L / taxa
        android.util.Log.i(
            "ClaryonField",
            "SILERO COM FALA REAL: ${duracaoMs}ms sintetizados → ${segmentos.size} segmento(s); " +
                segmentos.joinToString { "${it.pcm.size * 1000L / taxa}ms" },
        )

        assertTrue("fala real TEM de abrir janela — veio 0 segmento", segmentos.isNotEmpty())
        val falado = segmentos.sumOf { it.pcm.size }.toLong() * 1000 / taxa
        assertTrue(
            "o segmento deveria cobrir a maior parte da fala (\${falado}ms de \${duracaoMs}ms)",
            falado > duracaoMs / 2,
        )
    }

    /** Linear, e é o suficiente: o teste prova detecção, não fidelidade. */
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

    @Test
    fun silencioPuro_naoAbreJanela(): Unit = runBlocking {
        // O ganho do Silero sobre o RMS: ruído de fundo não vira invocação do
        // Whisper. Aqui o silêncio é absoluto — o piso do teste.
        val vad = SileroVoiceActivityDetector(assets = assets, sampleRateHz = taxa)
        val segmentos = vad.segment(flowOf(*quadrosDe(silencio(2000)).toTypedArray())).toList()

        assertTrue("silêncio não pode abrir janela (veio ${segmentos.size})", segmentos.isEmpty())
    }
}
