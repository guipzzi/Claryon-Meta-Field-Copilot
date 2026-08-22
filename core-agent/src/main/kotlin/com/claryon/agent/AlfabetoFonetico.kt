package com.claryon.agent

/**
 * **A tabela de soletração — e por que ela não é a da OTAN, e só ela.**
 *
 * O agente dita *"tango bravo unido três delta sete zero"*. As três primeiras
 * palavras são da OTAN; a quarta, **"unido"**, não é — e não foi encontrada em fonte
 * nenhuma (ver "Proveniência" abaixo). Rádio no Brasil corre sobre **dois**
 * alfabetos distintos, mais o nome das letras em português, e uma tabela que
 * aceitasse só a forma internacional perderia ditada real.
 *
 * Por isso cada letra aceita **um conjunto** de palavras. O custo de aceitar
 * variante a mais é falso positivo, e ele não é pago aqui: é pago pela corrida
 * contígua de sete com forma de placa, em [PlacaDitada]. Esta tabela pode ser
 * generosa **porque** o portão está depois dela — e o quanto ela custa está medido
 * em `BancoDePlacasDitadasTest` e `PlacaEmCorpusRealTest`.
 *
 * ---
 *
 * ## Proveniência — de onde veio cada grupo
 *
 * ### 1. Internacional (OTAN/ICAO) — é o que a polícia brasileira usa
 *
 * `alfa bravo charlie delta echo foxtrot golf hotel india juliett kilo lima mike
 * november oscar papa quebec romeo sierra tango uniform victor whiskey x-ray yankee
 * zulu` — grafia oficial confirmada em `nato.int`, com `Alfa`, `Juliett`, `Whiskey`
 * e `X-ray` escritos assim de propósito.
 *
 * Que o Brasil o adota, e em qual norma, está em quatro instrumentos:
 *
 * - **MCA 100-16/2021 §2.5**, DECEA / Comando da Aeronáutica — *"Quando for
 *   necessário soletrar, em radiotelefonia, […] usa-se o alfabeto fonético que se
 *   apresenta a seguir"*.
 * - **C 24-9 §2-2**, Exército Brasileiro, *Exploração em Radiotelefonia* — *"O
 *   alfabeto fonético utilizado na exploração é internacional e seu uso está
 *   consagrado pelas Forças Armadas."*
 * - **MD33-M-02 §3.5**, Ministério da Defesa / EMCFA.
 * - **ANATEL Res. 449/2006 Art. 43**, para radioamador (permissiva, sem tabela).
 *
 * E que a **polícia** o usa está em ato normativo vinculante: **Portaria n.º
 * 071-CG/15 da PM da Bahia**, Art. 1º — *"É obrigatória a utilização do código
 * fonético previsto nos Anexos I e II desta portaria por parte de todos os policiais
 * militares, quando das comunicações via rádio"*. Idem CBMSC (DtzPOP 12), SAMU
 * Campo Grande (IT/SAMU 12), Escola de Governo do DF, MJ/SENASP, PM-PI, PM-PB,
 * PMERJ/CCI.
 *
 * ### 2. As grafias abrasileiradas que circulam no material policial
 *
 * `eco` (E) — CBMSC DtzPOP 12 e SAMU-CG · `fox` (F) — MJ/SENASP, *"FOX ou
 * FOXTROT"* · `whisky` (W) — MJ/SENASP e SAMU-CG · `xadrez` (X) — PMBA, ato
 * normativo · `indian` (I) e `whiskei` (W) — CBMSC 2021.
 *
 * ### 3. O alfabeto "nacional" da Marinha — **rejeitado**, ver [NACIONAL_REJEITADO]
 *
 * ### 4. Nome das letras em português
 *
 * `bê cê dê éfe agá jota éle ême êne pê quê érre ésse tê vê dáblio xis ípsilon zê`
 * — ortografia corrente do pt-BR. Cobre a ditada soletrada, *"a bê cê um dois três
 * quatro"*, que é como se fala quando o canal está limpo. A letra sozinha ("a",
 * "b", "s") vale a si mesma, e isso é regra, não tabela: ver [letra].
 *
 * ### 5. As formas em português — a maioria documentada, e o resto rotulado
 *
 * Ver [ABRASILEIRADO]. O que **não** está enumerado ali é corrupção de STT
 * ("tangu", "bravu"): para isso existe [letraAproximada], porque enumerar erro de
 * modelo à mão não escala — a mesma lição que [ChaveFonetica] registra para a
 * palavra de ativação.
 *
 * ### 6. `unido` — a entrada sem fonte, e ela está marcada
 *
 * **Não foi encontrada em fonte alguma**, em duas pesquisas independentes. Toda
 * fonte dá *Uniform* (escrito) ou *Uniforme* (falado) para U — a PMERJ inclusive
 * registra "uniforme" como a pronúncia habitual da corporação. As hipóteses para a
 * origem de "unido" são duas: ou é "uniforme" cortado pelo PTT e pelo ruído, ou é
 * contaminação do alfabeto de **Portugal**, onde U = *Unidade*.
 *
 * `unido` fica, por um motivo só: é a palavra do exemplo de aceite em
 * `specs/consulta-de-placa-por-camera.spec.md` linha 85. E fica **rotulada**, porque
 * a regra da casa é que variante plausível sem prova alarga o portão sem que ninguém
 * note — ver o KDoc de `DeterministicIntentRouter.GRAFIAS_DE_GUARNICAO`.
 *
 * Pelo mesmo motivo **`roma` (R) NÃO entrou**: procurada, não achada em fonte
 * brasileira nenhuma — *Roma* é do alfabeto telefônico **italiano**. O que existe, e
 * está aqui, é `romeu`, que é oficial (DECEA, CBMSC).
 */
