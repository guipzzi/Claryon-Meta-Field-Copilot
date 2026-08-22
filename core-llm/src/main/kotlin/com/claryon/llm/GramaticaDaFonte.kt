package com.claryon.llm

/**
 * **A GBNF que torna a alucinação impossível por construção, em vez de detectável
 * por régua.**
 *
 * ## Por que isto existe
 *
 * O `GuardaDaRedacao` é um detector: ele julga o texto **depois** de gerado, por
 * casamento lexical, e por isso é cego a tudo que use o léxico da fonte com o
 * sentido trocado — a inversão de negação medida em 22/08 (*"A pessoa com
 * deficiência mental **não deixou de** observar as cautelas"*) passou com lastro
 * 0,78 e é a lei ao contrário. Detector cego não fica menos cego com mais
 * calibração: ele compara palavras e o defeito é de sentido.
 *
 * A alavanca alternativa está no artefato vendorizado e foi conferida por leitura
 * do cabeçalho, não de memória (Regra Zero):
 *
 * ```c
 * // core-llm/src/main/cpp/llama/include/llama.h:1415
 * LLAMA_API struct llama_sampler * llama_sampler_init_grammar(
 *         const struct llama_vocab * vocab,
 *                       const char * grammar_str,
 *                       const char * grammar_root);
 * ```
 *
 * Uma gramática não julga a saída: ela **impede** que os tokens fora dela sejam
 * sorteados. Se a gramática só admite trechos que existem na fonte, não há
 * amostragem que produza palavra inventada, número inventado ou negação
 * removida — o `-INFINITY` entra no logit antes do sorteio.
 *
 * ## O que esta gramática admite: UM trecho contíguo da fonte
 *
 * A saída tem de ser uma sequência de palavras **consecutivas** do trecho, na
 * ordem original, com no máximo [maximoDePalavras] palavras.
 *
 * Contíguo é a parte que importa, e é o que separa esta gramática de um saco de
 * palavras da fonte. Recombinar palavras da fonte permite *"não há pena"* a partir
 * de *"há pena"* — cada palavra tem lastro e a frase mente. Exigir contiguidade
 * transforma a saída em **citação**: ou está escrito assim na lei, ou não sai.
 *
 * A tarefa que sobra para o modelo é a que ele consegue fazer: **escolher onde o
 * trecho começa**. Isso é extração, não redação — e a Etapa B nunca precisou
 * redigir, precisou responder em ≤ 7 palavras.
 *
 * ## O que ela NÃO resolve, e é honesto dizer antes de medir
 *
 *  - **Omissão continua possível.** Um recorte contíguo pode deixar de fora a
 *    condicionante que vinha logo antes (*"salvo se…"*). Contiguidade não é
 *    completude.
 *  - **A abstenção some.** Sem alternativa vazia na gramática, o modelo sempre
 *    emite algum trecho — inclusive quando o artigo não responde à pergunta.
 *    Quem decide não responder continua sendo o limiar da Etapa A, e isso ficou
 *    igual de propósito: alternativa de escape neste projeto já foi medida e
 *    produziu `"NÃO SEI."` em 5 de 6 gerações sobre trecho que respondia.
 *  - **Custo.** Compilar GBNF por consulta e filtrar 128 k candidatos por token
 *    tem preço, e o orçamento já está estourado. É medido, não estimado, por
 *    `NativoDoRedator.nativoMedirGramatica`.
 *
 * ## A forma da GBNF, e por que ela é esta
 *
 * ```
 * root ::= " "? t
 * t    ::= p0-7 | p9-7 | p14-7 …        ← as posições em que um trecho pode começar
 * p0-7 ::= "Art." | "Art. " p1-6        ← ou para aqui, ou continua na próxima palavra
 * p1-6 ::= "306." | "306. " p2-5
 * …
 * p7-1 ::= "alterada"                   ← profundidade 1 não continua: é o teto
 * ```
 *
 * O índice duplo `posição-profundidade` é o que torna o teto de palavras **exato**.
 * Com uma cadeia só por posição, o teto teria de vir de `maxTokens`, e aí o corte
 * cairia no meio de uma palavra — que é justamente a truncagem que a tabela de
 * 22/08 mede em 4 e 6 casos nas formulações F3 e F4.
 *
 * `" "?` na raiz existe porque o primeiro token gerado pelo Llama 3 pode ou não
 * trazer o espaço colado. Sem essa folga, metade das gerações morreria no
 * primeiro token com a gramática rejeitando o vocabulário inteiro.
 *
 * As alternativas são escritas em vez de agrupadas com `( … )?` de propósito: o
 * agrupamento faz o parser do llama.cpp **sintetizar uma regra a mais por
 * ocorrência** (`llama-grammar.cpp`, `parse_sequence`, ramo `'('`), e aqui o
 * número de regras é da ordem de palavras × profundidade. Dobrá-lo dobra o custo
 * de compilação, que é o número em julgamento.
 */
