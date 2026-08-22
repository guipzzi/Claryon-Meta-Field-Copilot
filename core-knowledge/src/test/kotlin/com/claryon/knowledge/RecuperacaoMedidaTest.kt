package com.claryon.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Mede a recuperação e a curva do limiar sobre o corpus real, e trava os
 * números para que uma regressão apareça como teste vermelho.**
 *
 * Todo número escrito em KDoc neste módulo — recall@1, precisão, cobertura, o
 * `0 de 12` fora do corpus — sai daqui. Se alguém mexer no analisador, no
 * léxico ou no BM25 e os números piorarem, este teste cai. Se melhorarem além
 * do piso, ele também avisa: piso desatualizado é piso que já não mede.
 *
 * As asserções são **pisos**, não igualdades, com uma exceção — o falso aceite
 * fora do corpus, que é igualdade em zero porque é critério de aceite do
 * `ROADMAP.md` e não uma métrica a ser negociada.
 */
class RecuperacaoMedidaTest {

    private val trechos = CorpusDeNormas.embarcado()
    private val base = BaseDeConhecimentoLexical()
    private val indice = IndiceLexical(trechos)

    // --------------------------------------------------------------- o índice existe

    @Test
    fun oCorpusChegaAoArtefatoDoModulo() {
        // Controle positivo de tudo o que vem abaixo. Sem ele, um recurso que
        // sumiu do jar faria TODA pergunta virar recusa — e um teste que só
        // conferisse "não lança" ficaria verde sobre um índice vazio, relatando
        // 0 falso aceite com orgulho.
        assertTrue(
            "O recurso ${CorpusDeNormas.RECURSO} não está no artefato. Enquanto " +
                "isso for verdade, todo número deste arquivo mede o vazio.",
            indice.tamanho > 1700,
        )
        assertEquals(
            "1817 trechos no arquivo menos os 73 revogados. Se este número mudar, " +
                "ou o corpus mudou (e os gabaritos precisam ser reconferidos) ou o " +
                "filtro de revogado parou de filtrar.",
            1744,
            indice.tamanho,
        )
    }

    /**
     * **O que a Etapa A custa de tempo, medido — porque "quase zero" é promessa.**
     *
     * O aceite da Fase 4 dá ≤ 4 s do fim da fala até a resposta falada, e a
     * recuperação é só uma parcela disso. Aqui ficam as duas que este módulo
     * controla: montar o índice uma vez, e responder uma pergunta.
     */
    @Test
    fun oCustoDeTempoEMedido() {
        val t0 = System.nanoTime()
        val novo = IndiceLexical(trechos)
        val montagemMs = (System.nanoTime() - t0) / 1_000_000

        // Aquece: a primeira busca paga JIT que a segunda não paga.
        repeat(5) { novo.buscar("dirigir bebado da cadeia") }
        val t1 = System.nanoTime()
        for (p in PERGUNTAS_DE_CAMPO) novo.buscar(p.pergunta)
        val porBuscaUs = (System.nanoTime() - t1) / 1_000 / PERGUNTAS_DE_CAMPO.size

        println("\n  montar o índice de ${novo.tamanho} trechos: $montagemMs ms · busca: $porBuscaUs µs")
        assertTrue(
            "Montar o índice levou $montagemMs ms. É pago uma vez, com `lazy`, mas " +
                "acima de alguns segundos deixa de caber no primeiro 'Claryon' " +
                "do turno e precisa sair do caminho da pergunta.",
            montagemMs < 5_000,
        )
        assertTrue(
            "Uma busca levou $porBuscaUs µs. O orçamento do aceite é 4 s para a " +
                "resposta INTEIRA, e a recuperação é a parcela barata dele.",
            porBuscaUs < 100_000,
        )
    }

    // ------------------------------------------------------------------- recall

