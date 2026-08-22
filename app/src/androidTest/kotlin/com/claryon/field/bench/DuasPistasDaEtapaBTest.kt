package com.claryon.field.bench

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.field.norma.RedacaoDoCopiloto
import com.claryon.llm.FormulacaoDoPrompt
import com.claryon.llm.GramaticaDaFonte
import com.claryon.llm.GuardaDaRedacao
import com.claryon.llm.OpcoesDeAmostragem
import com.claryon.llm.PedidoDeRedacao
import com.claryon.llm.RecorteDaFonte
import com.claryon.llm.RedatorLlamaCpp
import com.claryon.llm.SelecaoDeTrecho
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **As duas pistas da Etapa B medidas sobre o mesmo banco, no mesmo aparelho, na
 * mesma execução — sem trocar de modelo.**
 *
 * ## O que já estava medido em 22/08, e por que não basta
 *
 * `OrcamentoDaEtapaBNoAparelhoTest` mediu **cinco formulações de prompt** sobre
 * estas mesmas 20 perguntas e produziu a refutação que governa esta sessão:
 * `utilizáveis` fica **entre 1 e 2 em todas as cinco**, e `aprova` anda no sentido
 * **oposto** de `utilizáveis` — porque a régua de lastro premia casamento lexical,
 * e o jeito mais barato de casar com a fonte é copiá-la.
 *
 * Trocar o prompt está, portanto, medido e esgotado. O que sobra são duas pistas
 * que mudam outra coisa:
 *
 *  - **Pista 1 — o orçamento.** O prefill de ~500 tokens custa 1 620 a 2 550 ms
 *    contra um prazo de 2 500, e `llama_decode` chega a devolver `2` **antes de o
 *    prompt entrar**. Os trechos deste banco têm 207 palavras de média e 381 na
 *    cauda: quase todo o prompt é artigo. [RecorteDaFonte] encurta o artigo pela
 *    pergunta; a amostragem gulosa e a instrução em inglês entram no mesmo bloco
 *    porque custam uma string cada.
 *  - **Pista 2 — a extração por gramática.** A tarefa real nunca foi redigir: é
 *    pegar um artigo e produzir uma fala curta. Um modelo generativo inventa
 *    porque foi treinado para continuar. [GramaticaDaFonte] usa
 *    `llama_sampler_init_grammar` (llama.h:1415) para que os únicos tokens
 *    sorteáveis sejam os de um **trecho contíguo da fonte** — e aí alucinação não
 *    é detectada, é **impossível**.
 *
 * ## O critério de UTILIZÁVEL, escrito ANTES de existir saída para ler
 *
 * `utilizáveis` é classificação humana sobre o log, como o
 * `RedacaoDeAbordagemNoAparelhoTest` já registrava: *"meta-comentário"* e
 * *"consequência inventada"* não têm assinatura lexical, e automatizar isso seria
 * inventar precisão. O critério fixado antes desta bancada rodar pela primeira
 * vez é o de 22/08, com uma cláusula a mais (a **e**), que a Pista 2 obriga a
 * tornar explícita porque um recorte contíguo pode mentir por omissão:
 *
 *  - **(a) frase ou sintagma completo** — não termina no meio de uma palavra, nem
 *    numa preposição cujo complemento ficou de fora;
 *  - **(b) tudo o que afirma está no trecho, com o mesmo sentido** — sem inversão
 *    de negação, sem troca de grandeza, sem palavra inexistente;
 *  - **(c) responde o que foi perguntado**, e não outra coisa do mesmo artigo;
 *  - **(d) não fala sobre a tarefa, sobre o texto nem sobre si mesma**;
 *  - **(e) não induz a erro por omissão de condicionante adjacente** — dizer a
 *    pena calando o crime, ou o preceito calando o *"salvo se"* que vinha na
 *    linha seguinte, reprova mesmo estando cada palavra na lei.
 *
 * E `alucinações` é a subclasse de (b): quantas saídas **afirmam** algo que a
 * fonte não diz. Para os braços de gramática ela é verificada por máquina, não
 * por leitura — ver [oQueAGramaticaProduzENecessariamenteTrechoDaFonte].
 *
 * ## Por que os braços são INTERCALADOS, e não rodados em bloco
 *
 * O KDoc de `OrcamentoDaEtapaBNoAparelhoTest` registra o achado que invalidou uma
 * tabela inteira: rodando cinco formulações em bloco, a primeira rendeu 14 textos
 * e a terceira rendeu 3 — **com prompts do mesmo tamanho** —, porque a máquina que
 * hospeda o emulador ficou 30% mais lenta ao longo de vinte minutos e o prefill
 * passou a estourar o prazo sozinho. A tabela mediu a ordem dos blocos.
 *
 * Aqui o laço externo é a **pergunta** e o interno é o **braço**: os oito braços de
 * uma pergunta acontecem em segundos, então a deriva da máquina atinge os oito
 * igualmente em vez de premiar quem rodou primeiro. O braço de controle
 * ([CONTROLE]) repete o braço de hoje no fim de cada rodada e mede o que sobrou de
 * deriva.
 *
 * ## Como pôr as entradas no aparelho
 *
 * ```
 * adb push /tmp/abordagem.tsv /data/local/tmp/abordagem.tsv
 * adb push Llama-3.2-1B-Instruct-Q4_K_M.gguf /data/local/tmp/redator.gguf
 * ```
 *
 * Sem os dois o teste **pula**. Pulado é a resposta honesta; verde seria mentira.
 */
