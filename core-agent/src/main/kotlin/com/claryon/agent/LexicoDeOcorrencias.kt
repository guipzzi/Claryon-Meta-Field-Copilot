package com.claryon.agent

/**
 * Tipos de ocorrência reconhecidos, com a prioridade **de base** de cada um.
 *
 * A prioridade é de base, não final: modificadores na fala podem elevá-la (ver
 * [LexicoDeOcorrencias.classificar]). Um roubo é P2; um roubo *com refém* é P1.
 *
 * A lista não pretende ser exaustiva do código penal — pretende cobrir o que um
 * agente **diz no rádio**. "Tiroteio" está aqui; "disparo de arma de fogo em via
 * pública" não, porque ninguém fala assim correndo.
 */
enum class TipoDeOcorrencia(val rotulo: String, val prioridadeBase: Prioridade) {

    // ── Violência contra a pessoa ─────────────────────────────────────────────
    TIROTEIO("tiroteio", Prioridade.EMERGENCIA),
    HOMICIDIO("homicídio", Prioridade.EMERGENCIA),
    TENTATIVA_HOMICIDIO("tentativa de homicídio", Prioridade.EMERGENCIA),
    REFEM("refém", Prioridade.EMERGENCIA),
    AGRESSAO("agressão", Prioridade.ALTA),
    BRIGA("briga", Prioridade.ALTA),
    AMEACA("ameaça", Prioridade.ALTA),
    LESAO_CORPORAL("lesão corporal", Prioridade.ALTA),

    // ── Patrimônio ────────────────────────────────────────────────────────────
    ROUBO("roubo", Prioridade.ALTA),
    ROUBO_DE_VEICULO("roubo de veículo", Prioridade.ALTA),
    FURTO("furto", Prioridade.NORMAL),
    FURTO_DE_VEICULO("furto de veículo", Prioridade.NORMAL),
    ARROMBAMENTO("arrombamento", Prioridade.ALTA),
    RECEPTACAO("receptação", Prioridade.NORMAL),

    // ── Trânsito ──────────────────────────────────────────────────────────────
    ACIDENTE_COM_VITIMA("acidente com vítima", Prioridade.EMERGENCIA),
    ACIDENTE_SEM_VITIMA("acidente sem vítima", Prioridade.NORMAL),
    ATROPELAMENTO("atropelamento", Prioridade.EMERGENCIA),
    CAPOTAMENTO("capotamento", Prioridade.EMERGENCIA),
    EMBRIAGUEZ_AO_VOLANTE("embriaguez ao volante", Prioridade.ALTA),
    RACHA("racha", Prioridade.ALTA),

    // ── Entorpecentes ─────────────────────────────────────────────────────────
    TRAFICO("tráfico", Prioridade.ALTA),
    USO_DE_ENTORPECENTE("uso de entorpecente", Prioridade.NORMAL),

    // ── Pessoa ────────────────────────────────────────────────────────────────
    PESSOA_DESAPARECIDA("pessoa desaparecida", Prioridade.ALTA),
    PESSOA_EM_SURTO("pessoa em surto", Prioridade.ALTA),
    TENTATIVA_DE_SUICIDIO("tentativa de suicídio", Prioridade.EMERGENCIA),
    MAUS_TRATOS("maus-tratos", Prioridade.ALTA),
    PESSOA_CAIDA("pessoa caída", Prioridade.EMERGENCIA),

    // ── Violência doméstica ───────────────────────────────────────────────────
    VIOLENCIA_DOMESTICA("violência doméstica", Prioridade.EMERGENCIA),
    DESCUMPRIMENTO_DE_MEDIDA("descumprimento de medida protetiva", Prioridade.ALTA),

    // ── Ordem pública ─────────────────────────────────────────────────────────
    PERTURBACAO_DO_SOSSEGO("perturbação do sossego", Prioridade.NORMAL),
    DESACATO("desacato", Prioridade.ALTA),
    DANO("dano", Prioridade.NORMAL),
    VANDALISMO("vandalismo", Prioridade.NORMAL),
    INVASAO("invasão", Prioridade.ALTA),

