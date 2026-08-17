package com.claryon.net

import android.media.MediaCodec
import android.media.MediaFormat
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * [CodecDeVoz] sobre o `MediaCodec` do Android, MIME `audio/opus`.
 *
 * A sonda [SondaDeCodec] confirmou `c2.android.opus.encoder` presente e o
 * decodificador aceitando 8 kHz nativamente — a taxa que o HFP entrega.
 *
 * ## O cabeçalho que o decodificador exige
 *
 * O decodificador Opus do Android não parte do zero: ele precisa de três blocos
 * de configuração (`csd-0`, `csd-1`, `csd-2`). O `csd-0` é o **OpusHead**, 19
 * bytes que descrevem canais, pré-skip e taxa; os outros dois são tempos em
 * nanossegundos.
 *
 * Nós os **construímos**, em vez de transmiti-los junto do áudio, porque os
 * parâmetros são fixos e conhecidos dos dois lados. Mandá-los pela rede
 * significaria que perder a primeira mensagem deixaria o receptor incapaz de
 * decodificar o resto da fala — e num rádio a primeira mensagem é justamente a
 * que chega no pior momento.
 *
 * ## Limitação honesta: o PLC não é o do Opus
 *
 * `decodificar(null)` deveria acionar o *packet loss concealment* do Opus, que
 * interpola a partir do estado interno do decodificador. O `MediaCodec` **não
 * expõe** esse caminho. O que fazemos é repetir o último quadro com atenuação —
 * pior que o PLC real, muito melhor que silêncio, que soa como corte.
 *
 * Se a medição em campo mostrar que isso incomoda, o caminho é libopus via NDK,
 * onde `opus_decode(dec, NULL, 0, pcm, n, 0)` faz o PLC de verdade. A toolchain
 * já existe (NDK 27 + CMake, do whisper), então a troca é localizada.
 *
 * Não é thread-safe por dentro do `MediaCodec`; o `Mutex` serializa.
 *
 * ## Fora da Main, e não é preciosismo
 *
 * `dequeueInputBuffer`/`dequeueOutputBuffer` esperam até [TIMEOUT_US] (20 ms)
 * quando não há buffer pronto — e `drenarEncoder` chama `dequeueOutputBuffer`
 * em loop até a última chamada devolver negativo, o que significa que **toda**
 * chamada de [codificar] paga até 20 ms de bloqueio, não só a primeira. Com um
 * quadro de microfone a cada 20 ms durante o PTT inteiro, isso é ~50 bloqueios
 * de até 20 ms por segundo — na prática, a Main **saturada** enquanto o agente
 * fala. `decodificar` tem o mesmo formato e o mesmo custo, na recepção.
 *
 * `dispatcher` é parâmetro e não `Dispatchers.Default` fixo pelo mesmo motivo
 * de `VoiceOutput.dispatcherDeAudio`: um dispatcher fixo escapa do
 * escalonador de teste (`UnconfinedTestDispatcher` nunca veria a corrotina
 * terminar).
 */
