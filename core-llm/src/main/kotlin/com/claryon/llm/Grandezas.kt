package com.claryon.llm

/**
 * **Números como o direito os usa: valor + unidade, não sequência de dígitos.**
 *
 * ## Por que este arquivo existe
 *
 * A régua de cifras do [GuardaDaRedacao] comparava `\d+` da saída contra `\d+`
 * da fonte. Medida em 21/08 sobre 268 gerações do Llama 3.2 1B, ela **reprovou
 * zero**, enquanto 25 daquelas gerações inventavam número que a lei não tem. As
 * três causas foram lidas no dado, não supostas:
 *
 *  1. **Dígito comum já está na fonte.** `"1,5 gramas"` vira os tokens `1` e `5`,
 *     e os dois existem em `"§ 1º"` e `"§ 5º"`. A régua olhava para os dígitos
 *     separados e não via o número.
 *  2. **Número por extenso não tem dígito.** `"Quatro quilos configura tráfico
 *     privilegiado"` passava inteiro, porque `\d+` não casa com `quatro`.
 *  3. **Reatribuição usa cifras da fonte trocadas de grandeza.** `"o peso que
 *     separa usuário de traficante é de 500 a 1.500 dias-multa"` — os dois
 *     valores estão no art. 33, e o que está errado é a **unidade** que eles
 *     ganharam na frase.
 *
 * As causas 1 e 2 se fecham lendo o número como **valor** (`1,5` é um número,
 * não dois) e normalizando o extenso. A causa 3 exige a **unidade**, e é por
 * isso que este arquivo não para no valor.
 *
 * ## O que NÃO está aqui, de propósito
 *
 * - **Ordinais** (`primeiro`, `segundo`, `décimo`). Texto de norma é feito de
 *   `§ 1º` e `inciso II`, e o extenso deles aparece em prosa comum
 *   (*"em primeiro lugar"*). Convertê-los encheria a saída de valores que
 *   precisariam de lastro sem nunca terem sido afirmação numérica.
 * - **Romanos.** Mesmo motivo, com o agravante de `I`, `V` e `X` serem letras.
 * - **`meio`/`metade`/`dobro`.** São operadores sobre outro número, e resolver
 *   isso é aritmética, não leitura.
 *
 * Cada exclusão custa recall e está declarada. Nenhuma delas foi escolhida por
 * ser difícil: as três produzem valor onde o texto não afirmou quantidade.
 */
internal object Grandezas {

    /**
     * A classe da unidade, e não a unidade em si.
     *
     * O que a régua precisa saber é se `"500 dias-multa"` e `"500 gramas"` são a
     * mesma afirmação — e não são, ainda que o valor seja idêntico. Comparar por
     * classe (e não por token) faz `"quilos"` e `"kg"` casarem sem uma tabela de
     * sinônimos por unidade.
     */
    enum class Classe {
        TEMPO,
        MASSA,
        VOLUME,
        DINHEIRO,
        DIAS_MULTA,
        PONTOS,
        VELOCIDADE,
        DISTANCIA,
        PERCENTUAL,
        VEZES,
        CONCENTRACAO,
        MUNICAO,
    }

    /**
     * Um número afirmado no texto. [classe] é `null` quando o número aparece sem
     * unidade reconhecida — `"Art. 33"`, `"§ 1º"`, `"Lei 11.343"`.
     */
    data class Grandeza(val valor: Double, val classe: Classe?) {
        override fun toString(): String {
            val v = if (valor == valor.toLong().toDouble()) valor.toLong().toString() else valor.toString()
            return if (classe == null) v else "$v ${classe.name.lowercase()}"
        }
    }

    // ------------------------------------------------------------------ léxico

