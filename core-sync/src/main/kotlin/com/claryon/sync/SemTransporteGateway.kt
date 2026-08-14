package com.claryon.sync

import com.claryon.common.ClaryonError
import com.claryon.common.Result

/**
 * Gateway que **nunca entrega**, porque o transporte tático (`core-net`) ainda
 * não existe.
 *
 * Não é placeholder inerte: é a declaração honesta do estado atual. Com ele
 * instalado, todo despacho cai em [Despacho.Enfileirada], o agente ouve
 * *"Sem rede. Na fila."*, e a mensagem fica guardada em disco até existir
 * transporte. É exatamente o comportamento correto — e exercita, desde já, o
 * caminho de degradação que a banca vai testar.
 *
 * A alternativa (não instalar gateway nenhum e deixar o despacho "funcionar")
 * seria voltar a mentir.
 */
object SemTransporteGateway : SyncGateway {
    override suspend fun push(item: OutboxItem): Result<Unit> = Result.failure(
        ClaryonError.Sync(
            "SYNC_SEM_TRANSPORTE",
            "Transporte tático ainda não configurado — item permanece na fila.",
        ),
    )
}
