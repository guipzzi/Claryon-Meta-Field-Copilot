package com.claryon.field

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Earcon
import com.claryon.common.PcmResampler
import com.claryon.common.getOrNull
import com.claryon.field.norma.ConsultaDeNorma
import com.claryon.field.voice.Modelos
import com.claryon.sound.EarconSynthesizer
import com.claryon.voice.DetectorDeAtivacao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Bancada de desperdício: números antes, não hipóteses.**
 *
 * Cada teste aqui mede uma suspeita levantada por leitura e a converte em número
 * — ou a derruba. Nenhum deles altera comportamento; o que consertar sai em diff
 * separado, com o número depois medido pela MESMA função.
 */
@RunWith(AndroidJUnit4::class)
class OficinaDeDesperdicioTest {

    private val app
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun List<Long>.mediana(): Long = sorted()[size / 2]
    private fun List<Long>.p90(): Long {
        val s = sorted()
        return s[(Math.ceil(0.90 * s.size).toInt().coerceIn(1, s.size)) - 1]
    }

    private fun us(ns: Long) = "%.1f µs".format(ns / 1000.0)

    // ═══ TAREFA 2 — o Piper isolado ══════════════════════════════════════════

    /**
     * `PiperTts.synthesize` devolve o `PcmAudio` inteiro: nenhuma amostra sai
     * antes da última ser gerada. A pergunta é se o custo é **de partida** (e aí
     * streaming não compra nada) ou **proporcional** (e aí compra).
     *
     * O teste sintetiza frases de tamanho crescente, N vezes cada, e ajusta uma
     * reta `tempo = a + b · duração_do_áudio`. `a` é o custo fixo; `b` é o inverso
     * do fator de tempo real.
     */
    @Test
    fun oPiperIsolado_custoFixoContraProporcional(): Unit = runBlocking {
        val piper = Modelos.piper(app)
        Assume.assumeTrue("Piper ausente", piper != null)
        piper!!.aquecer()

        val frases = listOf(
            "Sim." to 1,
            "Apoio a caminho." to 3,
            // A resposta REAL da Etapa A: `utteranceFor(NormaEncontrada)` produz
            // "${citacao}, ${norma}".
            "Art. 306, Lei 9.503" to 4,
            "Guarnição avisada, apoio a caminho." to 5,
            "Alfa Dois a trezentos metros, rumo norte." to 7,
            "Gravação iniciada, apoio a caminho, guarnição avisada, aguarde o retorno." to 11,
        )

        // Aquecimento: a primeira síntese depois do `aquecer` ainda tem custo
        // atípico (alocação de arena do ONNX Runtime).
        repeat(3) { piper.synthesize("Aquecimento.") }

        val relatorio = StringBuilder()
        val amostrasParaReta = mutableListOf<Pair<Double, Double>>() // (ms de áudio, ms de síntese)

        for ((texto, palavras) in frases) {
            val tempos = mutableListOf<Long>()
            var amostras = 0
            var taxa = 0
            repeat(REPETICOES_PIPER) {
                val t0 = System.nanoTime()
                val a = piper.synthesize(texto).getOrNull()
                tempos += (System.nanoTime() - t0) / 1_000_000
                if (a != null) { amostras = a.samples.size; taxa = a.sampleRateHz }
            }
            val msDeAudio = amostras * 1000.0 / taxa
            amostrasParaReta += msDeAudio to tempos.mediana().toDouble()
            relatorio.appendLine(
                "  %2d palavra(s) | áudio %6.0f ms | síntese p50 %4d ms p90 %4d ms | RTF %.3f | \"%s\""
                    .format(
                        palavras, msDeAudio, tempos.mediana(), tempos.p90(),
                        tempos.mediana() / msDeAudio, texto.take(40),
                    ),
            )
        }

        // Mínimos quadrados: tempo = a + b · duração.
        val n = amostrasParaReta.size
        val mx = amostrasParaReta.sumOf { it.first } / n
        val my = amostrasParaReta.sumOf { it.second } / n
        val sxy = amostrasParaReta.sumOf { (x, y) -> (x - mx) * (y - my) }
        val sxx = amostrasParaReta.sumOf { (x, _) -> (x - mx) * (x - mx) }
        val b = if (sxx > 0) sxy / sxx else 0.0
        val a = my - b * mx

        // A frase real da Etapa A, para o veredito de streaming.
        val artigo = amostrasParaReta[2]
        val fracaoFixa = if (artigo.second > 0) (a / artigo.second) * 100 else 0.0

        android.util.Log.i(
            TAG,
            """
            |
            |════════ TAREFA 2 — PIPER ISOLADO (${REPETICOES_PIPER} repetições por frase) ════════
            |$relatorio
            |  reta:  síntese ≈ %.0f ms + %.3f × (ms de áudio)
            |  custo FIXO de partida ......... %.0f ms
            |  em "Art. 306, Lei 9.503" ...... %.0f ms de síntese, dos quais %.0f%% é custo fixo
            |  fator de tempo real (b) ....... %.3f  (1/b = %.1f× mais rápido que tempo real)
            |════════════════════════════════════════════════════════════════════
            """.trimMargin().format(a, b, a, artigo.second, fracaoFixa, b, if (b > 0) 1 / b else 0.0),
        )

        assertTrue("nenhuma síntese mediu", amostrasParaReta.all { it.second > 0 })
        piper.release()
    }

