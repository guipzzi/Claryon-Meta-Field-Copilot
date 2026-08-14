package com.claryon.agent

import com.claryon.common.LaconicityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da regra que define o produto: **toda resposta falada deriva do
 * resultado da ação, nunca do comando recebido.**
 */
class UtteranceTest {

    /**
     * Todos os resultados possíveis. A exaustividade do `when` em `utteranceFor`
     * é garantida pelo compilador (acrescentar um [ActionOutcome] quebra a
     * compilação); esta lista garante que cada um também passe pelas regras de
     * saída abaixo.
     */
    private val todos: List<ActionOutcome> = buildList {
        add(ActionOutcome.ApoioTransmitido(null))
        add(ActionOutcome.ApoioTransmitido(0))
        add(ActionOutcome.ApoioTransmitido(1))
        add(ActionOutcome.ApoioTransmitido(4))
        add(ActionOutcome.ApoioTransmitido(12))
        add(ActionOutcome.ApoioTransmitido(37))
        add(ActionOutcome.ApoioEnfileirado)
        add(ActionOutcome.GravacaoIniciada("GTA-3_007_42"))
        add(ActionOutcome.GravacaoEncerrada(30))
        Restricao.entries.forEach { add(ActionOutcome.PlacaConsultada("ABC1D23", it)) }
        add(ActionOutcome.OcorrenciaRegistrada("oc-1"))
        ModoOperacao.entries.forEach { add(ActionOutcome.ModoTrocado(it)) }
        add(ActionOutcome.NaoEntendi)
        FalhaOperacional.entries.forEach { add(ActionOutcome.Falhou(it)) }
    }

    private fun textoDe(u: Utterance): String? = when (u) {
        is Utterance.Falar -> u.texto
        is Utterance.SinalizarEFalar -> u.texto
        is Utterance.Sinalizar -> null
    }

    // ── A regra estrutural ────────────────────────────────────────────────────

    /**
     * **O teste que sustenta a honestidade do produto.**
     *
     * `utteranceFor` só pode aceitar [ActionOutcome]. No dia em que alguém
     * acrescentar uma sobrecarga que aceite [Intent] — por conveniência, para
     * "responder mais rápido" — o app volta a poder falar antes de agir, e este
     * teste falha antes de o defeito chegar ao campo.
     */
    @Test
    fun naoExisteCaminhoDaIntencaoParaAFala() {
        val metodos = Class.forName("com.claryon.agent.UtteranceKt").declaredMethods
        val utterances = metodos.filter { it.name == "utteranceFor" }
        assertTrue("utteranceFor sumiu do arquivo Utterance.kt", utterances.isNotEmpty())

        val aceitamIntent = utterances.filter { m ->
            m.parameterTypes.any { Intent::class.java.isAssignableFrom(it) }
        }
        assertTrue(
            "utteranceFor NÃO pode aceitar Intent — a fala tem de derivar do resultado " +
                "da ação. Sobrecargas encontradas: ${aceitamIntent.map { it.parameterTypes.toList() }}",
            aceitamIntent.isEmpty(),
        )
    }

    // ── Regras de saída ───────────────────────────────────────────────────────

    @Test
    fun todaFalaRespeitaOProtocoloDeLaconicidade() {
        for (outcome in todos) {
            val texto = textoDe(utteranceFor(outcome)) ?: continue
            assertTrue(
                "Excede ${LaconicityPolicy.MAX_WORDS} palavras em $outcome: \"$texto\"",
                LaconicityPolicy.isWithinLimit(texto),
            )
            assertFalse(
                "Tem cortesia em $outcome: \"$texto\"",
                LaconicityPolicy.hasCourtesy(texto),
            )
        }
    }

    @Test
    fun falhaNuncaESilencio() {
        for (falha in FalhaOperacional.entries) {
            val u = utteranceFor(ActionOutcome.Falhou(falha))
            assertTrue(
                "Falha $falha saiu sem earcon — silêncio é indistinguível de app morto",
                u is Utterance.Sinalizar || u is Utterance.SinalizarEFalar,
            )
        }
        assertTrue(utteranceFor(ActionOutcome.NaoEntendi) is Utterance.SinalizarEFalar)
    }

    @Test
    fun resultadoSensivelSaiComoEarconNuncaFalado() {
        // O alto-falante é open-ear: falar "sem restrição" entrega o resultado da
        // consulta a quem está sendo abordado.
        for (r in Restricao.entries) {
            val u = utteranceFor(ActionOutcome.PlacaConsultada("ABC1D23", r))
            assertTrue("Restrição $r foi falada", u is Utterance.Sinalizar)
        }
    }

    @Test
    fun gravacaoIniciadaEUmTomSemFala() {
        val u = utteranceFor(ActionOutcome.GravacaoIniciada("x"))
        assertTrue(u is Utterance.Sinalizar)
        assertEquals(com.claryon.common.Earcon.GRAVANDO, (u as Utterance.Sinalizar).earcon)
    }

    // ── Honestidade, caso a caso ──────────────────────────────────────────────

    @Test
    fun enfileiradoNuncaAfirmaEntrega() {
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioEnfileirado))!!.lowercase()
        for (palavra in listOf("recebeu", "receberam", "avisada", "entregue", "solicitado")) {
            assertFalse(
                "\"$texto\" sugere entrega, mas a mensagem só entrou na fila",
                texto.contains(palavra),
            )
        }
        assertTrue("O agente precisa ouvir que está sem rede", texto.contains("sem rede"))
    }

    @Test
    fun transmitidoSemContagemNaoInventaNumero() {
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(null)))!!
        assertEquals("Apoio enviado.", texto)
    }

    @Test
    fun zeroDestinatariosEDito_naoMascaradoComoSucesso() {
        // "Apoio solicitado" com zero unidades por perto faria o agente contar
        // com quem não existe.
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(0)))!!.lowercase()
        assertTrue(texto.contains("nenhuma"))
    }

    @Test
    fun contagemConcordaEmNumero() {
        assertEquals("Uma unidade recebeu.", textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(1))))
        assertEquals("Quatro unidades receberam.", textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(4))))
    }

    @Test
    fun apoioTemPrioridadeDeEmergencia() {
        // Apoio não pode ficar atrás de um informativo na fila de som.
        assertEquals(
            com.claryon.common.Priority.EMERGENCIA,
            utteranceFor(ActionOutcome.ApoioTransmitido(2)).priority,
        )
        assertEquals(
            com.claryon.common.Priority.EMERGENCIA,
            utteranceFor(ActionOutcome.ApoioEnfileirado).priority,
        )
    }
}
