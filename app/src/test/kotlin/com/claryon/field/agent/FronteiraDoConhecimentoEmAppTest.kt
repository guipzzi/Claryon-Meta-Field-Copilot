package com.claryon.field.agent

import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A fronteira do conhecimento provada AQUI, onde os dois lados se encontram.**
 *
 * ## Por que este teste existe se `FronteiraDoConhecimentoTest` já existe
 *
 * Aquele prova que `core-knowledge` não tem `core-agent` no classpath — ou seja,
 * que uma linha *daquele módulo* não consegue nomear `Intent` nem `IntentExecutor`
 * nem por engano. É garantia forte e continua valendo.
 *
 * Só que ela é **necessária e não suficiente**, e o `ROADMAP.md` diz isso com todas
 * as letras no item 5 da Etapa A: *"`app` importa os dois e é lá que a `String` do
 * LLM e o executor se encontram. A garantia tem de ser um teste em `app`, do mesmo
 * jeito que a garantia de posição é do servidor e não do cliente."*
 *
 * A analogia com o servidor é exata e vale repetir: as migrações `0006`/`0009`/`0010`
 * não confiam no cliente para não pedir a coordenada de terceiro — o servidor
 * simplesmente não sabe devolvê-la. Aqui é o mesmo raciocínio aplicado a texto:
 * não basta o módulo de conhecimento ser incapaz de agir; é preciso que o texto que
 * ele devolve **não consiga virar ação** depois de atravessar a fronteira.
 *
 * ## O que ESTE teste prova, e que o outro não poderia
 *
 * O caminho de ação deste produto tem uma porta única: `DeterministicIntentRouter`.
 * Toda `String` que vira `Intent` passa por ela, e ela é determinística — regex,
 * não modelo. A pergunta que sobra é empírica, não arquitetural:
 *
 * > **existe texto de norma que, entregue ao roteador, vira ação?**
 *
 * Argumento não responde isso. Medição responde. Então o corpus inteiro —
 * **1817 artigos de lei federal e de trânsito**, o material exato que a Etapa A
 * recupera e que a Etapa B vai reescrever — entra no roteador como entrada
 * adversarial, e nenhum artigo pode sair como ação.
 *
 * É o mesmo padrão que já pegou defeito neste projeto duas vezes: em
 * `GrafiasDaAtivacaoTest`, montar a frase inteira revelou que *"clarim, guarnição 3
 * na escuta"* abria canal, contra o meu argumento de que "ninguém diz isso". O
 * argumento estava certo sobre gente e errado sobre o portão. Aqui o risco é maior,
 * porque a Etapa B vai colocar texto **gerado** nesse caminho, e texto gerado não
 * tem a decência de ser improvável.
 *
 * ## Por que ler o corpus do disco em vez de fabricar exemplos
 *
 * Exemplo que eu escrevo é exemplo que eu já imaginei — e o defeito mora no que não
 * imaginei. O CP tem artigos que dizem literalmente "prestar socorro", o CTB tem
 * "remoção do veículo", o CPP tem "prisão em flagrante". São as frases que um
 * roteador de comando operacional tem mais chance de confundir com ordem, e nenhuma
 * delas viria de uma lista minha.
 *
 * O teste **falha se o corpus sumir**, em vez de passar vazio: varredura que não
 * acha nada também "não acha ação", e aí não prova coisa alguma. É o controle
 * positivo que o teste irmão em `core-knowledge` também exige de si.
 */
class FronteiraDoConhecimentoEmAppTest {

    /** Piso de material. Abaixo disto o corpus foi truncado e o teste não vale. */
    private val minimoDeTrechos = 1_000

