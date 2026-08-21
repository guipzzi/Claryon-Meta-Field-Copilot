package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * O tipo carrega a garantia: **não existe trecho sem procedência**. Se fosse
 * regra de uso — "lembre de citar" —, um dia alguém esqueceria, e o agente
 * ouviria uma regra com autoridade e sem origem.
 */
class TrechoTest {

    @Test
    fun trechoSemCitacao_naoPodeSerConstruido() {
        assertThrows(IllegalArgumentException::class.java) {
            Trecho(texto = "Qualquer texto de norma", citacao = "   ", norma = "Lei 10.826/2003")
        }
    }

    @Test
    fun trechoSemNorma_naoPodeSerConstruido() {
        assertThrows(IllegalArgumentException::class.java) {
            Trecho(texto = "Qualquer texto de norma", citacao = "Art. 12", norma = "")
        }
    }

    @Test
    fun trechoSemTexto_naoPodeSerConstruido() {
        assertThrows(IllegalArgumentException::class.java) {
            Trecho(texto = "\n\t ", citacao = "Art. 12", norma = "Lei 10.826/2003")
        }
    }

    @Test
    fun oTextoSobreviveIntacto_porqueEleSeraLidoVerbatim() {
        val comoEstaPublicado =
            "Art. 28.  Quem adquirir, guardar, tiver em depósito, transportar ou " +
                "trouxer consigo, para consumo pessoal, drogas sem autorização..."

        val trecho = Trecho(
            texto = comoEstaPublicado,
            citacao = "Art. 28",
            norma = "Lei 11.343/2006",
        )

        // Nenhum trim, nenhuma normalização de espaço duplo, nenhum corte: o TTS
        // lê exatamente isto, e o que a norma escreveu é o que o agente ouve.
        assertEquals(comoEstaPublicado, trecho.texto)
    }

    @Test
    fun similaridadeForaDaReguaDoCosseno_naoPodeSerConstruida() {
        val trecho = Trecho("texto", "Art. 1º", "Lei 10.826/2003")

        for (invalida in listOf(1.5, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(
                "Similaridade $invalida deveria ser recusada: o limiar só significa " +
                    "algo se todas as implementações medirem na mesma régua.",
                IllegalArgumentException::class.java,
            ) {
                Candidato(trecho, invalida)
            }
        }
    }
}
