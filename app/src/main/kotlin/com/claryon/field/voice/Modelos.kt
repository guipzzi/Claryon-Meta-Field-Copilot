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
     * Velocidade da fala do copiloto. **Menor que 1 = mais lenta.**
     *
     * ⚠️ **Semântica NÃO confirmada em artefato deste repositório.** O `javap`
     * confirma que `OfflineTts.generate(String, Int, Float)` existe e recebe o
     * float, mas não diz o que ele significa — o sentido acima vem da fonte
     * upstream do sherpa-onnx, que não está aqui. A Regra Zero manda declarar
     * isso em vez de escrever "confirmado". **Confira de ouvido no primeiro teste
     * com fone:** se a fala acelerar em vez de desacelerar, o sentido é inverso e
     * a constante vira `1.1f`.
     *
     * Por que 10% mais lenta, e não mais rápida "para soar urgente":
     *  - O elo até os óculos é HFP 8 kHz mono (doc oficial do DAT). Acima de
     *    4 kHz não chega nada — sem as pistas espectrais das fricativas, a
     *    discriminação recai sobre **duração** e transições de formante, que são
     *    justamente o que a pressa destrói.
     *  - O agente ouve por alto-falante open-ear, na rua, com vento, trânsito e
     *    tráfego de rádio por cima.
     *  - Números saem dígito a dígito (§Design de áudio). É o conteúdo que mais
     *    sofre com pressa e o que mais custa errar.
     *  - A urgência já viaja no earcon, enfileirado **antes** da frase
     *    (`VoiceOutput.emitir`). Acelerar a fala duplicaria um sinal existente e
     *    degradaria os dois.
     *  - **Custa latência, e o custo é real.** `generate` é síntese em lote: só
     *    retorna com o áudio inteiro pronto, então falar 10% mais devagar atrasa
     *    o **início** da resposta, não só o fim. Com o teto de 7 palavras o
     *    acréscimo fica em ~0,2 s sobre a meta de 2,0 s — cabe, mas é gasto, não
     *    de graça. O jeito de não pagar seria `generateWithCallback`, que existe
     *    no mesmo AAR e não é usado (`PiperTts.kt:79`): fica registrado como a
     *    melhoria que torna esta escolha grátis.
     *
     * Abaixo de 0,9 o VITS começa a arrastar as vogais e soa artificial.
     */
    const val VELOCIDADE_DE_CAMPO = 0.9f

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
            // O parâmetro existia e já era repassado ao motor (`PiperTts:79`);
            // faltava alguém em produção passar valor. Sem esta linha, o único
            // ponto de construção do produto rodava no padrão neutro.
            speed = VELOCIDADE_DE_CAMPO,
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
