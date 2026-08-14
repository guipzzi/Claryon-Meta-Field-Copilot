package com.claryon.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **V2 — sonda do codec.** Não afirma um resultado: registra o que o aparelho tem.
 *
 * No emulador a lista de codecs é a do sistema convidado e difere de aparelho
 * real, então isto é sinal, não prova. A resposta que decide entre MediaCodec e
 * libopus/NDK vem do celular — ver `docs/VERIFICACOES_COM_HARDWARE.md`.
 */
@RunWith(AndroidJUnit4::class)
class SondaDeCodecTest {

    @Test
    fun registraOQueOAparelhoOferece() {
        val relatorio = SondaDeCodec.relatorio()
        Log.w("ClaryonField", "V2 $relatorio")
        Log.w("ClaryonField", "V2 detalhe opus:\n" + SondaDeCodec.detalhar(SondaDeCodec.MIME_OPUS))

        // A única garantia real do sistema: o decodificador Opus. Se nem ele
        // existir, a suposição de base do C1 está errada e é melhor saber agora.
        assertTrue(
            "sem decodificador Opus o receptor não tem como tocar nada: $relatorio",
            SondaDeCodec.capacidade(SondaDeCodec.MIME_OPUS).temDecoder,
        )
    }
}
