package com.claryon.sync

import com.claryon.common.Result

/** Resultado de uma drenagem: quantos saíram, quantos ainda restam. */
data class DrainReport(val enviados: Int, val descartados: Int, val restantes: Int)

/**
 * Drena a [Outbox] pelo [gateway], em ordem FIFO.
 *
 *  - sucesso ⇒ remove o item;
 *  - falha ⇒ incrementa tentativas; se exceder [maxTentativas], **descarta** e
 *    registra (não trava a fila para sempre num item veneno);
 *  - **para na primeira falha** de rede (não adianta tentar os próximos se caiu),
 *    deixando o resto para a próxima janela do WorkManager.
 *
 * Puro: sem Android, sem WorkManager — testável com [FileOutbox] + [FakeSyncGateway].
 */
class OutboxDrainer(
    private val outbox: Outbox,
    private val gateway: SyncGateway,
    private val maxTentativas: Int = 5,
) {
    suspend fun drenar(lote: Int = 50): DrainReport {
        var enviados = 0
        var descartados = 0
        for (pend in outbox.list(lote)) {
            when (gateway.push(pend.item)) {
                is Result.Success -> {
                    outbox.remove(pend.seq)
                    enviados++
                }
                is Result.Failure -> {
                    val tent = outbox.bumpAttempts(pend.seq)
                    if (tent >= maxTentativas) {
                        outbox.remove(pend.seq)
                        descartados++
                    }
                    // Rede provavelmente caiu: não insistir no resto do lote agora.
                    break
                }
            }
        }
        return DrainReport(enviados, descartados, outbox.size())
    }
}
