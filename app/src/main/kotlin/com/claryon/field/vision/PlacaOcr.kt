package com.claryon.field.vision

import android.graphics.Bitmap
import com.claryon.agent.PlacaValidator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR de placa **on-device** (ML Kit Text Recognition, modelo Latin embarcado —
 * roda offline, sem rede no caminho crítico). Recebe um frame já decodificado
 * (Bitmap), lê o texto e devolve **apenas** a placa validada por [PlacaValidator].
 *
 * Política de privacidade e energia (CLAUDE.md):
 *  - o frame é insumo efêmero: **não** é persistido — só o texto validado
 *    sobrevive à inferência (minimização);
 *  - dispara por intenção explícita, nunca em laço; o chamador controla a taxa.
 */
class PlacaOcr(
    private val recognizer: com.google.mlkit.vision.text.TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : java.io.Closeable {

    /**
     * `TextRecognizer` segura recursos nativos (o modelo Latin carregado). Sem
     * `close()`, cada instância vaza o modelo — e o ciclo de vida deste objeto
     * acompanha a tela/serviço que o criou.
     */
    override fun close() = recognizer.close()

    /** Texto bruto lido pelo OCR (para diagnóstico/painel). */
    suspend fun lerTexto(bitmap: Bitmap): String {
        val imagem = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(imagem)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** Placa válida (Mercosul ou antiga) lida no frame, ou `null` se não houver. */
    suspend fun lerPlaca(bitmap: Bitmap): String? = PlacaValidator.extrair(lerTexto(bitmap))
}