object GramaticaDaFonte {

    /**
     * Onde um trecho pode começar.
     *
     * **A escolha não é só de gosto — ela decide o custo do primeiro token.** A
     * raiz vira uma alternância sobre as posições de início, e cada alternativa
     * é uma pilha ativa no autômato da gramática. `llama_grammar_apply_impl`
     * varre os candidatos uma vez por pilha (`llama_grammar_reject_candidates`
     * encadeia as pilhas sobre o conjunto de rejeitados), então 380 posições de
     * início custam ordens de grandeza mais que 30.
     */
    enum class Inicio {
        /** Qualquer palavra. Máxima liberdade, custo máximo no primeiro token. */
        QUALQUER_PALAVRA,

        /**
         * Só depois de pontuação de fronteira (`. : ; , – —`) e no começo do
         * trecho.
         *
         * Além de barato, é **melhor fala**: um trecho que começa no meio de um
         * sintagma (*"de veículo automotor, elétrico"*) é dito ao agente sem o
         * núcleo que o rege. Fronteira de cláusula é onde a lei já pontuou.
         */
        FRONTEIRA_DE_CLAUSULA,
    }

    /**
     * A GBNF de [fonte], ou `null` quando não há de onde extrair.
     *
     * `null` é resposta legítima e quem chama cai no caminho sem gramática — do
     * mesmo jeito que `Redator.redigir` devolve `null` e o produto cai na
     * citação. Um trecho vazio não produz gramática vazia: produziria uma
     * gramática que não casa com nada, e o modelo ficaria mudo por um motivo que
     * ninguém conseguiria ler no log.
     *
     * @param maximoDePalavras teto de palavras da saída. `7` é o teto de fala
     *   operacional do `CLAUDE.md` §4.
     */
    fun de(
        fonte: String,
        maximoDePalavras: Int = 7,
        inicio: Inicio = Inicio.FRONTEIRA_DE_CLAUSULA,
        minimoDePalavras: Int = MINIMO_PADRAO,
    ): String? {
        require(maximoDePalavras >= 1) { "Teto de palavras abaixo de 1: $maximoDePalavras" }
        require(minimoDePalavras in 1..maximoDePalavras) {
            "Piso $minimoDePalavras fora de 1..$maximoDePalavras"
        }
        val palavras = palavras(fonte)
        if (palavras.isEmpty()) return null

        val inicios = iniciosPermitidos(palavras, inicio)
        if (inicios.isEmpty()) return null

        val sb = StringBuilder(inicios.size * maximoDePalavras * 48)
        sb.append("root ::= \" \"? t\n")
        sb.append("t ::= ")
        inicios.forEachIndexed { i, p ->
            if (i > 0) sb.append(" | ")
            sb.append(nome(p, maximoDePalavras))
        }
        sb.append('\n')

        // **Só as regras ALCANÇÁVEIS são escritas.** A regra `p5-7` só existe se
        // alguma posição de início chega a 5 com 7 de profundidade restante, isto
        // é, se `5` for início. Emitir a grade inteira `posição × profundidade`
        // custaria `palavras × teto` regras — 1 449 num artigo de 207 palavras —,
        // e o custo de compilar é o número em julgamento aqui. Com fronteira de
        // cláusula sobram ~30 inícios, e a conta cai para ~30 × teto.
        var nivel: Set<Int> = inicios.toSet()
        for (d in maximoDePalavras downTo 1) {
            // **Quantas palavras já foram ditas quando esta regra roda.** É o que
            // decide se PARAR aqui é permitido: com `d` restantes de um teto de
            // `maximoDePalavras`, já saíram `maximoDePalavras - d + 1` contando
            // esta.
            val jaDitas = maximoDePalavras - d + 1
            for (i in nivel.sorted()) {
                val literal = escapar(palavras[i])
                val podeContinuar = d > 1 && i + 1 < palavras.size
                // **O piso é o conserto do defeito mais frequente da medição de
                // 22/08 (noite).** Sem ele, a gramática admite parar depois de
                // QUALQUER palavra — a pilha esvazia e o EOG passa a ser
                // sorteável. Um modelo de 1B toma essa saída o tempo todo: das 20
                // gerações do braço sem trecho, sete foram *"Penalidade"*,
                // *"não"*, *"Apresentado"*, *"1º"*, *"a 2"*, *"25-"* — trechos
                // perfeitamente contíguos e perfeitamente inúteis.
                //
                // Fechar a saída antes de `minimoDePalavras` não é preferência de
                // estilo: uma palavra solta não é fala, e o agente que ouve
                // "Penalidade" recebeu silêncio com sotaque.
                //
                // O `podeContinuar == false` sempre termina, senão o ramo vira
                // beco sem saída: chegou ao fim do texto e não pode parar.
                val podeParar = jaDitas >= minimoDePalavras || !podeContinuar
                sb.append(nome(i, d)).append(" ::= ")
                if (podeParar) {
                    sb.append('"').append(literal).append('"')
                    if (podeContinuar) sb.append(" | ")
                }
                if (podeContinuar) {
                    sb.append('"').append(literal).append(" \" ").append(nome(i + 1, d - 1))
                }
                sb.append('\n')
            }
            nivel = nivel.mapNotNull { (it + 1).takeIf { p -> p < palavras.size } }.toSet()
        }
        return sb.toString()
    }

