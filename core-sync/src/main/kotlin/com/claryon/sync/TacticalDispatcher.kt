package com.claryon.sync

import com.claryon.common.Result

/**
 * Resultado do despacho — a fonte da verdade para o **feedback sonoro honesto**.
 * O TTS fala conforme o caso; nunca diz "enviado" quando só enfileirou.
 */
sealed interface Despacho {
    /** Entregue ao destino agora. */
    data class Enviada(val id: String) : Despacho
    /** Sem rede (ou falha momentânea): guardado na fila durável para subir depois. */
    data object Enfileirada : Despacho
}

/**
 * Ponto único de saída de mensagem tática. Regra de honestidade (CLAUDE.md):
 * *"Sem rede, a mensagem entra na fila e o TTS confirma 'apoio na fila' — NÃO
 * mente dizendo que enviou."*
 *
 * Tenta enviar direto; em qualquer falha, **enfileira** e devolve
 * [Despacho.Enfileirada] — o item nunca se perde e o usuário nunca é enganado.
 *
 * @param novoId gerador de id (injetável — sem relógio/aleatório no núcleo puro).
 */
class TacticalDispatcher(
    private val outbox: Outbox,
    private val gateway: SyncGateway,
    private val novoId: (TacticalMessage) -> String,
    private val agora: () -> Long,
) {
    suspend fun despachar(msg: TacticalMessage): Despacho {
        val item = TacticalMessageCodec.encode(msg, novoId(msg), agora())
        return when (gateway.push(item)) {
            is Result.Success -> Despacho.Enviada(item.id)
            is Result.Failure -> {
                outbox.enqueue(item)
                Despacho.Enfileirada
            }
        }
    }
}