    /**
     * A parcela que o marco `RESPONSE_FIRST_AUDIO` **não** cobre: construir o
     * `AudioTrack` e chamar `play()`. Em produção isso acontece depois do marco
     * (`VoiceOutput.play` → `aoIniciarReproducao` → `reproduzir`), então o número
     * do razão é otimista por exatamente isto.
     */
    @Test
    fun oCustoDeAbrirOAudioTrack() {
        val taxa = 16_000
        val tempos = mutableListOf<Long>()
        repeat(20) {
            val minBuf = AudioTrack.getMinBufferSize(
                taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val t0 = System.nanoTime()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(taxa)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, 32_000))
                .build()
            track.play()
            tempos += (System.nanoTime() - t0) / 1_000
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
        android.util.Log.i(
            TAG,
            "AudioTrack.build+play: p50 ${tempos.mediana()} µs · p90 ${tempos.p90()} µs " +
                "(fora do marco RESPONSE_FIRST_AUDIO)",
        )
        assertTrue(tempos.isNotEmpty())
    }

    // ═══ TAREFA 3 — desperdício ══════════════════════════════════════════════

    /**
     * **O earcon é re-sintetizado a cada reprodução.**
     *
     * `PrioritySoundQueue.loop` chama `render(next)` toda vez, e o ramo
     * `Sound.Tone` é `EarconSynthesizer.render(sound.earcon)` — que recalcula
     * `sin()` amostra por amostra. A saída é **função pura do enum**: mesma
     * entrada, mesmo array, sempre. Isso é trabalho repetido por definição.
     *
     * E roda na **Main**: o escopo da fila em `SaidaUnica` é
     * `Dispatchers.Main.immediate`, e `render` é chamado de dentro de `loop()`,
     * que vive nesse escopo. Diferente de `sintetizar` (que salta para `Default`
     * dentro do `PiperTts`) e de `naTaxaDeSaida` (que salta explicitamente), o
     * `EarconSynthesizer.render` não salta para lugar nenhum.
     *
     * Este teste mede o custo por earcon E prova a pureza — que é o que autoriza
     * o cache.
     */
    @Test
    fun oEarconERecalculadoACadaReproducao() {
        val relatorio = StringBuilder()
        var somaP50 = 0L
        for (e in Earcon.entries) {
            repeat(20) { EarconSynthesizer.render(e) } // aquece JIT
            val tempos = (1..50).map {
                val t0 = System.nanoTime()
                EarconSynthesizer.render(e)
                System.nanoTime() - t0
            }
            val n = EarconSynthesizer.render(e).size
            somaP50 += tempos.mediana()
            // Pureza: duas chamadas dão exatamente o mesmo array.
            assertTrue(
                "render($e) não é puro — cache seria mudança de comportamento",
                EarconSynthesizer.render(e).contentEquals(EarconSynthesizer.render(e)),
            )
            relatorio.appendLine(
                "  %-26s %6d amostras (%4d ms de som) | render p50 %s p90 %s"
                    .format(e.name, n, n * 1000 / 16_000, us(tempos.mediana()), us(tempos.p90())),
            )
        }
        android.util.Log.i(
            TAG,
            """
            |
            |════════ EARCON: recalculado a cada reprodução, na Main ════════
            |$relatorio
            |  soma dos oito (p50) ......... ${us(somaP50)}
            |  DESPERTAR e CANAL_ABERTO/CANAL_FECHADO são os do ciclo de voz.
            |════════════════════════════════════════════════════════════════
            """.trimMargin(),
        )
    }

