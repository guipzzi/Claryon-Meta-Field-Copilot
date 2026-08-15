package com.claryon.glasses

import android.content.Context
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

/**
 * Bootstrap do Mock Device Kit para desenvolvimento **sem hardware** (M2).
 *
 * `enable()` simula a pilha inteira do SDK (inclusive registro e permissões) e,
 * por padrão, leva o registro a REGISTERED — por isso não é preciso o app Meta
 * AI. Depois pareamos um Ray-Ban simulado, ligamos, "vestimos" (don) e usamos a
 * **câmera do próprio celular** como feed, para ver frames reais no emulador.
 *
 * ⚠️ Uso restrito a builds DEBUG (o chamador deve gatear por `BuildConfig.DEBUG`).
 * O artefato `mwdat-mockdevice` ainda é `implementation`; mover para
 * `debugImplementation` + `src/debug` é um TODO de compliance (ver DECISIONS).
 *
 * Assinaturas confirmadas no sample oficial `CameraAccess`
 * (`MockDeviceKitViewModel`): `getInstance` → `enable()` → `pairGlasses(...)`
 * (DatResult), `device.powerOn()/don()`, `device.services.camera.setCameraFeed(CameraFacing)`.
 */
class MockDeviceController(context: Context) {

    private val mockDeviceKit = MockDeviceKit.getInstance(context.applicationContext)
    private var device: MockGlasses? = null

    /**
     * `enable()` chamado duas vezes sem `disable()` no meio **aborta o processo**
     * nativamente (`MediaCodec CHECK_EQ(mState, UNINITIALIZED)` — o codec do feed
     * de vídeo é reinicializado). Como `MockDeviceKit.getInstance` é um singleton
     * de processo, este guarda protege tanto o duplo toque no painel quanto dois
     * testes instrumentados que compartilham o processo.
     */
    private var habilitado = false

    /**
     * Habilita o MDK, pareia um Ray-Ban simulado, liga, veste e aponta a câmera
     * do celular como feed. Retorna `true` se o pareamento funcionou.
     *
     * Idempotente: se já estiver habilitado, apenas reporta o pareamento vigente.
     */
    fun enableWithPhoneCameraFeed(): Boolean {
        if (habilitado) return device != null
        mockDeviceKit.enable()
        habilitado = true
        var paired = false
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META)
            .onSuccess { glasses ->
                device = glasses
                glasses.powerOn()
                glasses.don() // "vestir" — o streaming exige o dispositivo ligado e vestido
                // Câmera do celular como fonte simulada (feed ao vivo, sem arquivo).
                runCatching {
                    CameraFacing.entries.firstOrNull()?.let { facing ->
                        glasses.services.camera.setCameraFeed(facing)
                    }
                }
                paired = true
            }
            .onFailure { _, _ -> paired = false }
        return paired
    }

    /**
     * Simula um toque capacitivo na haste. Assinatura confirmada por **inspeção
     * do artefato** (`javap` sobre `mwdat-mockdevice-0.9.0.aar`):
     * `MockGlassesServices.getCaptouch(): MockCaptouchKit`, com `tap()` e
     * `tapAndHold()` — a doc oficial descreve o comportamento mas não publica a
     * assinatura Android.
     *
     * Existe para responder **empiricamente** se o toque serve como gatilho de
     * PTT: a doc afirma que, com stream ativo, `tap` alterna pausa/retomada e
     * `tapAndHold` encerra a sessão. Ver `PttTriggerTest`.
     */
    fun tap(): Boolean {
        val d = device ?: return false
        d.services.captouch.tap()
        return true
    }

    /** Toque longo simulado. Ver [tap]. */
    fun tapAndHold(): Boolean {
        val d = device ?: return false
        d.services.captouch.tapAndHold()
        return true
    }

    /**
     * Simula o agente **tirando os óculos**. Assinatura confirmada por inspeção
     * do artefato: `MockDevice.doff()`.
     *
     * É o cenário de campo mais banal que existe — e um dos que mais quebra
     * produto: acontece no meio de uma transmissão, sem aviso.
     */
    fun tirarDoRosto(): Boolean = device?.let { it.doff(); true } ?: false

    /** Recolocar. */
    fun vestir(): Boolean = device?.let { it.don(); true } ?: false

    /** Simula **dobrar as hastes** — o gesto de guardar os óculos no bolso. */
    fun dobrar(): Boolean = device?.let { it.fold(); true } ?: false

    fun desdobrar(): Boolean = device?.let { it.unfold(); true } ?: false

    /** Simula a **bateria acabando** no meio da operação. */
    fun desligar(): Boolean = device?.let { it.powerOff(); true } ?: false

    /** Restaura a pilha real do SDK. Idempotente, como [enableWithPhoneCameraFeed]. */
    fun disable() {
        if (!habilitado) return
        device = null
        habilitado = false
        mockDeviceKit.disable()
    }
}
