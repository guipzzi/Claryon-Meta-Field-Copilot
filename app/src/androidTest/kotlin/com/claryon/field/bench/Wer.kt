package com.claryon.field.bench

import java.text.Normalizer

/**
 * **A régua da transcrição: WER e CER, sem indulgência.**
 *
 * ## Por que esta classe existe
 *
 * O aceite de STT deste projeto era `texto.contains("central") || ...`, e depois
 * "ao menos 2 de 4 palavras de conteúdo". Com esse limiar, a frase
 *
 * > falada: *"Central, a guarnição está a caminho da ocorrência."*
 * > transcrita: *"central, agora nisso são está caminhinho da ocorrência."*
 *
 * passava — apesar de a palavra mais importante do domínio, **guarnição**, ter
 * virado "agora nisso são". Um limiar que aceita isso não mede qualidade: mede se
 * o modelo produziu *alguma* coisa.
 *
 * O número honesto para essa transcrição é **WER de 62,5%**: cinco substituições
 * em oito palavras de referência, ou seja **37,5% de acurácia** contra a meta de
 * ≥92% do `ROADMAP.md`. Ele está fixado em [WerTest] como caso de regressão, e foi
 * calculado — a primeira versão deste KDoc dizia "50%", número que eu havia
 * estimado de cabeça em vez de computar. Errar a régua ao descrever a régua é o
 * tipo de coisa que a Regra Zero existe para impedir, e ela vale também para
 * prosa.
 *
 * ## A fórmula, e por que ela é essa
 *
 * `WER = (S + D + I) / N`, onde `N` é o número de palavras da **referência** —
 * não do resultado. A inserção conta no numerador mas não no denominador, então
 * um modelo que alucina texto tem WER acima de 100%, o que é o comportamento
 * correto: alucinar é pior que calar. Dividir por `max(ref, hip)` "normalizaria"
 * a alucinação para dentro de 100% e esconderia justamente o pior caso.
 *
 * `S`, `D` e `I` saem do alinhamento de custo mínimo (Levenshtein por palavra).
 * O alinhamento ótimo não é único; a **contagem total** de erros é. Como o WER só
 * usa a soma, a ambiguidade não afeta o número — mas afeta o detalhamento, e por
 * isso [Resultado.alinhamento] é declarado como *um* alinhamento ótimo, não *o*.
 *
 * ## Normalização: a decisão que mais mexe no número
 *
 * Comparar "Guarnição," com "guarnicao" tem de dar acerto — a diferença é
 * ortográfica, e o produto age sobre o sentido. Mas normalizar demais mente ao
 * contrário: colapsar dígitos apagaria a diferença entre "guarnição três" e
 * "guarnição treze", que num despacho é a diferença entre duas viaturas.
 *
 * Portanto: minúsculas, sem acento, sem pontuação de borda, espaços colapsados —
 * e **nada além disso**. Número escrito continua diferente de dígito, porque para
 * o STT são reconhecimentos diferentes e o projeto fala número dígito a dígito.
 */
object Wer {

    /**
     * Uma operação do alinhamento. Serve para o log dizer *onde* errou, que é o
     * que transforma "WER 50%" em informação acionável.
     */
    enum class Op { ACERTO, SUBSTITUICAO, DELECAO, INSERCAO }

    data class Passo(val op: Op, val referencia: String?, val hipotese: String?)

    data class Resultado(
        val substituicoes: Int,
        val delecoes: Int,
        val insercoes: Int,
        /** Palavras da **referência**. É o denominador. */
        val palavrasDeReferencia: Int,
        /** Um alinhamento de custo mínimo — não necessariamente o único. */
        val alinhamento: List<Passo>,
    ) {
        val erros: Int get() = substituicoes + delecoes + insercoes

        /**
         * `(S + D + I) / N`. Pode passar de 1.0 quando o modelo alucina, e isso é
         * proposital — ver o KDoc da classe.
         *
         * Referência vazia é caso degenerado: sem nada para acertar, qualquer
         * saída é 100% errada e nenhuma saída é 0%.
         */
        val taxa: Double
            get() = when {
                palavrasDeReferencia > 0 -> erros.toDouble() / palavrasDeReferencia
                erros > 0 -> 1.0
                else -> 0.0
            }

        val percentual: Double get() = taxa * 100.0

        /** Quanto o modelo acertou. É o número que o ROADMAP chama de meta de STT. */
        val acuraciaPercentual: Double get() = (1.0 - taxa).coerceAtLeast(0.0) * 100.0

        /** As trocas que importam para diagnóstico, na ordem da fala. */
        fun errosLegiveis(): String = alinhamento
            .filter { it.op != Op.ACERTO }
            .joinToString("; ") {
                when (it.op) {
                    Op.SUBSTITUICAO -> "'${it.referencia}' → '${it.hipotese}'"
                    Op.DELECAO -> "'${it.referencia}' → (sumiu)"
                    Op.INSERCAO -> "(inventou) → '${it.hipotese}'"
                    Op.ACERTO -> ""
                }
            }

        override fun toString(): String =
            "WER=%.1f%% (S=%d D=%d I=%d de N=%d)".format(
                percentual, substituicoes, delecoes, insercoes, palavrasDeReferencia,
            )
    }