class DuasPistasDaEtapaBTest {

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Tripla(
        val pergunta: String,
        val citacao: String,
        val norma: String,
        val confianca: Double,
        val texto: String,
    )

    /**
     * Um braço da bancada: o que muda em relação ao de hoje, e nada mais.
     *
     * @property gbnfDe recebe o **trecho inteiro** e devolve a GBNF, ou `null`
     *   para geração livre. Recebe o trecho inteiro mesmo quando o prompt leva só
     *   o recorte: a gramática é a jaula, e encolher a jaula junto com o contexto
     *   confundiria duas mudanças numa medição só.
     * @property recortar quando `true`, o `trecho` do pedido é substituído pelo
     *   recorte por [RecorteDaFonte]. Muda o **prompt**, não a jaula.
     */
    private data class Braco(
        val nome: String,
        val formulacao: FormulacaoDoPrompt,
        val opcoes: OpcoesDeAmostragem = OpcoesDeAmostragem(),
        val recortar: Boolean = false,
        val gbnfDe: ((String) -> String?)? = null,
        /**
         * Quando presente, **o modelo não roda**: a resposta sai desta função,
         * que recebe `(trecho, pergunta)`. É o braço de controle sem LLM.
         */
        val semModelo: ((String, String) -> String?)? = null,
    ) {
        val comGramatica: Boolean get() = gbnfDe != null
    }

    private data class Amostra(
        val braco: String,
        val ms: Long,
        val cru: String?,
        val aprovado: Boolean,
        val lastro: Double,
        val contiguo: Boolean,
        val prefillMs: Long,
        val gramaticaUs: Long,
        val amostragemUs: Long,
        val promptTok: Long,
        val gerados: Long,
        val decodeRc: Long,
    ) {
        val comTexto: Boolean get() = !cru.isNullOrBlank()
        val umaLinha: String get() = cru?.replace('\n', ' ')?.trim().orEmpty().ifEmpty { "(vazio)" }

        /** Ver `Amostra.truncado` da bancada irmã: indicador declarado, não veredito. */
        val truncado: Boolean
            get() = !cru.isNullOrBlank() && cru.trim().last() !in FIM_DE_FRASE
    }

    // ------------------------------------------------- 1. o custo de compilar a gramática

