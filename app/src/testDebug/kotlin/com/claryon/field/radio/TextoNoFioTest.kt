package com.claryon.field.radio

import com.claryon.field.ui.telas.FalaNoGrupo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **A decisão da transcrição na origem que eu tinha justificado sem testar.**
 *
 * A verificação ponta a ponta de 20/08 provou o acumulador (80 000 amostras = os
 * 5,0 s que entraram), o quarto evento e o texto **idêntico dos dois lados**. Ela
 * passou ao largo de onde o texto pousa na tela, porque o caminho medido não sobe
 * até o `ViewModel`.
 *
 * O que fica coberto aqui é justamente o cenário perigoso: **duas falas em voo**. O
 * critério da recebida é a chave, e este teste falharia se alguém "simplificasse"
 * para "o último balão".
 */
class TextoNoFioTest {

    private fun fala(id: String, propria: Boolean = false, texto: String = "") = FalaNoGrupo(
        id = id,
        indicativo = if (propria) "Bravo Um" else "Alfa Dois",
        hora = "14:32",
        texto = texto,
        propria = propria,
        prioridade = null,
        entrega = FalaNoGrupo.Entrega.RECEBIDA,
    )

    /**
     * **O erro que não se perdoa num rádio.**
     *
     * O texto da fala de `tx-1` chega depois de `tx-2` já ter começado. Casar pela
     * posição poria a frase de um agente embaixo do nome de outro — e o colega
     * atribuiria a ordem errada à pessoa errada, numa ocorrência.
     */
    @Test
    fun oTextoRecebidoPousaNaFalaCerta_mesmoComOutraNoMeio() {
        val antes = listOf(fala("tx-1"), fala("tx-2"))
        val depois = comTexto(antes, "tx-1", "pedir apoio na Praça A", propria = false)

        assertEquals("pedir apoio na Praça A", depois.first { it.id == "tx-1" }.texto)
        assertEquals("a fala seguinte foi contaminada", "", depois.first { it.id == "tx-2" }.texto)
    }

    /**
     * A própria fala não tem `transmissaoId` no balão — ele nasce em `aoSoltar()` e
     * o id só existe dentro da `SessaoPtt`. O candidato é o balão próprio mais
     * recente ainda sem texto.
     */
    @Test
    fun oTextoProprioPousaNoBalaoLocalRecemCriado() {
        val antes = listOf(
            fala("tx-1", texto = "já transcrita"),
            fala("local-1", propria = true, texto = "fala anterior minha"),
            fala("local-2", propria = true),
        )
        val depois = comTexto(antes, "tx-qualquer", "guarnição 3 na escuta", propria = true)

        assertEquals("guarnição 3 na escuta", depois[2].texto)
        assertEquals("a fala própria anterior foi sobrescrita", "fala anterior minha", depois[1].texto)
    }

    /**
     * **Texto sem balão é descartado, não vira balão.**
     *
     * Criar um do zero produziria uma fala sem hora e sem indicativo — a interface
     * inventando conteúdo, que é o que este produto proíbe.
     */
    @Test
    fun textoSemBalaoCorrespondenteEhDescartado() {
        val antes = listOf(fala("tx-1", texto = "x"))
        assertEquals(antes, comTexto(antes, "tx-inexistente", "sumiu", propria = false))
        assertEquals("um balão próprio foi inventado", antes, comTexto(antes, "tx-9", "sumiu", propria = true))
    }

    /**
     * Uma transcrição que chega atrasada não pode apagar a que já estava lá: o
     * agente veria o texto sumir e passaria a duvidar do que leu. Balão próprio já
     * preenchido não é candidato.
     */
    @Test
    fun oTextoProprioNaoSobrescreveOQueJaFoiPreenchido() {
        val antes = listOf(fala("local-1", propria = true, texto = "primeira"))
        assertEquals(antes, comTexto(antes, "tx-1", "segunda", propria = true))
    }
}
