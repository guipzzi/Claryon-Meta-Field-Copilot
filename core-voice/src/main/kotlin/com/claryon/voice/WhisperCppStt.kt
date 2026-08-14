package com.claryon.voice

import com.claryon.common.ClaryonError
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * STT primário **on-device** — whisper.cpp (C++) via JNI, modelo `ggml-tiny`.
 *
 * Processa em **lote**: recebe o PCM da janela fechada pelo VAD (16 kHz mono) e
 * devolve o texto. O binding nativo (`WhisperContext`/`jni.c`) e a build CMake
 * do submódulo `whisper` são reaproveitados **verbatim** do exemplo Android
 * oficial do whisper.cpp — nada escrito de memória.
 *
 * O modelo (~75 MB, ou menor quantizado) é baixado por [modelPath] (via
 * WorkManager no M7/M8) — **não** é versionado.
 *
 * ⚠️ Espera **16 kHz**. Nosso HFP entrega 8 kHz: o resample 8→16 kHz é uma etapa
 * a resolver (ver docs/COMPLIANCE.md §D). `AudioRecord` a 16 kHz e o `jfk.wav`
 * de teste já estão a 16 kHz.
 */
class WhisperCppStt(private val modelPath: String) : SttEngine {

    override val id: String = "whisper.cpp/ggml-tiny"

    private val mutex = Mutex()
    private var context: WhisperContext? = null

    override suspend fun isAvailable(): Boolean = File(modelPath).exists()

    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript> {
        if (!File(modelPath).exists()) {
            return Result.failure(
                ClaryonError.Voice("stt.model_missing", "Modelo whisper não encontrado: $modelPath"),
            )
        }
        return try {
            val ctx = mutex.withLock {
                context ?: WhisperContext.createContextFromFile(modelPath).also { context = it }
            }
            // HFP entrega 8 kHz; o Whisper espera 16 kHz → reamostra se preciso.
            val pcm16k =
                if (sampleRateHz != TARGET_HZ) PcmResampler.resampleLinear(pcm, sampleRateHz, TARGET_HZ) else pcm
            // Whisper espera PCM float normalizado em [-1, 1], mono, 16 kHz.
            val floats = FloatArray(pcm16k.size) { pcm16k[it] / 32768.0f }
            val texto = ctx.transcribeData(floats, printTimestamp = false).trim()
            Result.success(Transcript(texto, confidence = null))
        } catch (e: Exception) {
            Result.failure(ClaryonError.Voice("stt.whisper_error", e.message ?: "erro no whisper.cpp"))
        }
    }

    suspend fun release() {
        context?.release()
        context = null
    }

    private companion object {
        const val TARGET_HZ = 16_000
    }
}
