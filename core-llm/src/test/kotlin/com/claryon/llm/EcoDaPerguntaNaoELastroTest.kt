package com.claryon.llm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Ecoar a pergunta não é responder — e até 22/08 valia lastro 1,00.**
 *
 * `RedatorLlamaCpp` montava a fonte do guarda como `trecho + procedencia + pergunta`.
 * O raciocínio soava certo: uma redação que responde à pergunta reusa as palavras
 * dela, e penalizá-la por isso reprovaria redação honesta.
 *
 * **A medição derrubou o raciocínio.** Sobre as 20 perguntas do banco de abordagem que
 * passam o limiar, medido por diferença — o mesmo texto julgado com e sem a pergunta na
 * fonte. Remedido no aparelho em 22/08, com o guarda já consertado nos outros dois eixos:
 *
 * ```
 * prompt de 22/08, prazo de produção .....  2 de  9 aprovações caem
 * prompt de 22/08, prazo de medição ......  2 de 12
 * prompt de hoje (SEM_ROTULO) ............  5 de 13
 * ```
 *
 * A terceira linha é a que importa para quem for mexer: a formulação que entrou em
 * produção no mesmo dia devolve o foco à pergunta e **ecoa mais**. Sem esta régua, as
 * cinco chegariam ao agente com lastro 1,00 e informação zero.
 *
 * E o modelo deste projeto **ecoa a pergunta** — está registrado no KDoc de
 * `RedatorLlamaCpp`, e apareceu no aparelho como *"O número do motor foi remarcado."*,
 * que é a pergunta com o ponto final trocado.
 *
 * Este é o **terceiro** buraco do guarda. Os dois primeiros eram da régua de cifras e
 * estão em `TresFurosDaReguaTest`; este era da **composição da fonte**, e sobreviveu ao
 * conserto daqueles porque não é a régua que está errada — é o material que ela mede.
 *
 * O medo de reprovar redação honesta não se concretiza, e o segundo teste prova.
 */
class EcoDaPerguntaNaoELastroTest {

    private val guarda = GuardaDaRedacao()

    /** O trecho e a citação — a fonte como ela é depois de 22/08. */
    private val fonte = """
        Art. 306. Conduzir veículo automotor com capacidade psicomotora alterada em
        razão da influência de álcool ou de outra substância psicoativa que determine
        dependência.
        Art. 306, Lei 9.503
    """.trimIndent()

    private val pergunta = "posso prender o motorista que remarcou o número do motor"

    @Test
    fun oEcoDaPergunta_naoTemLastroNaNorma() {
        // A "redação" é a pergunta devolvida como afirmação. Zero conteúdo do artigo.
        val eco = "O motorista remarcou o número do motor."

        assertNull(
            "O eco da pergunta foi aprovado. Se a pergunta voltou para a fonte do " +
                "guarda, o furo de 22/08 voltou junto: um modelo que só repete o que " +
                "o agente disse recebe lastro 1,00 sem tocar na norma.",
            guarda.aprovar(eco, fonte),
        )

        // E a prova de que o defeito é da FONTE, não da régua: com a pergunta de volta
        // na fonte, o mesmo eco passa. É a medição de 22/08 reproduzida em uma linha.
        assertNotNull(
            "Com a pergunta na fonte, o eco DEVERIA passar — se não passou, este " +
                "teste deixou de reproduzir o defeito que ele documenta, e a " +
                "explicação acima virou arqueologia sem prova.",
            guarda.aprovar(eco, "$fonte\n$pergunta"),
        )
    }

    /**
     * **O outro lado: redação honesta continua passando.**
     *
     * Era este o medo que justificava pôr a pergunta na fonte. Ele não se concretiza,
     * e a razão é simples: palavra que a pergunta compartilha com o artigo **já está
     * no trecho**. Some apenas a que existe só na pergunta — a que não tem lastro.
     */
    @Test
    fun redacaoAncoradaNoArtigo_continuaPassando() {
        val honesta = "Conduzir veículo com capacidade psicomotora alterada por álcool."
        assertNotNull(
            "Redação ancorada no artigo foi reprovada ao tirar a pergunta da fonte. " +
                "A régua ficou restritiva demais e o custo é o copiloto emudecer — " +
                "que é o pior desfecho, porque o produto volta a só citar.",
            guarda.aprovar(honesta, fonte),
        )
    }

    /**
     * **Citação sozinha NÃO basta, e está certo assim.**
     *
     * Descoberto ao escrever este arquivo: *"Responde pelo artigo 306 da Lei 9.503."*
     * é **reprovado**. As palavras de conteúdo são `responde` e `artigo`, e nenhuma
     * está na fonte — que escreve `Art. 306`, com `Art.` curto demais para contar como
     * palavra de conteúdo.
     *
     * O primeiro impulso foi chamar isso de defeito. Não é. O guarda existe para exigir
     * **âncora no artigo**, não no número dele: uma frase que só cita e não diz nada da
     * norma é exatamente o que não se quer na boca do copiloto. Quem cita **e** diz o
     * que a norma manda passa — é o segundo caso abaixo.
     *
     * O que fica registrado como custo aceito: `artigo` por extenso não casa `Art.`
     * abreviado. Quem for mexer na régua de raiz vai reencontrar isto.
     */
    @Test
    fun citacaoSozinha_naoBasta_masCitarEDizerBasta() {
        assertNull(
            "Uma frase que só cita o número e não diz nada da norma foi aprovada. " +
                "O guarda exige âncora no ARTIGO, não no número dele.",
            guarda.aprovar("Responde pelo artigo 306 da Lei 9.503.", fonte),
        )

        assertNotNull(
            "Redação que cita E diz o que a norma manda foi reprovada. A régua ficou " +
                "restritiva demais, e o custo é o copiloto voltar a só citar.",
            guarda.aprovar(
                "Conduzir com capacidade psicomotora alterada, artigo 306.",
                fonte,
            ),
        )
    }

    /**
     * A composição da fonte é decisão de `RedatorLlamaCpp`, não do guarda — então o
     * teste confere o arquivo. Sem isto, alguém devolve a pergunta lá e os testes
     * acima continuam verdes, porque eles montam a fonte por conta própria.
     */
    @Test
    fun oRedator_naoPoeAPerguntaNaFonteDoGuarda() {
        val fonteDoRedator = java.io.File(
            "src/main/kotlin/com/claryon/llm/RedatorLlamaCpp.kt",
        ).takeIf { it.exists() } ?: java.io.File(
            "core-llm/src/main/kotlin/com/claryon/llm/RedatorLlamaCpp.kt",
        )
        assertTrue("RedatorLlamaCpp.kt não encontrado", fonteDoRedator.exists())

        val linha = fonteDoRedator.readLines()
            .firstOrNull { it.contains("val fonte =") }
            ?: error("a linha `val fonte =` sumiu de RedatorLlamaCpp")

        assertTrue(
            "A pergunta voltou para a fonte do guarda:\n    $linha\n" +
                "Medido em 22/08: isso devolve lastro 1,00 a quem só ecoa a pergunta. " +
                "Com o prompt em produção hoje, 5 de 13 aprovações passariam a não ter " +
                "lastro na norma.",
            "pedido.pergunta" !in linha,
        )
    }
}
