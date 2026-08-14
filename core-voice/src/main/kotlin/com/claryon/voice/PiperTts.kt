package com.claryon.voice

import android.content.res.AssetManager
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
        }.getOrNull()?.also { tts = it }
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

    fun release() {
        tts?.release()
        tts = null
    }

    private companion object {
        const val DEFAULT_MODEL_DIR = "vits-piper-pt_BR-faber-medium"
        const val DEFAULT_MODEL_NAME = "pt_BR-faber-medium.onnx"
    }
}
