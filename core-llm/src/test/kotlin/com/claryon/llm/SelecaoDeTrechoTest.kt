package com.claryon.llm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O baseline sem modelo tem de jogar o MESMO jogo da Pista 2 — senão a
 * comparação é encenada.**
 *
 * Se ele pudesse escolher fora do espaço que a gramática admite, ganharia por
 * ter mais liberdade; se pudesse escolher menos, perderia por ter menos. Os dois
 * casos transformariam *"o LLM não estava contribuindo"* numa conclusão sobre a
 * jaula, não sobre o modelo.
 */
class SelecaoDeTrechoTest {

    private val art311 =
        "Art. 311. Adulterar, remarcar ou suprimir número de chassi, monobloco, motor, " +
            "placa de identificação, ou qualquer sinal identificador de veículo automotor: " +
            "Pena - reclusão, de três a seis anos, e multa."

    /**
     * **A escolha vive dentro da gramática.** É a propriedade que faz do baseline
     * um controle e não um concorrente com outras regras.
     */
    @Test
    fun oEscolhidoENecessariamenteAdmitidoPelaGramatica() {
        for (teto in listOf(5, 7, 12)) {
            val escolhido = SelecaoDeTrecho.escolher(art311, "quantos anos de cadeia", teto)
            assertNotNull("Nada escolhido com teto $teto", escolhido)
            assertTrue(
                "O escolhido não é trecho contíguo da fonte: \"$escolhido\"",
                GramaticaDaFonte.eTrechoContiguo(escolhido!!, art311),
            )
            val palavras = escolhido.split(Regex("""\s+""")).count { it.isNotEmpty() }
            assertTrue("Estourou o teto $teto com $palavras palavras", palavras <= teto)
            assertTrue(
                "Devolveu menos que ${SelecaoDeTrecho.MINIMO_DE_PALAVRAS} palavras: não é fala",
                palavras >= SelecaoDeTrecho.MINIMO_DE_PALAVRAS,
            )
        }
    }

    /**
     * **Perguntas diferentes escolhem trechos diferentes.**
     *
     * Contra-teste: sem isto, `escolher` poderia devolver sempre as primeiras N
     * palavras e todos os testes acima passariam igual — a família de teste que o
     * `CLAUDE.md` §6 chama de nome sem corpo.
     */
    @Test
    fun aPerguntaMudaAEscolha() {
        val sobrePena = SelecaoDeTrecho.escolher(art311, "reclusão pena multa cadeia", 7)
        val sobreConduta = SelecaoDeTrecho.escolher(art311, "adulterar remarcar chassi motor", 7)
        assertTrue(
            "A pergunta não foi lida: as duas deram \"$sobrePena\"",
            sobrePena != sobreConduta,
        )
    }

    /** Fonte sem palavra não produz escolha — e não produz string vazia. */
    @Test
    fun fonteVazia_naoEscolheNada() {
        assertNull(SelecaoDeTrecho.escolher("", "qualquer coisa"))
        assertNull(SelecaoDeTrecho.escolher("   \n ", "qualquer coisa"))
    }

    /**
     * **A penalidade por comprimento existe e funciona.** Sem ela a nota é
     * monótona no comprimento e o vencedor é sempre o trecho do tamanho do teto —
     * o teto vira a resposta e a escolha deixa de escolher.
     */
    @Test
    fun oTetoNaoViraAResposta() {
        // Pergunta que casa com UMA raiz só, bem no começo de uma cláusula: sem
        // penalidade, o vencedor teria 12 palavras porque nenhuma delas tira nota.
        val escolhido = SelecaoDeTrecho.escolher(art311, "reclusão", 12)!!
        val palavras = escolhido.split(Regex("""\s+""")).count { it.isNotEmpty() }
        assertTrue(
            "Devolveu o teto inteiro ($palavras palavras) — a penalidade não pesa: " +
                "\"$escolhido\"",
            palavras < 12,
        )
    }
}
