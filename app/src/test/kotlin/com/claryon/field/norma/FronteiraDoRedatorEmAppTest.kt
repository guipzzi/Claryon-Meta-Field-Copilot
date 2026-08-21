package com.claryon.field.norma

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A garantia que sobrou depois que a medição derrubou a outra.**
 *
 * A Etapa B introduz texto **gerado** no produto, e o `CLAUDE.md` §2 proíbe LLM
 * escolhendo ação. A forma óbvia de provar isso seria mostrar que a saída do
 * modelo não vira comando. Em 21/08 essa prova foi tentada no aparelho e
 * **falhou** — com Llama 3.2 1B, duas de três respostas casaram com o
 * `DeterministicIntentRouter`:
 *
 * ```
 * "preciso chamar apoio para essa ocorrência de trânsito" → PedirApoio
 * "devo gravar a abordagem do condutor embriagado"        → IniciarGravacao
 * ```
 *
 * A causa não tem conserto por prompt: modelo pequeno repete a pergunta ao
 * responder, e o roteador é regex sobre português — qualquer frase com "chamar
 * apoio" casa, venha ela de um agente, de um artigo de lei ou de um LLM.
 *
 * **Então a garantia é estrutural, e é esta.** Ela tem duas metades:
 *
 *  1. `core-llm` não tem `core-agent` no classpath (`FronteiraDoRedatorTest`) —
 *     nenhuma linha daquele módulo consegue nomear uma ação.
 *  2. **Esta.** `app` importa os dois lados, e é aqui que a `String` gerada e o
 *     roteador poderiam se encontrar. Nenhum arquivo de produção de `app` pode
 *     mencionar `com.claryon.llm` e o caminho de ação no mesmo lugar.
 *
 * Não é sofisticado, e é de propósito: o que quebra a regra não é um esquema
 * elaborado, é alguém passando o texto redigido para o roteador "só para
 * testar". Isso mora no mesmo arquivo, e é exatamente o que esta varredura vê.
 *
 * É o mesmo raciocínio de `FronteiraDoConhecimentoEmAppTest`, que faz a pergunta
 * gêmea sobre texto recuperado.
 */
class FronteiraDoRedatorEmAppTest {

    /** O que caracteriza o caminho de ação deste produto, em texto. */
    private val marcasDeAcao = listOf(
        "IntentExecutor",
        "ActionOutcome",
        "IntentRouter",
        "DeterministicIntentRouter",
    )

    private fun fontesDeProducao(): List<File> {
        val raiz = raizDoRepositorio().resolve("app/src/main/kotlin")
        assertTrue("Fontes de app não encontradas em $raiz", raiz.isDirectory)
        return raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun nenhumArquivoDeProducaoJuntaORedatorEOCaminhoDeAcao() {
        val fontes = fontesDeProducao()
        assertTrue(
            "Nenhum .kt varrido — varredura vazia não é prova.",
            fontes.size > 20,
        )

        // **Controle positivo, e sem ele o teste é decorativo.** Se nenhum
        // arquivo de produção mencionar `com.claryon.llm`, "nenhum junta os
        // dois" é verdade por o redator não existir — que é justamente o estado
        // que este projeto confunde com capacidade seis vezes por ano.
        val usamOsRedator = fontes.filter { it.readText().contains("com.claryon.llm") }
        assertTrue(
            "Nenhum arquivo de produção de app importa com.claryon.llm. Ou a " +
                "Etapa B não está ligada em lugar nenhum — e aí ela é código " +
                "escrito, não construído —, ou este teste está olhando para o " +
                "lugar errado. Nos dois casos ele não prova nada.",
            usamOsRedator.isNotEmpty(),
        )

        val infratores = fontes.filter { arquivo ->
            val texto = arquivo.readText()
            texto.contains("com.claryon.llm") && marcasDeAcao.any { texto.contains(it) }
        }.map { it.name }

        assertEquals(
            "Estes arquivos de produção mencionam o REDATOR e o caminho de ação " +
                "no mesmo lugar:\n" + infratores.joinToString("\n") { "  $it" } +
                "\n\nTexto gerado casa com o roteador — está medido. Se esta " +
                "junção for intencional, a exceção entra nesta lista com o " +
                "motivo escrito, por decisão humana.",
            emptyList<String>(),
            infratores,
        )
    }

    /** Meta-teste: a varredura **enxerga** a junção quando ela existe. */
    @Test
    fun aVarreduraDetectaAJuncaoQuandoElaExiste() {
        val comAFalha = """
            import com.claryon.llm.Redator
            import com.claryon.agent.IntentExecutor
            class Errado(val r: Redator, val e: IntentExecutor)
        """.trimIndent()
        assertTrue(
            "Se a varredura não vê a junção num arquivo que a comete, ela é " +
                "decorativa.",
            comAFalha.contains("com.claryon.llm") && marcasDeAcao.any { comAFalha.contains(it) },
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
