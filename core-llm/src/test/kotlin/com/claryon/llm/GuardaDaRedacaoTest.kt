package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **O guarda de lastro, com os dois lados exigidos em cada regra.**
 *
 * Um guarda que reprova tudo satisfaz "alucinação não passa" e destrói a Etapa B
 * inteira sem que nenhum teste fique vermelho — a Etapa A assumiria em silêncio
 * e o LLM viraria 16 MB de `.so` que nunca fala. Por isso cada reprovação aqui
 * vem colada à aprovação do caso legítimo mais parecido possível.
 */
class GuardaDaRedacaoTest {

    private val guarda = GuardaDaRedacao()

    /** Um trecho real do corpus deste projeto, com a citação junto. */
    private val trecho =
        "Art. 165. Dirigir sob a influência de álcool ou de qualquer outra substância " +
            "psicoativa que determine dependência: Infração — gravíssima; Penalidade — " +
            "multa (dez vezes) e suspensão do direito de dirigir por 12 (doze) meses."
    private val procedencia = "Art. 165 — Lei 9.503/1997"
    private val pergunta = "o que acontece se o motorista estiver bêbado"
    private val fonte = "$trecho\n$procedencia\n$pergunta"

    // ------------------------------------------------------------------ régua 1: cifras

    /**
     * **A cifra sem lastro reprova o texto inteiro, e é a regra mais importante
     * daqui.** O agente ouve `"suspensão por 24 meses"` com a mesma naturalidade
     * de `"12 meses"` e não tem display para conferir. Reescrever o número é o
     * único erro deste caminho que produz uma decisão errada no mundo.
     */
    @Test
    fun cifraQueNaoEstaNaFonteReprova() {
        assertNull(
            guarda.aprovar(
                "Dirigir sob influência de álcool é infração gravíssima. " +
                    "A suspensão do direito de dirigir é por 24 meses.",
                fonte,
            ),
        )
    }

    /**
     * O par: o MESMO texto com a cifra certa passa. Sem isto, o teste acima
     * também passaria num guarda que reprovasse qualquer texto com dígito — e a
     * Etapa B nunca poderia dizer um prazo, que é metade do valor dela.
     */
    @Test
    fun aMesmaFraseComACifraDaFonteAprova() {
        val comCifraCerta =
            "Dirigir sob influência de álcool é infração gravíssima. " +
                "A suspensão do direito de dirigir é por 12 meses."
        assertNotNull(
            "O guarda reprovou uma redação fiel. Guarda que reprova tudo desliga " +
                "a Etapa B sem deixar teste vermelho.",
            guarda.aprovar(comCifraCerta, fonte),
        )
    }

    // ------------------------------------------------------- régua 2: lastro lexical

    /**
     * **Norma inventada, sem uma cifra sequer.** É a alucinação mais plausível
     * de um modelo pequeno: ele não erra o número, ele fala de outra coisa com
     * confiança. A régua de cifras não pega isto — a de lastro, sim.
     */
    @Test
    fun textoSobreOutroAssuntoReprovaMesmoSemNumero() {
        assertNull(
            guarda.aprovar(
                "Solicite imediatamente reforço policial, isole completamente o " +
                    "perímetro urbano e aguarde perícia criminal especializada.",
                fonte,
            ),
        )
    }

    /**
     * **Flexão não é alucinação.** A fonte diz `"dirigir"` e `"substância"`; a
     * redação diz `"dirigindo"` e `"substâncias"`. Se a raiz de 6 caracteres não
     * absorvesse isso, toda redação legítima em português seria reprovada — e o
     * guarda estaria medindo ortografia, não lastro.
     */
    @Test
    fun flexaoEPluralNaoContamComoInvencao() {
        assertNotNull(
            guarda.aprovar(
                "Dirigindo sob influência de álcool ou substâncias psicoativas, " +
                    "a infração é gravíssima e a penalidade inclui multa.",
                fonte,
            ),
        )
    }