    @Test
    fun oRecallSeSustentaNasTresParticoes() {
        val d = medir(DESENVOLVIMENTO)
        val r = medir(RESERVADAS)
        val p = medir(POSTERIORES)
        val t = medir(PERGUNTAS_DE_CAMPO)

        println(
            """
            |
            |  partição            n   recall@1   recall@3
            |  desenvolvimento   ${DESENVOLVIMENTO.size}   ${pct(d.emPrimeiro, d.n)}      ${pct(d.emTres, d.n)}
            |  reservadas        ${RESERVADAS.size}   ${pct(r.emPrimeiro, r.n)}      ${pct(r.emTres, r.n)}
            |  posteriores       ${POSTERIORES.size}   ${pct(p.emPrimeiro, p.n)}      ${pct(p.emTres, p.n)}
            |  TODAS             ${t.n}   ${pct(t.emPrimeiro, t.n)}      ${pct(t.emTres, t.n)}
            """.trimMargin(),
        )

        assertTrue("recall@1 geral caiu abaixo do medido (46/88)", t.emPrimeiro >= 46)
        assertTrue("recall@3 geral caiu abaixo do medido (60/88)", t.emTres >= 60)

        // **O contra-teste da honestidade do léxico.** Se ele tivesse sido
        // ajustado às perguntas de desenvolvimento, o recall despencaria nas
        // partições que ele nunca viu. Exigir que as duas reservadas fiquem
        // perto da de desenvolvimento é o que impede "medi no que treinei" de
        // voltar sem ninguém notar.
        val naoVistas = r.emPrimeiro + p.emPrimeiro
        val nNaoVistas = r.n + p.n
        assertTrue(
            "As partições que o léxico nunca viu (${pct(naoVistas, nNaoVistas)}) ficaram " +
                "muito abaixo da que ele viu (${pct(d.emPrimeiro, d.n)}). Isso é a assinatura " +
                "de léxico ajustado ao conjunto de avaliação — o número de cima deixa de " +
                "valer como previsão.",
            naoVistas.toDouble() / nNaoVistas >= d.emPrimeiro.toDouble() / d.n - 0.15,
        )
    }

    /**
     * **O contra-teste do léxico de domínio: sem ele, o recall tem de cair.**
     *
     * Um teste que só conferisse "com léxico dá 47,7%" passaria também se o
     * léxico não estivesse fazendo nada e o índice sozinho já desse isso. Aqui
     * as duas configurações rodam e é exigido que **difiram**, na direção certa.
     */
    @Test
    fun oLexicoDeDominioEOQueAtravessaOAbismoDeVocabulario() {
        val comLexico = acertos(PERGUNTAS_DE_CAMPO, comLexico = true)
        val semLexico = acertos(PERGUNTAS_DE_CAMPO, comLexico = false)
        val n = PERGUNTAS_DE_CAMPO.size

        // Quantas perguntas dividem UMA palavra ou nenhuma com o artigo que as
        // responde. É a medida do abismo, e é o que justifica a camada existir.
        val abismo = PERGUNTAS_DE_CAMPO.count { p ->
            val doArtigo = trechos
                .firstOrNull { it.trecho.citacao == p.citacao && it.trecho.norma == p.norma }
                ?.trecho?.texto.orEmpty()
            val emComum = AnalisadorPtBr.palavras(p.pergunta)
                .intersect(AnalisadorPtBr.palavras(doArtigo).toSet())
            emComum.size <= 1
        }

        println(
            "  ${LexicoDeDominio.tamanho} entradas · $abismo de $n perguntas dividem " +
                "≤1 palavra com o gabarito\n" +
                "  recall@1 SEM léxico = ${pct(semLexico, n)} · COM = ${pct(comLexico, n)}",
        )

        assertTrue("o léxico esvaziou", LexicoDeDominio.tamanho >= 100)
        assertTrue(
            "Se quase nenhuma pergunta caísse no abismo de vocabulário, esta camada " +
                "não teria motivo e o KDoc dela mentiria.",
            abismo >= n / 3,
        )
        // O contra-teste propriamente dito: as duas configurações têm de DIFERIR,
        // e na direção certa. Com o léxico morto, este número volta a bater.
        assertTrue(
            "Ligar e desligar o léxico deu o mesmo recall ($semLexico). Ou ele não " +
                "está no caminho, ou não faz nada — nos dois casos o KDoc que diz " +
                "'39,8% → 52,3%' está mentindo.",
            comLexico > semLexico,
        )
        assertTrue("recall@1 sem léxico ficou acima do medido (35/88) — reconfira o KDoc", semLexico <= 35)
        assertTrue("recall@1 com léxico regrediu abaixo do medido (46/88)", comLexico >= 46)
    }