    /**
     * **O contra-teste da gramática, em Kotlin.** `true` se [texto] é de fato uma
     * sequência contígua de palavras de [fonte].
     *
     * Existe porque "a gramática garante" é exatamente o tipo de afirmação que
     * este projeto já viu nascer morta seis vezes. Se o `llama_sampler_init_grammar`
     * não estiver na cadeia, se a raiz estiver errada ou se a GBNF não compilar e
     * alguém cair em geração livre, esta função devolve `false` e a bancada falha
     * dizendo isso — em vez de imprimir "0 alucinações" sobre texto irrestrito.
     */
    fun eTrechoContiguo(texto: String, fonte: String): Boolean {
        val saida = palavras(texto)
        if (saida.isEmpty()) return false
        val origem = palavras(fonte)
        if (saida.size > origem.size) return false
        for (inicio in 0..(origem.size - saida.size)) {
            var casou = true
            for (k in saida.indices) {
                if (origem[inicio + k] != saida[k]) {
                    casou = false
                    break
                }
            }
            if (casou) return true
        }
        return false
    }

    /**
     * As palavras da fonte: separadas por espaço em branco, **com a pontuação
     * colada**.
     *
     * Colar a pontuação é o que faz a saída ser citação byte a byte. Separá-la
     * criaria posições de início dentro de `"(dois)"` e permitiria emitir
     * pontuação solta, que o TTS lê como pausa sem palavra.
     */
    private fun palavras(texto: String): List<String> =
        texto.split(ESPACO).filter { it.isNotEmpty() }

    private fun iniciosPermitidos(palavras: List<String>, inicio: Inicio): List<Int> =
        when (inicio) {
            Inicio.QUALQUER_PALAVRA -> palavras.indices.toList()
            Inicio.FRONTEIRA_DE_CLAUSULA -> buildList {
                add(0)
                for (i in 1 until palavras.size) {
                    val anterior = palavras[i - 1]
                    if (anterior.isNotEmpty() && anterior.last() in FRONTEIRA) add(i)
                }
            }
        }

    private fun nome(posicao: Int, profundidade: Int): String = "p$posicao-$profundidade"

    /**
     * O que o `parse_char` do llama.cpp exige dentro de `"…"`: só `\` e `"`
     * precisam de fuga (`llama-grammar.cpp:162`). Acento e `§` passam inteiros —
     * o parser decodifica UTF-8 e compara por ponto de código.
     *
     * O que **não** pode aparecer é quebra de linha, e não pode porque as regras
     * são separadas por `\n`: um literal com `\n` dentro terminaria a regra no
     * meio. Como [palavras] já quebra em qualquer espaço em branco, nenhuma
     * palavra contém uma — mas a troca fica aqui, explícita, para o dia em que
     * alguém mudar o separador.
     */
    private fun escapar(palavra: String): String = buildString(palavra.length + 4) {
        for (c in palavra) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r', '\t' -> append(' ')
                else -> append(c)
            }
        }
    }

    /**
     * **Três palavras, e o número saiu de uma medição, não de gosto.**
     *
     * Com piso 1 (isto é, sem piso), o braço de gramática sobre as 20 perguntas
     * do banco devolveu **7 fragmentos de uma a duas palavras em 20** — todos
     * contíguos, todos aprovados pelo guarda com lastro 1,00, e nenhum deles fala
     * alguma coisa. O piso fecha a saída de emergência que o modelo estava
     * tomando.
     *
     * Três e não mais: em texto de lei, *"Nas mesmas penas"* e *"multa e
     * suspensão"* são respostas legítimas de três palavras, e um piso de cinco as
     * proibiria para comprar pouco.
     */
    const val MINIMO_PADRAO: Int = 3

    private val ESPACO = Regex("""\s+""")

    /** Última letra de uma palavra depois da qual a lei já pontuou. */
    private val FRONTEIRA = charArrayOf('.', ':', ';', ',', '–', '—', '-', ')', '§')
}
