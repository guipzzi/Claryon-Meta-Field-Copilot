package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **Regressão do defeito que quebrava a troca de grupo por voz.**
 *
 * Medido no aparelho em 2026-08-17: o Whisper devolveu `"...guarnição 4."` — com
 * ponto final, como todo decodificador de STT faz no fim de frase. A normalização
 * não removia pontuação, então `"guarnicao 4."` nunca casava `"guarnicao 4"` e a
 * feature recusava **toda** troca.
 *
 * Nenhum teste de unidade pegava: todos alimentavam rótulos escritos à mão, sempre
 * limpos. Foi o teste da corrente inteira que achou.
 */
class RotulosFaladosNormalizacaoTest {

    @Test
    fun pontuacaoDoSttNaoImpedeOCasamento() {
        val doServidor = RotulosFalados.normalizar("guarnicao 4")
        // Exatamente o que o ggml-tiny produziu no aparelho.
        val doStt = RotulosFalados.normalizar("guarnição 4.")
        assertEquals("o ponto final do STT não pode impedir o casamento", doServidor, doStt)
    }

    @Test
    fun asOutrasPontuacoesQueOSttProduz() {
        val alvo = RotulosFalados.normalizar("guarnicao 4")
        for (variante in listOf(
            "guarnição 4.", "guarnição 4,", "Guarnição 4!", "guarnição 4?",
            "  guarnição   4 . ", "\"guarnição 4\"", "guarnição 4…",
        )) {
            assertEquals(
                "'$variante' deveria normalizar para o mesmo rótulo",
                alvo,
                RotulosFalados.normalizar(variante),
            )
        }
    }

    /**
     * **O contra-teste: dígito e palavra continuam DIFERENTES.**
     *
     * O léxico do servidor grava `'guarnicao 3'` (migração `0011:93`) e o Whisper
     * também produz dígito — os dois lados convergem sem tradução. Colapsar "três"
     * em "3" aqui inventaria uma equivalência que o cadastro não pediu, e abriria a
     * porta para "três" casar "treze": no despacho, duas viaturas diferentes.
     */
    @Test
    fun digitoEPalavraNaoSaoAMesmaCoisa() {
        assertNotEquals(
            RotulosFalados.normalizar("guarnicao 3"),
            RotulosFalados.normalizar("guarnicao tres"),
        )
        assertNotEquals(
            RotulosFalados.normalizar("guarnicao 3"),
            RotulosFalados.normalizar("guarnicao 13"),
        )
    }

    @Test
    fun acentoECaixaNaoImportam() {
        val alvo = RotulosFalados.normalizar("guarnicao 3")
        for (v in listOf("Guarnição 3", "GUARNIÇÃO 3", "guarniçao 3")) {
            assertEquals(v, alvo, RotulosFalados.normalizar(v))
        }
    }
}
