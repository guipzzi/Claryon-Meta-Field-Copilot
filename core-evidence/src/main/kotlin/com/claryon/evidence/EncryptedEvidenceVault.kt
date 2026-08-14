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
                mutex.withLock {
                    // Reabrir uma ocorrência já existente reiniciaria `seq` em 0 e
                    // sobregravaria seg_00000.enc — destruindo evidência já
                    // gravada. O id é (unidade, agente, início): colisão só
                    // acontece por engano de orquestração, e falhar é o certo.
                    if (sessoes.containsKey(id) || segmentosExistentes(dir)) {
                        return@withLock Result.failure(
                            ClaryonError.Evidence(
                                "EVID_JA_ABERTA",
                                "já existe gravação para esta ocorrência — finalize antes de reabrir",
                            ),
                        )
                    }
                    sessoes[id] = Sessao(dir)
                    Result.success(RecordingHandle(id))
                }
            }.getOrElse { e ->
                Result.failure(ClaryonError.Evidence("EVID_BEGIN", "falha ao abrir gravação"), e)
            }
        }

    /**
     * Anexa um segmento. **Toda** a operação (ler `seq`/`ultimoHash`, cifrar,
     * gravar, encadear) roda sob o `mutex`.
     *
     * Ler a sequência fora do lock e gravar dentro parece suficiente, mas não é:
     * dois `append` concorrentes no mesmo handle leriam `seq=0` e `prev=null`,
     * gravariam **ambos** em `seg_00000.enc` — o segundo sobrescrevendo o
     * primeiro — e a cadeia ficaria com duas entradas `sequence=0`. Perda
     * silenciosa de evidência, que é exatamente o que este módulo existe para
     * impedir. O custo de serializar é irrelevante perto disso.
     */
    override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<ChunkHash> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessao = sessoes[handle.id]
                    ?: return@withLock Result.failure(
                        ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                    )
                runCatching {
                    val seq = sessao.seq
                    val prev = sessao.ultimoHash
                    encryptedFile(segmentFile(sessao.dir, seq)).openFileOutput().use { it.write(chunk) }

                    val hashHex = HashChain.sha256Hex(chunk, prev)
                    val ch = ChunkHash(sequence = seq, sha256Hex = hashHex, previousSha256Hex = prev)

                    sessao.seq += 1
                    sessao.ultimoHash = hashHex
                    sessao.cadeia.add(ch)
                    // Manifesto parcial a cada segmento: se o processo morrer no
                    // meio de uma ocorrência, os segmentos no disco continuam
                    // tendo cadeia de custódia demonstrável (sem isto, evidência
                    // cifrada sem manifesto = evidência inútil).
                    escreverManifesto(sessao.dir, handle, sessao.cadeia, finalizado = false)
                    Result.success(ch)
                }.getOrElse { e ->
                    Result.failure(ClaryonError.Evidence("EVID_APPEND", "falha ao anexar segmento"), e)
                }
            }
        }

    override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessao = sessoes.remove(handle.id)
                    ?: return@withLock Result.failure(
                        ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                    )
                runCatching {
                    val manifesto = CustodyManifest(
                        handle = handle,
                        chain = sessao.cadeia.toList(),
                        finalizedAtEpochMillis = clockMillis(),
                    )
                    escreverManifesto(sessao.dir, handle, manifesto.chain, finalizado = true)
                    Result.success(manifesto)
                }.getOrElse { e ->
                    Result.failure(ClaryonError.Evidence("EVID_FINALIZE", "falha ao emitir manifesto"), e)
                }
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

    private fun segmentosExistentes(dir: File): Boolean =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".enc") }?.isNotEmpty() == true

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /**
     * Manifesto em linhas simples — hashes não são segredo, e deixá-lo em claro
     * é o que permite a um terceiro verificar a custódia **sem** a chave.
     *
     * `finalizado=false` marca uma gravação interrompida (processo morto no meio
     * da ocorrência): a cadeia até ali continua verificável e demonstrável.
     */
    private fun escreverManifesto(
        dir: File,
        handle: RecordingHandle,
        cadeia: List<ChunkHash>,
        finalizado: Boolean,
    ) {
        val texto = buildString {
            appendLine("handle=${handle.id}")
            appendLine("finalizado=$finalizado")
            if (finalizado) appendLine("finalizedAt=${clockMillis()}")
            for (c in cadeia) appendLine("${c.sequence}\t${c.previousSha256Hex ?: "-"}\t${c.sha256Hex}")
        }
        // Escrita atômica: um manifesto truncado é pior que nenhum.
        val tmp = File(dir, "$MANIFEST_NOME.tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(File(dir, MANIFEST_NOME))) {
            tmp.copyTo(File(dir, MANIFEST_NOME), overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val MANIFEST_NOME = "manifest.txt"
    }
}
