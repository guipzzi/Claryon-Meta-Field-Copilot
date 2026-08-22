package com.claryon.field.bench

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.field.norma.RedacaoDoCopiloto
import com.claryon.llm.FormulacaoDoPrompt
import com.claryon.llm.GuardaDaRedacao
import com.claryon.llm.PedidoDeRedacao
import com.claryon.llm.RedatorLlamaCpp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **O orçamento da Etapa B com a configuração de PRODUÇÃO, sobre as perguntas
 * que a Etapa A de fato deixa passar.**
 *
 * ## Por que este arquivo existe, tendo `RedacaoDeAbordagemNoAparelhoTest`
 *
 * O irmão mede o **conteúdo**: cifra inventada, lastro, se vira ação. Ele roda
 * com `prazoMs = 30 000` de propósito, porque medir conteúdo através de um prazo
 * curto mediria o prazo. E roda sobre as **100** perguntas, incluindo as que a
 * porta do conhecimento recusa.
 *
 * Nenhum dos dois é o número que decide se a capacidade entra no palco. Esse
 * número é outro, e é este arquivo:
 *
 *  - **configuração de produção, literal** — [RedatorLlamaCpp] construído só com
 *    o arquivo, o que dá `nThreads = 4`, `nCtx = 1024`, `maxTokens = 96` e
 *    `prazoMs = 2 500`. Trocar qualquer um mediria outro produto;
 *  - **só as perguntas que chegariam lá** — as de confiança ≥ 0,30, porque
 *    abaixo disso `PortaDoConhecimento` recusa e a Etapa B nunca é chamada;
 *  - **p50 e p90**, não mediana solta: o aceite de ≤ 4 s do `ROADMAP.md` é sobre
 *    o ciclo inteiro, e a cauda é o que estoura orçamento, não o centro.
 *
 * ## O contra-teste está embutido, e ele é o par de prazos
 *
 * Rodar só com o prazo de produção não distingue *"o modelo é rápido"* de *"o
 * prazo cortou o `llama_decode` do próprio prompt e o texto nunca existiu"* — e
 * a segunda hipótese não é teórica: o KDoc de `RedatorNoAparelhoTest` registra
 * que foi exatamente isso que aconteceu neste emulador em 21/08, com o teste
 * verde e zero texto gerado.
 *
 * Por isso as mesmas perguntas rodam **duas vezes**, com o prazo de produção e
 * com [PRAZO_DE_MEDICAO_MS], e o relatório mostra as duas colunas. Se as duas
 * produzirem o mesmo número de textos, o prazo não está ligado em nada; se a de
 * produção produzir menos, a diferença **é** o custo do aceite.
 *
 * ## Como pôr as entradas no aparelho
 *
 * ```
 * ./gradlew :core-knowledge:test --tests '*DespejoDeAbordagemTest*' -i \
 *   | grep -E '^\s*TRIPLA' | sed -e 's|^ *||' > /tmp/abordagem.tsv
 * adb push /tmp/abordagem.tsv /data/local/tmp/abordagem.tsv
 * adb push Llama-3.2-1B-Instruct-Q4_K_M.gguf /data/local/tmp/redator.gguf
 * ```
 *
 * Sem os dois o teste **pula**. Pulado é a resposta honesta; verde seria mentira
 * — é o achado de 21/08 sobre o `WhisperCppSttTest`.
 */
