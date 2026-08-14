package com.claryon.net

/** Canal concedido a um emissor, por tempo limitado. */
data class Concessao(
    val talkGroupId: String,
    val agenteId: String,
    val transmissaoId: String,
    val prioridade: PrioridadeTransmissao,
    val expiraEmMs: Long,
)

/** Resposta a um pedido de canal. */
sealed interface ResultadoDoPedido {

    /** Pode falar. */
    data class Concedido(val concessao: Concessao) : ResultadoDoPedido

    /**
     * Outro agente está falando. O cliente responde com **tom de ocupado
     * imediato** e não grava — comportamento de rádio, reconhecível na hora.
     */
    data class Ocupado(val detentor: Concessao) : ResultadoDoPedido

    /**
     * Emergência tomou o canal de quem falava. O interrompido recebe um tom
     * distinto informando que perdeu o piso — perder a palavra em silêncio
     * faria o agente seguir falando para ninguém.
     */
    data class Tomado(val interrompido: Concessao, val concessao: Concessao) : ResultadoDoPedido
}

/**
 * **Controle de piso: um emissor por vez, por talk group.**
 *
 * Sem ele, duas vozes se sobrepõem no ouvido de quem está numa ocorrência — que é
 * exatamente o momento em que a inteligibilidade importa mais.
 *
 * Política pura, sem relógio interno: o tempo entra por parâmetro. Isso permite
 * testar expiração de TTL sem esperar 30 segundos, e é a mesma lógica que roda no
 * servidor (a Edge Function `transmit` chama esta política).
 *
 * @param ttlMs validade da concessão. Renovada a cada poucos segundos de fala; se
 *   o cliente morrer no meio (app fechado, bateria acabou), a trava expira sozinha
 *   e o canal volta ao grupo — sem isso, um crash calaria a guarnição inteira.
 */
class ControleDePiso(private val ttlMs: Long = TTL_PADRAO_MS) {

    private val detentores = HashMap<String, Concessao>()

    /**
     * Pede o canal.
     *
     * **Não bloqueie a captura esperando este resultado.** O `AudioRecord` começa
     * no instante do toque e os quadros vão para o buffer local; se o pedido for
     * negado, descarta-se o que foi capturado. Bloquear a captura pela rede
     * transformaria latência de rede em fala perdida.
     */
    fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
        agoraMs: Long,
    ): ResultadoDoPedido {
        val atual = detentorVigente(talkGroupId, agoraMs)
        val nova = Concessao(talkGroupId, agenteId, transmissaoId, prioridade, agoraMs + ttlMs)

        if (atual == null) {
            detentores[talkGroupId] = nova
            return ResultadoDoPedido.Concedido(nova)
        }

        // O mesmo agente pedindo de novo (retry por rede instável) renova em vez
        // de receber "ocupado" por si próprio.
        if (atual.agenteId == agenteId) {
            detentores[talkGroupId] = nova
            return ResultadoDoPedido.Concedido(nova)
        }

        // Emergência toma o canal de prioridade menor. Emergência **não** toma de
        // outra emergência: cortar quem já está numa ocorrência em curso é pior
        // que esperar, e duas P1 se revezando indefinidamente calariam as duas.
        val tomaOCanal = prioridade == PrioridadeTransmissao.P1_EMERGENCIA &&
            atual.prioridade != PrioridadeTransmissao.P1_EMERGENCIA
        if (tomaOCanal) {
            detentores[talkGroupId] = nova
            return ResultadoDoPedido.Tomado(interrompido = atual, concessao = nova)
        }

        return ResultadoDoPedido.Ocupado(atual)
    }

    /**
     * Estende a concessão enquanto o agente fala. Devolve `false` se o canal já
     * não é dele (expirou ou foi tomado) — sinal para parar de transmitir.
     */
    fun renovar(concessao: Concessao, agoraMs: Long): Boolean {
        val atual = detentorVigente(concessao.talkGroupId, agoraMs) ?: return false
        if (atual.transmissaoId != concessao.transmissaoId) return false
        detentores[concessao.talkGroupId] = atual.copy(expiraEmMs = agoraMs + ttlMs)
        return true
    }

    /** Solta o canal ao soltar o PTT. Só o detentor consegue liberar. */
    fun liberar(concessao: Concessao) {
        val atual = detentores[concessao.talkGroupId] ?: return
        if (atual.transmissaoId == concessao.transmissaoId) {
            detentores.remove(concessao.talkGroupId)
        }
    }

    /** Quem detém o canal agora, considerando expiração. `null` = livre. */
    fun detentorVigente(talkGroupId: String, agoraMs: Long): Concessao? {
        val atual = detentores[talkGroupId] ?: return null
        if (agoraMs >= atual.expiraEmMs) {
            detentores.remove(talkGroupId)
            return null
        }
        return atual
    }

    companion object {
        /** 30 s: cobre a duração máxima de uma transmissão, renovada durante a fala. */
        const val TTL_PADRAO_MS = 30_000L
    }
}
