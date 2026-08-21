package com.claryon.field

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.ActionOutcome
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.IntentRouter
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.norma.ConsultaDeNorma
import com.claryon.field.voice.EscutaDoAgente
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.SileroVoiceActivityDetector
import com.claryon.field.voice.VoiceCycle
import com.claryon.field.voice.VoiceOutput
import com.claryon.sound.Sound
import com.claryon.voice.PcmAudio
import com.claryon.voice.PiperTts
import com.claryon.voice.SpeechSegment
import com.claryon.voice.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O razão do ciclo de voz: onde vão os 4 s do aceite da Fase 4.**
 *
 * `CicloDeVozNoAparelhoTest` já provava que os marcos de `SaidaUnica` existem —
 * mas com **uma** amostra, com o PCM despejado no VAD o mais rápido que o `Flow`
 * aceita, e com a decomposição parando em *"síntese + rota + fila"*, uma parcela
 * só. Este teste responde três perguntas que aquele não responde:
 *
 *  1. **Quanto, com mediana e p90 sobre 10 ciclos.** Uma medição não é medição.
 *  2. **Onde**, trecho a trecho: `fim da fala → VAD fecha → STT devolve →
 *     roteador → recuperação → síntese Piper → PCM entregue à reprodução`.
 *  3. **Qual parcela é do produto e qual é da bancada**, declarado abaixo.
 *
 * ## O que muda em relação à bancada anterior, e por que muda o número
 *
 * `quadrosCom` (naquele teste) faz `tudo.chunked(320).asFlow()`: os 1,25 s de
 * silêncio final atravessam o VAD em microssegundos de parede. O `hangover` de
 * 300 ms nunca acontece no relógio, e `fim da fala → earcon` vira ficção — mede o
 * *custo de CPU* do Silero sobre o silêncio, não a espera que o agente sente.
 *
 * Aqui o PCM é entregue **em tempo real**, um quadro de 320 amostras a cada 20 ms,
 * com o instante do fim da fala calculado da posição da última amostra falada. É
 * o que a captura HFP faz, e é a única forma de o 300 ms do
 * `silencioParaFecharS` aparecer como o que é.
 *
 * ## O que este teste NÃO mede, dito antes do número
 *
 * `RESPONSE_FIRST_AUDIO` **não é a primeira amostra no alto-falante**, nem aqui
 * nem em produção. `VoiceOutput.play` chama `aoIniciarReproducao(sound)` e só
 * DEPOIS `reproduzir(...)` — que em produção é
 * `SaidaUnica.reproduzirComRotaESupressao`, onde a rota SCO sobe e o `AudioTrack`
 * é construído. O marco é *"PCM pronto e entregue à reprodução"*. A parcela que
 * falta está medida em [OficinaDeDesperdicioTest.oCustoDeAbrirOAudioTrack].
 *
 * Por isso a fila deste teste é uma [VoiceOutput] própria e não a `SaidaUnica`:
 * são as MESMAS classes com a MESMA fiação (`PrioritySoundQueue` no escopo
 * `Main.immediate`, Piper real, `AudioDoAgente` real), e o `sintetizar` fica
 * observável — sem isso, "síntese Piper" e "fila" continuam sendo uma parcela só.
 * A diferença declarada: sem `RotaSustentada` e sem `SupressorDeSaidaPropria`, os
 * dois **depois** do marco.
 */
@RunWith(AndroidJUnit4::class)
class RazaoDoCicloDeVozTest {

    private val taxa = 16_000

    private val app
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    /**
     * O comando falado. Precisa casar `CONSULTAR_NORMA` do
     * `DeterministicIntentRouter` **depois** de passar pelo whisper — "qual
     * artigo" é o marcador; o resto é o que o índice lexical usa para achar.
     */
    private val comando = "Qual artigo trata de embriaguez ao volante"

    private class Marcos {
        var fimDaFala = 0L
        var vadFechou = 0L
        var sttInicio = 0L
        var sttFim = 0L
        var roteado = 0L
        var recupInicio = 0L
        var recupFim = 0L
        var acaoFim = 0L
        var sinteseInicio = 0L
        var sinteseFim = 0L
        var earcon = 0L
        var resposta = 0L
        var transcricao = ""
        var falado = ""
    }

    // ── Bancada ───────────────────────────────────────────────────────────────

