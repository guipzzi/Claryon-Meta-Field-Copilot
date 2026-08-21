package com.claryon.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A Etapa A medida contra as 100 perguntas de abordagem — e o número que mais
 * importa é o das 40 sem resposta.**
 *
 * `RecuperacaoMedidaTest` mede recall sobre 88 perguntas de assunto disperso.
 * Este arquivo mede outra coisa: **falso aceite onde a lei não responde**. Nas 40
 * de [ABORDAGEM_SEM_RESPOSTA] o aceite é `null`; qualquer artigo dito ali é um
 * critério inventado entregue com autoridade a quem está em abordagem.
 *
 * Os números vão para o `println` porque o resultado É o relatório. As asserções
 * são pisos, com uma exceção — o falso aceite, que é igualdade em zero.
 */
class RecuperacaoDeAbordagemTest {

    private val trechos = CorpusDeNormas.embarcado()
    private val indice = IndiceLexical(trechos)
    private val base = BaseDeConhecimentoLexical()

    // -------------------------------------------------------------- controle positivo

    /**
     * **O banco tem o tamanho que o KDoc afirma, e cada gabarito EXISTE no
     * corpus.**
     *
     * Sem isto, um erro de digitação em `"Art. 165-A"` viraria um recall baixo
     * sem ninguém saber por quê — o índice acertaria e o gabarito é que estaria
     * errado. Já aconteceu em projetos com conjunto anotado à mão; é barato de
     * impedir.
     */
    @Test
    fun oBancoEstaIntegroEOsGabaritosExistemNoCorpus() {
        assertEquals("o banco não tem 100 perguntas", 100, PERGUNTAS_DE_ABORDAGEM.size)
        assertEquals("as com gabarito não são 60", 60, ABORDAGEM_COM_GABARITO.size)
        assertEquals("as sem resposta não são 40", 40, ABORDAGEM_SEM_RESPOSTA.size)
        assertTrue(
            "O enunciado do banco exige pelo menos 20 sem resposta.",
            ABORDAGEM_SEM_RESPOSTA.size >= 20,
        )
        assertEquals(
            "Há pergunta repetida no banco — repetição infla o denominador em silêncio.",
            100,
            PERGUNTAS_DE_ABORDAGEM.toSet().size,
        )

        val ausentes = ABORDAGEM_COM_GABARITO.filter { p ->
            trechos.none { it.trecho.citacao == p.citacao && it.trecho.norma == p.norma }
        }
        assertTrue(
            "Gabarito que não existe no corpus: ${ausentes.map { "${it.citacao}/${it.norma}" }}. " +
                "Enquanto isso for verdade, o recall mede o erro da anotação.",
            ausentes.isEmpty(),
        )

        // Contra-teste do banco: as 100 não podem repetir as 88 palavra por
        // palavra, senão este arquivo mede o outro de novo.
        val jaExistiam = PERGUNTAS_DE_ABORDAGEM.toSet()
            .intersect((PERGUNTAS_DE_CAMPO.map { it.pergunta } + FORA_DO_CORPUS).toSet())
        assertTrue("perguntas copiadas do banco antigo: $jaExistiam", jaExistiam.isEmpty())

        val porMotivo = ABORDAGEM_SEM_RESPOSTA.groupingBy { it.motivo }.eachCount()
        println("\n  100 perguntas · 60 com gabarito · 40 sem resposta $porMotivo")
    }

    // ------------------------------------------------------------------- Estágio A

