package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Por que a Alexa é chamada e responde, e por que este caminho NÃO resolve isso.**
 *
 * A Alexa não transcreve: ela usa um detector dedicado, que devolve **escore** e não
 * texto. O `KeywordSpotter` do sherpa-onnx é dessa família, e por isso ele foi a
 * primeira tentativa. **A medição reprovou o caminho, e reprovou também as duas
 * hipóteses que eu levantei para salvá-lo.**
 *
 * ## O que foi medido, em ordem
 *
 * | | resultado |
 * |---|---|
 * | Controle canônico: `0.wav`/`1.wav` do próprio modelo, inglês real, banda cheia | **3/3** |
 * | Piper pt-BR dizendo **"Alexa"** — a chave de fábrica do modelo — banda cheia | **0/4** |
 * | `Claryon` em 6 grafias × 2 bandas | **0/8 em todas** |
 * | Grade de 9 pontos, até limiar 0,02 e bônus 5,0 | **0/8**; só "Alexa" aparece 1/4 no canto extremo |
 *
 * ## As duas hipóteses que morreram aqui
 *
 * **"A grafia estava errada."** Eu escrevi `▁C LA RY ON` — a grafia da marca — e
 * argumentei que o modelo inglês leria *KLAR-yon* enquanto o agente diz /kla.ˈɾi.õ/.
 * A correção parecia óbvia: `CLARION` é palavra inglesa real, quase homófona, e era
 * justamente o que o whisper vinha transcrevendo. **Deu 0/8 igual**, assim como as
 * outras quatro grafias.
 *
 * **"A banda estreita de 8 kHz é que mata."** Também não: banda cheia dá zero pelo
 * mesmo tanto. O HFP não tem culpa nesta.
 *
 * ## O que a linha do controle prova, e é ela que decide
 *
 * O modelo detecta **3/3** em fala inglesa real e **0/4** na voz do Piper pt-BR —
 * inclusive a chave `▁A LE X A`, que é de fábrica. Um modelo que não reconhece a
 * própria Alexa nesta voz não está medindo `Claryon`: está **fora de domínio**.
 * Nenhuma conclusão sobre a palavra pode sair daqui, para bem ou para mal.
 *
 * O `KeywordSpotter` é KWS **por texto**: não treina, casa tokens BPE contra um
 * modelo acústico da língua dele. Só existem presets de inglês e mandarim, e é isso
 * que fecha o caminho — não a marca, não a grafia, não o HFP.
 *
 * ## Por que este arquivo continua no repositório
 *
 * Porque o caminho é o primeiro que qualquer um tenta ao ler "faça como a Alexa", e
 * sem esta medição ele será tentado de novo. O controle canônico fica como asserção
 * dura: se um dia um modelo pt-BR aparecer, é só trocar os `.onnx` e o arquivo já
 * sabe dizer se o instrumento presta antes de reportar qualquer número.
 *
 * A conclusão de produto está em [`docs/AVALIACAO_DAS_PROPOSTAS_FASE_2.md`].
 */
@RunWith(AndroidJUnit4::class)
class KwsDeClaryonTest {

    private val taxa = 16_000
    private val hfpHz = 8_000