    // ── Emergências não criminais ─────────────────────────────────────────────
    INCENDIO("incêndio", Prioridade.EMERGENCIA),
    VAZAMENTO_DE_GAS("vazamento de gás", Prioridade.EMERGENCIA),
    DESABAMENTO("desabamento", Prioridade.EMERGENCIA),
    AFOGAMENTO("afogamento", Prioridade.EMERGENCIA),
    OBJETO_SUSPEITO("objeto suspeito", Prioridade.EMERGENCIA),

    // ── Operacional ───────────────────────────────────────────────────────────
    // "Apoio" NÃO está aqui: pedir reforço já é `Intent.PedirApoio`, com
    // derivação de prioridade própria. Ter o mesmo termo em dois classificadores
    // criaria ambiguidade — e a ambiguidade cairia para o lado errado conforme a
    // ordem das regras, que é a pior forma de decidir comportamento.
    PERSEGUICAO("perseguição", Prioridade.EMERGENCIA),
    CERCO("cerco", Prioridade.EMERGENCIA),
    ABORDAGEM("abordagem", Prioridade.ALTA),
    ANIMAL_PERIGOSO("animal perigoso", Prioridade.ALTA),
}

/**
 * Ocorrência estruturada a partir da fala do agente.
 *
 * `logradouro` e `referencia` são opcionais **de propósito**: sob estresse o
 * agente diz "tiroteio aqui na Rui Barbosa" ou só "tiroteio". Exigir endereço
 * completo faria o produto recusar exatamente o alerta mais urgente.
 */
data class Ocorrencia(
    val tipo: TipoDeOcorrencia,
    val prioridade: Prioridade,
    val logradouro: String?,
    val referencia: String?,
    val textoOriginal: String,
)

/**
 * **Classificação determinística de ocorrências — o coração do C3.**
 *
 * O agente não fala em formato estruturado sob estresse. Este léxico traduz a
 * fala livre em ocorrência tipada, **sem modelo de linguagem**: regras, palavras
 * de gatilho e um gazetteer de logradouros.
 *
 * ## Por que determinístico
 *
 * Um LLM aqui acrescentaria latência, bateria e risco de alucinação para produzir
 * a mesma saída na esmagadora maioria dos casos. E alucinar aqui não é erro
 * cosmético: classificar "briga" como "tiroteio" manda a guarnição inteira ao
 * local com a postura errada — ou, no sentido inverso, rebaixa um tiroteio a
 * ocorrência de rotina.
 *
 * O modelo de linguagem tem lugar previsto no projeto, e é **depois** deste: só
 * quando o léxico não casa, e apenas como preenchedor de campos com saída
 * validada contra esquema. Nunca escolhendo a ação.
 *
 * ## Escalada por modificador
 *
 * A prioridade de base do tipo é elevada por gatilhos presentes na fala. "Roubo"
 * é P2; "roubo com refém", "roubo a mão armada" ou "roubo com vítima ferida" são
 * P1. A escalada **só sobe, nunca desce**: na dúvida entre dois níveis, o produto
 * erra para o lado de mandar mais gente.
 */
object LexicoDeOcorrencias {

