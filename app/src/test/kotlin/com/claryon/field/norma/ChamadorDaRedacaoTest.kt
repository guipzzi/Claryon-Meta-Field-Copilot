package com.claryon.field.norma

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O fio de armar da Etapa B: ela está desligada, e ligá-la é decisão humana.**
 *
 * ## Por que um teste guarda uma AUSÊNCIA
 *
 * O `CLAUDE.md` §6 conta seis vezes em que este projeto construiu, testou e não
 * ligou — e a resposta natural é um teste que exija chamador. Aqui a exigência é
 * a **contrária**, e o motivo está medido:
 *
 * `RedacaoDoCopiloto.redigir` só pode ser ligada sobrepondo o teto de **7
 * palavras** do `CLAUDE.md` §4, que é regra dura. Pelo §7, sobrepor regra dura
 * entra como spec e **espera** — a spec é `specs/redacao-por-llm-na-fala.spec.md`,
 * e ela recomenda **não ligar** com o modelo atual, com estes números
 * (`OrcamentoDaEtapaBNoAparelhoTest`, 22/08, configuração de produção, 20
 * perguntas do corpus acima do limiar):
 *
 * ```
 * sem texto nenhum (prazo de 2 500 ms) ....  9 de 20
 * aprovadas pelo guarda de lastro .........  7
 * utilizáveis, por leitura humana do log ..  1
 * ```
 *
 * **Os três números pioraram na remedição de 22/08, e o motivo é bom.** O guarda
 * foi consertado nos três eixos naquele dia — régua de grandezas, composição da
 * fonte e a `Grandezas` que estava desligada —, e um guarda que enxerga reprova
 * mais: das 9 aprovações que a composição antiga daria, 2 eram lastro na própria
 * pergunta. A coluna `utilizáveis` caiu de ≈2 para 1 por leitura, não por régua:
 * a segunda "utilizável" de antes era a pena do Art. 311 dita dentro de
 * *"O TRECHO DA NORMA trata sobre…"*, que não é dizível a ninguém.
 *
 * **Consertar o prompt não move esta conta.** Cinco formulações medidas sobre o
 * mesmo banco em 22/08 (`FormulacaoDoPrompt.TODAS`): o vazamento do andaime cai
 * de 4 para 0, o eco de 5 para 0, a truncagem de 2 para 0 — e `utilizáveis` fica
 * entre **1 e 2 de 20** em todas as cinco. O que trava não é como se pede.
 *
 * Então o risco que este arquivo cobre não é "esqueceram de ligar". É **"alguém
 * ligou sem a decisão"** — que num produto de segurança pública significa um
 * policial ouvindo *"O alcoólatempo é crime."* com a autoridade de uma consulta à
 * lei. Foi uma das saídas que o guarda aprovou, com lastro **1,00**.
 *
 * ## Como este teste morre, e por que morrer é o comportamento certo
 *
 * Quando a decisão humana sair e a ligação for feita, [aRedacaoContinuaSemChamador]
 * **falha**. Isso não é defeito: é o teste cobrando que quem ligou passe por aqui,
 * leia a spec e apague este arquivo de propósito, em vez de silenciosamente. Uma
 * ausência que ninguém guarda é uma ausência que volta a existir sem ninguém
 * notar — e o inverso também.
 */
class ChamadorDaRedacaoTest {

    /**
     * Onde a redação pode ser chamada: produção de `app` e dos módulos. Testes
     * ficam de fora de propósito — `RedacaoDeAbordagemNoAparelhoTest` e
     * `OrcamentoDaEtapaBNoAparelhoTest` chamam o motor **para medir**, e medir não
     * é ligar. A distinção é exatamente a do §6: chamador em `src/main` alcançável
     * em runtime.
     */
    private fun fontesDeProducao(): List<File> {
        val raiz = raizDoRepositorio()
        val diretorios = raiz.listFiles()
            .orEmpty()
            .filter { it.isDirectory && (it.name == "app" || it.name.startsWith("core-")) }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
        return diretorios
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            // O llama.cpp vendorizado traz exemplos Kotlin da ARM que não são
            // deste produto e não podem votar nesta contagem.
            .filterNot { it.path.contains("/cpp/llama/") }
    }

    /** Arquivo → linhas que casam, já sem as que apenas *declaram* a função. */
    private fun chamadasDe(simbolo: String, definidoEm: Set<String>): Map<String, List<String>> =
        fontesDeProducao()
            .filterNot { it.name in definidoEm }
            .associate { arquivo ->
                arquivo.name to arquivo.readLines()
                    .map { it.trim() }
                    .filter { it.contains("$simbolo(") && !it.startsWith("*") && !it.startsWith("//") }
            }
            .filterValues { it.isNotEmpty() }

