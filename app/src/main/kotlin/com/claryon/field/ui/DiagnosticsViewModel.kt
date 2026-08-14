package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.OperationalResponses
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import com.claryon.field.voice.VoiceCycle
import com.claryon.voice.AndroidOnDeviceStt
import com.claryon.voice.AndroidTts
import com.claryon.voice.EnergyVoiceActivityDetector
import com.claryon.voice.WhisperCppStt
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel do painel de diagnóstico (M2).
 *
 * Orquestra a fachada real do DAT ([DatGlassesFacade]) e, em builds DEBUG, o
 * [MockDeviceController] — permitindo exercitar registro → sessão → stream de
 * câmera **sem óculos e sem o app Meta AI**, com a câmera do celular como fonte.
 *
 * Expõe os StateFlows do SDK direto à UI. O `viewModelScope` é o escopo de vida
 * da fachada (coletas de registro/sessão/stream).
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val facade = DatGlassesFacade(viewModelScope)
    private val mock = if (BuildConfig.DEBUG) MockDeviceController(app) else null
    private var mockEnabled = false

    val registration = facade.registration
    val session = facade.session
    val streamState = facade.streamState
    val frameInfo = facade.frameInfo
    val deviceCount = facade.deviceCount

    val mockAvailable: Boolean = mock != null

    private val _mockStatus = MutableStateFlow("desligado")
    val mockStatus: StateFlow<String> = _mockStatus.asStateFlow()

    /** DEBUG: habilita o MDK, pareia o Ray-Ban simulado e aponta a câmera do celular. */
    fun enableMock() {
        val controller = mock ?: return
        if (mockEnabled) return
        mockEnabled = controller.enableWithPhoneCameraFeed()
        _mockStatus.value =
            if (mockEnabled) "pareado (Ray-Ban simulado + câmera do celular)" else "falha ao parear"
    }

    fun startSession() {
        viewModelScope.launch { facade.startSession() }
    }

    fun startCamera() = facade.startCameraStream()

    fun stopCamera() = facade.stopCameraStream()

    // ── Áudio HFP (M3) ────────────────────────────────────────────────────────

    // Em DEBUG permite fallback para o dispositivo padrão (o emulador/MDK não têm
    // SCO). Em produto, só rota HFP dos óculos.
    private val audio = GlassesAudioManagerImpl(app, allowFallbackToDefault = BuildConfig.DEBUG)

    private val _audioStatus = MutableStateFlow("—")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    /** Ciclo de eco: rotear → gravar 3 s → reproduzir → liberar. Exige fone HFP real. */
    fun echo() {
        viewModelScope.launch {
            when (val r = audio.iniciar()) {
                is Result.Failure -> {
                    _audioStatus.value = "sem rota: ${r.error.message}"
                    audio.liberar()
                    return@launch
                }
                is Result.Success -> Unit
            }
            val rota = audio.rotaAtual // capturar ANTES de liberar() (que zera a rota)
            try {
                _audioStatus.value = "gravando 3 s… (rota $rota)"
                val buffer = ArrayList<Short>()
                withTimeoutOrNull(3_000) {
                    audio.microfonePcm().collect { chunk -> chunk.forEach { buffer.add(it) } }
                }
                val pcm = buffer.toShortArray()
                _audioStatus.value = "reproduzindo ${pcm.size} amostras… (rota $rota)"
                audio.reproduzir(pcm, 16_000)
                _audioStatus.value = "eco concluído · ${pcm.size} amostras · rota $rota"
            } catch (e: Exception) {
                // Ex.: RECORD_AUDIO negada, AudioRecord não inicializa. Falha nunca é silêncio.
                _audioStatus.value = "falha no eco: ${e.message}"
            } finally {
                audio.liberar()
            }
        }
    }

    // ── Ciclo de voz — cérebro + saída (M4) ────────────────────────────────────

    private val router = DeterministicIntentRouter()
    private val tts = AndroidTts(app)
    private val stt = AndroidOnDeviceStt(app)

    private val _commandStatus = MutableStateFlow("—")
    val commandStatus: StateFlow<String> = _commandStatus.asStateFlow()

    /** Comando por TEXTO (bypassa o STT): roteador → resposta lacônica → TTS. */
    fun runCommand(text: String) {
        viewModelScope.launch { processar(text) }
    }

    /**
     * Ciclo de voz REAL no aparelho: STT on-device (auto-capturador) → roteador
     * → TTS. Fecha falar→transcrever→responder sem NDK. Exige pt-BR baixado
     * (indisponível no emulador → mensagem clara).
     */
    fun falarComando() {
        viewModelScope.launch {
            if (!stt.isAvailable()) {
                _commandStatus.value = "STT on-device indisponível (baixe o pt-BR nas configs de voz)"
                return@launch
            }
            _commandStatus.value = "ouvindo…"
            when (val r = stt.recognizeOnce()) {
                is Result.Success -> processar(r.value.text)
                is Result.Failure -> _commandStatus.value = "STT: ${r.error.message}"
            }
        }
    }

    /** Roteia o texto e fala a resposta pelo pipeline HFP. */
    private suspend fun processar(text: String) {
        val intent = router.route(text)
        val resposta = OperationalResponses.para(intent)
        val nome = intent::class.simpleName
        _commandStatus.value = "\"$text\" → $nome → \"$resposta\""
        when (val syn = tts.synthesize(resposta)) {
            is Result.Success -> {
                if (audio.iniciar() is Result.Success) {
                    audio.reproduzir(syn.value.samples, syn.value.sampleRateHz)
                    audio.liberar()
                }
                _commandStatus.value = "\"$text\" → $nome → \"$resposta\" (falado)"
            }
            is Result.Failure ->
                _commandStatus.value = "$nome → \"$resposta\" (TTS: ${syn.error.message})"
        }
    }

    /**
     * Ciclo de voz COMPLETO (push-to-talk): captura HFP → VAD → earcon → STT
     * (whisper se o modelo estiver em filesDir; senão degrada) → roteador →
     * resposta → TTS → reprodução. É o [VoiceCycle] com os engines reais.
     */
    fun cicloDeVoz() {
        viewModelScope.launch {
            when (val rota = audio.iniciar()) {
                is Result.Failure -> {
                    _commandStatus.value = "ciclo: sem rota de áudio (${rota.error.message})"
                    audio.liberar(); return@launch
                }
                is Result.Success -> Unit
            }
            val modelo = File(getApplication<Application>().filesDir, "ggml-tiny.bin")
            val whisper = if (modelo.exists()) WhisperCppStt(modelo.path) else null
            _commandStatus.value = "ciclo: ouvindo… (STT=${if (whisper != null) "whisper" else "indisponível"})"

            val cycle = VoiceCycle(
                pcmInput = { audio.microfonePcm() },
                vad = EnergyVoiceActivityDetector(sampleRateHz = 16_000),
                sttFn = { pcm, sr ->
                    (whisper?.transcribe(pcm, sr) as? Result.Success)?.value?.text.orEmpty()
                },
                router = router,
                ttsFn = { texto -> (tts.synthesize(texto) as? Result.Success)?.value },
                playFn = { audio.reproduzir(it.samples, it.sampleRateHz) },
                onOuviVoce = { _commandStatus.value = "ciclo: ouvi você (earcon)" },
                sampleRateHz = 16_000,
            )
            val r = runCatching { withTimeoutOrNull(8_000) { cycle.runOnce() } }.getOrNull()
            audio.liberar()
            whisper?.release()
            _commandStatus.value = if (r != null) {
                "\"${r.transcricao}\" → ${r.intent::class.simpleName} → \"${r.resposta}\""
            } else {
                "ciclo: sem fala detectada (8 s)"
            }
        }
    }

    override fun onCleared() {
        audio.liberar()
        tts.liberar()
        if (mockEnabled) mock?.disable()
        super.onCleared()
    }
}
