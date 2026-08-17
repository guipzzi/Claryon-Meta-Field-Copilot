package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Testes da régua, porque régua errada é pior que régua nenhuma.**
 *
 * Uma régua defeituosa não avisa: ela devolve um número, o número parece razoável,
 * e toda decisão sobre modelo de STT passa a se apoiar nele. Por isso cada
 * propriedade de [Wer] tem caso próprio, e os números aqui foram **calculados**,
 * não estimados.
 *
 * Vive em `androidTest` junto com a bancada que a usa. É lógica pura e roda em
 * milissegundos no aparelho — o custo de não a separar em outro *source set* é
 * menor que o de duplicar a régua para poder testá-la na JVM.
 */
@RunWith(AndroidJUnit4::class)
class WerTest {

    // ── O caso que originou tudo ──────────────────────────────────────────────

    /**
     * **A transcrição real que eu chamei de "funcionando".**
     *
     * Medida no emulador com `ggml-tiny` sobre fala pt-BR sintetizada pelo Piper.
     * Este teste existe para que o número nunca mais possa ser descrito de forma
     * generosa: são cinco substituições em oito palavras.
     */
    @Test
    fun oCasoReal_daGuarnicaoQueVirouAgoraNissoSao() {
        val falado = "Central, a guarnição está a caminho da ocorrência."
        val transcrito = "central, agora nisso são está caminhinho da ocorrência."

        val r = Wer.calcular(falado, transcrito)

        assertEquals("palavras de referência", 8, r.palavrasDeReferencia)
        assertEquals("substituições", 5, r.substituicoes)
        assertEquals("deleções", 0, r.delecoes)
        assertEquals("inserções", 0, r.insercoes)
        assertEquals("WER", 62.5, r.percentual, 0.01)
        assertEquals("acurácia", 37.5, r.acuraciaPercentual, 0.01)

        // A meta do ROADMAP é ≥92% de acurácia. Este é o tamanho do buraco.
        assertTrue(
            "a meta de STT do ROADMAP (≥92%) tem de reprovar esta transcrição",
            r.acuraciaPercentual < 92.0,
        )

        // E o erro que mais importa tem de aparecer nomeado no diagnóstico: sem
        // isso, "WER 62,5%" não diz a quem lê QUE palavra o produto perdeu.
        assertTrue(
            "o diagnóstico deve nomear a perda de 'guarnicao': ${r.errosLegiveis()}",
            r.errosLegiveis().contains("guarnicao"),
        )
    }

    /**
     * **O contra-teste: o limiar antigo aprovava o que o novo reprova.**
     *
     * Se este teste falhasse, significaria que a régua nova não é mais rigorosa
     * que a antiga — e a troca teria sido cosmética. É o padrão de contra-teste do
     * `CLAUDE.md` §6.3: rodar as duas configurações e exigir que **difiram**.
     */
    @Test
    fun oLimiarAntigo_aprovavaOQueONovoReprova() {
        val transcrito = "central, agora nisso são está caminhinho da ocorrência."

        // Exatamente o critério que estava no teste antes: 2 de 4 palavras.
        val criterioAntigo =
            listOf("central", "guarni", "caminho", "ocorr").count { transcrito.contains(it) } >= 2
        val criterioNovo =
            Wer.calcular("Central, a guarnição está a caminho da ocorrência.", transcrito)
                .acuraciaPercentual >= 92.0

        assertTrue("o critério antigo aprovava — é o que o tornava uma mentira", criterioAntigo)
        assertTrue("o critério novo tem de reprovar", !criterioNovo)
    }

    // ── Propriedades da fórmula ───────────────────────────────────────────────

    @Test
    fun transcricaoPerfeita_daZero() {
        val r = Wer.calcular("guarnição três na escuta", "Guarnição três na escuta.")
        assertEquals(0, r.erros)
        assertEquals(0.0, r.percentual, 0.001)
        assertEquals(100.0, r.acuraciaPercentual, 0.001)
    }

    @Test
    fun acentoEPontuacaoNaoSaoErro_masDigitoEPalavraSim() {
        // Ortografia não muda o sentido: o produto age sobre a palavra.
        assertEquals(0, Wer.calcular("guarnição", "GUARNICAO,").erros)

        // Mas "três" e "3" são reconhecimentos DIFERENTES, e no despacho a
        // diferença entre guarnição três e guarnição treze é outra viatura.
        // Colapsar isso seria uma régua generosa, que é o defeito que se está
        // consertando.
        assertEquals(1, Wer.calcular("guarnição três", "guarnição 3").erros)
        assertEquals(1, Wer.calcular("guarnição três", "guarnição treze").erros)
    }

