package com.claryon.agent

/**
 * **O segundo estágio da ativação: a palavra de gatilho DENTRO da transcrição.**
 *
 * ## Por que ela está lá, e por que isso é o desenho e não um estorvo
 *
 * O whisper não sabe que "Claryon" foi o gatilho: ele transcreve tudo que ouviu.
 * Medido no aparelho em 20/08, com fala humana e servidor real, o texto que voltou
 * foi *"Clareon, Guarney são 1 na escuta."* — e o roteador, ancorado em
 * `^guarnicao`, recusou.
 *
 * A arquitetura da Alexa faz exatamente isto de propósito: a detecção é em **dois
 * estágios** — um detector acústico pequeno no aparelho e uma verificação maior
 * depois —, e o áudio enviado para reconhecimento **contém a palavra de ativação**,
 * porque é ela que a segunda etapa confere. Falso despertar é descartado ali.
 *
 * Aqui vale o mesmo, com a segunda etapa sendo a transcrição local:
 *
 * - **Estágio 1, rápido:** `DetectorDeAtivacao` dispara o earcon em ≤500 ms. É o
 *   que dá a sensação de resposta imediata, e ele **nunca decide ação**.
 * - **Estágio 2, exato:** o texto tem de começar pela palavra de ativação. Se não
 *   começa, o estágio 1 despertou por engano — e o comando não roda.
 *
 * Isso transforma o prefixo, que era o defeito, na **prova** de que o agente falou
 * com o copiloto. Sem ele, qualquer frase dita perto do aparelho no ciclo aberto
 * viraria comando.
 *
 * ## As variantes não são chute
 *
 * São as grafias que o whisper pt-BR de fato produz para "Claryon", medidas pelos
 * bancos deste projeto. "Clareon" foi a que voltou na captura de 20/08; as outras
 * vieram dos ensaios de `PalavraDeAtivacaoTest` e `VariantesDoParTest`. Acrescentar
 * variante inventada aqui **alarga o portão sem prova** — cada entrada nova precisa
 * de uma transcrição real que a justifique.
 */
object PalavraDeAtivacaoNaFala {

    /**
     * Grafias medidas de "Claryon" na saída do whisper pt-BR.
     *
     * Ordenadas do mais longo para o mais curto na comparação, para que "clareon"
     * não seja consumido por um prefixo menor que também casaria.
     */
    val VARIANTES: List<String> = listOf(
        "claryon", "clareon", "clarion", "claryom", "clareom",
        // "e clarion" / "eclareon": o whisper às vezes cola o artigo anterior na
        // palavra. Medido em `VariantesDoParTest`.
        "eclarion", "eclareon",
    )

    /** A grafia oficial. Tudo é comparado foneticamente contra ela. */
    const val CANONICA = "claryon"

    /**
     * **ZERO, e o número foi medido — não escolhido.**
     *
     * Com tolerância 1 o portão deixava passar "clarim", e a frase inteira
     * *"clarim, guarnição 3 na escuta"* **abria canal** — medido em
     * `GrafiasDaAtivacaoTest`, 1 falso aceite em 174 negativos montados em frase.
     *
     * Eu tinha argumentado que a conjunção dos dois estágios bastaria, porque
     * "ninguém diz isso". O teste construiu a frase e ela abriu. Argumento sobre o
     * que as pessoas dizem não é defesa: o portão tem de recusar.
     *
     * A saída não foi apertar a tolerância e perder recall — foi **melhorar a
     * chave**. "e" átono antes de vogal nasal passou a colapsar em "i", então
     * "clareon" e "claryon" ficaram na distância ZERO e a tolerância pôde fechar
     * sem custo. Cobertura do espaço gráfico: 90%.
     */
    const val TOLERANCIA = 0

    /** Saudações que costumam vir grudadas no gatilho. */
    private val SAUDACOES = listOf("hey", "hei", "ei", "ok", "oi")

    /**
     * O resultado da conferência de segundo estágio.
     *
     * @param confirmada `true` quando a transcrição começa pela palavra de ativação.
     * @param resto o comando, já sem o gatilho. Vazio quando o agente só disse a
     *   palavra e nada mais — que é caso legítimo (acordar e pensar) e não comando.
     */
    data class Conferencia(val confirmada: Boolean, val resto: String)

    /**
     * Separa o gatilho do comando.
     *
     * **Só no PREFIXO**, e isso é regra de segurança, não economia: "Claryon" no
     * meio de uma frase é o agente FALANDO sobre o copiloto para outra pessoa
     * ("pergunta pro Claryon onde está o Alfa Dois"), não falando COM ele. Aceitar
     * no meio faria conversa virar comando.
     */
    fun conferir(transcricao: String): Conferencia {
        var t = normalizar(transcricao)

        // A saudação some antes do gatilho: "hey claryon" e "claryon" são a mesma
        // intenção, e o agente não escolhe qual vai sair na transcrição.
        for (s in SAUDACOES) {
            if (t.startsWith("$s ")) { t = t.removePrefix("$s ").trim(); break }
        }

        // **Casamento FONÉTICO da primeira palavra, não lista de grafias.**
        //
        // Enumerar não escala: o espaço gráfico plausível de "Claryon" tem centenas
        // de formas, e cada uma acrescentada à mão é linha que ninguém revisa.
        // `ChaveFonetica` colapsa a variação que o português permite sobre o mesmo
        // som — medido em `GrafiasDaAtivacaoTest`.
        val primeira = t.substringBefore(' ').trimEnd(',', '.', '!', '?', ';', ':')
        val casou = VARIANTES.any { primeira == it } ||
            ChaveFonetica.pareceCom(primeira, CANONICA, TOLERANCIA)
        if (!casou) return Conferencia(confirmada = false, resto = transcricao.trim())
        val variante = primeira

        // Pontuação depois do gatilho é o caso COMUM, não a exceção: o whisper
        // devolveu "Clareon, Guarney são 1 na escuta." com a vírgula. Sem tirá-la o
        // resto começaria por `,` e nenhum padrão casaria.
        val resto = t.removePrefix(variante).trimStart(',', '.', '!', '?', ' ', ';', ':')
        return Conferencia(confirmada = true, resto = resto.trim())
    }

    /** Minúsculo, sem acento, espaços colapsados — a mesma régua do roteador. */
    private fun normalizar(s: String): String =
        java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
}
