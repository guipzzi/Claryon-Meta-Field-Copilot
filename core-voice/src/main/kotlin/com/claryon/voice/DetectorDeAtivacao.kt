package com.claryon.voice

import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable
import kotlin.math.exp

/**
 * **Detector da palavra de ativação, contínuo, 100% local.**
 *
 * A pergunta que originou esta classe foi *"a Alexa é chamada e responde, por que
 * Claryon não?"*, e a resposta é que a Alexa **não transcreve**. Ela pontua um padrão
 * acústico. Este projeto tentou detectar a ativação através do whisper e mediu o teto:
 * **33% de recall** com a grafia da marca, contra a meta de 90%, porque o ataque /kl/
 * é oclusiva velar e não sobrevive aos 8 kHz do HFP — sai `varyon`, `faryon`,
 * `haryon`, `quaryon`. Números e refutações em `docs/PALAVRA_DE_ATIVACAO.md`.
 *
 * ## Como funciona
 *
 * ```
 * anel de 1,0 s  →  mel  →  3 embeddings de 96  →  produto escalar  →  sigmoide
 *      ↑ desliza 80 ms                                    ↑ 289 floats, 1156 bytes
 * ```
 *
 * O extrator (mel + embedding) é ONNX e vive em `ativacao_jni.c`; a **cabeça** é o
 * produto escalar aqui embaixo. Ela é pequena assim porque escalador e regressão
 * logística **dobram** numa única camada linear — e a dobra foi verificada contra
 * 866 vetores, incluindo toda janela dos fluxos longos: a diferença numérica máxima
 * é `2,0e-7`, quatro ordens de grandeza abaixo da margem mais apertada do corpus
 * (`2,6e-3`). Nenhuma decisão muda.
 *
 * ## O que não custa nada
 *
 * Nenhuma dependência nova. `libonnxruntime.so` já entra no APK pelo AAR do
 * sherpa-onnx e já exporta `OrtGetApiBase` — faltava chamador, não motor.
 *
 * ## O que esta classe NÃO garante, e está medido
 *
 * O modelo de referência foi treinado com **um** locutor e 27 elocuções aumentadas.
 * Ele acerta 26 de 26 em fluxo contínuo e as 9 elocuções retidas com escore mínimo
 * de 0,995 — mas **o falso positivo não tem taxa medida**: o único negativo humano
 * disponível são 3,8 s de fala. `0 disparos` em 3,8 s é ausência de amostra, não
 * garantia. Antes de ligar isto no caminho do rádio em campo, a métrica que decide é
 * **falsos por hora** sobre fala espontânea.
 *
 * @param limiar acima disto o quadro é considerado ativação. `0,5` é convenção, não
 *   medida — só uma curva ROC sobre fala espontânea justifica outro valor.
 * @param refratarioMs janela morta após um disparo, para uma elocução não gerar dois.
 */
