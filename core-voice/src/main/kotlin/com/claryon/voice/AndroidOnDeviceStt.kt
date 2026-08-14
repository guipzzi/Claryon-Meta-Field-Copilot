package com.claryon.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * STT **fallback** nativo — `SpeechRecognizer` **on-device** do Android.
 *
 * Regras (do material Un12): força o reconhecedor **local**
 * (`createOnDeviceSpeechRecognizer`, API 31+) — nunca o padrão, que pode
 * **vazar áudio** para servidor; roda na **main thread** (a API exige) e sempre
 * chama `destroy()`; `ERROR_NO_MATCH` é "não entendi" (não erro grave).
 *
 * É o ganho rápido que fecha o ciclo falar→transcrever→responder no aparelho,
 * sem NDK, enquanto o primário (whisper.cpp) é compilado. Exige o pacote de
 * idioma **pt-BR** baixado no dispositivo (`isAvailable()` reflete isso).
 */
class AndroidOnDeviceStt(context: Context) : SelfCapturingStt {

    private val appContext = context.applicationContext

    override suspend fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
            .getOrDefault(false)

    override suspend fun recognizeOnce(): Result<Transcript> = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            return@withContext Result.failure(
                ClaryonError.Voice("stt.unavailable", "Reconhecedor on-device indisponível (pacote pt-BR baixado?)."),
            )
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        try {
            suspendCancellableCoroutine<Result<Transcript>> { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        if (!cont.isActive) return
                        val texto = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        val conf = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                        cont.resume(
                            if (texto.isEmpty()) {
                                Result.failure(ClaryonError.Voice("stt.no_match", "Não entendi."))
                            } else {
                                Result.success(Transcript(texto, conf))
                            },
                        )
                    }

                    override fun onError(error: Int) {
                        if (!cont.isActive) return
                        cont.resume(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    Result.failure(ClaryonError.Voice("stt.no_match", "Não entendi."))
                                else ->
                                    Result.failure(ClaryonError.Voice("stt.error_$error", "Erro do reconhecedor ($error)."))
                            },
                        )
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                // invokeOnCancellation roda na thread de QUEM cancelou — que
                // raramente é a main. Chamar recognizer.cancel() ali dispara
                // "SpeechRecognizer should be used only from the application's
                // main thread" (armadilha conhecida), engolida pelo runCatching e
                // deixando o reconhecedor em estado indefinido. Por isso o
                // cancelamento é reenviado para a main thread.
                cont.invokeOnCancellation {
                    mainHandler.post { runCatching { recognizer.cancel() } }
                }
                recognizer.startListening(intent)
            }
        } finally {
            // destroy() também exige a main thread; o withContext(Main) externo
            // garante isso mesmo no caminho de exceção.
            recognizer.destroy()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
}
