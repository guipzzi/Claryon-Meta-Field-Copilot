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
            // **`/cpp/`, e não `/cpp/llama/`.** O filtro estreito deixava passar os
            // exemplos Kotlin vendorizados do whisper.cpp — e um deles se chama
            // `MainActivity.kt`. Como [arquivosQueContem] indexa por NOME de
            // arquivo, o exemplo da ARM **sobrescrevia a raiz de composição deste
            // aplicativo** no mapa, e toda fiação ligada em `MainActivity.kt` era
            // invisível a esta varredura. Uma trava do §6 cega justamente no
            // arquivo que liga tudo é pior que trava nenhuma: ela dá o verde.
            .filterNot { it.path.contains("/cpp/") }
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

    /**
     * **Nome de arquivo repetido apaga fiação em silêncio.**
     *
     * [arquivosQueContem] indexa por `File.name`, e `associate` fica com a ÚLTIMA
     * entrada de cada chave. Enquanto o filtro de vendorizados era `/cpp/llama/`, o
     * `MainActivity.kt` dos exemplos do whisper.cpp sobrescrevia o
     * `MainActivity.kt` **deste** aplicativo — e a raiz de composição, que é onde
     * quase toda fiação mora, simplesmente não existia para esta varredura. O
     * sintoma é o pior possível: nenhum erro, só um verde a menos.
     *
     * Esta trava fecha a porta em vez de confiar no filtro: se dois arquivos de
     * produção passarem a dividir um nome, ela falha nomeando os dois.
     */
    @Test
    fun nenhumNomeDeArquivoSeRepeteNaProducao() {
        val repetidos = fontesDeProducao()
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .mapValues { (_, arquivos) -> arquivos.map { it.path } }
        assertEquals(
            "Dois arquivos de produção dividem o mesmo nome. A varredura desta " +
                "classe indexa por nome, então um deles some do mapa e a fiação que " +
                "ele contém vira invisível — sem erro, sem aviso. Ou renomeie, ou " +
                "troque a chave do índice por caminho relativo:\n$repetidos",
            emptyMap<String, List<String>>(),
            repetidos,
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

    // ── O outro lado do fio: quem LÊ o que a cascata escreve ──────────────────

    /**
     * **A auditoria tem leitor, e ele está na raiz de composição.**
     *
     * Os testes acima guardam a metade que ESCREVE. Esta guarda a que LÊ, e a
     * distinção não é acadêmica: `DiarioDaConsultaExterna.auditar` tinha chamador
     * desde que a cascata foi ligada, e `DO_PROCESSO.auditoria` tinha **zero**. A
     * `specs/consulta-externa.spec.md` §4 promete que a procedência *"fica
     * disponível na tela"*, e a mesma spec listava a tela sob *"NÃO construído, e é
     * preciso dizer"*. Um `StateFlow` publicado que ninguém coleta é o §6 inteiro:
     * escrito, não construído — só que desta vez o defeito estava no consumidor.
     *
     * Exigir que o leitor viva no arquivo que constrói a tela amarra "tem chamador"
     * a "alcançável em runtime". Uma linha `DO_PROCESSO.auditoria` num arquivo que
     * ninguém compõe seria leitor no `grep` e nada na tela.
     */
    @Test
    fun aAuditoriaTemLeitorNaRaizDeComposicao() {
        val leitores = arquivosQueContem(
            "DO_PROCESSO.auditoria",
            exceto = setOf("DiarioDaConsultaExterna.kt"),
        )
        assertTrue(
            "`DiarioDaConsultaExterna.DO_PROCESSO.auditoria` não tem leitor em " +
                "src/main. `LugarPelaRede` escreve nele a cada resposta do Overpass e " +
                "ninguém o observa — o registro do §4 vive 50 entradas em RAM e morre " +
                "com o processo, sem nunca ter chegado a olho humano.",
            leitores.isNotEmpty(),
        )

        val raizes = arquivosQueContem("TelaDePerfil(", exceto = setOf("TelaDePerfil.kt")).keys
        assertTrue(
            "`DO_PROCESSO.auditoria` é lido em ${leitores.keys}, e nenhum desses " +
                "arquivos compõe a `TelaDePerfil` (composta em $raizes). Leitor em " +
                "arquivo que ninguém compõe é código morto com cara de fiação.",
            leitores.keys.any { it in raizes },
        )
    }

    /**
     * **A seção existe na tela, e recebe o parâmetro por fora.**
     *
     * Sem isto, [aAuditoriaTemLeitorNaRaizDeComposicao] passaria com um
     * `collectAsState()` colhido e jogado fora — a variável existiria, o `grep`
     * acharia, e a tela continuaria sem a seção. É a pergunta 3 do §6: se o teste
     * passaria com o defeito de volta, ele não testa o defeito.
     */
    @Test
    fun aTelaDePerfilRecebeEDesenhaAAuditoria() {
        val recebe = arquivosQueContem("auditoriaExterna", exceto = emptySet())
        assertTrue(
            "`auditoriaExterna` tem de aparecer TANTO em TelaDePerfil.kt (o parâmetro " +
                "e a seção) QUANTO na raiz que a compõe (o argumento). Achei: " +
                "${recebe.keys}",
            "TelaDePerfil.kt" in recebe && recebe.keys.size >= 2,
        )
        assertTrue(
            "TelaDePerfil.kt cita `auditoriaExterna` mas não desenha os três campos " +
                "que o §4 da spec manda mostrar — serviço, trecho e carimbo. " +
                "Parâmetro recebido e não desenhado é a mesma capacidade morta com " +
                "um passo a mais.",
            arquivosQueContem("r.servico").keys.contains("TelaDePerfil.kt") &&
                arquivosQueContem("r.trecho").keys.contains("TelaDePerfil.kt") &&
                arquivosQueContem("r.carimboMillis").keys.contains("TelaDePerfil.kt"),
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
