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
 * de 0,995.
 *
 * ### O falso positivo PASSOU a ter taxa medida (21/08)
 *
 * Este parágrafo dizia que não tinha, e a frase estava certa até 20/08: o único
 * negativo humano eram 3,8 s de fala, e `0 disparos` ali é ausência de amostra.
 *
 * Agora são **3,04 h de fala espontânea retida** — metade de cada um de cinco
 * podcasts em pt-BR, cortados ao meio **no tempo** para que a metade que mede nunca
 * tenha sido vista pelo treino. Múltiplos locutores, sobreposição, música de
 * abertura. Medido com o refratário de 1 s desta própria classe.
 *
 * Cabeça em produção (`assets/models/ativacao/cabeca.f32` = `cabeca_v5.f32`), e a
 * anterior (`v3`) para comparação:
 *
 * ```
 *  limiar    v5 FP/h   v3 FP/h   recall v5 (9 retidas)
 *   0,50       0,99      1,65      100%
 *   0,70       0,33      0,66      100%
 *   0,90       0,33      0,00      100%
 *   0,99       0,00      0,00      100%
 *   0,999      0,00      0,00       89%
 * ```
 *
 * Escore máximo no negativo: v5 0,9558 · v3 0,8907. Mínimo nas retidas: v5 0,9986.
 *
 * **Nenhuma linha de `0,00` é uma taxa.** Zero evento em 3,04 h dá, pela regra dos
 * três, teto de 95% em **0,99/h** — ainda o dobro da meta de 0,5/h do aceite da
 * Fase 2. Para afirmar 0,5/h seriam necessárias ~6 h retidas.
 *
 * ### O que continua SEM medida, e é o outro lado da curva
 *
 * O lado negativo tem 3,04 h de áudio real. O lado **positivo** continua sendo **9
 * clipes limpos, centrados, de um locutor só** — não é fala pelo microfone dos
 * óculos por HFP, que comprime e estreita a banda.
 *
 * Isso muda a conclusão de lugar: subir o limiar acima de 0,5 continua sem
 * justificativa medida, e agora **por falta do lado positivo**, não do negativo. Uma
 * tabela que mostra 100% de recall até 0,995 sobre 9 clipes de laboratório não
 * autoriza apertar o portão em campo.
 *
 * Scripts: `ferramentas/ativacao/podcast5.py` (fatorial 2×2),
 * `podcast5_semente.py` (10 sementes por braço — uma medida sem variância não é
 * medida) e `comparar_cabecas.py` (as cabeças gravadas, com o escore calculado como
 * este arquivo calcula).
 *
 * @param limiar acima disto o quadro é considerado ativação. `0,5` **permanece** o
 *   valor, e agora por razão medida: no negativo ele custa 0,99/h contra 0,33/h em
 *   0,7, e essa diferença não paga o risco do lado positivo não medido. Mudar isto
 *   exige a curva com fala real por HFP, não a que existe hoje.
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

    /**
     * **A janela desenrolada, alocada UMA vez.**
     *
     * Antes era um `FloatArray(AMOSTRAS)` novo a cada avaliação. Medido no aparelho
     * em 21/08: **781 KB/s de lixo** — 16 000 floats × 4 bytes × 12,5 avaliações/s
     * —, gerados o turno inteiro, num aparelho que também roda mapa, rádio e cofre.
     *
     * O buffer é seguro de reusar porque `avaliar()` roda numa thread só (o laço de
     * captura) e o conteúdo é sobrescrito por completo antes de ser lido: as
     * `AMOSTRAS` posições são preenchidas no `for` abaixo, sem depender do que havia
     * antes. Se um dia a avaliação sair para outra thread, isto **tem** de voltar a
     * ser local — e é por isso que o motivo está escrito aqui e não só no commit.
     */
    private val janelaDesenrolada = FloatArray(AMOSTRAS)

    private fun avaliar(): Boolean {
        val janela = janelaDesenrolada
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
