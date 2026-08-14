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

    /**
     * Extrai a primeira placa válida encontrada no texto (ou `null`).
     *
     * Compacta **token a token**, não o texto inteiro: a janela deslizante sobre
     * tudo junto casava dentro de sequências maiores — "código ABC12345" produzia
     * `ABC1234`, e o app iria consultar uma placa que ninguém falou. Um token só
     * é candidato se, depois de tirar pontuação e espaços, tiver exatamente 7
     * caracteres. Tokens vizinhos também são unidos, para aceitar "ABC 1D23".
     */
    fun extrair(texto: String): String? {
        val tokens = texto.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        // Um token sozinho ("ABC1D23") ou dois vizinhos ("ABC" + "1D23").
        for (i in tokens.indices) {
            candidato(tokens[i])?.let { return it }
            if (i + 1 < tokens.size) {
                candidato(tokens[i] + tokens[i + 1])?.let { return it }
            }
        }
        return null
    }

    private fun candidato(bruto: String): String? {
        val p = normalizar(bruto)
        if (p.length != 7) return null
        return if (MERCOSUL.matches(p) || ANTIGA.matches(p)) p else null
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