object AlfabetoFonetico {

    /** Grupo 1 e 2: internacional (OTAN/ICAO) e as grafias abrasileiradas dele. */
    private val INTERNACIONAL: Map<Char, List<String>> = mapOf(
        'A' to listOf("alfa", "alpha"),
        'B' to listOf("bravo"),
        'C' to listOf("charlie"),
        'D' to listOf("delta"),
        'E' to listOf("echo", "eco"),
        'F' to listOf("foxtrot", "fox"),
        'G' to listOf("golf"),
        'H' to listOf("hotel"),
        'I' to listOf("india", "indian"),
        'J' to listOf("juliett", "juliet", "juliette", "juliete"),
        'K' to listOf("kilo"),
        'L' to listOf("lima"),
        'M' to listOf("mike"),
        'N' to listOf("november"),
        'O' to listOf("oscar"),
        'P' to listOf("papa"),
        'Q' to listOf("quebec"),
        'R' to listOf("romeo"),
        'S' to listOf("sierra"),
        'T' to listOf("tango"),
        'U' to listOf("uniform"),
        'V' to listOf("victor"),
        'W' to listOf("whiskey", "whisky", "whiskei"),
        // **`xadrez` é obrigatório por ato normativo, não é folclore.** A Portaria
        // 071-CG/15 da PM da Bahia torna o código fonético do Anexo II de uso
        // obrigatório, e lá X é `XADREZ`. O manual do MJ/SENASP registra
        // "X-RAY, XINGU ou XADREZ". Quem só aceitasse `xray` perderia a ditada de
        // um estado inteiro.
        'X' to listOf("xray", "xrey", "xadrez", "xingu"),
        'Y' to listOf("yankee"),
        'Z' to listOf("zulu"),
    )

    /**
     * Grupo 3 — **REJEITADO pela pesquisa, e fica escrito por quê.**
     *
     * O "alfabeto fonético nacional" (`afir bala cruz dedo elmo face gato hora inte
     * joia kilo luar maré nega onda prep quer rato solo tupi urso viga veve xara yole
     * zaga`) chegou a entrar aqui. Saiu por três achados:
     *
     * 1. É o alfabeto **pré-1949 da Marinha**, substituído pelo da ICAO entre 1949 e
     *    1956. `AFIR`, `INTE`, `NEGA` e `PREP` são as bandeiras de governo do Código
     *    Internacional de Sinais (Afirm, Int, Negat, Prep) — a assinatura de um
     *    alfabeto naval antigo, não de rádio policial.
     * 2. A afirmação de que "algumas instituições militares estaduais o usam" **não
     *    tem fonte**: a lista de 26 letras circula a partir de um único post de blog
     *    de 2012, copiado literalmente desde então. Nenhum `.gov.br` ou `.mil.br` tem
     *    a lista completa.
     * 3. **Todo documento policial e de bombeiros consultado usa o internacional** —
     *    PMBA (Portaria 071-CG/15, que é ato normativo vinculante), CBMSC, SAMU,
     *    PM-PI, PM-PB, PMERJ, EGov-DF, MJ/SENASP.
     *
     * E o custo de tê-lo era concreto: 26 substantivos comuns do português ("hora",
     * "cruz", "gato", "onda", "rato", "solo", "face") viravam letra. Com ele ligado,
     * a varredura dos 1817 trechos de lei em `PlacaEmCorpusRealTest` **fabricou uma
     * placa**; sem ele, zero. Foi a medição que decidiu, não o argumento.
     */
    private val NACIONAL_REJEITADO: Map<Char, List<String>> = emptyMap()

