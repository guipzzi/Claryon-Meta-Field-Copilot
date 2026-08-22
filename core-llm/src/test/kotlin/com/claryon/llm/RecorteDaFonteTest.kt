package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O recorte prova que encurta e que não inventa; que encurta BEM é medido no
 * aparelho.**
 *
 * A pergunta que decide a Pista 1 — *"o modelo continua respondendo certo sobre
 * um terço do artigo?"* — não tem resposta em JVM, e está em
 * `DuasPistasDaEtapaBTest`. Aqui ficam as propriedades sem as quais aquele número
 * descreveria outra coisa.
 */
class RecorteDaFonteTest {

    private val art244 =
        "Art. 244. Conduzir motocicleta, motoneta ou ciclomotor: " +
            "I - sem usar capacete de segurança ou vestuário de acordo com as normas; " +
            "II - transportando passageiro sem o capacete de segurança; " +
            "III - fazendo malabarismo ou equilibrando-se apenas em uma roda; " +
            "Infração - gravíssima; " +
            "Penalidade - multa e suspensão do direito de dirigir; " +
            "Parágrafo único. É proibido transportar criança menor de sete anos " +
            "ou que não tenha condições de cuidar de sua própria segurança."

    @Test
    fun oQueJaCabe_naoERecortado() {
        val curto = "Art. 25. Entende-se em legítima defesa quem repele injusta agressão."
        assertEquals(curto, RecorteDaFonte.para(curto, "quantos tiros posso dar", 70))
    }

    @Test
    fun oRecorteRespeitaOOrcamento() {
        val fora = RecorteDaFonte.para(art244, "moto transportando criança de oito anos", 30)
        val palavras = fora.split(Regex("""\s+""")).count { it.isNotEmpty() }
        assertTrue("Recorte de $palavras palavras estourou o orçamento de 30", palavras <= 30)
        assertTrue("Recorte vazio: o prompt ficaria sem fonte nenhuma", palavras > 0)
    }

    /**
     * **O recorte é subconjunto da fonte, palavra por palavra e na ordem.**
     *
     * É a garantia que impede a Pista 1 de introduzir, pela porta dos fundos, o
     * defeito que a Pista 2 existe para fechar: um "resumo" do artigo feito pelo
     * recorte seria texto novo, e texto novo antes do modelo é alucinação com
     * procedência falsa.
     */
    @Test
    fun oRecorteSoContemPalavrasDaFonte_naOrdem() {
        val fora = RecorteDaFonte.para(art244, "criança na moto", 40)
        val origem = art244.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val recorte = fora.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var i = 0
        for (p in recorte) {
            while (i < origem.size && origem[i] != p) i++
            assertTrue(
                "A palavra \"$p\" do recorte não aparece na fonte na ordem original — " +
                    "o recorte deixou de ser recorte.",
                i < origem.size,
            )
            i++
        }
    }

    /**
     * **O recorte tem de escolher pela pergunta, e não sempre o começo do artigo.**
     *
     * Contra-teste: duas perguntas diferentes sobre o MESMO artigo têm de produzir
     * recortes diferentes. Sem isto, `para()` poderia ser `take(70)` e o teste de
     * orçamento acima passaria igual — que é exatamente a família de teste que o
     * `CLAUDE.md` §6 chama de nome sem corpo.
     */
    @Test
    fun perguntasDiferentes_produzemRecortesDiferentes() {
        val sobreCrianca = RecorteDaFonte.para(art244, "criança pequena na garupa", 30)
        val sobreCapacete = RecorteDaFonte.para(art244, "passageiro sem capacete de segurança", 30)
        assertTrue(
            "O recorte ignorou a pergunta: os dois deram \"$sobreCrianca\". " +
                "Ou a nota não é lida, ou ela empata em tudo.",
            sobreCrianca != sobreCapacete,
        )
        assertTrue(
            "O recorte da pergunta sobre criança não trouxe o parágrafo da criança",
            "criança" in sobreCrianca,
        )
        assertTrue(
            "O recorte da pergunta sobre capacete não trouxe o inciso do capacete",
            "capacete" in sobreCapacete,
        )
    }

    /**
     * **O vizinho da direita entra junto quando cabe.**
     *
     * Em texto de lei a condicionante vem depois do preceito. Levar o campeão
     * sozinho é a forma mais barata de inverter o sentido sem inventar palavra —
     * e o modelo, vendo só metade, responderia certo sobre um pedaço errado.
     */
    @Test
    fun oVizinhoDaDireitaEntraJunto() {
        val fonte = "Art. 1. O agente responde pelo crime. " +
            "Parágrafo único. Não responde quem agiu em legítima defesa. " +
            "Art. 2. Outra coisa completamente diferente aqui."
        val fora = RecorteDaFonte.para(fonte, "o agente responde pelo crime", 22)
        assertTrue("O preceito não entrou: \"$fora\"", "responde pelo crime" in fora)
        assertTrue(
            "A condicionante que vem logo depois ficou de fora, e o recorte diz o " +
                "contrário da lei por omissão: \"$fora\"",
            "legítima defesa" in fora,
        )
    }
}
