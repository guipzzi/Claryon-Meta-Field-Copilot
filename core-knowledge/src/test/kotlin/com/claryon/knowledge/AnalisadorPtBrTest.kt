package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O analisador é o ponto onde corpus e pergunta têm de se encontrar.** Se ele
 * divergir entre os dois lados, nada casa e a falha é silenciosa: o sistema
 * apenas recusa tudo, e "recusa tudo" parece prudência.
 */
class AnalisadorPtBrTest {

    @Test
    fun dobraAcentoECaixa_porqueOSttNaoAcentua() {
        assertEquals("trafico de drogas", AnalisadorPtBr.normalizar("Tráfico de Drogas"))
        assertEquals("infracao gravissima", AnalisadorPtBr.normalizar("Infração — GRAVÍSSIMA"))
        assertEquals(
            "o STT devolve 'veiculo'; a lei escreve 'veículo'. Se os dois não " +
                "colapsarem no mesmo termo, o artigo se perde por um acento.",
            AnalisadorPtBr.normalizar("veículo"),
            AnalisadorPtBr.normalizar("veiculo"),
        )
    }

    @Test
    fun oNgramaUneAFlexao_queEOndeAPerguntaEALeiDiferem() {
        // A lei usa infinitivo ("Recusar-se a ser submetido"); o agente usa
        // pretérito ("o cara recusou"). É o n-grama que junta os dois — e essa é
        // a razão de o prefixo ter saído: ele fazia a mesma coisa, pior.
        val pares = listOf(
            "recusou" to "recusar",
            "dirigindo" to "dirigir",
            "portando" to "portar",
            "apreendeu" to "apreender",
        )
        for ((doAgente, daLei) in pares) {
            val comuns = AnalisadorPtBr.termos(doAgente).toSet() intersect AnalisadorPtBr.termos(daLei).toSet()
            assertTrue(
                "'$doAgente' e '$daLei' são a mesma ideia e precisam dividir n-gramas; achei $comuns",
                comuns.size >= 3,
            )
        }
    }

    /**
     * **O prefixo saiu porque foi medido pior — este teste guarda o motivo.**
     *
     * `"portar"` e `"portaria"` dividem o prefixo de 5 inteiro, então para o
     * prefixo elas são o mesmo termo. Para o n-grama, não: `"portaria"` traz
     * `tari`, `aria`, `ria_`, que `"portar"` não tem.
     */
    @Test
    fun oPrefixoConfundeOQueONgramaSepara() {
        assertEquals(
            "controle: o prefixo NÃO distingue 'portar' de 'portaria'",
            "portar".take(AnalisadorPtBr.PREFIXO),
            "portaria".take(AnalisadorPtBr.PREFIXO),
        )
        val portar = AnalisadorPtBr.termos("portar").toSet()
        val portaria = AnalisadorPtBr.termos("portaria").toSet()
        assertTrue(
            "o n-grama tem de separar as duas — é o que o prefixo não fazia",
            (portaria - portar).size >= 3,
        )
        assertFalse(
            "e o prefixo não pode estar sendo emitido em produção",
            AnalisadorPtBr.termos("portaria").contains("porta".take(AnalisadorPtBr.PREFIXO)) &&
                AnalisadorPtBr.termos("portaria").none { it.startsWith("_") },
        )
    }

    @Test
    fun oNgramaSobreviveAoErroDeTranscricao() {
        // "altorizacao" não é "autorizacao" nem divide o prefixo com ela: sem
        // n-grama, uma letra trocada pelo STT zeraria o termo.
        val certo = AnalisadorPtBr.termos("autorizacao").toSet()
        val errado = AnalisadorPtBr.termos("altorizacao").toSet()

        assertFalse(
            "controle: as duas grafias NÃO dividem o prefixo — se dividissem, este " +
                "teste estaria medindo o prefixo, não o n-grama",
            "autor".take(AnalisadorPtBr.PREFIXO) == "altor".take(AnalisadorPtBr.PREFIXO),
        )
        val comuns = certo intersect errado
        assertTrue(
            "esperava n-gramas em comum entre 'autorizacao' e 'altorizacao', achei $comuns",
            comuns.size >= 4,
        )
    }

    @Test
    fun asPalavrasVaziasSaemDosDoisLados() {
        val t = AnalisadorPtBr.palavras("quando posso apreender o veiculo do condutor")
        assertFalse("'quando' é o começo de quase toda pergunta", t.contains("quando"))
        assertFalse("'posso' idem", t.contains("posso"))
        assertTrue("o que interessa fica", t.containsAll(listOf("apreender", "veiculo", "condutor")))
    }

    @Test
    fun aFormulaJuridicaNaoEntraNoIndice() {
        // "art", "parágrafo", "inciso" estão em milhares de trechos e não
        // distinguem nenhum deles.
        val t = AnalisadorPtBr.palavras("Art. 306, § 1º, inciso II, caput")
        assertTrue("sobrou fórmula jurídica no índice: $t", t.none { it in setOf("art", "paragrafo", "inciso", "caput") })
    }

    @Test
    fun aRepeticaoEPreservada_porqueOBm25PrecisaDela() {
        val termos = AnalisadorPtBr.termos("veiculo veiculo veiculo")
        assertEquals(
            "se `termos` devolvesse Set, um artigo que diz 'veículo' seis vezes " +
                "ficaria indistinguível de outro que diz uma — e a saturação do BM25 " +
                "não teria o que saturar",
            3,
            termos.count { it == "_vei" },
        )
    }

    /**
     * **O numeral romano de inciso era um falso aceite acima do limiar.**
     *
     * `"XXX"` aparece duas vezes no corpus inteiro, então tem IDF altíssimo — e
     * `"zzz qqq xxx"` casava com o art. 10 do CTB com confiança 0,374 contra um
     * limiar de 0,30.
     */
    @Test
    fun oNumeralRomanoDeIncisoNaoEntraNoIndice() {
        for (n in listOf("ii", "iii", "iv", "vi", "xxi", "xxx", "cd")) {
            assertTrue("'$n' é numeral de inciso e não deveria virar termo", AnalisadorPtBr.palavras(n).isEmpty())
        }
        // **Contra-teste, e é ele que justifica a gramática estrita.** Estas três
        // são compostas SÓ de letras romanas: um filtro `[ivxlcdm]+` apagaria as
        // três do índice, e "civil" aparece 48 vezes neste corpus.
        for (palavra in listOf("civil", "mil", "mim", "vll")) {
            assertEquals(
                "'$palavra' é palavra, não numeral. Se sumiu, o filtro está frouxo " +
                    "demais e está comendo vocabulário de trabalho.",
                listOf(palavra),
                AnalisadorPtBr.palavras(palavra),
            )
        }
    }
}
