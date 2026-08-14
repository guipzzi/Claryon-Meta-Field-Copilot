package com.claryon.sync

import com.claryon.common.Result

/**
 * Resultado de uma drenagem.
 *
 * @param descartados itens que esgotaram as tentativas e foram removidos. **Não
 *   pode ser ignorado pelo chamador**: cada um é uma mensagem que o agente ouviu
 *   "na fila" e que nunca vai chegar. Silenciar isso é a pior variante de "falha
 *   é silêncio" — perda de um pedido de apoio sem qualquer sinal.
 * @param corrompidos itens ilegíveis removidos da fila.
 */
data class DrainReport(
    val enviados: Int,
    val descartados: Int,
    val restantes: Int,
    val corrompidos: Int = 0,
)

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
/**
 * @param aoDescartar chamado para **cada** item que esgotou as tentativas, antes
 *   da remoção. É o gancho por onde a perda vira earcon/telemetria — descartar
 *   em silêncio um pedido de apoio é inaceitável no contexto operacional.
 */
class OutboxDrainer(
    private val outbox: Outbox,
    private val gateway: SyncGateway,
    private val maxTentativas: Int = 5,
    private val aoDescartar: (OutboxItem) -> Unit = {},
) {
    suspend fun drenar(lote: Int = 50): DrainReport {
        // Antes de tudo: tira da frente os itens ilegíveis, senão `size()` os
        // conta para sempre e o worker retenta eternamente sem nada a drenar.
        val corrompidos = outbox.descartarCorrompidos()

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
                        aoDescartar(pend.item)
                        outbox.remove(pend.seq)
                        descartados++
                    }
                    // Rede provavelmente caiu: não insistir no resto do lote agora.
                    break
                }
            }
        }
        return DrainReport(enviados, descartados, outbox.size(), corrompidos)
    }
}
