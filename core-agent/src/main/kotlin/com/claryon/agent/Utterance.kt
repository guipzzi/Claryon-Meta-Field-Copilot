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

    is ActionOutcome.PosicaoEncontrada ->
        Utterance.Falar(FalaDePosicao.para(outcome.posicao), Priority.RESPOSTA)

    is ActionOutcome.ParNaoLocalizado ->
        // Não é earcon de falha: o sistema funcionou, o par é que não está
        // localizável. Tratar como erro faria o agente duvidar do rádio.
        Utterance.Falar(FalaDePosicao.naoEncontrado(outcome.indicativo), Priority.RESPOSTA)

    is ActionOutcome.AlertaDisparado -> when (outcome.destinatarios) {
        null -> Utterance.Falar("Alerta enviado.", Priority.EMERGENCIA)
        0 -> Utterance.Falar("Alerta enviado. Ninguém próximo.", Priority.EMERGENCIA)
        1 -> Utterance.Falar("Uma unidade recebeu.", Priority.EMERGENCIA)
        else -> Utterance.Falar(
            "${porExtenso(outcome.destinatarios)} unidades receberam.",
            Priority.EMERGENCIA,
        )
    }

    is ActionOutcome.GrupoTrocado ->
        // Earcon ANTES da fala (aceite 8 da spec): o efeito aqui é redirecionar a
        // voz do agente, e confirmação cega ("pronto") não serve — ele precisa
        // ouvir PARA ONDE foi.
        Utterance.SinalizarEFalar(
            Earcon.ACAO_EXECUTADA,
            "Agora na ${nomeFalavelDeGrupo(outcome.nome)}.",
            Priority.RESPOSTA,
        )

    is ActionOutcome.GrupoNaoReconhecido ->
        // **Não** distingue "não existe" de "existe e você não é membro": a
        // distinção é informação sobre a estrutura da corporação. Mesmo princípio
        // do servidor, que devolve grandezas e nunca coordenada de terceiro.
        // O rótulo dito NÃO é repetido de volta — repetir confirmaria ao ouvido de
        // quem está por perto qual grupo foi tentado.
        Utterance.SinalizarEFalar(
            Earcon.FALHA,
            "Não conheço essa guarnição.",
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

/**
 * Recorta o nome de um talk group para caber no teto de sete palavras.
 *
 * O nome vem do **cadastro**, não do código: "GTA-3 Alfa" cabe, "Grupamento
 * Tático de Ações Especiais Três" não. Deixar o teto depender de os cadastros
 * serem curtos é confiar em disciplina de terceiro para sustentar um invariante do
 * produto — e o invariante existe porque fala longa por HFP em viatura não é
 * ouvida, é ruído.
 *
 * Quatro palavras: com "Agora na " na frente, o total fecha em seis.
 *
 * **Risco declarado, a medir com fone real:** nomes de unidade brasileiros são
 * cheios de sigla ("GTA", "ROTAM", "GATE"), e sigla falada letra por letra vive
 * acima do corte de 4 kHz do HFP em banda estreita — é o mesmo motivo pelo qual
 * "Claryon" foi escolhida como palavra de ativação. Se em hardware real a sigla
 * não for compreensível, a saída é uma coluna `nome_falado` no cadastro, e não
 * mudar isto aqui. Fica registrado em `specs/troca-de-grupo-por-voz.spec.md`.
 */
private fun nomeFalavelDeGrupo(nome: String): String =
    nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4).joinToString(" ")
