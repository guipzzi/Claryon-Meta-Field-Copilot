package com.claryon.common

/**
 * Reamostragem de PCM 16-bit mono por **interpolação linear**.
 *
 * Caso de uso central: **upsample 8 kHz → 16 kHz** — o microfone dos óculos
 * chega mono a 8 kHz via HFP, mas o Whisper (e a maioria dos modelos de voz)
 * espera 16 kHz. Interpolação linear basta para upsample de fala.
 *
 * ⚠️ Para **downsample** (ex.: 44,1 → 16 kHz) seria preciso um filtro
 * anti-aliasing antes; não é o caso HFP→Whisper. Reamostrar de 8 para 16 kHz
 * **não recupera** informação que o link a 8 kHz não transmitiu — só adapta o
 * formato para o modelo.
 */
object PcmResampler {

    fun resampleLinear(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        require(fromHz > 0 && toHz > 0) { "taxas devem ser positivas" }
        if (fromHz == toHz || input.size < 2) return input

        val outLen = (input.size.toLong() * toHz / fromHz).toInt()
        if (outLen <= 0) return ShortArray(0)

        val out = ShortArray(outLen)
        val step = fromHz.toDouble() / toHz
        for (i in 0 until outLen) {
            val srcPos = i * step
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = input[idx].toInt()
            val s1 = if (idx + 1 < input.size) input[idx + 1].toInt() else s0
            out[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
