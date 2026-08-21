package com.claryon.field.oculos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A trava contra o defeito voltar — e ela FALHA com o defeito de volta.**
 *
 * ## O que esta varredura guarda
 *
 * `DatGlassesFacade` recebe o escopo no construtor e roda tudo dentro dele. Ela
 * era construída em dois lugares, cada um com o **seu** `viewModelScope`:
 *
 * ```kotlin
 * // OculosViewModel.kt:38   — produção
 * private val facade = DatGlassesFacade(viewModelScope)
 * // DiagnosticoViewModel.kt:39 — painel de depuração
 * private val facade = DatGlassesFacade(viewModelScope)
 * ```
 *
 * Duas consequências, e as duas são defeito:
 *
 *  1. **A fachada morre com a Activity** — sessão, stream e coleta de
 *     `errorStream` junto. Um fluxo de câmera de 5 s com o celular no bolso não
 *     existe.
 *  2. **Duas donas de um recurso global do aparelho.** É o mesmo defeito de classe
 *     que `AudioDoAgente` conserta para o `AudioManager`, e aqui é pior: o KDoc de
 *     `DatGlassesFacade.stopSession` já adverte que "um `createSession` seguinte
 *     encontra a anterior ativa".
 *
 * ## Por que uma varredura de fonte, e não um teste de runtime
 *
 * O `SessaoSemTelaTest` prova que o dono de processo **vive** sem tela. Ele não
 * consegue provar que **nenhuma tela pode ser dona** — para isso seria preciso
 * construir cada ViewModel do app, matá-lo, e observar um efeito no SDK que o
 * emulador sem óculos não produz.
 *
 * O §6 do `CLAUDE.md`, pergunta 3, é o critério: *"Se o teste passaria com o
 * defeito de volta, ele não testa o defeito."* Com `DatGlassesFacade(viewModelScope)`
 * de volta em qualquer ViewModel, **este teste falha e nomeia o arquivo**.
 *
 * Mesma forma da varredura de `FronteiraDoConhecimentoEmAppTest`, inclusive o
 * filtro de comentário: **falar sobre** o símbolo não é **usá-lo**, e um teste que
 * reprova a documentação da regra ensina a apagar a documentação.
 */
class FachadaDoDatTemDonoUnicoTest {

    /** O único arquivo autorizado a construir a fachada. */
    private val donoDeProcesso = "SessaoDosOculos.kt"

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
     * `//` dentro de literal de string. Para procurar uma **construção** de tipo, o
     * efeito é nenhum.
     */
    private fun semComentarios(fonte: String): String =
        fonte.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * Construção da fachada — `DatGlassesFacade(`, e não a mera menção do nome.
     *
     * `import com.claryon.glasses.DatGlassesFacade` e `private val facade:
     * DatGlassesFacade` são leitura legítima do tipo; `DatGlassesFacade(` é
     * instanciação. A diferença é o parêntese, e é ela que separa quem usa de quem
     * é dono.
     *
     * O `(?<!class )` existe porque a **declaração** — `class DatGlassesFacade(private
     * val scope: CoroutineScope)`, em `core-glasses` — casa com o resto do padrão.
     * Sem o recorte, o arquivo que define o tipo seria o primeiro infrator da lista,
     * e a saída fácil seria afrouxar a varredura até ela não valer nada.
     */
    private val constroiAFachada = Regex("""(?<!class )DatGlassesFacade\s*\(""")

    private fun fontesDeProducao(): List<File> {
        val raiz = raizDoRepositorio()
        val dirs = listOf(
            raiz.resolve("app/src/main/kotlin"),
            raiz.resolve("app/src/debug/kotlin"),
            raiz.resolve("core-glasses/src/main/kotlin"),
        )
        dirs.forEach { d ->
            assertTrue("Fontes não encontradas em $d — varredura que não varre não é prova.", d.isDirectory)
        }
        return dirs.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" } }
    }

