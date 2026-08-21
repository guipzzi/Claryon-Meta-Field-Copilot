package com.claryon.agent

/**
 * **Números ditados, e a diferença entre DÍGITO e QUANTIDADE.**
 *
 * Uma placa não tem número — tem dígitos. `ABC0123` e a quantidade `123` são coisas
 * diferentes, e é aí que um parser ingênuo perde a placa: quem lê "zero um dois
 * três" como o inteiro 123 devolve três caracteres onde havia quatro, e a leitura
 * some sem erro nenhum.
 *
 * Por isso há **duas** portas aqui, e a ordem entre elas é a regra:
 *
 * 1. [digitoSolto] — "zero", "um", …, "nove" e "meia" valem **um caractere cada**,
 *    e nunca se juntam ao vizinho. É como o agente dita placa no rádio.
 * 2. [Grupo] — só quando aparece palavra que **não pode ser dígito** ("mil",
 *    "cento", "duzentos", "trinta"…) é que se monta uma quantidade e ela é
 *    renderizada em dígitos. É o caso de *"placa ABC mil duzentos e trinta e
 *    quatro"*, que um agente de fato fala.
 *
 * A quantidade é renderizada **sem zero à esquerda**, porque é o que ela significa:
 * "mil duzentos e trinta e quatro" são exatamente os quatro dígitos `1234`. Não há
 * como um grupo produzir zero à esquerda, e é justamente por isso que a porta 1
 * existe e vem primeiro.
 *
 * ### "meia" é seis, e é medida de rádio, não folclore
 *
 * Em telefonia e radiocomunicação no Brasil diz-se "meia" (de "meia dúzia") no
 * lugar de "seis", porque "seis" e "três" se confundem em canal ruidoso. Só vale
 * como dígito: "meia hora" não é 6, e quem garante isso não é esta classe — é a
 * exigência de a corrida inteira fechar em 7 caracteres com forma de placa.
 */
internal object NumeroFalado {

    /**
     * Palavras que valem **exatamente um dígito**.
     *
     * "um"/"uma" entram apesar de serem artigo indefinido — o agente dita "um" para
     * o dígito 1 e não há alternativa. O custo disso é falso positivo, e ele é
     * contido pela corrida contígua de 7 com forma de placa, não por esta tabela.
     */
    private val DIGITO: Map<String, Char> = mapOf(
        "zero" to '0',
        "um" to '1', "uma" to '1', "hum" to '1',
        // **`uno` é o 1 obrigatório do Exército e da aviação.** C 24-9 §2-3:
        // "Os algarismos fonéticos utilizados na exploração radiofônica são
        // específicos de nosso País e seu uso é obrigatório para a Força Terrestre,
        // como também para a Aviação Civil e Militar". Idem MCA 100-16 §2.6 do
        // DECEA, e o material da PMERJ/CCI.
        "uno" to '1',
        "dois" to '2', "duas" to '2',
        "tres" to '3',
        "quatro" to '4',
        "cinco" to '5',
        "seis" to '6',
        // Rádio brasileiro: "meia dúzia" → 6. Ver KDoc da classe.
        "meia" to '6',
        "sete" to '7',
        "oito" to '8',
        "nove" to '9',
    ) + ORDINAL

    /**
     * **O sistema ORDINAL — e ele não é curiosidade, é o que a PM manda usar.**
     *
     * A pesquisa de 21/08 achou **dois sistemas de algarismo em uso oficial no
     * Brasil, contraditórios entre si**, e este é o que aparece em ato normativo de
     * polícia. A Portaria n.º 071-CG/15 da PM da Bahia traz, no corpo do artigo, a
     * ditada de uma sequência alfanumérica exatamente como um policial a fala:
     *
     * > *"JNO – 5448 - Juliet; November; Oscar. Quinto; Quarto Dobrado; Oitavo."*
     *
     * Ou seja: `5448` é dito **"quinto, quarto dobrado, oitavo"**. Um extrator que
     * só entendesse "cinco quatro quatro oito" perderia a ditada de um estado
     * inteiro — e perderia calado, porque a corrida simplesmente não se formaria.
     *
     * Mesmo sistema em CBMSC (DtzPOP 12), SAMU Campo Grande (IT/SAMU 12), Escola de
     * Governo do DF, PM-PI e no manual do MJ/SENASP.
     *
     * ### O conflito é real, e está na fala dos dois lados
     *
     * A PMERJ **proíbe** este sistema — *"empregando a designação ordinal de cada
     * algarismo para indicá-los como 'primeiro', 'segundo', 'terceiro'… ficando em
     * desacordo com as normas previstas para a Corporação"* — e manda usar o
     * cardinal com `uno` e `meia`. Os dois estão aqui porque o produto não escolhe a
     * corporação do agente.
     *
     * ### `negativo` = 0, e é o pior nome possível
     *
     * A própria PMBA usa "negativo" como palavra de serviço ("não") no Anexo I e
     * como o algarismo zero no Anexo II. A ambiguidade é da norma, não desta tabela.
     * Aqui ela é resolvida do único jeito honesto: "negativo" só vira `0` se a
     * corrida inteira fechar em sete caracteres com forma de placa — senão não vira
     * nada.
     */
    private val ORDINAL: Map<String, Char>
        get() = mapOf(
            "negativo" to '0', "nulo" to '0',
            "primeiro" to '1', "segundo" to '2', "terceiro" to '3', "quarto" to '4',
            "quinto" to '5', "sexto" to '6', "setimo" to '7', "oitavo" to '8',
            "nono" to '9',
        )

