package com.claryon.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claryon.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * O codec é a única peça da Fase 2 que não dá para provar com política pura: ou o
 * áudio sobrevive à ida e volta, ou não sobrevive.
 *
 * O teste central compara **energia e forma**, não byte a byte — Opus é com
 * perdas por desenho. Um seno de 440 Hz que atravessa e volta tem de continuar
 * sendo um seno de 440 Hz reconhecível.
 *
 * Todos os corpos são tipados como `Unit`: `Log.i` devolve `Int` e o JUnit recusa
 * método de teste que não retorne void — falha que aparece como
 * `initializationError`, sem apontar o método culpado.
 */
@RunWith(AndroidJUnit4::class)
class MediaCodecOpusTest {

    private val config = ConfigOpus(sampleRateHz = 8_000, bitrate = 12_000, quadroMs = 20)
    private val amostrasPorQuadro = config.sampleRateHz * config.quadroMs / 1000 // 160

    private var codec: MediaCodecOpus? = null

    @After
    fun liberar() {
        codec?.liberar()
        codec = null
    }

    private fun novo() = MediaCodecOpus(config).also { codec = it }

    private fun seno(hz: Double, amostras: Int, faseInicial: Int = 0) = ShortArray(amostras) { i ->
        (sin(2 * PI * hz * (i + faseInicial) / config.sampleRateHz) * 19_000).toInt().toShort()
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var soma = 0.0
        for (s in pcm) soma += s.toDouble() * s.toDouble()
        return sqrt(soma / pcm.size)
    }

    /**
     * Energia na frequência [hz], por correlação com seno e cosseno. Evita trazer
     * uma FFT só para um teste: o que interessa é comparar a energia no tom
     * original contra a de um tom que não estava lá.
     */
    private fun energiaEm(pcm: ShortArray, hz: Double, taxaHz: Int = config.sampleRateHz): Double {
        var re = 0.0
        var im = 0.0
        for (i in pcm.indices) {
            val ang = 2 * PI * hz * i / taxaHz
            re += pcm[i] * cos(ang)
            im += pcm[i] * sin(ang)
        }
        return sqrt(re * re + im * im) / pcm.size
    }

    private suspend fun MediaCodecOpus.pacotesDe(pcm: ShortArray): List<ByteArray> =
        when (val r = codificar(pcm)) {
            is Result.Success -> r.value
            is Result.Failure -> error("codificação falhou: ${r.error.code} ${r.error.message}")
        }

    /** Codifica e decodifica um quadro; devolve o último PCM produzido, se houver. */
    private suspend fun MediaCodecOpus.idaEVolta(pcm: ShortArray): ShortArray? {
        var ultimo: ShortArray? = null
        for (p in pacotesDe(pcm)) {
            val d = decodificar(p)
            if (d is Result.Success && d.value.isNotEmpty()) ultimo = d.value
        }
        return ultimo
    }

    // ── Caminho feliz ─────────────────────────────────────────────────────────

    @Test
    fun aoAquecer_oCodificadorPodeNaoEmitirNada_eIssoNaoEhFalha(): Unit = runBlocking {
        // O MediaCodec é um pipeline: consome alguns quadros antes do 1º pacote.
        // Tratar o aquecimento como erro derrubaria toda fala nos primeiros ms.
        val c = novo()
        var totalPacotes = 0
        var primeiroComSaida = -1

        for (q in 0 until 12) {
            val pacotes = c.pacotesDe(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro))
            if (pacotes.isNotEmpty() && primeiroComSaida < 0) primeiroComSaida = q
            totalPacotes += pacotes.size
        }

