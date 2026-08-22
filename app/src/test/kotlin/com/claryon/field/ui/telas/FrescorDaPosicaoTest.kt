package com.claryon.field.ui.telas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O número de destaque do produto — a idade da posição própria.
 *
 * Roda na JVM porque `FrescorDaPosicao.kt` não importa Compose, que é a razão de ele
 * existir separado da tela.
 */
class FrescorDaPosicaoTest {

    @Test
    fun oNumeroEAUnidadeSaemSEPARADOS_porqueOsEstilosSaoDois() {
        // A razão de a função existir: `Tipo.Heroi` é 44 sp Light e
        // `Tipo.HeroiUnidade` é 15 sp. Uma string "12 s" devolveria os dois no mesmo
        // corpo, que é exatamente o que a régua da referência proíbe.
        val i = idadeHeroica(12)
        assertEquals("12", i.numero)
        assertEquals("s", i.unidade)
        assertFalse(i.ausente)
    }

    @Test
    fun cadaFaixaTemSuaUnidade_eAsTresDiferem() {
        // Contra-teste: as três faixas rodam e têm de divergir. Asserir só
        // `idadeHeroica(12).unidade == "s"` passaria com uma função que devolvesse
        // "s" sempre.
        assertEquals("s" to "59", idadeHeroica(59).let { it.unidade to it.numero })
        assertEquals("min" to "1", idadeHeroica(60).let { it.unidade to it.numero })
        assertEquals("min" to "59", idadeHeroica(3599).let { it.unidade to it.numero })
        assertEquals("h" to "1", idadeHeroica(3600).let { it.unidade to it.numero })
    }

    @Test
    fun truncaParaBAIXO_porqueErrarParaCimaFariaDescartarDadoBom() {
        // 119 s é "1 min", nunca "2 min". A direção do erro é a mesma que
        // `duracaoLegivel` e a migração 0020 escolheram: nunca inventar.
        assertEquals("1", idadeHeroica(119).numero)
        assertEquals("min", idadeHeroica(119).unidade)
    }

    @Test
    fun semPosicaoNoServidor_naoInventaZero() {
        // `null` é "o servidor não tem posição minha". Sair como "0 s" seria a
        // interface afirmando a posição mais fresca possível justamente quando não
        // há posição nenhuma — a mentira mais cara que esta tela poderia contar.
        val i = idadeHeroica(null)
        assertTrue(i.ausente)
        assertEquals("", i.unidade)
        assertEquals("sem posição no servidor", rotuloDoFrescor(i))
    }

    @Test
    fun idadeNegativa_naoVaiParaATela() {
        // Relógio do servidor adiantado em relação ao do aparelho. "-3 s" num painel
        // lê como defeito do aparelho, e o dado ainda é utilizável.
        assertEquals("0", idadeHeroica(-3).numero)
        assertEquals("s", idadeHeroica(-3).unidade)
    }

    @Test
    fun comPosicao_oRotuloFalaDoSERVIDOR_naoDoGps() {
        // A distinção que a auditoria de GPS exigiu: o GPS deste aparelho pode estar
        // perfeito enquanto o POST falha. O rótulo afirma sobre quem tem o dado.
        assertEquals("no servidor", rotuloDoFrescor(idadeHeroica(40)))
    }
}
