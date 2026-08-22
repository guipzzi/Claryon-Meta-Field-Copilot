package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O que dá para provar sobre a gramática sem o aparelho.**
 *
 * Se ela compila contra o vocabulário do Llama, quanto custa compilar e se o
 * modelo escolhe trechos úteis — nada disso se sabe aqui, e este arquivo não
 * finge saber: está em `DuasPistasDaEtapaBTest`, no emulador.
 *
 * O que se prova aqui é a **forma**, e ela é o alicerce da afirmação forte da
 * Pista 2 (*"alucinação impossível por construção"*). Uma gramática que admitisse
 * recombinação, ou que tivesse regra inalcançável apontando para o vazio, faria
 * aquela frase virar mentira sem que o log no aparelho mudasse de cara.
 */
class GramaticaDaFonteTest {

    private val artigo =
        "Art. 311. Adulterar ou remarcar número de chassi: Pena - reclusão, " +
            "de três a seis anos, e multa."

    @Test
    fun aGramaticaTemRaizEDeclaraAsPosicoesDeInicio() {
        val g = GramaticaDaFonte.de(artigo, maximoDePalavras = 4)
        assertNotNull("Gramática nula sobre trecho com texto", g)
        assertTrue("Sem regra `root`, `llama_sampler_init_grammar` não acha a raiz", "root ::=" in g!!)
        assertTrue("Sem a regra de alternância de inícios", "\nt ::= " in g)
    }

    /**
     * **Toda regra referenciada existe, e toda regra escrita é alcançável.**
     *
     * A poda por alcançabilidade é o que faz a gramática caber no orçamento — ela
     * derruba `palavras × teto` regras para `inícios × teto`. Podar demais deixa
     * referência pendurada, e o parser do llama.cpp aceita a referência criando um
     * símbolo **vazio**: a gramática compila, o autômato morre no primeiro token e
     * o log mostra "0 gerações com texto" sem uma palavra sobre o motivo.
     */
    @Test
    fun naoHaReferenciaPendurada_nemRegraOrfa() {
        val g = GramaticaDaFonte.de(artigo, maximoDePalavras = 5)!!
        val definidas = HashSet<String>()
        val referenciadas = HashSet<String>()
        for (linha in g.lines().filter { it.isNotBlank() }) {
            val nome = linha.substringBefore(" ::=").trim()
            definidas += nome
            val corpo = linha.substringAfter("::=")
            // Fora dos literais entre aspas só existem nomes de regra e `|`.
            var dentro = false
            val fora = StringBuilder()
            var i = 0
            while (i < corpo.length) {
                val c = corpo[i]
                when {
                    c == '\\' && dentro -> i++
                    c == '"' -> dentro = !dentro
                    !dentro -> fora.append(c)
                }
                i++
            }
            for (t in fora.split(Regex("""[^A-Za-z0-9-]+"""))) {
                if (t.isNotEmpty() && t != "|" && t != "?") referenciadas += t
            }
        }
        val penduradas = referenciadas - definidas
        assertTrue("Regras referenciadas e nunca definidas: $penduradas", penduradas.isEmpty())

        val orfas = definidas - referenciadas - setOf("root")
        assertTrue("Regras definidas e nunca alcançáveis: $orfas", orfas.isEmpty())
    }

    /**
     * **O teto de palavras é exato, e é exato porque a profundidade está no nome
     * da regra.**
     *
     * Com uma cadeia só por posição, o teto teria de vir de `maxTokens` e o corte
     * cairia no meio de uma palavra — a truncagem que a tabela de 22/08 mede em 4 e
     * 6 casos em F3 e F4. Aqui a regra de profundidade 1 não tem continuação, ponto.
     */
    @Test
    fun aProfundidade1_naoContinua() {
        val g = GramaticaDaFonte.de(artigo, maximoDePalavras = 3, minimoDePalavras = 1)!!
        val deProfundidade1 = g.lines().filter { it.startsWith("p") && it.contains("-1 ::=") }
        assertTrue("Nenhuma regra de profundidade 1 foi escrita", deProfundidade1.isNotEmpty())
        for (linha in deProfundidade1) {
            assertFalse(
                "A regra de teto continua para a próxima palavra — o teto não é teto: $linha",
                "|" in linha,
            )
        }
    }