class OrcamentoDaEtapaBNoAparelhoTest {

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
     * Uma geração medida: quanto custou e o que o guarda de produção achou.
     *
     * **As duas colunas trocaram de papel em 22/08, e o número mudou junto.**
     * Até aquele dia `RedatorLlamaCpp` montava a fonte do guarda como
     * `trecho + procedencia + pergunta`, e era essa a coluna "produção". A
     * composição foi corrigida (`EcoDaPerguntaNaoELastroTest`), então:
     *
     * @property aprovado o veredito de **produção de hoje** — guarda sobre
     *   `trecho + procedencia`, sem a pergunta, exatamente o que
     *   `RedatorLlamaCpp.redigir` monta.
     * @property aprovadoComAPergunta o veredito que a composição **antiga**
     *   daria. Não descreve o produto: existe para que a diferença entre as duas
     *   colunas seja lida como número, e é ela o [ecoDaPergunta].
     */
    private data class Amostra(
        val ms: Long,
        val cru: String?,
        val aprovado: Boolean,
        val aprovadoComAPergunta: Boolean,
        val lastro: Double,
        val lastroComAPergunta: Double,
    ) {
        /**
         * **O que a composição antiga aprovava e a de hoje reprova.**
         *
         * Um modelo que **repete a pergunta** — comportamento registrado deste
         * modelo, palavra por palavra — obtinha lastro **1,00** sem tocar no
         * artigo, porque a pergunta estava na fonte. Cada `true` aqui é uma
         * aprovação que existia por esse motivo e deixou de existir.
         */
        val ecoDaPergunta: Boolean get() = !aprovado && aprovadoComAPergunta

        /** O texto sem quebra de linha, ou `(vazio)`. Serve ao log e à leitura humana. */
        val umaLinha: String get() = cru?.replace('\n', ' ')?.trim().orEmpty().ifEmpty { "(vazio)" }

        /**
         * **O andaime do prompt vazou para a saída.** Não é heurística de
         * qualidade: são as strings literais que só existem no prompt, e achá-las
         * na resposta prova cópia.
         */
        val vazouOAndaime: Boolean
            get() = cru != null && ANDAIME.any { it in cru.uppercase() }

        /**
         * **A frase acabou no meio.** Frase truncada dita a um policial é pior que
         * silêncio: ele ouve metade de uma regra e não sabe que é metade. Indicador
         * declarado, não veredito — quem julga é a leitura humana do log.
         */
        val truncado: Boolean
            get() = !cru.isNullOrBlank() && cru.trim().last() !in FIM_DE_FRASE
    }

    // ------------------------------------------------------------- 1. carga e memória

    /**
     * **Tempo de carga e PSS antes/depois — o preço de simplesmente ter o modelo
     * de pé.**
     *
     * Vem separado da latência de geração porque são decisões diferentes: a
     * carga acontece uma vez no boot (`RedacaoDoCopiloto.decidirNoBoot`) e não
     * entra no aceite de 4 s; o PSS entra no orçamento de memória do aparelho, e
     * é ele que decide se `PoliticaDeRedacao` pode liberar a Etapa B.
     */
    @Test
    fun aCargaEOPssSaoMedidosComAConfiguracaoDeProducao() = runBlocking {
        val modelo = modeloNoAparelho()
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)

        val pssAntes = pssKb()
        val t0 = System.currentTimeMillis()
        val redator = RedatorLlamaCpp(modelo)
        val ok = redator.preparar()
        val carga = System.currentTimeMillis() - t0
        val pssDepois = pssKb()

