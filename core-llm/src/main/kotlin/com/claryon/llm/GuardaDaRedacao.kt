package com.claryon.llm

/**
 * **O filtro que decide se o texto gerado tem lastro no que foi recuperado.**
 *
 * ## O que este guarda é, e o que ele NÃO é
 *
 * Ele **não prova** que o modelo não alucinou. Prova de não-alucinação neste
 * produto tem um nome e já existe: é a Etapa A, que não tem passo capaz de
 * produzir texto novo. Este guarda é a válvula que devolve o produto àquele
 * caminho quando a redação sai sem lastro.
 *
 * **E "aquele caminho" é a CITAÇÃO, não a leitura do artigo.** Este KDoc dizia
 * *"a Etapa A, que lê o trecho verbatim"*, e isso nunca foi verdade em produção:
 * o que o agente ouve é `"Art. 306, Lei 9.503"` — quatro palavras —, e o corpo
 * do artigo não atravessa a camada de recuperação, que entrega apenas o par
 * `citacao, norma`. Ler o artigo em voz alta esbarra no teto de **7 palavras**
 * do `CLAUDE.md` §4 e está **proposto**, não construído, em
 * `specs/leitura-de-norma.spec.md`.
 *
 * A distinção não é de redação: quem lê "cai na leitura verbatim" supõe uma rede
 * de segurança que responde a pergunta. A rede que existe diz **onde** procurar.
 * É bem menos, e é o que há.
 *
 * Registrar isso importa: um filtro apresentado como garantia vira a próxima
 * mentira do repositório, e o `CLAUDE.md` §6 persegue exatamente essa família.
 *
 * ## O buraco conhecido, medido em 21/08 e não fechado
 *
 * **Este guarda não vê negação.** Rodando Llama 3.2 1B Q4_K_M no emulador sobre
 * o Art. 165 do CTB, uma das saídas foi:
 *
 * > *"Não há, não há nada que aconteça com quem dirige embriagado."*
 *
 * Ela **passou**. E passou corretamente pelas duas réguas: não tem cifra, e cada
 * palavra de conteúdo (`acontece`, `dirige`, `embriagado`) tem raiz na pergunta
 * ou no trecho. O lastro é lexical, e a inversão de sentido usa exatamente o
 * léxico da fonte — é o caso em que a régua é cega por construção, não por
 * calibração ruim.
 *
 * Fechar isso exige comparar *sentido*, não palavras: entailment, ou um segundo
 * passe com o modelo julgando a própria saída (que dobra a latência e usa o
 * mesmo modelo fraco como juiz). Nenhum dos dois entra hoje. Enquanto não
 * entrar, **a Etapa B não deve ser ligada em aparelho de campo.**
 *
 * **A troca de modelo de 22/08 não mexe nisto, e pode piorar.** O
 * `Llama-3.2-1B-Instruct-Q4_K_M` saiu e entrou o `Qwen2.5-1.5B-Instruct-Q4_K_M`
 * por cobertura de português (`DECISIONS.md`). Este buraco é da **régua**, não do
 * modelo: ela compara léxico, e inversão de sentido é justamente a alucinação que
 * reusa o léxico da fonte. Um modelo mais fluente em português produz negação
 * mais bem construída — que é exatamente a que passa aqui. A frase acima continua
 * sendo o caso de teste, e o próximo modelo herda o buraco inteiro.
 *
 * ## As duas réguas, e por que uma é absoluta e a outra não
 *
 * **1. Cifras: 100%, sem tolerância.** Toda sequência de dígitos da saída tem de
 * aparecer na fonte. Número de artigo, número de lei, prazo em horas, dosagem:
 * é a classe de erro mais cara que existe aqui, porque o agente ouve `"Art. 33"`
 * com a mesma naturalidade de `"Art. 28"` e não tem display para conferir. Uma
 * cifra sem lastro reprova o texto inteiro.
 *
 * **2. Palavras de conteúdo: fração mínima.** Reescrever é, por definição,
 * trocar palavras — exigir 100% de casamento lexical reprovaria toda redação
 * legítima e a Etapa B nunca falaria. A régua é a fração de palavras longas cuja
 * raiz aparece na fonte; abaixo de [lastroMinimo] o texto está falando de outra
 * coisa.
 *
 * ## Por que raiz de 6 caracteres, e não a palavra inteira
 *
 * Português flexiona: a fonte diz `"veículos"` e a redação diz `"veículo"`;
 * a fonte diz `"conduzir"` e a redação diz `"conduzindo"`. Comparar palavra
 * inteira transformaria flexão em alucinação. Seis caracteres é o ponto em que
 * `condut`/`conduz` ainda separam e `veicul` ainda junta — é heurística
 * declarada, não medida, e está aqui escrito que é heurística.
 *
 * Palavras curtas (< [tamanhoDePalavraDeConteudo]) não entram na conta: são
 * conectivos e verbos auxiliares, e um redator precisa deles para fazer frase.
 */