    /** Contexto do APP: onde vivem o Piper e o whisper. */
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Contexto da INSTRUMENTAÇÃO: onde vive o modelo de KWS.
     *
     * O modelo fica no APK **de teste**, não no do produto — só entra em `src/main`
     * no dia em que a adoção for decidida. Ler do contexto errado faz o sherpa-onnx
     * chamar `abort()` no nativo (`Read binary file ... failed`) e o processo morre
     * sem exceção que o Kotlin possa pegar — mesmo comportamento já visto no Piper.
     */
    private val ctxDoTeste get() = InstrumentationRegistry.getInstrumentation().context

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    private fun detector(threshold: Float = 0.25f, score: Float = 1.5f) = KeywordSpotter(
        assetManager = ctxDoTeste.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = taxa, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.int8.onnx",
                    decoder = "kws/decoder.int8.onnx",
                    joiner = "kws/joiner.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                numThreads = 1,
                modelType = "zipformer2",
            ),
            keywordsFile = "kws/keywords.txt",
            keywordsScore = score,
            keywordsThreshold = threshold,
        ),
    )

    /**
     * Passa o PCM pelo detector com um conjunto de chaves **deste stream**.
     *
     * `createStream(String)` — confirmado por `javap` no AAR — aceita as chaves por
     * stream, então uma varredura de grafias não precisa reescrever asset nem
     * reconstruir o modelo a cada candidata.
     */
    private fun detectar(kws: KeywordSpotter, chaves: String, pcm: ShortArray): List<String> {
        val stream = kws.createStream(chaves)
        val achados = mutableListOf<String>()
        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        var i = 0
        val passo = taxa / 10 // 100 ms
        while (i < floats.size) {
            val fim = minOf(i + passo, floats.size)
            stream.acceptWaveform(floats.copyOfRange(i, fim), taxa)
            while (kws.isReady(stream)) {
                kws.decode(stream)
                val r = kws.getResult(stream)
                if (r.keyword.isNotEmpty()) {
                    achados += r.keyword
                    kws.reset(stream)
                }
            }
            i = fim
        }
        stream.inputFinished()
        while (kws.isReady(stream)) {
            kws.decode(stream)
            val r = kws.getResult(stream)
            if (r.keyword.isNotEmpty()) achados += r.keyword
        }
        stream.release()
        return achados
    }

    /**
     * Lê um WAV PCM 16 bits das assets do APK de teste.
     *
     * Procura o chunk `data` em vez de assumir cabeçalho de 44 bytes: WAV com
     * `LIST`/`INFO` antes do `data` é comum e o salto fixo leria lixo como áudio.
     */
    private fun lerWav(nome: String): ShortArray {
        val b = ctxDoTeste.assets.open(nome).use { it.readBytes() }
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val tam = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(tam, b.size - i - 8) / 2
                return ShortArray(n) { k ->
                    val p = i + 8 + k * 2
                    (((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)).toShort()
                }
            }
            i += 8 + tam + (tam and 1)
        }
        return ShortArray(0)
    }

    /**
     * **O controle canônico: os áudios do próprio modelo, com verdade documentada.**
     *
     * `trans.txt` diz o que cada WAV contém e `test_keywords.txt` diz o que deve
     * disparar. É fala inglesa real, 16 kHz, banda cheia — o caso que o autor do
     * modelo garante. Se isto não dispara, o defeito é meu: laço de decodificação,
     * `modelType`, ou os `.onnx` que eu renomeei. Nenhum número sobre Claryon vale
     * antes desta linha fechar.
     */
    private val controleCanonico = listOf(
        Triple("kws/0.wav", "▁ L IGHT ▁UP", "…yellow lamps would LIGHT UP here and there…"),
        Triple("kws/1.wav", "▁LOVE LY ▁CHI L D", "…had given her a LOVELY CHILD whose place…"),
        Triple("kws/1.wav", "▁FOR E VER", "…to connect her parent FOR EVER with the race…"),
    )

    /** Grafias candidatas da chave acústica, e o som que cada uma pede ao modelo. */
    private val grafias = listOf(
        "▁C LA RY ON" to "CLARYON — a grafia da marca; foi a primeira tentativa",
        "▁C LA RI ON" to "CLARION — palavra inglesa real, o que o whisper já ouvia",
        "▁C LA RE ON" to "CLAREON — a variante que o decodificador produziu 2 de 6 vezes",
        "▁C LA R I ON" to "CLARION com a tônica separada, ataque mais marcado",
        "▁K LA RI ON" to "KLARION — oclusiva explícita, sem o dígrafo ambíguo",
        "▁C LA RI O N" to "CLARION com a coda aberta, para a nasal do pt-BR",
    )

    /** O par, nas duas grafias que sobrevivem ao teste de palavra isolada. */
    private val grafiasDoPar = listOf(
        "▁HE Y ▁C LA RI ON" to "HEY CLARION",
        "▁HE Y ▁C LA RY ON" to "HEY CLARYON",
    )

    @Test
    fun aGrafiaDaChaveEDaPronunciaNaoDaMarca(): Unit = runBlocking {
        val comandos = listOf(
            "Claryon, mudar para guarnição 3.",
            "Claryon, mudar para guarnição 4.",
            "Claryon, onde está a guarnição 3?",
            "Claryon, pedir apoio.",
            "Claryon, modo ocorrência.",
            "Claryon, iniciar gravação.",
            "Claryon, consultar placa.",
            "Claryon, detalhar.",
        )
        val comPar = listOf(
            "Hey Claryon, mudar para guarnição 3.",
            "Hey Claryon, iniciar gravação.",
            "Hey Claryon, consultar placa.",
            "Hey Claryon, pedir apoio.",
        )
        // Fala operacional que NÃO é comando. As três primeiras são as vizinhas
        // acústicas de CLARION e existem exatamente para punir a grafia nova.
        val ambiente = listOf(
            "Ele clareou a situação para o comandante ontem.",
            "A claridade do dia ajudou na identificação.",
            "É clara a necessidade de apoio nesta ocorrência.",
            "Central, aqui é a guarnição dois, estamos no local.",
            "Negativo, sem alteração no perímetro, na escuta.",
            "O veículo seguiu sentido bairro pela avenida.",
            "Atenção todas as unidades, ocorrência na área central.",
            "A perseguição terminou perto do posto de gasolina.",
        )
        // O CONTROLE: chaves de fábrica deste modelo, ditas pela mesma voz.
        val controles = listOf(
            "Alexa, tocar música." to "▁A LE X A",
            "Alexa." to "▁A LE X A",
            "Hello world." to "▁HE LL O ▁WORLD",
            "Play music." to "▁PLAY ▁MU S IC",
        )

        // ── Fase 1: sintetizar tudo e soltar o Piper (os dois modelos juntos
        // estouram o LMK e o processo morre com signal 9). ────────────────────
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        // Cada frase guarda as DUAS bandas: cheia e 8 kHz. É o que separa "a voz
        // pt-BR está fora do domínio do modelo inglês" de "o HFP é que mata".
        suspend fun falar(t: String): Pair<ShortArray, ShortArray>? =
            piper!!.synthesize(t).getOrNull()?.let {
                val cheia = PcmResampler.resample(it.samples, it.sampleRateHz, taxa)
                cheia to porHfp(cheia)
            }

        val audioControle = controles.mapNotNull { (f, k) -> falar(f)?.let { Triple(f, k, it) } }
        val audioComando = comandos.mapNotNull { f -> falar(f)?.let { f to it } }
        val audioPar = comPar.mapNotNull { f -> falar(f)?.let { f to it } }
        val audioAmbiente = ambiente.mapNotNull { f -> falar(f)?.let { f to it } }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", audioComando.isNotEmpty())

        val kws = detector()
        val linhas = StringBuilder()
        try {
            // ── Seção 0: CONTROLE CANÔNICO (fala inglesa real, banda cheia) ──
            var canonicoOk = 0
            for ((wav, chave, trecho) in controleCanonico) {
                val pcm = lerWav(wav)
                val r = if (pcm.isEmpty()) emptyList() else detectar(kws, chave, pcm)
                if (r.isNotEmpty()) canonicoOk++
                linhas.append(
                    "\n    %-18s %-24s → %s   %s".format(
                        wav.removePrefix("kws/"), chave,
                        if (r.isNotEmpty()) "DETECTOU" else "—",
                        if (pcm.isEmpty()) "(WAV VAZIO)" else "${pcm.size / taxa}s · $trecho".take(52),
                    ),
                )
            }
            linhas.append("\n")

            // ── Seção 1: CONTROLE pela voz do Piper, em banda estreita ───────
            var controleOk = 0
            for ((frase, chave, bandas) in audioControle) {
                val cheia = detectar(kws, chave, bandas.first).isNotEmpty()
                val estreita = detectar(kws, chave, bandas.second).isNotEmpty()
                if (cheia || estreita) controleOk++
                linhas.append(
                    "\n    %-18s em \"%-22s banda cheia %s   8 kHz %s".format(
                        chave, frase + '"', if (cheia) "DETECTOU" else "—", if (estreita) "DETECTOU" else "—",
                    ),
                )
            }

            // ── Seção 2: as grafias de Claryon ───────────────────────────────
            val placar = LinkedHashMap<String, Triple<Int, Int, String>>()
            for ((chave, porque) in grafias) {
                var cheia = 0
                var det = 0
                for ((_, b) in audioComando) {
                    if (detectar(kws, chave, b.first).isNotEmpty()) cheia++
                    if (detectar(kws, chave, b.second).isNotEmpty()) det++
                }
                var falsos = 0
                val onde = mutableListOf<String>()
                for ((frase, b) in audioAmbiente) {
                    if (detectar(kws, chave, b.second).isNotEmpty()) {
                        falsos++
                        onde += frase.take(30)
                    }
                }
                placar[chave] = Triple(det, falsos, porque)
                linhas.append(
                    "\n    %-14s banda cheia %d/%-2d   8 kHz %d/%-2d   falso positivo %d/%-2d %s".format(
                        chave, cheia, audioComando.size, det, audioComando.size,
                        falsos, audioAmbiente.size,
                        if (onde.isEmpty()) "" else "← " + onde.joinToString(" · "),
                    ),
                )
            }

            // ── Seção 3: o par "Hey Claryon" ─────────────────────────────────
            val placarPar = LinkedHashMap<String, Pair<Int, Int>>()
            for ((chave, _) in grafiasDoPar) {
                var cheia = 0
                var det = 0
                for ((_, b) in audioPar) {
                    if (detectar(kws, chave, b.first).isNotEmpty()) cheia++
                    if (detectar(kws, chave, b.second).isNotEmpty()) det++
                }
                var falsos = 0
                for ((_, b) in audioAmbiente) if (detectar(kws, chave, b.second).isNotEmpty()) falsos++
                placarPar[chave] = det to falsos
                linhas.append(
                    "\n    %-20s banda cheia %d/%-2d   8 kHz %d/%-2d   falso positivo %d/%d".format(
                        chave, cheia, audioPar.size, det, audioPar.size, falsos, audioAmbiente.size,
                    ),
                )
            }

            // ── Seção 4: o modelo só precisa de folga? ───────────────────────
            // `keywordsThreshold` é o piso de probabilidade e `keywordsScore` é o
            // bônus somado ao log-prob de cada token da chave. Se o padrão está lá
            // e só não passa do corte, afrouxar os dois faz aparecer. Se nem no
            // canto mais permissivo da grade aparece, o padrão não está lá — e aí
            // a conclusão é sobre o modelo, não sobre o ajuste.
            linhas.append("\n")
            for (thr in listOf(0.25f, 0.10f, 0.02f)) {
                for (sc in listOf(1.5f, 3.0f, 5.0f)) {
                    val d = detector(threshold = thr, score = sc)
                    try {
                        var claryon = 0
                        for ((_, b) in audioComando) {
                            if (detectar(d, "▁C LA RI ON", b.first).isNotEmpty()) claryon++
                        }
                        var alexa = 0
                        for ((_, _, b) in audioControle) {
                            if (detectar(d, "▁A LE X A", b.first).isNotEmpty()) alexa++
                        }
                        var falsos = 0
                        for ((_, b) in audioAmbiente) {
                            if (detectar(d, "▁C LA RI ON", b.first).isNotEmpty()) falsos++
                        }
                        linhas.append(
                            "\n    limiar %.2f · bônus %.1f → CLARION %d/%-2d  ALEXA %d/%-2d  falso positivo %d/%d"
                                .format(thr, sc, claryon, audioComando.size, alexa,
                                    audioControle.size, falsos, audioAmbiente.size),
                        )
                    } finally {
                        d.release()
                    }
                }
            }

            val melhor = placar.entries.filter { it.value.second == 0 }.maxByOrNull { it.value.first }
            android.util.Log.i(
                "ClaryonField",
                """
                |KWS DEDICADO — a grafia da chave é da PRONÚNCIA, não da marca
                |  kws-zipformer-gigaspeech-3.3M (5,0 MiB), banda estreita 8 kHz
                |$linhas
                |
                |  controle CANÔNICO (inglês real, banda cheia) .. $canonicoOk/${controleCanonico.size} ${
                    if (canonicoOk > 0) "(instrumento validado)" else "(INSTRUMENTO NÃO VALIDADO)"
                }
                |  controle pelo Piper (pt-BR, 8 kHz) ............ $controleOk/${audioControle.size}
                |  melhor grafia com ZERO falso positivo: ${melhor?.key ?: "NENHUMA"} ${
                    melhor?.let { "→ ${it.value.first}/${audioComando.size} = ${
                        "%.0f".format(it.value.first * 100.0 / audioComando.size)
                    }%" } ?: ""
                }
                |
                |  Linha de base pelo TRANSCRITOR, mesmas condições:
                |    "Hey Claryon" + lista de 7 variantes ....... 40,0 %
                |    "Claryon" sozinho, grafia canônica ......... 0,0 %
                |
                |  ⚠️ Voz do Piper: sem sotaque, sem hesitação, sem AGC de uplink.
                """.trimMargin(),
            )

            // A trava que impede este arquivo de reportar 0/8 outra vez sem saber
            // se a culpa era da palavra ou do harness.
            assertTrue(
                "o controle CANÔNICO não disparou ($canonicoOk/${controleCanonico.size}): fala inglesa " +
                    "real, banda cheia, com verdade documentada pelo autor do modelo. O defeito é do " +
                    "harness — laço de decodificação, modelType ou os .onnx renomeados — e nenhum " +
                    "número sobre Claryon vale enquanto esta linha não fechar",
                canonicoOk > 0,
            )
        } finally {
            kws.release()
        }
    }
}