    /**
     * **O número que pode matar a Pista 2 mesmo que ela funcione: quanto custa
     * compilar a GBNF, por consulta.**
     *
     * A gramática deriva do trecho recuperado, então ela **muda a cada pergunta** —
     * não há como compilá-la uma vez no boot. Se compilar custar centenas de
     * milissegundos, a pista morre por latência num orçamento que já está
     * estourado, e isso tem de ser dito antes de qualquer tabela de qualidade.
     *
     * Medido isolado da geração de propósito, em `nativoMedirGramatica`: por
     * dentro de uma geração o custo se misturaria com prefill e amostragem, e a
     * atribuição viraria chute.
     *
     * As quatro configurações existem porque o custo tem **duas** dimensões, e elas
     * puxam para lados diferentes:
     *
     *  - **teto de palavras** (7 do `CLAUDE.md` §4, contra 12) multiplica as regras;
     *  - **posições de início** — qualquer palavra contra fronteira de cláusula —
     *    multiplica as *pilhas ativas*, que é o custo do primeiro token, não o de
     *    compilar. As duas colunas saem no log para que não sejam confundidas.
     */
    @Test
    fun oCustoDeCompilarAGramaticaPorConsultaEMedido() = runBlocking {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        assertTrue("TSV truncado: ${doCorpus.size} perguntas", doCorpus.size >= MINIMO_DE_PERGUNTAS)

        val redator = RedatorLlamaCpp(modelo)
        assertTrue("preparar() devolveu false — ver o logcat do llama", redator.preparar())

        val configuracoes = listOf(
            Triple("7-fronteira", 7, GramaticaDaFonte.Inicio.FRONTEIRA_DE_CLAUSULA),
            Triple("12-fronteira", 12, GramaticaDaFonte.Inicio.FRONTEIRA_DE_CLAUSULA),
            Triple("7-qualquer", 7, GramaticaDaFonte.Inicio.QUALQUER_PALAVRA),
            Triple("12-qualquer", 12, GramaticaDaFonte.Inicio.QUALQUER_PALAVRA),
        )

        var houveMedida = false
        for ((nome, teto, inicio) in configuracoes) {
            val us = ArrayList<Long>(doCorpus.size)
            val bytes = ArrayList<Int>(doCorpus.size)
            val regras = ArrayList<Int>(doCorpus.size)
            val inicios = ArrayList<Int>(doCorpus.size)
            for (t in doCorpus) {
                val gbnf = GramaticaDaFonte.de(t.texto, teto, inicio) ?: continue
                bytes += gbnf.toByteArray(Charsets.UTF_8).size
                regras += gbnf.lines().count { it.isNotBlank() }
                inicios += gbnf.lines().first { it.startsWith("t ::=") }.split('|').size
                // Três compilações e a média: uma medição só, em microssegundos,
                // é ruído de escalonador. Não é aquecimento — não há cache aqui,
                // `llama_sampler_init_grammar` parte do texto toda vez.
                val total = redator.medirGramatica(gbnf, repeticoes = 3)
                assertTrue(
                    "A GBNF de \"${t.citacao}\" ($nome, ${bytes.last()} B) NÃO COMPILOU " +
                        "contra o vocabulário. Toda tabela de qualidade da Pista 2 seria " +
                        "sobre geração livre.",
                    total >= 0,
                )
                us += total / 3
                houveMedida = true
            }
            // **Sem `.format` no fim de uma concatenação.** Em Kotlin o `.format`
            // liga no ÚLTIMO literal da cadeia de `+`, não na string inteira: a
            // primeira versão desta linha morreu com
            // `IllegalFormatConversionException: d != java.lang.Double` no
            // aparelho, depois de 1 min 14 s de instalação. Os números saem
            // prontos, por interpolação.
            val p50us = percentil(us, 50)
            val p90us = percentil(us, 90)
            Log.i(
                TAG,
                "pistas: GRAMÁTICA $nome · ${us.size} trechos · compilar " +
                    "p50=${p50us}us p90=${p90us}us max=${us.maxOrNull()}us " +
                    "(= ${p50us / 1000}.${(p50us % 1000) / 100} ms no p50, " +
                    "${p90us / 1000}.${(p90us % 1000) / 100} ms no p90) · " +
                    "GBNF p50=${percentil(bytes.map { it.toLong() }, 50)} B · " +
                    "regras p50=${percentil(regras.map { it.toLong() }, 50)} · " +
                    "inícios p50=${percentil(inicios.map { it.toLong() }, 50)} " +
                    "(é o número de pilhas ativas no 1º token)",
            )
        }
        redator.liberar()
        assertTrue("Nenhuma gramática foi compilada — a tabela acima está vazia", houveMedida)
        Unit
    }

    // --------------------------------------------------------- 2. as duas pistas, medidas

    /**
     * **A tabela que o dono pediu: hoje · pista 1 · pista 2, no prazo de produção.**
     *
     * Prazo de 2 500 ms, `nThreads = 4`, `nCtx = 1024`, `maxTokens = 96` — a
     * configuração literal de [RedatorLlamaCpp]. Trocar qualquer um mediria outro
     * produto.
     *
     * O guarda de produção julga por fora, com a composição de hoje
     * (`trecho + procedência`, **sem** a pergunta). A coluna `aprova` sai no log
     * porque foi pedida, e sai com a advertência de 22/08 colada nela: **ela mede
     * cópia, não utilidade**, e nenhuma decisão deve usá-la sozinha.
     */
    @Test
    fun asDuasPistasSaoMedidasNoPrazoDeProducao() = runBlocking {
        rodar(RedatorLlamaCpp.PRAZO_PADRAO_MS, "PRODUCAO")
    }