    private val CARDINAIS: Map<String, Long> = buildMap {
        put("zero", 0); put("um", 1); put("uma", 1); put("dois", 2); put("duas", 2)
        put("tres", 3); put("quatro", 4); put("cinco", 5); put("seis", 6); put("sete", 7)
        put("oito", 8); put("nove", 9); put("dez", 10); put("onze", 11); put("doze", 12)
        put("treze", 13); put("quatorze", 14); put("catorze", 14); put("quinze", 15)
        put("dezesseis", 16); put("dezasseis", 16); put("dezessete", 17); put("dezassete", 17)
        put("dezoito", 18); put("dezenove", 19); put("dezanove", 19)
        put("vinte", 20); put("trinta", 30); put("quarenta", 40); put("cinquenta", 50)
        put("cinquenta", 50); put("sessenta", 60); put("setenta", 70); put("oitenta", 80)
        put("noventa", 90)
        put("cem", 100); put("cento", 100); put("duzentos", 200); put("duzentas", 200)
        put("trezentos", 300); put("trezentas", 300); put("quatrocentos", 400); put("quatrocentas", 400)
        put("quinhentos", 500); put("quinhentas", 500); put("seiscentos", 600); put("seiscentas", 600)
        put("setecentos", 700); put("setecentas", 700); put("oitocentos", 800); put("oitocentas", 800)
        put("novecentos", 900); put("novecentas", 900)
    }

    private const val MIL = 1_000L
    private const val MILHAO = 1_000_000L

    private val MULTIPLICADORES: Map<String, Long> =
        mapOf("mil" to MIL, "milhao" to MILHAO, "milhoes" to MILHAO)

    /**
     * Unidades por classe. Tokens de **uma letra** (`g`, `l`, `m`) ficam de fora:
     * o custo de casar `"a"` de artigo com litro é maior que o de perder `"5 g"`,
     * que texto de norma brasileiro não escreve assim.
     */
    private val UNIDADES: Map<String, Classe> = buildMap {
        listOf("ano", "anos", "mes", "meses", "dia", "dias", "hora", "horas",
            "minuto", "minutos", "semana", "semanas")
            .forEach { put(it, Classe.TEMPO) }
        listOf("grama", "gramas", "quilo", "quilos", "quilograma", "quilogramas", "kg",
            "miligrama", "miligramas", "mg", "decigrama", "decigramas",
            "tonelada", "toneladas")
            .forEach { put(it, Classe.MASSA) }
        listOf("litro", "litros", "mililitro", "mililitros", "ml")
            .forEach { put(it, Classe.VOLUME) }
        listOf("real", "reais", "centavo", "centavos", "salario", "salarios")
            .forEach { put(it, Classe.DINHEIRO) }
        listOf("dias-multa", "dia-multa", "diasmulta")
            .forEach { put(it, Classe.DIAS_MULTA) }
        listOf("ponto", "pontos").forEach { put(it, Classe.PONTOS) }
        listOf("km/h", "kmh").forEach { put(it, Classe.VELOCIDADE) }
        listOf("metro", "metros", "quilometro", "quilometros", "km")
            .forEach { put(it, Classe.DISTANCIA) }
        listOf("%", "porcento").forEach { put(it, Classe.PERCENTUAL) }
        listOf("vez", "vezes").forEach { put(it, Classe.VEZES) }
        listOf("mg/l", "dg/l", "g/l").forEach { put(it, Classe.CONCENTRACAO) }
        listOf("cartucho", "cartuchos", "municao", "municoes", "projetil", "projeteis")
            .forEach { put(it, Classe.MUNICAO) }
    }

    /** Ligações que podem separar o número da sua unidade sem quebrar o vínculo. */
    private val PONTES = setOf("de", "do", "da", "a", "ate", "e", "ou", "por", "no", "na", "em")

    // ------------------------------------------------------------------ extração

    /**
     * Todas as grandezas afirmadas em [texto], em ordem de aparição.
     *
     * O texto deve chegar **normalizado** (minúsculas, sem acento) — quem chama é
     * o [GuardaDaRedacao], que já normaliza para as outras réguas.
     */
    fun extrair(texto: String): List<Grandeza> {
        val tokens = tokenizar(texto)
        val brutas = ArrayList<Pair<Int, Grandeza>>()  // índice do token final + grandeza

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val valorDigito = comoDigito(t)
            if (valorDigito != null) {
                brutas += i to Grandeza(valorDigito, null)
                i++
                continue
            }
            if (t in CARDINAIS || t in MULTIPLICADORES) {
                val (valor, fim) = lerExtenso(tokens, i)
                if (valor != null) {
                    brutas += (fim - 1) to Grandeza(valor.toDouble(), null)
                    i = fim
                    continue
                }
            }
            i++
        }