    /**
     * **Cada parâmetro escolhido roda contra as alternativas, aqui.**
     *
     * Parâmetro sem contra-teste é parâmetro copiado de um artigo. Este teste
     * existe para que nenhum número de KDoc deste módulo seja lembrança: se o
     * `b = 0,5` deixar de ser defensável, ele cai.
     */
    @Test
    fun osParametrosDoIndiceSaoMedidos() {
        println("\n  --- normalização por tamanho (b) ---")
        val porB = listOf(0.0, 0.5, 0.75, 1.0).associateWith { b ->
            acertosCom(IndiceLexical(trechos, b = b))
        }
        porB.forEach { (b, k) -> println("   b=%.2f  recall@1 = %s".format(b, pct(k, PERGUNTAS_DE_CAMPO.size))) }
        assertTrue(
            "b=0,5 deixou de ser a melhor escolha entre as testadas ($porB). O KDoc " +
                "de IndiceLexical.b afirma que os trechos de uma linha ganham do " +
                "artigo certo quando b sobe — reconfira antes de mudar o padrão.",
            porB.getValue(0.5) >= porB.values.max(),
        )

        println("  --- as duas metades do analisador ---")
        val n = PERGUNTAS_DE_CAMPO.size
        val porAnalisador = LinkedHashMap<String, Int>()
        for ((nome, idx) in listOf(
            "só prefixo" to IndiceLexical(trechos, comPrefixo = true, comNgrama = false),
            "só n-grama" to IndiceLexical(trechos),
            "ambos" to IndiceLexical(trechos, comPrefixo = true, comNgrama = true),
        )) {
            var r1 = 0; var r3 = 0; var certo = 0; var errado = 0
            for (p in PERGUNTAS_DE_CAMPO) {
                val topo = idx.buscar(p.pergunta, quantos = 3)
                val ok = { c: Candidato -> c.trecho.citacao == p.citacao && c.trecho.norma == p.norma }
                if (topo.firstOrNull()?.let(ok) == true) r1++
                if (topo.any(ok)) r3++
                val c0 = topo.firstOrNull() ?: continue
                if (c0.similaridade >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO) {
                    if (ok(c0)) certo++ else errado++
                }
            }
            val fora = FORA_DO_CORPUS.count {
                (idx.buscar(it, quantos = 1).firstOrNull()?.similaridade ?: 0.0) >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO
            }
            val part = listOf("dev" to DESENVOLVIMENTO, "res" to RESERVADAS, "pos" to POSTERIORES).joinToString(" ") { (nm, cj) ->
                "$nm=" + cj.count { p -> idx.buscar(p.pergunta, quantos = 1).firstOrNull()
                    ?.let { it.trecho.citacao == p.citacao && it.trecho.norma == p.norma } == true } + "/" + cj.size
            }
            println("   %-11s r@1=%s r@3=%s | no limiar: certo=%d errado=%d fora=%d/%d | %s"
                .format(nome, pct(r1, n), pct(r3, n), certo, errado, fora, FORA_DO_CORPUS.size, part))
            porAnalisador[nome] = r1
        }
        assertTrue(
            "O n-grama sozinho deixou de ser a melhor configuração ($porAnalisador). " +
                "O KDoc de AnalisadorPtBr afirma isso com números; se mudou, os " +
                "números mudaram junto e precisam ser reescritos — não silenciados.",
            porAnalisador.getValue("só n-grama") >= porAnalisador.values.max(),
        )
        assertTrue(
            "Somar o prefixo voltou a ajudar. Era para piorar — reconfira o KDoc.",
            porAnalisador.getValue("ambos") <= porAnalisador.getValue("só n-grama"),
        )

        println("  --- peso da expansão do léxico ---")
        val porPeso = listOf(0.0, 0.3, 0.5, 1.0).associateWith { p ->
            acertosCom(IndiceLexical(trechos, pesoDaExpansao = p))
        }
        porPeso.forEach { (p, k) -> println("   peso=%.1f  recall@1 = %s".format(p, pct(k, n))) }
        assertTrue(
            "com peso 0 a expansão não faz nada, e o recall tem de cair",
            porPeso.getValue(0.5) > porPeso.getValue(0.0),
        )
    }

