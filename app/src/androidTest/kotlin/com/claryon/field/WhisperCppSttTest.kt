package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.getOrNull
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
 * `ggml-tiny` (direto do asset, sem copiar para disco) e transcreve — no
 * emulador arm64, sem rede. Valida a cadeia JNI → C++ → texto.
 *
 * ## A testemunha era da língua errada
 *
 * Este teste transcrevia `jfk.wav`, em inglês, e exigia as palavras "country" ou
 * "ask". Mas `jni.c:190` fixa `params.language = "pt"` — decisão deliberada, o
 * copiloto é de segurança pública brasileira. Alimentar inglês num decodificador
 * fixado em português devolvia *"e então, meu fellow americano… o que você pode
 * fazer para você?"*: o nativo funcionando perfeitamente, e o teste vermelho.
 *
 * O teste só passaria se o produto falasse uma língua que ele não fala. Testemunha
 * assim não certifica nada — e, pior, um dia alguém a faria passar trocando o
 * idioma do produto.
 *
 * A testemunha certa já está no APK: o Piper sintetiza português no próprio
 * aparelho. A ida e volta Piper → Whisper exercita exatamente o caminho de
 * produção, sem rede e sem `.wav` versionado.
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

    /**
     * Ida e volta em português: o Piper fala, o Whisper ouve.
     *
     * O `ggml-tiny` é o menor modelo da família e erra acentuação e palavra rara —
     * por isso o aceite é sobre **palavras de conteúdo**, não sobre a frase
     * inteira. Exigir transcrição literal de um `tiny` seria um teste que quebra
     * por ruído, não por regressão.
     */
    @Test
    fun whisperTranscrevePortuguesOnDevice() = runBlocking {
        val temModelo = runCatching {
            ctx.assets.open("models/ggml-tiny.bin").use { it.read() >= 0 }
        }.getOrDefault(false)
        Assume.assumeTrue("modelo ggml-tiny ausente nos assets", temModelo)

        val alvo = InstrumentationRegistry.getInstrumentation().targetContext
        val piper = com.claryon.field.voice.Modelos.piper(alvo)
        Assume.assumeTrue("Piper ausente: sem ele não há fala em português para ouvir", piper != null)
        val dito = piper!!.synthesize("Central, a guarnição está a caminho da ocorrência.").getOrNull()
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", dito != null)

        val whisper = WhisperContext.createContextFromAsset(ctx.assets, "models/ggml-tiny.bin")
        try {
            // O Piper gera na taxa da voz; o whisper.cpp exige 16 kHz.
            val pcm = reamostrar(dito!!.samples, dito.sampleRateHz, 16_000)
            val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            val texto = whisper.transcribeData(floats, printTimestamp = false).trim().lowercase()
            android.util.Log.i("ClaryonField", "WHISPER PT: $texto")

            assertTrue("texto vazio", texto.isNotBlank())
            val achou = listOf("central", "guarni", "caminho", "ocorr").count { texto.contains(it) }
            assertTrue(
                "esperava ao menos 2 palavras de conteúdo da frase falada; veio: $texto",
                achou >= 2,
            )
        } finally {
            whisper.release()
        }
    }

    /** Linear: o teste prova transcrição, não fidelidade de reamostragem. */
    /**
     * **`PcmResampler.resample` e NÃO uma interpolação linear escrita à mão.**
     *
     * Esta função era um reamostrador linear meu, repetido em seis benches — e
     * linear é um filtro anti-aliasing péssimo. O Piper sintetiza a 22 050 Hz e o
     * barramento é 16 000: descer sem filtro **dobra 8–11 kHz para dentro da banda
     * de voz**, exatamente onde vivem as fricativas que distinguem consoantes.
     *
     * O projeto já tinha resolvido isso em `801df29` ("Anti-aliasing na voz"), com
     * um FIR de 63 tapes e janela de Hamming antes do decimador — e o KDoc do
     * próprio `PcmResampler` avisa que `resampleLinear` não filtra. Eu reintroduzi
     * o defeito na bancada e passei a medir o meu aliasing em vez do ASR.
     */
    private fun reamostrar(entrada: ShortArray, de: Int, para: Int): ShortArray =
        com.claryon.common.PcmResampler.resample(entrada, de, para)

    @org.junit.Ignore(
        "A testemunha é inglesa e o decodificador é fixado em pt (jni.c:190). " +
            "Mantido como registro do porquê; o teste vivo é whisperTranscrevePortuguesOnDevice.",
    )
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