    /**
     * **A mesma matriz com o prazo folgado.** Não descreve o produto: descreve o
     * que o modelo **sabe** responder quando a máquina não corta.
     *
     * Existe porque no prazo de produção metade dos braços fica muda, e leitura
     * humana de `utilizáveis` sobre nove textos não distingue *"o modelo não sabe"*
     * de *"o prefill comeu o prazo"*. É o mesmo par de prazos que a bancada irmã
     * usa, pelo mesmo motivo.
     */
    @Test
    fun asDuasPistasSaoMedidasComOPrazoFolgado() = runBlocking {
        rodar(PRAZO_DE_MEDICAO_MS, "FOLGADO")
    }

    private suspend fun rodar(prazo: Int, rotulo: String) {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        assertTrue(
            "menos de $MINIMO_DE_PERGUNTAS perguntas acima do limiar: o TSV chegou " +
                "truncado ao aparelho, e p90 sobre punhado não é p90",
            doCorpus.size >= MINIMO_DE_PERGUNTAS,
        )

        val redator = RedatorLlamaCpp(modelo, prazoMs = prazo)
        assertTrue("preparar() devolveu false — ver o logcat do llama", redator.preparar())
        val guarda = GuardaDaRedacao()

        val porBraco = LinkedHashMap<String, MutableList<Amostra>>()
        for (b in BRACOS) porBraco[b.nome] = ArrayList(doCorpus.size)

        // Laço externo: PERGUNTA. Laço interno: BRAÇO. Ver o KDoc da classe —
        // rodar em bloco já produziu uma tabela que media a ordem dos blocos.
        for ((i, t) in doCorpus.withIndex()) {
            val procedencia = "${t.citacao} — ${t.norma}"
            val fonteDoGuarda = "${t.texto}\n$procedencia"
            for (b in BRACOS) {
                val trechoDoPrompt =
                    if (b.recortar) RecorteDaFonte.para(t.texto, t.pergunta, ORCAMENTO_DE_PALAVRAS)
                    else t.texto
                val pedido = PedidoDeRedacao(
                    pergunta = t.pergunta,
                    trecho = trechoDoPrompt,
                    procedencia = procedencia,
                )
                // A jaula vem SEMPRE do trecho inteiro, mesmo quando o prompt leva
                // só o recorte: encolher as duas juntas confundiria duas mudanças.
                val gbnf = b.gbnfDe?.invoke(t.texto)
                assertTrue(
                    "O braço ${b.nome} pede gramática e ela não foi construída para " +
                        "\"${t.citacao}\". Sem GBNF a geração é livre e a coluna " +
                        "`alucinações` deste braço seria uma mentira.",
                    !b.comGramatica || gbnf != null,
                )

                val t0 = System.currentTimeMillis()
                val cru = if (b.semModelo != null) {
                    b.semModelo.invoke(t.texto, t.pergunta)
                } else {
                    redator.redigirNaBancada(pedido, b.formulacao, b.opcoes, gbnf, prazo)
                }
                val dt = System.currentTimeMillis() - t0
                val m = if (b.semModelo != null) null else redator.ultimasMetricas()
                val vazio = cru.isNullOrBlank()
                val a = Amostra(
                    braco = b.nome,
                    ms = dt,
                    cru = cru,
                    aprovado = !vazio && guarda.aprovar(cru!!, fonteDoGuarda) != null,
                    lastro = if (vazio) 0.0 else guarda.lastro(cru!!, fonteDoGuarda),
                    contiguo = !vazio && GramaticaDaFonte.eTrechoContiguo(cru!!, t.texto),
                    prefillMs = m?.prefillMs ?: -1,
                    gramaticaUs = m?.gramaticaUs ?: -1,
                    amostragemUs = m?.amostragemUs ?: -1,
                    promptTok = m?.promptTok ?: -1,
                    gerados = m?.gerados ?: -1,
                    decodeRc = m?.decodeRc ?: -1,
                )
                porBraco.getValue(b.nome) += a
                Log.i(
                    TAG,
                    "pistas: $rotulo [${i + 1}/${doCorpus.size}] ${b.nome} ${dt}ms " +
                        "prefill=${a.prefillMs}ms tok=${a.promptTok} gerou=${a.gerados} " +
                        "rc=${a.decodeRc} gram=${a.gramaticaUs}us amostra=${a.amostragemUs}us " +
                        "guarda=${if (a.aprovado) "APROVA" else "recusa"} " +
                        "lastro=%.2f".format(a.lastro) +
                        " contiguo=${a.contiguo}" +
                        (if (a.truncado) " TRUNCADO" else "") +
                        " | P=\"${t.pergunta}\" | A=${t.citacao}/${t.norma}" +
                        " | G=\"${a.umaLinha}\"",
                )
            }
        }
        redator.liberar()

        Log.i(
            TAG,
            "pistas: ── TABELA $rotulo · prazo ${prazo} ms · ${doCorpus.size} perguntas · " +
                "braços INTERCALADOS por pergunta ──",
        )
        Log.i(
            TAG,
            "pistas: braço | texto | aprova | contíguo | trunc | p50 | p90 | " +
                "prefill p50 | tok p50 | gram p50 us | amostra p50 us",
        )
        for ((nome, a) in porBraco) tabelar(nome, a)

        conferir(porBraco, rotulo)
    }

