package com.claryon.voice

import android.content.Context
import android.speech.SpeechRecognizer
import com.claryon.common.ClaryonError
import com.claryon.common.Result

/**
 * Back-ends de STT. **A implementação nativa é a próxima tarefa (M4-nativo)** —
 * é o marco de maior risco de ambiente (NDK, `.so` por ABI, modelos em assets),
 * que o Guia recomenda resolver e commitar semanas antes do hackathon.
 *
 * Enquanto o build nativo não entra, ambos declaram `isAvailable() = false` e o
 * pipeline degrada graciosamente (o comando vira `NaoReconhecida` → earcon de
 * falha), sem inventar transcrição.
 */

/**
 * STT primário — **whisper.cpp** (C/C++) via JNI, modelo `ggml-tiny` quantizado
 * em assets. Processa em **lote**: recebe o PCM da janela fechada pelo VAD e
 * devolve o texto. Não é streaming.
 *
 * Setup pendente (M4-nativo): submódulo/clonagem de `ggml-org/whisper.cpp`,
 * `externalNativeBuild` (CMake) por ABI, wrapper JNI, download do modelo via
 * `WorkManager` (não versionar o `.bin`).
 */
class WhisperCppStt : SttEngine {
    override val id: String = "whisper.cpp/ggml-tiny"
    override suspend fun isAvailable(): Boolean = false // TODO(M4-nativo): checar libnative + modelo
    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript> =
        Result.failure(
            ClaryonError.Voice("stt.whisper_not_built", "whisper.cpp ainda não compilado (M4-nativo)."),
        )
}

/**
 * STT de **fallback** nativo do Android. Nota de arquitetura: o `SpeechRecognizer`
 * **captura o próprio áudio** (streaming, orientado a eventos) e **não** aceita um
 * `ShortArray` de PCM — logo não encaixa direto em `transcribe(pcm)`. O caminho
 * correto do fallback é um fluxo auto-capturador separado, a ser adicionado no
 * M4-nativo, usando `createOnDeviceSpeechRecognizer` + `EXTRA_PREFER_OFFLINE`
 * (nunca o reconhecedor padrão, que **vaza áudio** para servidor).
 *
 * `isAvailable()` reflete a disponibilidade real do reconhecedor on-device.
 */
class AndroidOnDeviceStt(private val context: Context) : SttEngine {
    override val id: String = "android.SpeechRecognizer(on-device)"

    override suspend fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context.applicationContext) }
            .getOrDefault(false)

    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript> =
        Result.failure(
            ClaryonError.Voice(
                "stt.android_needs_self_capture",
                "SpeechRecognizer captura o próprio áudio; caminho auto-capturador é M4-nativo.",
            ),
        )
}

/**
 * Seleciona o primeiro [SttEngine] disponível (primário → fallback). Enquanto
 * nenhum estiver pronto, o chamador trata a ausência como `NaoReconhecida`.
 */
class SttSelector(private val engines: List<SttEngine>) {
    suspend fun firstAvailable(): SttEngine? = engines.firstOrNull { it.isAvailable() }
}
