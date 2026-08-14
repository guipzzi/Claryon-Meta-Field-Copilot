package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.voice.PiperTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Prova de que o **Piper (sherpa-onnx)** sintetiza voz pt-BR on-device.
 *
 * O modelo (`.onnx` + `tokens.txt`) é lido dos **assets**; o `espeak-ng-data`
 * precisa de um **diretório de filesystem** (a lib de fonemização usa `fopen`),
 * então é copiado para o `filesDir` do app — é o padrão `copyDataDir()` do
 * exemplo oficial do sherpa-onnx.
 *
 * Requer o modelo nos assets (não versionado; baixado no setup). Ausente, ignora.
 */
@RunWith(AndroidJUnit4::class)
class PiperTtsTest {

    // Assets vivem no APK de teste; o filesystem acessível é o do app (targetContext).
    private val ctxTest = InstrumentationRegistry.getInstrumentation().context
    private val ctxApp = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelDir = "models/vits-piper-pt_BR-faber-medium-int8"

    private fun copyAssetDir(assetPath: String, outDir: File) {
        outDir.mkdirs()
        for (e in ctxTest.assets.list(assetPath) ?: emptyArray()) {
            val child = "$assetPath/$e"
            val sub = ctxTest.assets.list(child)
            if (sub.isNullOrEmpty()) {
                ctxTest.assets.open(child).use { input ->
                    File(outDir, e).outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetDir(child, File(outDir, e))
            }
        }
    }

    @Test
    fun piperSintetizaPtBrOnDevice() = runBlocking {
        val temModelo = runCatching {
            ctxTest.assets.open("$modelDir/pt_BR-faber-medium.onnx").use { it.read() >= 0 }
        }.getOrDefault(false)
        Assume.assumeTrue("modelo Piper pt-BR ausente nos assets", temModelo)

        // espeak-ng-data → filesystem (fopen exige diretório real)
        val espeakDir = File(ctxApp.filesDir, "espeak-ng-data")
        copyAssetDir("$modelDir/espeak-ng-data", espeakDir)

        val piper = PiperTts(
            assetManager = ctxTest.assets,
            modelDir = modelDir,
            modelName = "pt_BR-faber-medium.onnx",
            dataDir = espeakDir.absolutePath,
        )
        assertTrue("Piper deveria carregar", piper.isAvailable())

        val r = piper.synthesize("Apoio solicitado, guarnição avisada.")
        assertTrue("síntese falhou: $r", r is Result.Success)
        val pcm = (r as Result.Success).value
        android.util.Log.i("PiperTtsTest", "amostras=${pcm.samples.size} sampleRate=${pcm.sampleRateHz}")

        assertTrue("áudio vazio", pcm.samples.isNotEmpty())
        assertTrue("sampleRate inválido: ${pcm.sampleRateHz}", pcm.sampleRateHz in 8_000..48_000)
        assertTrue("áudio silencioso", pcm.samples.any { abs(it.toInt()) > 100 })

        piper.release()
    }
}
