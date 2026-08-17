package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

// ── Dimensionamento da janela do encoder ─────────────────────────────────────
// Derivados de `whisper.h` e `whisper.cpp` no artefato vendorizado, não de
// memória. Ver o KDoc de `WhisperContext.audioCtxPara`.

/** `WHISPER_HOP_LENGTH` (160, `whisper.h:35`) × stride 2 das convoluções. */
private const val AMOSTRAS_POR_POSICAO = 320

/** `GGML_PAD(n_audio_ctx, 256)` (`whisper.cpp:2487`): abaixo disto não se ganha. */
private const val AUDIO_CTX_MINIMO = 256

/** `n_audio_ctx` do modelo (`whisper.cpp:592`). Acima disto o whisper retorna -5. */
private const val AUDIO_CTX_MAXIMO = 1500

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * **A janela do encoder, dimensionada pela fala em vez de por trinta segundos.**
     *
     * O Whisper preenche a entrada com zeros até 30 s (`whisper.cpp:3203`) e o
     * encoder roda sobre `n_audio_ctx = 1500` posições (`whisper.cpp:592`) —
     * mesmo para um comando de dois segundos. Medido no emulador: **18 000 a
     * 48 000 ms** de STT para 2,1 s de fala, contra a meta de 2 000 ms do ciclo
     * inteiro.
     *
     * A conta sai das constantes do artefato, não de memória: `WHISPER_HOP_LENGTH`
     * é 160 (`whisper.h:35`) e as convoluções do encoder têm stride 2, logo
     * **uma posição de contexto = 320 amostras**. Confere com o padrão:
     * 30 s × 16 kHz ÷ 320 = 1500. *Derivado de `whisper.h` e `whisper.cpp` em
     * 2026-08-17.*
     *
     * ## Os dois limites, e por que existem
     *
     * **Piso de 256:** `whisper.cpp:2487` faz `GGML_PAD(n_audio_ctx, 256)`, então
     * qualquer valor abaixo de 256 é arredondado para cima na alocação e não compra
     * nada. 256 posições = ~5,1 s, que cobre qualquer comando do produto com folga.
     *
     * **Teto de 1500:** é o do modelo, e o próprio whisper recusa valor maior
     * (`whisper.cpp:6983-6987`, retorna -5). Áudio mais longo que 30 s é fatiado
     * pelo whisper de qualquer forma.
     *
     * A margem de 10% existe porque o espectrograma acrescenta *reflective pad* no
     * fim (`whisper.cpp:3203`) e cortar exatamente no último quadro de fala
     * arriscaria truncar a última sílaba — que em português é onde vive a flexão.
     */
    private fun audioCtxPara(amostras: Int): Int {
        val posicoes = (amostras + AMOSTRAS_POR_POSICAO - 1) / AMOSTRAS_POR_POSICAO
        val comMargem = (posicoes * 11) / 10
        return comMargem.coerceIn(AUDIO_CTX_MINIMO, AUDIO_CTX_MAXIMO)
    }

    suspend fun transcribeData(data: FloatArray, printTimestamp: Boolean = true): String = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        val audioCtx = audioCtxPara(data.size)
        Log.d(LOG_TAG, "Selecting $numThreads threads, audio_ctx=$audioCtx")
        WhisperLib.fullTranscribe(ptr, numThreads, audioCtx, data)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val textTimestamp = "[${toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))} --> ${toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))}]"
                    val textSegment = WhisperLib.getTextSegment(ptr, i)
                    append("$textTimestamp: $textSegment\n")
                } else {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }
        }
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            var loadDotprod = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    // **dotprod e a variante preferida, e a ordem importa.**
                    //
                    // "asimddp" e como o /proc/cpuinfo do Linux nomeia a feature que
                    // o compilador chama de "dotprod". Os kernels quantizados do
                    // ggml (55 usos de __ARM_FEATURE_DOTPROD) sao onde o tempo e
                    // gasto com modelo q5_1, e sem a flag eles compilam no caminho
                    // de fallback.
                    //
                    // Sem a feature, cair para v8fp16_va e correto. Carregar um .so
                    // com dotprod num aparelho que nao tem daria SIGILL — nao ha
                    // degradacao suave possivel, e por isso a escolha e por
                    // biblioteca e nao por flag.
                    if (cpuInfo.contains("fphp")) {
                        if (cpuInfo.contains("asimddp")) {
                            Log.d(LOG_TAG, "CPU supports fp16 + dotprod")
                            loadDotprod = true
                        } else {
                            Log.d(LOG_TAG, "CPU supports fp16 arithmetic (sem dotprod)")
                            loadV8fp16 = true
                        }
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadDotprod) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va_dotprod.so")
                System.loadLibrary("whisper_v8fp16_va_dotprod")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioCtx: Int,
            audioData: FloatArray,
        )
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}