package com.claryon.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Prova que nada de `core-llm` alcança o executor de ações.**
 *
 * É o gêmeo de `FronteiraDoConhecimentoTest`, e a regra que ele guarda é mais
 * dura aqui do que lá. Na Etapa A o texto que sai é *recuperado*: existia antes,
 * escrito por um legislador. Na Etapa B o texto é **gerado** — e texto gerado
 * não tem a decência de ser improvável. O `CLAUDE.md` §2 é literal: *"LLM
 * escolhendo ação"* é proibido, sem versão, sem flag, sem exceção.
 *
 * O que sustenta a regra é a **fronteira de módulo**, não a disciplina: sem
 * `core-agent` no classpath, uma linha daqui não consegue nomear os tipos de
 * ação nem por engano. Só que fronteira é uma linha em `build.gradle.kts`, e uma
 * linha some sem ninguém notar.
 *
 * **Limite conhecido, e ele importa.** Isto é necessário e não suficiente: `app`
 * importa os dois lados, e é lá que a `String` gerada e o executor podem se
 * encontrar. A outra metade da prova é instrumentada e roda no aparelho
 * (`RedatorNoAparelhoTest`), sobre a saída REAL do modelo.
 */
class FronteiraDoRedatorTest {

    @Test
    fun oBuildDeclaraApenasCoreCommon() {
        assertEquals(
            "core-llm só pode depender de :core-common. Com :core-agent no " +
                "classpath, a saída do modelo passa a poder nomear uma ação — que é " +
                "exatamente o que o CLAUDE.md §2 proíbe sem exceção.",
            setOf(":core-common"),
            dependenciasDeProjeto(arquivoDeBuild().readText()),
        )
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
            "Se o leitor não vê :core-agent num build que o declara, este teste é " +
                "decorativo.",
            setOf(":core-common", ":core-agent"),
            dependenciasDeProjeto(comAFalha),
        )
    }

    /** Meta-teste: menção em comentário não conta — e este build explica a proibição. */
    @Test
    fun oLeitorIgnoraMencaoEmComentario() {
        val soComentario = """
            // Nunca acrescente implementation(project(":core-agent")) aqui.
            dependencies {
                implementation(project(":core-common")) // https://exemplo/doc
            }
        """.trimIndent()
        assertEquals(setOf(":core-common"), dependenciasDeProjeto(soComentario))
    }

    @Test
    fun oClasspathDeTesteNaoContemCoreAgent() {
        val entradas = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }

        // Controle positivo: dependência declarada APARECE no classpath. Sem
        // isto, a ausência de :core-agent poderia ser só uma forma diferente de
        // o Gradle montar a lista, e a prova viraria tautologia.
        assertTrue(
            "Controle positivo falhou: :core-common é dependência declarada e não " +
                "apareceu no classpath de teste.",
            entradas.any { it.contains("${File.separator}core-common${File.separator}") },
        )

        assertEquals(
            "core-agent chegou ao classpath de core-llm. Se não está no " +
                "build.gradle.kts deste módulo, veio transitivamente — procure um " +
                "`api(project(\":core-agent\"))` em core-common.",
            emptyList<String>(),
            entradas.filter { it.contains("${File.separator}core-agent${File.separator}") },
        )
    }

    @Test
    fun nenhumTipoDeAcaoCarregaNesteModulo() {
        for (presente in listOf("com.claryon.llm.Redator", "com.claryon.common.Result")) {
            assertTrue(
                "Controle positivo falhou: $presente deveria carregar. Sem isso, " +
                    "'não carregou' não distingue ausência de fronteira quebrada.",
                carrega(presente),
            )
        }
        for (proibida in TIPOS_DE_ACAO) {
            assertFalse(
                "$proibida está alcançável de dentro de core-llm. Este módulo " +
                    "devolve texto; quem transforma texto em ação é outra camada.",
                carrega(proibida),
            )
        }
    }

    /**
     * **Nem por reflexão.** `Class.forName` aceita `String`, e o compilador não
     * vê nome de classe escrito como texto — então a varredura olha o texto.
     */
    @Test
    fun nenhumaFonteDeProducaoNomeiaOExecutor() {
        val fontes = arquivoDoModulo("src/main")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "cpp") }
            .toList()

        assertTrue(
            "Controle positivo falhou: nenhuma fonte encontrada em src/main. " +
                "Varredura que não olha nada aprova tudo.",
            fontes.size >= 4,
        )

        for (fonte in fontes) {
            val texto = fonte.readText()
            for (nome in NOMES_PROIBIDOS_EM_PRODUCAO) {
                assertFalse(
                    "${fonte.name} menciona '$nome'.",
                    texto.contains(nome),
                )
            }
        }
    }

    /**
     * **O JNI não abre rede, e isso é conferido no fonte.**
     *
     * O pilar P3 é 100% local. O llama.cpp vendorizado traz caminhos de HTTP
     * (download de modelo por URL) que o nosso build desliga por
     * `-DLLAMA_OPENSSL=OFF`/`-DLLAMA_CURL=OFF`; esta varredura garante que o
     * nosso lado também não os invoca — nem por um `#include` distraído.
     */
    @Test
    fun oJniNaoNomeiaRede() {
        val jni = arquivoDoModulo("src/main/cpp/redator_jni.cpp").readText()
        for (proibido in listOf("curl", "http", "socket", "openssl")) {
            assertFalse(
                "redator_jni.cpp menciona '$proibido'. Nada de IA na nuvem em " +
                    "caminho nenhum (CLAUDE.md §2).",
                jni.lowercase().contains(proibido),
            )
        }
    }
}

// --------------------------------------------------------------------- utilitários

private val TIPOS_DE_ACAO = listOf(
    "com.claryon.agent.IntentExecutor",
    "com.claryon.agent.Intent",
    "com.claryon.agent.ActionOutcome",
    "com.claryon.field.agent.ClaryonIntentExecutor",
)

private val NOMES_PROIBIDOS_EM_PRODUCAO = listOf(
    "com.claryon.agent",
    "com.claryon.field",
    "IntentExecutor",
    "ActionOutcome",
)

private fun carrega(fqn: String): Boolean = try {
    Class.forName(fqn, false, FronteiraDoRedatorTest::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
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

private fun arquivoDoModulo(relativo: String): File {
    val alvo = raizDoRepositorio().resolve("core-llm").resolve(relativo)
    assertTrue("Esperava encontrar $alvo", alvo.exists())
    return alvo
}

private fun arquivoDeBuild(): File = arquivoDoModulo("build.gradle.kts")

private val COMENTARIO_DE_LINHA = Regex("""(?<!:)//[^\n]*""")
private val COMENTARIO_DE_BLOCO = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val DEPENDENCIA_DE_PROJETO = Regex("""project\(\s*"(:[^"]+)"\s*\)""")

private fun semComentarios(texto: String): String =
    texto.replace(COMENTARIO_DE_BLOCO, " ").replace(COMENTARIO_DE_LINHA, " ")

private fun dependenciasDeProjeto(buildFile: String): Set<String> =
    DEPENDENCIA_DE_PROJETO.findAll(semComentarios(buildFile))
        .map { it.groupValues[1] }
        .toSet()