    /**
     * **Por que a confiança é `√(s₀·(s₀−s₁))` e não o escore cru.**
     *
     * A comparação que decide não é a AUC, é a curva: para cada régua, quanta
     * precisão se compra por quanta cobertura. O escore cru satura — sobe o
     * limiar e a precisão quase não anda, porque um artigo errado que divide
     * muitas palavras com a pergunta pontua alto do mesmo jeito. A confiança
     * pergunta outra coisa: *o primeiro colocado destacou-se dos outros?*
     */
    @Test
    fun aConfiancaSeparaMelhorQueOEscoreCru() {
        val certasEscore = ArrayList<Double>()
        val erradasEscore = ArrayList<Double>()
        val certasConf = ArrayList<Double>()
        val erradasConf = ArrayList<Double>()
        val foraEscore = ArrayList<Double>()
        val foraConf = ArrayList<Double>()

        for (p in PERGUNTAS_DE_CAMPO) {
            val (s0, s1, acertou) = topoDe(p)
            (if (acertou) certasEscore else erradasEscore) += s0
            (if (acertou) certasConf else erradasConf) += IndiceLexical.confiancaDe(s0, s1)
        }
        for (q in FORA_DO_CORPUS) {
            val bruto = indice.escoresBrutos(q)
            val s0 = bruto.getOrElse(0) { 0.0 }
            val s1 = bruto.getOrElse(1) { 0.0 }
            foraEscore += s0
            foraConf += IndiceLexical.confiancaDe(s0, s1)
        }

        val aucEscore = auc(certasEscore, erradasEscore)
        val aucConf = auc(certasConf, erradasConf)
        println(
            "\n  AUC acerto×erro: escore cru %.3f · confiança %.3f".format(aucEscore, aucConf) +
                "  |  acerto×fora: %.3f · %.3f".format(auc(certasEscore, foraEscore), auc(certasConf, foraConf)),
        )

        // O que realmente decide: sob as MESMAS duas restrições — zero falso
        // aceite fora do corpus, e precisão mínima de 75% no que é falado —,
        // qual das duas réguas responde mais perguntas?
        val porEscore = melhorPontoDeOperacao(certasEscore, erradasEscore, foraEscore, 0.75)
        val porConfianca = melhorPontoDeOperacao(certasConf, erradasConf, foraConf, 0.75)
        println(
            "  máxima cobertura com ZERO falso aceite e precisão ≥ 75%:\n" +
                "    escore cru: limiar %.2f → %d certos, %d errados (precisão %.1f%%)\n"
                    .format(porEscore.limiar, porEscore.certos, porEscore.errados, 100.0 * porEscore.precisao) +
                "    confiança : limiar %.2f → %d certos, %d errados (precisão %.1f%%)"
                    .format(porConfianca.limiar, porConfianca.certos, porConfianca.errados, 100.0 * porConfianca.precisao),
        )
        println("  maior valor visto fora do corpus: escore %.3f · confiança %.3f"
            .format(foraEscore.max(), foraConf.max()))
        println("  confiança de cada pergunta fora do corpus:")
        FORA_DO_CORPUS.zip(foraConf).sortedByDescending { it.second }
            .forEach { (q, c) -> println("    %.3f  '%s'".format(c, q)) }

        println("\n  régua ESCORE CRU:")
        curva(certasEscore, erradasEscore, foraEscore)
        println("  régua CONFIANÇA √(s0*(s0-s1)):")
        curva(certasConf, erradasConf, foraConf)
        assertTrue("a confiança tem de separar acerto de erro acima do acaso", aucConf > 0.65)
        assertTrue(
            "Sob as mesmas restrições (zero falso aceite, precisão ≥ 75%), a " +
                "confiança respondeu ${porConfianca.certos} perguntas contra " +
                "${porEscore.certos} do escore cru. Se o escore cru empatar ou ganhar, a " +
                "fórmula de confiancaDe perdeu a razão de existir e o KDoc dela mente.",
            porConfianca.certos > porEscore.certos,
        )
    }

    private fun curva(c: List<Double>, e: List<Double>, f: List<Double>) {
        for (i in 0..20) {
            val l = i * 0.05
            val cc = c.count { it >= l }; val ee = e.count { it >= l }; val ff = f.count { it >= l }
            if (cc + ee + ff == 0) continue
            println("    %.2f | certo=%2d errado=%2d fora=%2d | precisao=%5.1f%% cobertura=%5.1f%%"
                .format(l, cc, ee, ff, 100.0 * cc / (cc + ee + ff), 100.0 * cc / PERGUNTAS_DE_CAMPO.size))
        }
    }

