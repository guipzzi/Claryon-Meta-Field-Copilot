package com.claryon.field.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Result
import com.claryon.field.audio.AudioDoAgente
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Painel de diagnóstico — existe apenas no build de depuração.**
 *
 * ## Por que source set, e não `if (BuildConfig.DEBUG)`
 *
 * Estes métodos viviam dentro do `DiagnosticsViewModel` de produção. Eram
 * inalcançáveis em runtime (a `DiagnosticsScreen` nunca foi composta), mas
 * **embarcavam no APK de release** — e inalcançável não é o mesmo que gateado.
 * Um `setContent` distraído, ou uma tela de suporte acrescentada às pressas na
 * véspera, bastaria para ligar o MockDeviceKit num aparelho em operação.
 *
 * Em `app/src/debug` o compilador de release nem enxerga o arquivo.
 * Verificável no artefato: `javap` na `classes.dex` do release não acha esta
 * classe. É a mesma proteção que `rotaDeTeste` usa em `core-audio`.
 *
 * O que ficou em produção é o `OculosViewModel`: registro do DAT e o aviso
 * falado de "óculos não conectados", que a tela de perfil mostra de verdade.
 */
class DiagnosticoViewModel(app: Application) : AndroidViewModel(app) {

    private val facade = DatGlassesFacade(viewModelScope)
    private val mock = MockDeviceController(app)
    private var mockEnabled = false

    private val audio = AudioDoAgente.de(app)

    val registration = facade.registration
    val session = facade.session
    val streamState = facade.streamState
    val frameInfo = facade.frameInfo
    val deviceCount = facade.deviceCount

    /**
     * **A causa da última falha do stream, não só o estado.**
     *
     * `Stream.errorStream` passou a ser coletado dentro da fachada; este é o
     * consumidor vivo dele no único caminho que hoje abre câmera de verdade — o
     * painel. Em produção a causa chega por outra porta, tipada no
     * `Result.Failure` de `withCamera`, que é o que um chamador precisa.
     *
     * O estado sozinho dizia "STOPPED" para hastes dobradas, permissão negada,
     * bateria no fim e superaquecimento — quatro problemas com quatro ações
     * diferentes e um único rótulo.
     */
    private val _causaDoStream = MutableStateFlow("—")
    val causaDoStream: StateFlow<String> = _causaDoStream.asStateFlow()

    init {
        viewModelScope.launch {
            facade.streamErrors.collect { erro -> _causaDoStream.value = erro.message }
        }
    }

    val mockAvailable: Boolean = true

    private val _mockStatus = MutableStateFlow("desligado")
    val mockStatus: StateFlow<String> = _mockStatus.asStateFlow()

    private val _audioStatus = MutableStateFlow("—")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    /** Dispara o fluxo de registro do DAT — o deeplink para o app Meta AI. */
    fun registrar(activity: Activity) {
        facade.startRegistration(activity)
    }

    /** Habilita o MDK, pareia o Ray-Ban simulado e aponta a câmera do celular. */
    fun enableMock() {
        if (mockEnabled) return
        mockEnabled = mock.enableWithPhoneCameraFeed()
        _mockStatus.value =
            if (mockEnabled) "pareado (Ray-Ban simulado + câmera do celular)" else "falha ao parear"
    }

    fun startSession() {
        viewModelScope.launch { facade.startSession() }
    }

    fun startCamera() = facade.startCameraStream()

    fun stopCamera() = facade.stopCameraStream()

    /**
     * Ciclo de eco: rotear → gravar 3 s → reproduzir → liberar. Exige fone HFP
     * real — o MockDeviceKit não simula áudio.
     */
    fun echo() {
        // Dispatchers.Default: o acúmulo amostra a amostra e a reprodução não
        // podem rodar na Main.
        viewModelScope.launch(Dispatchers.Default) {
            // `iniciar()` falhou ⇒ nenhum usuário foi contado ⇒ NÃO chamar
            // `liberar()`: chamada extra desequilibraria a contagem e derrubaria
            // a rota de quem estivesse capturando em paralelo.
            val prova: GlassesAudioRoute = when (val r = audio.iniciar()) {
                is Result.Failure -> {
                    _audioStatus.value = "sem rota: ${r.error.message}"
                    return@launch
                }
                is Result.Success -> r.value
            }
            val rota = audio.rotaAtual // capturar ANTES de liberar() (que zera a rota)
            try {
                _audioStatus.value = "gravando 3 s… (rota $rota)"
                val buffer = ArrayList<Short>()
                withTimeoutOrNull(3_000) {
                    audio.microfonePcm(prova).collect { chunk -> chunk.forEach { buffer.add(it) } }
                }
                val pcm = buffer.toShortArray()
                _audioStatus.value = "reproduzindo ${pcm.size} amostras… (rota $rota)"
                audio.reproduzir(pcm, audio.taxaDeAmostragemHz)
                _audioStatus.value = "eco concluído · ${pcm.size} amostras · rota $rota"
            } catch (e: Exception) {
                // Ex.: RECORD_AUDIO negada, AudioRecord não inicializa.
                // Falha nunca é silêncio.
                _audioStatus.value = "falha no eco: ${e.message}"
            } finally {
                audio.liberar()
            }
        }
    }

    override fun onCleared() {
        facade.stopCameraStream()
        facade.stopSession()
        mock.disable()
        super.onCleared()
    }
}