    /**
     * Um quadro de 320 amostras a cada 20 ms, como a captura entrega.
     *
     * O instante do fim da fala é **calculado**, não observado: sai da posição da
     * última amostra falada dentro da linha do tempo do quadro que a contém. É
     * mais exato que cravar no `emit` — o quadro tem 20 ms e a fala termina em
     * algum ponto dentro dele.
     */
    private fun fluxoEmTempoReal(
        fala: ShortArray,
        silencioInicialMs: Int,
        silencioFinalMs: Int,
        aoFimDaFala: (Long) -> Unit,
    ): Flow<ShortArray> = flow {
        val quadro = 320
        val pre = taxa * silencioInicialMs / 1000
        val pos = taxa * silencioFinalMs / 1000
        val total = pre + fala.size + pos
        val ultimaAmostraFalada = pre + fala.size - 1

        val t0 = System.nanoTime()
        var i = 0
        while (i * quadro < total) {
            val inicio = i * quadro
            val n = minOf(quadro, total - inicio)
            val q = ShortArray(n)
            for (j in 0 until n) {
                val abs = inicio + j
                q[j] = if (abs in pre until pre + fala.size) fala[abs - pre] else 0
            }
            val alvoNs = t0 + inicio.toLong() * 1_000_000_000L / taxa
            val esperaMs = (alvoNs - System.nanoTime()) / 1_000_000
            if (esperaMs > 0) delay(esperaMs)
            emit(q)
            if (ultimaAmostraFalada in inicio until inicio + n) {
                // Instante do emit + o resto do quadro até a última amostra falada.
                aoFimDaFala(
                    System.currentTimeMillis() +
                        (ultimaAmostraFalada - inicio) * 1000L / taxa,
                )
            }
            i++
        }
    }

    private fun List<Long>.mediana(): Long = sorted()[size / 2]

    /** Nearest-rank: p90 de 10 amostras é a 9ª menor, não a maior. */
    private fun List<Long>.p90(): Long {
        val s = sorted()
        val posto = Math.ceil(0.90 * s.size).toInt().coerceIn(1, s.size)
        return s[posto - 1]
    }

    private fun linha(rotulo: String, v: List<Long>): String {
        if (v.isEmpty()) return "  ${rotulo.padEnd(34, '.')} sem amostra"
        return "  ${rotulo.padEnd(34, '.')} p50 %5d ms   p90 %5d ms   [%d..%d]"
            .format(v.mediana(), v.p90(), v.min(), v.max())
    }

    // ── O razão ───────────────────────────────────────────────────────────────

