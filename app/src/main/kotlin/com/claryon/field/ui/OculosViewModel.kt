package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.Utterance
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.field.audio.SaidaUnica
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.RegistrationStatus
import com.claryon.net.AutenticacaoSupabase

/**
 * **O estado dos óculos, e só isso.**
 *
 * Este arquivo chamava-se `DiagnosticsViewModel` e tinha 769 linhas com cinco
 * subsistemas. Depois de extrair `MapaViewModel`, `CopilotoViewModel` e
 * `SessaoDoAgente`, o que sobrou eram DUAS coisas com destinos opostos:
 *
 *  - o **estado do registro do DAT**, que a tela de perfil mostra em produção;
 *  - o **painel de diagnóstico** (eco de áudio, MockDeviceKit, câmera), que só
 *    faz sentido em desenvolvimento.
 *
 * Os dois viviam na mesma classe, e a consequência era concreta: `echo()`,
 * `enableMock()` e `startCamera()` **embarcavam no APK de release**, mortos
 * porque a tela que os chama nunca foi composta. Inalcançável não é o mesmo que
 * gateado — o código estava lá, e um `setContent` distraído bastaria.
 *
 * O corte é por *source set*, não por `if (BuildConfig.DEBUG)`: `DiagnosticoViewModel`
 * e `DiagnosticsScreen` vivem em `app/src/debug`, e o compilador de release nem
 * os vê. É a mesma proteção que `rotaDeTeste` usa em `core-audio`, e é
 * verificável no artefato por `javap`.
 */
class OculosViewModel(app: Application) : AndroidViewModel(app) {

    private val facade = DatGlassesFacade(viewModelScope)

    val registration = facade.registration

    /** `false` quando `local.properties` não trouxe o projeto: sem servidor, sem C2. */
    val redeConfigurada: Boolean = SessaoDoAgente.redeConfigurada

    val autenticacao: AutenticacaoSupabase = SessaoDoAgente.de(app)

    /**
     * Anuncia o que está degradado **no DAT**.
     *
     * Existe porque o aviso foi escrito e não tinha chamador — um método de
     * aviso que ninguém chama é pior que aviso nenhum: dá a impressão, para quem
     * lê o código, de que o produto avisa.
     *
     * A parte de PERMISSÕES mudou de casa junto com quem fala: quem tem a fila
     * de som é o `CopilotoViewModel`, e `MainActivity` chama os dois.
     */
    fun anunciarEstadoDegradado() {
        anunciarRegistroPerdido()
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
        // ViewModel.
        SaidaUnica.de(getApplication()).emitir(
            Utterance.SinalizarEFalar(Earcon.FALHA, "Óculos não conectados.", Priority.INFORMATIVO),
        )
    }

    private var jaAnunciouRegistro = false

    override fun onCleared() {
        // Sem parar a sessão, o stream dos óculos segue transmitindo por
        // Bluetooth depois que a tela morre, sem nenhum indicador — e um novo
        // DatGlassesFacade tentaria createSession com a anterior ainda viva.
        //
        // **`audio` e `saida` NÃO são liberados aqui**: os dois são donos únicos
        // de processo (`AudioDoAgente`, `SaidaUnica`). `audio.liberarTudo()`
        // chegou a estar aqui e derrubava a rota SCO por baixo do `AudioRecord`
        // do rádio em todo encerramento de turno.
        facade.stopCameraStream()
        facade.stopSession()
        super.onCleared()
    }
}
