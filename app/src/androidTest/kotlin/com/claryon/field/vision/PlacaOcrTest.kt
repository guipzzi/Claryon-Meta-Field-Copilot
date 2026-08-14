package com.claryon.field.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlacaOcrTest {

    /** Desenha uma placa impressa sintética (texto preto sobre fundo claro). */
    private fun placaBitmap(texto: String): Bitmap {
        val bmp = Bitmap.createBitmap(900, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 180f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(texto, 450f, 220f, paint)
        return bmp
    }

    @Test
    fun leEValidaPlacaMercosulImpressa() = runBlocking {
        val placa = PlacaOcr().lerPlaca(placaBitmap("ABC1D23"))
        assertEquals("ABC1D23", placa)
    }
}
