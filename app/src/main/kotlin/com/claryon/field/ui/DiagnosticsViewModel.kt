package com.claryon.field.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.Utterance
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.audio.SaidaUnica
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.MockDeviceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
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

    /**
     * Dispara o fluxo de registro do DAT — o deeplink para o app Meta AI.
     *
     * Isto não existia. `DatGlassesFacade.startRegistration` estava escrito,
     * documentado e **nunca chamado por ninguém**; `ensureRegistered` idem. O
     * app detectava `UNAVAILABLE` e não fazia nada a respeito.
     *
     * Em Dev Mode só um app de terceiros fica registrado por vez, então perder o
     * registro é rotina — basta o desenvolvedor abrir outro app do DAT. E foi
     * medido no MockDeviceKit que **parear um aparelho não restaura o registro**
     * (fica `UNAVAILABLE` indefinidamente). Sem este caminho, o agente ficaria
     * com um app que não conecta e nenhuma pista do porquê.
     */
    fun registrar(activity: Activity) {
        facade.startRegistration(activity)
    }

    /**
     * Diz em voz alta que os óculos não estão conectados.
     *
     * Mesma regra do aviso de permissão: uma vez por abertura, nível
     * informativo. A tela mostra `UNAVAILABLE`; o ouvido recebe a manchete —
     * porque o agente que está de óculos não está olhando para a tela.
     */
    fun anunciarRegistroPerdido() {
        if (jaAnunciouRegistro) return
        if (facade.registration.value == RegistrationStatus.REGISTERED) return
        jaAnunciouRegistro = true
        // A fila de som é dono de processo (`SaidaUnica`), não campo deste
        // ViewModel — foi por isso que o aviso sobreviveu ao corte sem precisar
        // de um `saida` local.
        SaidaUnica.de(getApplication()).emitir(
            Utterance.SinalizarEFalar(Earcon.FALHA, "Óculos não conectados.", Priority.INFORMATIVO),
        )
    }

    private var jaAnunciouRegistro = false
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
    // Dono único do processo. Ver `AudioDoAgente` — a instância separada daqui
    // derrubava a rota SCO por baixo da captura do rádio.
    private val audio = AudioDoAgente.de(app)

    private val _audioStatus = MutableStateFlow("—")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    /** Ciclo de eco: rotear → gravar 3 s → reproduzir → liberar. Exige fone HFP real. */
    fun echo() {
        // Dispatchers.Default: o acúmulo amostra a amostra e a reprodução não
        // podem rodar na Main (viewModelScope é Main.immediate por padrão).
        viewModelScope.launch(Dispatchers.Default) {
            // iniciar() falhou ⇒ nenhum usuário foi contado ⇒ NÃO chamar liberar()
            // (chamada extra desequilibraria a contagem e derrubaria a rota de
            // quem estivesse capturando em paralelo).
            val prova = when (val r = audio.iniciar()) {
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

    // ── Sessão e rede (C2) ────────────────────────────────────────────────────
    //
    // A sessão saiu daqui: `SessaoDoAgente` é dono de processo. Este ViewModel
    // chamava-se "Diagnostics" e guardava a credencial do app inteiro, o que
    // impedia a decomposição — `MainActivity` precisava dele só para o portão de
    // login. Os campos abaixo são reexportações finas, mantidas para não mudar a
    // superfície da UI no mesmo passo em que se move a propriedade.

    val redeConfigurada: Boolean = SessaoDoAgente.redeConfigurada

    val autenticacao = SessaoDoAgente.de(app)

    /**
     * Anuncia o que está degradado **no DAT**.
     *
     * Existe porque o aviso foi escrito e não tinha chamador — um método de
     * aviso que ninguém chama é pior que aviso nenhum: dá a impressão, para
     * quem lê o código, de que o produto avisa.
     *
     * A parte de PERMISSÕES mudou de casa junto com quem fala: quem tem a fila
     * de som é o `CopilotoViewModel`, e `MainActivity` chama os dois.
     */
    fun anunciarEstadoDegradado() {
        anunciarRegistroPerdido()
    }

    override fun onCleared() {
        // Encerramento na ordem do contrato: câmera → sessão → engines.
        // Sem parar a sessão, o stream dos óculos segue transmitindo por
        // Bluetooth depois que a tela morre, sem nenhum indicador — e um novo
        // DatGlassesFacade tentaria createSession com a anterior ainda viva.
        //
        // **`audio` e `saida` NÃO são liberados aqui.** Os dois são donos únicos
        // de processo (`AudioDoAgente`, `SaidaUnica`), não recursos deste
        // ViewModel — `audio.liberarTudo()` chegou a estar aqui e era uma
        // regressão real: `MainActivity.kt` pede `diag` antes de `radio`
        // (`viewModel()`), e `ViewModelStore` limpa na ordem de inserção, então
        // TODO encerramento de turno derrubava a rota SCO por baixo do
        // `AudioRecord` do rádio, que segue vivo. Quem encerra um recurso de
        // processo é o dono do processo, não o primeiro ViewModel a morrer.
        //
        // A gravação de evidência saiu junto com o executor, para o
        // `CopilotoViewModel` — é ele que cancela o `gravacaoJob` agora.
        facade.stopCameraStream()
        facade.stopSession()
        mock?.disable()
        // A gravação não é finalizada aqui de propósito: `onCleared` não é
        // suspensa e o `viewModelScope` já está morrendo. Não há perda — o cofre
        // grava manifesto parcial a cada segmento, então o que ficou no disco
        // continua com cadeia de custódia demonstrável e verificável.
        super.onCleared()
    }
}
