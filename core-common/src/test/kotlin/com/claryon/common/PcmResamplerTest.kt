package com.claryon.common

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun upsample8para16_dobraOTamanho_ePreservaInicio() {
        val input = ShortArray(100) { (it * 100).toShort() }
        val out = PcmResampler.resampleLinear(input, 8_000, 16_000)
        assertEquals(200, out.size)
        assertEquals(input[0], out[0]) // âncora inicial preservada
    }

    @Test
    fun mesmaTaxa_retornaOMesmoArray() {
        val input = shortArrayOf(1, 2, 3)
        assertSame(input, PcmResampler.resampleLinear(input, 16_000, 16_000))
    }

    @Test
    fun interpolaEntreAmostras() {
        // 2 amostras a 8k → 4 a 16k; o valor do meio fica entre 0 e 1000.
        val out = PcmResampler.resampleLinear(shortArrayOf(0, 1000), 8_000, 16_000)
        assertEquals(4, out.size)
        assertTrue("interpolação esperada", out[1] in 1..999)
    }

    // ── Anti-aliasing ─────────────────────────────────────────────────────────
    //
    // Estes testes existem porque o defeito que eles cobrem é INAUDÍVEL para um
    // teste de tamanho de array e para qualquer assert de "não estourou": o áudio
    // sai, com a duração certa, e só está sujo. Quebra em silêncio por definição.

    private fun tom(freqHz: Double, fs: Int, amostras: Int, amplitude: Double = 16_000.0) =
        ShortArray(amostras) { (amplitude * sin(2.0 * PI * freqHz * it / fs)).toInt().toShort() }

    private fun rms(x: ShortArray): Double {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v.toDouble()
        return sqrt(acc / x.size)
    }

    @Test
    fun descer22kPara16k_removeODobramentoQueAInterpolacaoCruaDeixaPassar() {
        // 9,5 kHz está acima da Nyquist de saída (8 kHz): sem filtro ele NÃO
        // desaparece — reaparece em 6,5 kHz, dentro da banda de voz.
        val entrada = tom(9_500.0, 22_050, 11_025)

        val cru = PcmResampler.resampleLinear(entrada, 22_050, 16_000)
        val filtrado = PcmResampler.resample(entrada, 22_050, 16_000)

        // Medido: 6.828 sem filtro contra 25 com filtro (razão 0,0037).
        assertTrue("o teste só vale se a versão crua realmente dobrar", rms(cru) > 5_000.0)
        assertTrue(
            "alias deveria sumir; sobrou RMS=${rms(filtrado)} contra ${rms(cru)}",
            rms(filtrado) < 0.02 * rms(cru),
        )
    }

    @Test
    fun descer22kPara16k_naoEmudeceAFala() {
        // O par do teste acima: um filtro que zera tudo também passaria naquele.
        // 1 kHz é onde vivem as formantes que sobrevivem ao elo HFP de 8 kHz.
        val entrada = tom(1_000.0, 22_050, 11_025)

        val cru = PcmResampler.resampleLinear(entrada, 22_050, 16_000)
        val filtrado = PcmResampler.resample(entrada, 22_050, 16_000)

        assertTrue(
            "banda passante deveria atravessar intacta; RMS=${rms(filtrado)} contra ${rms(cru)}",
            rms(filtrado) > 0.95 * rms(cru),
        )
    }

    @Test
    fun oFiltroTemGanhoUnitario_naoMexeNoVolume() {
        // Nível constante é o caso extremo de DC. Se o núcleo não estiver
        // normalizado, o volume da fala muda — e volume aqui é sinal operacional.
        val constante = ShortArray(2_000) { 8_000 }
        val saida = PcmResampler.filtrarParaNyquistDe(constante, 22_050, 16_000)

        assertEquals(constante.size, saida.size)
        for (v in saida) {
            assertTrue("ganho DC fora de ±1 LSB: $v", v.toInt() in 7_999..8_001)
        }
    }

    @Test
    fun subirNaoFiltra_oCaminhoDoWhisperSegueIntacto() {
        // 8 → 16 kHz não tem banda para dobrar. Se `resample` filtrasse aqui,
        // estaria jogando fora agudos do microfone sem ganho nenhum.
        val entrada = tom(3_000.0, 8_000, 800)
        assertArrayEquals(
            PcmResampler.resampleLinear(entrada, 8_000, 16_000),
            PcmResampler.resample(entrada, 8_000, 16_000),
        )
    }

    @Test
    fun sinalMaisCurtoQueONucleo_naoEstoura_eNaoZeraOMiolo() {
        // 63 taps contra 10 amostras: todo índice é borda. O ramo que trata isso
        // é o que impede um laço de limites invertidos devolver metade zerada.
        val curto = ShortArray(10) { 5_000 }
        val saida = PcmResampler.resample(curto, 22_050, 16_000)

        assertEquals(10L * 16_000 / 22_050, saida.size.toLong())
        assertTrue("saída não pode ser silêncio", saida.any { it > 4_000 })
    }

    @Test
    fun filtrar_preservaTamanho_eNaoEhChamadoAoSubir() {
        val x = tom(1_000.0, 22_050, 5_000)
        assertEquals(x.size, PcmResampler.filtrarParaNyquistDe(x, 22_050, 16_000).size)
        // Subir ou manter devolve o próprio array: nada a filtrar.
        assertSame(x, PcmResampler.filtrarParaNyquistDe(x, 16_000, 22_050))
        assertSame(x, PcmResampler.filtrarParaNyquistDe(x, 16_000, 16_000))
    }
}
