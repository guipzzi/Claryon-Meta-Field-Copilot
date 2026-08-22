package com.claryon.field.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A pergunta 2 do `CLAUDE.md` §6, transformada em teste: quem chama isto?**
 *
 * *"Zero chamador = escrito, não construído."* A consulta externa nasceu nessa
 * condição e ficou nela por uma sessão inteira: `ConsultaGeoespacial`,
 * `HigieneDaConsulta`, `ConsultaHigienizada`, `RegistroDeUso` — quatro arquivos com
 * KDoc, testes possíveis e **nenhuma chamada vinda de `src/main`**. O executor
 * tinha `procurarLugar` com padrão `BuscaDeLugar.SemRede`, então em produção o
 * agente ouvia *"Sem rede para consultar."* com quatro barras de sinal, e a suíte
 * ficava verde.
 *
 * Nenhum dos testes de comportamento pega isso, e é importante entender por quê:
 * eles montam o próprio executor com a própria fonte. Um teste que monta a
 * dependência não pode provar que alguém a montou em produção — ele prova o
 * contrário do que parece.
 *
 * Por isso este arquivo lê o **código-fonte de `src/main`**. É a mesma técnica de
 * `ChamadorDaRedacaoTest`, com o sinal invertido: lá se guarda uma ausência
 * decidida, aqui se guarda uma presença.
 *
 * ## Como este teste morre
 *
 * Se alguém remover a linha `procurarLugar = …` da raiz de composição, ele falha
 * dizendo exatamente isso. É o único lugar do projeto onde essa remoção é visível,
 * porque o padrão do parâmetro **recusa em silêncio** — de propósito, para não
 * haver regressão por omissão, e ao custo de a omissão ser silenciosa.
 */
class ChamadorDaConsultaExternaTest {

    // ── Varredura ─────────────────────────────────────────────────────────────

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

