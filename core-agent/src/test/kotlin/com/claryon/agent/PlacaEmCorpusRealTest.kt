package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Falso positivo medido em português de verdade, não em 22 frases que eu escrevi.**
 *
 * O banco de elocuções (`BancoDePlacasDitadasTest`) tem um defeito que nenhum
 * cuidado conserta: **eu escrevi os negativos**, e ninguém escreve o negativo que
 * derruba o próprio extrator. É o mesmo defeito que o `ESTADO.md` registra sobre o
 * earcon — o falso positivo só virou número quando saiu das frases de teste e foi
 * medido sobre 3,04 h de podcast retidas.
 *
 * O equivalente barato aqui já está no repositório: **`corpus/trechos.jsonl`, 1817
 * trechos de lei em português**, que este projeto já usa como corpus adversarial em
 * `FronteiraDoConhecimentoEmAppTest`. É material duro para este extrator
 * especificamente, porque texto legal é **cheio de número** — "Art. 306", "§ 1º",
 * "Lei nº 9.503, de 23 de setembro de 1997", "R$ 500,00" — e número colado a palavra
 * é exatamente a matéria-prima de uma corrida.
 *
 * ### O teste roda SEM âncora, de propósito
 *
 * Com a âncora, quase nenhum trecho seria sequer examinado — só 18 dos 1817 citam
 * "placa" — e o resultado seria um zero que não prova nada. Sem ela, **o texto
 * inteiro dos 1817 é varrido**, que é o pior caso possível do montador de corridas.
 * Um zero aqui é um zero com significado.
 *
 * ### Limite honesto
 *
 * Texto de lei não é fala de rádio: não tem "meia", não tem alfabeto fonético, e a
 * pontuação é outra. Isto mede a **precisão do montador sobre português real com
 * muito número** — não mede recall, e não substitui medir com fala humana pelo fone
 * HFP, que continua nos quebrados do `ESTADO.md`.
 */
class PlacaEmCorpusRealTest {

    private fun fabricadas(textos: List<String>, ancorar: Boolean): List<Pair<String, String>> =
        textos.mapNotNull { texto ->
            (PlacaDitada.ler(texto, ancorar = ancorar) as? PlacaDitada.Leitura.Reconhecida)
                ?.let { it.placa to texto }
        }

    /**
     * **A configuração de produção, sobre 1817 trechos: zero.**
     *
     * Com âncora é como o roteador chama — ele só chega aqui depois de casar
     * "consultar placa", e a palavra "placa" está na fala por construção.
     */
    @Test
    fun nenhumTrechoDeLeiViraPlaca_naConfiguracaoDeProducao() {
        val textos = textosDoCorpus()
        assertTrue(
            "corpus com ${textos.size} trechos — abaixo de mil isto não mede nada",
            textos.size >= 1_000,
        )
        val comAncora = fabricadas(textos, ancorar = true)
        println("── corpus real: ${textos.size} trechos de lei ──")
        println("  corridas montadas ......... ${textos.sumOf { PlacaDitada.corridasDe(it, ancorar = false).size }}")
        println("  corridas de 7 caracteres .. ${textos.sumOf { t -> PlacaDitada.corridasDe(t, ancorar = false).count { it.length == 7 } }}")
        println("  placas COM âncora ......... ${comAncora.size}")
        assertEquals(
            "cada uma destas seria consulta a um veículo que ninguém pediu:\n" +
                comAncora.joinToString("\n") { (p, t) -> "  $p ← ${t.take(160)}" },
            0,
            comAncora.size,
        )
    }

    /**
     * **O contra-teste da gramática sobre texto real — e ele é mais forte que o do
     * banco escrito à mão.**
     *
     * Zero placas fabricadas só significa alguma coisa se alguma coisa **chegou** à
     * validação. Chegou: o montador forma dezenas de corridas de exatamente sete
     * caracteres a partir de texto de lei — "Art. 306", "§ 1º", enumerações, valores
     * — e a gramática reprova **todas**.
     *
     * Um extrator sem a gramática, que aceitasse toda corrida de sete, consultaria a
     * base uma vez para cada uma delas. Os dois números são calculados aqui e o teste
     * exige que difiram.
     */
    @Test
    fun aGramaticaBarraTodasAsCorridasDeSeteQueOTextoDeLeiForma() {
        val textos = textosDoCorpus()
        val seteCaracteres = textos.flatMap { PlacaDitada.corridasDe(it, ancorar = false) }
            .filter { it.length == 7 }
        val aceitasSemGramatica = seteCaracteres.size
        val aceitasComGramatica = seteCaracteres.count { PlacaValidator.formato(it) != null }

        println("── contra-teste da gramática, sobre texto de lei ──")
        println("  corridas de 7 que chegaram à validação: $aceitasSemGramatica")
        println("  exemplos: ${seteCaracteres.distinct().take(12)}")
        println("  aceitas pela gramática ...............: $aceitasComGramatica")

        assertTrue(
            "se nada de 7 caracteres se formasse, o zero de falso positivo seria " +
                "do montador, não da gramática — e este teste não provaria nada",
            aceitasSemGramatica > 0,
        )
        assertEquals("nenhuma corrida de texto de lei pode ter forma de placa", 0, aceitasComGramatica)
    }

