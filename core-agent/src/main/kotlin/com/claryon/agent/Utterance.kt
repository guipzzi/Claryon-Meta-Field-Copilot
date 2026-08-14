package com.claryon.agent

import com.claryon.common.Earcon
import com.claryon.common.Priority

/**
 * O que o agente ouve. Sempre derivado de um [ActionOutcome] — ver [utteranceFor].
 */
sealed interface Utterance {

    val priority: Priority

    /** Fala sintetizada. Sujeita ao protocolo de laconicidade (≤ 7 palavras). */
    data class Falar(val texto: String, override val priority: Priority) : Utterance

    /** Só sinal. Resultado sensível **nunca** é falado. */
    data class Sinalizar(val earcon: Earcon, override val priority: Priority) : Utterance

    /** Sinal seguido de fala curta — usado em falha (earcon + causa em três palavras). */
    data class SinalizarEFalar(
        val earcon: Earcon,
        val texto: String,
        override val priority: Priority,
    ) : Utterance
}

/**
 * **Constrói a resposta a partir do resultado da ação — jamais do comando.**
 *
 * Esta é a função que fecha a regra de honestidade do produto. Repare no que
 * *não* existe neste arquivo: nenhuma sobrecarga `utteranceFor(intent: Intent)`.
 * Isso é deliberado e não deve ser acrescentado. Enquanto o único parâmetro for
 * [ActionOutcome], é **impossível** — não improvável, impossível — que o app
 * fale "Apoio solicitado" sem que o apoio tenha sido de fato despachado, porque
 * não há como obter um [ActionOutcome] sem passar pelo [IntentExecutor].
 *
 * Regras de saída aplicadas aqui:
 *  - resultado de consulta sensível sai como earcon codificado, nunca falado;
 *  - "gravando" é tom contínuo de 2 s **sem fala** (avisa o agente e o ambiente);
 *  - toda falha tem earcon próprio — falha nunca é silêncio;
 *  - toda fala respeita ≤ 7 palavras, sem cortesia (há teste que varre todos os
 *    ramos deste `when`).
 */
fun utteranceFor(outcome: ActionOutcome): Utterance = when (outcome) {

    is ActionOutcome.ApoioTransmitido -> when (outcome.destinatarios) {
        // Entregue, sem contagem: afirma só o que se sabe.
        null -> Utterance.Falar("Apoio enviado.", Priority.EMERGENCIA)
        // Honestidade: saiu, mas não havia ninguém por perto. Dizer só "Apoio
        // solicitado" faria o agente contar com uma unidade que não existe.
        0 -> Utterance.Falar("Enviado. Nenhuma unidade próxima.", Priority.EMERGENCIA)
        1 -> Utterance.Falar("Uma unidade recebeu.", Priority.EMERGENCIA)
        else -> Utterance.Falar(
            "${porExtenso(outcome.destinatarios)} unidades receberam.",
            Priority.EMERGENCIA,
        )
    }

    ActionOutcome.ApoioEnfileirado ->
        Utterance.SinalizarEFalar(Earcon.FALHA, "Sem rede. Na fila.", Priority.EMERGENCIA)

    is ActionOutcome.GravacaoIniciada ->
        // Tom de 2 s, sem fala: avisa quem está ao redor de que há gravação.
        Utterance.Sinalizar(Earcon.GRAVANDO, Priority.RESPOSTA)

    is ActionOutcome.GravacaoEncerrada ->
        Utterance.SinalizarEFalar(Earcon.ACAO_EXECUTADA, "Gravação encerrada.", Priority.RESPOSTA)

    is ActionOutcome.PlacaConsultada ->
        // NUNCA falado: o alto-falante open-ear vaza para o abordado.
        Utterance.Sinalizar(
            when (outcome.restricao) {
                Restricao.SEM_RESTRICAO -> Earcon.CONSULTA_SEM_RESTRICAO
                Restricao.ADMINISTRATIVA -> Earcon.CONSULTA_RESTRICAO_ADMIN
                Restricao.FURTO_ROUBO -> Earcon.CONSULTA_FURTO_ROUBO
            },
            Priority.RESPOSTA,
        )

    is ActionOutcome.OcorrenciaRegistrada ->
        Utterance.SinalizarEFalar(Earcon.ACAO_EXECUTADA, "Ocorrência registrada.", Priority.RESPOSTA)

    is ActionOutcome.ModoTrocado -> Utterance.Falar(
        when (outcome.modo) {
            ModoOperacao.STANDBY -> "Modo standby."
            ModoOperacao.ATIVO -> "Modo ativo."
            ModoOperacao.OCORRENCIA -> "Modo ocorrência."
        },
        Priority.RESPOSTA,
    )

    ActionOutcome.NaoEntendi ->
        Utterance.SinalizarEFalar(Earcon.FALHA, "Não entendi, repita.", Priority.RESPOSTA)

    is ActionOutcome.Falhou ->
        Utterance.SinalizarEFalar(Earcon.FALHA, outcome.falha.causaCurta, Priority.RESPOSTA)
}

/**
 * Números falados por extenso até doze. Acima disso, o dígito a dígito da regra
 * geral seria pior aqui ("um-cinco unidades" soa como código, não como
 * contagem); o limite prático de uma guarnição não passa disso.
 */
private fun porExtenso(n: Int): String = when (n) {
    2 -> "Duas"
    3 -> "Três"
    4 -> "Quatro"
    5 -> "Cinco"
    6 -> "Seis"
    7 -> "Sete"
    8 -> "Oito"
    9 -> "Nove"
    10 -> "Dez"
    11 -> "Onze"
    12 -> "Doze"
    else -> n.toString()
}
