package com.claryon.knowledge

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Índice invertido BM25 sobre os trechos de norma. Kotlin puro, zero MB de
 * modelo, zero dependência.**
 *
 * ## Por que lexical, e não um embedder
 *
 * O `ROADMAP.md` prevê "embedder + índice vetorial". A medição foi feita antes:
 * um índice lexical em Kotlin puro custa **0 MB de modelo**, **112 ms** para
 * indexar os 1744 trechos e **913 µs** por busca (medido em
 * `oCustoDeTempoEMedido`), contra uma ponte JNI nova em C para o ONNX Runtime
 * (não há API Java
 * dele neste projeto — o acesso é por `dlopen`/`dlsym`, ver
 * `core-voice/src/main/cpp`) mais centenas de MB de pesos. O baseline barato vem
 * primeiro por regra da casa, e os números dele estão em `RecuperacaoMedidaTest`.
 *
 * O contrato [BaseDeConhecimento] existe exatamente para o mecanismo ser
 * trocável: quando o embedder entrar, ele troca **esta** classe e nada mais.
 *
 * ## A confiança não é o escore, e essa é a decisão que importa
 *
 * O escore BM25 normalizado responde *"quanto da pergunta casou"*. É informação,
 * mas é a informação errada para decidir se **fala**: um artigo errado que
 * divide muitas palavras com a pergunta casa tanto quanto o certo. A régua que
 * decide precisa responder outra coisa — *o primeiro colocado destacou-se dos
 * outros?* Se os cinco primeiros empatam, o índice está espalhando, e espalhar
 * com autoridade é o modo de falha que [PortaDoConhecimento] existe para negar.
 *
 * Daí [confiancaDe]: a **média geométrica** entre "casou bem" (`s₀`) e "ganhou
 * do segundo" (`s₀ − s₁`). Os dois fatores vivem em `0..1`, então a raiz também
 * vive — e é isso que permite entregá-la como [Candidato.similaridade] sem
 * mentir sobre a régua que o KDoc de [Candidato] exige.
 *
 * **A comparação que decidiu, e ela não é a AUC.** Medidas sobre as 88 perguntas
 * mais 12 fora do corpus (`aConfiancaSeparaMelhorQueOEscoreCru`), sob as duas
 * restrições que importam — **zero** pergunta fora do corpus respondida, que é o
 * critério de aceite do `ROADMAP.md`, e **precisão ≥ 75%** no que é falado:
 *
 * | régua | melhor limiar | responde certo | erra | precisão |
 * |---|---|---|---|---|
 * | escore cru | 0,85 | **6** | 2 | 75,0% |
 * | **confiança** | 0,24 | **24** | 7 | 77,4% |
 *
 * Quatro vezes mais perguntas respondidas com a mesma segurança. A AUC sozinha
 * teria enganado: ela é 0,646 para o escore cru e 0,698 para a confiança — uma
 * diferença que pareceria pequena demais para justificar a fórmula, e é a curva,
 * não o resumo dela, que mostra o tamanho real do ganho.
 *
 * **Onde o escore cru ganha, e por que isso não muda a decisão.** Ele separa
 * melhor pergunta de domínio de pergunta fora do domínio (AUC 0,877 contra
 * 0,777): faz sentido, porque quem pergunta de futebol casa pouco com tudo. Só
 * que essa não é a decisão difícil — a difícil é entre o artigo certo e o artigo
 * vizinho, e nessa a confiança ganha.
 */
