package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Prova que nada de `core-knowledge` alcança `ClaryonIntentExecutor`.**
 *
 * A regra dura do projeto é que o LLM não escolhe ação — ele só preenche campos
 * de intenção já definida. Na Etapa A não há LLM nenhum, mas o formato do risco é
 * o mesmo: o que sai daqui é *texto recuperado*, e texto recuperado não pode
 * virar despacho, gravação ou troca de canal por nenhum caminho.
 *
 * **O que sustenta a regra é a fronteira de módulo, não a disciplina.** Sem
 * `core-agent` no classpath, uma linha deste módulo não consegue nomear `Intent`,
 * `ActionOutcome` ou `IntentExecutor` nem por engano — o compilador recusa. Só que
 * fronteira é uma linha em `build.gradle.kts`, e uma linha some ou aparece sem
 * ninguém notar: um `implementation(project(":core-agent"))` acrescentado numa
 * madrugada para "só reaproveitar aquele enum" compila, passa em todos os testes
 * existentes e desmonta a garantia em silêncio. É por isso que a fronteira precisa
 * de teste.
 *
 * **Duas provas independentes, de naturezas diferentes de propósito:**
 *
 *  1. **O que está declarado** — lê `core-knowledge/build.gradle.kts` e exige que
 *     as dependências de projeto sejam exatamente `{":core-common"}`. Pega o diff
 *     na hora, com mensagem que diz o porquê.
 *  2. **O que de fato chegou** — varre o classpath de teste e tenta carregar os
 *     tipos de ação por reflexão. Esta não depende de sintaxe de build, e por isso
 *     pega o que a primeira não pegaria: `core-agent` entrando **transitivamente**
 *     (por exemplo, `core-common` passando a expor `api(project(":core-agent"))`),
 *     ou injetado por um bloco `subprojects { }` na raiz.
 *
 * Todas as asserções têm **controle positivo**: um teste que só diz "não achei"
 * também passa quando o mecanismo de busca está quebrado, e aí ele não prova nada.
 * Por isso cada varredura exige achar o que *deve* estar lá — `core-common` no
 * classpath, arquivos `.kt` na pasta de fontes — antes de valer como prova de
 * ausência.
 *
 * **Limite conhecido, e ele importa.** Fronteira de módulo é necessária e **não
 * suficiente**: o `ROADMAP.md` registra que `app` importa os dois lados, e é lá
 * que uma `String` recuperada e o executor podem se encontrar. A garantia completa
 * exige um teste em `app`, que não existe ainda — este arquivo cobre a metade que
 * pode ser provada de dentro do módulo.
 */
class FronteiraDoConhecimentoTest {

    // ---------------------------------------------------------------- 1. declarado

    @Test
    fun oBuildDeclaraApenasCoreCommon() {
        val declaradas = dependenciasDeProjeto(arquivoDeBuildDoModulo().readText())

        assertEquals(
            "core-knowledge só pode depender de :core-common. Dependência a mais " +
                "(:core-agent em especial) devolve os tipos de ação ao alcance deste " +
                "módulo e desfaz a fronteira que impede texto recuperado de virar ação.",
            setOf(":core-common"),
            declaradas,
        )
    }

    @Test
    fun oBuildNaoUsaFormaDeDependenciaQueEsteTesteNaoSabeLer() {
        val texto = semComentarios(arquivoDeBuildDoModulo().readText())

        // Se a declaração migrar para acessores de tipo seguro (`projects.coreAgent`)
        // ou para um `fileTree`, o leitor acima devolveria conjunto vazio e o teste
        // acima passaria sem provar nada. Falhar aqui é o comportamento certo:
        // forma nova exige leitor novo, e a decisão é humana.
        for (forma in listOf("projects.", "fileTree(", "files(")) {
            assertFalse(
                "O build passou a declarar dependência numa forma que este teste não " +
                    "interpreta ('$forma'). Ensine o leitor antes de trocar a forma — " +
                    "senão a fronteira fica sem guarda.",
                texto.contains(forma),
            )
        }
    }

    /** Meta-teste: o leitor **enxerga** a dependência proibida quando ela existe. */
    @Test
    fun oLeitorDoBuildDetectaADependenciaProibida() {
        val comAFalha = """
            dependencies {
                implementation(project(":core-common"))
                implementation(project(":core-agent"))
            }
        """.trimIndent()

        assertEquals(
            "Se o leitor não vê :core-agent num build que o declara, o teste de " +
                "fronteira é decorativo.",
            setOf(":core-common", ":core-agent"),
            dependenciasDeProjeto(comAFalha),
        )
    }

    /** Meta-teste: menção em comentário **não** conta como dependência. */
    @Test
    fun oLeitorIgnoraMencaoEmComentario() {
        val soComentario = """
            // Nunca acrescente implementation(project(":core-agent")) aqui.
            /* Nem project(":core-agent") em bloco. */
            dependencies {
                implementation(project(":core-common")) // https://exemplo/doc
            }
        """.trimIndent()

        assertEquals(
            "O próprio build explica por escrito por que :core-agent é proibido. Um " +
                "leitor que confunde a explicação com a dependência deixa o teste " +
                "vermelho para sempre — e teste sempre vermelho é teste apagado.",
            setOf(":core-common"),
            dependenciasDeProjeto(soComentario),
        )
    }

    // ------------------------------------------------------------- 2. o que chegou

    @Test
    fun oClasspathDeTesteNaoContemCoreAgent() {
        val entradas = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }

        // Controle positivo: uma dependência de projeto *aparece* nesta lista, na
        // forma de um caminho que contém o nome do módulo. Sem esta asserção, a
        // ausência de :core-agent poderia ser só um jeito diferente de o Gradle
        // montar o classpath — e a prova viraria tautologia.
        assertTrue(
            "Controle positivo falhou: :core-common é dependência declarada e não " +
                "apareceu no classpath de teste. Enquanto isso não for verdade, a " +
                "ausência de :core-agent não prova nada.",
            entradas.any { it.contains(caminhoDoModulo("core-common")) },
        )

        val intrusas = entradas.filter { it.contains(caminhoDoModulo("core-agent")) }
        assertEquals(
            "core-agent chegou ao classpath de core-knowledge. Se não está no " +
                "build.gradle.kts deste módulo, veio transitivamente — procure um " +
                "`api(project(\":core-agent\"))` em core-common ou injeção na raiz.",
            emptyList<String>(),
            intrusas,
        )
    }

    @Test
    fun nenhumTipoDeAcaoCarregaNesteModulo() {
        // Controle positivo: a reflexão daqui realmente encontra classes do
        // projeto — as deste módulo e as da única dependência declarada.
        for (presente in listOf("com.claryon.knowledge.Trecho", "com.claryon.common.Result")) {
            assertTrue(
                "Controle positivo falhou: $presente deveria carregar. Sem isso, " +
                    "'não carregou' não distingue ausência de fronteira quebrada.",
                carrega(presente),
            )
        }

        for (proibida in TIPOS_DE_ACAO) {
            assertFalse(
                "$proibida está alcançável de dentro de core-knowledge. O módulo do " +
                    "conhecimento devolve texto; quem transforma texto em ação é outra " +
                    "camada, e esta fronteira é o que garante isso.",
                carrega(proibida),
            )
        }
    }

    // -------------------------------------------------------- 3. nem por reflexão

    @Test
    fun nenhumaFonteDeProducaoNomeiaOExecutor() {
        // Só `src/main`: o teste que você está lendo cita os nomes proibidos de
        // propósito, para proibi-los.
        val fontes = arquivoDoModulo("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(
            "Controle positivo falhou: nenhuma fonte .kt encontrada em src/main. " +
                "Varredura que não olha nada aprova tudo.",
            fontes.isNotEmpty(),
        )

        for (fonte in fontes) {
            val texto = fonte.readText()
            for (nome in NOMES_PROIBIDOS_EM_PRODUCAO) {
                assertFalse(
                    "${fonte.name} menciona '$nome'. Mesmo sem import — e é justamente " +
                        "por causa de Class.forName que esta varredura existe: o " +
                        "compilador não vê nome de classe escrito como String.",
                    texto.contains(nome),
                )
            }
        }
    }
}

// --------------------------------------------------------------------- utilitários

/**
 * Tipos que decidem ou executam ação. Nenhum deles pode ser alcançável daqui.
 * `ClaryonIntentExecutor` mora em `app` e os demais em `core-agent`.
 */
private val TIPOS_DE_ACAO = listOf(
    "com.claryon.agent.IntentExecutor",
    "com.claryon.agent.Intent",
    "com.claryon.agent.ActionOutcome",
    "com.claryon.field.agent.ClaryonIntentExecutor",
)

/** O que uma fonte de produção deste módulo não pode nomear, nem dentro de String. */
private val NOMES_PROIBIDOS_EM_PRODUCAO = listOf(
    "com.claryon.agent",
    "com.claryon.field",
    "IntentExecutor",
    "ActionOutcome",
)

private fun carrega(fqn: String): Boolean = try {
    // `initialize = false`: queremos saber se o tipo está ao alcance, sem rodar
    // inicializador estático de nada.
    Class.forName(fqn, false, FronteiraDoConhecimentoTest::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}

/** `/core-agent/` com os separadores desta plataforma. */
private fun caminhoDoModulo(nome: String): String =
    "${File.separator}$nome${File.separator}"

/**
 * Raiz do repositório, achada subindo até o `settings.gradle.kts`. O diretório de
 * trabalho do teste é o do módulo, mas subir torna a busca válida também quando
 * alguém roda pela IDE, da raiz.
 */
private fun raizDoRepositorio(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    throw AssertionError(
        "Não achei a raiz do repositório a partir de ${File(".").canonicalPath}. " +
            "Sem ela não dá para ler o build do módulo — e teste que não acha o que " +
            "precisa conferir tem de falhar, não passar.",
    )
}

private fun arquivoDoModulo(relativo: String): File {
    val alvo = raizDoRepositorio().resolve("core-knowledge").resolve(relativo)
    assertTrue("Esperava encontrar $alvo", alvo.exists())
    return alvo
}

private fun arquivoDeBuildDoModulo(): File = arquivoDoModulo("build.gradle.kts")

// `(?<!:)` preserva `https://…` dentro de String — sem isso, uma URL numa linha
// engoliria o resto dela, inclusive uma dependência declarada depois.
private val COMENTARIO_DE_LINHA = Regex("""(?<!:)//[^\n]*""")
private val COMENTARIO_DE_BLOCO = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val DEPENDENCIA_DE_PROJETO = Regex("""project\(\s*"(:[^"]+)"\s*\)""")

private fun semComentarios(texto: String): String =
    texto.replace(COMENTARIO_DE_BLOCO, " ").replace(COMENTARIO_DE_LINHA, " ")

/** Dependências de projeto declaradas em [buildFile], ignorando comentários. */
private fun dependenciasDeProjeto(buildFile: String): Set<String> =
    DEPENDENCIA_DE_PROJETO.findAll(semComentarios(buildFile))
        .map { it.groupValues[1] }
        .toSet()