    /**
     * Gatilhos por tipo, em ordem de especificidade — o mais específico primeiro.
     *
     * A ordem importa: "roubo de veículo" tem de ser testado antes de "roubo",
     * senão toda ocorrência de veículo viraria roubo genérico e o alerta perderia
     * a informação que define a resposta (perseguir um carro é diferente de
     * atender uma vítima parada).
     */
    private val GATILHOS: List<Pair<TipoDeOcorrencia, List<String>>> = listOf(
        // Específicos primeiro.
        TipoDeOcorrencia.TENTATIVA_HOMICIDIO to listOf("tentativa de homicidio", "tentaram matar"),
        TipoDeOcorrencia.ROUBO_DE_VEICULO to listOf("roubo de veiculo", "roubo de carro", "roubo de moto", "carro roubado", "moto roubada"),
        TipoDeOcorrencia.FURTO_DE_VEICULO to listOf("furto de veiculo", "furto de carro", "furto de moto"),
        TipoDeOcorrencia.ACIDENTE_COM_VITIMA to listOf("acidente com vitima", "acidente com feridos", "acidente com ferido"),
        TipoDeOcorrencia.ACIDENTE_SEM_VITIMA to listOf("acidente sem vitima", "batida sem vitima"),
        TipoDeOcorrencia.TENTATIVA_DE_SUICIDIO to listOf("tentativa de suicidio", "quer se jogar", "ameaca de suicidio"),
        TipoDeOcorrencia.VIOLENCIA_DOMESTICA to listOf("violencia domestica", "agressao domestica", "marido agredindo", "companheiro agredindo"),
        TipoDeOcorrencia.DESCUMPRIMENTO_DE_MEDIDA to listOf("descumprimento de medida", "medida protetiva", "violou a medida"),
        TipoDeOcorrencia.EMBRIAGUEZ_AO_VOLANTE to listOf("embriaguez ao volante", "motorista embriagado", "dirigindo bebado"),
        TipoDeOcorrencia.PERTURBACAO_DO_SOSSEGO to listOf("perturbacao do sossego", "som alto", "barulho excessivo"),
        TipoDeOcorrencia.OBJETO_SUSPEITO to listOf("objeto suspeito", "bomba", "artefato explosivo", "mochila abandonada"),
        TipoDeOcorrencia.VAZAMENTO_DE_GAS to listOf("vazamento de gas", "cheiro de gas"),
        TipoDeOcorrencia.PESSOA_DESAPARECIDA to listOf("pessoa desaparecida", "desaparecido", "desaparecida", "sumiu"),
        TipoDeOcorrencia.PESSOA_EM_SURTO to listOf("em surto", "surto psicotico", "crise psiquiatrica", "descontrolado"),
        TipoDeOcorrencia.PESSOA_CAIDA to listOf("pessoa caida", "homem caido", "mulher caida", "corpo caido", "desmaiado"),
        TipoDeOcorrencia.ANIMAL_PERIGOSO to listOf("animal perigoso", "cachorro bravo", "cobra"),
        TipoDeOcorrencia.USO_DE_ENTORPECENTE to listOf("uso de entorpecente", "usuario de droga", "consumo de droga"),

        // Genéricos depois.
        TipoDeOcorrencia.TIROTEIO to listOf("tiroteio", "troca de tiros", "tiros", "disparos", "arma disparando"),
        TipoDeOcorrencia.HOMICIDIO to listOf("homicidio", "assassinato", "morto a tiros", "cadaver"),
        TipoDeOcorrencia.REFEM to listOf("refem", "sequestro", "mantendo pessoas"),
        TipoDeOcorrencia.PERSEGUICAO to listOf("perseguicao", "fuga", "em fuga", "fugindo"),
        TipoDeOcorrencia.CERCO to listOf("cerco", "cercar", "isolamento"),
        TipoDeOcorrencia.INCENDIO to listOf("incendio", "fogo", "queimando"),
        TipoDeOcorrencia.DESABAMENTO to listOf("desabamento", "desmoronamento", "predio caiu"),
        TipoDeOcorrencia.AFOGAMENTO to listOf("afogamento", "afogando"),
        TipoDeOcorrencia.ATROPELAMENTO to listOf("atropelamento", "atropelou", "atropelado"),
        TipoDeOcorrencia.CAPOTAMENTO to listOf("capotamento", "capotou", "capotado"),
        TipoDeOcorrencia.RACHA to listOf("racha", "pega", "corrida ilegal"),
        TipoDeOcorrencia.TRAFICO to listOf("trafico", "traficando", "boca de fumo", "venda de droga"),
        TipoDeOcorrencia.ARROMBAMENTO to listOf("arrombamento", "arrombou", "arrombaram"),
        TipoDeOcorrencia.INVASAO to listOf("invasao", "invadiram", "invadindo"),
        TipoDeOcorrencia.ROUBO to listOf("roubo", "assalto", "roubaram", "assaltaram"),
        TipoDeOcorrencia.FURTO to listOf("furto", "furtaram", "levaram sem"),
        TipoDeOcorrencia.RECEPTACAO to listOf("receptacao", "produto de roubo"),
        TipoDeOcorrencia.MAUS_TRATOS to listOf("maus tratos", "maltratando"),
        TipoDeOcorrencia.LESAO_CORPORAL to listOf("lesao corporal", "ferido a faca", "esfaqueado"),
        TipoDeOcorrencia.AGRESSAO to listOf("agressao", "agredindo", "agrediu", "espancamento"),
        TipoDeOcorrencia.BRIGA to listOf("briga", "confusao", "tumulto", "desordem"),
        TipoDeOcorrencia.AMEACA to listOf("ameaca", "ameacando", "ameacou"),
        TipoDeOcorrencia.DESACATO to listOf("desacato", "desacatando"),
        TipoDeOcorrencia.VANDALISMO to listOf("vandalismo", "depredacao", "pichacao"),
        TipoDeOcorrencia.DANO to listOf("dano", "danificou", "quebrou o"),
        TipoDeOcorrencia.ABORDAGEM to listOf("abordagem", "abordar", "suspeito parado"),
    )

