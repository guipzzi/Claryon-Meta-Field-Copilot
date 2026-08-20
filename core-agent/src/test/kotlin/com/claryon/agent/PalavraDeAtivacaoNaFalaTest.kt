package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O segundo estágio da ativação.**
 *
 * A arquitetura é a mesma da Alexa: detector acústico pequeno e rápido no
 * aparelho, e uma segunda conferência depois. Lá o áudio enviado **contém** a
 * palavra de ativação de propósito, porque é ela que a segunda etapa verifica e é
 * assim que falso despertar é descartado.
 *
 * Aqui a segunda etapa é a transcrição local, e a inversão importa: o prefixo era
 * o DEFEITO — recusava toda fala real — e virou a PROVA de que o agente falou com
 * o copiloto, e não perto dele.
 */
class PalavraDeAtivacaoNaFalaTest {

    private fun c(t: String) = PalavraDeAtivacaoNaFala.conferir(t)

    /** A transcrição medida no aparelho em 20/08, com fala humana. */
    @Test
    fun aTranscricaoREAL_eConfirmadaEDevolveOComando() {
        val r = c("Clareon, Guarney são 1 na escuta.")
        assertTrue("o whisper escreveu 'Clareon' e a conferência não reconheceu", r.confirmada)
        assertEquals("guarney sao 1 na escuta.", r.resto)
    }

    /**
     * A vírgula depois do gatilho é o caso COMUM, não a exceção — foi assim que o
     * whisper devolveu. Sem tirá-la, o resto começa por `,` e nenhum padrão casa.
     */
    @Test
    fun aPontuacaoDepoisDoGatilho_naoSobraNoComando() {
        for (t in listOf("Claryon, abrir", "Claryon. abrir", "Claryon: abrir", "Claryon abrir")) {
            assertEquals("sobrou pontuação em \"$t\"", "abrir", c(t).resto)
        }
    }

    @Test
    fun asSaudacoesSaemJunto() {
        for (t in listOf("Hey Claryon, abrir", "Hei Claryon abrir", "Ok Claryon abrir")) {
            assertTrue(c(t).confirmada)
            assertEquals("abrir", c(t).resto)
        }
    }

    @Test
    fun todasAsVariantesMEDIDAS_saoAceitas() {
        for (v in PalavraDeAtivacaoNaFala.VARIANTES) {
            assertTrue("a variante medida \"$v\" não foi aceita", c("$v abrir").confirmada)
        }
    }

    // ── o que NÃO confirma ────────────────────────────────────────────────────

    /**
     * **A regra de segurança.** "Claryon" no meio é o agente falando SOBRE o
     * copiloto para outra pessoa. Aceitar no meio faria conversa virar comando.
     */
    @Test
    fun oGatilhoNoMEIO_naoConfirma() {
        val r = c("pergunta pro Claryon onde está o Alfa Dois")
        assertFalse("gatilho no meio da frase confirmou — isso é conversa", r.confirmada)
    }

    @Test
    fun semGatilhoNenhum_naoConfirma_eDevolveOTextoInteiro() {
        val r = c("guarnição 3 na escuta")
        assertFalse(r.confirmada)
        assertEquals(
            "o texto foi alterado mesmo sem confirmação — quem chama precisa do original",
            "guarnição 3 na escuta",
            r.resto,
        )
    }

    /**
     * Palavra que só COMEÇA parecido não pode passar. "Clara" não é "Claryon", e um
     * `startsWith` sobre um prefixo curto demais abriria o portão para nomes
     * próprios comuns.
     */
    @Test
    fun palavraApenasPARECIDA_naoConfirma() {
        for (t in listOf("clara diga onde está", "claro que sim", "clarissa na escuta")) {
            assertFalse("\"$t\" passou pela conferência", c(t).confirmada)
        }
    }

    /** Só o gatilho, sem comando: legítimo (acordar e pensar), e não é comando. */
    @Test
    fun soOGatilho_confirmaComRestoVAZIO() {
        val r = c("Claryon")
        assertTrue(r.confirmada)
        assertEquals("", r.resto)
    }
}