    @Test
    fun oRazaoDoCicloDeVoz_dezCiclosComFalaReal(): Unit = runBlocking {
        val piper = Modelos.piper(app)
        Assume.assumeTrue("Piper ausente: sem fala não há ciclo", piper != null)
        val whisper = EscutaDoAgente.de(app)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        // ── Aquecimento: tudo o que produção aquece, aquecido aqui ────────────
        // `PiperTts.aquecer` é o que `SaidaUnica.aquecer` chama; `EscutaDoAgente`
        // mantém o whisper quente entre ciclos (é dono de processo). O índice
        // lexical é `by lazy` num `object` — a primeira consulta paga a montagem.
        piper!!.aquecer()
        val falaCrua = piper.synthesize(comando).getOrNull()
        Assume.assumeTrue("Piper não sintetizou o comando", falaCrua != null)
        val fala = PcmResampler.resample(falaCrua!!.samples, falaCrua.sampleRateHz, taxa)

        whisper!!.transcribe(fala, taxa) // carrega o contexto nativo fora da medição
        ConsultaDeNorma.consultar("aquecimento do indice lexical")

        val audio = AudioDoAgente.de(app)
        val escopoDaFila = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val roteadorReal = DeterministicIntentRouter()
        val vadReal = SileroVoiceActivityDetector(assets = app.assets, sampleRateHz = taxa)

        val ciclos = mutableListOf<Marcos>()
        val volta = 12 // 2 descartadas + 10 medidas

        repeat(volta) { n ->
            val m = Marcos()

            val saida = VoiceOutput(
                scope = escopoDaFila,
                sintetizar = { texto ->
                    m.sinteseInicio = System.currentTimeMillis()
                    m.falado = texto
                    (piper.synthesize(texto) as? Result.Success)?.value
                        .also { m.sinteseFim = System.currentTimeMillis() }
                },
                reproduzir = { pcm, sr -> audio.reproduzir(pcm, sr) },
                aoIniciarReproducao = { som ->
                    val agora = System.currentTimeMillis()
                    when (som) {
                        is Sound.Tone -> if (m.earcon == 0L) m.earcon = agora
                        is Sound.Speech -> if (m.resposta == 0L) m.resposta = agora
                    }
                },
            )

            val vad = object : VoiceActivityDetector {
                override fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment> =
                    vadReal.segment(pcm).onEach { m.vadFechou = System.currentTimeMillis() }
            }

            val roteador = object : IntentRouter {
                override fun route(transcricao: String): Intent =
                    roteadorReal.route(transcricao).also { m.roteado = System.currentTimeMillis() }
            }

            // O MESMO ramo do `ClaryonIntentExecutor` para `ConsultarNorma`
            // (`ClaryonIntentExecutor.kt:251-254`), com o relógio em volta da
            // recuperação. Os outros ramos do executor não entram no caminho
            // desta pergunta e trariam cofre, despachante e rede para a bancada.
            val executor = object : IntentExecutor {
                override suspend fun execute(intent: Intent): ActionOutcome {
                    m.recupInicio = System.currentTimeMillis()
                    val achado = (intent as? Intent.ConsultarNorma)
                        ?.let { ConsultaDeNorma.consultar(it.pergunta) }
                    m.recupFim = System.currentTimeMillis()
                    m.acaoFim = m.recupFim
                    return when {
                        intent !is Intent.ConsultarNorma -> ActionOutcome.NaoEntendi
                        achado == null -> ActionOutcome.NormaNaoEncontrada
                        else -> ActionOutcome.NormaEncontrada(achado.first, achado.second)
                    }
                }
            }

            val ciclo = VoiceCycle(
                pcmInput = {
                    fluxoEmTempoReal(
                        fala = fala,
                        silencioInicialMs = 200,
                        // > 300 ms do `silencioParaFecharS` com folga para o
                        // Silero decidir e para o `drenar` emitir.
                        silencioFinalMs = 1_200,
                        aoFimDaFala = { m.fimDaFala = it },
                    )
                },
                vad = vad,
                sttFn = { pcm, sr ->
                    m.sttInicio = System.currentTimeMillis()
                    val t = (whisper.transcribe(pcm, sr) as? Result.Success)?.value?.text.orEmpty()
                    m.sttFim = System.currentTimeMillis()
                    t
                },
                router = roteador,
                executor = executor,
                emitir = { u -> saida.emitir(u) },
                sampleRateHz = taxa,
            )

            val r = withContext(Dispatchers.Default) {
                withTimeoutOrNull(60_000) { ciclo.runOnce() }
            }
            assertTrue("ciclo $n não fechou em 60 s", r != null)
            m.transcricao = r!!.transcricao

            // A fala é enfileirada; o marco de reprodução só existe quando a fila
            // chega nela. Sem esta espera o ciclo seguinte começaria por cima.
            val prazo = System.currentTimeMillis() + 30_000
            while (m.resposta == 0L && System.currentTimeMillis() < prazo) delay(50)
            // E espera a reprodução drenar, senão o AudioTrack do ciclo seguinte
            // disputa o mesmo dispositivo.
            delay(500)

            if (n >= 2) ciclos += m
            android.util.Log.i(
                TAG,
                "ciclo $n: \"${m.transcricao}\" → \"${m.falado}\"  " +
                    "fim→resposta=${m.resposta - m.fimDaFala} ms",
            )
        }

        // ── O razão ───────────────────────────────────────────────────────────
        val vadFecha = ciclos.map { it.vadFechou - it.fimDaFala }
        val vadCompute = ciclos.map { it.vadFechou - it.fimDaFala - 300 }
        val stt = ciclos.map { it.sttFim - it.sttInicio }
        val entreVadESttt = ciclos.map { it.sttInicio - it.vadFechou }
        val roteador = ciclos.map { it.roteado - it.sttFim }
        val recuperacao = ciclos.map { it.recupFim - it.recupInicio }
        val ateSintese = ciclos.map { it.sinteseInicio - it.acaoFim }
        val sintese = ciclos.map { it.sinteseFim - it.sinteseInicio }
        val posSintese = ciclos.map { it.resposta - it.sinteseFim }
        val earcon = ciclos.map { it.earcon - it.fimDaFala }
        val total = ciclos.map { it.resposta - it.fimDaFala }
        val duracaoDaResposta = ciclos.map { it.sinteseFim - it.sinteseInicio }

        android.util.Log.i(
            TAG,
            """
            |
            |════════ RAZÃO DO CICLO DE VOZ — ${ciclos.size} ciclos, fala real, emulador ════════
            |  comando falado ........... "$comando"
            |  transcrito ............... "${ciclos.first().transcricao}"
            |  resposta falada .......... "${ciclos.first().falado}"
            |  amostras do comando ...... ${fala.size} (${fala.size * 1000L / taxa} ms de fala)
            |
            |${linha("fim da fala → VAD fecha", vadFecha)}
            |${linha("  └─ acima do hangover 300 ms", vadCompute)}
            |${linha("VAD fecha → STT começa", entreVadESttt)}
            |${linha("STT (whisper, custo real)", stt)}
            |${linha("STT → roteador", roteador)}
            |${linha("recuperação (índice lexical)", recuperacao)}
            |${linha("ação → síntese começa (fila)", ateSintese)}
            |${linha("síntese Piper", sintese)}
            |${linha("síntese → PCM na reprodução", posSintese)}
            |  ────────────────────────────────────────────────────────────────
            |${linha("FIM DA FALA → RESPOSTA (aceite ≤ 4000)", total)}
            |${linha("fim da fala → earcon (meta ≤ 500)", earcon)}
            |
            |  duração do áudio sintetizado: ${duracaoDaResposta.mediana()} ms de síntese
            |════════════════════════════════════════════════════════════════════
            """.trimMargin(),
        )

        assertTrue("nenhum ciclo medido", ciclos.size >= 10)
        assertTrue("o razão não fechou: total mediano ${total.mediana()}", total.mediana() > 0)
        piper.release()
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