    // ------------------------------------------------------------------ o fio

    @Test
    fun aRedacaoContinuaSemChamador() {
        val fontes = fontesDeProducao()
        assertTrue(
            "Varri ${fontes.size} arquivos de produção. Varredura vazia não prova " +
                "ausência de nada — ou a raiz do repositório mudou, ou este teste " +
                "está olhando para o lugar errado.",
            fontes.size > 100,
        )

        // **Controle positivo do SÍMBOLO.** Sem ele, renomear `redigir` faria este
        // teste passar para sempre sobre uma busca que não acha mais nada — que é
        // a família de defeito do `WhisperCppSttTest`, verde por semanas
        // procurando um arquivo que o projeto não embarca.
        val definicoes = fontes.filter { arquivo ->
            arquivo.readText().let { t ->
                t.contains("fun redigir(") || t.contains("suspend fun redigir(")
            }
        }.map { it.name }.toSet()
        assertEquals(
            "Esperava achar `fun redigir(` declarada em Redator.kt, RedatorLlamaCpp.kt " +
                "e RedacaoDoCopiloto.kt. Achei: $definicoes. Se a função foi " +
                "renomeada, este teste parou de guardar o que diz guardar.",
            setOf("Redator.kt", "RedatorLlamaCpp.kt", "RedacaoDoCopiloto.kt"),
            definicoes,
        )

        val chamadores = chamadasDe("redigir", definicoes)

        assertEquals(
            "A Etapa B ganhou chamador em produção:\n" +
                chamadores.entries.joinToString("\n") { (arq, linhas) ->
                    "  $arq:\n" + linhas.joinToString("\n") { "      $it" }
                } +
                "\n\nIsso sobrepõe o teto de 7 palavras do CLAUDE.md §4, que é regra " +
                "dura, e o §7 exige decisão humana antes do diff. A spec é " +
                "`specs/redacao-por-llm-na-fala.spec.md` e ela recomenda NÃO ligar " +
                "com o modelo atual: 9 de 20 perguntas ficam mudas, o guarda aprova " +
                "7 e 1 é utilizável — e trocar o prompt não move a última coluna.\n\n" +
                "Se a decisão JÁ saiu e a ligação é intencional, apague este arquivo " +
                "junto com o diff — e atualize a spec para `estado: aceita`.",
            emptyMap<String, List<String>>(),
            chamadores,
        )
    }

    // ------------------------------------------------------------- o contra-teste

    /**
     * **Prova que a varredura acima consegue ENXERGAR um chamador.**
     *
     * Sem este par, `aRedacaoContinuaSemChamador` passaria por vacuidade em todo
     * cenário em que a busca estivesse quebrada — raiz errada, extensão errada,
     * `filterNot` largo demais. É o padrão que o `CLAUDE.md` §6 pergunta 3 exige:
     * *"se o teste passaria com o defeito de volta, ele não testa o defeito"*.
     *
     * Os dois símbolos escolhidos são os vizinhos exatos de `redigir` no mesmo
     * `object` — `decidirNoBoot` e `liberar`, ambos chamados por `ClaryonApp`. Se
     * a varredura vê estes dois e não vê `redigir`, a diferença é real e está no
     * produto, não no teste.
     */
    @Test
    fun aVarreduraEnxergaOsVizinhosDeRedigirQueTEMChamador() {
        val definidoEm = setOf("RedacaoDoCopiloto.kt")

        val doBoot = chamadasDe("decidirNoBoot", definidoEm)
        assertTrue(
            "A varredura não achou chamador de `decidirNoBoot`, que ClaryonApp.kt " +
                "chama na linha 56. Se ela não vê ESTE, o zero de `redigir` não " +
                "significa nada.",
            doBoot.isNotEmpty(),
        )

        val doLiberar = chamadasDe("RedacaoDoCopiloto.liberar", definidoEm)
        assertTrue(
            "A varredura não achou chamador de `RedacaoDoCopiloto.liberar`, que " +
                "ClaryonApp.kt chama em `onTrimMemory`. Mesmo raciocínio.",
            doLiberar.isNotEmpty(),
        )
    }

    private fun raizDoRepositorio(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "Não achei a raiz do repositório a partir de ${File(".").canonicalPath}. " +
                "Teste que não acha o que precisa conferir tem de falhar, não passar.",
        )
    }
}
