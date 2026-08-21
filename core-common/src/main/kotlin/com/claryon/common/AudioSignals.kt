package com.claryon.common

/**
 * Vocabulário da saída sonora — **compartilhado** entre quem decide o que o
 * agente vai ouvir (`core-agent`) e quem renderiza (`core-sound`).
 *
 * Mora em `core-common` porque os `core-*` não dependem uns dos outros: sem isto,
 * o roteador/executor não teria como dizer "isto sai como earcon, não como fala"
 * sem inventar um segundo enum e abrir espaço para as duas listas divergirem.
 */

/**
 * Prioridade de reprodução.
 *  - [EMERGENCIA] (nível 1): interrompe qualquer coisa.
 *  - [RESPOSTA]   (nível 2): resposta a comando do próprio agente; aguarda nível 1.
 *  - [INFORMATIVO](nível 3): suprimido inteiramente em Modo Tático.
 */
enum class Priority { EMERGENCIA, RESPOSTA, INFORMATIVO }

/** Biblioteca fixa de earcons — sinal não-verbal curto (150–250 ms), significado fixo. */
enum class Earcon(val significado: String) {
    OUVI_VOCE("bipe curto ascendente — 'ouvi você' (~400 ms do fim da fala)"),
    ACAO_EXECUTADA("duplo bipe curto — 'ação executada'"),
    FALHA("bipe grave descendente — 'não entendi / falhou'"),
    GRAVANDO("tom contínuo 2 s — 'gravando' (avisa agente e ambiente)"),
    PRIORITARIA("três bipes rápidos — 'mensagem prioritária chegando'"),

    // Resultado de consulta sensível: codificado no som, E falado em ≤7 palavras
    // desde 21/08 (decisão humana). Os três earcons continuam DISTINTOS de
    // propósito: eles chegam em 139 ms e a fala pode ser preemptada por P1.
    CONSULTA_SEM_RESTRICAO("1 bipe curto e neutro"),
    CONSULTA_RESTRICAO_ADMIN("2 bipes médios"),
    CONSULTA_FURTO_ROUBO("padrão de alerta distinto, 3 tons"),
}

/**
 * Protocolo de laconicidade — regra dura do design de áudio, verificável.
 *
 * Fala sintetizada é cara em atenção. Em contexto operacional, nenhuma resposta
 * de TTS pode exceder [MAX_WORDS] palavras. Sem cortesia ("por favor",
 * "desculpe", "tudo bem"). "Apoio solicitado, guarnição avisada." — não a versão
 * longa e educada.
 *
 * Puro e testável. Vive em `core-common` porque agora é aplicado **na origem**
 * (o executor, ao construir a fala a partir do resultado da ação) e não só na
 * saída.
 */
object LaconicityPolicy {

    const val MAX_WORDS = 7

    /**
     * Palavras que **sozinhas** já são cortesia, sem ambiguidade operacional.
     * "por", "tudo" e "bem" NÃO entram aqui: são preposição e advérbios comuns
     * em fala operacional legítima ("Apoio solicitado **por** rádio.", "Sem
     * restrição, **tudo** limpo.") — barrá-las reprovaria respostas válidas.
     */
    private val CORTESIA_ISOLADA = setOf(
        "favor", "desculpe", "desculpa", "obrigado", "obrigada",
    )

    /** Cortesia que só existe como locução — casada na frase inteira. */
    private val CORTESIA_LOCUCAO = listOf("por favor", "tudo bem", "com licenca", "com licença")

    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    fun isWithinLimit(text: String): Boolean = wordCount(text) <= MAX_WORDS

    fun hasCourtesy(text: String): Boolean {
        val minusculo = text.lowercase()
        if (CORTESIA_LOCUCAO.any { minusculo.contains(it) }) return true
        return minusculo.split(Regex("\\W+")).any { it in CORTESIA_ISOLADA }
    }

    /** `true` se a fala respeita o protocolo (curta e sem cortesia). */
    fun isCompliant(text: String): Boolean = isWithinLimit(text) && !hasCourtesy(text)
}