    /**
     * As três coisas que, se não valerem, fazem a tabela acima descrever outra
     * coisa. Nenhuma delas é sobre qualidade — são sobre a bancada existir.
     */
    private fun conferir(porBraco: Map<String, List<Amostra>>, rotulo: String) {
        // ── 1. os braços TÊM de diferir ──────────────────────────────────────
        // Sem isto, um parâmetro que não chegasse ao JNI daria linhas idênticas e
        // a tabela pareceria dizer "não adianta mudar" — quando o que ela diria é
        // "o parâmetro está inerte". Já aconteceu neste projeto, com `Grandezas`
        // desligada e a régua reprovando zero de 268.
        val assinaturas = porBraco.values.map { linhas -> linhas.map { it.umaLinha } }.toSet()
        assertTrue(
            "Os ${porBraco.size} braços produziram exatamente os MESMOS textos. Ou " +
                "`formulacao`/`opcoes`/`gbnf` não chegam ao `nativoRedigirComOpcoes`, ou " +
                "nada deles é lido — e nos dois casos a tabela não mede pista nenhuma.",
            assinaturas.size > 1,
        )

        // ── 2. o recorte TEM de encurtar o prompt ────────────────────────────
        // É a Pista 1 inteira. Se o prompt não encolheu, `RecorteDaFonte` não
        // chegou ao pedido e a linha `P1a` é a linha `A0` com outro nome.
        val tokensDeHoje = porBraco.getValue(HOJE).mapNotNull { it.promptTok.takeIf { t -> t > 0 } }
        val tokensDoRecorte =
            porBraco.getValue(RECORTE).mapNotNull { it.promptTok.takeIf { t -> t > 0 } }
        if (tokensDeHoje.isNotEmpty() && tokensDoRecorte.isNotEmpty()) {
            assertTrue(
                "O recorte não encurtou o prompt: p50 de ${percentil(tokensDoRecorte, 50)} " +
                    "tokens contra ${percentil(tokensDeHoje, 50)} de hoje. `RecorteDaFonte` " +
                    "não chegou ao pedido, e a Pista 1 não foi medida.",
                percentil(tokensDoRecorte, 50) < percentil(tokensDeHoje, 50),
            )
        }

        // ── 3. o braço sem LLM é contíguo por construção, e é barato ─────────
        // As duas coisas que ele afirma. Se qualquer uma falhar, ele deixa de ser
        // um controle honesto da Pista 2 e a comparação "com modelo × sem modelo"
        // não significa nada.
        val semLlm = porBraco.getValue(SEM_LLM)
        val naoContiguos = semLlm.filter { it.comTexto && !it.contiguo }
        assertTrue(
            "O baseline sem LLM produziu ${naoContiguos.size} saídas que não são trecho " +
                "contíguo da fonte: ${naoContiguos.map { it.umaLinha }}. Ele escolhe de uma " +
                "lista de trechos; se o resultado não está na lista, a lista está errada.",
            naoContiguos.isEmpty(),
        )
        val msSemLlm = percentil(semLlm.map { it.ms }, 90)
        assertTrue(
            "O baseline sem LLM levou ${msSemLlm}ms no p90. Ele é uma varredura de " +
                "posições × comprimentos sobre algumas centenas de palavras — se custa " +
                "isso, ou a fonte é enorme ou a busca está quadrática onde não devia.",
            msSemLlm <= TETO_DO_BASELINE_MS,
        )

        // ── 4. a deriva da máquina, medida e não suposta ──────────────────────
        val textosDeHoje = porBraco.getValue(HOJE).count { it.comTexto }
        val textosDoControle = porBraco.getValue(CONTROLE).count { it.comTexto }
        Log.i(
            TAG,
            "pistas: CONTROLE $rotulo — o MESMO braço rendeu $textosDeHoje textos como " +
                "primeiro da rodada e $textosDoControle como último. A intercalação existe " +
                "para que esta diferença seja pequena; se for grande, a máquina derivou " +
                "DENTRO de cada pergunta e nenhuma coluna é atribuível ao braço.",
        )
        assertTrue(
            "Deriva grande demais entre o braço de hoje ($textosDeHoje textos) e sua " +
                "repetição no fim de cada rodada ($textosDoControle). A bancada não foi " +
                "reprodutível nesta execução — repita com a máquina ociosa.",
            kotlin.math.abs(textosDeHoje - textosDoControle) <= DERIVA_TOLERADA,
        )
    }

