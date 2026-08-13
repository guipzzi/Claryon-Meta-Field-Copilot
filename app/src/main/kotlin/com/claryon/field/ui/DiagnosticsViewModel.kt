package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.field.BuildConfig
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    override fun onCleared() {
        if (mockEnabled) mock?.disable()
        super.onCleared()
    }
}