    private fun fontesDeProducao(): List<File> {
        val raiz = raizDoRepositorio()
        return raiz.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "app" || it.name.startsWith("core-")) }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filterNot { it.path.contains("/cpp/llama/") }
    }

    /** Arquivos de produção cujas linhas de CÓDIGO (não KDoc) contêm [trecho]. */
    private fun arquivosQueContem(trecho: String, exceto: Set<String> = emptySet()): Map<String, List<String>> =
        fontesDeProducao()
            .filterNot { it.name in exceto }
            .associate { arquivo ->
                arquivo.name to arquivo.readLines()
                    .map { it.trim() }
                    .filter { it.contains(trecho) && !it.startsWith("*") && !it.startsWith("//") }
            }
            .filterValues { it.isNotEmpty() }

    // ── O controle positivo vem PRIMEIRO ──────────────────────────────────────

    /**
     * **Sem isto, todo o resto do arquivo é decorativo.**
     *
     * Uma varredura quebrada — raiz errada, extensão errada, filtro largo demais —
     * "não acha chamador" de tudo, e um teste que exige presença falharia por
     * motivo errado, ou pior: um que exigisse ausência passaria por vacuidade. Foi
     * assim que `WhisperCppSttTest` ficou verde por semanas procurando um arquivo
     * que o projeto não embarca.
     */
    @Test
    fun aVarreduraEnxergaOQueJaEstaLigado() {
        val fontes = fontesDeProducao()
        assertTrue(
            "Varri ${fontes.size} arquivos de produção — poucos demais. A raiz do " +
                "repositório mudou, ou este teste está olhando para o lugar errado.",
            fontes.size > 100,
        )
        // `consultarNorma` está ligado desde a Etapa A e é vizinho de `procurarLugar`
        // na mesma lista de parâmetros. Se a varredura vê aquele e não vê este, a
        // diferença é real e está no produto.
        assertTrue(
            "a varredura não achou `consultarNorma =`, que a raiz de composição " +
                "liga desde a Etapa A — ela não enxerga fiação nenhuma",
            arquivosQueContem("consultarNorma =").isNotEmpty(),
        )
    }

    // ── O fio ─────────────────────────────────────────────────────────────────

    /**
     * **`procurarLugar` tem chamador, e ele está na mesma raiz de composição.**
     *
     * A segunda parte não é firula: uma linha `procurarLugar = …` num arquivo que
     * ninguém constrói seria chamador no `grep` e código morto em runtime — que é a
     * distinção exata que o §6 faz entre *"tem chamador"* e *"alcançável em
     * runtime"*. Exigir que o mesmo arquivo construa o `ClaryonIntentExecutor`
     * amarra as duas coisas.
     */
    @Test
    fun aCascataExternaEstaLigadaNaRaizDeComposicao() {
        val fiacao = arquivosQueContem("procurarLugar =", exceto = setOf("ClaryonIntentExecutor.kt"))
        assertTrue(
            "`procurarLugar` não tem chamador em src/main. O padrão dele é " +
                "`BuscaDeLugar.SemRede`, então a cascata externa termina sempre em " +
                "\"Sem rede para consultar.\" — com ou sem rede. É a sétima vez de " +
                "'escrito e não construído' que o CLAUDE.md §6 conta.",
            fiacao.isNotEmpty(),
        )

        val raizes = arquivosQueContem("ClaryonIntentExecutor(", exceto = setOf("ClaryonIntentExecutor.kt")).keys
        val ligadoNaRaiz = fiacao.keys.filter { it in raizes }
        assertTrue(
            "`procurarLugar` aparece em ${fiacao.keys}, e nenhum desses arquivos " +
                "constrói o ClaryonIntentExecutor (que é construído em $raizes). " +
                "Chamador em arquivo que ninguém instancia é código morto com cara " +
                "de fiação.",
            ligadoNaRaiz.isNotEmpty(),
        )
    }

    /**
     * **As quatro peças da cascata têm construtor em produção.**
     *
     * Uma por uma, porque uma ligada e três órfãs continua sendo capacidade que não
     * acontece — e a mensagem de falha precisa dizer QUAL faltou.
     */
    @Test
    fun asPecasDaCascataTemConstrutorEmProducao() {
        val exigidas = mapOf(
            // A fonte externa de verdade, construída (e não só declarada).
            "ConsultaGeoespacial(" to setOf("ConsultaGeoespacial.kt"),
            // A costura que traduz categoria em consulta.
            "LugarPelaRede(" to setOf("LugarPelaRede.kt"),
            // A higiene, que é o portão do §5. Sem ela, a costura mandaria o quê?
            "ConsultaHigienizada.de(" to setOf("HigieneDaConsulta.kt"),
            // Os dois registros, separados por decisão 5 — e os dois preenchidos.
            "RegistroDeUso.de(" to setOf("RegistroDeUso.kt"),
            "RegistroDeAuditoria.de(" to setOf("RegistroDeAuditoria.kt"),
        )
        val orfas = exigidas.filter { (simbolo, definidoEm) ->
            arquivosQueContem(simbolo, exceto = definidoEm).isEmpty()
        }.keys
        assertEquals(
            "estas peças da consulta externa não são construídas por nenhum arquivo " +
                "de src/main além do que as define. Classe testada sem chamador é " +
                "ESCRITA, não construída.",
            emptySet<String>(),
            orfas,
        )
    }

    /**
     * **A tradução `CategoriaDeLugar → LugarProcurado` existe num lugar SÓ.**
     *
     * Os dois enums são espelhados porque `core-agent` e `core-net` não se enxergam,
     * e a regra do projeto para espelhamento é que quem costura é `app`, num arquivo
     * auditável de olho. Uma segunda tradução em outro arquivo é como as duas
     * metades divergem sem ninguém perceber.
     */
    @Test
    fun aTraducaoEntreOsEnumsEspelhadosViveNumArquivoSo() {
        val tradutores = arquivosQueContem("LugarProcurado.")
        assertEquals(
            "a tradução entre CategoriaDeLugar e LugarProcurado aparece em mais de " +
                "um arquivo de produção: ${tradutores.keys}",
            setOf("LugarPelaRede.kt"),
            tradutores.keys,
        )
    }
}
