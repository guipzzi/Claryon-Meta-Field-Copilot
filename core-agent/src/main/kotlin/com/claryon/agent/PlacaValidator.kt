package com.claryon.agent

/**
 * Validação e extração de placas brasileiras — **Mercosul** (ABC1D23) e **padrão
 * antigo** (ABC1234). Pura e testável; usada tanto pelo roteador (comando por
 * voz) quanto pelo OCR (leitura pela câmera).
 */
object PlacaValidator {

    private val MERCOSUL = Regex("[A-Z]{3}[0-9][A-Z][0-9]{2}") // ABC1D23
    private val ANTIGA = Regex("[A-Z]{3}[0-9]{4}")             // ABC1234

    enum class Formato { MERCOSUL, ANTIGA }

    /** Remove tudo que não é letra/dígito e sobe para maiúsculas. */
    fun normalizar(texto: String): String =
        texto.uppercase().filter { it.isLetterOrDigit() }

    /** Extrai a primeira placa válida encontrada no texto (ou `null`). */
    fun extrair(texto: String): String? {
        val compacto = normalizar(texto)
        var i = 0
        while (i + 7 <= compacto.length) {
            val cand = compacto.substring(i, i + 7)
            if (MERCOSUL.matches(cand) || ANTIGA.matches(cand)) return cand
            i++
        }
        return null
    }

    fun isValida(placa: String): Boolean = formato(placa) != null

    fun formato(placa: String): Formato? {
        val p = normalizar(placa)
        return when {
            MERCOSUL.matches(p) -> Formato.MERCOSUL
            ANTIGA.matches(p) -> Formato.ANTIGA
            else -> null
        }
    }
}
