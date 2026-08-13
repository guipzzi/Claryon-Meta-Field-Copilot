package com.claryon.evidence

import com.claryon.common.Result

/**
 * Cofre de evidências com cadeia de custódia auditável.
 *
 * Princípios que a implementação (M6) deve honrar:
 *  - repouso: EncryptedFile + chave no Android Keystore (nem o app lê sem desbloqueio);
 *  - integridade: cada segmento com SHA-256 encadeado ao anterior;
 *  - minimização: vídeo não solicitado nunca é persistido; frame de OCR é
 *    descartado após a inferência — só o texto validado sobrevive;
 *  - a fala de terceiros é preservada como áudio bruto para fins probatórios,
 *    mas NÃO é transcrita, classificada ou indexada.
 *
 * Contrato fixado no M0.
 */
interface EvidenceVault {

    /** Abre uma gravação para uma ocorrência e devolve um handle opaco. */
    suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle>

    /** Anexa um segmento cifrado; devolve o hash encadeado do segmento. */
    suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<ChunkHash>

    /** Fecha a gravação e emite o manifesto de custódia. */
    suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest>
}

/** Contexto mínimo de uma ocorrência (georreferência e metadados na implementação). */
data class OccurrenceContext(
    val agentId: String,
    val unitId: String,
    val startedAtEpochMillis: Long,
)

/** Handle opaco de uma gravação em andamento. */
@JvmInline
value class RecordingHandle(val id: String)

/** Hash SHA-256 de um segmento, encadeado ao anterior. */
data class ChunkHash(val sequence: Int, val sha256Hex: String, val previousSha256Hex: String?)

/** Manifesto final: cadeia de hashes que comprova integridade da custódia. */
data class CustodyManifest(
    val handle: RecordingHandle,
    val chain: List<ChunkHash>,
    val finalizedAtEpochMillis: Long,
)
