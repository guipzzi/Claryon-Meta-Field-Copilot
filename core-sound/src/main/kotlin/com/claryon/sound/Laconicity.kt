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

    private val CORTESIA = setOf(
        "por", "favor", "desculpe", "desculpa", "obrigado", "obrigada", "tudo", "bem",
    )

    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    fun isWithinLimit(text: String): Boolean = wordCount(text) <= MAX_WORDS

    fun hasCourtesy(text: String): Boolean =
        text.lowercase().split(Regex("\\W+")).any { it in CORTESIA }

    /** `true` se a fala respeita o protocolo (curta e sem cortesia). */
    fun isCompliant(text: String): Boolean = isWithinLimit(text) && !hasCourtesy(text)
}