    /**
     * **O controle positivo — sem ele o zero acima não vale nada.**
     *
     * Um extrator quebrado que devolvesse sempre `null` passaria no teste anterior
     * com nota máxima. Este teste enfia uma placa ditada **dentro** de um trecho de
     * lei real e exige que ela seja lida: prova que o instrumento estava ligado
     * enquanto media zero.
     *
     * É a mesma lição que o KDoc de `FronteiraDoConhecimentoEmAppTest.campo` registra
     * — lá, um separador de JSON diferente do esperado produzia "zero trechos lidos"
     * e teria dado um "nenhuma norma vira ação" verdíssimo calculado sobre lista
     * vazia.
     */
    @Test
    fun oInstrumentoEstavaLigado_placaDitadaDentroDeTextoDeLeiEEncontrada() {
        val trecho = textosDoCorpus().first { it.length > 200 }
        val comPlaca = "$trecho Claryon, consultar placa tango bravo unido três delta sete zero."
        assertEquals(
            "se isto falhar, o zero do teste anterior é do instrumento, não do extrator",
            PlacaDitada.Leitura.Reconhecida("TBU3D70", PlacaValidator.Formato.MERCOSUL),
            PlacaDitada.ler(comPlaca),
        )
    }

    /**
     * **A âncora, e o que a medição NÃO conseguiu provar sobre ela.**
     *
     * Ela reduz a superfície examinada, e isso está asserido abaixo. O que ela **não**
     * mostra é diferença em falso positivo: 0 com âncora e 0 sem, tanto nas 84
     * elocuções quanto nos 1817 trechos. Pelo critério da casa, hipótese que não muda
     * número é peso morto — então por que ela fica?
     *
     * Porque ela **já mostrou diferença uma vez, e o achado foi consertado na
     * origem.** Antes de a regra de caixa alta exigir formato de placa, a varredura
     * sem âncora fabricava `AII1212` a partir de *"para a categoria A; II - 12 (doze)
     * anos"* — algarismo romano de enumeração colado a número por extenso, sete
     * caracteres em `LLLNNNN` perfeita. Consertado o montador, o número caiu para
     * zero dos dois lados, e as corridas de sete caíram de 194 para 93.
     *
     * Ou seja: o que a âncora segurava passou a ser segurado antes dela. Ela fica
     * como defesa em profundidade, e **isto aqui é o registro honesto de que ela está
     * sem prova própria** — não a afirmação de que ela é essencial. Se alguém quiser
     * tirá-la, o custo a medir é sobre texto que não seja lei nem frase minha.
     */
    @Test
    fun aAncoraReduzASuperficieExaminada() {
        val textos = textosDoCorpus()
        val citamPlaca = textos.count { Regex("\\bplacas?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val comAncora = textos.sumOf { PlacaDitada.corridasDe(it, ancorar = true).size }
        val semAncora = textos.sumOf { PlacaDitada.corridasDe(it, ancorar = false).size }
        println("── âncora sobre o corpus ──")
        println("  trechos que citam 'placa' .... $citamPlaca de ${textos.size}")
        println("  corridas com âncora .......... $comAncora")
        println("  corridas sem âncora .......... $semAncora")
        assertTrue("a âncora tem de reduzir a superfície, senão não serve", comAncora < semAncora)
    }

    // ── leitura do corpus ─────────────────────────────────────────────────────

    /**
     * O leitor mora em [CorpusDeLei] desde que `PlacaDitadaNoRoteadorTest` passou a
     * precisar do mesmo texto: duas cópias de um leitor divergem no primeiro conserto
     * aplicado a uma só delas — e este leitor já foi consertado uma vez, de regex
     * catastrófica para varredura linear.
     */
    private fun textosDoCorpus(): List<String> = CorpusDeLei.textos()
}
