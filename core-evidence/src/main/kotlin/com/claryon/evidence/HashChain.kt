package com.claryon.evidence

import java.security.MessageDigest

/**
 * Cadeia de hash (SHA-256 encadeado) da cadeia de custódia.
 *
 * Cada segmento recebe `sha256(hash_anterior + bytes_do_segmento)`. Adulterar
 * **qualquer byte** de qualquer segmento quebra a cadeia a partir daquele ponto,
 * de forma detectável e **demonstrável em juízo** (aponta exatamente o segmento).
 *
 * Puro (só `java.security`) — testável em JVM.
 */
object HashChain {

    /** Hash de um segmento, encadeado ao [anteriorHex] (ou início, se `null`). */
    fun sha256Hex(dados: ByteArray, anteriorHex: String?): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (anteriorHex != null) md.update(anteriorHex.toByteArray(Charsets.US_ASCII))
        md.update(dados)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifica a integridade da cadeia. Retorna o **índice do primeiro segmento
     * adulterado, faltante ou excedente**, ou `-1` se a cadeia está íntegra.
     *
     * Percorre o **maior** dos dois tamanhos de propósito: iterar só sobre
     * `segmentos` faria uma cadeia truncada no fim (atacante apaga os últimos
     * arquivos e deixa o manifesto intacto) passar como íntegra — os que sobram
     * batem, e ninguém repara nos que sumiram.
     */
    fun verificar(segmentos: List<ByteArray>, hashes: List<String>): Int {
        var anterior: String? = null
        for (i in 0 until maxOf(segmentos.size, hashes.size)) {
            // Segmento sem hash (excedente) ou hash sem segmento (removido).
            if (i >= segmentos.size || i >= hashes.size) return i
            val esperado = sha256Hex(segmentos[i], anterior)
            if (hashes[i] != esperado) return i
            anterior = hashes[i]
        }
        return -1
    }
}