    /**
     * **O piso fecha a saída de emergência do modelo, e a gramática o carrega —
     * não o `maxTokens`.**
     *
     * Medido em 22/08 (noite): sem piso, 7 das 20 gerações do braço de gramática
     * foram fragmentos de uma ou duas palavras — *"Penalidade"*, *"não"*,
     * *"Apresentado"*, *"1º"*. Contíguos, aprovados pelo guarda com lastro 1,00 e
     * sem informação nenhuma. A gramática permitia PARAR depois de qualquer
     * palavra: a pilha esvaziava, o EOG virava sorteável, e um modelo de 1B toma
     * essa saída.
     *
     * A propriedade estrutural que consertou isso: nas primeiras
     * `minimo - 1` palavras a regra **não** tem a alternativa de parar, só a de
     * continuar.
     */
    @Test
    fun antesDoPiso_naoHaComoParar() {
        val piso = 3
        val g = GramaticaDaFonte.de(artigo, maximoDePalavras = 6, minimoDePalavras = piso)!!
        // Profundidade 6 = primeira palavra; 5 = segunda. Nenhuma das duas pode
        // ser o fim de um trecho de 3 palavras no mínimo.
        for (d in listOf(6, 5)) {
            val regras = g.lines().filter { it.matches(Regex("""p\d+-$d ::=.*""")) }
            assertTrue("Nenhuma regra de profundidade $d", regras.isNotEmpty())
            for (linha in regras) {
                assertFalse(
                    "A regra \"$linha\" admite parar com menos de $piso palavras — o " +
                        "modelo volta a responder \"Penalidade\" e o guarda volta a aprovar.",
                    "|" in linha,
                )
            }
        }
        // Controle positivo: na profundidade em que o piso já foi cumprido, parar
        // volta a ser permitido. Sem esta metade, o teste passaria com uma
        // gramática que nunca termina.
        val naProfundidade4 = g.lines().filter { it.matches(Regex("""p\d+-4 ::=.*""")) }
        assertTrue("Nenhuma regra de profundidade 4", naProfundidade4.isNotEmpty())
        assertTrue(
            "Na terceira palavra ainda não é possível parar: a gramática não termina nunca",
            naProfundidade4.any { "|" in it },
        )
    }

    /**
     * **Fronteira de cláusula produz menos inícios que qualquer palavra.**
     *
     * Não é detalhe de estilo: cada início é uma **pilha ativa** no autômato, e
     * `llama_grammar_apply_impl` varre os candidatos uma vez por pilha. É a
     * diferença entre 30 e 380 varreduras de 128 k candidatos no primeiro token.
     */
    @Test
    fun aFronteiraDeClausulaCortaOsInicios() {
        val livre = GramaticaDaFonte.de(artigo, 4, GramaticaDaFonte.Inicio.QUALQUER_PALAVRA)!!
        val presa = GramaticaDaFonte.de(artigo, 4, GramaticaDaFonte.Inicio.FRONTEIRA_DE_CLAUSULA)!!
        val inicios = { g: String -> g.lines().first { it.startsWith("t ::=") }.split('|').size }
        assertTrue(
            "Fronteira de cláusula não cortou início nenhum: ${inicios(presa)} contra " +
                "${inicios(livre)}. Ou a pontuação do trecho não foi vista, ou o modo não chega.",
            inicios(presa) < inicios(livre),
        )
    }

    /** Trecho vazio não vira gramática vazia: vira `null`, e quem chama cai fora. */
    @Test
    fun trechoSemPalavra_naoViraGramatica() {
        assertNull(GramaticaDaFonte.de("   \n  "))
        assertNull(GramaticaDaFonte.de(""))
    }