class GuardaDaRedacao(
    private val lastroMinimo: Double = LASTRO_MINIMO_PADRAO,
    private val tamanhoDePalavraDeConteudo: Int = 5,
    private val tamanhoDaRaiz: Int = 6,
) {
    init {
        require(lastroMinimo in 0.0..1.0) { "Lastro fora de 0..1: $lastroMinimo" }
    }

    /**
     * O texto aprovado, ou `null` quando ele não tem lastro na fonte.
     *
     * [fonte] é o material com procedência: o trecho recuperado e a citação.
     *
     * **A pergunta do agente NÃO entra, e isto mudou em 22/08.** Ela entrava, pelo
     * raciocínio de que uma resposta honesta reusa as palavras da pergunta. Medido:
     * com a pergunta na fonte, um modelo que apenas a ECOA obtém lastro **1,00 sem
     * tocar no artigo**. Remedido no aparelho em 22/08, sobre as 20 perguntas do banco
     * de abordagem: **2 de 9** aprovações caem no prazo de produção, **2 de 12** no
     * prazo de medição, e **5 de 13** com a formulação de prompt que entrou no mesmo
     * dia — quanto mais o modelo ecoa, mais esta régua trabalha. Palavra que a
     * pergunta compartilha com o artigo já está no trecho e segue contando; some
     * apenas a que existe só na pergunta — a que não tem lastro na norma.
     *
     * **Nenhuma redação honesta foi perdida na troca**, que era o risco alegado: as
     * recusas novas, lidas uma a uma no log, são todas eco puro ou meta-comentário.
     *
     * A resposta anterior também não entra: uma alucinação aprovada uma vez viraria
     * lastro para a próxima, e o filtro degradaria sozinho ao longo do turno.
     */
    fun aprovar(gerado: String, fonte: String): String? {
        val texto = gerado.trim()
        if (texto.isBlank()) return null

        val fonteNormalizada = normalizar(fonte)

        // Régua 1 — grandezas. Absoluta.
        //
        // **Era `Regex("""\d+""")`, e reprovava ZERO de 268 gerações medidas.** Os três
        // furos eram todos da cifra crua, e `Grandezas` existe para tapá-los:
        //
        //  1. `"trinta dias"` não tem dígito — a regex não via número nenhum.
        //  2. `"500 gramas"` onde a fonte diz `"500 dias-multa"` passava, porque a
        //     cifra `500` ESTÁ na fonte. Trocar a grandeza mantendo o número é a forma
        //     mais perigosa de invenção: parece citação.
        //  3. `"1,5"` virava as cifras `1` e `5`, ambas comuns em qualquer norma.
        //
        // Comparar `Grandeza(valor, classe)` fecha os três de uma vez: o extenso é
        // normalizado, a classe separa tempo de massa, e o decimal é um número só.
        //
        // Número sem unidade reconhecida (`Art. 33`, `§ 1º`) tem `classe == null` e
        // continua sendo comparado só pelo valor — é o comportamento antigo, que para
        // esse caso estava certo.
        val grandezasDaFonte = Grandezas.extrair(fonteNormalizada).toSet()
        val semLastro = Grandezas.extrair(normalizar(texto))
            .firstOrNull { it !in grandezasDaFonte }
        if (semLastro != null) return null

        // Régua 2 — lastro lexical.
        val raizesDaFonte = palavras(fonteNormalizada).map { raiz(it) }.toSet()
        val conteudo = palavras(normalizar(texto))
            .filter { it.length >= tamanhoDePalavraDeConteudo }
        if (conteudo.isEmpty()) return null

        val comLastro = conteudo.count { raiz(it) in raizesDaFonte }
        val fracao = comLastro.toDouble() / conteudo.size
        return if (fracao >= lastroMinimo) texto else null
    }

    /** Quanto do texto tem lastro, em `0..1`. Serve a log e a teste, não a decisão. */
    fun lastro(gerado: String, fonte: String): Double {
        val raizesDaFonte = palavras(normalizar(fonte)).map { raiz(it) }.toSet()
        val conteudo = palavras(normalizar(gerado))
            .filter { it.length >= tamanhoDePalavraDeConteudo }
        if (conteudo.isEmpty()) return 0.0
        return conteudo.count { raiz(it) in raizesDaFonte }.toDouble() / conteudo.size
    }

    private fun raiz(palavra: String): String = palavra.take(tamanhoDaRaiz)

    private fun palavras(normalizado: String): List<String> =
        normalizado.split(SEPARADOR).filter { it.isNotEmpty() && it.any(Char::isLetter) }

    /**
     * Minúsculas e sem acento, para que `"VEÍCULO"` e `"veiculo"` sejam a mesma
     * coisa. O STT deste projeto entrega texto com acentuação irregular, então
     * comparar com acento faria a régua reprovar por ortografia.
     */
    private fun normalizar(texto: String): String {
        val sb = StringBuilder(texto.length)
        for (c in texto.lowercase()) {
            sb.append(SEM_ACENTO[c] ?: c)
        }
        return sb.toString()
    }

    companion object {
        /**
         * **Não medido em corpus real.** Escolhido pelo lado do erro que custa
         * menos: reprovar demais devolve o produto à citação seca (`"Art. 306,
         * Lei 9.503"`), que é o comportamento padrão e correto; aprovar demais
         * põe texto sem lastro na boca do copiloto. Quem calibrar troca este
         * número **com a medição junto**.
         */
        const val LASTRO_MINIMO_PADRAO: Double = 0.55

        private val CIFRA = Regex("""\d+""")
        private val SEPARADOR = Regex("""[^\p{L}\p{N}]+""")

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
}