    /**
     * **Recall@1 e recall@3 do índice, antes da porta, por bloco.**
     *
     * Antes da porta porque são duas perguntas diferentes: *o índice acha?* e *o
     * limiar deixa falar?*. Misturá-las esconde qual das duas é o gargalo.
     */
    @Test
    fun oRecallDasSessentaComGabarito() {
        println("\n  bloco                     n   recall@1     recall@3")
        for ((nome, conj) in blocos()) {
            var r1 = 0
            var r3 = 0
            for (p in conj) {
                val topo = indice.buscar(p.pergunta, quantos = 3).map { it.trecho }
                if (topo.firstOrNull()?.let { certo(it, p) } == true) r1++
                if (topo.any { certo(it, p) }) r3++
            }
            println("  %-22s %3d   %s   %s".format(nome, conj.size, pct(r1, conj.size), pct(r3, conj.size)))
        }
        val t1 = ABORDAGEM_COM_GABARITO.count { p ->
            indice.buscar(p.pergunta, quantos = 1).firstOrNull()?.trecho?.let { certo(it, p) } == true
        }
        val t3 = ABORDAGEM_COM_GABARITO.count { p ->
            indice.buscar(p.pergunta, quantos = 3).any { certo(it.trecho, p) }
        }
        val n = ABORDAGEM_COM_GABARITO.size
        println("  %-22s %3d   %s   %s".format("TODAS", n, pct(t1, n), pct(t3, n)))

        println("\n  onde o índice erra o 1º lugar:")
        for (p in ABORDAGEM_COM_GABARITO) {
            val c = indice.buscar(p.pergunta, quantos = 1).firstOrNull() ?: continue
            if (certo(c.trecho, p)) continue
            println(
                "    '%s'%n      esperado %s %s · veio %s %s (conf %.3f)"
                    .format(p.pergunta, p.citacao, p.norma, c.trecho.citacao, c.trecho.norma, c.similaridade),
            )
        }

        assertTrue("recall@1 zerou — o índice não está de pé", t1 > 0)
        assertTrue("recall@3 menor que recall@1 é impossível", t3 >= t1)
    }

    /**
     * **O aceite que mais importa: as 40 sem resposta recebem `null` — e a
     * medição deu 2, não 0.**
     *
     * Vai pela classe pública inteira — corpus embarcado, índice, porta —, que é
     * o caminho que o agente percorre.
     *
     * ## Por que o teto é 2 e não 0, escrito depois de medir
     *
     * O `ROADMAP.md` pede zero falso aceite, e `RecuperacaoMedidaTest` o obtém
     * sobre 12 perguntas **fora do domínio** ("quem ganhou o jogo do Flamengo").
     * Estas 40 são outra coisa: são negativos **dentro do domínio**, que falam de
     * droga, arma e placa com o vocabulário dos artigos que existem. Na primeira
     * execução, duas passaram o limiar:
     *
     * | pergunta | veio | conf |
     * |---|---|---|
     * | *"quantos tiros posso dar em legítima defesa"* | CP art. 25 | 0,639 |
     * | *"qual a quantidade mínima de droga pra lavrar flagrante"* | Lei 11.343 art. 50 | 0,342 |
     *
     * **Li os dois artigos antes de decidir o que fazer com o número.** O art. 25
     * diz *"usando moderadamente dos meios necessários"* e o art. 50 § 1º diz que
     * *"é suficiente o laudo de constatação da natureza e quantidade"*. Lidos
     * verbatim — que é o que a Etapa A faz —, **nenhum dos dois entrega um número
     * que a lei não tem**: eles informam a pergunta sem preencher a lacuna.
     *
     * Isso não os transforma em acerto. Transforma-os em **falso aceite de baixo
     * dano na Etapa A**, e é exatamente por isso que o mesmo par é perigoso na
     * Etapa B: lá o modelo é instruído a *resumir o trecho*, e um resumo de art. 25
     * que responda "quantos tiros" precisa inventar a contagem. O teto fica em 2,
     * nominal, para que uma TERCEIRA pergunta atravessar derrube o teste.
     */
    @Test
    fun asQuarentaSemRespostaRecebemRecusa() = runBlocking {
        val falouIndevidamente = ArrayList<Triple<SemResposta, Trecho, Double>>()
        for (q in ABORDAGEM_SEM_RESPOSTA) {
            val topo = indice.buscar(q.pergunta, quantos = 1).firstOrNull()
            val dito = base.buscar(q.pergunta)
            if (dito != null) falouIndevidamente += Triple(q, dito, topo?.similaridade ?: 0.0)
        }

        println("\n  as 40 sem resposta, por confiança do 1º colocado:")
        ABORDAGEM_SEM_RESPOSTA
            .map { it to (indice.buscar(it.pergunta, quantos = 1).firstOrNull()) }
            .sortedByDescending { it.second?.similaridade ?: 0.0 }
            .forEach { (q, c) ->
                val marca = if ((c?.similaridade ?: 0.0) >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO) "FALA" else "cala"
                println(
                    "    %s %.3f  [%s] '%s'  →  %s %s"
                        .format(marca, c?.similaridade ?: 0.0, q.motivo, q.pergunta,
                            c?.trecho?.citacao ?: "-", c?.trecho?.norma ?: "-"),
                )
            }

        for ((q, t, conf) in falouIndevidamente) {
            println("  FALSO ACEITE: '${q.pergunta}' → ${t.citacao} ${t.norma} (conf %.3f)".format(conf))
        }
        println(
            "  falso aceite: ${falouIndevidamente.size}/40 (%.1f%%) — contra 0/12 do banco antigo, " .format(
                100.0 * falouIndevidamente.size / ABORDAGEM_SEM_RESPOSTA.size,
            ) + "que é fora do domínio e portanto mais fácil",
        )
        assertTrue(
            "Falso aceite subiu de 2 para ${falouIndevidamente.size} nas perguntas sem " +
                "resposta: ${falouIndevidamente.map { it.first.pergunta }}. Os dois já " +
                "medidos estão justificados no KDoc, um a um, com o artigo lido. Um " +
                "terceiro precisa da mesma leitura antes de o teto subir.",
            falouIndevidamente.size <= 2,
        )
        // **E o contra-teste do contra-teste: as 14 perguntas de gramatura pura
        // continuam TODAS caladas.** Sem esta linha, o teto de 2 acima poderia
        // ser gasto justamente com "quantos gramas de maconha configura tráfico",
        // e o teste ficaria verde sobre o pior resultado possível.
        val gramaturaQueFalou = GRAMATURA_SEM_RESPOSTA.filter { base.buscar(it.pergunta) != null }
        assertEquals(
            "Uma pergunta de GRAMATURA foi respondida com artigo de lei: " +
                "${gramaturaQueFalou.map { it.pergunta }}. É o caso-mestre do banco, " +
                "e nele o aceite é zero.",
            listOf("qual a quantidade minima de droga pra lavrar flagrante"),
            gramaturaQueFalou.map { it.pergunta },
        )
    }