    /**
     * **A afirmação forte da Pista 2, verificada por máquina em vez de lida.**
     *
     * *"Alucinação impossível por construção"* é exatamente o tipo de frase que
     * este projeto já viu nascer morta: bastaria a GBNF não compilar, a raiz estar
     * errada ou o sampler não entrar na cadeia para a geração ser livre e a coluna
     * `alucinações` sair `0` sobre texto irrestrito.
     *
     * Aqui cada saída dos braços de gramática é conferida contra a fonte por
     * [GramaticaDaFonte.eTrechoContiguo] — que recusa recombinação, supressão do
     * meio e palavra enxertada, os três modos que o `GuardaDaRedacao` aprova. O
     * **contra-teste** está no mesmo teste: o braço de hoje, sem gramática, é
     * medido pela mesma régua e **não** pode ser 100% contíguo, senão a régua está
     * aceitando qualquer coisa.
     */
    @Test
    fun oQueAGramaticaProduzENecessariamenteTrechoDaFonte() = runBlocking {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        assertTrue("TSV truncado", doCorpus.size >= MINIMO_DE_PERGUNTAS)

        val redator = RedatorLlamaCpp(modelo, prazoMs = PRAZO_DE_MEDICAO_MS)
        assertTrue("preparar() devolveu false", redator.preparar())

        var comGramatica = 0
        var contiguosComGramatica = 0
        var livres = 0
        var contiguosLivres = 0

        for (t in doCorpus) {
            val procedencia = "${t.citacao} — ${t.norma}"
            val pedido = PedidoDeRedacao(t.pergunta, t.texto, procedencia)
            val gbnf = GramaticaDaFonte.de(t.texto, TETO_DE_PALAVRAS)!!

            val preso = redator.redigirNaBancada(
                pedido, FormulacaoDoPrompt.EXTRACAO_COM_TRECHO, OpcoesDeAmostragem(),
                gbnf, PRAZO_DE_MEDICAO_MS,
            )
            if (!preso.isNullOrBlank()) {
                comGramatica++
                val ok = GramaticaDaFonte.eTrechoContiguo(preso, t.texto)
                if (ok) contiguosComGramatica++
                assertTrue(
                    "A gramática deixou passar texto que NÃO é trecho contíguo de " +
                        "${t.citacao}: \"${preso.replace('\n', ' ').trim()}\". Ou o sampler " +
                        "não entrou na cadeia, ou a raiz está errada — e a coluna " +
                        "`alucinações` da Pista 2 seria falsa.",
                    ok,
                )
                val palavras = preso.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
                assertTrue(
                    "A gramática de teto $TETO_DE_PALAVRAS devolveu $palavras palavras: " +
                        "\"$preso\". O teto está na profundidade das regras; se ele não " +
                        "vale, a fala não cabe no §4 e a truncagem volta.",
                    palavras <= TETO_DE_PALAVRAS,
                )
            }

            val solto = redator.redigirNaBancada(
                pedido, FormulacaoDoPrompt.PRODUCAO, OpcoesDeAmostragem(),
                null, PRAZO_DE_MEDICAO_MS,
            )
            if (!solto.isNullOrBlank()) {
                livres++
                if (GramaticaDaFonte.eTrechoContiguo(solto, t.texto)) contiguosLivres++
            }
        }
        redator.liberar()

        Log.i(
            TAG,
            "pistas: CONTIGUIDADE · com gramática $contiguosComGramatica/$comGramatica · " +
                "geração livre $contiguosLivres/$livres",
        )
        assertTrue(
            "Nenhuma geração com gramática produziu texto: não há o que verificar, e a " +
                "afirmação da Pista 2 fica sem lastro.",
            comGramatica > 0,
        )
        // Contra-teste: se a geração LIVRE também fosse toda contígua, a régua
        // estaria aceitando qualquer coisa e o `assertTrue` de cima seria vácuo.
        assertTrue(
            "A geração livre produziu $contiguosLivres de $livres saídas contíguas — todas. " +
                "Ou o banco é degenerado, ou `eTrechoContiguo` aceita qualquer coisa, e a " +
                "verificação da gramática não prova nada.",
            livres == 0 || contiguosLivres < livres,
        )
        Unit
    }