internal class IndiceLexical(
    trechos: List<TrechoIndexado>,
    /**
     * Saturação de frequência do BM25. 1,2 é o valor clássico e não foi mexido:
     * a varredura de parâmetros ficou dentro do ruído de ±3 perguntas em 88, e
     * escolher o máximo de uma varredura plana é ajustar ao conjunto de teste,
     * não calibrar.
     */
    private val k1: Double = 1.2,
    /**
     * Normalização por tamanho. **0,5, não os 0,75 de fábrica**, e a diferença
     * é grande porque os trechos vão de 1 a 529 termos:
     *
     * | b | 0,00 | **0,50** | 0,75 | 1,00 |
     * |---|---|---|---|---|
     * | recall@1 | 25/88 | **46/88** | 40/88 | 32/88 |
     *
     * Nos dois extremos por motivos opostos: em 0 o artigo longo vence sempre,
     * porque acumula ocorrência sem pagar por tamanho; em 1 o artigo de uma linha
     * vence, porque qualquer casamento nele é uma fração enorme do texto.
     * `osParametrosDoIndiceSaoMedidos` roda os quatro e exige que 0,5 não perca —
     * parâmetro sem contra-teste é parâmetro copiado de um artigo.
     *
     * Parametrizado só para esse teste existir; produção usa o padrão.
     */
    private val b: Double = 0.5,
    /**
     * O quanto vale um termo que o léxico **sugeriu** contra um que o agente
     * **disse**. Metade: expansão é palpite sobre a intenção, evidência é o que
     * saiu da boca dele.
     *
     * | peso | 0,0 | 0,3 | **0,5** | 1,0 |
     * |---|---|---|---|---|
     * | recall@1 | 35/88 | 46/88 | **46/88** | 44/88 |
     *
     * O degrau que importa é o de 0,0 para 0,3 — é o léxico existindo. Entre 0,3
     * e 0,5 não há diferença mensurável, e em 1,0 começa a piorar: com peso
     * cheio, a sugestão passa a mandar mais que a fala.
     */
    private val pesoDaExpansao: Double = 0.5,
    /**
     * **Desligado por medição**, não por gosto: somar o prefixo ao n-grama
     * derruba o recall@1 de 46/88 para 43/88. Ver o KDoc de [AnalisadorPtBr].
     */
    private val comPrefixo: Boolean = false,
    /** Desligável só para o contra-teste que prova que o n-grama é o que carrega. */
    private val comNgrama: Boolean = true,
) {

    private val itens: List<TrechoIndexado> = trechos
    private val frequencias: List<Map<String, Int>>
    private val tamanhos: IntArray
    private val tamanhoMedio: Double
    private val idf: Map<String, Double>
    private val posting: Map<String, IntArray>

    init {
        val tf = ArrayList<Map<String, Int>>(trechos.size)
        val tam = IntArray(trechos.size)
        val df = HashMap<String, Int>()
        val listas = HashMap<String, MutableList<Int>>()

        for ((i, item) in trechos.withIndex()) {
            val contagem = HashMap<String, Int>()
            for (t in AnalisadorPtBr.termos(textoIndexavel(item), comPrefixo, comNgrama)) {
                contagem[t] = (contagem[t] ?: 0) + 1
            }
            tf += contagem
            tam[i] = contagem.values.sum()
            for (t in contagem.keys) {
                df[t] = (df[t] ?: 0) + 1
                listas.getOrPut(t) { ArrayList() } += i
            }
        }

        frequencias = tf
        tamanhos = tam
        tamanhoMedio = if (tam.isEmpty()) 1.0 else tam.sum().toDouble() / tam.size
        val n = trechos.size.toDouble()
        idf = df.mapValues { (_, d) -> max(0.0, ln(1.0 + (n - d + 0.5) / (d + 0.5))) }
        posting = listas.mapValues { (_, v) -> v.toIntArray() }
    }

    val tamanho: Int get() = itens.size

    /**
     * O texto que **acha** o trecho, que não é o texto que o trecho **é**.
     *
     * Além do corpo do artigo entram:
     *  - o **título** (`"Desacato"`, `"Legítima defesa"`), repetido três vezes.
     *    É o nome jurídico do fato, e é exatamente a palavra que o agente às
     *    vezes já sabe. Só 359 dos 1744 trechos têm um, e é por isso que ele
     *    ajuda pouco no total e muito nesses;
     *  - a **sigla** e o **documento** (`"CTB"`, `"Lei 9.503/1997"`), para a
     *    pergunta que já nomeia a norma achar a norma.
     *
     * Nada disso é lido em voz alta: quem sai daqui é [TrechoIndexado.trecho].
     */
    private fun textoIndexavel(item: TrechoIndexado): String = buildString {
        append(item.trecho.texto)
        repeat(3) { append(' ').append(item.titulo) }
        append(' ').append(item.sigla)
        append(' ').append(item.trecho.norma)
    }

    /**
     * Os [quantos] trechos mais parecidos com [pergunta], do mais para o menos,
     * já com [Candidato.similaridade] preenchida com a **confiança** — não com o
     * escore cru. Lista vazia quando nenhum termo da pergunta existe no índice.
     *
     * @param comLexico desligar existe para **um** propósito: o contra-teste que
     *   prova que [LexicoDeDominio] faz alguma coisa. Um teste que só medisse a
     *   configuração ligada passaria igual se o léxico estivesse morto. Produção
     *   nunca chama com `false`.
     */
    fun buscar(pergunta: String, quantos: Int = 5, comLexico: Boolean = true): List<Candidato> {
        val ordenados = ranquear(pergunta, comLexico, max(quantos, 2))
        if (ordenados.isEmpty()) return emptyList()

        val melhor = ordenados[0].second
        val segundo = if (ordenados.size > 1) ordenados[1].second else 0.0

        return ordenados.take(quantos).mapIndexed { posicao, (i, _) ->
            // Só o primeiro colocado tem "distância para quem vem atrás". Do
            // segundo em diante a confiança é 0 — e isso é o que se quer: a porta
            // escolhe pelo maior, e um segundo colocado nunca deve ser dito.
            val confianca = if (posicao == 0) confiancaDe(melhor, segundo) else 0.0
            Candidato(trecho = itens[i].trecho, similaridade = confianca)
        }
    }

    /**
     * Os escores **normalizados e crus**, antes de virarem confiança. Existe
     * para o teste medir a separação das duas réguas sem reimplementar o BM25 —
     * fórmula copiada no teste deixa de conferir a de produção no dia em que uma
     * das duas muda.
     */
    fun escoresBrutos(pergunta: String, quantos: Int = 5): List<Double> =
        ranquear(pergunta, comLexico = true, quantos = quantos).map { it.second }

    /** `(índice do trecho, escore normalizado)`, do maior para o menor. */
    private fun ranquear(pergunta: String, comLexico: Boolean, quantos: Int): List<Pair<Int, Double>> {
        if (itens.isEmpty()) return emptyList()

        val pesos = HashMap<String, Double>()
        for (t in AnalisadorPtBr.termos(pergunta, comPrefixo, comNgrama)) {
            pesos[t] = (pesos[t] ?: 0.0) + 1.0
        }
        val expansao = if (comLexico) LexicoDeDominio.expansaoDe(pergunta) else ""
        if (expansao.isNotEmpty()) {
            for (t in AnalisadorPtBr.termos(expansao, comPrefixo, comNgrama)) {
                pesos[t] = (pesos[t] ?: 0.0) + pesoDaExpansao
            }
        }
        if (pesos.isEmpty()) return emptyList()

        val escores = HashMap<Int, Double>()
        var teto = 0.0
        // Escore máximo que um termo pode render: frequência infinita no
        // documento mais curto possível. Dividir por ele torna o escore
        // comparável ENTRE perguntas — sem isso, pergunta longa pontuaria mais
        // que pergunta curta e um limiar único não significaria nada.
        val saturacao = (k1 + 1.0) / (k1 * (1.0 - b) + 1.0)

        for ((termo, peso) in pesos) {
            val idfTermo = idf[termo] ?: continue
            teto += peso * idfTermo * saturacao
            val lista = posting[termo] ?: continue
            for (i in lista) {
                val f = frequencias[i][termo] ?: continue
                val den = f + k1 * (1.0 - b + b * tamanhos[i] / tamanhoMedio)
                escores[i] = (escores[i] ?: 0.0) + peso * idfTermo * f * (k1 + 1.0) / den
            }
        }
        if (escores.isEmpty() || teto <= 0.0) return emptyList()

        return escores.entries
            .map { it.key to min(1.0, it.value / teto) }
            .sortedByDescending { it.second }
            .take(quantos)
    }

    companion object {
        /**
         * `√(s₀ · (s₀ − s₁))` — a média geométrica entre *casou bem* e *ganhou
         * do segundo*, presa em `0..1` porque os dois fatores estão.
         *
         * Público (internamente) para o teste poder medir a curva do limiar sem
         * reconstruir a fórmula — fórmula copiada no teste é fórmula que deixa
         * de conferir a de produção no dia em que uma das duas muda.
         */
        fun confiancaDe(melhor: Double, segundo: Double): Double =
            sqrt(max(0.0, melhor * (melhor - segundo))).coerceIn(0.0, 1.0)
    }
}