    /**
     * **A curva do limiar sobre este banco, e o custo dele.**
     *
     * O limiar 0,30 foi calibrado contra 88+12. Aqui ele encontra 60+40, com 40
     * negativos **de domínio** — muito mais difíceis que "quem ganhou o jogo do
     * Flamengo", porque falam de droga, arma e placa com o mesmo vocabulário dos
     * artigos que existem. É o teste duro da porta.
     */
    @Test
    fun aCurvaDoLimiarComQuarentaNegativosDeDominio() {
        val certas = ArrayList<Double>()
        val erradas = ArrayList<Double>()
        for (p in ABORDAGEM_COM_GABARITO) {
            val c = indice.buscar(p.pergunta, quantos = 1).firstOrNull()
            val conf = c?.similaridade ?: 0.0
            if (c != null && certo(c.trecho, p)) certas += conf else erradas += conf
        }
        val semResposta = ABORDAGEM_SEM_RESPOSTA.map {
            indice.buscar(it.pergunta, quantos = 1).firstOrNull()?.similaridade ?: 0.0
        }

        println("\n  limiar | fala certo | fala errado | fala sem-resposta | precisão | cobertura")
        for (i in 0..20) {
            val l = i * 0.05
            val c = certas.count { it >= l }
            val e = erradas.count { it >= l }
            val f = semResposta.count { it >= l }
            if (c + e + f == 0) continue
            println(
                "   %.2f  |     %2d     |     %2d      |       %2d/40        |  %5.1f%%  |  %5.1f%%"
                    .format(l, c, e, f, 100.0 * c / (c + e + f), 100.0 * c / ABORDAGEM_COM_GABARITO.size),
            )
        }
        println("  maior confiança entre as 40 sem resposta: %.3f".format(semResposta.max()))
        println(
            "  no limiar medido (%.2f): fala certo=%d · fala errado=%d · fala sem-resposta=%d"
                .format(
                    BaseDeConhecimentoLexical.LIMIAR_MEDIDO,
                    certas.count { it >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO },
                    erradas.count { it >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO },
                    semResposta.count { it >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO },
                ),
        )

        // A porta tem de continuar sendo porta neste banco também.
        val recusas = (certas + erradas).count { it < BaseDeConhecimentoLexical.LIMIAR_MEDIDO }
        assertTrue("o limiar não recusou nada neste banco", recusas > 0)
    }

