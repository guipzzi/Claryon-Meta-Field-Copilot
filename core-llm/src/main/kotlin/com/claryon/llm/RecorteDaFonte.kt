package com.claryon.llm

/**
 * **O trecho encurtado para caber no prazo — a alavanca de latência da Pista 1.**
 *
 * ## O número que obriga isto a existir
 *
 * Medido no aparelho em 22/08 e registrado em `specs/redacao-por-llm-na-fala.spec.md`:
 * o prefill de **~500 tokens custa 1 620 a 2 550 ms**, contra um prazo de produção
 * de **2 500 ms**. Nas piores execuções o `llama_decode` devolve `2` — abortado —
 * **sem que o prompt tenha entrado**, e a Etapa B fica muda por aritmética de
 * tokens por segundo, não por não saber responder.
 *
 * As 20 perguntas do banco de abordagem carregam trechos de **207 palavras em
 * média**, e a cauda vai a 381 (Art. 244 do CTB). O prompt inteiro é quase todo
 * artigo: instrução e pergunta somam menos de 60 tokens.
 *
 * Então há três saídas conhecidas, e esta é a única que não muda o aceite:
 * encurtar o trecho recuperado, subir o prazo (e sair dos 4 s), ou pipelinar a
 * citação antes da redação.
 *
 * ## O que se perde ao encurtar, dito antes de medir
 *
 * O recorte é feito por **casamento lexical com a pergunta**, que é a mesma
 * heurística — e a mesma cegueira — do `GuardaDaRedacao`: ele não entende o
 * artigo, ele conta raízes em comum. Um parágrafo que responde a pergunta com
 * outras palavras (a exceção do *"salvo se"*, o inciso que remete a outro) pode
 * ficar de fora, e aí o modelo responde certo sobre um pedaço errado.
 *
 * É por isso que o segmento **campeão vem sempre acompanhado do seguinte**
 * quando o orçamento permite: em texto de lei, a condicionante costuma vir logo
 * depois do preceito, e cortar entre os dois é o modo mais barato de inverter o
 * sentido sem inventar palavra nenhuma.
 *
 * A ordem original é preservada. Reordenar por pontuação entregaria ao modelo um
 * artigo que não existe, e a Etapa A perderia o direito de dizer que o conteúdo
 * veio da lei como publicada.
 */
object RecorteDaFonte {

    /**
     * [trecho] reduzido a no máximo [orcamentoDePalavras] palavras, mantendo os
     * segmentos mais próximos de [pergunta] na ordem em que a lei os publicou.
     *
     * Devolve o trecho inteiro quando ele já cabe: recortar o que cabe só
     * introduziria risco sem comprar latência.
     */
    fun para(trecho: String, pergunta: String, orcamentoDePalavras: Int = 70): String {
        require(orcamentoDePalavras >= 1) { "Orçamento abaixo de 1: $orcamentoDePalavras" }
        val inteiro = trecho.trim()
        if (contarPalavras(inteiro) <= orcamentoDePalavras) return inteiro

        val segmentos = segmentar(inteiro)
        if (segmentos.size <= 1) return inteiro

        val raizesDaPergunta = raizes(pergunta)
        val ordenados = segmentos.indices.sortedWith(
            compareByDescending<Int> { nota(segmentos[it], raizesDaPergunta) }.thenBy { it },
        )

        val escolhidos = sortedSetOf<Int>()
        var palavras = 0
        for (i in ordenados) {
            val custo = contarPalavras(segmentos[i])
            if (i in escolhidos) continue
            if (palavras + custo > orcamentoDePalavras && escolhidos.isNotEmpty()) continue
            escolhidos += i
            palavras += custo
            // O vizinho da direita entra junto sempre que couber: em texto de
            // lei a condicionante vem depois do preceito, e separar os dois
            // inverte o sentido sem inventar palavra.
            val vizinho = i + 1
            if (vizinho < segmentos.size && vizinho !in escolhidos) {
                val custoDoVizinho = contarPalavras(segmentos[vizinho])
                if (palavras + custoDoVizinho <= orcamentoDePalavras) {
                    escolhidos += vizinho
                    palavras += custoDoVizinho
                }
            }
            if (palavras >= orcamentoDePalavras) break
        }

        return escolhidos.joinToString(" ") { segmentos[it].trim() }.trim()
    }

    /**
     * Quebra em segmentos por ponto final, ponto e vírgula, dois-pontos e marca
     * de inciso — as fronteiras que a própria redação legislativa usa.
     *
     * **A vírgula fica de fora de propósito.** Ela separa itens dentro do mesmo
     * preceito (*"Possuir, deter, portar, adquirir…"*), e cortar ali produziria
     * fragmentos que não são proposição nenhuma.
     */
    private fun segmentar(texto: String): List<String> {
        val fora = ArrayList<String>()
        val atual = StringBuilder()
        for (c in texto) {
            atual.append(c)
            if (c in FIM_DE_SEGMENTO) {
                fora += atual.toString()
                atual.setLength(0)
            }
        }
        if (atual.isNotBlank()) fora += atual.toString()
        return fora.filter { it.isNotBlank() }
    }

    /**
     * Fração de raízes do segmento que aparecem na pergunta, com um empurrão por
     * densidade: dois segmentos com a mesma fração empatam, e o desempate é o
     * número absoluto de raízes em comum — senão um fragmento de três palavras
     * com uma coincidência ganha do parágrafo que responde.
     */
    private fun nota(segmento: String, raizesDaPergunta: Set<String>): Double {
        val raizes = raizes(segmento)
        if (raizes.isEmpty()) return 0.0
        val comuns = raizes.count { it in raizesDaPergunta }
        return comuns + comuns.toDouble() / raizes.size
    }

    private fun raizes(texto: String): Set<String> =
        normalizar(texto).split(SEPARADOR)
            .filter { it.length >= TAMANHO_DE_PALAVRA_DE_CONTEUDO && it.any(Char::isLetter) }
            .map { it.take(TAMANHO_DA_RAIZ) }
            .toSet()

    private fun contarPalavras(texto: String): Int =
        texto.split(ESPACO).count { it.isNotEmpty() }

    /** As mesmas trocas do [GuardaDaRedacao]: comparar com acento reprova por ortografia. */
    private fun normalizar(texto: String): String = buildString(texto.length) {
        for (c in texto.lowercase()) append(SEM_ACENTO[c] ?: c)
    }

    /** As mesmas constantes do [GuardaDaRedacao] — heurística declarada, não medida. */
    private const val TAMANHO_DE_PALAVRA_DE_CONTEUDO = 5
    private const val TAMANHO_DA_RAIZ = 6

    private val ESPACO = Regex("""\s+""")
    private val SEPARADOR = Regex("""[^\p{L}\p{N}]+""")
    private val FIM_DE_SEGMENTO = charArrayOf('.', ';', ':')

    private val SEM_ACENTO: Map<Char, Char> = buildMap {
        "áàâãä".forEach { put(it, 'a') }
        "éèêë".forEach { put(it, 'e') }
        "íìîï".forEach { put(it, 'i') }
        "óòôõö".forEach { put(it, 'o') }
        "úùûü".forEach { put(it, 'u') }
        put('ç', 'c')
        put('ñ', 'n')
    }
}