    private class Ponto(val limiar: Double, val certos: Int, val errados: Int) {
        val precisao: Double get() = if (certos + errados == 0) 0.0 else certos.toDouble() / (certos + errados)
    }

    /** O limiar mais permissivo que ainda não responde nenhuma pergunta fora do corpus. */
    private fun melhorPontoDeOperacao(
        certas: List<Double>,
        erradas: List<Double>,
        fora: List<Double>,
        precisaoMinima: Double,
    ): Ponto {
        var melhor = Ponto(1.0, 0, 0)
        for (i in 0..200) {
            val l = i * 0.005
            if (fora.any { it >= l }) continue
            val p = Ponto(l, certas.count { it >= l }, erradas.count { it >= l })
            if (p.precisao >= precisaoMinima && p.certos > melhor.certos) melhor = p
        }
        return melhor
    }

    // ------------------------------------------------------------------- a porta

    @Test
    fun aCurvaDoLimiarEOPontoEscolhido() {
        val confiancasCertas = ArrayList<Double>()
        val confiancasErradas = ArrayList<Double>()
        for (p in PERGUNTAS_DE_CAMPO) {
            val topo = indice.buscar(p.pergunta, quantos = 1).firstOrNull()
            val c = topo?.similaridade ?: 0.0
            val acertou = topo != null && topo.trecho.citacao == p.citacao && topo.trecho.norma == p.norma
            (if (acertou) confiancasCertas else confiancasErradas) += c
        }
        val confiancasFora = FORA_DO_CORPUS.map {
            indice.buscar(it, quantos = 1).firstOrNull()?.similaridade ?: 0.0
        }

        println("\n  limiar | fala certo | fala errado | fala fora | precisão | cobertura")
        for (i in 0..20) {
            val l = i * 0.05
            val c = confiancasCertas.count { it >= l }
            val e = confiancasErradas.count { it >= l }
            val f = confiancasFora.count { it >= l }
            val fala = c + e + f
            if (fala == 0) continue
            println(
                "   %.2f  |     %2d     |     %2d      |    %2d/%d   |  %5.1f%%  |  %5.1f%%"
                    .format(l, c, e, f, FORA_DO_CORPUS.size, 100.0 * c / fala, 100.0 * c / PERGUNTAS_DE_CAMPO.size),
            )
        }
        println("  maior confiança fora do corpus: %.3f".format(confiancasFora.max()))

        // 1. O ponto escolhido é o que o KDoc de LIMIAR_MEDIDO afirma.
        val l = BaseDeConhecimentoLexical.LIMIAR_MEDIDO
        val certos = confiancasCertas.count { it >= l }
        val errados = confiancasErradas.count { it >= l }
        assertTrue("no limiar medido, fala e acerta menos que os 18 relatados", certos >= 18)
        assertTrue("no limiar medido, fala e erra mais que os 5 relatados", errados <= 5)

        // 2. Critério de aceite do ROADMAP, verbatim: pergunta fora do corpus não
        //    produz invenção. Igualdade em zero, não piso.
        assertEquals(
            "Uma pergunta fora do corpus foi respondida com artigo de lei. É o " +
                "critério de aceite escrito no ROADMAP e não é negociável por " +
                "cobertura.",
            0,
            confiancasFora.count { it >= l },
        )

        // 3. **O contra-teste do limiar: ele tem de ser um limiar.** Um valor que
        //    nunca recusa não é limiar, é decoração — e passaria em tudo acima.
        val recusas = confiancasCertas.count { it < l } + confiancasErradas.count { it < l }
        assertTrue(
            "O limiar não recusou nada. Porta que nunca fecha não é porta.",
            recusas > PERGUNTAS_DE_CAMPO.size / 2,
        )

        // 4. E a recusa tem de ser majoritariamente CORRETA — recusar o que teria
        //    errado é sucesso; recusar o que teria acertado é o custo.
        val recusaCorreta = confiancasErradas.count { it < l } + confiancasFora.count { it < l }
        val recusaCustosa = confiancasCertas.count { it < l }
        println("  recusas: $recusaCorreta corretas · $recusaCustosa custosas")
        assertTrue("a maioria das recusas deveria ser correta", recusaCorreta > recusaCustosa)
    }