        Log.i(TAG, "Opus: 1o pacote no quadro $primeiroComSaida · 12 quadros → $totalPacotes pacotes")
        assertTrue("o codificador nunca emitiu nada em 12 quadros", totalPacotes > 0)
    }

    @Test
    fun comprime_ePacoteEhMenorQueOPcm(): Unit = runBlocking {
        val c = novo()
        var pacote: ByteArray? = null
        for (q in 0 until 12) {
            if (pacote == null) {
                pacote = c.pacotesDe(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro)).firstOrNull()
            }
        }
        assertTrue("nenhum pacote produzido em 12 quadros", pacote != null)
        // 160 amostras × 2 bytes = 320 crus. A 12 kbps, 20 ms ≈ 30 bytes.
        assertTrue(
            "compressão não aconteceu: ${pacote!!.size} B para ${amostrasPorQuadro * 2} crus",
            pacote!!.size < amostrasPorQuadro,
        )
        Log.i(TAG, "Opus: ${amostrasPorQuadro * 2} B de PCM → ${pacote!!.size} B codificados")
    }

    @Test
    fun senoAtravessaOCodec_eContinuaSendoOMesmoTom(): Unit = runBlocking {
        val c = novo()
        val tom = 440.0
        var ultimo = ShortArray(0)

        for (q in 0 until 30) {
            c.idaEVolta(seno(tom, amostrasPorQuadro, q * amostrasPorQuadro))?.let { ultimo = it }
        }

        assertTrue("nada foi decodificado em 30 quadros", ultimo.isNotEmpty())

        // O Opus trabalha internamente a 48 kHz e o decodificador do Android pode
        // devolver nessa taxa, não na de entrada. Descobrimos pela CONTAGEM DE
        // AMOSTRAS de um quadro de 20 ms, em vez de assumir — se a taxa de saída
        // for outra, o caminho de reprodução precisa saber.
        val taxaDeSaida = ultimo.size * 1000 / config.quadroMs
        Log.i(TAG, "Opus: quadro decodificado = ${ultimo.size} amostras → taxa de saída ${taxaDeSaida} Hz")

        val noTom = energiaEm(ultimo, tom, taxaDeSaida)
        val foraDoTom = energiaEm(ultimo, 1_800.0, taxaDeSaida)
        Log.i(
            TAG,
            "Opus round-trip @${taxaDeSaida}Hz: energia@440=${noTom.toInt()} @1800=${foraDoTom.toInt()} rms=${rms(ultimo).toInt()}",
        )

        assertTrue("o tom original não sobreviveu (440=$noTom, 1800=$foraDoTom)", noTom > foraDoTom * 3)
        assertTrue("o áudio decodificado está mudo", rms(ultimo) > 500)
    }

    @Test
    fun oCodecInformaATaxaDeSaida_queNaoEhADeEntrada(): Unit = runBlocking {
        // O receptor configura o AudioTrack com este valor. Se ele fosse assumido
        // como a taxa de entrada, a voz sairia 3x mais grave e 3x mais lenta —
        // defeito que soa como problema de microfone, e manda procurar no lugar
        // errado. Este teste existe para que uma mudança de aparelho apareça aqui.
        val c = novo()
        assertEquals("antes de decodificar, a taxa é desconhecida", 0, c.taxaDeSaidaHz)

        for (q in 0 until 30) c.idaEVolta(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro))

        Log.i(TAG, "Opus: entrada ${config.sampleRateHz} Hz → saída ${c.taxaDeSaidaHz} Hz")
        assertTrue("o codec não informou a taxa de saída", c.taxaDeSaidaHz > 0)
        assertEquals(
            "taxa de saída mudou — o caminho de reprodução precisa ser revisto",
            24_000,
            c.taxaDeSaidaHz,
        )
    }

    // ── Perda ─────────────────────────────────────────────────────────────────

    @Test
    fun perda_produzInterpolacao_naoSilencio(): Unit = runBlocking {
        // Silêncio soa como corte; repetir atenuado soa como voz degradada.
        val c = novo()
        var houveAudio = false
        for (q in 0 until 30) {
            c.idaEVolta(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro))?.let { houveAudio = true }
        }
        assertTrue("o cenário exige áudio decodificado antes da perda", houveAudio)

        val oculto = (c.decodificar(null) as Result.Success).value
        assertTrue("PLC devolveu vazio", oculto.isNotEmpty())
        assertTrue("PLC devolveu silêncio — deveria interpolar", rms(oculto) > 300)
    }

    @Test
    fun perdasSeguidas_desvanecem_naoViramZumbido(): Unit = runBlocking {
        // Repetir o mesmo quadro indefinidamente produziria um tom preso.
        val c = novo()
        for (q in 0 until 30) c.idaEVolta(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro))

        val primeira = rms((c.decodificar(null) as Result.Success).value)
        repeat(8) { c.decodificar(null) }
        val decima = rms((c.decodificar(null) as Result.Success).value)

        Log.i(TAG, "PLC: 1a perda rms=${primeira.toInt()} · 10a rms=${decima.toInt()}")
        assertTrue("a repetição não desvaneceu ($primeira → $decima)", decima < primeira / 2)
    }

    @Test
    fun perdaAntesDeQualquerQuadro_devolveSilencioDoTamanhoCerto(): Unit = runBlocking {
        // Não há o que interpolar. O que não pode é devolver vazio e desalinhar a
        // linha do tempo do receptor.
        val oculto = (novo().decodificar(null) as Result.Success).value
        assertEquals(amostrasPorQuadro, oculto.size)
        assertEquals(0.0, rms(oculto), 0.001)
    }

    @Test
    fun pacoteVazio_ehTratadoComoPerda(): Unit = runBlocking {
        val r = novo().decodificar(ByteArray(0))
        assertTrue(r is Result.Success)
        assertEquals(amostrasPorQuadro, (r as Result.Success).value.size)
    }

    // ── Cenários adversos ─────────────────────────────────────────────────────

    @Test
    fun pacoteCorrompido_naoDerrubaOCodec(): Unit = runBlocking {
        // Um byte trocado na rede não pode matar o rádio.
        val c = novo()
        var bom: ByteArray? = null
        for (q in 0 until 20) {
            if (bom == null) {
                bom = c.pacotesDe(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro)).firstOrNull()
            }
        }
        assertTrue("o cenário exige um pacote válido", bom != null)
        c.decodificar(bom)

        val corrompido = bom!!.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
        c.decodificar(corrompido) // pode falhar ou produzir lixo; não pode explodir

        val depois = c.decodificar(bom)
        assertTrue("o codec morreu depois de um pacote corrompido", depois is Result.Success)
    }

    @Test
    fun quadroDeTamanhoErrado_naoLanca(): Unit = runBlocking {
        // O AudioRecord pode entregar um bloco parcial no fim da captura.
        val r = novo().codificar(ShortArray(amostrasPorQuadro / 3) { 1000 })
        assertTrue("deveria falhar de forma tipada, nunca lançar", r is Result.Success || r is Result.Failure)
    }

    @Test
    fun liberarDuasVezes_ehIdempotente(): Unit = runBlocking {
        // Encerramento duplo acontece: tela morrendo junto com o serviço parando.
        val c = novo()
        c.codificar(seno(440.0, amostrasPorQuadro))
        c.liberar()
        c.liberar()
    }

    @Test
    fun usarDepoisDeLiberar_reabreEmVezDeExplodir(): Unit = runBlocking {
        val c = novo()
        repeat(5) { c.codificar(seno(440.0, amostrasPorQuadro, it * amostrasPorQuadro)) }
        c.liberar()

        val r = c.codificar(seno(440.0, amostrasPorQuadro))
        assertTrue("deveria reabrir o codec: $r", r is Result.Success)
    }

    @Test
    fun rajadaDeQuadros_mantemTaxaEstavel(): Unit = runBlocking {
        // 250 quadros = 5 s de fala contínua. Vazamento de buffer aparece aqui.
        val c = novo()
        var bytes = 0
        var pacotes = 0
        for (q in 0 until 250) {
            val r = c.codificar(seno(440.0, amostrasPorQuadro, q * amostrasPorQuadro))
            assertTrue("falhou no quadro $q: $r", r is Result.Success)
            for (p in (r as Result.Success).value) {
                bytes += p.size
                pacotes++
            }
        }
        val kbps = bytes * 8.0 / 5.0 / 1000.0
        Log.i(TAG, "Opus: 250 quadros (5 s) → $pacotes pacotes, $bytes B ≈ ${"%.1f".format(kbps)} kbps")

        assertTrue("quase nenhum pacote em 5 s: $pacotes", pacotes > 200)
        assertTrue("taxa fora do esperado: $kbps kbps", kbps in 4.0..40.0)
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