    /** Acento e caixa não podem decidir nada: o STT deste projeto acentua mal. */
    @Test
    fun acentoECaixaNaoMudamADecisao() {
        val comAcento = "Dirigir sob influência de álcool é infração gravíssima."
        val semAcento = "DIRIGIR SOB INFLUENCIA DE ALCOOL E INFRACAO GRAVISSIMA."
        assertEquals(
            "Mesma frase, mesma decisão — senão o guarda estaria medindo ortografia.",
            guarda.aprovar(comAcento, fonte) != null,
            guarda.aprovar(semAcento, fonte) != null,
        )
    }

    // ------------------------------------------------------------------ casos de borda

    @Test
    fun textoVazioOuSoDeConectivosReprova() {
        assertNull(guarda.aprovar("", fonte))
        assertNull(guarda.aprovar("   ", fonte))
        // Sem nenhuma palavra de conteúdo não há o que ter lastro: aprovar seria
        // deixar passar "sim.", "ok." e "não sei dizer" como se fossem resposta.
        assertNull(guarda.aprovar("e o de a se ou", fonte))
    }

    /**
     * **A saída anterior não pode virar fonte.** Este teste fixa o contrato que
     * `RedatorLlamaCpp` honra: o guarda recebe pedido + trecho, nunca a resposta
     * passada. Sem isso, uma alucinação aprovada uma vez viraria lastro para a
     * seguinte e o filtro degradaria sozinho ao longo do turno.
     */
    @Test
    fun oQueTemLastroEmUmaFonteNaoTemNaOutra() {
        val outraFonte = "Art. 28 — Lei 11.343/2006. Adquirir drogas para consumo pessoal."
        val texto = "Dirigir sob influência de álcool é infração gravíssima."
        assertNotNull(guarda.aprovar(texto, fonte))
        assertNull(guarda.aprovar(texto, outraFonte))
    }

    /**
     * **O buraco conhecido, fixado como teste para não virar surpresa.**
     *
     * A frase abaixo saiu do Llama 3.2 1B rodando no emulador em 21/08, sobre
     * este mesmo artigo. Ela **é aprovada**, e está certo que seja: não tem
     * cifra, e cada palavra de conteúdo tem raiz na fonte. O lastro é lexical, e
     * a inversão de sentido usa o léxico da própria fonte.
     *
     * O teste assere o comportamento REAL, não o desejado, de propósito. Um
     * `@Ignore` com "consertar depois" não falha quando alguém "conserta" o
     * guarda por acidente e quebra o resto; esta asserção falha no dia em que a
     * cegueira for fechada — e aí quem fechar atualiza o teste sabendo o que
     * mudou. Enquanto ela passar, a Etapa B não está segura contra negação.
     */
    @Test
    fun oGuardaNAOVeNegacao_eIssoEUmaLimitacaoConhecida() {
        // A fonte é reconstruída com a pergunta EXATA do aparelho: o lastro
        // conta a pergunta como material com procedência, e ela muda o
        // resultado. Reproduzir com outra pergunta reproduziria outra coisa.
        val perguntaDoAparelho = "o que acontece com quem dirige embriagado"
        val fonteDoAparelho = "$trecho\n$procedencia\n$perguntaDoAparelho"
        val inversaoQuePassou = "Não há, não há nada que aconteça com quem dirige embriagado."

        assertNotNull(
            "Se este texto passou a ser REPROVADO, a cegueira à negação foi " +
                "fechada — ótimo, e o KDoc de GuardaDaRedacao precisa ser " +
                "reescrito junto, porque ele afirma o contrário.",
            guarda.aprovar(inversaoQuePassou, fonteDoAparelho),
        )
    }

    /** O limiar é o que separa, e ele é comparado por `>=`. */
    @Test
    fun oLimiarSepara_eUmGuardaFrouxoAprovaOQueOEstritoReprova() {
        val meioTermo =
            "Dirigir alcoolizado configura infração gravíssima, e a penalidade " +
                "aplicada envolve procedimento administrativo complementar imediato."

        val estrito = GuardaDaRedacao(lastroMinimo = 0.95)
        val frouxo = GuardaDaRedacao(lastroMinimo = 0.10)

        assertNull(estrito.aprovar(meioTermo, fonte))
        assertNotNull(frouxo.aprovar(meioTermo, fonte))
    }
}