    /**
     * O fonte sem comentário — porque **falar sobre** um símbolo não é **usá-lo**.
     *
     * Achado em 21/08, ao ligar a Etapa A: a varredura reprovou `ConsultaDeNorma.kt`
     * e `CopilotoDoAgente.kt`, e os dois estavam **certos**. O que casou foi o KDoc,
     * que explica a regra nomeando o que a regra proíbe: *"não sabe o que é `Intent`,
     * `ActionOutcome` ou `IntentExecutor`"*. O código não menciona nenhum deles.
     *
     * É exatamente a falha que este teste mede no roteador, virada contra o próprio
     * teste: **varredura de texto não distingue usar de comentar.** Um teste que
     * reprova a documentação da regra ensina a apagar a documentação — e a próxima
     * pessoa escreve o violador de verdade sem KDoc nenhum, passando limpo.
     *
     * Abrir exceção nominal para os dois arquivos era a outra saída, e a pior: lista
     * de exceção é onde a próxima entra sem ninguém notar.
     *
     * **Limite honesto:** isto tira `//`, `/* */` e KDoc, e não trata `//` dentro de
     * literal de string. Um arquivo com `val s = "http://x"` perderia o resto da
     * linha na varredura. Para o que este teste procura — `import` e uso de tipo —
     * o efeito é nenhum, e o custo de um parser de Kotlin aqui não se paga.
     */
    private fun semComentarios(fonte: String): String =
        fonte.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

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
     * Os textos do corpus, lidos do JSONL por varredura, **não por regex**.
     *
     * A primeira versão usava `"((?:[^"\\]|\\.)*)"` e morreu de `StackOverflowError`
     * dentro do `java.util.regex.Pattern`: alternância com quantificador sobre texto
     * de artigo longo é backtracking catastrófico, e artigo de lei é exatamente o
     * caso longo. Vale registrar porque o defeito estava no INSTRUMENTO de medida, e
     * instrumento que estoura em silêncio derrubaria o teste sem que ninguém
     * suspeitasse do corpus.
     *
     * A varredura anda caractere a caractere e respeita a escapada — linear, sem
     * pilha, e sem arrastar uma biblioteca de JSON para dentro de `app` por causa de
     * um teste.
     */
    private fun textosDoCorpus(): List<Pair<String, String>> {
        val jsonl = raizDoRepositorio().resolve("corpus/trechos.jsonl")
        assertTrue(
            "corpus/trechos.jsonl não existe. Sem corpus este teste não prova nada, " +
                "então ele falha em vez de passar vazio.",
            jsonl.isFile,
        )
        return jsonl.readLines().mapNotNull { linha ->
            val t = campo(linha, "texto") ?: return@mapNotNull null
            (campo(linha, "citacao") ?: "(sem citação)") to t
        }
    }

