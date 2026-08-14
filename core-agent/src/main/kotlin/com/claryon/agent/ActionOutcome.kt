package com.claryon.agent

/**
 * **Resultado de uma ação que realmente aconteceu.**
 *
 * Este tipo existe para fechar a lacuna central do produto: até aqui, a frase
 * falada era escolhida a partir da *intenção* — o app dizia "Apoio solicitado"
 * sem ter enviado nada e "Gravação iniciada" sem gravar. Um copiloto de
 * segurança pública que afirma ter pedido apoio quando não pediu é **pior que a
 * ausência do produto**, porque o agente para de procurar o rádio.
 *
 * A regra, garantida por assinatura e não por disciplina: `utteranceFor` aceita
 * [ActionOutcome] e **não existe sobrecarga que aceite [Intent]**. Não há
 * caminho no código em que a fala seja construída antes de a ação ser executada.
 */
sealed interface ActionOutcome {

    /**
     * Apoio entregue agora.
     *
     * @param destinatarios quantas unidades receberam. **`null` = entregue, mas
     *   a contagem é desconhecida** — é o caso enquanto o servidor não devolve
     *   `{destinatarios: n}`. Inventar um número aqui (ou assumir zero) seria
     *   mentir sobre quem está a caminho, que é a única coisa que o agente
     *   precisa saber. A fala muda de acordo.
     */
    data class ApoioTransmitido(val destinatarios: Int?) : ActionOutcome

    /** Sem rede: guardado na fila durável. O agente **precisa** saber disso. */
    data object ApoioEnfileirado : ActionOutcome

    /** Cofre de evidência aberto; [id] identifica a ocorrência. */
    data class GravacaoIniciada(val id: String) : ActionOutcome

    /** Cofre fechado, manifesto de custódia emitido com [segmentos] segmentos. */
    data class GravacaoEncerrada(val segmentos: Int) : ActionOutcome

    /**
     * Placa consultada. O resultado é **sensível** e sai como earcon codificado,
     * nunca falado — o alto-falante *open-ear* vaza som para quem está ao lado.
     */
    data class PlacaConsultada(val placa: String, val restricao: Restricao) : ActionOutcome

    /** Ocorrência narrada e registrada. */
    data class OcorrenciaRegistrada(val id: String) : ActionOutcome

    /** Modo de operação trocado de fato (energia, câmera e rede já reconfiguradas). */
    data class ModoTrocado(val modo: ModoOperacao) : ActionOutcome

    /** Transcrição não casou com nenhuma intenção. Não é falha: é "repita". */
    data object NaoEntendi : ActionOutcome

    /** A ação foi tentada e falhou. Nunca silêncio — [falha] vira earcon + causa curta. */
    data class Falhou(val falha: FalhaOperacional) : ActionOutcome
}

/** Situação de uma placa consultada. */
enum class Restricao { SEM_RESTRICAO, ADMINISTRATIVA, FURTO_ROUBO }

/**
 * Causas de falha operacional, com a **causa em três palavras** que vai ao
 * ouvido junto do earcon. Código estável para telemetria e para o mapeamento
 * erro → earcon.
 */
enum class FalhaOperacional(val causaCurta: String) {
    SEM_ROTA_DE_AUDIO("Sem rota."),
    COFRE_INDISPONIVEL("Cofre falhou."),
    GRAVACAO_JA_ATIVA("Já gravando."),
    SEM_GRAVACAO_ATIVA("Nada gravando."),
    PLACA_NAO_LIDA("Placa ilegível."),
    CONSULTA_INDISPONIVEL("Consulta indisponível."),
    SEM_REDE("Sem rede."),
    NADA_A_REPETIR("Nada a repetir."),
    INTERNA("Falha interna."),
}
