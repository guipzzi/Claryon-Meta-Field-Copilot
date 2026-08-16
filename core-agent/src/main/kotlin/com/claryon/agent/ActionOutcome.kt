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

    /** Par localizado (C2). A fala sai de [FalaDePosicao], nunca de coordenadas. */
    data class PosicaoEncontrada(val posicao: PosicaoRelativa) : ActionOutcome

    /**
     * O par não foi localizado. **Não é falha do sistema** — é informação: pode
     * estar fora do talk group, ou sem posição recente. Inventar uma posição
     * plausível aqui seria dizer a um policial que o apoio está a 800 m quando
     * está a 6 km.
     */
    data class ParNaoLocalizado(val indicativo: String) : ActionOutcome

    /**
     * Alerta disparado (C3), com a contagem de quem recebeu.
     *
     * @param destinatarios `null` = entregue sem contagem conhecida. A mesma
     *   regra do apoio: contagem desconhecida não vira zero nem número inventado.
     */
    data class AlertaDisparado(
        val tipo: TipoDeOcorrencia,
        val prioridade: Prioridade,
        val destinatarios: Int?,
    ) : ActionOutcome

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

    /**
     * Disco no piso de reserva: o cofre parou de gravar **de propósito**.
     *
     * Tem código próprio, e não `COFRE_INDISPONIVEL`, porque a recuperação é
     * diferente e o agente precisa saber qual é. "Cofre falhou" manda procurar
     * defeito; "disco cheio" manda liberar espaço. Antes, esta condição não
     * produzia som nenhum: o cofre falhava cinquenta vezes por segundo e o
     * retorno era descartado.
     */
    SEM_ESPACO("Disco cheio."),
    GRAVACAO_JA_ATIVA("Já gravando."),
    SEM_GRAVACAO_ATIVA("Nada gravando."),
    PLACA_NAO_LIDA("Placa ilegível."),
    CONSULTA_INDISPONIVEL("Consulta indisponível."),
    SEM_REDE("Sem rede."),

    /**
     * A consulta de posição precisa da posição **própria** para dizer distância e
     * rumo. Sem ela não há resposta relativa — e a alternativa, entregar a
     * coordenada crua do par, é justamente o que a regra de C2 proíbe.
     */
    SEM_POSICAO_PROPRIA("Sem sinal de GPS."),

    /** Permissão de localização negada. Falha explícita, nunca degradação muda. */
    SEM_PERMISSAO_DE_LOCAL("Sem permissão de local."),
    NADA_A_REPETIR("Nada a repetir."),
    INTERNA("Falha interna."),
}
