package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.SileroVoiceActivityDetector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * **Os aceites da Fase 2 que exigem hardware, rodados em simulação.**
 *
 * Três cláusulas do aceite dependem de óculos e fone que não existem aqui:
 *
 * 1. *"Trinta pronúncias reais gravadas por HFP dão recall ≥ 90%"*
 * 2. *"Oito horas de rádio ambiente gravado e reproduzido não abrem canal nenhuma vez"*
 * 3. *"transmissão que um segundo ouvinte recebe"*
 *
 * Simulação **não substitui** nenhuma delas, e a diferença tem de estar dita antes
 * do primeiro número: o Piper produz fala limpa, sem sotaque, sem hesitação e sem o
 * AGC do *uplink*; o HFP real negocia mSBC (16 kHz) **ou** CVSD (8 kHz), e qual dos
 * dois é a variável independente que este teste **fixa por decreto**. Foi uma
 * bancada assim que aprovou "Claryon" em 14/08 e errou.
 *
 * O que a simulação **faz** é o que o hardware não faz barato: elimina candidatas
 * e desenhos que já falham no caso fácil, antes de gastar sessão de campo. Um
 * resultado ruim aqui é notícia definitiva; um resultado bom é permissão para ir
 * ao hardware, não aprovação.
 *
 * ## A banda estreita, e por que ela é simulada assim
 *
 * `16 kHz → 8 kHz → 16 kHz` com o `PcmResampler` do projeto (FIR de 63 tapes). A
 * descida corta tudo acima de 4 kHz — que é o efeito dominante do CVSD — e a
 * subida devolve a taxa que o whisper espera **sem** devolver as frequências. Não
 * simula o *codec* CVSD em si (quantização ADPCM, ruído de quantização), e isso
 * está declarado: é banda, não é codec.
 */