    /**
     * Repetidores. **`dobrado` vem DEPOIS do algarismo; `duplo`/`triplo`, ANTES.**
     *
     * Não é escolha de projeto — é o que as duas normas fazem, e elas discordam:
     *
     * - PMBA: *"5448 - Quinto; **Quarto Dobrado**; Oitavo"* → o `4` dobra para `44`.
     * - PMERJ: *"100 = **Uno DUPLO zero**"*, *"33348 = **TRIPLO três** quatro
     *   oito"*, *"2222 = Dois **TRIPLO dois**"* → o multiplicador precede.
     *
     * Suportar só um dos dois corromperia a placa em silêncio, que é o modo de falha
     * que este arquivo inteiro existe para evitar.
     */
    private val REPETIDOR_ANTES: Map<String, Int> =
        mapOf("duplo" to 2, "dupla" to 2, "triplo" to 3, "tripla" to 3, "quadruplo" to 4)

    private const val REPETIDOR_DEPOIS = "dobrado"

    /** Multiplicador que precede o algarismo ("duplo zero"), ou `null`. */
    fun repetidorAntes(palavra: String): Int? = REPETIDOR_ANTES[palavra]

    /** `true` para "dobrado", que repete o algarismo JÁ dito. */
    fun repeteAnterior(palavra: String): Boolean = palavra == REPETIDOR_DEPOIS

    /** Unidades dentro de uma quantidade (aqui "um" vale 1, não o caractere '1'). */
    private val UNIDADE: Map<String, Int> = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "hum" to 1, "dois" to 2, "duas" to 2,
        "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
        "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezasseis" to 16, "dezessete" to 17, "dezassete" to 17,
        "dezoito" to 18, "dezenove" to 19, "dezanove" to 19,
    )

    /**
     * Palavras que **só** existem como quantidade — nenhuma delas é um dígito.
     *
     * É a presença de uma destas que autoriza a montagem de um grupo. Sem esta
     * separação, "sete zero" viraria 70 e a placa perderia um caractere.
     */
    private val ESCALA: Map<String, Int> = mapOf(
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50,
        "sessenta" to 60, "setenta" to 70, "oitenta" to 80, "noventa" to 90,
        "cem" to 100, "cento" to 100, "duzentos" to 200, "duzentas" to 200,
        "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400,
        "quatrocentas" to 400, "quinhentos" to 500, "quinhentas" to 500,
        "seiscentos" to 600, "seiscentas" to 600, "setecentos" to 700,
        "setecentas" to 700, "oitocentos" to 800, "oitocentas" to 800,
        "novecentos" to 900, "novecentas" to 900,
    )

    private const val MIL = "mil"

    /** `true` se a palavra vale um único dígito. */
    fun digitoSolto(palavra: String): Char? = DIGITO[palavra]

    /**
     * `true` se a palavra **não pode ser um dígito** — e por isso abre um grupo.
     *
     * O critério é esse e nenhum outro: "dez" a "dezenove" entram junto com as
     * dezenas e centenas porque valem dois caracteres, e ninguém dita `19` como um
     * caractere só. "nove" não entra, porque vale exatamente um.
     */
    fun abreGrupo(palavra: String): Boolean =
        palavra == MIL || palavra in ESCALA || (UNIDADE[palavra] ?: 0) >= 10

    /** Palavra que pode continuar um grupo já aberto. */
    private fun continuaGrupo(palavra: String): Boolean =
        palavra == MIL || palavra in ESCALA || palavra in UNIDADE

    /**
     * Lê uma quantidade a partir de [inicio] e devolve os dígitos dela e onde parou.
     *
     * O "e" de ligação é consumido **apenas entre duas palavras de número** — solto,
     * ele é conjunção, e tratá-lo como parte do grupo faria "trinta e a placa" comer
     * a fala seguinte.
     */
    fun grupo(palavras: List<String>, inicio: Int): Grupo? {
        if (inicio >= palavras.size || !abreGrupo(palavras[inicio])) return null

        var total = 0
        var parcial = 0
        var i = inicio
        var leuAlgo = false

        while (i < palavras.size) {
            val p = palavras[i]
            when {
                p == MIL -> {
                    // "mil" sozinho é 1000; "dois mil" é 2000.
                    total += (if (parcial == 0) 1 else parcial) * 1000
                    parcial = 0
                    leuAlgo = true
                    i++
                }
                p in ESCALA -> {
                    parcial += ESCALA.getValue(p)
                    leuAlgo = true
                    i++
                }
                p in UNIDADE -> {
                    parcial += UNIDADE.getValue(p)
                    leuAlgo = true
                    i++
                }
                // "e" só liga se houver número dos dois lados.
                p == "e" && i + 1 < palavras.size && continuaGrupo(palavras[i + 1]) -> i++
                else -> break
            }
        }
        if (!leuAlgo) return null
        return Grupo(digitos = (total + parcial).toString(), proximo = i)
    }

    /** Dígitos que a quantidade produziu e o índice logo após ela. */
    data class Grupo(val digitos: String, val proximo: Int)
}