    // ------------------------------------------------------------------- relatório

    private fun tabelar(nome: String, a: List<Amostra>) {
        val ms = a.map { it.ms }
        val prefill = a.mapNotNull { it.prefillMs.takeIf { v -> v >= 0 } }
        val tok = a.mapNotNull { it.promptTok.takeIf { v -> v >= 0 } }
        val gram = a.mapNotNull { it.gramaticaUs.takeIf { v -> v >= 0 } }
        val amostra = a.mapNotNull { it.amostragemUs.takeIf { v -> v >= 0 } }
        Log.i(
            TAG,
            "pistas: $nome | ${a.count { it.comTexto }} | ${a.count { it.aprovado }} | " +
                "${a.count { it.comTexto && it.contiguo }} | ${a.count { it.truncado }} | " +
                "${percentil(ms, 50)} | ${percentil(ms, 90)} | ${percentil(prefill, 50)} | " +
                "${percentil(tok, 50)} | ${percentil(gram, 50)} | ${percentil(amostra, 50)} " +
                "· abortados no prefill=${a.count { it.decodeRc == 2L }}",
        )
    }

    private fun percentil(valores: List<Long>, p: Int): Long {
        if (valores.isEmpty()) return -1
        val ordenado = valores.sorted()
        val idx = Math.ceil(p / 100.0 * ordenado.size).toInt().coerceIn(1, ordenado.size)
        return ordenado[idx - 1]
    }

    private fun triplas(tsv: File): List<Tripla> = tsv.readLines().mapNotNull { linha ->
        val c = linha.split('\t')
        if (c.size < 6 || c[0] != "TRIPLA" || c[5].isBlank()) {
            null
        } else {
            Tripla(c[1], c[2], c[3], c[4].replace(',', '.').toDoubleOrNull() ?: 0.0, c[5])
        }
    }

    /**
     * O GGUF no `filesDir`, copiado de `/data/local/tmp` se ainda não estiver lá
     * **ou se estiver pela metade**.
     *
     * ## Por que a comparação de tamanho existe
     *
     * Uma execução desta bancada em 22/08 morreu com `Process crashed` e o log
     * mostrou `modelo=617611264 B` — contra os 807 694 464 B do arquivo. O
     * `adb shell run-as` respondeu `unknown package`: **outro agente reinstalou o
     * pacote no meio da cópia**, que é exatamente o `Killing … due to
     * deletePackageX` que o KDoc da bancada irmã registra. Sobrou um GGUF truncado,
     * e um GGUF truncado não devolve `false` no `preparar()` — ele derruba o
     * processo dentro do `mmap`.
     *
     * `isFile && length() != 0` não vê isso. Comparar com o tamanho da origem vê.
     */
    private fun modeloNoAparelho(): File {
        val destino = RedacaoDoCopiloto.arquivoDoModelo(contexto)
        val origem = File(GGUF_NO_TMP)
        if (!origem.canRead()) return destino
        if (destino.isFile && destino.length() == origem.length()) return destino
        if (destino.isFile) {
            Log.w(
                TAG,
                "pistas: modelo em ${destino.path} tem ${destino.length()} B contra " +
                    "${origem.length()} B da origem — cópia interrompida. Recopiando.",
            )
            destino.delete()
        }
        origem.inputStream().use { e -> destino.outputStream().use { e.copyTo(it, 1 shl 20) } }
        return destino
    }