    /** Grupo 4: o nome das letras em português, já sem acento. */
    private val NOME_DA_LETRA: Map<Char, List<String>> = mapOf(
        'B' to listOf("be"), 'C' to listOf("ce"), 'D' to listOf("de"),
        'F' to listOf("efe"), 'G' to listOf("ge"), 'H' to listOf("aga"),
        'J' to listOf("jota"), 'K' to listOf("ka"), 'L' to listOf("ele"),
        'M' to listOf("eme"), 'N' to listOf("ene"), 'P' to listOf("pe"),
        'Q' to listOf("que"), 'R' to listOf("erre"), 'S' to listOf("esse"),
        'T' to listOf("te"), 'V' to listOf("ve"), 'W' to listOf("dablio"),
        'X' to listOf("xis"), 'Y' to listOf("ipsilon"), 'Z' to listOf("ze"),
    )

    /**
     * Grupo 5: as formas em português.
     *
     * `romeu`, `uniforme`, `vitor`, `ianque`, `uisqui` e `quebeque` **não são
     * hipótese** — estão em documento institucional: `ROMEU` na MCA 100-16/2021 do
     * DECEA §2.5 e na DtzPOP 12 do CBMSC; `UNIFORME` e `VITOR` no material da
     * PMERJ/CCI, que inclusive registra "uniforme" como a pronúncia corrente da
     * corporação; `IANQUE` no manual do MJ/SENASP.
     *
     * `golfe`, `quilo` e `novembro` **são** hipótese sobre a saída do whisper pt-BR,
     * e estão rotuladas como tal.
     *
     * **`serra` foi tirado daqui.** Era hipótese minha para "sierra", e a medição
     * mostrou o preço: a chave fonética colapsa o `rr`, e "será" — verbo corrente —
     * passou a valer S. Ver `PlacaDitadaTest.palavraComumQueSoaComoAlfabeto`.
     */
    private val ABRASILEIRADO: Map<Char, List<String>> = mapOf(
        'G' to listOf("golfe"),
        'K' to listOf("quilo"),
        'N' to listOf("novembro"),
        'Q' to listOf("quebeque"),
        'R' to listOf("romeu"),
        'U' to listOf("uniforme"),
        'V' to listOf("vitor"),
        'W' to listOf("uisqui", "uisque"),
        'Y' to listOf("ianque"),
    )

    /** Grupo 6: a entrada sem fonte, isolada para não se perder de vista. */
    private val SEM_FONTE: Map<Char, List<String>> = mapOf('U' to listOf("unido"))

    /**
     * Letra → as palavras que a representam, já normalizadas. A primeira é a
     * canônica.
     */
    private val PALAVRAS: Map<Char, List<String>> = ('A'..'Z').associateWith { l ->
        listOf(INTERNACIONAL, ABRASILEIRADO, NACIONAL_REJEITADO, NOME_DA_LETRA, SEM_FONTE)
            .flatMap { it[l].orEmpty() }
            .distinct()
    }

    /**
     * O mesmo conjunto **sem** [NOME_DA_LETRA]. Ver [palavrasDeCodigo].
     *
     * Montado a partir das mesmas listas, e não escrito à mão, para que uma palavra
     * nova de código entre nos dois lugares de uma vez.
     */
    private val PALAVRAS_DE_CODIGO: Set<String> =
        listOf(INTERNACIONAL, ABRASILEIRADO, NACIONAL_REJEITADO, SEM_FONTE)
            .flatMap { it.values.flatten() }
            .toSet()

