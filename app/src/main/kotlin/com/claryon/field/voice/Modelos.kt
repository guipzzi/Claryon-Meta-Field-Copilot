package com.claryon.field.voice

import android.content.Context
import com.claryon.voice.ModelSource
import com.claryon.voice.PiperTts
import com.claryon.voice.WhisperCppStt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Descobre de onde carregar os modelos on-device.
 *
 * Ordem deliberada — **asset primeiro, arquivo depois**:
 *  1. `assets/models/` no APK: instala junto com o app, funciona offline e não
 *     depende do Wi-Fi do evento. É o que garante IA local no aparelho que a
 *     organização entrega no dia.
 *  2. `filesDir`: modelo colocado por `adb push` ou baixado por WorkManager.
 *     Serve para desenvolvimento e para trocar de modelo sem reinstalar — e é o
 *     caminho previsto para um LLM, grande demais para o APK.
 *
 * Devolver `null` (nenhuma origem) é um estado legítimo e **audível**: o ciclo de
 * voz degrada e diz que o STT está indisponível, em vez de fingir que ouviu.
 */
object Modelos {

    const val WHISPER_ASSET = "models/ggml-tiny.bin"
    const val WHISPER_ARQUIVO = "ggml-tiny.bin"

    /** Diretório do Piper dentro de `assets/` (o TTS carrega a pasta inteira). */
    const val PIPER_ASSET_DIR = "models/vits-piper-pt_BR-faber-medium-int8"

    /** Origem efetiva do whisper, ou `null` se o modelo não está em lugar nenhum. */
    fun fonteDoWhisper(context: Context): ModelSource? {
        val asset = ModelSource.Asset(context.assets, WHISPER_ASSET)
        if (asset.existe()) return asset

        val arquivo = ModelSource.Arquivo(File(context.filesDir, WHISPER_ARQUIVO).path)
        if (arquivo.existe()) return arquivo

        return null
    }

    /** STT nativo pronto para uso, ou `null` se não há modelo. */
    fun whisper(context: Context): WhisperCppStt? =
        fonteDoWhisper(context)?.let { WhisperCppStt(it) }

    // ── Piper (TTS neural) ────────────────────────────────────────────────────

    const val PIPER_MODELO = "pt_BR-faber-medium.onnx"

    /**
     * TTS neural pronto para uso, ou `null` se o modelo não está empacotado
     * (aí o chamador cai no [com.claryon.voice.AndroidTts]).
     *
     * O `espeak-ng-data` **precisa ser diretório real no sistema de arquivos**:
     * o espeak-ng abre os arquivos com `fopen`, que não enxerga assets dentro do
     * APK. Por isso é copiado uma vez para o `filesDir` — foi o que travou a
     * primeira tentativa de rodar o Piper, e é o mesmo `copyDataDir()` do exemplo
     * oficial do sherpa-onnx. O modelo `.onnx` em si continua sendo lido do
     * asset, sem cópia.
     *
     * Suspensa de propósito: a cópia são ~120 diretórios e não pode rodar na Main.
     */
    suspend fun piper(context: Context): PiperTts? = withContext(Dispatchers.IO) {
        val temModelo = runCatching {
            context.assets.open("$PIPER_ASSET_DIR/$PIPER_MODELO").use { it.read() >= 0 }
        }.getOrDefault(false)
        if (!temModelo) return@withContext null

        val espeak = File(context.filesDir, "espeak-ng-data")
        if (espeak.list().isNullOrEmpty()) {
            runCatching { copiarDeAssets(context, "$PIPER_ASSET_DIR/espeak-ng-data", espeak) }
                .onFailure {
                    // Cópia parcial deixaria o espeak carregando pela metade e
                    // falhando de forma obscura na primeira síntese. Melhor
                    // apagar e cair no fallback.
                    espeak.deleteRecursively()
                    return@withContext null
                }
        }

        PiperTts(
            assetManager = context.assets,
            modelDir = PIPER_ASSET_DIR,
            modelName = PIPER_MODELO,
            dataDir = espeak.absolutePath,
        )
    }

    private fun copiarDeAssets(context: Context, origem: String, destino: File) {
        destino.mkdirs()
        for (nome in context.assets.list(origem) ?: emptyArray()) {
            val filho = "$origem/$nome"
            val netos = context.assets.list(filho)
            if (netos.isNullOrEmpty()) {
                context.assets.open(filho).use { input ->
                    File(destino, nome).outputStream().use { input.copyTo(it) }
                }
            } else {
                copiarDeAssets(context, filho, File(destino, nome))
            }
        }
    }
}