class DetectorDeAtivacao(
    private val pesos: FloatArray,
    private val vies: Float,
    private val limiar: Float = 0.5f,
    private val refratarioMs: Int = 1_000,
) : Closeable {

    init {
        require(pesos.size == DIMENSAO) {
            "a cabeça tem de ter $DIMENSAO pesos (3 embeddings de $EMBEDDING); veio ${pesos.size}"
        }
    }

    private var ptr: Long = 0L
    private val anel = FloatArray(AMOSTRAS)
    private var preenchido = 0
    private var desde = 0
    private val saida = FloatArray(DIMENSAO)
    private var amostrasDesdeDisparo = Int.MAX_VALUE

    /** Último escore calculado. Diagnóstico — não é o portão. */
    @Volatile
    var ultimoEscore: Float = 0f
        private set

    /**
     * Carrega os dois modelos ONNX das assets.
     *
     * Devolve `false` em vez de lançar: o detector é acessório do rádio, e a falta
     * dele não pode impedir o PTT de subir. Quem chama decide se degrada ou desiste.
     */
    fun preparar(assets: AssetManager, diretorio: String = ASSETS): Boolean {
        if (ptr != 0L) return true
        val mel = runCatching { assets.open("$diretorio/melspectrogram.onnx").use { it.readBytes() } }
            .getOrElse {
                Log.w(TAG, "melspectrogram.onnx ausente em $diretorio", it)
                return false
            }
        val emb = runCatching { assets.open("$diretorio/embedding_model.onnx").use { it.readBytes() } }
            .getOrElse {
                Log.w(TAG, "embedding_model.onnx ausente em $diretorio", it)
                return false
            }
        ptr = nativeCriar(mel, emb)
        if (ptr == 0L) Log.w(TAG, "o extrator ONNX não subiu; detector desligado")
        return ptr != 0L
    }

    /**
     * Consome PCM e diz se a palavra acabou de ser dita.
     *
     * Aceita blocos de qualquer tamanho — o anel absorve a granularidade de quem
     * captura. A decisão sai a cada [PASSO_AMOSTRAS] amostras (80 ms), que é o passo
     * com que a cabeça foi treinada; mudar isto aqui sem retreinar desalinha as três
     * janelas empilhadas e o escore perde sentido.
     */
    fun aceitar(pcm: ShortArray, tamanho: Int = pcm.size): Boolean {
        if (ptr == 0L) return false
        var disparou = false
        for (i in 0 until tamanho) {
            anel[desde] = pcm[i] / 32768f
            desde = (desde + 1) % AMOSTRAS
            if (preenchido < AMOSTRAS) preenchido++
            if (amostrasDesdeDisparo < Int.MAX_VALUE) amostrasDesdeDisparo++

            if (preenchido >= AMOSTRAS && desde % PASSO_AMOSTRAS == 0) {
                if (avaliar()) disparou = true
            }
        }
        return disparou
    }

    private fun avaliar(): Boolean {
        val janela = FloatArray(AMOSTRAS)
        // O anel é circular; o extrator quer a ordem cronológica.
        for (i in 0 until AMOSTRAS) janela[i] = anel[(desde + i) % AMOSTRAS]
        if (nativeEmbutir(ptr, janela, saida) == 0) return false

        var soma = vies
        for (i in 0 until DIMENSAO) soma += pesos[i] * saida[i]
        val escore = 1f / (1f + exp(-soma))
        ultimoEscore = escore

        if (escore <= limiar) return false
        if (amostrasDesdeDisparo < refratarioMs * TAXA / 1000) return false
        amostrasDesdeDisparo = 0
        return true
    }

    /** Esquece o áudio acumulado. Chamar ao trocar de rota ou retomar do silêncio. */
    fun reiniciar() {
        preenchido = 0
        desde = 0
        amostrasDesdeDisparo = Int.MAX_VALUE
    }

    override fun close() {
        if (ptr != 0L) {
            nativeDestruir(ptr)
            ptr = 0L
        }
    }

    private external fun nativeCriar(melModelo: ByteArray, embModelo: ByteArray): Long
    private external fun nativeEmbutir(ptr: Long, pcm: FloatArray, saida: FloatArray): Int
    private external fun nativeDestruir(ptr: Long)

    companion object {
        private const val TAG = "ClaryonAtivacao"

        /** 16 kHz, como todo o resto da cadeia de voz. */
        const val TAXA = 16_000

        /** A janela do extrator: 1,0 s. É ela que fixa a latência mínima. */
        const val AMOSTRAS = 16_000

        /** 80 ms entre decisões — o passo com que a cabeça foi treinada. */
        const val PASSO_AMOSTRAS = 1_280

        const val EMBEDDING = 96
        const val PILHA = 3
        const val DIMENSAO = PILHA * EMBEDDING

        const val ASSETS = "models/ativacao"

        init {
            System.loadLibrary("whisper")
        }

        /**
         * Lê a cabeça de um `.f32`: [DIMENSAO] pesos seguidos do viés, *little
         * endian*, que é o que o `numpy.tofile` escreve e o que o ARM lê nativo.
         */
        fun cabecaDeBytes(bytes: ByteArray): Pair<FloatArray, Float>? {
            if (bytes.size != (DIMENSAO + 1) * 4) {
                Log.w(TAG, "cabeça com ${bytes.size} bytes; esperado ${(DIMENSAO + 1) * 4}")
                return null
            }
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val pesos = FloatArray(DIMENSAO) { bb.float }
            return pesos to bb.float
        }
    }
}