    /**
     * Modificadores que **elevam** a prioridade. Nunca rebaixam: na dúvida entre
     * dois níveis, o produto erra para o lado de mandar mais gente.
     */
    private val ESCALA_PARA_EMERGENCIA = comFlexoes(
        listOf(
            "arma", "armado", "refem", "vitima", "ferido", "sangrando", "baleado",
            "crianca", "perigo de vida", "socorro", "urgente agora",
            // "policial" sozinho NÃO entra: "chama outro policial" viraria
            // emergência. O que escala é o que aconteceu com ele — "baleado",
            // "ferido" — e a guarnição em risco, que é frase de rádio própria.
            "guarnicao em risco",
        ),
    )

    private val ESCALA_PARA_ALTA = comFlexoes(
        listOf("urgente", "rapido", "agora", "correndo", "muita gente"),
    )

    /** Prefixos que anunciam logradouro na fala natural. */
    private val PREFIXOS_DE_VIA = listOf(
        "avenida", "av", "rua", "r", "alameda", "travessa", "rodovia", "br", "go",
        "praca", "estrada", "marginal", "viela", "setor", "quadra",
    )

    /**
     * Gazetteer de logradouros da cidade do piloto. Injetável porque muda por
     * cidade — e porque um nome de rua conhecido é o que distingue "Rui Barbosa"
     * (logradouro) de "Rui Barbosa" (nome de pessoa envolvida).
     */
    /**
     * Mapa `normalizado → grafia canônica`. O valor é o que vai para o
     * despachante: "Rui Barbosa", com maiúscula e acento, não "rui barbosa" nem
     * um pedaço da fala recortado por índice.
     */
    @Volatile
    var gazetteer: Map<String, String> = emptyMap()
        private set

    fun configurarGazetteer(logradouros: Collection<String>) {
        gazetteer = logradouros.associateBy({ normalizarTexto(it) }, { it.trim() })
    }

