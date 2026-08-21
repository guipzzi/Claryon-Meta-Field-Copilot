package com.claryon.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * **Os caminhos que não são o feliz.**
 *
 * Teste verde prova que o caminho feliz existe; não prova que os outros
 * existem. O contrato de [BaseDeConhecimento] diz "não lança" e lista os modos
 * de falha por nome — corpus ausente, corpus corrompido, índice que não
 * carregou. Cada um deles é um teste aqui, porque num aparelho sem display uma
 * exceção que sobe é indistinguível de o aplicativo ter morrido.
 */
class BaseDeConhecimentoLexicalTest {

    private fun fluxo(conteudo: String): () -> InputStream =
        { ByteArrayInputStream(conteudo.toByteArray()) }

    // ------------------------------------------------------------- não lança

    @Test
    fun corpusAusenteViraRecusa_naoExcecao() = runBlocking {
        val base = BaseDeConhecimentoLexical(corpus = { ByteArrayInputStream(ByteArray(0)) })
        assertEquals(0, base.trechosIndexados)
        assertNull("sem corpus, toda pergunta é recusa", base.buscar("porte de arma sem autorizacao"))
    }

    @Test
    fun fonteQueExplodeViraRecusa_naoExcecao() = runBlocking {
        val base = BaseDeConhecimentoLexical(corpus = { throw IOException("asset sumiu do APK") })
        assertEquals(0, base.trechosIndexados)
        assertNull(base.buscar("dirigir bebado da cadeia"))
    }

    @Test
    fun jsonlCorrompidoNaoDerrubaOQueSobra() = runBlocking {
        val meioQuebrado = buildString {
            appendLine("""{"norma": "CTB", "documento": "Lei 9.503/1997", "artigo": "Art. 306", "titulo": "", "texto": "Conduzir veículo automotor com capacidade psicomotora alterada em razão da influência de álcool.", "citacao": "x", "revogado": false}""")
            appendLine("}{ isto não é json {{{")
            appendLine("")
            appendLine("""{"norma": "CP", "documento": "Decreto-Lei 2.848/1940", "artigo": "Art. 331", "titulo": "Desacato", "texto": "Desacatar funcionário público no exercício da função ou em razão dela.", "citacao": "y", "revogado": false}""")
        }
        val base = BaseDeConhecimentoLexical(corpus = fluxo(meioQuebrado))
        assertEquals("a linha ruim é descartada, as boas ficam", 2, base.trechosIndexados)
        // E o índice de duas linhas ainda funciona: linha ruim não contamina.
        assertNotNull(base.buscar("o cara me xingou de tudo quanto e nome"))
    }

    @Test
    fun perguntaVaziaOuSoRuidoViraRecusa() = runBlocking {
        val base = BaseDeConhecimentoLexical()
        for (q in listOf("", "   ", "...", "é", "a o de", "zzz qqq xxx")) {
            assertNull("'$q' deveria recusar", base.buscar(q))
        }
    }

    // ---------------------------------------------------------------- a porta

    /**
     * **Contra-teste do limiar: com ele em 0, futebol passa a ser respondido.**
     *
     * Sem este par, `assertNull` sobre uma pergunta de futebol também passaria
     * num sistema que recusa tudo — e não distinguiria "a porta funciona" de "o
     * índice está vazio".
     */
    @Test
    fun eOLimiarQueRecusa_naoAFaltaDeCandidato() = runBlocking {
        val pergunta = "quem ganhou o jogo do flamengo ontem"

        assertNull(
            "no limiar medido, futebol tem de virar recusa",
            BaseDeConhecimentoLexical().buscar(pergunta),
        )

        val semPorta = BaseDeConhecimentoLexical(limiar = 0.0).buscar(pergunta)
        assertNotNull(
            "Com o limiar em zero a mesma pergunta TEM de devolver alguma coisa. Se " +
                "devolvesse null aqui também, o null de cima não seria a porta " +
                "funcionando — seria o índice não achando candidato nenhum, e o " +
                "teste acima não provaria nada sobre o limiar.",
            semPorta,
        )
    }

    @Test
    fun oSegundoColocadoNuncaEOferecido() {
        // A porta escolhe pelo maior e só o primeiro colocado carrega confiança.
        val indice = IndiceLexical(CorpusDeNormas.embarcado())
        val candidatos = indice.buscar("achei um fuzil de uso restrito com o suspeito", quantos = 5)
        assertTrue("esperava vários candidatos", candidatos.size >= 2)
        assertTrue(
            "só o primeiro colocado pode ter confiança acima de zero: um segundo " +
                "colocado com confiança poderia ser dito em voz alta um dia, e a " +
                "distância dele não aparece na fala",
            candidatos.drop(1).all { it.similaridade == 0.0 },
        )
    }

    // -------------------------------------------------------- o caminho feliz

    @Test
    fun devolveOArtigoComACitacaoEONumeroDoDocumento() = runBlocking {
        val t = BaseDeConhecimentoLexical().buscar("achei um fuzil de uso restrito com o suspeito")
        assertNotNull("esta é uma das 18 que o sistema responde acima do limiar", t)
        assertEquals("Art. 16", t!!.citacao)
        assertEquals(
            "o Piper anuncia o NÚMERO do documento, não a sigla",
            "Lei 10.826/2003",
            t.norma,
        )
        assertTrue(
            "o texto tem de ser o da lei, verbatim — nada aqui redige",
            t.texto.startsWith("Art. 16. Possuir, deter, portar, adquirir"),
        )
    }

    @Test
    fun oTextoDevolvidoExisteNoCorpusPalavraPorPalavra() = runBlocking {
        // Zero alucinação não é meta de qualidade: é consequência de não existir
        // passo que produza texto novo. Este teste é a prova disso.
        val doCorpus = CorpusDeNormas.embarcado().map { it.trecho.texto }.toSet()
        val base = BaseDeConhecimentoLexical()
        var respondidas = 0
        for (p in PERGUNTAS_DE_CAMPO) {
            val t = base.buscar(p.pergunta) ?: continue
            respondidas++
            assertTrue(
                "o texto devolvido para '${p.pergunta}' não é byte a byte um trecho " +
                    "do corpus — apareceu um passo de redação no caminho",
                t.texto in doCorpus,
            )
        }
        assertTrue("controle positivo: nada foi respondido", respondidas >= 20)
    }
}