        assertTrue("preparar() devolveu false — ver o logcat do llama", ok)
        Log.i(
            TAG,
            "orcamento: CARGA carga=${carga}ms · PSS ${pssAntes}→${pssDepois} kB " +
                "(Δ ${pssDepois - pssAntes} kB = %.2f MiB) · GGUF %d B (%.2f MiB) · razão %.2f×"
                    .format(
                        (pssDepois - pssAntes) / 1024.0,
                        modelo.length(),
                        modelo.length() / (1024.0 * 1024.0),
                        (pssDepois - pssAntes) * 1024.0 / modelo.length(),
                    ),
        )
        redator.liberar()
        val pssLiberado = pssKb()
        Log.i(TAG, "orcamento: CARGA após liberar() PSS=${pssLiberado} kB")
        // `Unit` explícito: sem ele, `= runBlocking { … }` terminado em `Log.i`
        // devolve `Int` e o JUnit recusa a CLASSE INTEIRA com "should be void" —
        // nenhum teste do arquivo roda. O KDoc de `RedatorNoAparelhoTest` já
        // registrava esta armadilha, e este arquivo caiu nela mesmo assim.
        Unit
    }

    // --------------------------------------------------------------- 1b. o embarque

    /**
     * **Como os 770 MiB chegam ao aparelho no dia — medido, não suposto.**
     *
     * O GGUF não vai no APK e o motivo é técnico, não de tamanho: o loader faz
     * `fopen`/`mmap` e asset não tem caminho no sistema de arquivos. Sobram três
     * caminhos, e este teste mede os dois que não dependem de rede:
     *
     *  1. **`adb push` + cópia para `filesDir`** — o que
     *     `RedacaoDoCopiloto.arquivoDoModelo` lê hoje. Custa **duas** vezes o
     *     espaço em disco enquanto a cópia acontece, e o tempo medido abaixo.
     *  2. **`adb push` direto para `getExternalFilesDir`** — sem cópia e sem
     *     `run-as`, portanto funciona também em build de release. Só vale se o
     *     llama.cpp conseguir carregar de lá, que é o que este teste confere: o
     *     armazenamento "externo" é FUSE em Android moderno, e `mmap` sobre FUSE
     *     não é garantido.
     *
     * O terceiro caminho — baixar na primeira execução — não é medido aqui de
     * propósito: ele depende do Wi-Fi do evento no dia do evento, e o que
     * decidiria a favor dele não é uma medida de bancada.
     */
    @Test
    fun osDoisCaminhosDeEmbarqueSaoMedidos() = runBlocking {
        val origem = File(GGUF_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", origem.canRead())

        // ── Caminho 1: cópia para filesDir ───────────────────────────────────
        // **Caminho privado montado à mão, e não `arquivoDoModelo`.** Desde 22/08
        // aquela função procura nos DOIS lugares e pode devolver o externo — o
        // `delete()` abaixo apagaria justamente o arquivo que o caminho 2 vai
        // medir, e o teste passaria medindo a si mesmo.
        val interno = File(contexto.filesDir, RedacaoDoCopiloto.ARQUIVO_DO_MODELO)
        interno.delete()
        val t0 = System.currentTimeMillis()
        origem.inputStream().use { e -> interno.outputStream().use { e.copyTo(it, 1 shl 20) } }
        val copia = System.currentTimeMillis() - t0
        Log.i(
            TAG,
            "embarque: CÓPIA para filesDir ${copia}ms · %.2f MiB · %.1f MiB/s · destino=%s"
                .format(
                    interno.length() / (1024.0 * 1024.0),
                    interno.length() / (1024.0 * 1024.0) / (copia / 1000.0),
                    interno.path,
                ),
        )

        // ── Caminho 2: carregar direto do externo, sem cópia ─────────────────
        val externo = File(contexto.getExternalFilesDir(null), "redator.gguf")
        Log.i(
            TAG,
            "embarque: EXTERNO ${externo.path} existe=${externo.isFile} " +
                "tamanho=${if (externo.isFile) externo.length() else 0} B " +
                "legível=${externo.canRead()}",
        )
        if (externo.isFile && externo.canRead()) {
            val t1 = System.currentTimeMillis()
            val doExterno = RedatorLlamaCpp(externo)
            val subiu = doExterno.preparar()
            val cargaExterna = System.currentTimeMillis() - t1
            Log.i(
                TAG,
                "embarque: EXTERNO preparar()=$subiu em ${cargaExterna}ms — " +
                    if (subiu) {
                        "o llama.cpp CARREGA de getExternalFilesDir: o `adb push` pode ir " +
                            "direto para lá e a cópia de ${copia}ms some do onboarding"
                    } else {
                        "o llama.cpp NÃO carrega de lá; o onboarding precisa da cópia"
                    },
            )
            doExterno.liberar()
        } else {
            Log.w(
                TAG,
                "embarque: EXTERNO ausente — faça `adb push <gguf> " +
                    "/sdcard/Android/data/com.claryon.field/files/redator.gguf` e rode " +
                    "de novo. Sem isso o caminho 2 fica NÃO MEDIDO, não reprovado.",
            )
        }
        Unit
    }

    // ------------------------------------------------------- 2. latência de produção

    /**
     * **p50 e p90 da geração, com o prazo de produção e com o prazo de medição,
     * sobre as perguntas que a porta deixa passar.**
     *
     * O guarda do MOTOR fica em `lastroMinimo = 0.0` para o texto cru chegar ao
     * log; o guarda de PRODUÇÃO julga aqui, explícito, e o número de recusas é
     * um dos resultados. Isso não muda a latência: o guarda é comparação de
     * conjuntos sobre poucas centenas de caracteres, não inferência.
     */
    @Test
    fun aLatenciaEAsRecusasSaoMedidasSobreAsPerguntasQuePassamAPorta() = runBlocking {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        Log.i(
            TAG,
            "orcamento: ${doCorpus.size} perguntas com confiança ≥ $LIMIAR_DE_PRODUCAO " +
                "(de ${triplas(tsv).size} no banco)",
        )
        assertTrue(
            "menos de $MINIMO_DE_PERGUNTAS perguntas acima do limiar: o TSV chegou " +
                "truncado ao aparelho, e p90 sobre punhado não é p90",
            doCorpus.size >= MINIMO_DE_PERGUNTAS,
        )

        val producao = medir(doCorpus, modelo, RedatorLlamaCpp.PRAZO_PADRAO_MS, "PRODUCAO")
        val folgado = medir(doCorpus, modelo, PRAZO_DE_MEDICAO_MS, "FOLGADO")

        relatar("PRODUCAO", RedatorLlamaCpp.PRAZO_PADRAO_MS, producao, doCorpus.size)
        relatar("FOLGADO", PRAZO_DE_MEDICAO_MS, folgado, doCorpus.size)

        val comTextoProducao = producao.count { !it.cru.isNullOrBlank() }
        val comTextoFolgado = folgado.count { !it.cru.isNullOrBlank() }

        // ── Controle positivo ────────────────────────────────────────────────
        // Sem isto, um modelo que devolve `null` em tudo produziria "p50 de
        // 2 501 ms" e o relatório mediria o prazo estourando, não geração
        // nenhuma. Já aconteceu neste projeto, com este mesmo motor.
        assertTrue(
            "Nenhuma das ${folgado.size} gerações produziu texto nem com " +
                "${PRAZO_DE_MEDICAO_MS} ms. O par de prazos não tem o que comparar e " +
                "todo número acima é sobre o prazo, não sobre o modelo.",
            comTextoFolgado > 0,
        )

        // ── Contra-teste: o prazo de produção TEM de morder ──────────────────
        // Se as duas configurações rendessem o mesmo, `prazoMs` não estaria
        // ligado a nada e "a Etapa B cabe no aceite" seria afirmação sobre um
        // parâmetro inerte. A asserção é sobre a DIFERENÇA — nunca sobre um teto
        // absoluto, que este emulador cumpriria e um celular de campo não.
        val p50Producao = percentil(producao.map { it.ms }, 50)
        val p50Folgado = percentil(folgado.map { it.ms }, 50)
        assertTrue(
            "prazo de produção (${RedatorLlamaCpp.PRAZO_PADRAO_MS} ms) e prazo de medição " +
                "($PRAZO_DE_MEDICAO_MS ms) renderam o MESMO trabalho: $comTextoProducao e " +
                "$comTextoFolgado textos, p50 $p50Producao e $p50Folgado ms. Ou o " +
                "abort_callback não está ligado, ou o modelo termina antes dos dois — " +
                "e nos dois casos o número de produção acima não significa o que diz.",
            comTextoProducao != comTextoFolgado || p50Producao != p50Folgado,
        )
        Unit
    }

    // -------------------------------------------------------- 3. o prompt, uma coisa por vez

    /**
     * **As formulações de prompt comparadas sobre o mesmo banco, no mesmo
     * aparelho, com o prazo de produção.**
     *
     * ## Por que este teste existe antes de trocar de modelo
     *
     * Os quatro defeitos lidos no log de 22/08 — eco da pergunta, vazamento do
     * andaime, meta-comentário e consequência inventada — **são todos de
     * prompt**, e três deles têm causa literal no texto que o modelo recebe:
     *
     *  - a pergunta está no contexto, então há o que ecoar;
     *  - `TRECHO DA NORMA:` está no contexto em caixa alta, então há o que copiar;
     *  - a instrução manda *"Resuma o TRECHO DA NORMA"*, nomeando o artefato,
     *    então falar **sobre** o trecho é uma leitura razoável da tarefa.
     *
     * Trocar de família de modelo custa tokenizador, licença e prompt novo
     * (`ROADMAP.md`). Trocar a formulação custa uma string. O `CLAUDE.md` §6 pede
     * que a afirmação venha com número, e o §7 pede uma mudança por vez: por isso
     * [FormulacaoDoPrompt.TODAS] muda **uma** coisa de cada vez, e este teste
     * roda todas em sequência.
     *
     * ## Por que a comparação NÃO roda com o prazo de produção
     *
     * A primeira versão deste teste rodava as cinco formulações a 2 500 ms, pelo
     * argumento de que medir prompt com prazo folgado mede um produto que não
     * existe. **A tabela que saiu era do emulador, não do prompt:**
     *
     * ```
     * F0 …… 14 textos · prefill 287 tok/s   (bloco rodado primeiro)
     * F1 ……  7 textos · prefill 232 tok/s
     * F2 ……  3 textos · prefill 196 tok/s
     * F3 ……  2 textos · prefill 218 tok/s
     * F4 ……  4 textos · prefill 219 tok/s   (bloco rodado por último)
     * ```
     *
     * As cinco têm prompt do **mesmo tamanho** (469 a 506 tokens de média, ±5%),
     * e ainda assim o primeiro bloco rendeu sete vezes mais texto que o terceiro.
     * A causa está na terceira coluna: a máquina que hospeda o emulador ficou
     * 30% mais lenta ao longo dos vinte minutos, o prefill de ~500 tokens passou
     * a custar mais de 2 500 ms sozinho, e `llama_decode` começou a devolver `2`
     * (abortado) **antes de o prompt entrar**. O que a tabela mediu foi a ordem
     * dos blocos.
     *
     * Por isso a comparação de **conteúdo** roda com [PRAZO_DE_MEDICAO_MS], onde
     * carga da máquina vira latência e não mudez, e o custo de orçamento é
     * medido à parte, na segunda tabela.
     *
     * ## O contra-teste é o bloco de controle
     *
     * [FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA] roda **duas vezes**: primeiro e
     * por último. Se os dois blocos não derem o mesmo número de textos, a bancada
     * não é reprodutível naquela execução e nenhuma diferença entre formulações
     * pode ser atribuída ao prompt — o teste falha dizendo isso, em vez de deixar
     * a tabela parecer um resultado.
     *
     * ## O que este teste NÃO decide
     *
     * Ele não diz qual formulação é melhor. Ele produz `com texto`, `aprovadas`,
     * `ECO`, `ANDAIME`, `TRUNCADAS`, p50 e p90 por formulação, e imprime cada
     * geração inteira. **Utilizável é julgamento humano sobre esse log** — o
     * `RedacaoDeAbordagemNoAparelhoTest` já registrava que automatizar isso com
     * honestidade não dá, porque "meta-comentário" e "consequência inventada" não
     * têm assinatura lexical.
     *
     * ## O resultado de 22/08, com a coluna que o log não sabe produzir
     *
     * Emulador arm64 API 35, 20 perguntas acima do limiar, prazo de medição. A
     * última coluna é leitura humana das 100 gerações, pelo critério: **frase
     * completa · tudo o que afirma está no trecho · responde o que foi perguntado ·
     * não fala sobre a tarefa · não induz o agente a erro se dita sozinha, sem
     * tela.**
     *
     * ```
     * formulação          texto  aprova  eco  andaime  trunc  utilizáveis
     * F0 andaime            13     10     2      4       2         1
     * F1 sem rótulo         17      8     5      0       0         2
     * F2 sem a pergunta     18     16     0      0       0         1
     * F3 proibições         17     14     0      0       6         1
     * F4 uma frase          16     14     0      0       4         1
     * F0 controle           13     10     2      4       2         1
     * ```
     *
     * **`aprova` e `utilizáveis` andam em sentidos opostos, e a causa é estrutural.**
     * F2 aprova 16 e rende 1; F0 aprova 10 e rende 1. A régua de lastro premia
     * casamento lexical com a fonte, e a forma mais barata de casar com a fonte é
     * **copiá-la**: F3 e F4 transcrevem o artigo (*"Art. 311. Adulterar ou suprimir
     * identificação de veículo automotor…"*), obtêm lastro 1,00, passam — e chegam
     * ao agente truncadas no meio, com o número de artigo que a instrução proibia.
     * Quem for decidir a Etapa B por esta tabela tem de olhar a última coluna.
     *
     * **A refutação que importa: trocar o prompt conserta o DEFEITO, não a
     * utilidade.** O vazamento do andaime é 4 → 0 e o eco é 5 → 0 — os dois eram de
     * prompt, e a prova é que sumiram sem trocar de modelo. Mas `utilizáveis` fica
     * entre 1 e 2 em todas as cinco. O que trava não é como se pede; é o que um
     * modelo de 1B sabe responder sobre a lei.
     */
    @Test
    fun asFormulacoesDePromptSaoComparadasSobreOMesmoBanco() = runBlocking {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        assertTrue(
            "menos de $MINIMO_DE_PERGUNTAS perguntas acima do limiar: o TSV chegou truncado",
            doCorpus.size >= MINIMO_DE_PERGUNTAS,
        )

        // ── Tabela 1: conteúdo, com o prazo fora do caminho ──────────────────
        val porFormulacao = LinkedHashMap<String, List<Amostra>>()
        for (f in FormulacaoDoPrompt.TODAS) {
            val a = medir(doCorpus, modelo, PRAZO_DE_MEDICAO_MS, f.nome, f)
            porFormulacao[f.nome] = a
            relatar(f.nome, PRAZO_DE_MEDICAO_MS, a, doCorpus.size)
        }
        val controle = medir(
            doCorpus, modelo, PRAZO_DE_MEDICAO_MS, CONTROLE,
            FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA,
        )
        porFormulacao[CONTROLE] = controle
        relatar(CONTROLE, PRAZO_DE_MEDICAO_MS, controle, doCorpus.size)

        Log.i(TAG, "prompt: ── TABELA 1 · CONTEÚDO · ${doCorpus.size} perguntas · prazo $PRAZO_DE_MEDICAO_MS ms ──")
        Log.i(TAG, "prompt: formulação | texto | aprova | eco | andaime | trunc | p50 | p90")
        for ((nome, a) in porFormulacao) tabelar(nome, a)

        // ── Contra-teste 1: as formulações TÊM de diferir ────────────────────
        // Sem isto, um `formulacao` que não chegasse ao JNI daria linhas
        // idênticas e a tabela pareceria dizer "o prompt não importa" — quando o
        // que ela diria é "o parâmetro está inerte". Já aconteceu neste projeto,
        // com `Grandezas` desligada e a régua reprovando zero de 268.
        val assinaturas = FormulacaoDoPrompt.TODAS
            .map { f -> porFormulacao.getValue(f.nome).map { it.umaLinha } }
            .toSet()
        assertTrue(
            "As ${FormulacaoDoPrompt.TODAS.size} formulações produziram exatamente os MESMOS " +
                "textos. Ou `formulacao` não chega ao `nativoRedigir`, ou o prompt não é lido — " +
                "e nos dois casos as tabelas acima não medem prompt nenhum.",
            assinaturas.size > 1,
        )

        // ── Contra-teste 2: o bloco de controle ──────────────────────────────
        val base = porFormulacao.getValue(FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA.nome)
        val textosBase = base.count { !it.cru.isNullOrBlank() }
        val textosControle = controle.count { !it.cru.isNullOrBlank() }
        assertTrue(
            "A MESMA formulação rendeu $textosBase textos no primeiro bloco e $textosControle " +
                "no último. A bancada não foi reprodutível nesta execução — provavelmente a " +
                "máquina que hospeda o emulador mudou de carga —, e nenhuma diferença entre " +
                "formulações pode ser atribuída ao prompt. Repita com a máquina ociosa.",
            textosBase == textosControle,
        )

        // O andaime só pode vazar de onde ele existe. Se F2 em diante — que não
        // têm rótulo nenhum no contexto — vazassem `TRECHO DA NORMA`, o detector
        // estaria casando com outra coisa e o número da coluna seria ruído.
        for (f in listOf(FormulacaoDoPrompt.SEM_A_PERGUNTA, FormulacaoDoPrompt.PROIBICOES_EXPLICITAS)) {
            val vazamentos = porFormulacao.getValue(f.nome).filter { it.vazouOAndaime }
            assertTrue(
                "A formulação ${f.nome} não põe rótulo nenhum no contexto e ainda assim " +
                    "${vazamentos.size} saídas contêm o andaime: ${vazamentos.map { it.umaLinha }}. " +
                    "O detector está casando com texto da própria norma — a coluna ANDAIME é ruído.",
                vazamentos.isEmpty(),
            )
        }
        Unit
    }

    /**
     * **A segunda coluna: as mesmas formulações com o prazo de produção.**
     *
     * Separada do teste de conteúdo por dois motivos, e o segundo é operacional:
     *
     *  1. ela mede **orçamento**, não prompt. Como o KDoc da irmã registra, a
     *     ordem dos blocos entra no número quando a máquina que hospeda o
     *     emulador muda de carga — o prefill de ~500 tokens custa 1,6 a 2,5 s
     *     sozinho, e o prazo é 2,5 s. Ler esta tabela como comparação entre
     *     formulações é o erro que a primeira versão cometeu;
     *  2. juntas, as duas passam de quinze minutos no aparelho. Uma execução
     *     longa é uma execução que morre no meio — esta bancada já foi morta por
     *     `Killing … due to deletePackageX`, que é o `connectedAndroidTest` de
     *     outra sessão reinstalando o pacote por baixo. Duas execuções curtas
     *     sobrevivem; uma longa não.
     */
    @Test
    fun oOrcamentoDeCadaFormulacaoNoPrazoDeProducao() = runBlocking {
        val modelo = modeloNoAparelho()
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO, não verde", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val doCorpus = triplas(tsv).filter { it.confianca >= LIMIAR_DE_PRODUCAO }
        assertTrue("TSV truncado", doCorpus.size >= MINIMO_DE_PERGUNTAS)

        val comPrazo = LinkedHashMap<String, List<Amostra>>()
        for (f in FormulacaoDoPrompt.TODAS) {
            comPrazo[f.nome] = medir(
                doCorpus, modelo, RedatorLlamaCpp.PRAZO_PADRAO_MS, "prod-${f.nome}", f,
            )
        }
        comPrazo[CONTROLE] = medir(
            doCorpus, modelo, RedatorLlamaCpp.PRAZO_PADRAO_MS, "prod-$CONTROLE",
            FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA,
        )

        Log.i(
            TAG,
            "prompt: ── TABELA 2 · ORÇAMENTO · prazo ${RedatorLlamaCpp.PRAZO_PADRAO_MS} ms · " +
                "a ORDEM DOS BLOCOS entra no número; ver o KDoc ──",
        )
        Log.i(TAG, "prompt: formulação | texto | aprova | eco | andaime | trunc | p50 | p90")
        for ((nome, a) in comPrazo) tabelar(nome, a)

        val textosBase = comPrazo.getValue(FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA.nome)
            .count { !it.cru.isNullOrBlank() }
        val textosControle = comPrazo.getValue(CONTROLE).count { !it.cru.isNullOrBlank() }
        Log.i(
            TAG,
            "prompt: CONTROLE do orçamento — a MESMA formulação rendeu $textosBase textos no " +
                "primeiro bloco e $textosControle no último. Se diferem, a tabela 2 mede a carga " +
                "da máquina e a diferença entre formulações NÃO é atribuível ao prompt.",
        )
        Unit
    }

    private fun tabelar(nome: String, a: List<Amostra>) {
        val ms = a.map { it.ms }
        Log.i(
            TAG,
            "prompt: $nome | ${a.count { !it.cru.isNullOrBlank() }} | ${a.count { it.aprovado }} | " +
                "${a.count { it.ecoDaPergunta }} | ${a.count { it.vazouOAndaime }} | " +
                "${a.count { it.truncado }} | ${percentil(ms, 50)} | ${percentil(ms, 90)}",
        )
    }

    // ------------------------------------------------------------------- medição

    private suspend fun medir(
        perguntas: List<Tripla>,
        modelo: File,
        prazo: Int,
        rotulo: String,
        formulacao: FormulacaoDoPrompt = FormulacaoDoPrompt.PRODUCAO,
    ): List<Amostra> {
        // Guarda desligado no motor para o CRU chegar ao log; o de produção
        // julga fora, e a diferença entre os dois é justamente o dado pedido.
        //
        // **Motor NOVO a cada rodada, e isso não é higiene — é a medição.** A
        // cadeia de amostragem nasce em `nativoCarregar` e NÃO é reiniciada entre
        // gerações: o `penalties` guarda os últimos 64 tokens e o `dist` carrega o
        // estado do RNG. Reusar o mesmo handle entre duas formulações faria a
        // segunda começar de um estado que a primeira produziu, e a diferença
        // medida seria em parte a semente, não o prompt.
        val redator = RedatorLlamaCpp(
            modelo,
            prazoMs = prazo,
            guarda = SEM_GUARDA,
            formulacao = formulacao,
        )
        assertTrue("preparar() devolveu false com prazo=$prazo", redator.preparar())
        val guarda = GuardaDaRedacao()

        val amostras = ArrayList<Amostra>(perguntas.size)
        for ((i, t) in perguntas.withIndex()) {
            val procedencia = "${t.citacao} — ${t.norma}"
            // A fonte EXATA que `RedatorLlamaCpp.redigir` monta desde 22/08 —
            // inclusive a ordem das parcelas, e SEM a pergunta.
            val fonte = "${t.texto}\n$procedencia"
            // A composição ANTIGA, com a pergunta. Ver `Amostra.ecoDaPergunta`.
            val fonteComAPergunta = "$fonte\n${t.pergunta}"
            val t0 = System.currentTimeMillis()
            val cru = redator.redigir(
                PedidoDeRedacao(pergunta = t.pergunta, trecho = t.texto, procedencia = procedencia),
            )
            val dt = System.currentTimeMillis() - t0
            val vazio = cru.isNullOrBlank()
            val lastro = if (vazio) 0.0 else guarda.lastro(cru!!, fonte)
            val lastroComP = if (vazio) 0.0 else guarda.lastro(cru!!, fonteComAPergunta)
            val aprovado = !vazio && guarda.aprovar(cru!!, fonte) != null
            val aprovadoComP = !vazio && guarda.aprovar(cru!!, fonteComAPergunta) != null
            val a = Amostra(dt, cru, aprovado, aprovadoComP, lastro, lastroComP)
            amostras += a
            Log.i(
                TAG,
                "orcamento: $rotulo [${i + 1}/${perguntas.size}] ${dt}ms " +
                    "conf=%.3f".format(t.confianca) +
                    " guarda=${if (aprovado) "APROVA" else "recusa"}" +
                    " lastro=%.2f".format(lastro) +
                    " comPergunta=%.2f".format(lastroComP) +
                    "/${if (aprovadoComP) "APROVA" else "recusa"}" +
                    (if (a.ecoDaPergunta) " ECO" else "") +
                    (if (a.vazouOAndaime) " ANDAIME" else "") +
                    (if (a.truncado) " TRUNCADO" else "") +
                    " | P=\"${t.pergunta}\" | A=${t.citacao}/${t.norma}" +
                    " | G=\"${a.umaLinha}\"",
            )
        }
        redator.liberar()
        return amostras
    }

    private fun relatar(rotulo: String, prazo: Int, a: List<Amostra>, n: Int) {
        val ms = a.map { it.ms }
        val comTexto = a.count { !it.cru.isNullOrBlank() }
        val aprovadas = a.count { it.aprovado }
        val ecos = a.count { it.ecoDaPergunta }
        Log.i(
            TAG,
            "orcamento: RESUMO $rotulo prazo=${prazo}ms · $n perguntas · " +
                "com texto=$comTexto · vazias=${n - comTexto} · " +
                "guarda APROVA=$aprovadas REPROVA=${comTexto - aprovadas} " +
                "(sobre as $comTexto com texto) · " +
                "ECO=$ecos (aprovadas pela composição ANTIGA e reprovadas pela de hoje) · " +
                "ANDAIME=${a.count { it.vazouOAndaime }} · TRUNCADAS=${a.count { it.truncado }} · " +
                "p50=${percentil(ms, 50)}ms p90=${percentil(ms, 90)}ms " +
                "min=${ms.minOrNull()}ms max=${ms.maxOrNull()}ms · " +
                "aceite do ROADMAP é 4000 ms para o CICLO INTEIRO, e isto é só a parcela " +
                "de redação",
        )
    }

    /**
     * Percentil pelo método do índice mais próximo, sem interpolação — o mesmo
     * que o resto da bancada deste projeto usa. Com 20 amostras, interpolar
     * inventaria precisão que o tamanho da amostra não tem.
     */
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
     * Copia o GGUF de `/data/local/tmp` para o `filesDir` do app na primeira
     * execução — o mesmo caminho que `RedacaoDoCopiloto.decidirNoBoot` lê em
     * produção, e o mesmo que o onboarding do dia do evento vai usar.
     */
    private fun modeloNoAparelho(): File {
        val destino = RedacaoDoCopiloto.arquivoDoModelo(contexto)
        if (!destino.isFile || destino.length() == 0L) {
            val origem = File(GGUF_NO_TMP)
            if (origem.canRead()) {
                origem.inputStream().use { e -> destino.outputStream().use { e.copyTo(it, 1 shl 20) } }
            }
        }
        return destino
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    companion object {
        private const val TAG = "ClaryonField"
        private const val GGUF_NO_TMP = "/data/local/tmp/redator.gguf"
        private const val TSV_NO_TMP = "/data/local/tmp/abordagem.tsv"

        /** `BaseDeConhecimentoLexical.LIMIAR_MEDIDO`, repetido porque `app` não tem o módulo. */
        private const val LIMIAR_DE_PRODUCAO = 0.30

        /**
         * Abaixo disto o p90 é ruído: com 9 amostras o p90 é literalmente o
         * penúltimo valor. O banco de abordagem rende 20 acima do limiar, então
         * o piso só dispara se o TSV chegar truncado — que já aconteceu.
         */
        private const val MINIMO_DE_PERGUNTAS = 10

        /** O par do prazo de produção. Ver o KDoc da classe. */
        private const val PRAZO_DE_MEDICAO_MS = 30_000

        private val SEM_GUARDA = GuardaDaRedacao(lastroMinimo = 0.0)

        /**
         * As strings que **só** existem no prompt de
         * [FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA]. Achá-las na saída não é
         * heurística de qualidade: é prova de cópia literal.
         */
        private val ANDAIME = listOf("TRECHO DA NORMA", "PERGUNTA DO AGENTE")

        private val FIM_DE_FRASE = charArrayOf('.', '!', '?', ':', '”', '"')

        /** O rótulo do bloco de repetição. Ver o contra-teste 2. */
        private const val CONTROLE = "F0-controle"
    }
}
