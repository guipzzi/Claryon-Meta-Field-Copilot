package com.claryon.field.vision

import android.graphics.Bitmap
import android.util.Log
import com.claryon.glasses.Frame

/**
 * **Frame do DAT → Bitmap para o OCR, usando SÓ o plano de luminância.**
 *
 * ## Por que só o Y, e por que isso não é preguiça
 *
 * O que chega em [Frame.bytes] é a saída de um `MediaCodec` **de dentro do SDK**, e
 * o formato de cor dela não é uma escolha nossa. O pedido é conhecido — `javap` em
 * `mwdat-camera-0.9.0`, `VideoFormat.Companion.getDefaultFormat()`:
 *
 * ```
 * H265, 504x896, frameRate 30, iFrameInterval 3, bitRate 750000, colorFormat 19
 * ```
 *
 * `19` é `COLOR_FormatYUV420Planar` (I420). Mas **pedido não é entrega**: o
 * `MediaCodec` de cada aparelho pode devolver `COLOR_FormatYUV420SemiPlanar` (NV12),
 * `YV12` ou um formato flexível, e o `VideoFrame` do DAT não carrega qual foi — ele
 * tem `buffer`, `width`, `height`, `presentationTimeUs`, `isCompressed` e
 * `isCodecConfig`, e nada mais (`javap` no mesmo artefato). Não há como perguntar.
 *
 * A saída é a propriedade que **todos** os layouts 4:2:0 compartilham: o plano Y vem
 * primeiro, em resolução plena, ocupando exatamente `largura × altura` bytes. O que
 * muda entre I420, YV12, NV12 e NV21 é a ordem e o entrelaçamento da **croma** — e
 * croma é a única coisa que o reconhecimento de texto não usa. Adivinhar o layout
 * daria uma imagem de cor errada quando a adivinhação falhasse; ler só o Y dá uma
 * imagem em cinza **correta em qualquer um dos quatro**.
 *
 * ## O que este arquivo recusa, e por quê
 *
 * Frame comprimido ([Frame.comprimido]) e buffer curto demais para conter o plano Y
 * viram `null`. É a diferença entre "o OCR não achou placa" e "o OCR leu bitstream
 * de H.265 como se fosse imagem": o segundo não produz erro, produz ruído — e ruído
 * que passe por [com.claryon.agent.PlacaValidator] seria uma placa **fabricada**,
 * que é o pior desfecho deste fluxo inteiro.
 *
 * ## O que ainda não foi medido
 *
 * **Padding de linha (`rowStride > width`).** O SDK clona o buffer de saída com
 * `info.size`, e se o codec do aparelho alinhar as linhas, `size` passa de
 * `w × h × 3/2`. Aqui a leitura segue row-major mesmo assim, e o resultado seria uma
 * imagem cisalhada — que **não lê placa nenhuma**, em vez de ler a errada. O
 * [Log] abaixo publica a razão medida para que a primeira execução com óculos
 * reais responda isso em vez de o código presumir. Ver
 * `docs/VERIFICACOES_COM_HARDWARE.md`.
 *
 * **Rotação.** 504×896 é retrato; a placa é horizontal. Se o sensor entregar o frame
 * girado, o texto chega de lado e o ML Kit não o lê. Só se sabe com óculos.
 */
object FrameParaBitmap {

    /**
     * @return bitmap em cinza (ARGB com R=G=B=Y), ou `null` se o frame não puder
     *   ser lido como imagem.
     */
    fun luminancia(frame: Frame): Bitmap? {
        val pixels = frame.width * frame.height
        if (pixels <= 0) return null

        if (frame.comprimido) {
            Log.w(TAG, "frame comprimido recusado pelo OCR (${frame.width}x${frame.height})")
            return null
        }

        // O plano Y inteiro precisa estar no buffer. Um frame comprimido de 750 kbps
        // tem ordens de grandeza menos bytes que isto, então a guarda também pega o
        // caso em que `comprimido` mentir.
        if (frame.bytes.size < pixels) {
            Log.w(
                TAG,
                "frame curto demais para o plano Y: ${frame.bytes.size} B para $pixels px",
            )
            return null
        }

        // Diagnóstico da razão real. 1,5 é 4:2:0 sem padding; qualquer outra coisa é
        // informação que só o aparelho tem, e que precisa aparecer no log em vez de
        // virar suposição no código.
        if (frame.bytes.size != pixels + pixels / 2) {
            Log.i(
                TAG,
                "razão bytes/pixel = ${"%.3f".format(frame.bytes.size.toDouble() / pixels)} " +
                    "(${frame.bytes.size} B, ${frame.width}x${frame.height}) — " +
                    "esperado 1.500 para 4:2:0 sem padding de linha",
            )
        }

        val argb = IntArray(pixels)
        val bytes = frame.bytes
        for (i in 0 until pixels) {
            val y = bytes[i].toInt() and 0xFF
            argb[i] = OPACO or (y shl 16) or (y shl 8) or y
        }
        return Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    private const val OPACO = 0xFF shl 24
    private const val TAG = "ClaryonField"
}
