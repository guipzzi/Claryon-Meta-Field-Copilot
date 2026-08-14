package com.claryon.sync

import com.claryon.common.Result

/**
 * Destino remoto de um item da fila (Supabase, na implementação real). Isolado
 * por interface para que a **política de fila** seja testável sem rede: em teste
 * usamos [FakeSyncGateway]; em produção, `SupabaseSyncGateway`.
 */
interface SyncGateway {
    /** Empurra um item. Sucesso ⇒ pode sair da fila; falha ⇒ permanece para retry. */
    suspend fun push(item: OutboxItem): Result<Unit>
}

/** Gateway de teste: registra o que recebeu e pode simular indisponibilidade. */
class FakeSyncGateway(
    @Volatile var online: Boolean = true,
    private val erro: com.claryon.common.ClaryonError =
        com.claryon.common.ClaryonError.Sync("SYNC_OFFLINE", "sem rede"),
) : SyncGateway {
    val recebidos = mutableListOf<OutboxItem>()

    override suspend fun push(item: OutboxItem): Result<Unit> =
        if (online) {
            recebidos.add(item)
            Result.success(Unit)
        } else {
            Result.failure(erro)
        }
}