    /**
     * Classifica a fala. `null` quando **nada** casa — e nesse caso o produto
     * pede repetição em vez de adivinhar. Um alerta inventado é pior que um
     * alerta não enviado.
     */
    fun classificar(texto: String): Ocorrencia? {
        val normalizado = normalizarTexto(texto)
        if (normalizado.isBlank()) return null

        // `palavraInteira`, NUNCA `contains`. Com `contains` cru, qualquer palavra
        // que *contenha* um gatilho disparava alerta para a guarnição inteira:
        // "obrigado" e "brigada militar" viravam BRIGA/ALTA, "pegar" virava
        // RACHA, "fogos" virava INCÊNDIO/EMERGÊNCIA, "cobrança" virava ANIMAL
        // PERIGOSO. Em RS/SC, onde a corporação se chama Brigada Militar, o falso
        // positivo seria diário — e um alerta não tem confirmação nem desfazer.
        val tipo = GATILHOS.firstOrNull { (_, gatilhos) ->
            gatilhos.any { palavraInteira(normalizado, it) }
        }?.first ?: return null

        return Ocorrencia(
            tipo = tipo,
            prioridade = escalar(tipo.prioridadeBase, normalizado),
            logradouro = extrairLogradouro(texto, normalizado),
            referencia = extrairReferencia(normalizado),
            textoOriginal = texto.trim(),
        )
    }

    /**
     * Escalada de prioridade a partir da fala, **reutilizável fora do léxico**.
     *
     * Existe pública porque o pedido de apoio (`Intent.PedirApoio`) precisa da
     * mesma régua. Sem isso havia duas: "policial baleado" era emergência pelo
     * léxico e prioridade normal pelo pedido de apoio — a mesma frase, dois
     * despachos diferentes, dependendo de qual caminho o roteador tomasse.
     */
    fun escalarPrioridade(base: Prioridade, texto: String): Prioridade =
        escalar(base, normalizarTexto(texto))

    private fun escalar(base: Prioridade, normalizado: String): Prioridade = when {
        ESCALA_PARA_EMERGENCIA.any { presenteENaoNegado(normalizado, it) } -> Prioridade.EMERGENCIA
        base == Prioridade.EMERGENCIA -> Prioridade.EMERGENCIA
        ESCALA_PARA_ALTA.any { presenteENaoNegado(normalizado, it) } -> maxOf(base, Prioridade.ALTA)
        else -> base
    }

    /**
     * Negações que invertem o sentido de um modificador.
     *
     * Sem isto, **"acidente sem vítima" escalaria para emergência** — a palavra
     * "vítima" está lá, negada. O efeito operacional seria esvaziar o setor para
     * uma batida de para-choque, e a próxima emergência de verdade encontraria a
     * guarnição comprometida. O caso oposto — "com vítima" — precisa continuar
     * escalando, então a checagem é da janela imediatamente anterior à palavra.
     */
    private val NEGACOES = listOf("sem", "nenhum", "nenhuma", "nao ha", "nao tem", "nao houve")

    /**
     * O casamento é por palavra inteira, então **flexão precisa estar na lista**.
     * "policial baleado" escalava; "dois policiais baleados" não. "suspeito
     * armado" escalava; "gente armada" não. A régua tem de valer para como a
     * pessoa fala, não para a forma de dicionário.
     */
    private fun comFlexoes(palavras: List<String>): List<String> = palavras.flatMap { p ->
        when {
            p.endsWith("ado") -> listOf(p, p + "s", p.dropLast(1) + "a", p.dropLast(1) + "as")
            p.endsWith("ido") -> listOf(p, p + "s", p.dropLast(1) + "a", p.dropLast(1) + "as")
            p.endsWith("a") || p.endsWith("o") || p.endsWith("e") -> listOf(p, p + "s")
            else -> listOf(p, p + "s", p + "es")
        }
    }.distinct()

