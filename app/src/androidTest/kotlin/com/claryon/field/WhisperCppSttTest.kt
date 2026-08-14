package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prova de que o **whisper.cpp nativo** funciona on-device: carrega o modelo
 * `ggml-tiny` (direto do asset, sem copiar para disco) e transcreve o `jfk.wav`
 * (16 kHz mono) — no emulador arm64, sem rede. Valida a cadeia JNI → C++ → texto.
 *
 * Requer os assets `models/ggml-tiny.bin` e `jfk.wav` (não versionados; baixados
 * no setup). Ausente o modelo, o teste é ignorado (Assume).
 */
@RunWith(AndroidJUnit4::class)
class WhisperCppSttTest {

    // Contexto da INSTRUMENTAÇÃO (APK de teste) — onde vivem os assets de androidTest.
    private val ctx = InstrumentationRegistry.getInstrumentation().context

    /** Lê um WAV PCM 16-bit mono em ShortArray (cabeçalho RIFF de 44 bytes). */
    private fun readWavPcm(input: InputStream): ShortArray {
        val bytes = input.use { it.readBytes() }
        val data = bytes.copyOfRange(44, bytes.size)
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    @Test
    fun whisperTranscreveJfkOnDevice() = runBlocking {
        val temModelo = runCatching {
            ctx.assets.open("models/ggml-tiny.bin").use { it.read() >= 0 }
        }.getOrDefault(false)
        Assume.assumeTrue("modelo ggml-tiny ausente nos assets", temModelo)

        // initContextFromAsset é implementado no jni.c e lê do APK via AAssetManager
        // (sem copiar 77 MB para o disco do emulador).
        val whisper = WhisperContext.createContextFromAsset(ctx.assets, "models/ggml-tiny.bin")
        try {
            val pcm = readWavPcm(ctx.assets.open("jfk.wav"))
            val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            val texto = whisper.transcribeData(floats, printTimestamp = false).trim().lowercase()
            android.util.Log.i("WhisperCppSttTest", "Transcrição: $texto")
            assertTrue("texto vazio", texto.isNotBlank())
            // JFK: "...ask not what your country can do for you..."
            assertTrue("esperava conteúdo do discurso; veio: $texto",
                texto.contains("country") || texto.contains("ask"))
        } finally {
            whisper.release()
        }
    }
}
