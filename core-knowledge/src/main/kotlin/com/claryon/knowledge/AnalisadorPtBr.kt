package com.claryon.knowledge

import java.text.Normalizer

/**
 * **Transforma texto em termos comparáveis.** É o mesmo analisador para o corpus
 * e para a pergunta — se os dois divergirem, nada casa, e a falha é silenciosa:
 * o sistema apenas recusa tudo, e "recusa tudo" parece prudência.
 *
 * Duas decisões, e as duas foram medidas:
 *
 *  1. **Dobra acento e caixa.** O STT devolve `"trafico"` onde a lei escreve
 *     `"tráfico"`. Comparar byte a byte perderia o artigo por um acento que o
 *     agente não pronunciou de forma diferente.
 *  2. **Indexa n-gramas de [NGRAMA] caracteres, não palavras.** O `_` de borda
 *     impede que o n-grama de uma palavra se confunda com o da vizinha.
 *
 * ## O n-grama resolve os DOIS problemas, e é por isso que ele fica sozinho
 *
 * O português flexiona muito e a lei quase sempre usa o infinitivo
 * (*"Recusar-se a ser submetido…"*) enquanto o agente usa o pretérito (*"o cara
 * recusou"*). E o STT erra letras: `"altorizacao"` por `"autorizacao"`. Os dois
 * casos são a mesma coisa para um 4-grama — `recus`/`recusa` dividem `_rec`,
 * `recu`, `ecus`; `autorizacao`/`altorizacao` dividem seis de oito.
 *
 * ## O que a medição desmentiu
 *
 * O desenho original truncava a palavra num prefixo de [PREFIXO] caracteres
 * **além** dos n-gramas, com a ideia de que uma cuidaria da flexão e a outra do
 * erro de transcrição. `osParametrosDoIndiceSaoMedidos` rodou as três
 * configurações sobre as 88 perguntas e o prefixo **não paga o próprio lugar**:
 *
 * | analisador | recall@1 | recall@3 | no limiar: certo / errado / fora |
 * |---|---|---|---|
 * | só prefixo | 39/88 (44,3%) | 58/88 | 20 / 7 / **2 de 12** |
 * | **só n-grama** | **46/88 (52,3%)** | **60/88** | 18 / 5 / **0 de 12** |
 * | os dois juntos | 43/88 (48,9%) | 58/88 | 18 / 5 / 0 de 12 |
 *
 * Somar o prefixo **piora** — e piora nas três partições do conjunto, não em
 * uma. A leitura é que o prefixo é um n-grama pior: casa o começo da palavra e
 * ignora o resto, então `"port"` de *portar* casa `"portaria"` com o mesmo peso.
 * Ele fica no código atrás de [comPrefixo], desligado, só para essa afirmação
 * continuar falseável.
 */
internal object AnalisadorPtBr {

    /**
     * Onde a palavra seria truncada, **se o prefixo estivesse ligado — e ele
     * não está.** Medido pior que o n-grama sozinho; ver o KDoc da classe.
     */
    const val PREFIXO = 5

    /** Tamanho do n-grama de caractere. Medido: 3 e 4 empatam; 5 piora o topo. */
    const val NGRAMA = 4

    /**
     * Palavras que aparecem em quase todo artigo e em quase toda pergunta.
     * Mantê-las custaria escore em documentos que só têm preposição em comum.
     *
     * Inclui **vocabulário de fórmula jurídica** (`art`, `paragrafo`, `inciso`,
     * `redacao`, `vide`): ele está em milhares de trechos e não distingue
     * nenhum deles. E inclui **vocabulário de pergunta** (`posso`, `pode`,
     * `quando`, `como`): o agente começa quase toda frase com um deles.
     */
    private val VAZIAS: Set<String> = setOf(
        "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das",
        "em", "no", "na", "nos", "nas", "por", "para", "pela", "pelo", "pelas",
        "pelos", "com", "sem", "sob", "sobre", "entre", "ate", "ou", "mas",
        "que", "se", "ao", "aos", "seu", "sua", "seus", "suas", "este", "esta",
        "estes", "estas", "esse", "essa", "esses", "essas", "aquele", "aquela",
        "aqueles", "aquelas", "isso", "isto", "aquilo", "ser", "sera", "serao",
        "foi", "sao", "ha", "havera", "tem", "tera", "nao", "sim", "quando",
        "onde", "como", "qual", "quais", "quem", "eu", "tu", "ele", "ela",
        "nos", "vos", "eles", "elas", "me", "te", "lhe", "lhes", "meu", "minha",
        "teu", "tua", "mais", "menos", "muito", "pouco", "todo", "toda",
        "todos", "todas", "outro", "outra", "outros", "outras", "mesmo",
        "mesma", "ja", "tambem", "so", "somente", "apenas", "depois", "antes",
        "durante", "desde", "art", "artigo", "paragrafo", "inciso", "alinea",
        "caput", "vide", "redacao", "dada", "numero", "posso", "pode", "podem",
        "devo", "deve", "fazer", "faco", "faz", "cara", "sujeito", "pra", "ai",
        "la",
    )

