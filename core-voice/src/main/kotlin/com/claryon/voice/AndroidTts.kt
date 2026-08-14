package com.claryon.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * TTS de **fallback** usando o `TextToSpeech` nativo do Android.
 *
 * Implementação: `synthesizeToFile` gera um WAV; lemos o PCM de volta e
 * entregamos como [PcmAudio], para a reprodução seguir pelo nosso pipeline
 * (GlassesAudioManager/AudioTrack), coerente com o back-end primário.
 *
 * Regras respeitadas: **nunca `speak()` antes do `onInit`** (enfileiramos via
 * [ready]); o motor primário será **Piper (sherpa-onnx)** com voz pt-BR de maior
 * naturalidade — a interface [TtsEngine] permite trocar sem reescrita.
 */
class AndroidTts(
    context: Context,
    private val locale: Locale = Locale("pt", "BR"),
) : TtsEngine {

    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(locale)
                ready.complete(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED)
            } else {
                ready.complete(false)
            }
        }
    }

    override suspend fun isAvailable(): Boolean = ready.await()

    override suspend fun synthesize(text: String): Result<PcmAudio> {
        if (!ready.await()) {
            return Result.failure(
                ClaryonError.Voice("tts.unavailable", "TextToSpeech indisponível ou pt-BR ausente."),
            )
        }
        val engine = tts
            ?: return Result.failure(ClaryonError.Voice("tts.null", "Motor TTS não inicializado."))

        val outFile = File.createTempFile("claryon_tts", ".wav")
        val utteranceId = "claryon-${System.identityHashCode(text)}"
        val done = CompletableDeferred<Boolean>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { done.complete(false) }
            override fun onError(id: String?, errorCode: Int) { done.complete(false) }
        })

        val enq = engine.synthesizeToFile(text, Bundle(), outFile, utteranceId)
        if (enq != TextToSpeech.SUCCESS) {
            outFile.delete()
            return Result.failure(ClaryonError.Voice("tts.enqueue_failed", "synthesizeToFile falhou."))
        }
        val ok = done.await()
        return try {
            if (!ok || !outFile.exists()) {
                Result.failure(ClaryonError.Voice("tts.synthesis_failed", "Síntese não concluiu."))
            } else {
                Result.success(readWavAsPcm(outFile))
            }
        } finally {
            outFile.delete()
        }
    }

    fun liberar() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * Lê um WAV PCM 16-bit em [PcmAudio], **localizando os chunks** `fmt `/`data`
     * (não assume offset 44 — alguns motores inserem chunks LIST/fact antes do
     * `data`, o que corromperia a leitura fixa).
     */
    private fun readWavAsPcm(file: File): PcmAudio {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate = 22_050
        var dataOffset = -1
        var dataLen = 0
        var pos = 12 // após "RIFF" <size> "WAVE"
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 8 <= bytes.size) sampleRate = bb.getInt(body + 4)
                "data" -> { dataOffset = body; dataLen = size }
            }
            if (dataOffset >= 0) break
            pos = body + size + (size and 1) // chunks alinhados a palavra
        }
        if (dataOffset < 0) { dataOffset = 44; dataLen = bytes.size - 44 } // fallback
        val end = (dataOffset + dataLen).coerceIn(dataOffset, bytes.size)
        val pcmBytes = bytes.copyOfRange(dataOffset, end)
        val samples = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return PcmAudio(samples, sampleRate)
    }
}
