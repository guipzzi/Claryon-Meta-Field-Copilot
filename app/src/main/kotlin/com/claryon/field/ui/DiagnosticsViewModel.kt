package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
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
            _audioStatus.value = "gravando 3 s… (rota ${audio.rotaAtual})"
            val buffer = ArrayList<Short>()
            withTimeoutOrNull(3_000) {
                audio.microfonePcm().collect { chunk -> chunk.forEach { buffer.add(it) } }
            }
            val pcm = buffer.toShortArray()
            val rota = audio.rotaAtual // capturar ANTES de liberar() (que zera a rota)
            _audioStatus.value = "reproduzindo ${pcm.size} amostras… (rota $rota)"
            audio.reproduzir(pcm, 16_000)
            audio.liberar()
            _audioStatus.value = "eco concluído · ${pcm.size} amostras · rota $rota"
        }
    }

    override fun onCleared() {
        audio.liberar()
        if (mockEnabled) mock?.disable()
        super.onCleared()
    }
}
