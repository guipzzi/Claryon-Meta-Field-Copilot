package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A porta tem uma regra só, e estes testes existem para que ela não possa ser
 * removida sem alguém ficar sabendo: **abaixo do limiar é recusa, nunca o mais
 * próximo.**
 *
 * Um teste que só confirma "o melhor candidato volta quando é bom" passaria
 * também numa implementação sem limiar nenhum — por isso os casos abaixo são
 * escritos como contraprova: cada um falha se a regra sumir.
 */
class PortaDoConhecimentoTest {

    private val porte = Trecho(
        texto = "É livre a manifestação do pensamento...",
        citacao = "Art. 5º, IV",
        norma = "Constituição Federal",
    )

    private val transito = Trecho(
        texto = "Conduzir veículo sem possuir Carteira Nacional de Habilitação...",
        citacao = "Art. 162, I",
        norma = "Lei 9.503/1997 — Código de Trânsito Brasileiro",
    )

    @Test
    fun acimaDoLimiar_devolveOTrechoParaLeitura() {
        val porta = PortaDoConhecimento(limiar = 0.60)

        val escolhido = porta.escolher(listOf(Candidato(porte, 0.81)))

        assertEquals(porte, escolhido)
    }

    @Test
    fun abaixoDoLimiar_recusa_emVezDeDevolverOMaisProximo() {
        val porta = PortaDoConhecimento(limiar = 0.60)

        // Há candidato, ele é o melhor da lista, e ainda assim a resposta é "não
        // sei". Uma implementação sem limiar devolveria `transito` aqui — um
        // artigo do CTB para quem perguntou de arma emperrada.
        val escolhido = porta.escolher(
            listOf(Candidato(transito, 0.31), Candidato(porte, 0.12)),
        )

        assertNull("Abaixo do limiar a resposta é recusa, não aproximação.", escolhido)
    }

    @Test
    fun noLimiarExato_aceita() {
        val porta = PortaDoConhecimento(limiar = 0.60)

        assertEquals(porte, porta.escolher(listOf(Candidato(porte, 0.60))))
    }

    @Test
    fun semCandidato_recusa() {
        assertNull(PortaDoConhecimento().escolher(emptyList()))
    }

    @Test
    fun escolheOMaisSimilar_naoOPrimeiroDaLista() {
        val porta = PortaDoConhecimento(limiar = 0.60)

        val escolhido = porta.escolher(
            listOf(Candidato(transito, 0.63), Candidato(porte, 0.92)),
        )

        assertEquals(
            "A ordem da lista não é a ordem de relevância; quem decide é a similaridade.",
            porte,
            escolhido,
        )
    }

    @Test
    fun osMesmosCandidatos_comLimiaresDiferentes_decidemDiferente() {
        val candidatos = listOf(Candidato(porte, 0.70))

        val frouxa = PortaDoConhecimento(limiar = 0.50).escolher(candidatos)
        val severa = PortaDoConhecimento(limiar = 0.90).escolher(candidatos)

        // O ponto do par: se o limiar deixar de ser consultado, os dois lados
        // passam a devolver a mesma coisa e este teste cai.
        assertEquals(porte, frouxa)
        assertNull(severa)
        assertNotEquals(frouxa, severa)
    }
}
