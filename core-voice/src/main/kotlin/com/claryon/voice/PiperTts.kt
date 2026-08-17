package com.claryon.voice

import android.content.res.AssetManager
import android.util.Log
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TTS **primário** — Piper (VITS) via **sherpa-onnx** (runtime ONNX on-device).
 *
 * Voz neural pt-BR local, 100% no aparelho (o texto nunca sai). Substitui o
 * [AndroidTts] (fallback) sem reescrita, pois ambos implementam [TtsEngine].
 *
 * Modelo padrão: `vits-piper-pt_BR-faber-medium` (model.onnx + tokens +
 * espeak-ng-data). Carrega de **assets** (quando [assetManager] != null) ou de
 * **arquivo** (paths absolutos), este último para o modelo baixado em produção.
 *
 * Assinaturas confirmadas na API oficial do sherpa-onnx (`OfflineTts`,
 * `getOfflineTtsConfig`, `generate` → `GeneratedAudio(samples: FloatArray,
 * sampleRate)`).
 *
 * ## ⚠️ Modelo ausente **aborta o processo** — e nenhum `catch` salva
 *
 * Se o `.onnx` não estiver no caminho, o sherpa-onnx não lança exceção: ele
 * escreve `Read binary file: ... failed` e chama `abort()` no nativo. O
 * `runCatching` abaixo e o [ultimaFalha] cobrem falha de *configuração*; contra
 * um `abort()` **nada em Kotlin roda** — nem `catch`, nem `finally`, nem
 * relatório de crash do app. O processo simplesmente morre.
 *
 * Verificado no emulador: construir esta classe com um `modelDir` inexistente
 * derruba o processo inteiro, levando junto o teste que estava rodando.
 *
 * Por isso [existeModelo] confere o asset **antes** de entregá-lo ao nativo. A
 * mesma verificação já existia em `Modelos.piper()`, mas morar só no chamador
 * significa que ela protege quem lembra dela — e este projeto tem por regra que
 * a garantia é da classe, não da disciplina de quem a usa.
 */
class PiperTts(
    private val assetManager: AssetManager?,
    private val modelDir: String = DEFAULT_MODEL_DIR,
    private val modelName: String = DEFAULT_MODEL_NAME,
    private val dataDir: String = "$DEFAULT_MODEL_DIR/espeak-ng-data",
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
) : TtsEngine {

    private val mutex = Mutex()
    private var tts: OfflineTts? = null

    /** Última causa de falha ao carregar o motor (diagnóstico). */
    @Volatile
    var ultimaFalha: Throwable? = null
        private set

    /**
     * O asset do modelo abre? Ver o aviso no KDoc da classe: esta pergunta tem de
     * ser respondida **antes** do construtor nativo, porque depois dele não há
     * mais linha de execução para responder nada.
     *
     * Só cobre o caminho de asset. Com [assetManager] nulo o modelo vem de
     * arquivo e a checagem equivalente é do chamador — declarado, não esquecido.
     */
    private fun existeModelo(): Boolean {
        val am = assetManager ?: return true
        return runCatching { am.open("$modelDir/$modelName").use { it.read() >= 0 } }
            .getOrDefault(false)
    }

    private suspend fun engine(): OfflineTts? = mutex.withLock {
        if (tts == null && !existeModelo()) {
            val causa = IllegalStateException(
                "Modelo Piper ausente em assets/$modelDir/$modelName — " +
                    "entregá-lo ao sherpa-onnx abortaria o processo.",
            )
            ultimaFalha = causa
            Log.e(TAG, causa.message, causa)
            return@withLock null
        }
        tts ?: runCatching {
            val config = getOfflineTtsConfig(
                modelDir = modelDir,
                modelName = modelName,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = dataDir,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
            )
            OfflineTts(assetManager = assetManager, config = config)
        }
            // A causa NÃO pode ser descartada: sem ela o usuário recebe "Modelo
            // Piper não carregou" e ninguém sabe se faltou arquivo, se a ABI está
            // errada ou se o espeak-ng-data não foi copiado. Ponto crítico.
            .onFailure { e ->
                ultimaFalha = e
                Log.e(TAG, "Piper/sherpa-onnx não carregou (modelDir=$modelDir, dataDir=$dataDir)", e)
            }
            .getOrNull()?.also { tts = it }
    }

    override suspend fun isAvailable(): Boolean = engine() != null

    override suspend fun synthesize(text: String): Result<PcmAudio> = withContext(Dispatchers.Default) {
        val engine = engine()
            ?: return@withContext Result.failure(
                ClaryonError.Voice("tts.piper_unavailable", "Modelo Piper (sherpa-onnx) não carregou."),
            )
        try {
            val audio = engine.generate(text = text, sid = speakerId, speed = speed)
            // sherpa devolve FloatArray em [-1, 1]; convertemos para PCM 16-bit.
            val samples = ShortArray(audio.samples.size) {
                (audio.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            Result.success(PcmAudio(samples, audio.sampleRate))
        } catch (e: Exception) {
            Result.failure(ClaryonError.Voice("tts.piper_error", e.message ?: "erro no Piper/sherpa-onnx"))
        }
    }

    /** Libera o motor nativo sob o mesmo lock de [engine] — senão liberaria com
     *  uma `generate()` nativa em andamento. */
    suspend fun release() = mutex.withLock {
        tts?.release()
        tts = null
    }

    private companion object {
        const val TAG = "ClaryonField"
        const val DEFAULT_MODEL_DIR = "vits-piper-pt_BR-faber-medium"
        const val DEFAULT_MODEL_NAME = "pt_BR-faber-medium.onnx"
    }
}