    companion object {
        private const val TAG = "ClaryonField"
        private const val GGUF_NO_TMP = "/data/local/tmp/redator.gguf"
        private const val TSV_NO_TMP = "/data/local/tmp/abordagem.tsv"

        /** `BaseDeConhecimentoLexical.LIMIAR_MEDIDO`, repetido porque `app` não tem o módulo. */
        private const val LIMIAR_DE_PRODUCAO = 0.30
        private const val MINIMO_DE_PERGUNTAS = 10
        private const val PRAZO_DE_MEDICAO_MS = 20_000

        /**
         * **12, e não os 7 do `CLAUDE.md` §4.** A pena de um artigo do CP —
         * *"reclusão, de três a seis anos, e multa"* — tem 8 palavras, e cortá-la
         * em 7 entrega meia pena a quem não tem tela para conferir.
         *
         * O teto de 7 é medido à parte, na tabela de custo de compilação: se a
         * Pista 2 for aprovada, a diferença entre 7 e 12 vira a mesma decisão
         * humana que a spec já registra sobre o §4, e não uma escolha feita aqui.
         */
        private const val TETO_DE_PALAVRAS = 12

        /**
         * **70 palavras contra as 207 de média do banco.** É onde o prefill cai de
         * ~500 para ~200 tokens — abaixo disso o recorte começa a deixar de fora o
         * inciso que responde, e aí a Pista 1 compraria latência com resposta
         * errada.
         */
        private const val ORCAMENTO_DE_PALAVRAS = 70

        /**
         * Quantos textos de diferença entre o braço de hoje e sua repetição ainda
         * contam como a mesma execução. Três em vinte é 15% — acima disso a deriva
         * da máquina compete com o efeito medido.
         */
        private const val DERIVA_TOLERADA = 3

        /**
         * O teto do baseline sem LLM. Generoso de propósito: ele não está sendo
         * otimizado, está sendo provado **barato o bastante para que a comparação
         * de latência com a Pista 2 seja sobre ordens de grandeza**, não sobre
         * décimos.
         */
        private const val TETO_DO_BASELINE_MS = 100L

        private val FIM_DE_FRASE = charArrayOf('.', '!', '?', ':', '”', '"')

        private const val HOJE = "A0-hoje"
        private const val RECORTE = "P1a-recorte"
        private const val CONTROLE = "A0-controle"
        private const val SEM_LLM = "S0-sem-llm"

        /**
         * **Os oito braços, cada um mudando UMA coisa em relação ao de hoje.**
         *
         * A ordem é a ordem em que eles rodam dentro de cada pergunta, e o
         * controle fecha a rodada de propósito.
         */
        private val BRACOS: List<Braco> = listOf(
            // ── hoje ─────────────────────────────────────────────────────────
            Braco(HOJE, FormulacaoDoPrompt.PRODUCAO),

            // ── Pista 1: o orçamento e a amostragem ──────────────────────────
            Braco(RECORTE, FormulacaoDoPrompt.PRODUCAO, recortar = true),
            Braco(
                "P1b-recorte-guloso", FormulacaoDoPrompt.PRODUCAO,
                opcoes = OpcoesDeAmostragem(temperatura = 0.0f, penalidade = 1.0f),
                recortar = true,
            ),
            Braco("P1c-ingles", FormulacaoDoPrompt.INSTRUCAO_EM_INGLES),

            // ── Pista 2: extração por gramática ──────────────────────────────
            Braco(
                "P2a-gramatica-12", FormulacaoDoPrompt.EXTRACAO_COM_TRECHO,
                gbnfDe = { GramaticaDaFonte.de(it, TETO_DE_PALAVRAS) },
            ),
            Braco(
                "P2b-gramatica-sem-trecho", FormulacaoDoPrompt.EXTRACAO_SO_A_PERGUNTA,
                gbnfDe = { GramaticaDaFonte.de(it, TETO_DE_PALAVRAS) },
            ),
            Braco(
                "P2c-gramatica-7", FormulacaoDoPrompt.EXTRACAO_COM_TRECHO,
                gbnfDe = { GramaticaDaFonte.de(it, 7) },
            ),
            // **As duas pistas somadas**, e é o braço que a primeira execução
            // mandou existir: com o artigo inteiro no prompt, P2a fica muda em 18
            // de 20 porque o prefill come o prazo antes da gramática entrar. O
            // recorte devolve o orçamento; a jaula continua vindo do trecho
            // INTEIRO, para que só uma coisa mude em relação a P2a.
            Braco(
                "P2d-gramatica-recorte", FormulacaoDoPrompt.EXTRACAO_COM_TRECHO,
                recortar = true,
                gbnfDe = { GramaticaDaFonte.de(it, TETO_DE_PALAVRAS) },
            ),

            // ── o controle que decide se o modelo paga o próprio custo ───────
            Braco(
                SEM_LLM, FormulacaoDoPrompt.EXTRACAO_COM_TRECHO,
                semModelo = { trecho, pergunta ->
                    SelecaoDeTrecho.escolher(trecho, pergunta, TETO_DE_PALAVRAS)
                },
            ),

            // ── controle de deriva ───────────────────────────────────────────
            Braco(CONTROLE, FormulacaoDoPrompt.PRODUCAO),
        )
    }
}