    /** `true` se a palavra aparece **e** não está negada logo antes. */
    private fun presenteENaoNegado(texto: String, palavra: String): Boolean {
        val re = Regex("(^|\\W)${Regex.escape(palavra)}(\\W|$)")
        var busca = re.find(texto)
        while (busca != null) {
            val inicio = busca.range.first
            // Janela de até 20 caracteres antes da ocorrência: cobre "sem",
            // "não há" e "nenhuma" sem atravessar a oração inteira.
            // Só as DUAS palavras imediatamente anteriores contam como negação.
            //
            // A versão anterior varria 20 caracteres com `contains`, e qualquer
            // "sem" na janela negava o que viesse depois — mesmo de outra oração.
            // "roubo, o assaltante estava sem capacete, vítima ferida" deixava de
            // escalar para emergência porque o "sem capacete" negou a vítima.
            val antes = texto.substring(0, inicio).trimEnd().split(" ")
            val duasUltimas = antes.takeLast(2)
            val bigrama = duasUltimas.joinToString(" ")
            val negado = NEGACOES.any { n ->
                if (" " in n) bigrama == n else duasUltimas.lastOrNull() == n
            }
            if (!negado) return true
            busca = busca.next()
        }
        return false
    }

    /** `EMERGENCIA` é a mais alta; o enum está em ordem decrescente de urgência. */
    private fun maxOf(a: Prioridade, b: Prioridade): Prioridade =
        if (a.ordinal <= b.ordinal) a else b

    /**
     * Extrai o logradouro. Prefere o gazetteer (nome conhecido da cidade) e cai
     * para o padrão "avenida/rua X" quando não houver correspondência — o piloto
     * pode começar sem gazetteer completo.
     *
     * Devolve o trecho **original**, não o normalizado: "Rui Barbosa" é o que o
     * despachante lê, não "rui barbosa".
     */
    private fun extrairLogradouro(original: String, normalizado: String): String? {
        // Devolve a grafia CANÔNICA do gazetteer, não uma fatia do texto original.
        //
        // A versão anterior fazia `original.substring(normalizado.indexOf(...))`.
        // Como `normalizarTexto` remove pontuação e colapsa espaços, os índices
        // divergem sempre que a fala tiver vírgula ou espaço duplo — e "Tiroteio,
        // na Rui Barbosa" produzia o logradouro **"Rui Barbos"**. Esse campo é o
        // endereço que o despachante lê para mandar a guarnição.
        gazetteer.entries.firstOrNull { palavraInteira(normalizado, it.key) }
            ?.let { return it.value }

        val palavras = normalizado.split(" ")
        for ((i, p) in palavras.withIndex()) {
            if (p in PREFIXOS_DE_VIA && i + 1 < palavras.size) {
                // Pega até 3 palavras seguintes, parando em conectivo — "avenida
                // Rui Barbosa perto do posto" não pode virar rua "Rui Barbosa
                // perto do".
                val nome = palavras.drop(i + 1)
                    .takeWhile { it !in CONECTIVOS }
                    .take(3)
                    .joinToString(" ")
                if (nome.isNotBlank()) return "${p.replaceFirstChar { it.uppercase() }} ${nome.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }}"
            }
        }
        return null
    }

    private val CONECTIVOS = setOf("perto", "proximo", "em", "no", "na", "com", "e", "ao", "do", "da", "de")

    /** Referência de apoio ("perto do posto", "esquina com"). */
    private fun extrairReferencia(normalizado: String): String? {
        val marcadores = listOf("perto do ", "perto da ", "proximo ao ", "proximo da ", "esquina com ", "em frente ao ", "em frente a ")
        for (m in marcadores) {
            val i = normalizado.indexOf(m)
            if (i >= 0) {
                val resto = normalizado.substring(i + m.length).split(" ").take(3).joinToString(" ")
                if (resto.isNotBlank()) return (m.trim() + " " + resto).trim()
            }
        }
        return null
    }

    private fun palavraInteira(texto: String, palavra: String): Boolean =
        Regex("(^|\\W)${Regex.escape(palavra)}(\\W|$)").containsMatchIn(texto)

    /** Minúsculas, sem acento, espaços colapsados — para casar "agressão" com "agressao". */
    fun normalizarTexto(s: String): String = java.text.Normalizer
        .normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
