package com.claryon.sound

/**
 * Protocolo de laconicidade — regra dura do design de áudio, verificável.
 *
 * Fala sintetizada é cara em atenção. Em contexto operacional, nenhuma resposta
 * de TTS pode exceder [MAX_WORDS] palavras. Sem cortesia ("por favor",
 * "desculpe", "tudo bem"). "Apoio solicitado, guarnição avisada." — não a versão
 * longa e educada.
 *
 * Este objeto é puro e testável (o teste automatizado de ≤7 palavras do M5
 * ancora aqui). A validação é intencionalmente conservadora: conta tokens
 * separados por espaço após colapsar espaços em branco.
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