    @Test
    fun alucinacao_passaDeCemPorCento() {
        // Referência curta, hipótese longa: o Whisper em áudio curto às vezes
        // entra em laço e repete. O WER TEM de estourar 100% nesse caso — se a
        // fórmula normalizasse por max(ref, hip), alucinação apareceria como 100%
        // e ficaria indistinguível de "errou tudo", que é bem menos grave.
        val r = Wer.calcular("afirmativo", "afirmativo afirmativo afirmativo afirmativo")
        assertEquals(1, r.palavrasDeReferencia)
        assertEquals(3, r.insercoes)
        assertEquals(300.0, r.percentual, 0.01)
        assertEquals("acurácia satura em zero, não fica negativa", 0.0, r.acuraciaPercentual, 0.01)
    }

    @Test
    fun silencioTotal_ehCemPorCentoDeErro() {
        val r = Wer.calcular("guarnição três na escuta", "")
        assertEquals(4, r.delecoes)
        assertEquals(100.0, r.percentual, 0.01)
    }

    @Test
    fun referenciaVazia_naoDividePorZero() {
        assertEquals(0.0, Wer.calcular("", "").percentual, 0.001)
        assertEquals(1.0, Wer.calcular("", "inventou").taxa, 0.001)
    }

    @Test
    fun asTresOperacoesSaoDistinguidas() {
        // ref: a b c d      hip: a x c d e
        //      = S = =           inserção no fim
        val r = Wer.calcular("a b c d", "a x c d e")
        assertEquals(1, r.substituicoes)
        assertEquals(0, r.delecoes)
        assertEquals(1, r.insercoes)
        assertEquals(4, r.palavrasDeReferencia)
        assertEquals(50.0, r.percentual, 0.01)
    }

    // ── Agregação de corpus ───────────────────────────────────────────────────

    /**
     * **O erro que fabrica número bonito.**
     *
     * Média das taxas por frase dá o mesmo peso a uma frase de 1 palavra e a uma
     * de 20. Com um corpus desbalanceado, isso é como se escolhe o resultado.
     * O WER de um corpus é `ΣE / ΣN`, e este teste fixa a diferença.
     */
    @Test
    fun corpus_agregaErrosENaoMediaDeTaxas() {
        val pares = listOf(
            // 1 palavra, toda errada → taxa 100%
            "sim" to "não",
            // 19 palavras, 1 errada → taxa ~5,3%
            ("um dois tres quatro cinco seis sete oito nove dez " +
                "onze doze treze catorze quinze dezesseis dezessete dezoito dezenove") to
                ("um dois tres quatro cinco seis sete oito nove dez " +
                    "onze doze treze catorze quinze dezesseis dezessete dezoito ERRADO"),
        )

        val agregado = Wer.calcularCorpus(pares)
        assertEquals("erros somados", 2, agregado.erros)
        assertEquals("palavras somadas", 20, agregado.palavrasDeReferencia)
        assertEquals("WER do corpus = 2/20", 10.0, agregado.percentual, 0.01)

        // A média de taxas daria (100 + 5,26) / 2 ≈ 52,6% — cinco vezes o valor
        // correto. É por isso que a agregação não é média.
        val mediaDeTaxas = pares.map { Wer.calcular(it.first, it.second).percentual }.average()
        assertTrue(
            "a média de taxas (%.1f%%) tem de divergir do WER do corpus (10%%)"
                .format(mediaDeTaxas),
            mediaDeTaxas > 40.0,
        )
    }

    @Test
    fun cer_contaCaractere() {
        // "caminho" (7) → "caminhinho" (10): três caracteres inseridos, "i", "n",
        // "h". A primeira versão deste teste dizia dois, de cabeça; a distância de
        // edição real é 3. Fica registrado porque errar a conta no teste da régua
        // seria a forma mais silenciosa de a régua mentir.
        val r = Wer.calcularCaracteres("caminho", "caminhinho")
        assertEquals(7, r.palavrasDeReferencia)
        assertEquals(3, r.insercoes)
        assertEquals(0, r.substituicoes)
        assertEquals(42.86, r.percentual, 0.01)

        // O CER é mais generoso que o WER para erro de flexão — e é exatamente por
        // isso que o aceite do produto é por PALAVRA. Aqui são ~43% de CER contra
        // 100% de WER naquela palavra; para quem lê o registro operacional, a
        // palavra está errada, ponto.
        val porPalavra = Wer.calcular("caminho", "caminhinho")
        assertEquals(100.0, porPalavra.percentual, 0.01)
        assertTrue("CER tem de ser mais generoso que WER aqui", r.percentual < porPalavra.percentual)
    }
}
