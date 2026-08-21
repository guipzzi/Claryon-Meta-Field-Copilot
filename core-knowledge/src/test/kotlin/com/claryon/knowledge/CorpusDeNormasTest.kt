package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O mapa de campos, a recusa de linha ruim, e a promessa de nunca lançar.**
 *
 * O teste central aqui é [oMapaDeCamposNaoPodeSerFeitoPorNomeIgual]: ele não
 * confere que os campos estão preenchidos — confere que estão preenchidos com o
 * campo **certo**, e o contra-teste ao lado prova que ele pegaria a troca.
 */
class CorpusDeNormasTest {

    private val linhaReal = """
        {"norma": "CTB", "documento": "Lei 9.503/1997", "artigo": "Art. 306", "titulo": "", "texto": "Art. 306. Conduzir veículo automotor com capacidade psicomotora alterada.", "citacao": "Art. 306 do CTB", "revogado": false}
    """.trimIndent()

    // ------------------------------------------------------------ o mapa de campos

    @Test
    fun oMapaDeCamposNaoPodeSerFeitoPorNomeIgual() {
        val lido = CorpusDeNormas.trechoDe(linhaReal)
        assertNotNull("linha válida do corpus não deveria ser descartada", lido)

        assertEquals(
            "citacao vem de `artigo`. Vindo de `citacao` do JSONL, o Piper diria " +
                "\"Art. 306 do CTB\" — redundante com a norma que ele anuncia logo " +
                "em seguida.",
            "Art. 306",
            lido!!.trecho.citacao,
        )
        assertEquals(
            "norma vem de `documento`. Este é O defeito que o KDoc de Trecho avisa: " +
                "casando por nome igual, o campo `norma` do JSONL (a SIGLA) entraria " +
                "aqui e o agente ouviria \"CTB\" no lugar de \"Lei 9.503/1997\" — " +
                "perdendo a informação com que ele conferiria a fonte depois.",
            "Lei 9.503/1997",
            lido.trecho.norma,
        )
        assertEquals("a sigla é termo de busca, não citação", "CTB", lido.sigla)
    }

    /**
     * **Contra-teste do mapa: com os campos trocados, a asserção acima grita.**
     *
     * Sem isto, `assertEquals("Lei 9.503/1997", ...)` poderia estar passando por
     * coincidência — por exemplo se algum dia os dois campos do JSONL passassem a
     * trazer o mesmo valor. Aqui a troca é encenada e o resultado é comparado.
     */
    @Test
    fun oTesteDoMapaPegariaOsCamposTrocados() {
        // A mesma linha, com `norma` e `documento` invertidos: é exatamente o que
        // o arquivo pareceria se o extrator tivesse errado a origem.
        val trocada = linhaReal
            .replace("\"norma\": \"CTB\"", "\"norma\": \"XX\"")
            .replace("\"documento\": \"Lei 9.503/1997\"", "\"documento\": \"CTB\"")

        val lido = CorpusDeNormas.trechoDe(trocada)!!
        assertEquals(
            "Se este leitor pega `documento` e o valor de `documento` é a sigla, a " +
                "norma sai errada — e é isso que o teste de cima detecta.",
            "CTB",
            lido.trecho.norma,
        )
        assertTrue(
            "O gabarito das 88 perguntas compara `norma` com o número do documento. " +
                "Com a troca acima, todas falhariam — que é o comportamento certo.",
            lido.trecho.norma != "Lei 9.503/1997",
        )
    }

    // ------------------------------------------------------------ linha ruim = null

    @Test
    fun linhaRuimEDescartadaEmVezDeLancar() {
        val ruins = mapOf(
            "vazia" to "",
            "só espaço" to "   ",
            "não é objeto" to "isto não é json",
            "objeto truncado" to """{"norma": "CTB", "documento": "Lei 9.5""",
            "string sem fechar" to """{"texto": "abre e não fecha}""",
            "sem texto" to """{"documento": "Lei 9.503/1997", "artigo": "Art. 1º", "revogado": false}""",
            "texto vazio" to """{"texto": "", "documento": "Lei 9.503/1997", "artigo": "Art. 1º"}""",
            "sem artigo" to """{"texto": "algo", "documento": "Lei 9.503/1997"}""",
            "sem documento" to """{"texto": "algo", "artigo": "Art. 1º"}""",
            "valor aninhado" to """{"texto": {"a": 1}, "documento": "L", "artigo": "A"}""",
            "revogado" to """{"texto": "Art. 262. (Revogado)", "documento": "Lei 9.503/1997", "artigo": "Art. 262", "revogado": true}""",
        )
        for ((nome, linha) in ruins) {
            // A ausência de try/catch aqui É a asserção de "não lança": exceção
            // vira erro de teste, não falha de asserção — e as duas aparecem.
            assertNull("linha '$nome' deveria ser descartada, não aceita", CorpusDeNormas.trechoDe(linha))
        }
    }

    @Test
    fun oEscapeDeAspasEDesfeito() {
        // O corpus real usa `\"` — é o único escape presente nas 1817 linhas.
        val linha = """{"texto": "disse \"pare\" ao condutor", "documento": "L", "artigo": "A"}"""
        assertEquals("""disse "pare" ao condutor""", CorpusDeNormas.trechoDe(linha)!!.trecho.texto)
    }

    // --------------------------------------------------------- o corpus embarcado

    @Test
    fun oCorpusEmbarcadoTemAsCincoNormasESemRevogados() {
        val todos = CorpusDeNormas.embarcado()
        assertEquals("1817 linhas menos 73 revogadas", 1744, todos.size)

        val normas = todos.map { it.trecho.norma }.toSet()
        assertEquals(
            "as cinco normas do corpus, pelo NÚMERO do documento — se aparecer " +
                "\"CTB\" aqui, o mapa de campos regrediu",
            setOf(
                "Lei 9.503/1997",
                "Decreto-Lei 3.689/1941",
                "Decreto-Lei 2.848/1940",
                "Lei 11.343/2006",
                "Lei 10.826/2003",
            ),
            normas,
        )

        // O trecho revogado tem a revogação como corpo INTEIRO — "Art. 262.
        // (Revogado pela Lei nº 13.281, de 2016)". Não vale procurar "(Revogado"
        // em qualquer posição: artigo vigente com um parágrafo revogado no meio
        // (o art. 302 do CTB é um) continua sendo norma boa e tem de ficar.
        val soRevogacao = Regex("""^Art\..{0,12}\(Revogad""", RegexOption.IGNORE_CASE)
        val intrusos = todos.filter { soRevogacao.containsMatchIn(it.trecho.texto) }
        assertEquals(
            "trecho revogado entrou no índice. Ler \"(Revogado pela Lei nº …)\" em " +
                "voz alta não é uma resposta pior — é uma que ocupa o lugar da certa.",
            emptyList<String>(),
            intrusos.map { it.trecho.citacao },
        )
        // Controle positivo do filtro: sem ele, estes existiriam. Se o corpus
        // deixasse de ter revogados, o teste acima passaria sem provar nada.
        assertTrue(
            "nenhum trecho revogado no arquivo — o filtro deixou de ser exercitado",
            CorpusDeNormas.trechoDe(
                """{"texto": "Art. 262. (Revogado pela Lei nº 13.281, de 2016)", "documento": "Lei 9.503/1997", "artigo": "Art. 262", "revogado": true}""",
            ) == null,
        )
        assertTrue("nenhum trecho pode ter citação vazia", todos.all { it.trecho.citacao.isNotBlank() })
    }
}
