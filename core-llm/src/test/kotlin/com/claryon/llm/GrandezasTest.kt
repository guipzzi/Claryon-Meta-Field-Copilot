package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A leitura de número, com o par exigido em cada regra.**
 *
 * Cada caso aqui é um dos três defeitos medidos em 21/08 sobre 268 gerações, ou
 * o contra-caso dele. Um extrator que só acerta o defeito serve para o relatório
 * e reprova texto fiel no aparelho.
 */
class GrandezasTest {

    private fun valores(t: String) = Grandezas.valores(t)
    private fun extrair(t: String) = Grandezas.extrair(t)

    // ------------------------------------------------- causa 1: dígito comum

    /**
     * **O defeito, em uma linha.** A régua velha via `1` e `5`; os dois existem em
     * `"§ 1º"` e `"§ 5º"`, e `"1,5 gramas"` passava.
     */
    @Test
    fun umVirgulaCincoEUmNumeroSo_naoDoisDigitos() {
        assertEquals(setOf(1.5), valores("1,5 gramas"))
        // O par: a fonte com os dois parágrafos NÃO contém o valor 1,5.
        assertEquals(setOf(1.0, 5.0), valores("§ 1º e § 5º"))
        assertTrue(1.5 !in valores("§ 1º e § 5º"))
    }

    @Test
    fun pontoEMilharEVirgulaEDecimal() {
        assertEquals(setOf(1500.0), valores("1.500 dias-multa"))
        assertEquals(setOf(293.47), valores("R$ 293,47"))
        assertEquals(setOf(11343.0), valores("Lei 11.343"))
        assertEquals(setOf(0.3), valores("0,3 mg/L"))
    }

    // ------------------------------------------------- causa 2: por extenso

    @Test
    fun oExtensoViraValor() {
        assertEquals(setOf(4.0), valores("quatro quilos"))
        assertEquals(setOf(1500.0), valores("mil e quinhentos"))
        assertEquals(setOf(293.0), valores("duzentos e noventa e tres"))
        assertEquals(setOf(15.0), valores("quinze anos"))
    }

    /**
     * **A fonte também escreve por extenso**, e é isso que faz a normalização ser
     * simétrica em vez de virar reprovação em massa: o art. 306 do CTB diz
     * *"detenção, de seis meses a três anos"* sem um dígito sequer.
     */
    @Test
    fun aFonteEmExtensoDaOsMesmosValoresQueAsaidaEmDigito() {
        val fonte = valores("penas - detencao, de seis meses a tres anos")
        assertTrue(6.0 in fonte && 3.0 in fonte)
        val saida = valores("detencao de 6 meses a 3 anos")
        assertTrue(saida.all { it in fonte })
    }

    /** `"6 (seis) decigramas"` é um número, não dois, e a unidade é dele. */
    @Test
    fun oParentesePorExtensoNaoDuplicaNemPerdeAUnidade() {
        val g = extrair("6 (seis) decigramas de alcool por litro de sangue")
        assertEquals(setOf(6.0), g.map { it.valor }.toSet())
        assertTrue(g.all { it.classe == Grandezas.Classe.MASSA })
    }

    // ------------------------------------------------- causa 3: unidade

    @Test
    fun aUnidadeSeparaOMesmoValorEmDuasAfirmacoes() {
        val multa = extrair("500 dias-multa").single()
        val peso = extrair("500 gramas").single()
        assertEquals(multa.valor, peso.valor, 0.0)
        assertTrue("mesmo valor não pode ser a mesma grandeza", multa.classe != peso.classe)
    }

    /**
     * **A faixa propaga a unidade para trás.** `"de 500 a 1.500 dias-multa"` só
     * escreve a unidade uma vez; sem a propagação, metade de toda faixa legal
     * ficaria sem classe e a reatribuição escaparia pela borda de baixo.
     */
    @Test
    fun aFaixaDaAUnidadeAosDoisExtremos() {
        val g = extrair("de 500 (quinhentos) a 1.500 (mil e quinhentos) dias-multa")
        assertEquals(setOf(500.0, 1500.0), g.map { it.valor }.toSet())
        assertTrue(
            "os dois extremos são dias-multa: $g",
            g.all { it.classe == Grandezas.Classe.DIAS_MULTA },
        )
    }

    // ------------------------------------------------- o que fica de fora

    /**
     * **Ordinal e romano ficam fora, e isso é escolha.** `"em primeiro lugar"` não
     * é afirmação numérica; convertê-lo criaria um valor `1` que precisaria de
     * lastro e reprovaria prosa correta. O custo: `"o primeiro grau"` não é
     * conferido. Está declarado no KDoc de [Grandezas].
     */
    @Test
    fun ordinalERomanoNaoViramValor() {
        assertTrue(valores("em primeiro lugar, o inciso II do artigo").isEmpty())
    }
}