    /**
     * Índice invertido: palavra → letra.
     *
     * Onde duas letras reivindicam a mesma palavra, **a primeira lista vence** —
     * `kilo` é K pelos dois alfabetos, e não há conflito real; se algum dia houver,
     * `AlfabetoFoneticoTest` o nomeia em vez de deixá-lo silencioso.
     */
    private val TABELA: Map<String, Char> = buildMap {
        PALAVRAS.forEach { (letra, palavras) -> palavras.forEach { putIfAbsent(it, letra) } }
    }

    /**
     * Palavras longas o bastante para o casamento por som não colidir entre si.
     *
     * O corte por comprimento não é estético. Com tolerância 1 sobre a chave
     * fonética, "de" (D) e "te" (T) ficam a distância 1 — o nome de uma letra casaria
     * com o de outra e a placa sairia trocada. Palavra curta **só casa exata**.
     */
    private val LONGAS: List<Pair<String, Char>> =
        TABELA.entries.filter { it.key.length >= MINIMO_PARA_SOM }.map { it.key to it.value }

    /**
     * Casamento exato.
     *
     * A letra sozinha vale a si mesma — "placa RIO 2 A 18" é ditada real, e o agente
     * diz a letra, não a palavra dela. Isso é regra e não tabela, senão seriam 26
     * linhas que não dizem nada.
     */
    fun letra(palavra: String): Char? {
        if (palavra.length == 1 && palavra[0] in 'a'..'z') return palavra[0].uppercaseChar()
        return TABELA[palavra]
    }

    /** As palavras aceitas para [letra] — a primeira é a canônica. */
    fun palavrasDe(letra: Char): List<String> = PALAVRAS[letra.uppercaseChar()].orEmpty()

    /**
     * **Só as palavras de CÓDIGO — sem o nome das letras.**
     *
     * "alfa", "bravo", "tango", "romeu", "unido" formam indicativo de guarnição.
     * "de", "te", "pe", "que", "esse" **não formam nada**: são o nome da letra, e
     * também são palavra corrente do português. Quem soletra uma placa usa as duas
     * famílias; quem nomeia uma guarnição usa só a primeira.
     *
     * Existe porque [palavrasDe] — que devolve as duas famílias juntas — foi usada
     * como se fosse esta, e o preço apareceu em produção: `CategoriaDeLugar
     * .POSTO_DE_SAUDE` tem o termo `"posto de saude"`, o `"de"` casava com D, e
     * `ConsultaHigienizada.de(POSTO_DE_SAUDE)` **lançava** — o agente que
     * perguntasse por um posto de saúde ouviria "Falha interna.".
     */
    fun palavrasDeCodigo(): Set<String> = PALAVRAS_DE_CODIGO

    /**
     * Casamento por som, para o que o whisper corrompeu ("tangu" por "tango").
     *
     * Existe para **não** enumerar erro de STT à mão: as corrupções são centenas e
     * cada linha acrescentada é uma que ninguém revisa — a mesma lição que
     * [ChaveFonetica] registra para a palavra de ativação.
     *
     * Devolve `null` quando **duas** letras diferentes ficam igualmente perto. Um
     * empate é ambiguidade, e escolher no empate é inventar caractere: "jato" fica a
     * distância 1 de "gato" (G) e de "rato" (R), e a resposta certa é nenhuma das
     * duas.
     */
    fun letraAproximada(palavra: String): Char? {
        if (palavra.length < MINIMO_PARA_SOM) return null
        val candidatas = LONGAS
            .map { (p, l) -> l to ChaveFonetica.distancia(palavra, p) }
            .filter { it.second <= TOLERANCIA }
        if (candidatas.isEmpty()) return null
        val melhor = candidatas.minOf { it.second }
        return candidatas.filter { it.second == melhor }.map { it.first }.distinct().singleOrNull()
    }

    /** Abaixo disto, só casamento exato — ver [LONGAS]. */
    private const val MINIMO_PARA_SOM = 4

    /** Uma sílaba trocada. Medido em `PlacaDitadaTest` e no banco de elocuções. */
    private const val TOLERANCIA = 1
}
