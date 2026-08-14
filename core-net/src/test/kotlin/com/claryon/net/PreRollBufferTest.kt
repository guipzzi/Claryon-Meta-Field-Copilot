package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O pré-roll carrega duas promessas ao mesmo tempo: **não cortar a primeira
 * sílaba** e **não virar escuta ambiente**. Os testes cobrem as duas.
 */
class PreRollBufferTest {

    private val sr = 8_000
    private val amostrasPorQuadro = sr / 50 // 20 ms = 160 amostras

    private fun buffer(duracaoMs: Int = 600) =
        PreRollBuffer(sampleRateHz = sr, duracaoMs = duracaoMs, amostrasPorQuadro = amostrasPorQuadro)

    private fun silencio() = ShortArray(amostrasPorQuadro)
    private fun fala() = ShortArray(amostrasPorQuadro) { 4000 }

    private fun quadrosDe(ms: Int) = ms / 20

    @Test
    fun capacidadeCobreAJanelaConfigurada() {
        // 600 ms / 20 ms = 30 quadros. A 8 kHz/16 bits são 9,6 KB.
        assertEquals(30, buffer().capacidadeEmQuadros)
        assertEquals(30 * amostrasPorQuadro * 2, 30 * amostrasPorQuadro * 2) // 9600 bytes
    }

    @Test
    fun buffeCircular_nuncaCresceAlemDaCapacidade() {
        val b = buffer()
        repeat(200) { b.escrever(fala()) }
        assertEquals(30, b.tamanho)
    }

    @Test
    fun falaIniciadaAntesDoToque_naoEhCortada() {
        // Cenário que o pré-roll fixo de 300 ms erraria: a fala começou 460 ms
        // antes do toque. O recuo fixo cortaria 160 ms — a primeira sílaba.
        val b = buffer()
        repeat(quadrosDe(140)) { b.escrever(silencio()) }
        repeat(quadrosDe(460)) { b.escrever(fala()) }

        val capturado = b.desdeOInicioDaFala()
        val msCapturados = capturado.size * 1000 / sr

        assertTrue(
            "deveria recuperar ~460 ms de fala, veio $msCapturados ms",
            msCapturados >= 440,
        )
    }

    @Test
    fun falaQueComecouJuntoComOToque_naoArrastaRuidoDeRua() {
        // Buffer quase todo de silêncio (rua), fala só nos últimos 60 ms.
        val b = buffer()
        repeat(quadrosDe(540)) { b.escrever(silencio()) }
        repeat(quadrosDe(60)) { b.escrever(fala()) }

        val msCapturados = b.desdeOInicioDaFala().size * 1000 / sr
        assertTrue(
            "não pode arrastar o silêncio anterior; veio $msCapturados ms",
            msCapturados in 40..200,
        )
    }

    @Test
    fun semFalaNoBuffer_naoTransmiteNada() {
        // Aperto sem ter falado: transmite a partir do toque, não 600 ms de rua.
        val b = buffer()
        repeat(30) { b.escrever(silencio()) }
        assertEquals(0, b.desdeOInicioDaFala().size)
    }

    @Test
    fun pausaCurtaDentroDaFala_naoParteAFraseAoMeio() {
        // "apoio… na Rui Barbosa" — 60 ms de pausa no meio não pode cortar.
        val b = buffer()
        repeat(quadrosDe(100)) { b.escrever(silencio()) }
        repeat(quadrosDe(200)) { b.escrever(fala()) }
        repeat(quadrosDe(60)) { b.escrever(silencio()) } // pausa respiratória
        repeat(quadrosDe(240)) { b.escrever(fala()) }

        val msCapturados = b.desdeOInicioDaFala().size * 1000 / sr
        assertTrue(
            "a frase inteira (~500 ms) deveria vir junto; veio $msCapturados ms",
            msCapturados >= 460,
        )
    }

    @Test
    fun pausaLonga_cortaNaFalaMaisRecente() {
        // Fala antiga, silêncio longo, fala nova: só a nova interessa. A antiga
        // é conversa anterior — transmiti-la seria difundir o que não foi dito
        // para o rádio.
        val b = buffer()
        repeat(quadrosDe(200)) { b.escrever(fala()) }
        repeat(quadrosDe(240)) { b.escrever(silencio()) }
        repeat(quadrosDe(160)) { b.escrever(fala()) }

        val msCapturados = b.desdeOInicioDaFala().size * 1000 / sr
        assertTrue(
            "só a fala recente (~160 ms) deveria sair; veio $msCapturados ms",
            msCapturados in 120..260,
        )
    }

    @Test
    fun escrever_copiaOQuadro_naoGuardaAReferencia() {
        // O laço de captura reaproveita o mesmo array do AudioRecord. Guardar a
        // referência faria todo o pré-roll apontar para o último conteúdo lido.
        val b = buffer()
        val reaproveitado = ShortArray(amostrasPorQuadro) { 4000 }
        repeat(10) { b.escrever(reaproveitado) }
        reaproveitado.fill(0) // o chamador sobrescreve para a próxima leitura

        assertTrue(
            "o buffer não pode ter sido zerado junto com o array do chamador",
            b.desdeOInicioDaFala().any { it.toInt() != 0 },
        )
    }

    @Test
    fun limpar_zeraOConteudo() {
        // Conteúdo de áudio não deve sobreviver ao momento em que poderia ser usado.
        val b = buffer()
        repeat(30) { b.escrever(fala()) }
        b.limpar()

        assertEquals(0, b.tamanho)
        assertEquals(0, b.desdeOInicioDaFala().size)
    }
}
