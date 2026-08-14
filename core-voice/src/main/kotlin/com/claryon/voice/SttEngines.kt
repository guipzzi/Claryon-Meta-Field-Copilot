package com.claryon.voice

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
 * STT **auto-capturador**: escuta o microfone e transcreve um comando por conta
 * própria (orientado a eventos), diferente do [SttEngine] de **buffer** (que
 * recebe PCM já capturado, como o whisper). São dois formatos distintos e ambos
 * legítimos — o `SpeechRecognizer` do Android é auto-capturador.
 */
interface SelfCapturingStt {
    suspend fun isAvailable(): Boolean
    /** Escuta e transcreve UM comando. Trata `ERROR_NO_MATCH` como "não entendi". */
    suspend fun recognizeOnce(): Result<Transcript>
}

/**
 * Seleciona o primeiro [SttEngine] de buffer disponível (primário → fallback).
 * Enquanto nenhum estiver pronto, o chamador trata a ausência como `NaoReconhecida`.
 */
class SttSelector(private val engines: List<SttEngine>) {
    suspend fun firstAvailable(): SttEngine? = engines.firstOrNull { it.isAvailable() }
}
