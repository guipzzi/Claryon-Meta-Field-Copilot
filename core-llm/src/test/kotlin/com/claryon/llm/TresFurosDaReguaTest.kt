package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Os três furos que fizeram a régua reprovar ZERO de 268 gerações.**
 *
 * Em 21/08 mediu-se: 300 gerações do Llama-3.2-1B sobre o corpus, **25 de 268
 * inventaram número que a lei não fixa**, o guarda humano aprovou 23 — e a régua de
 * cifras reprovou **nenhuma**. Não foi azar de amostra. A régua era
 * `Regex("""\d+""")` sobre a fonte, e os três modos de invenção passavam por baixo
 * dela por construção:
 *
 *  1. **Extenso não tem dígito.** `"trinta dias"` não casa `\d+`, então a régua não
 *     via número nenhum para conferir.
 *  2. **Reatribuição reusa a cifra da fonte.** `"500 gramas"` onde a fonte diz
 *     `"500 dias-multa"`: a cifra `500` **está** na fonte, então passava. É a pior
 *     das três, porque o resultado *parece* citação.
 *  3. **Decimal vira duas cifras comuns.** `"1,5 grama"` virava `1` e `5`, e é difícil
 *     achar norma brasileira que não contenha ambos.
 *
 * Cada teste abaixo usa um caso que a régua antiga **aprovava**. Se alguém devolver a
 * cifra crua, estes testes reprovam — que é a definição de contra-teste do
 * `CLAUDE.md §6`, pergunta 3: *se o teste passaria com o defeito de volta, ele não
 * testa o defeito.*
 */
class TresFurosDaReguaTest {

    /** Lastro alto de propósito: o que decide aqui é a régua 1, não a 2. */
    private val guarda = GuardaDaRedacao(lastroMinimo = 0.0)

    @Test
    fun furo1_numeroPorExtensoQueAFonteNaoTem_reprova() {
        val fonte = "Art. 28. Quem adquirir, guardar, tiver em depósito drogas para " +
            "consumo pessoal será submetido a advertência sobre os efeitos das drogas."

        assertNull(
            "\"trinta dias\" não está na fonte, e a régua de cifras não via porque " +
                "extenso não tem dígito. Se isto passou, `Grandezas` foi desligada " +
                "do guarda e o furo 1 voltou.",
            guarda.aprovar("A pena é de trinta dias de advertência.", fonte),
        )
    }

    @Test
    fun furo2_reatribuicao_mesmaCifraOutraGrandeza_reprova() {
        val fonte = "Art. 33. Pena — reclusão de 5 (cinco) a 15 (quinze) anos e " +
            "pagamento de 500 (quinhentos) a 1.500 (mil e quinhentos) dias-multa."

        assertNull(
            "\"500 gramas\" reusa a cifra 500 da fonte, mas a fonte fala em " +
                "DIAS-MULTA. Trocar a grandeza mantendo o número é a invenção mais " +
                "perigosa: parece citação. Se passou, a régua voltou a comparar " +
                "cifra solta.",
            guarda.aprovar("Configura tráfico a partir de 500 gramas.", fonte),
        )

        // E o contra-caso: a MESMA cifra na MESMA classe tem de passar, senão a régua
        // vira ruído e o produto volta à leitura verbatim.
        assertNotNull(
            "500 dias-multa está literalmente na fonte e foi reprovado — a régua " +
                "ficou restritiva demais e o custo disso é o copiloto emudecer.",
            guarda.aprovar("O pagamento é de 500 dias-multa.", fonte),
        )
    }

    @Test
    fun furo3_decimalNaoSeDesmontaEmCifrasComuns() {
        // "1,5" virava `1` e `5`. Uma norma que cite o art. 1º e o § 5º daria lastro
        // aos dois pedaços, e a gramatura inventada passaria inteira.
        val fonte = "Art. 1º Esta Lei institui o Sistema Nacional. § 5º O disposto " +
            "no caput aplica-se aos casos previstos em regulamento."

        assertNull(
            "\"1,5 grama\" foi aprovado porque `1` e `5` existem soltos na fonte. " +
                "Decimal tem de ser UM número, não dois dígitos.",
            guarda.aprovar("O limite é de 1,5 grama por pessoa.", fonte),
        )

        assertEquals(
            "1,5 deixou de ser lido como um número só.",
            setOf(1.5),
            Grandezas.valores("1,5 grama"),
        )
    }

    /**
     * **A régua não pode ficar tão apertada que emudeça o produto.**
     *
     * Reprovar demais devolve o copiloto à leitura verbatim — que é o padrão correto,
     * mas é uma capacidade a menos. Este teste é o outro lado do contra-teste: prova
     * que redação honesta, que só reafirma o que a fonte diz, continua passando.
     */
    @Test
    fun redacaoHonesta_continuaPassando() {
        val fonte = "Art. 33. Pena — reclusão de 5 (cinco) a 15 (quinze) anos."
        assertNotNull(
            "Redação fiel foi reprovada. A régua ficou restritiva demais e o custo " +
                "é o copiloto perder a capacidade de redigir.",
            guarda.aprovar("A pena vai de 5 a 15 anos de reclusão.", fonte),
        )
    }

    /**
     * Número sem unidade — `Art. 33`, `§ 1º` — segue comparado só pelo valor. É o
     * comportamento antigo, e para este caso ele estava certo: não há grandeza a
     * confundir, e exigir unidade reprovaria toda citação de artigo.
     */
    @Test
    fun numeroSemUnidade_continuaComparadoPeloValor() {
        val fonte = "Art. 33 da Lei 11.343 de 2006, tráfico de drogas."

        // As frases abaixo são propositalmente longas. `aprovar` exige ao menos uma
        // palavra de conteúdo (5+ letras) antes de medir lastro — `"Veja o Art. 33."`
        // devolve `null` por não ter nenhuma, e não por causa da régua de grandezas.
        assertNotNull(
            "Citar o artigo 33, que está na fonte, foi reprovado.",
            guarda.aprovar("Responde pelo artigo 33 da referida legislação.", fonte),
        )
        assertNull(
            "Art. 44 não está na fonte e foi aprovado.",
            guarda.aprovar("Responde pelo artigo 44 da referida legislação.", fonte),
        )
    }

    /**
     * **Resposta curta demais não tem o que lastrear, e por isso é recusada.**
     *
     * Descoberto ao escrever o teste acima: `"Veja o Art. 33."` era reprovado mesmo com
     * o artigo presente na fonte, porque nenhuma das três palavras chega a 5 letras e
     * `conteudo` fica vazio. O comportamento está certo — não há como aferir lastro de
     * texto sem palavra de conteúdo —, mas não estava escrito em lugar nenhum, e
     * confundiu quem escreveu o teste. Fica registrado aqui.
     */
    @Test
    fun textoSemPalavraDeConteudo_eRecusado_eIssoEIntencional() {
        val fonte = "Art. 33 da Lei 11.343 de 2006, tráfico de drogas."
        assertNull(
            "Texto sem nenhuma palavra de 5+ letras passou. Não há lastro a medir " +
                "num texto assim, e aprová-lo seria aprovar por ausência de prova.",
            guarda.aprovar("Veja o Art. 33.", fonte),
        )
    }
}