    /**
     * **O contra-teste que o enunciado da porta pede: futebol devolve `null`.**
     *
     * Passa pela classe pública inteira — corpus embarcado, índice, porta —, não
     * por dentro do índice. É o caminho que o agente percorre.
     */
    @Test
    fun perguntaForaDoCorpusRecebeRecusa() = runBlocking {
        for (q in FORA_DO_CORPUS) {
            assertEquals(
                "'$q' não tem resposta neste corpus e recebeu uma. Trecho errado dito " +
                    "com autoridade para quem está em abordagem é pior que 'não sei'.",
                null,
                base.buscar(q),
            )
        }
    }

    /**
     * O outro lado do contra-teste: se ele recusasse **tudo**, o teste acima
     * também passaria. Alguma pergunta de domínio tem de ser respondida.
     */
    @Test
    fun perguntaDeDominioRecebeORespostaCerta() = runBlocking {
        val respondidas = PERGUNTAS_DE_CAMPO.count { base.buscar(it.pergunta) != null }
        val certas = PERGUNTAS_DE_CAMPO.count { p ->
            base.buscar(p.pergunta)?.let { it.citacao == p.citacao && it.norma == p.norma } == true
        }
        println("  responde $respondidas de ${PERGUNTAS_DE_CAMPO.size}, e acerta $certas delas")
        for (p in PERGUNTAS_DE_CAMPO) {
            val t = base.buscar(p.pergunta) ?: continue
            val marca = if (t.citacao == p.citacao && t.norma == p.norma) "OK  " else "ERRO"
            println("    $marca '${p.pergunta}' -> ${t.citacao} ${t.norma}")
        }
        assertTrue("recusou todas as perguntas de domínio", respondidas >= 23)
        assertTrue("respondeu, mas não acertou o que foi medido (18)", certas >= 18)
    }

    // ------------------------------------------------------------------ utilidades

    private class Placar(val emPrimeiro: Int, val emTres: Int, val n: Int)

    private fun acertos(conjunto: List<P>, comLexico: Boolean): Int = conjunto.count { p ->
        indice.buscar(p.pergunta, quantos = 1, comLexico = comLexico)
            .firstOrNull()?.trecho
            ?.let { it.citacao == p.citacao && it.norma == p.norma } == true
    }

    /** recall@1 de um índice qualquer — é o que permite comparar configurações. */
    private fun acertosCom(outro: IndiceLexical): Int = PERGUNTAS_DE_CAMPO.count { p ->
        outro.buscar(p.pergunta, quantos = 1).firstOrNull()?.trecho
            ?.let { it.citacao == p.citacao && it.norma == p.norma } == true
    }

    /** `(escore do 1º, escore do 2º, acertou?)` para uma pergunta anotada. */
    private fun topoDe(p: P): Triple<Double, Double, Boolean> {
        val escores = indice.escoresBrutos(p.pergunta)
        val acertou = indice.buscar(p.pergunta, quantos = 1).firstOrNull()?.trecho
            ?.let { it.citacao == p.citacao && it.norma == p.norma } == true
        return Triple(escores.getOrElse(0) { 0.0 }, escores.getOrElse(1) { 0.0 }, acertou)
    }

    /**
     * Probabilidade de um valor de [bons] superar um de [ruins] — a AUC de
     * Mann-Whitney. Empate vale meio, senão uma régua que devolve sempre o mesmo
     * número marcaria 1,0 e pareceria perfeita.
     */
    private fun auc(bons: List<Double>, ruins: List<Double>): Double {
        if (bons.isEmpty() || ruins.isEmpty()) return Double.NaN
        var soma = 0.0
        for (x in bons) for (y in ruins) soma += if (x > y) 1.0 else if (x == y) 0.5 else 0.0
        return soma / (bons.size * ruins.size)
    }

    private fun medir(conjunto: List<P>): Placar {
        var um = 0
        var tres = 0
        for (p in conjunto) {
            val topo = indice.buscar(p.pergunta, quantos = 3).map { it.trecho }
            if (topo.firstOrNull()?.let { it.citacao == p.citacao && it.norma == p.norma } == true) um++
            if (topo.any { it.citacao == p.citacao && it.norma == p.norma }) tres++
        }
        return Placar(um, tres, conjunto.size)
    }

    private fun pct(k: Int, n: Int) = "%2d/%d (%4.1f%%)".format(k, n, 100.0 * k / n)
}
