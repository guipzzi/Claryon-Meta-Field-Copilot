package com.claryon.field.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A trava contra o defeito voltar — e ela FALHA com o defeito de volta.**
 *
 * ## O defeito de 22/08
 *
 * `CerebroDoCopiloto.localizar` abria assim:
 *
 * ```kotlin
 * if (!redeConfigurada || SessaoDoAgente.tokenValido(app) == null) {
 *     return BuscaDePar.Indisponivel
 * }
 * ```
 *
 * e trinta linhas acima, no mesmo arquivo, estava escrito *"`runBlocking` NÃO: a
 * renovação de token não pode travar o ciclo de voz"*. A intenção estava lá; o
 * caminho é que furava. `AutenticacaoSupabase.tokenValido`, fora da margem de 60 s,
 * entra num `Mutex` e faz `client.newCall(req).execute()` — **síncrono** — com um
 * `OkHttpClient()` de fábrica que não tem `callTimeout`. Dentro de um ciclo cujo
 * aceite é 4 s.
 *
 * ## Por que uma varredura de fonte, e não um teste de runtime
 *
 * Provar em runtime que o ciclo de voz não espera exigiria subir o cérebro do
 * copiloto inteiro (áudio, whisper de 75 MB, VAD) contra um servidor lento — o que
 * o emulador não sustenta e a JVM não roda. A varredura prova a propriedade que
 * importa e nomeia o arquivo infrator.
 *
 * O §6 do `CLAUDE.md`, pergunta 3, é o critério: *"Se o teste passaria com o
 * defeito de volta, ele não testa o defeito."* Com `tokenValido(` de volta em
 * qualquer arquivo do pacote `voice`, **este teste falha**.
 *
 * Mesma forma — inclusive o filtro de comentário — de
 * `FachadaDoDatTemDonoUnicoTest`: **falar sobre** o símbolo não é **usá-lo**, e um
 * teste que reprova a documentação da regra ensina a apagar a documentação.
 */
class TokenNaoTravaOCicloDeVozTest {

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

    /**
     * Tira `/* */`, KDoc e `//`. Limite honesto, o mesmo do teste irmão: não trata
     * `//` dentro de literal de string. Para procurar uma **chamada**, o efeito é
     * nenhum.
     */
    private fun semComentarios(fonte: String): String =
        fonte.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * A **chamada** que espera rede — `tokenValido(`, e não a menção do nome.
     *
     * `suspend fun tokenValido(` é a declaração, em `core-net` e em
     * `SessaoDoAgente`; nenhuma das duas está sob varredura aqui. O que está é o
     * pacote `voice`, e lá dentro não existe declaração nenhuma desse nome — só
     * chamada.
     */
    private val esperaPelaRenovacao = Regex("""tokenValido\s*\(""")

    private fun fontesDoCicloDeVoz(): List<File> {
        val dir = raizDoRepositorio().resolve("app/src/main/kotlin/com/claryon/field/voice")
        assertTrue(
            "Fontes do ciclo de voz não encontradas em $dir — varredura que não " +
                "varre não é prova.",
            dir.isDirectory,
        )
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun oCicloDeVozNaoChamaARenovacaoQueEsperaRede() {
        val arquivos = fontesDoCicloDeVoz()
        assertTrue(
            "Só ${arquivos.size} arquivos .kt varridos — varredura vazia não é prova.",
            arquivos.size >= 3,
        )

        // Controle positivo: o cérebro do copiloto precisa continuar consultando o
        // token de ALGUMA forma. Sem esta asserção, apagar a consulta inteira
        // deixaria este teste verde sobre um copiloto que nunca autentica nada.
        val cerebro = arquivos.singleOrNull { it.name == "CopilotoDoAgente.kt" }
            ?: throw AssertionError(
                "CopilotoDoAgente.kt não foi varrido. Sem ele, 'ninguém espera pela " +
                    "renovação' é verdade por vacuidade.",
            )
        val fonteDoCerebro = semComentarios(cerebro.readText())
        assertTrue(
            "CopilotoDoAgente.kt não consulta mais o token por caminho nenhum " +
                "(`tokenSemEsperar` ou `tokenCorrente`). Ou a consulta de posição " +
                "deixou de exigir sessão, ou esta trava ficou cega.",
            "tokenSemEsperar" in fonteDoCerebro || "tokenCorrente" in fonteDoCerebro,
        )

        val infratores = arquivos
            .filter { esperaPelaRenovacao.containsMatchIn(semComentarios(it.readText())) }
            .map { it.relativeTo(raizDoRepositorio()).path }

        assertEquals(
            "Estes arquivos do ciclo de voz chamam `tokenValido`, que faz " +
                "`execute()` síncrono dentro de um `Mutex`:\n" +
                infratores.joinToString("\n") { "  $it" } +
                "\n\nO ciclo de voz tem 4 s de aceite e a renovação tem teto de " +
                "${com.claryon.net.AutenticacaoSupabase.TETO_DA_CHAMADA_MS} ms — " +
                "ela não cabe. Use `SessaoDoAgente.tokenSemEsperar` (ou " +
                "`tokenCorrente`), que confere o vencimento em memória e dispara a " +
                "renovação em segundo plano sem esperá-la.",
            emptyList<String>(),
            infratores,
        )
    }
}