    /**
     * **A palavra de ativação roda 50 quadros/s o turno inteiro.**
     *
     * Mede o custo real por quadro de 320 amostras e converte em CPU por hora.
     * Também separa as duas parcelas: o laço por amostra em `aceitar` (que roda
     * em TODO quadro) e o `avaliar()` (que roda a cada 1280 amostras = 80 ms, e
     * que aloca um `FloatArray(16000)` — 64 KB — a cada chamada só para
     * desenrolar o anel).
     */
    @Test
    fun aPalavraDeAtivacao_custoPorQuadroEPorHora() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val bytes = runCatching {
            assets.open("${DetectorDeAtivacao.ASSETS}/cabeca.f32").use { it.readBytes() }
        }.getOrNull()
        Assume.assumeTrue("cabeça da ativação ausente nos assets do app", bytes != null)
        val cabeca = DetectorDeAtivacao.cabecaDeBytes(bytes!!)
        Assume.assumeTrue("cabeça ilegível", cabeca != null)

        val d = DetectorDeAtivacao(cabeca!!.first, cabeca.second)
        Assume.assumeTrue("extrator ONNX não subiu", d.preparar(assets))

        // Ruído rosa-ish determinístico: não importa o conteúdo, importa o custo.
        val rnd = java.util.Random(7)
        val quadros = (0 until 3_000).map { // 60 s de áudio a 50 quadros/s
            ShortArray(320) { (rnd.nextInt(4000) - 2000).toShort() }
        }

        // Aquecimento: enche o anel de 1 s e passa a avaliar.
        repeat(200) { d.aceitar(quadros[it % quadros.size]) }

        val tempos = mutableListOf<Long>()
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        val t0 = System.nanoTime()
        for (q in quadros) {
            val a = System.nanoTime()
            d.aceitar(q)
            tempos += System.nanoTime() - a
        }
        val paredeNs = System.nanoTime() - t0
        val cpuNs = android.os.Debug.threadCpuTimeNanos() - cpu0
        d.close()

        val segundosDeAudio = quadros.size * 320.0 / 16_000
        val cpuPorSegundo = cpuNs / 1e9 / segundosDeAudio
        // Alocação analítica: 12,5 avaliações/s × FloatArray(16000) × 4 B.
        val avaliacoesPorS = 16_000.0 / DetectorDeAtivacao.PASSO_AMOSTRAS
        val lixoPorS = avaliacoesPorS * DetectorDeAtivacao.AMOSTRAS * 4