        // Unidade: primeiro token de unidade depois do número, atravessando
        // pontes, parênteses e o extenso entre parênteses ("6 (seis) decigramas").
        val comUnidade = brutas.map { (fim, g) ->
            var j = fim + 1
            var passos = 0
            var classe: Classe? = null
            while (j < tokens.size && passos < 5) {
                val t = tokens[j]
                val u = UNIDADES[t]
                if (u != null) { classe = u; break }
                val ponte = t in PONTES || t in CARDINAIS || t in MULTIPLICADORES ||
                    comoDigito(t) != null && tokens.getOrNull(j - 1) in PONTES
                if (!ponte) break
                j++; passos++
            }
            g.copy(classe = classe)
        }

        // Faixa: "de 500 a 1.500 dias-multa" — o primeiro número herda a unidade
        // do segundo. Sem isto, metade de toda faixa legal fica sem classe, e a
        // reatribuição de grandeza (causa 3) escapa pela borda de baixo.
        val propagada = comUnidade.toMutableList()
        for (k in propagada.indices.reversed()) {
            if (propagada[k].classe == null && k + 1 < propagada.size) {
                val prox = propagada[k + 1]
                if (prox.classe != null) propagada[k] = propagada[k].copy(classe = prox.classe)
            }
        }
        return propagada
    }

    /** Só os valores, sem unidade. Serve à régua de valor, que é a mais barata. */
    fun valores(texto: String): Set<Double> = extrair(texto).map { it.valor }.toSet()

    // ------------------------------------------------------------------ internos

    /**
     * Separa em tokens preservando `dias-multa`, `km/h` e `mg/l` inteiros — são
     * unidades cujo separador é pontuação, e quebrá-las apagaria a classe.
     */
    private fun tokenizar(texto: String): List<String> =
        TOKEN.findAll(texto).map { it.value }.toList()

    /**
     * `"1.500"` → 1500 · `"293,47"` → 293.47 · `"0,3"` → 0.3.
     *
     * Ponto é separador de milhar e vírgula é decimal, que é a convenção do texto
     * legal brasileiro. `"11.343"` (a Lei de Drogas) vira 11343, e é assim que ela
     * também aparece escrita na fonte — os dois lados passam pela mesma leitura,
     * então a comparação continua válida.
     */
    private fun comoDigito(t: String): Double? {
        if (t.isEmpty() || !t[0].isDigit()) return null
        if (!t.all { it.isDigit() || it == '.' || it == ',' }) return null
        val semMilhar = if (MILHAR.matches(t.substringBefore(','))) {
            t.replace(".", "")
        } else {
            t
        }
        return semMilhar.replace(',', '.').toDoubleOrNull()
    }

    /** `"mil e quinhentos"` → 1500, devolvendo o índice do primeiro token não numérico. */
    private fun lerExtenso(tokens: List<String>, inicio: Int): Pair<Long?, Int> {
        var total = 0L
        var atual = 0L
        var i = inicio
        var leuAlgo = false
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "e" && leuAlgo) {
                // "e" só é ponte se o próximo token continuar o número.
                val prox = tokens.getOrNull(i + 1)
                if (prox != null && (prox in CARDINAIS || prox in MULTIPLICADORES)) { i++; continue }
                break
            }
            val mult = MULTIPLICADORES[t]
            if (mult != null) {
                total += (if (atual == 0L) 1L else atual) * mult
                atual = 0L
                leuAlgo = true
                i++
                continue
            }
            val card = CARDINAIS[t] ?: break
            atual += card
            leuAlgo = true
            i++
        }
        if (!leuAlgo) return null to inicio + 1
        return (total + atual) to i
    }

    private val TOKEN = Regex("""\d[\d.,]*|[a-z]+(?:[-/][a-z]+)+|[a-z]+|%""")
    private val MILHAR = Regex("""\d{1,3}(\.\d{3})+""")
}
