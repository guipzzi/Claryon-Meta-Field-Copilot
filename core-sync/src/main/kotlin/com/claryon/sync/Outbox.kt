package com.claryon.sync

/**
 * Um item na fila de saída. `payload` é uma string opaca (JSON do evento) — a
 * fila não conhece o formato; quem drena ([SyncGateway]) sabe interpretá-lo.
 * Manter a fila agnóstica evita acoplá-la a `TacticalMessage`/Supabase.
 */
data class OutboxItem(
    val id: String,
    val type: String,
    val payload: String,
    val createdAtEpochMillis: Long,
    val attempts: Int = 0,
)

/** Item pendente com seu identificador de posição (FIFO) no cofre. */
data class PendingItem(val seq: Long, val item: OutboxItem)

/**
 * Fila de saída **durável** — sobrevive à morte do processo (é o ponto da
 * operação offline). FIFO por [PendingItem.seq]. Política de honestidade
 * (CLAUDE.md): sem rede a mensagem **entra na fila** e o TTS confirma "na fila";
 * nunca se diz que enviou.
 */
interface Outbox {
    /** Enfileira e devolve a posição atribuída. */
    fun enqueue(item: OutboxItem): Long

    /** Até [limit] pendentes, em ordem FIFO. */
    fun list(limit: Int = Int.MAX_VALUE): List<PendingItem>

    /** Remove definitivamente (item entregue). */
    fun remove(seq: Long)

    /** Incrementa e persiste o contador de tentativas; devolve o novo valor. */
    fun bumpAttempts(seq: Long): Int

    /**
     * Remove itens ilegíveis (corrompidos/truncados) e devolve quantos foram.
     * Necessário para a fila não ficar com entradas que `list()` esconde mas
     * `size()` conta — o que faria o worker retentar para sempre.
     */
    fun descartarCorrompidos(): Int

    fun size(): Int
}
