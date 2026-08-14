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

    private suspend fun engine(): OfflineTts? = mutex.withLock {
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