    /**
     * **Nenhum ViewModel — nenhum arquivo de produção — constrói a fachada.**
     *
     * A exceção é uma só e nominal: o dono de processo. Qualquer outra entra aqui
     * com o motivo escrito, por decisão humana, e não por diff que passou.
     */
    @Test
    fun somenteODonoDeProcessoConstroiAFachadaDoDat() {
        val arquivos = fontesDeProducao()
        assertTrue(
            "Só ${arquivos.size} arquivos .kt varridos — varredura vazia não é prova.",
            arquivos.size > 20,
        )

        // Controle positivo: a varredura precisa achar o dono. Se `SessaoDosOculos`
        // for renomeado ou apagado, este teste passaria vazio e aprovaria tudo.
        val doDono = arquivos.filter { it.name == donoDeProcesso }
        assertEquals(
            "O dono de processo ($donoDeProcesso) não foi varrido. Sem ele, " +
                "'ninguém mais constrói' é verdade por vacuidade.",
            1,
            doDono.size,
        )
        assertTrue(
            "$donoDeProcesso não constrói `DatGlassesFacade`. Ou ele deixou de ser " +
                "o dono, ou a varredura parou de enxergar a construção — nos dois " +
                "casos esta trava está cega.",
            constroiAFachada.containsMatchIn(semComentarios(doDono.single().readText())),
        )

        val infratores = arquivos
            .filter { it.name != donoDeProcesso }
            .filter { constroiAFachada.containsMatchIn(semComentarios(it.readText())) }
            .map { it.relativeTo(raizDoRepositorio()).path }

        assertEquals(
            "Estes arquivos de produção constroem a própria `DatGlassesFacade`:\n" +
                infratores.joinToString("\n") { "  $it" } +
                "\n\nA fachada roda TUDO no escopo que recebe — registro, sessão, " +
                "stream, `errorStream`, vigia de primeiro frame. Uma segunda " +
                "instância é uma segunda dona de um recurso global do aparelho; " +
                "construída com `viewModelScope`, ela ainda morre com a tela. " +
                "Use `SessaoDosOculos.facade()`. Ver " +
                "specs/dono-de-processo-para-a-facade-do-dat.spec.md",
            emptyList<String>(),
            infratores,
        )
    }

    /**
     * **`viewModelScope` não pode voltar a encostar na fachada — em lugar nenhum.**
     *
     * A varredura acima já pegaria `DatGlassesFacade(viewModelScope)`, porque pega
     * qualquer construção. Esta é mais estreita e mais explícita: nomeia o defeito
     * exato de 21/08, para que quem o reintroduzir leia o motivo no lugar de ver
     * "construção não autorizada".
     */
    @Test
    fun nenhumEscopoDeViewModelAlimentaAFachadaDoDat() {
        val infratores = fontesDeProducao().filter { f ->
            val t = semComentarios(f.readText())
            t.contains("DatGlassesFacade") && t.contains("viewModelScope")
        }.map { it.relativeTo(raizDoRepositorio()).path }

        assertEquals(
            "Estes arquivos amarram a fachada do DAT a um escopo de ViewModel:\n" +
                infratores.joinToString("\n") { "  $it" } +
                "\n\nÉ o defeito que `SessaoDosOculos` conserta: `viewModelScope` é " +
                "cancelado no `onCleared()`, e a fachada leva sessão, stream e a " +
                "coleta de `errorStream` junto. Um fluxo de câmera de 5 s com o " +
                "celular no bolso deixa de existir.",
            emptyList<String>(),
            infratores,
        )
    }

    /**
     * **O contra-teste da varredura: ela ainda pega violador de verdade.**
     *
     * Sem isto, os dois testes acima passariam também se `semComentarios` ou o
     * regex tivessem sido afrouxados até cegar — e ficariam verdes para sempre.
     * Afrouxar uma varredura é a forma mais fácil de matar um teste sem que
     * ninguém perceba.
     *
     * As duas asserções prendem o filtro pelos dois lados: o violador em CÓDIGO tem
     * de ser pego, e o arquivo que só MENCIONA o tipo (import, tipo de parâmetro,
     * KDoc) tem de passar. Uma sozinha não prende.
     */
    @Test
    fun aVarreduraAINDAPegaViolador_comOFiltroDeComentarioLigado() {
        val violador = """
            package com.claryon.field.exemplo
            import androidx.lifecycle.viewModelScope
            import com.claryon.glasses.DatGlassesFacade
            class Ruim : AndroidViewModel(app) {
                private val facade = DatGlassesFacade(viewModelScope)
            }
        """.trimIndent()

        val inocente = """
            package com.claryon.field.exemplo
            import com.claryon.glasses.DatGlassesFacade
            /** Não constrói DatGlassesFacade(...) — só recebe a do processo. */
            // e nem encosta em viewModelScope: DatGlassesFacade(viewModelScope) seria o defeito.
            class Boa(private val facade: DatGlassesFacade)
        """.trimIndent()

        fun constroi(fonte: String) = constroiAFachada.containsMatchIn(semComentarios(fonte))

        assertTrue(
            "O filtro cegou a varredura: um arquivo que CONSTRÓI a fachada com " +
                "`viewModelScope` passou limpo. Enquanto isto for verdade, a trava " +
                "aprova qualquer coisa.",
            constroi(violador),
        )
        assertTrue(
            "Um arquivo que só recebe a fachada por parâmetro foi reprovado — a " +
                "varredura passou a punir uso legítimo, e o próximo passo é alguém " +
                "afrouxá-la até não valer nada.",
            !constroi(inocente),
        )
    }
}
