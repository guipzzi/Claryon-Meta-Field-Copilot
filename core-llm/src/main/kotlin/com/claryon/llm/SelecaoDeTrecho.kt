package com.claryon.llm

/**
 * **O mesmo produto da Pista 2, sem modelo nenhum — o controle que decide se o
 * LLM está pagando o próprio custo.**
 *
 * ## Por que este arquivo existe
 *
 * Com a gramática de [GramaticaDaFonte] na cadeia, a saída do modelo é
 * obrigatoriamente **um trecho contíguo da fonte, começando numa fronteira de
 * cláusula, com no máximo N palavras**. O conjunto do que ele *pode* dizer é
 * pequeno, finito e enumerável sem inferência nenhuma: são as posições de início
 * vezes os comprimentos.
 *
 * O que o modelo faz, então, não é redigir nem saber: é **escolher um item de uma
 * lista**. E escolher item de lista por casamento léxico é coisa que uma função
 * de 40 linhas faz em microssegundos, sem 807 MB residentes, sem prefill e sem
 * prazo.
 *
 * Este objeto é essa função. Ele não existe para substituir a Pista 2 — existe
 * para **medi-la contra o preço dela**. Se a coluna `utilizáveis` do braço com
 * modelo não for melhor que a deste, a conclusão honesta não é "a gramática
 * funcionou": é que a gramática funcionou e o modelo não estava contribuindo.
 *
 * O `CLAUDE.md` §6 pede contra-teste para toda afirmação de capacidade. Este é o
 * contra-teste da Pista 2 inteira.
 *
 * ## A heurística é declarada, e é deliberadamente simples
 *
 * Nota de um trecho = quantas **raízes distintas da pergunta** ele contém, menos
 * uma penalidade linear pelo comprimento. Nada de IDF, nada de pesos ajustados ao
 * banco: um baseline afinado no mesmo banco em que é comparado deixa de ser
 * baseline e vira concorrente treinado no teste.
 *
 * A penalidade existe porque, sem ela, a nota é monótona no comprimento e o
 * vencedor é sempre o trecho de N palavras — o teto vira a resposta.
 *
 * O piso de [MINIMO_DE_PALAVRAS] existe porque uma palavra solta não é fala: o
 * agente ouve *"Penalidade"* e não recebeu informação nenhuma.
 */
object SelecaoDeTrecho {

    /**
     * O trecho contíguo de [fonte] que melhor casa com [pergunta], ou `null`
     * quando não há candidato.
     *
     * O espaço de busca é **exatamente** o que [GramaticaDaFonte] admite com os
     * mesmos parâmetros — se os dois divergirem, a comparação deixa de ser entre
     * "com modelo" e "sem modelo" e passa a ser entre duas jaulas diferentes.
     */
    fun escolher(
        fonte: String,
        pergunta: String,
        maximoDePalavras: Int = 7,
        inicio: GramaticaDaFonte.Inicio = GramaticaDaFonte.Inicio.FRONTEIRA_DE_CLAUSULA,
    ): String? {
        val palavras = fonte.split(ESPACO).filter { it.isNotEmpty() }
        if (palavras.isEmpty()) return null
        val inicios = iniciosPermitidos(palavras, inicio)
        if (inicios.isEmpty()) return null

        val daPergunta = raizes(pergunta)
        var melhor: String? = null
        var melhorNota = Double.NEGATIVE_INFINITY

        for (i in inicios) {
            val vistas = HashSet<String>()
            var casadas = 0
            for (n in 1..maximoDePalavras) {
                val fim = i + n
                if (fim > palavras.size) break
                val nova = raiz(palavras[fim - 1])
                if (nova != null && vistas.add(nova) && nova in daPergunta) casadas++
                if (n < MINIMO_DE_PALAVRAS) continue
                val nota = casadas - PENALIDADE_POR_PALAVRA * n
                if (nota > melhorNota) {
                    melhorNota = nota
                    melhor = palavras.subList(i, fim).joinToString(" ")
                }
            }
        }
        return melhor
    }

    private fun iniciosPermitidos(
        palavras: List<String>,
        inicio: GramaticaDaFonte.Inicio,
    ): List<Int> = when (inicio) {
        GramaticaDaFonte.Inicio.QUALQUER_PALAVRA -> palavras.indices.toList()
        GramaticaDaFonte.Inicio.FRONTEIRA_DE_CLAUSULA -> buildList {
            add(0)
            for (i in 1 until palavras.size) {
                val anterior = palavras[i - 1]
                if (anterior.isNotEmpty() && anterior.last() in FRONTEIRA) add(i)
            }
        }
    }

    private fun raizes(texto: String): Set<String> =
        normalizar(texto).split(SEPARADOR)
            .filter { it.length >= TAMANHO_DE_PALAVRA_DE_CONTEUDO && it.any(Char::isLetter) }
            .map { it.take(TAMANHO_DA_RAIZ) }
            .toSet()

    private fun raiz(palavra: String): String? {
        val limpa = normalizar(palavra).split(SEPARADOR).firstOrNull { it.length >= 1 } ?: return null
        return if (limpa.length >= TAMANHO_DE_PALAVRA_DE_CONTEUDO && limpa.any(Char::isLetter)) {
            limpa.take(TAMANHO_DA_RAIZ)
        } else {
            null
        }
    }

    private fun normalizar(texto: String): String = buildString(texto.length) {
        for (c in texto.lowercase()) append(SEM_ACENTO[c] ?: c)
    }

    /** As mesmas do [GuardaDaRedacao] e do [RecorteDaFonte]: heurística declarada. */
    private const val TAMANHO_DE_PALAVRA_DE_CONTEUDO = 5
    private const val TAMANHO_DA_RAIZ = 6

    /** Uma palavra solta não é fala. `"Penalidade"` não informa nada a quem ouve. */
    const val MINIMO_DE_PALAVRAS: Int = 3

    /**
     * Sem penalidade a nota é monótona no comprimento e o vencedor é sempre o
     * trecho do tamanho do teto. `0,03` é declarado, não ajustado: em 12 palavras
     * ele vale 0,36, ou seja, empate de raízes desempata pelo mais curto e uma
     * raiz a mais sempre ganha do comprimento.
     */
    private const val PENALIDADE_POR_PALAVRA = 0.03

    private val ESPACO = Regex("""\s+""")
    private val SEPARADOR = Regex("""[^\p{L}\p{N}]+""")
    private val FRONTEIRA = charArrayOf('.', ':', ';', ',', '–', '—', '-', ')', '§')

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