    /** Aspas e barras da fonte têm de sair com fuga, senão a GBNF não fecha o literal. */
    @Test
    fun aspaEBarraSaemComFuga() {
        // Teto de 5 para que as cinco palavras sejam ALCANÇÁVEIS a partir do
        // único início: com teto menor, a poda deixa `c:\prisao` de fora e o
        // teste passaria a medir a poda, não a fuga.
        val g = GramaticaDaFonte.de("""o "auto" de c:\prisao pronto.""", 5)!!
        assertTrue("A aspa da fonte saiu sem fuga e quebra o literal", """\"auto\"""" in g)
        assertTrue("A barra invertida saiu sem fuga", """c:\\prisao""" in g)
    }

    // ------------------------------------------------------- o contra-teste da bancada

    @Test
    fun oVerificadorDeContiguidade_aceitaOQueEContiguo() {
        assertTrue(GramaticaDaFonte.eTrechoContiguo("reclusão, de três a seis anos,", artigo))
        assertTrue(GramaticaDaFonte.eTrechoContiguo("Adulterar ou remarcar", artigo))
        assertTrue(GramaticaDaFonte.eTrechoContiguo("  e multa.  ", artigo))
    }

    /**
     * **E recusa o que a régua lexical do `GuardaDaRedacao` aprovaria.**
     *
     * Este é o teste que sustenta a frase que a Pista 2 vende. As três entradas
     * abaixo têm **lastro lexical perfeito** — cada palavra existe na fonte — e são
     * exatamente os três modos de falha que o guarda não vê:
     *
     *  - recombinação fora de ordem;
     *  - negação enxertada (o caso do Art. 13, medido em produção em 22/08);
     *  - palavra inventada no meio de material com lastro.
     */
    @Test
    fun oVerificadorDeContiguidade_recusaOQueOGuardaAprovaria() {
        val guarda = GuardaDaRedacao()
        val mentiras = listOf(
            "multa e reclusão" to "recombinação fora de ordem",
            "Pena - reclusão, de três anos" to "supressão do meio do trecho",
            "Adulterar ou remarcar número inventado de chassi" to "palavra enxertada",
        )
        for ((texto, defeito) in mentiras) {
            assertFalse(
                "A contiguidade aceitou $defeito: \"$texto\"",
                GramaticaDaFonte.eTrechoContiguo(texto, artigo),
            )
        }
        // Controle positivo do contraste: a régua lexical de hoje aprova pelo
        // menos uma delas. Sem isto, o teste acima só diria "contíguo é mais
        // estrito", sem provar que o que ele barra é o que hoje passa.
        assertTrue(
            "Nenhuma das mentiras passa mais no guarda — o contraste que justifica a " +
                "Pista 2 precisa ser remedido, não apagado.",
            mentiras.any { guarda.aprovar(it.first, artigo) != null },
        )
    }

    /** Vazio não é trecho contíguo de coisa nenhuma. */
    @Test
    fun oVerificadorDeContiguidade_recusaVazio() {
        assertFalse(GramaticaDaFonte.eTrechoContiguo("", artigo))
        assertFalse(GramaticaDaFonte.eTrechoContiguo("   ", artigo))
    }

    /**
     * O tamanho da GBNF entra no relatório, então tem de ser calculável sem o
     * aparelho — e tem de ser linear no número de inícios, não no de palavras.
     */
    @Test
    fun oTamanhoCresceComOsInicios_naoComAsPalavras() {
        val curto = GramaticaDaFonte.de(artigo, 7)!!
        val dobrado = GramaticaDaFonte.de("$artigo $artigo", 7)!!
        val regras = { g: String -> g.lines().count { it.isNotBlank() } }
        // Dobrar o texto dobra os inícios; o que não pode acontecer é crescer
        // com `palavras × teto`, que é a grade inteira.
        assertTrue(
            "Regras demais: ${regras(dobrado)} para ${dobrado.split(Regex("\\s+")).size} palavras",
            regras(dobrado) < dobrado.split(Regex("""\s+""")).size * 7,
        )
        assertEquals(
            "Dobrar o texto não dobrou as regras — a poda por alcançabilidade mudou de forma",
            2,
            (regras(dobrado) - 2) / (regras(curto) - 2),
        )
    }
}