        android.util.Log.i(
            TAG,
            """
            |
            |════════ PALAVRA DE ATIVAÇÃO — custo contínuo ════════
            |  quadros medidos ............. ${quadros.size} (%.0f s de áudio)
            |  por quadro (320 am., 20 ms) . p50 %s   p90 %s   máx %s
            |  parede total ................ %.0f ms para %.0f s de áudio
            |  CPU da thread ............... %.0f ms  →  %.1f%% de UM núcleo
            |  extrapolado ................. %.0f s de CPU por HORA de escuta
            |  lixo do desenrolar do anel .. 0 KB/s — CONSERTADO em 21/08.
            |    O buffer da janela virou campo (`janelaDesenrolada`), alocado uma vez.
            |    Antes: %.0f KB/s analíticos — FloatArray(%d) por avaliação, %.1f/s.
            |    Este número era CALCULADO da forma do código, não medido: quando a
            |    alocação saiu, a linha continuou imprimindo o valor antigo. Fica como
            |    registro do que foi removido, e não como medida do que existe.
            |═════════════════════════════════════════════════════
            """.trimMargin().format(
                segundosDeAudio,
                us(tempos.mediana()), us(tempos.p90()), us(tempos.max()),
                paredeNs / 1e6, segundosDeAudio,
                cpuNs / 1e6, cpuPorSegundo * 100,
                cpuPorSegundo * 3600,
                lixoPorS / 1024, DetectorDeAtivacao.AMOSTRAS, avaliacoesPorS,
            ),
        )
        assertTrue(tempos.isNotEmpty())
    }

    /**
     * **O índice lexical: uma vez por processo, ou uma por consulta?**
     *
     * `ConsultaDeNorma.base` é `by lazy` num `object`, e `BaseDeConhecimentoLexical.indice`
     * é `by lazy` também — dois níveis. A pergunta é empírica: se a 2ª consulta
     * custar o mesmo que a 1ª, o índice está sendo remontado.
     */
    @Test
    fun oIndiceLexical_montadoUmaVezPorProcesso(): Unit = runBlocking {
        val perguntas = listOf(
            "qual artigo trata de embriaguez ao volante",
            "que diz a lei sobre desacato",
            "qual o artigo de porte ilegal de arma",
            "o que preve a lei sobre trafico de drogas",
            "que artigo fala de direcao perigosa",
        )

        val t0 = System.nanoTime()
        ConsultaDeNorma.consultar(perguntas[0])
        val primeira = (System.nanoTime() - t0) / 1_000

        val seguintes = mutableListOf<Long>()
        repeat(200) { i ->
            val t = System.nanoTime()
            ConsultaDeNorma.consultar(perguntas[i % perguntas.size])
            seguintes += (System.nanoTime() - t) / 1_000
        }

        android.util.Log.i(
            TAG,
            """
            |
            |════════ ÍNDICE LEXICAL (Etapa A) ════════
            |  trechos indexados ........... ${ConsultaDeNorma.trechosIndexados}
            |  1ª consulta desta bancada ... ${primeira / 1000.0} ms
            |    ⚠️ NÃO é mais "com montagem": desde 21/08 `ClaryonApp.onCreate` chama
            |    `ConsultaDeNorma.aquecerNoBoot`, e o índice já está quente quando esta
            |    bancada roda. Para ver o custo real da montagem, é preciso um processo
            |    sem o aquecimento. Medido antes do conserto: 4855 ms, contra 16,4 ms
            |    das consultas seguintes — razão de 296×.
            |  consultas 2..201 ............ p50 ${us(seguintes.mediana() * 1000)}  p90 ${us(seguintes.p90() * 1000)}
            |  razão 1ª / p50 .............. ${"%.0f".format(primeira.toDouble() / seguintes.mediana().coerceAtLeast(1))}×
            |═════════════════════════════════════════
            """.trimMargin(),
        )

        assertTrue("índice vazio — o corpus não chegou ao APK", ConsultaDeNorma.trechosIndexados > 0)
        assertTrue(
            "a 2ª consulta custou o mesmo que a 1ª: o índice está sendo remontado",
            seguintes.mediana() * 10 < primeira,
        )
    }

    /**
     * **PCM de ida e volta entre Short e Float.**
     *
     * O caminho do comando converte o mesmo áudio quatro vezes:
     *   captura Short → Silero (Short→Float, `SileroVoiceActivityDetector:135`)
     *   → segmento (Float→Short, `:172`) → whisper (Short→Float, `WhisperCppStt:77`).
     * Este teste mede o custo de cada conversão sobre um segmento típico, para
     * dizer se a redundância vale conserto ou é ruído.
     */
    @Test
    fun asConversoesDePcm_custamQuanto() {
        val n = 16_000 * 3 // 3 s de fala, o tamanho de um comando
        val curto = ShortArray(n) { (it % 3000 - 1500).toShort() }
        val flutuante = FloatArray(n) { it / 100f }

        fun mede(nome: String, bloco: () -> Unit): String {
            repeat(20) { bloco() }
            val t = (1..50).map { val a = System.nanoTime(); bloco(); System.nanoTime() - a }
            return "  %-38s p50 %s".format(nome, us(t.mediana()))
        }

        val linhas = listOf(
            mede("Short→Float (3 s)") { FloatArray(n) { i -> curto[i] / 32768f } },
            mede("Float→Short (3 s)") {
                ShortArray(n) { i -> (flutuante[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            },
            mede("resample 22050→16000 (3 s, FIR 63)") {
                PcmResampler.resample(curto, 22_050, 16_000)
            },
            mede("resample 16000→16000 (no-op?)") {
                PcmResampler.resample(curto, 16_000, 16_000)
            },
        )
        android.util.Log.i(
            TAG,
            "\n════════ CONVERSÕES DE PCM (3 s de áudio) ════════\n" +
                linhas.joinToString("\n") +
                "\n══════════════════════════════════════════════════",
        )
        assertEquals(4, linhas.size)
    }

    private companion object {
        const val TAG = "ClaryonField"
        const val REPETICOES_PIPER = 12
    }
}