    private val ACENTO = Regex("\\p{Mn}+")
    private val NAO_ALFANUM = Regex("[^a-z0-9]+")

    /**
     * Numeral romano de inciso — `II`, `XXIII`, `IV`.
     *
     * **Não é purismo: era um falso aceite acima do limiar.** Os incisos estão
     * em quase todo artigo longo, e os altos (`XXX`, `XXVII`) aparecem em dois
     * ou três trechos no corpus inteiro — o que dá a eles um IDF altíssimo. A
     * pergunta de teste `"zzz qqq xxx"`, que não é português nem lei, casava com
     * `"XXX - mobilidade urbana"` do art. 10 do CTB e passava a porta com
     * confiança **0,374**, contra um limiar de 0,30. Filtrando: 0,000.
     *
     * A gramática é a **estrita**, não `[ivxlcdm]+`, e a diferença importa:
     * `civil` (48 ocorrências), `mil` (40) e `mim` são compostas só de letras
     * romanas e **não** são numerais romanos válidos. A forma frouxa apagaria as
     * três do índice — e "civil" é palavra de trabalho neste corpus.
     *
     * Os `0,374` foram medidos com o analisador da época (prefixo + n-grama);
     * com o de hoje a mesma pergunta dá **0,000**, e é isso que
     * `perguntaVaziaOuSoRuidoViraRecusa` confere. O filtro não custou recall.
     */
    private val NUMERAL_ROMANO =
        Regex("^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")

    /**
     * Minúsculas, sem acento, sem pontuação — a forma em que corpus e pergunta
     * se encontram. Público para o teste conseguir provar a dobra de acento sem
     * passar por todo o índice.
     */
    fun normalizar(texto: String): String =
        ACENTO.replace(Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD), "")
            .let { NAO_ALFANUM.replace(it, " ") }
            .trim()

    /** As palavras que sobram depois de normalizar e tirar as vazias. */
    fun palavras(texto: String): List<String> =
        normalizar(texto)
            .split(' ')
            .filter { it.length >= 2 && it !in VAZIAS && !NUMERAL_ROMANO.matches(it) }

    /**
     * Os termos indexáveis: o prefixo de cada palavra **mais** os n-gramas dela.
     *
     * Devolve `List`, não `Set`: a repetição é informação. Um artigo que diz
     * "arma de fogo" seis vezes é mais sobre arma de fogo que um que diz uma —
     * e é o BM25 quem decide o quanto isso vale, com saturação.
     *
     * [comPrefixo] **é `false` por padrão**: medido, ele piora o recall (ver o
     * KDoc da classe). O parâmetro só continua existindo para que o contra-teste
     * possa ligá-lo e provar que a afirmação ainda vale — número de KDoc sem
     * teste que o refaça é número que envelhece calado.
     */
    fun termos(texto: String, comPrefixo: Boolean = false, comNgrama: Boolean = true): List<String> {
        val fora = ArrayList<String>()
        for (p in palavras(texto)) {
            if (comPrefixo) fora += p.take(PREFIXO)
            if (!comNgrama) continue
            val cercada = "_${p}_"
            if (cercada.length <= NGRAMA) {
                fora += cercada
            } else {
                for (i in 0..cercada.length - NGRAMA) fora += cercada.substring(i, i + NGRAMA)
            }
        }
        return fora
    }
}
