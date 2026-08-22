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

    /**
     * **O pedido não chegou ao árbitro.** Não é "ocupado": ninguém disse que
     * está falando — nós é que não conseguimos perguntar.
     *
     * Existe porque a ausência dele custava a AÇÃO do agente. Até 22/08,
     * `ClienteDePisoRemoto` devolvia `Ocupado(detentor = "?")` quando o RPC não
     * voltava, e o aparelho tocava o mesmo tom de "canal ocupado". As duas causas
     * pedem gestos **opostos** em campo: canal ocupado se resolve **esperando** o
     * colega soltar o botão; falta de rede se resolve **andando** até pegar sinal.
     * Um agente que espera embaixo de um viaduto espera para sempre.
     *
     * Continua **não** presumindo concedido — falar achando que se tem o canal
     * sobrepõe voz no ouvido de quem está numa ocorrência. O que muda é o que o
     * agente ouve.
     */
    data object SemRede : ResultadoDoPedido

    /**
     * **O árbitro respondeu, e a resposta foi não.**
     *
     * Distinto de [SemRede] e de [Ocupado]: houve rede, houve resposta, e ela é
     * uma recusa de autorização — não-membro do talk group
     * (`0005_controle_de_piso.sql:78-82`), token vencido, agente inativo.
     * Colapsar isto em "sem rede" mandaria o agente procurar torre por um
     * problema de credencial, que é a mesma classe de mentira que [SemRede]
     * existe para desfazer.
     *
     * @param motivo texto de diagnóstico do servidor. **Nunca vai ao
     *   alto-falante**: quem fala é [com.claryon.agent.utteranceFor] a partir de
     *   um desfecho tipado, e texto livre que vira fala é porta por onde conteúdo
     *   arbitrário alcança o ouvido do agente.
     */
    data class Recusado(val motivo: String) : ResultadoDoPedido
}

/**
 * **O que aconteceu quando o canal foi devolvido ao grupo.**
 *
 * `liberar` era `Unit`, e isso não era economia de tipo: era um desfecho
 * apagado. Observado em 22/08 pela bateria de caos — `liberar_canal` falhando,
 * a sessão encerrando com [EventoPtt.Encerrada] normal, e a guarnição inteira
 * muda por 30 s (o TTL) sem log nem tom. Quem falou acha que devolveu o canal.
 *
 * Três camadas escondiam o mesmo fato (`ClientesDePiso.kt:142-157` sem
 * `onFailure`, `:110-112` descartando o `Boolean?`, `SessaoPtt.kt:377` com o
 * `runCatching` engolindo). Um tipo de retorno fecha as três de uma vez, porque
 * o compilador passa a exigir que alguém olhe.
 */
sealed interface ResultadoDaLiberacao {

    /**
     * O canal voltou ao grupo — ou já não era nosso, o que dá no mesmo para quem
     * espera para falar.
     */
    data object Devolvido : ResultadoDaLiberacao

    /**
     * **A devolução não chegou ao árbitro.** O piso fica preso até o TTL vencer
     * — 30 s de guarnição muda, e a única válvula é uma P1.
     *
     * @param motivo diagnóstico, para o log. Não vira fala: ver [ResultadoDoPedido.Recusado].
     */
    data class NaoDevolvido(val motivo: String) : ResultadoDaLiberacao
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
 * @param ehMembro pertencimento ao talk group, consultado a **cada** pedido e a
 *   **cada** renovação. Espelha o predicado que o Postgres aplica: `pedir_canal`
 *   conferia desde a `0005`, `renovar_canal` só passou a conferir na `0024`.
 *
 *   O padrão permissivo existe para os usos em que não há grupo a consultar
 *   (política isolada, demonstração de um aparelho só) e **não** para tornar a
 *   checagem opcional em produção: quem constrói esta política sobre um cadastro
 *   real passa o predicado, e o servidor a aplica de todo modo.
 */
class ControleDePiso(
    private val ttlMs: Long = TTL_PADRAO_MS,
    private val ehMembro: (talkGroupId: String, agenteId: String) -> Boolean = { _, _ -> true },
) {

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
        // Espelha `0005_controle_de_piso.sql:78-82`: quem não é do grupo não pede
        // o canal do grupo. Recusa TIPADA e não `Ocupado` — "ocupado" mandaria o
        // agente esperar por uma vez que nunca chega.
        if (!ehMembro(talkGroupId, agenteId)) {
            return ResultadoDoPedido.Recusado("nao pertence ao talk group")
        }

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
     * não é dele (expirou, foi tomado, ou ele saiu do grupo) — sinal para parar
     * de transmitir.
     *
     * **Sair do grupo derruba o piso na hora, e não no TTL.** Até 22/08 só
     * `pedir` conferia pertencimento: quem era removido da guarnição no meio da
     * fala continuava renovando indefinidamente, parava de ser ouvido (a política
     * de `realtime.messages` derruba a entrega) e **continuava detendo o canal**.
     * A guarnição legítima ficava muda com o piso na mão de quem já não pertencia
     * a ela. Por isso a concessão é **removida** aqui, e não só recusada: deixá-la
     * de pé até o TTL cobraria os mesmos 30 s de silêncio de quem ficou.
     */
    fun renovar(concessao: Concessao, agoraMs: Long): Boolean {
        val atual = detentorVigente(concessao.talkGroupId, agoraMs) ?: return false
        if (atual.transmissaoId != concessao.transmissaoId) return false
        if (!ehMembro(atual.talkGroupId, atual.agenteId)) {
            detentores.remove(atual.talkGroupId)
            return false
        }
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
