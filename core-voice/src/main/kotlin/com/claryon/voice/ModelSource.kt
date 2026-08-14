package com.claryon.voice

import android.content.res.AssetManager
import com.whispercpp.whisper.WhisperContext
import java.io.File

/**
 * De onde vem o modelo de transcrição.
 *
 * A distinção não é detalhe de implementação: decide se existe IA local no
 * aparelho que a organização entrega às 09h30 do dia da demonstração. Modelo que
 * só existe no pacote de testes **não existe no produto**.
 */
sealed interface ModelSource {

    /** `true` se o modelo está de fato acessível — checado antes de transcrever. */
    fun existe(): Boolean

    /** Abre o contexto nativo. Só chamar depois de [existe]. */
    fun abrir(): WhisperContext

    /**
     * Modelo empacotado no APK, em `assets/`. **Caminho de produção.**
     *
     * Vantagem sobre baixar no primeiro uso: instala com o app, roda offline, e
     * não depende do Wi-Fi do evento.
     *
     * O JNI abre o asset com `AASSET_MODE_STREAMING` e lê por `AAsset_read`
     * (verificado em `src/main/cpp/jni.c`), então **funciona mesmo com o asset
     * comprimido** — `noCompress` no build é otimização de tempo de carga e de
     * pico de memória, não pré-requisito de funcionamento. Por isso a checagem
     * abaixo usa `open` (vale nos dois casos) e não `openFd`, que só teria êxito
     * com o asset armazenado sem compressão e reprovaria um APK perfeitamente
     * funcional.
     */
    data class Asset(val assets: AssetManager, val caminho: String) : ModelSource {
        override fun existe(): Boolean =
            runCatching { assets.open(caminho).close() }.isSuccess

        override fun abrir(): WhisperContext =
            WhisperContext.createContextFromAsset(assets, caminho)

        override fun toString(): String = "asset:$caminho"
    }

    /**
     * Modelo no sistema de arquivos (`adb push`, download por WorkManager).
     * Serve para desenvolvimento e para trocar de modelo sem reinstalar o app.
     */
    data class Arquivo(val caminho: String) : ModelSource {
        override fun existe(): Boolean = File(caminho).exists()

        override fun abrir(): WhisperContext =
            WhisperContext.createContextFromFile(caminho)

        override fun toString(): String = "arquivo:$caminho"
    }
}
