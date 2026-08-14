package com.claryon.evidence

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implementação do [EvidenceVault] com repouso cifrado e custódia auditável.
 *
 *  - **Repouso:** cada segmento é gravado num [EncryptedFile] próprio (AES-256
 *    GCM via Tink), com a chave-mestra no **Android Keystore** — nem o app lê o
 *    conteúdo sem passar pelo Keystore. Como o GCM é autenticado, adulterar o
 *    ciphertext já faz a **descriptografia falhar** naquele segmento; a cadeia de
 *    hash ([HashChain]) é a segunda camada, que também apanha troca/remoção de
 *    segmentos e aponta exatamente qual quebrou.
 *  - **Custódia:** o manifesto (`manifest` — hashes, não são segredo) fica em
 *    claro para permitir verificação por terceiros sem a chave.
 *  - **Minimização:** só o que passa por [append] é persistido. Frame de OCR não
 *    entra aqui — é descartado após a inferência (ver camada de visão).
 *
 * Segmento no disco: `<filesDir>/evidence/<id>/seg_<seq>.enc`.
 *
 * @param clockMillis relógio injetável (default: relógio do sistema) — mantém a
 *   classe testável sem depender de `System.currentTimeMillis()` direto no teste.
 */
class EncryptedEvidenceVault(
    private val appContext: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : EvidenceVault {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val mutex = Mutex()
    private val sessoes = HashMap<String, Sessao>()

    private class Sessao(
        val dir: File,
        var seq: Int = 0,
        var ultimoHash: String? = null,
        val cadeia: MutableList<ChunkHash> = mutableListOf(),
    )

    override suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = "${context.unitId}_${context.agentId}_${context.startedAtEpochMillis}"
                val dir = File(File(appContext.filesDir, "evidence"), id)
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext Result.failure(
                        ClaryonError.Evidence("EVID_MKDIR", "não criou diretório da ocorrência"),
                    )
                }
                mutex.withLock { sessoes[id] = Sessao(dir) }
                Result.success(RecordingHandle(id))
            }.getOrElse { e ->
                Result.failure(ClaryonError.Evidence("EVID_BEGIN", "falha ao abrir gravação"), e)
            }
        }

    override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<ChunkHash> =
        withContext(Dispatchers.IO) {
            val sessao = mutex.withLock { sessoes[handle.id] }
                ?: return@withContext Result.failure(
                    ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                )
            runCatching {
                val seq = sessao.seq
                val arquivo = segmentFile(sessao.dir, seq)
                encryptedFile(arquivo).openFileOutput().use { it.write(chunk) }

                val prev = sessao.ultimoHash
                val hashHex = HashChain.sha256Hex(chunk, prev)
                val ch = ChunkHash(sequence = seq, sha256Hex = hashHex, previousSha256Hex = prev)

                mutex.withLock {
                    sessao.seq += 1
                    sessao.ultimoHash = hashHex
                    sessao.cadeia.add(ch)
                }
                Result.success(ch)
            }.getOrElse { e ->
                Result.failure(ClaryonError.Evidence("EVID_APPEND", "falha ao anexar segmento"), e)
            }
        }

    override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> =
        withContext(Dispatchers.IO) {
            val sessao = mutex.withLock { sessoes.remove(handle.id) }
                ?: return@withContext Result.failure(
                    ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                )
            runCatching {
                val manifesto = CustodyManifest(
                    handle = handle,
                    chain = sessao.cadeia.toList(),
                    finalizedAtEpochMillis = clockMillis(),
                )
                File(sessao.dir, MANIFEST_NOME).writeText(serializar(manifesto))
                Result.success(manifesto)
            }.getOrElse { e ->
                Result.failure(ClaryonError.Evidence("EVID_FINALIZE", "falha ao emitir manifesto"), e)
            }
        }

    /**
     * Reabre os segmentos cifrados, descriptografa e reconfere a cadeia de hash
     * contra o [manifesto]. Retorna o **índice do primeiro segmento adulterado**
     * (descriptografia falhou ou hash divergiu), ou `-1` se íntegro.
     */
    suspend fun verificar(handle: RecordingHandle, manifesto: CustodyManifest): Int =
        withContext(Dispatchers.IO) {
            val dir = File(File(appContext.filesDir, "evidence"), handle.id)
            val segmentos = ArrayList<ByteArray>(manifesto.chain.size)
            for (ch in manifesto.chain) {
                val bytes = try {
                    encryptedFile(segmentFile(dir, ch.sequence)).openFileInput().use { it.readBytes() }
                } catch (_: Throwable) {
                    // GCM não autenticou (byte adulterado) ou segmento sumiu.
                    return@withContext ch.sequence
                }
                segmentos.add(bytes)
            }
            HashChain.verificar(segmentos, manifesto.chain.map { it.sha256Hex })
        }

    private fun segmentFile(dir: File, seq: Int): File =
        File(dir, "seg_%05d.enc".format(seq))

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /** Manifesto serializado em linhas simples — hashes não são segredo. */
    private fun serializar(m: CustodyManifest): String = buildString {
        appendLine("handle=${m.handle.id}")
        appendLine("finalizedAt=${m.finalizedAtEpochMillis}")
        for (c in m.chain) appendLine("${c.sequence}\t${c.previousSha256Hex ?: "-"}\t${c.sha256Hex}")
    }

    private companion object {
        const val MANIFEST_NOME = "manifest.txt"
    }
}
