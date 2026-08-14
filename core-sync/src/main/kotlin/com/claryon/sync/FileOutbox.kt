package com.claryon.sync

import java.io.File
import java.util.Base64

/**
 * [Outbox] durável em disco: um arquivo por item, nome = sequência com zero-pad
 * (garante ordem FIFO por nome). Escrita **atômica** (grava em `.tmp` e renomeia)
 * para não deixar item meio-escrito se o processo morrer no meio.
 *
 * Formato do arquivo (linhas `chave=valor`; `payload` em Base64 por poder conter
 * quebras de linha):
 * ```
 * id=<id>
 * type=<tipo>
 * createdAt=<ms>
 * attempts=<n>
 * payload=<base64>
 * ```
 *
 * Só `java.io` → testável em JVM pura, sem Android.
 */
class FileOutbox(private val dir: File) : Outbox {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    @Synchronized
    override fun enqueue(item: OutboxItem): Long {
        val seq = (maiorSeq() + 1)
        escrever(seq, item)
        return seq
    }

    @Synchronized
    override fun list(limit: Int): List<PendingItem> =
        arquivos()
            .take(limit)
            .mapNotNull { f -> ler(f)?.let { PendingItem(seqDoNome(f.name), it) } }

    @Synchronized
    override fun remove(seq: Long) {
        arquivo(seq).delete()
    }

    @Synchronized
    override fun bumpAttempts(seq: Long): Int {
        val f = arquivo(seq)
        val atual = ler(f) ?: return -1
        val novo = atual.copy(attempts = atual.attempts + 1)
        escrever(seq, novo)
        return novo.attempts
    }

    @Synchronized
    override fun size(): Int = arquivos().size

    // ---- disco ----------------------------------------------------------------

    private fun arquivos(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .sortedBy { it.name }

    private fun arquivo(seq: Long) = File(dir, nomeDoSeq(seq))

    private fun escrever(seq: Long, item: OutboxItem) {
        val tmp = File(dir, "${nomeDoSeq(seq)}.tmp")
        val payloadB64 = Base64.getEncoder().encodeToString(item.payload.toByteArray(Charsets.UTF_8))
        tmp.writeText(
            buildString {
                appendLine("id=${item.id}")
                appendLine("type=${item.type}")
                appendLine("createdAt=${item.createdAtEpochMillis}")
                appendLine("attempts=${item.attempts}")
                appendLine("payload=$payloadB64")
            },
        )
        // renomeia por cima (atômico no mesmo filesystem).
        if (!tmp.renameTo(arquivo(seq))) {
            tmp.copyTo(arquivo(seq), overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Lê um item; devolve `null` se o arquivo estiver **corrompido ou truncado**
     * (o caminho de escrita não-atômico existe como fallback, e um `.item` pela
     * metade é possível se o processo morrer no pior instante).
     *
     * Nunca propaga exceção: sem o `runCatching`, um Base64 inválido subiria por
     * `list()` → `drenar()` → worker, e **toda mensagem tática enfileirada depois
     * nunca mais sairia** — um byte ruim travaria a fila para sempre. Quem drena
     * remove os quarentenados, e a remoção é contabilizada (não é silêncio).
     */
    private fun ler(f: File): OutboxItem? = runCatching {
        if (!f.exists()) return null
        val campos = HashMap<String, String>()
        f.forEachLine { linha ->
            val i = linha.indexOf('=')
            if (i > 0) campos[linha.substring(0, i)] = linha.substring(i + 1)
        }
        val payloadB64 = campos["payload"] ?: return null
        OutboxItem(
            id = campos["id"] ?: return null,
            type = campos["type"] ?: return null,
            payload = String(Base64.getDecoder().decode(payloadB64), Charsets.UTF_8),
            createdAtEpochMillis = campos["createdAt"]?.toLongOrNull() ?: return null,
            attempts = campos["attempts"]?.toIntOrNull() ?: 0,
        )
    }.getOrNull()

    /**
     * Remove os arquivos que não parseiam. Sem isto, `size()` os contaria para
     * sempre e o worker devolveria `retry()` eternamente — bateria gasta em
     * retentativas que nunca teriam o que drenar. Devolve quantos foram removidos.
     */
    @Synchronized
    override fun descartarCorrompidos(): Int {
        var n = 0
        for (f in arquivos()) {
            if (ler(f) == null) {
                f.delete()
                n++
            }
        }
        return n
    }

    private fun maiorSeq(): Long =
        arquivos().maxOfOrNull { seqDoNome(it.name) } ?: 0L

    private fun nomeDoSeq(seq: Long): String = "%016d%s".format(seq, EXT)
    private fun seqDoNome(nome: String): Long = nome.removeSuffix(EXT).toLong()

    private companion object {
        const val EXT = ".item"
    }
}
