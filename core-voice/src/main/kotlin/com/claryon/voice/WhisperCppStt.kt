package com.claryon.voice

import com.claryon.common.ClaryonError
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * STT primário **on-device** — whisper.cpp (C++) via JNI, modelo `ggml-tiny`.
 *
 * Processa em **lote**: recebe o PCM da janela fechada pelo VAD (16 kHz mono) e
 * devolve o texto. O binding nativo (`WhisperContext`/`jni.c`) e a build CMake
 * do submódulo `whisper` são reaproveitados **verbatim** do exemplo Android
 * oficial do whisper.cpp — nada escrito de memória.
 *
 * **Duas origens de modelo**, e a distinção decide se há IA local no dia da
 * demonstração:
 *  - [ModelSource.Asset] — empacotado no APK (`assets/models/`, com `noCompress`).
 *    É o caminho de **produção**: instala junto com o app, funciona offline, e não
 *    depende do Wi-Fi do evento. Lê direto do APK, sem copiar ~75 MB para o disco.
 *  - [ModelSource.Arquivo] — caminho no sistema de arquivos (`adb push`, download
 *    por WorkManager). Serve para desenvolvimento e para trocar de modelo sem
 *    reinstalar.
 *
 * ⚠️ Espera **16 kHz**. Nosso HFP entrega 8 kHz; o resample é feito aqui.
 */
class WhisperCppStt(private val fonte: ModelSource) : SttEngine {

    /** Compatibilidade: caminho no sistema de arquivos. */
    constructor(modelPath: String) : this(ModelSource.Arquivo(modelPath))

    override val id: String = "whisper.cpp/ggml-tiny"

    private val mutex = Mutex()
    private var context: WhisperContext? = null

    override suspend fun isAvailable(): Boolean = fonte.existe()

    /**
     * Prior do domínio usado por esta instância. Parâmetro para permitir o braço de
     * controle (com prompt × sem prompt) que `docs/LEXICO_DO_INITIAL_PROMPT.md`
     * prescreve e que era impossível enquanto a string vivia em `jni.c`.
     */
    var promptDeDominio: String? = null

    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript> {
        if (!fonte.existe()) {
            return Result.failure(
                ClaryonError.Voice("stt.model_missing", "Modelo whisper não encontrado: $fonte"),
            )
        }
        // `withContext(Default)`: o carregamento do modelo (~75 MB via JNI), o
        // resample e a conversão para float rodavam no dispatcher do chamador,
        // que é a Main — segundos de UI congelada e risco de ANR justamente na
        // janela em que a meta é resposta em ≤ 2,0 s.
        //
        // A transcrição inteira roda sob o lock: soltar o mutex logo após obter o
        // contexto permitia um `release()` concorrente anular o campo no meio da
        // inferência — ou criar um SEGUNDO contexto de ~75 MB que nunca seria
        // liberado. O engine é um recurso nativo único; serializar é o correto.
        return withContext(Dispatchers.Default) {
        try {
            mutex.withLock {
            val ctx = context ?: fonte.abrir().also { context = it }
            // HFP entrega 8 kHz; o Whisper espera 16 kHz → reamostra se preciso.
            // `resample` e não `resampleLinear`: o caminho HFP sobe (8 → 16) e
            // segue idêntico, mas na bancada uma fonte a 44,1/48 kHz DESCE — e aí
            // interpolação crua injetaria alias no que o modelo vai transcrever.
            val pcm16k =
                if (sampleRateHz != TARGET_HZ) PcmResampler.resample(pcm, sampleRateHz, TARGET_HZ) else pcm
            // Whisper espera PCM float normalizado em [-1, 1], mono, 16 kHz.
            val floats = FloatArray(pcm16k.size) { pcm16k[it] / 32768.0f }
            val texto = ctx.transcribeData(floats, printTimestamp = false, initialPrompt = promptDeDominio).trim()
            if (texto.isEmpty()) {
                // Texto vazio NÃO é sucesso.
                //
                // `Result.success(Transcript(""))` é indistinguível de uma
                // transcrição legítima que resultou em nada, e o chamador não tem
                // como decidir entre "o agente não falou" e "o motor falhou". Num
                // produto cuja regra é que falha nunca é silêncio, devolver
                // sucesso vazio é a definição de silêncio.
                Result.failure(
                    ClaryonError.Voice("stt.sem_texto", "O reconhecimento não produziu texto."),
                )
            } else {
                // **`confidence` deixa de ser sempre `null`.**
                //
                // O campo existia desde o primeiro dia e nunca foi preenchido, e a
                // spec do gatilho registrou como risco aceito que "não há limiar de
                // confiança a ajustar". A afirmação era falsa sobre o artefato: o
                // whisper.cpp calcula `no_speech_prob` e a expõe em
                // `whisper_full_get_segment_no_speech_prob` (whisper.h:766) — faltava
                // binding, que agora existe.
                //
                // Convenção: `confidence` é **confiança de que É fala**, então é o
                // complemento. Alto = pode agir; baixo = recusar e pedir repetição.
                // Recusar por baixa confiança é recusa honesta; o critério de hoje é
                // casamento de string sobre texto que pode ter sido alucinado.
                val naoFala = ctx.probabilidadeDeNaoSerFala()
                Result.success(Transcript(texto, confidence = naoFala?.let { 1.0f - it }))
            }
            }
        } catch (e: Exception) {
            Result.failure(ClaryonError.Voice("stt.whisper_error", e.message ?: "erro no whisper.cpp"))
        }
        }
    }

    /** Libera o contexto nativo. Sob o mesmo lock da transcrição (ver [transcribe]). */
    suspend fun release() {
        mutex.withLock {
            context?.release()
            context = null
        }
    }

    private companion object {
        const val TARGET_HZ = 16_000
    }
}
