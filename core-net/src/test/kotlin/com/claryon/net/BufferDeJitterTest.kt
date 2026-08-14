package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferDeJitterTest {

    private fun quadro(seq: Int, ultimo: Boolean = false) =
        QuadroAudio("t1", seq, capturadoEmMs = seq * 20L, payload = byteArrayOf(seq.toByte()), ultimo = ultimo)

    /** Drena até `n` saídas, parando em Fim. */
    private fun drenar(b: BufferDeJitter, n: Int): List<SaidaDoJitter> = buildList {
        repeat(n) {
            val s = b.proximo()
            add(s)
            if (s is SaidaDoJitter.Fim) return@buildList
        }
    }

    @Test
    fun esperaEncherAntesDeComecar() {
        // Começar no primeiro quadro faria a primeira variação de rede virar buraco.
        val b = BufferDeJitter(inicialMs = 100) // 5 quadros
        repeat(4) { b.receber(quadro(it)) }
        assertEquals(SaidaDoJitter.Aguardando, b.proximo())

        b.receber(quadro(4))
        assertTrue(b.proximo() is SaidaDoJitter.Reproduzir)
    }

    @Test
    fun entregaEmOrdem() {
        val b = BufferDeJitter(inicialMs = 60) // 3 quadros
        repeat(6) { b.receber(quadro(it)) }

        val seqs = drenar(b, 6).filterIsInstance<SaidaDoJitter.Reproduzir>().map { it.quadro.sequencia }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), seqs)
    }

    @Test
    fun chegadaForaDeOrdem_ehReordenada() {
        // A rede entrega 2 antes de 1 — o ouvido não pode perceber.
        val b = BufferDeJitter(inicialMs = 60)
        b.receber(quadro(0)); b.receber(quadro(2)); b.receber(quadro(1))

        val seqs = drenar(b, 3).filterIsInstance<SaidaDoJitter.Reproduzir>().map { it.quadro.sequencia }
        assertEquals(listOf(0, 1, 2), seqs)
    }

    @Test
    fun quadroPerdido_viraInterpolacao_naoSilencio() {
        // Silêncio soa como corte; a interpolação do Opus soa como voz.
        val b = BufferDeJitter(inicialMs = 60)
        b.receber(quadro(0)); b.receber(quadro(2)); b.receber(quadro(3))

        val saidas = drenar(b, 4)
        assertTrue("o quadro 1 deveria virar PLC", saidas.any { it is SaidaDoJitter.Interpolar && it.sequencia == 1 })
        val seqs = saidas.filterIsInstance<SaidaDoJitter.Reproduzir>().map { it.quadro.sequencia }
        assertEquals(listOf(0, 2, 3), seqs)
    }

    @Test
    fun quadroAtrasadoDemais_ehDescartado_naoTocaForaDeOrdem() {
        val b = BufferDeJitter(inicialMs = 60)
        b.receber(quadro(0)); b.receber(quadro(2)); b.receber(quadro(3))
        drenar(b, 3) // 0, PLC(1), 2

        // O quadro 1 finalmente chega — tarde. Tocá-lo agora inverteria a fala.
        b.receber(quadro(1))
        val seguintes = drenar(b, 2).filterIsInstance<SaidaDoJitter.Reproduzir>().map { it.quadro.sequencia }
        assertFalse("não pode reproduzir o 1 depois do 2", seguintes.contains(1))
    }

    @Test
    fun ultimoQuadro_encerraOFluxo() {
        val b = BufferDeJitter(inicialMs = 60)
        b.receber(quadro(0)); b.receber(quadro(1)); b.receber(quadro(2, ultimo = true))

        val saidas = drenar(b, 6)
        assertEquals(SaidaDoJitter.Fim, saidas.last())
    }

    @Test
    fun bufferCresceSobPerda_ateOTeto() {
        val b = BufferDeJitter(inicialMs = 60, maxMs = 140)
        val inicial = b.atrasoAtualMs

        // Sequência cheia de buracos: chegam só os pares.
        for (i in 0 until 40 step 2) b.receber(quadro(i))
        drenar(b, 40)

        assertTrue("o atraso deveria ter crescido (era $inicial)", b.atrasoAtualMs > inicial)
        assertTrue("não pode passar do teto", b.atrasoAtualMs <= 140)
    }

    @Test
    fun bufferEncolheEmRedeEstavel_masNaoAbaixoDoPiso() {
        val b = BufferDeJitter(inicialMs = 200, minMs = 60)
        for (i in 0 until 120) b.receber(quadro(i))
        drenar(b, 120)

        assertTrue("deveria ter encolhido de 200", b.atrasoAtualMs < 200)
        assertTrue("não pode furar o piso", b.atrasoAtualMs >= 60)
    }

    @Test
    fun perdaAlta_marcaCanalDegradado() {
        // O agente precisa saber que o canal está ruim ANTES de confiar nele.
        val b = BufferDeJitter(inicialMs = 60)
        for (i in 0 until 60 step 3) b.receber(quadro(i)) // ~2/3 perdidos
        drenar(b, 60)

        assertTrue("perda de ~66% deveria marcar degradação", b.degradado())
    }

    @Test
    fun redeLimpa_naoMarcaDegradado() {
        val b = BufferDeJitter(inicialMs = 60)
        for (i in 0 until 60) b.receber(quadro(i))
        drenar(b, 60)

        assertFalse(b.degradado())
        assertEquals(0.0, b.perdaRecente, 0.001)
    }

    @Test
    fun reiniciar_deixaProntoParaNovaTransmissao() {
        val b = BufferDeJitter(inicialMs = 60)
        repeat(5) { b.receber(quadro(it)) }
        drenar(b, 5)
        b.reiniciar()

        assertEquals(SaidaDoJitter.Aguardando, b.proximo())
        assertEquals(0.0, b.perdaRecente, 0.001)
    }
}