    /**
     * Valor de um campo string do JSON de uma linha, com escapadas desfeitas.
     *
     * Tolera espaço entre o nome, os dois-pontos e a abertura das aspas. A primeira
     * versão exigia `"texto":"` colado e o gerador escreve `"texto": "` — resultado:
     * **zero trechos lidos**. O teste não passou por isso, ele FALHOU, porque o piso
     * de material é asserção e não comentário. É o controle positivo pagando por si
     * no primeiro uso: sem ele eu teria um "nenhuma norma vira ação" verdíssimo,
     * calculado sobre lista vazia.
     */
    private fun campo(linha: String, nome: String): String? {
        val chave = "\"$nome\""
        var i = linha.indexOf(chave).takeIf { it >= 0 }?.plus(chave.length) ?: return null
        while (i < linha.length && linha[i].isWhitespace()) i++
        if (i >= linha.length || linha[i] != ':') return null
        i++
        while (i < linha.length && linha[i].isWhitespace()) i++
        if (i >= linha.length || linha[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < linha.length) {
            when (val c = linha[i]) {
                '\\' -> {
                    if (i + 1 >= linha.length) return sb.toString()
                    when (val e = linha[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 5 < linha.length) {
                                sb.append(linha.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                }
                '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * **A fronteira é estrutural, e este teste mede o tamanho do que ela segura.**
     *
     * Eu escrevi este teste esperando `0`. Medido: **252 de 1817 artigos de lei
     * viram ação** ao passar pelo `DeterministicIntentRouter` — 13,9%. `Art. 20 do
     * CTB` vira `Emergencia`; `Art. 16`, `PedirApoio`; `Art. 19`, `ConsultarPlaca`.
     *
     * A leitura preguiçosa seria "o roteador está quebrado". Ele não está: ele casa
     * palavra-chave em fala livre, e é assim que reconhece comando dito por gente
     * apressada dentro de uma viatura. Um roteador que recusasse texto de norma
     * recusaria também *"preciso de apoio na via de trânsito rápido"*. **A
     * permissividade é o que faz o produto funcionar**, e apertá-la para satisfazer
     * este teste trocaria uma exposição por uma falha de recall.
     *
     * Então o que este número prova é outra coisa, e mais útil: **a fronteira de
     * módulo não é decorativa.** Se um dia alguém ligar o texto recuperado ao
     * roteador — "só para testar" —, 13,9% do corpus vira comando no aparelho de um
     * agente em serviço. Na Etapa B o risco cresce, porque texto GERADO não tem a
     * decência de ser improvável.
     *
     * Por isso a asserção é `> 0` e não `== 252`. Travar 252 seria travar o corpus e
     * o léxico do roteador juntos, e o teste passaria a falhar por manutenção em vez
     * de por defeito. O que precisa continuar verdadeiro é: **enquanto isto for
     * maior que zero, a separação de módulos está segurando peso real.** Se um dia
     * der zero, ótimo — e aí este teste tem de ser relido, não apagado.
     */
    @Test
    fun aFronteiraSeguraPesoReal_medidoNoCorpusInteiro() {
        val corpus = textosDoCorpus()
        assertTrue(
            "Corpus com ${corpus.size} trechos, abaixo do piso de $minimoDeTrechos. " +
                "Varredura que quase não varre não é prova.",
            corpus.size >= minimoDeTrechos,
        )

        val router = DeterministicIntentRouter()
        val viraramAcao = corpus.mapNotNull { (citacao, texto) ->
            when (val intent = router.route(texto)) {
                is Intent.NaoReconhecida -> null
                else -> citacao to intent
            }
        }

        val porTipo = viraramAcao.groupingBy { it.second::class.simpleName }.eachCount()
        println(
            buildString {
                append("FRONTEIRA — texto de norma entregue ao roteador\n")
                append("  ${viraramAcao.size} de ${corpus.size} artigos viram ação ")
                append("(${"%.1f".format(100.0 * viraramAcao.size / corpus.size)}%)\n")
                porTipo.entries.sortedByDescending { it.value }
                    .forEach { (tipo, n) -> append("  %-22s %d\n".format(tipo, n)) }
                append("  exemplos: ")
                append(viraramAcao.take(3).joinToString("; ") {
                    "${it.first}→${it.second::class.simpleName}"
                })
            },
        )

        assertTrue(
            "NENHUM artigo de lei virou ação. Ou o roteador ficou estrito (e aí " +
                "precisa medir recall de comando de novo), ou o corpus/leitura " +
                "quebrou. Nos dois casos este teste precisa ser RELIDO antes de " +
                "aceito — ele foi escrito quando o número era 252 de 1817.",
            viraramAcao.isNotEmpty(),
        )
    }

    /**
     * **O contra-teste da varredura: tirar comentário não pode cegá-la.**
     *
     * `semComentarios` foi acrescentado porque o KDoc que EXPLICA a regra estava
     * reprovando os arquivos que a obedecem. Só que afrouxar uma varredura é a forma
     * mais fácil de matar um teste sem que ninguém perceba: se o filtro comesse
     * demais, `nenhumArquivoDeProducaoJuntaConhecimentoEExecutor` passaria a aprovar
     * tudo, inclusive o violador de verdade — e continuaria verde para sempre.
     *
     * Então: um violador **sintético**, com o import e o uso em CÓDIGO, tem de ser
     * pego. E um arquivo que só fala dos dois em comentário tem de passar. As duas
     * asserções juntas prendem o filtro pelos dois lados; uma sozinha não prende.
     */
    @Test
    fun aVarreduraAINDAPegaViolador_comOFiltroDeComentarioLigado() {
        val violador = """
            package com.claryon.field.exemplo
            import com.claryon.knowledge.Trecho
            import com.claryon.agent.ActionOutcome
            fun perigoso(t: Trecho): ActionOutcome = TODO()
        """.trimIndent()

        val inocente = """
            package com.claryon.field.exemplo
            /** Este arquivo NÃO usa com.claryon.knowledge nem ActionOutcome. */
            // e também não usa IntentExecutor — só fala deles aqui.
            fun inofensivo() = Unit
        """.trimIndent()

        fun junta(fonte: String): Boolean {
            val t = semComentarios(fonte)
            return t.contains("com.claryon.knowledge") &&
                (t.contains("IntentExecutor") || t.contains("ActionOutcome"))
        }

        assertTrue(
            "O filtro de comentário cegou a varredura: um arquivo que IMPORTA o " +
                "conhecimento e devolve ActionOutcome passou. Enquanto isto for " +
                "verdade, o teste da fronteira aprova qualquer coisa.",
            junta(violador),
        )
        assertTrue(
            "Um arquivo que só MENCIONA os símbolos em comentário foi reprovado — " +
                "é o defeito que o filtro existe para consertar, e ele voltou.",
            !junta(inocente),
        )
    }

    /**
     * **O segundo estágio da ativação NÃO barra — ele só tira o prefixo.**
     *
     * Achado de 21/08, e ele contradiz o KDoc de `PalavraDeAtivacaoNaFala`, que diz:
     * *"o texto tem de começar pela palavra de ativação. Se não começa, o estágio 1
     * despertou por engano — e o comando não roda."*
     *
     * O comando roda. `DeterministicIntentRouter.kt:27` faz:
     *
     * ```kotlin
     * val texto = normalizar(if (semGatilho.confirmada) semGatilho.resto else transcricao)
     * ```
     *
     * Sem confirmação, ele roteia a transcrição INTEIRA. E `confirmada` não é
     * consultada em nenhum outro lugar de `src/main` no produto todo — `processar()`
     * chama `router.route(text)` e executa o resultado, sem porta.
     *
     * A exposição que isso abre é concreta: o earcon dispara falso **0,99 vez por
     * hora** (medido, `ESTADO.md`), o ciclo abre, e o que for dito em seguida é
     * roteado e EXECUTADO mesmo sem gatilho nenhum na frente.
     *
     * **Este teste não conserta e não deve consertar.** Fechar a porta é decisão
     * humana com troca medida dos dois lados: o recall do gatilho hoje é 3/4
     * locutores, e a voz que TREINOU o detector já virou "Blerium" na transcrição —
     * barrar por prefixo faria comando legítimo virar silêncio. O §7 do `CLAUDE.md`
     * reserva isso para gente, com diff de spec antes do diff de código.
     *
     * O que o teste faz é **impedir que o achado se perca**: ele fixa o
     * comportamento REAL, para que quem fechar a porta um dia veja este teste falhar
     * e leia o porquê antes de mexer.
     */
    @Test
    fun oSegundoEstagioNaoBarra_ainda_ficaFixadoAteAlguemDecidir() {
        val router = DeterministicIntentRouter()
        val semGatilho = "preciso de apoio"

        assertTrue(
            "'$semGatilho' não tem a palavra de ativação e MESMO ASSIM foi roteado " +
                "como ação — este é o comportamento de hoje, fixado aqui de " +
                "propósito. Se esta asserção passou a falhar, alguém fechou a porta " +
                "do segundo estágio: leia o KDoc deste teste, confira o recall do " +
                "gatilho, e então atualize aqui.",
            router.route(semGatilho) !is Intent.NaoReconhecida,
        )
    }

    /**
     * **O contra-teste: esta varredura PEGARIA o defeito se ele existisse.**
     *
     * Sem isto, `nenhumTextoDeNormaViraAcao` passaria também se o roteador
     * devolvesse `NaoReconhecida` para tudo — inclusive para comando de verdade. Um
     * roteador quebrado satisfaz "nenhuma norma vira ação" perfeitamente.
     *
     * Então: comando legítimo TEM de virar ação pelo mesmo roteador e na mesma
     * chamada. Se este teste falhar, o outro perdeu o sentido.
     */
    @Test
    fun oMesmoRoteadorAINDAReconheceComandoDeVerdade() {
        val router = DeterministicIntentRouter()
        val comandos = listOf(
            "preciso de apoio",
            "iniciar gravação",
            "emergência",
        )
        val reconhecidos = comandos.filter { router.route(it) !is Intent.NaoReconhecida }
        assertEquals(
            "O roteador não reconheceu ${comandos.size - reconhecidos.size} de " +
                "${comandos.size} comandos legítimos. Enquanto isso for verdade, " +
                "'nenhuma norma vira ação' passa por vacuidade e não prova nada.",
            comandos.size,
            reconhecidos.size,
        )
    }

    /**
     * **`app` não pode dar ao conhecimento um atalho para o executor.**
     *
     * A prova de classpath vive em `core-knowledge` e olha para dentro daquele
     * módulo. Esta olha para o lado de cá: nenhuma classe de produção de `app` pode
     * mencionar `com.claryon.knowledge` **e** `IntentExecutor` no mesmo arquivo.
     *
     * Não é sofisticado, e é de propósito: o que quebra a regra não é um esquema
     * elaborado, é alguém injetando o texto recuperado num executor "só para
     * testar". Isso mora no mesmo arquivo, e é exatamente o que esta varredura vê.
     *
     * Quando a Etapa A ganhar seu adaptador em `app`, ele vai aparecer aqui — e a
     * lista de exceções tem de ser escrita à mão, com o motivo, por um humano.
     */
    @Test
    fun nenhumArquivoDeProducaoJuntaConhecimentoEExecutor() {
        val fontes = raizDoRepositorio().resolve("app/src/main/kotlin")
        assertTrue("Fontes de app não encontradas em $fontes", fontes.isDirectory)

        val arquivos = fontes.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "Nenhum .kt varrido em $fontes — varredura vazia não é prova.",
            arquivos.size > 20,
        )

        val infratores = arquivos.filter { f ->
            val texto = semComentarios(f.readText())
            texto.contains("com.claryon.knowledge") &&
                (texto.contains("IntentExecutor") || texto.contains("ActionOutcome"))
        }.map { it.relativeTo(fontes).path }

        assertEquals(
            "Estes arquivos de produção mencionam o conhecimento E o executor no " +
                "mesmo lugar:\n" + infratores.joinToString("\n") { "  $it" } +
                "\n\nSe isto for intencional, a exceção entra nesta lista com o " +
                "motivo escrito — por decisão humana, não por diff que passou.",
            emptyList<String>(),
            infratores,
        )
    }
}