@RunWith(AndroidJUnit4::class)
class SimulacaoDeHardwareTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A palavra que venceu a medição de sobrevivência. */
    private val ativacao = "Aurora"

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Banda estreita: desce a 8 kHz e volta. Corta acima de 4 kHz, como o HFP. */
    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    // ── Aceite 1: recall da palavra de ativação em banda estreita ─────────────

    /**
     * **Simulação do "recall ≥ 90% em 30 pronúncias por HFP".**
     *
     * Trinta enunciados distintos, cada um começando pela palavra de ativação, todos
     * passados pela banda estreita. Enunciados **distintos** e não a mesma frase
     * repetida: o que se mede é se a palavra sobrevive em contextos variados, não se
     * o VITS é estável.
     */
    @Test
    fun recallDaAtivacaoEmBandaEstreita(): Unit = runBlocking {
        val complementos = listOf(
            "mudar para guarnição 3", "mudar para guarnição 4", "onde está a guarnição 3",
            "modo ocorrência", "modo ativo", "modo standby", "pedir apoio",
            "iniciar gravação", "encerrar gravação", "consultar placa",
            "detalhar", "repetir", "trocar para guarnição 4", "muda pra guarnição 3",
        )
        val frases = complementos.map { "$ativacao, $it." } +
            complementos.take(16).map { "$ativacao $it." }

        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val amostras = mutableListOf<Pair<String, ShortArray>>()
        for (f in frases.take(30)) {
            piper!!.synthesize(f).getOrNull()?.let {
                amostras += f to porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", amostras.size >= 20)

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val alvo = normalizar(ativacao)
        var naPrimeira = 0
        var nasTresPrimeiras = 0
        val falhas = mutableListOf<String>()
        for ((frase, pcm) in amostras) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val palavras = normalizar(txt).split(" ")
            if (palavras.firstOrNull() == alvo) naPrimeira++
            if (palavras.take(3).any { it == alvo }) nasTresPrimeiras++ else falhas += txt.trim().take(46)
        }

        val n = amostras.size
        val recall1 = naPrimeira * 100.0 / n
        val recall3 = nasTresPrimeiras * 100.0 / n

        android.util.Log.i(
            "ClaryonField",
            """
            |SIMULAÇÃO — recall da ativação "$ativacao" em BANDA ESTREITA (8 kHz)
            |  $n enunciados distintos, ${Modelos.WHISPER_ASSET}
            |  exigindo na 1ª palavra ....... ${"%.1f".format(recall1)} %  ($naPrimeira/$n)
            |  aceitando nas 3 primeiras .... ${"%.1f".format(recall3)} %  ($nasTresPrimeiras/$n)
            |  meta do aceite ............... 90 %
            |  falhas: ${falhas.take(6).joinToString(" · ")}
            |
            |  ⚠️ Fala do Piper, sem sotaque, sem hesitação e sem AGC de uplink. Isto
            |     é o MELHOR caso. O aceite exige 30 pronúncias REAIS por HFP.
            """.trimMargin(),
        )

        // Asserção sobre o instrumento; o veredito de 90% é do aceite com hardware.
        assertTrue("nenhuma amostra foi medida", n >= 20)
    }

    // ── Aceite 2: falso positivo em ambiente ─────────────────────────────────

    /**
     * **Simulação acelerada do "8 h de rádio ambiente não abrem canal".**
     *
     * Oito horas reais são inviáveis em CI. O que se simula é o **portão inteiro**
     * — VAD e depois STT — sobre um fluxo longo de não-fala e de fala que **não é
     * comando**, contando quantas vezes a palavra de ativação apareceria.
     *
     * A parte mais importante é a segunda: o falso positivo perigoso não vem de
     * ruído (o VAD barra), vem de **fala humana que não era para nós**. Rádio da
     * corporação tocando é fala.
     */
    @Test
    fun falsoPositivoEmAmbienteQueNaoEComando(): Unit = runBlocking {
        // Fala ambiente: enunciados operacionais plausíveis que NÃO são comando e
        // não contêm a palavra de ativação.
        val ambiente = listOf(
            "Central, aqui é a guarnição dois, estamos no local.",
            "O veículo seguiu sentido bairro pela avenida.",
            "Negativo, sem alteração no perímetro.",
            "A ocorrência foi encerrada, retornando à base.",
            "Solicito informação sobre o endereço anterior.",
            "Aguardando o apoio chegar para prosseguir.",
            "A aurora boreal apareceu no noticiário ontem.",
            "Ele mudou para a outra rua agora há pouco.",
        )

        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val falaAmbiente = mutableListOf<Pair<String, ShortArray>>()
        for (f in ambiente) {
            piper!!.synthesize(f).getOrNull()?.let {
                falaAmbiente += f to porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", falaAmbiente.isNotEmpty())

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val alvo = normalizar(ativacao)
        var disparos = 0
        val ondeDisparou = mutableListOf<String>()
        for ((frase, pcm) in falaAmbiente) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val palavras = normalizar(txt).split(" ")
            // O critério do portão: a palavra nas três primeiras posições.
            if (palavras.take(3).any { it == alvo }) {
                disparos++
                ondeDisparou += "\"${frase.take(38)}\" → \"${txt.trim().take(38)}\""
            }
        }

        // E a não-fala: ruído e tom, que é o que o VAD tem de barrar ANTES do STT.
        val vad = SileroVoiceActivityDetector(assets = ctx.assets, sampleRateHz = taxa)
        suspend fun segmentosDe(pcm: ShortArray): Int {
            var n = 0
            vad.segment(flowOf(*pcm.toList().chunked(320) { it.toShortArray() }.toTypedArray()))
                .collect { n++ }
            return n
        }
        val ruido = ShortArray(taxa * 20) { Random(7 + it).nextInt(-6000, 6000).toShort() }
        val motor = ShortArray(taxa * 20) { i ->
            // Ruído de motor: banda baixa, o caso que mais parece voz para um RMS.
            (sin(2 * PI * 90.0 * i / taxa) * 5000 + Random(i).nextInt(-1500, 1500)).toInt().toShort()
        }
        val segRuido = segmentosDe(ruido + ShortArray(taxa))
        val segMotor = segmentosDe(motor + ShortArray(taxa))

        android.util.Log.i(
            "ClaryonField",
            """
            |SIMULAÇÃO — falso positivo do portão "$ativacao"
            |  FALA que não é comando (o caso perigoso, o VAD deixa passar):
            |    ${falaAmbiente.size} enunciados → **$disparos disparo(s)**
            |    ${ondeDisparou.joinToString("\n    ").ifEmpty { "(nenhum)" }}
            |  NÃO-FALA (o VAD tem de barrar antes do STT):
            |    ruído branco 20 s ... $segRuido segmento(s)
            |    ruído de motor 20 s . $segMotor segmento(s)
            |
            |  ⚠️ 8 h de rádio VHF real reproduzido no ambiente continuam sendo o aceite.
            |     Isto elimina desenhos ruins; não aprova o desenho bom.
            """.trimMargin(),
        )

        // O aceite é ZERO disparo. Aqui a asserção é dura porque o custo do falso
        // positivo é tomar o piso da guarnição — e "a aurora boreal apareceu no
        // noticiário" é exatamente a armadilha que uma palavra real do léxico traz.
        assertTrue(
            "o portão disparou $disparos vez(es) em fala que NÃO era comando: $ondeDisparou",
            disparos == 0,
        )
        assertTrue("o VAD deixou ruído branco passar ao STT", segRuido == 0)
        assertTrue("o VAD deixou ruído de motor passar ao STT", segMotor == 0)
    }
}
