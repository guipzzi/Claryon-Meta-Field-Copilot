package com.claryon.voice

import com.claryon.common.Result

/**
 * Contratos de STT. Há dois **formatos** legítimos e distintos:
 *  - [SttEngine] (em `VoicePipeline.kt`): recebe **PCM já capturado** e devolve
 *    texto — o formato do **whisper** (lote). Ver [WhisperCppStt].
 *  - [SelfCapturingStt]: **captura o próprio áudio** e transcreve — o formato do
 *    `SpeechRecognizer` do Android. Ver `AndroidOnDeviceStt`.
 */

/** STT auto-capturador (escuta o microfone por conta própria). */
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