class MediaCodecOpus(
    private val config: ConfigOpus = ConfigOpus(),
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : CodecDeVoz {

    private val mutex = Mutex()

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null

    /** Último quadro decodificado — insumo do PLC caseiro. */
    private var ultimoPcm: ShortArray? = null

    /**
     * Descoberta pela contagem de amostras do primeiro quadro decodificado, não
     * assumida: o decodificador do Android devolve a 24 kHz mesmo com entrada a
     * 8 kHz (ver [CodecDeVoz.taxaDeSaidaHz]).
     */
    @Volatile
    override var taxaDeSaidaHz: Int = 0
        private set

    private val amostrasPorQuadro: Int
        get() = config.sampleRateHz * config.quadroMs / 1000

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    private fun formato() = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, SondaDeCodec.MIME_OPUS)
        setInteger(MediaFormat.KEY_SAMPLE_RATE, config.sampleRateHz)
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        // Vale como dica ao encoder; nem todo aparelho respeita.
        setInteger(MediaFormat.KEY_COMPLEXITY, config.complexidade)
    }

    private fun encoderPronto(): MediaCodec = encoder ?: MediaCodec
        .createEncoderByType(SondaDeCodec.MIME_OPUS)
        .also {
            it.configure(formato(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
            encoder = it
        }

    private fun decoderPronto(): MediaCodec = decoder ?: MediaCodec
        .createDecoderByType(SondaDeCodec.MIME_OPUS)
        .also {
            val f = formato().apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(nanos(PRE_SKIP_NS)))
                setByteBuffer("csd-2", ByteBuffer.wrap(nanos(SEEK_PREROLL_NS)))
            }
            it.configure(f, null, null, 0)
            it.start()
            decoder = it
        }

    // ── Codificação ───────────────────────────────────────────────────────────

    /**
     * Constrói codificador e decodificador fora do caminho crítico. Ver o KDoc
     * de [CodecDeVoz.preparar].
     *
     * Os DOIS: o decodificador paga o mesmo preço na primeira transmissão
     * RECEBIDA, e ali o atraso aparece como início de fala cortado.
     */
    override suspend fun preparar(): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            runCatching {
                encoderPronto()
                decoderPronto()
                Result.success(Unit)
            }.getOrElse { e ->
                // Falhar ao aquecer não pode derrubar o rádio: o caminho normal
                // reconstrói sob demanda. Só se perde o ganho de latência.
                Result.failure(
                    ClaryonError.Unexpected("opus.preparar", e.message ?: "falha ao aquecer"),
                    e,
                )
            }
        }
    }

    /**
     * Enfileira o quadro e **drena tudo o que estiver pronto**.
     *
     * Devolver lista vazia no aquecimento é o comportamento correto, não uma
     * falha: o codificador ainda não tem o que emitir. Tratar isso como erro
     * derrubaria a transmissão nos primeiros 40 ms de toda fala.
     */
    override suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>> = withContext(dispatcher) {
        mutex.withLock {
            runCatching {
                val codec = encoderPronto()

                val entrada = codec.dequeueInputBuffer(TIMEOUT_US)
                if (entrada < 0) {
                    // Sem buffer livre: o pipeline está cheio. Ainda assim vale
                    // drenar a saída — provavelmente é ela que está segurando a fila.
                    return@runCatching Result.success(drenarEncoder(codec))
                }
                codec.getInputBuffer(entrada)!!.apply {
                    clear()
                    order(ByteOrder.LITTLE_ENDIAN)
                    asShortBuffer().put(pcm)
                }
                codec.queueInputBuffer(entrada, 0, pcm.size * 2, proximaApresentacaoUs(), 0)

                Result.success(drenarEncoder(codec))
            }.getOrElse { e ->
                Result.failure(ClaryonError.Unexpected("opus.encode", e.message ?: "falha ao codificar"), e)
            }
        }
    }

    /** Retira todos os pacotes prontos, descartando os de configuração. */
    private fun drenarEncoder(codec: MediaCodec): List<ByteArray> {
        val pacotes = ArrayList<ByteArray>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val saida = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (saida < 0) break
            // CODEC_CONFIG é o OpusHead que o encoder gera. Não é áudio: se fosse
            // para a rede, o receptor o decodificaria como se fosse fala.
            val ehConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!ehConfig && info.size > 0) {
                val bytes = ByteArray(info.size)
                codec.getOutputBuffer(saida)!!.apply { position(info.offset); get(bytes) }
                pacotes.add(bytes)
            }
            codec.releaseOutputBuffer(saida, false)
        }
        return pacotes
    }

    private var apresentacaoUs = 0L

    /** Carimbo monotônico exigido pelo codificador para ordenar os quadros. */
    private fun proximaApresentacaoUs(): Long {
        val atual = apresentacaoUs
        apresentacaoUs += config.quadroMs * 1_000L
        return atual
    }

    // ── Decodificação ─────────────────────────────────────────────────────────

    override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> = withContext(dispatcher) {
        mutex.withLock {
        if (payload == null || payload.isEmpty()) {
            return@withLock Result.success(ocultarPerda())
        }
        runCatching {
            val codec = decoderPronto()

            val entrada = codec.dequeueInputBuffer(TIMEOUT_US)
            if (entrada < 0) {
                return@withLock Result.success(ocultarPerda())
            }
            codec.getInputBuffer(entrada)!!.apply { clear(); put(payload) }
            codec.queueInputBuffer(entrada, 0, payload.size, 0L, 0)

            val info = MediaCodec.BufferInfo()
            var saida = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            while (saida >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(saida, false)
                saida = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            }
            if (saida < 0) {
                return@withLock Result.success(ocultarPerda())
            }

            val pcm = ShortArray(info.size / 2)
            codec.getOutputBuffer(saida)!!.apply {
                position(info.offset)
                order(ByteOrder.LITTLE_ENDIAN)
                asShortBuffer().get(pcm)
            }
            codec.releaseOutputBuffer(saida, false)

            if (pcm.isNotEmpty()) {
                ultimoPcm = pcm
                if (taxaDeSaidaHz == 0) taxaDeSaidaHz = pcm.size * 1000 / config.quadroMs
            }
            Result.success(pcm)
        }.getOrElse { e ->
            Result.failure(ClaryonError.Unexpected("opus.decode", e.message ?: "falha ao decodificar"), e)
        }
        }
    }

    /**
     * PLC caseiro: repete o último quadro com atenuação de 40%.
     *
     * Sem quadro anterior (perda no primeiro pacote), devolve silêncio — não há
     * o que interpolar, e inventar ruído seria pior.
     */
    private fun ocultarPerda(): ShortArray {
        // Sem quadro anterior, o silêncio tem de ter o tamanho da SAÍDA, não da
        // entrada — senão a linha do tempo do receptor desalinha logo no início.
        val anterior = ultimoPcm ?: return ShortArray(
            if (taxaDeSaidaHz > 0) taxaDeSaidaHz * config.quadroMs / 1000 else amostrasPorQuadro,
        )
        val saida = ShortArray(anterior.size) { (anterior[it] * ATENUACAO_PLC).toInt().toShort() }
        // Atenua progressivamente: perdas seguidas somem em vez de virar zumbido.
        ultimoPcm = saida
        return saida
    }

    override fun liberar() {
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }
        encoder = null
        decoder = null
        ultimoPcm = null
        apresentacaoUs = 0
        taxaDeSaidaHz = 0
    }

    // ── OpusHead ──────────────────────────────────────────────────────────────

    /**
     * Cabeçalho de identificação do Opus (RFC 7845 §5.1): 19 bytes, tudo
     * little-endian menos a assinatura.
     */
    private fun opusHead(): ByteArray = ByteBuffer.allocate(19)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII)) // 0..7  assinatura
        .put(1)                                          // 8     versão
        .put(1)                                          // 9     canais (mono)
        .putShort(PRE_SKIP_AMOSTRAS)                     // 10..11 pré-skip
        .putInt(config.sampleRateHz)                     // 12..15 taxa de entrada
        .putShort(0)                                     // 16..17 ganho de saída
        .put(0)                                          // 18    família de mapeamento
        .array()

    private fun nanos(valor: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(valor).array()

    private companion object {
        const val TIMEOUT_US = 20_000L

        /** 80 samples a 8 kHz = 10 ms, o pré-skip padrão do Opus em banda estreita. */
        const val PRE_SKIP_AMOSTRAS: Short = 80
        const val PRE_SKIP_NS = 10_000_000L
        const val SEEK_PREROLL_NS = 80_000_000L

        const val ATENUACAO_PLC = 0.6f
    }
}
