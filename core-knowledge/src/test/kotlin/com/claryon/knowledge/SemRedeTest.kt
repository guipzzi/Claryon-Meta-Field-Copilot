package com.claryon.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Prova que a recuperação não alcança rede nenhuma.**
 *
 * A regra dura do projeto é que nada de áudio, transcrição ou frame vai a
 * serviço externo no caminho crítico. A Etapa A é o caminho onde a pergunta do
 * agente — que é a transcrição da fala dele — encontra um índice. Um `HttpURLConnection`
 * acrescentado aqui para "só consultar a versão do corpus" mandaria a pergunta
 * inteira para fora, compila, e passa em todos os outros testes deste módulo.
 *
 * O critério de aceite da Fase 4 é **modo avião, sem rede nenhuma**. Este teste
 * é a metade que dá para provar de dentro do módulo: o `build.gradle.kts` não
 * traz cliente HTTP nenhum (a fronteira de `FronteiraDoConhecimentoTest`
 * garante a lista) e nenhuma fonte de produção nomeia uma API de rede da JDK.
 *
 * **Limite conhecido:** ausência de nome não é ausência de capacidade — a JDK
 * está no classpath e reflexão contorna qualquer varredura de texto. Isto pega o
 * acréscimo distraído, que é o modo de falha real, não o adversário.
 */
class SemRedeTest {

    @Test
    fun nenhumaFonteDeProducaoNomeiaRede() {
        val fontes = raizDoModulo().resolve("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        // Controle positivo: varredura que não olha nada aprova tudo.
        assertTrue(
            "nenhuma fonte .kt encontrada em src/main — a varredura está cega",
            fontes.size >= 4,
        )

        val proibidos = listOf(
            "java.net", "URL(", "URLConnection", "HttpURLConnection", "Socket",
            "okhttp", "OkHttp", "retrofit", "Retrofit", "ktor", "HttpClient",
            "InetAddress", "https://", "http://",
        )
        for (fonte in fontes) {
            val texto = fonte.readText()
            for (nome in proibidos) {
                assertFalse(
                    "${fonte.name} menciona '$nome'. A pergunta do agente é a " +
                        "transcrição da fala dele; ela não sai do aparelho por " +
                        "nenhum caminho, nem para consultar versão de corpus.",
                    texto.contains(nome),
                )
            }
        }
    }

    /** Meta-teste: a varredura **enxerga** o nome proibido quando ele existe. */
    @Test
    fun aVarreduraDetectaOAcrescimoDistraido() {
        val comAFalha = "val url = java.net.URL(\"https://exemplo/corpus\")"
        assertTrue(
            "Se a varredura não vê 'java.net' numa linha que o usa, ela é decorativa.",
            listOf("java.net", "URL(", "https://").all { comAFalha.contains(it) },
        )
    }

    /** O corpus é lido de recurso local, e é isso que este teste amarra. */
    @Test
    fun oCorpusVemDeRecursoLocal() {
        assertTrue(
            "o corpus tem de ser um recurso do artefato, não um endereço",
            CorpusDeNormas.RECURSO.startsWith("/") && !CorpusDeNormas.RECURSO.contains(":"),
        )
        assertTrue(
            "o recurso não está no classpath — ver a cópia em core-knowledge/build.gradle.kts",
            CorpusDeNormas::class.java.getResource(CorpusDeNormas.RECURSO) != null,
        )
    }
}

private fun raizDoModulo(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir.resolve("core-knowledge")
        dir = dir.parentFile
    }
    throw AssertionError("não achei a raiz do repositório a partir de ${File(".").canonicalPath}")
}