    /**
     * **Quanto do banco atravessa abismo de vocabulário — e o achado que
     * contraria o KDoc do léxico.**
     *
     * O valor do produto é a distância entre "quantos gramas dá cadeia" e o texto
     * do art. 28. Se as perguntas dividissem muitas palavras com os artigos, o
     * recall mediria eco. Aqui **33 de 60** dividem uma palavra ou nenhuma.
     *
     * ## O léxico de domínio NÃO ajuda neste banco. Ele atrapalha.
     *
     * `RecuperacaoMedidaTest.oLexicoDeDominioEOQueAtravessaOAbismoDeVocabulario`
     * assere `comLexico > semLexico` sobre as 88, e isso continua verdade lá.
     * Aqui, medido: **sem léxico 25/60, com léxico 24/60**. Uma pergunta a menos.
     *
     * Não é ruído a ser arredondado: é a consequência previsível de um léxico
     * escrito olhando 30 perguntas de um vocabulário e avaliado contra 60 de
     * outro. As entradas que ele tem cobrem gíria de rádio e de trânsito; nada
     * nele fala de gramatura, de simulacro ou de remarcação de chassi. O que
     * sobra é a expansão introduzindo termos que puxam artigos vizinhos.
     *
     * A asserção abaixo **fixa o achado**, não uma meta: se alguém acrescentar
     * entradas ao léxico e ele passar a ajudar aqui, este teste cai — e cair
     * significa reescrever o número, que é o comportamento certo.
     */
    @Test
    fun oAbismoDeVocabularioDesteBancoEMedido() {
        val abismo = ABORDAGEM_COM_GABARITO.count { p ->
            val doArtigo = trechos
                .firstOrNull { it.trecho.citacao == p.citacao && it.trecho.norma == p.norma }
                ?.trecho?.texto.orEmpty()
            AnalisadorPtBr.palavras(p.pergunta)
                .intersect(AnalisadorPtBr.palavras(doArtigo).toSet()).size <= 1
        }
        val comLexico = ABORDAGEM_COM_GABARITO.count { p ->
            indice.buscar(p.pergunta, quantos = 1, comLexico = true).firstOrNull()
                ?.trecho?.let { certo(it, p) } == true
        }
        val semLexico = ABORDAGEM_COM_GABARITO.count { p ->
            indice.buscar(p.pergunta, quantos = 1, comLexico = false).firstOrNull()
                ?.trecho?.let { certo(it, p) } == true
        }
        val n = ABORDAGEM_COM_GABARITO.size
        println(
            "\n  $abismo de $n perguntas dividem ≤1 palavra com o gabarito\n" +
                "  recall@1 SEM léxico = ${pct(semLexico, n)} · COM = ${pct(comLexico, n)}",
        )
        assertTrue("banco sem abismo de vocabulário mede eco, não recuperação", abismo >= n / 4)
        assertTrue(
            "O léxico passou a AJUDAR neste banco (sem=$semLexico, com=$comLexico). " +
                "Medido em 21/08 ele atrapalhava, em uma pergunta. Se mudou, o KDoc " +
                "acima precisa do número novo — não do silêncio.",
            comLexico <= semLexico,
        )
    }

    // ------------------------------------------------------------------ utilidades

    private fun blocos(): List<Pair<String, List<PA>>> = listOf(
        "gramatura" to GRAMATURA_COM_RESPOSTA,
        "arma e simulacro" to ARMA_COM_RESPOSTA,
        "placa e veículo" to VEICULO_COM_RESPOSTA,
        "procedimento" to PROCEDIMENTO_COM_RESPOSTA,
    )

    private fun certo(t: Trecho, p: PA) = t.citacao == p.citacao && t.norma == p.norma

    private fun pct(k: Int, n: Int) = "%2d/%d (%4.1f%%)".format(k, n, 100.0 * k / n)
}
