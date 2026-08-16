package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A taxa declarada tem de ser a taxa capturada.**
 *
 * Este arquivo existe por causa de um defeito que sobreviveu a toda a suíte: o
 * rádio declarava 8 kHz enquanto o microfone entregava 16 kHz, e o resultado era
 * a fala transmitida saindo **uma oitava abaixo, com o dobro da duração**. Nenhum
 * teste falhou, porque cada peça estava certa isoladamente — o erro morava no
 * *acordo* entre elas, e acordo não tem arquivo.
 *
 * O que estes testes travam não é um valor: é a relação. Trocar a fonte para 8 ou
 * 48 kHz continua passando, desde que quem consome derive dela.
 */
class TaxaDeAmostragemTest {

    /**
     * Um quadro de 20 ms tem de conter 20 ms de áudio.
     *
     * `amostrasPorQuadro = taxa / 50` é a fórmula usada no rádio. Com a taxa
     * errada ela produz um número que o resto do sistema **rotula** como 20 ms
     * sem ser — e a duração errada é o que faz a fala sair lenta.
     */
    @Test
    fun quadroDe20msContemUmVigesimoDeSegundo() {
        listOf(8_000, 16_000, 48_000).forEach { taxa ->
            val amostras = taxa / 50
            val duracaoMs = amostras * 1000.0 / taxa
            assertEquals("taxa $taxa deve render quadro de 20 ms", 20.0, duracaoMs, 0.001)
        }
    }

    /**
     * O pré-roll guarda os milissegundos que anuncia.
     *
     * Com `PreRollBuffer(8_000)` contra uma fonte de 16 kHz, os 600 ms viravam
     * **300 ms reais** — o recurso que existe para não cortar a primeira sílaba
     * cortava a primeira sílaba. É o mesmo defeito da taxa, num lugar onde ele não
     * produz som errado e sim **conteúdo faltando**, que é mais difícil de notar.
     */
    @Test
    fun preRollGuardaOTempoQueAnuncia() {
        val taxa = 16_000
        val buffer = PreRollBuffer(sampleRateHz = taxa)
        val amostrasPorQuadro = taxa / 50

        // Amplitude ACIMA do limiar de RMS: `desdeOInicioDaFala` aplica VAD
        // retroativo e devolve só a partir do início detectado da fala. A
        // primeira versão deste teste enchia com RMS 1 e recebia vazio — o teste
        // estava errado, não o código, e ele me disse isso.
        val alto = (PreRollBuffer.LIMIAR_RMS_PADRAO * 4).toInt().toShort()
        repeat(80) { buffer.escrever(ShortArray(amostrasPorQuadro) { alto }) }

        val guardado = buffer.desdeOInicioDaFala()
        val msGuardados = guardado.size * 1000.0 / taxa
        assertTrue(
            "pré-roll guardou ${msGuardados.toInt()} ms; esperado perto de ${PreRollBuffer.DURACAO_PADRAO_MS}",
            msGuardados >= PreRollBuffer.DURACAO_PADRAO_MS * 0.9,
        )
    }

    /**
     * `ConfigOpus` construído sem taxa explícita continua em 8 kHz — e isso é o
     * padrão perigoso que o defeito explorou.
     *
     * O teste não pede que o padrão mude: pede que quem o usa **saiba** que ele
     * existe. Se alguém subir o padrão para 16 kHz, este teste falha e obriga a
     * revisar todo consumidor que dependia do 8.
     */
    @Test
    fun padraoDoConfigOpusEhOitoKhz_eQuemUsaPrecisaSobrescrever() {
        assertEquals(8_000, ConfigOpus().sampleRateHz)
        assertEquals(16_000, ConfigOpus(sampleRateHz = 16_000).sampleRateHz)
    }
}