    /**
     * Normaliza para a forma em que duas transcrições são comparáveis.
     *
     * Tem de ser aplicada nos **dois** lados. Normalizar só um é como não
     * normalizar: a comparação falha em silêncio e a conclusão vira "o modelo é
     * ruim" quando o defeito é da régua. O projeto já foi mordido por isso no
     * léxico falado (`RotulosFalados.normalizar`), e a lição vale aqui.
     *
     * Divergência deliberada em relação a `RotulosFalados.normalizar`: aqui a
     * pontuação **interna** também cai, porque a hipótese do STT vem pontuada
     * pelo decodificador e a referência do corpus, não. Lá o objetivo é casar um
     * rótulo curto; aqui é contar palavras.
     */
    fun normalizar(texto: String): String =
        Normalizer.normalize(texto.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            // Só pontuação. Dígito e letra sobrevivem — ver o KDoc sobre "três"
            // versus "3", que o produto NÃO pode colapsar.
            .replace(Regex("[\\p{Punct}…“”‘’—–]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun palavras(texto: String): List<String> =
        normalizar(texto).split(' ').filter { it.isNotEmpty() }

    /** WER entre duas frases. */
    fun calcular(referencia: String, hipotese: String): Resultado =
        calcular(palavras(referencia), palavras(hipotese))

    /**
     * WER agregado sobre um corpus.
     *
     * **Agrega erros e palavras, não médias de taxa.** Média de WER por frase dá
     * peso igual a uma frase de duas palavras e a uma de trinta, e é assim que se
     * fabrica um número bonito com um corpus desbalanceado. O WER de um corpus é
     * `ΣE / ΣN`.
     */
    fun calcularCorpus(pares: List<Pair<String, String>>): Resultado {
        var s = 0
        var d = 0
        var i = 0
        var n = 0
        val alinhamento = mutableListOf<Passo>()
        for ((ref, hip) in pares) {
            val r = calcular(ref, hip)
            s += r.substituicoes
            d += r.delecoes
            i += r.insercoes
            n += r.palavrasDeReferencia
            alinhamento += r.alinhamento
        }
        return Resultado(s, d, i, n, alinhamento)
    }

    /** CER: a mesma conta com caractere no lugar de palavra. */
    fun calcularCaracteres(referencia: String, hipotese: String): Resultado =
        calcular(
            normalizar(referencia).replace(" ", "").map { it.toString() },
            normalizar(hipotese).replace(" ", "").map { it.toString() },
        )

    /**
     * Levenshtein com rastro, sobre tokens.
     *
     * Programação dinâmica completa (`(n+1) × (m+1)`) em vez de duas linhas
     * rolantes: o rastro é necessário para separar S de D e de I, e as frases aqui
     * têm dezenas de palavras — a matriz inteira é irrelevante em memória e o
     * código fica legível.
     */
    private fun calcular(ref: List<String>, hip: List<String>): Resultado {
        val n = ref.size
        val m = hip.size
        val custo = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) custo[i][0] = i
        for (j in 0..m) custo[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                custo[i][j] = if (ref[i - 1] == hip[j - 1]) {
                    custo[i - 1][j - 1]
                } else {
                    1 + minOf(
                        custo[i - 1][j - 1], // substituição
                        custo[i - 1][j], // deleção
                        custo[i][j - 1], // inserção
                    )
                }
            }
        }

        // Rastro de volta. A ordem dos testes fixa qual alinhamento ótimo sai
        // quando há empate — o total de erros é o mesmo em qualquer um, mas um
        // rastro determinístico torna o log estável entre rodadas.
        var s = 0
        var d = 0
        var ins = 0
        val passos = ArrayDeque<Passo>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hip[j - 1] && custo[i][j] == custo[i - 1][j - 1] -> {
                    passos.addFirst(Passo(Op.ACERTO, ref[i - 1], hip[j - 1]))
                    i--
                    j--
                }
                i > 0 && j > 0 && custo[i][j] == custo[i - 1][j - 1] + 1 -> {
                    passos.addFirst(Passo(Op.SUBSTITUICAO, ref[i - 1], hip[j - 1]))
                    s++
                    i--
                    j--
                }
                i > 0 && custo[i][j] == custo[i - 1][j] + 1 -> {
                    passos.addFirst(Passo(Op.DELECAO, ref[i - 1], null))
                    d++
                    i--
                }
                else -> {
                    passos.addFirst(Passo(Op.INSERCAO, null, hip[j - 1]))
                    ins++
                    j--
                }
            }
        }
        return Resultado(s, d, ins, n, passos.toList())
    }
}
