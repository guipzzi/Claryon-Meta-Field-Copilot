package com.claryon.glasses

import android.content.Context
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import android.net.Uri
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
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
    /**
     * Habilita o MDK com configuração explícita.
     *
     * `MockDeviceKitConfig(initiallyRegistered, initialPermissionsGranted)` é o
     * que permite exercitar dois cenários da tabela de armadilhas que, sem ele,
     * eram apenas teoria:
     *
     *  - **registro perdido** (`initiallyRegistered = false`) — em Dev Mode só um
     *    app de terceiros fica registrado por vez, então isso acontece de verdade
     *    quando o desenvolvedor abre outro app do DAT;
     *  - **permissão do DAT negada** (`initialPermissionsGranted = false`) — o
     *    agente que nega a câmera no diálogo da Meta AI.
     *
     * Assinatura confirmada por inspeção do artefato `mwdat-mockdevice-0.9.0`.
     */
    fun enableComConfig(registrado: Boolean, permissoesConcedidas: Boolean): Boolean {
        if (habilitado) return device != null
        mockDeviceKit.enable(
            MockDeviceKitConfig(
                initiallyRegistered = registrado,
                initialPermissionsGranted = permissoesConcedidas,
            ),
        )
        habilitado = true
        return parear() != null
    }

    /**
     * Pareia mais um Ray-Ban simulado. O MDK aceita **até três**.
     *
     * Existe para o cenário de guarnição em que o celular já viu mais de um
     * aparelho — troca de turno, óculos emprestado, dois pares na viatura. O
     * `AutoDeviceSelector` precisa escolher **um** de forma determinística, e não
     * abrir sessão com todos.
     */
    fun parear(): MockGlasses? {
        var novo: MockGlasses? = null
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META)
            .onSuccess { glasses ->
                glasses.powerOn()
                glasses.don()
                if (device == null) device = glasses
                novo = glasses
            }
            .onFailure { _, _ -> novo = null }
        return novo
    }

    /** Quantos aparelhos simulados estão pareados agora. */
    fun pareados(): Int = mockDeviceKit.pairedDevices.size

    /** Remove um aparelho — o agente que desliga os óculos e guarda. */
    fun desemparelhar(alvo: MockGlasses? = null): Boolean {
        val d = alvo ?: device ?: return false
        mockDeviceKit.unpairDevice(d)
        if (d === device) device = null
        return true
    }

    /**
     * Injeta uma imagem conhecida como foto capturada.
     *
     * É o que torna possível verificar a leitura de placa **ponta a ponta pela
     * câmera do DAT**, e não só chamando o OCR com um bitmap local: a imagem
     * entra pelo mesmo caminho que entraria vinda dos óculos.
     */
    fun definirFotoCapturada(uri: Uri): Boolean {
        val d = device ?: return false
        d.services.camera.setCapturedImage(uri)
        return true
    }

    /** Define o feed de vídeo a partir de um arquivo, em vez da câmera do celular. */
    fun definirFeedDeVideo(uri: Uri): Boolean {
        val d = device ?: return false
        d.services.camera.setCameraFeed(uri)
        return true
    }

    /**
     * Nega ou concede a permissão de câmera **do DAT** — a que o agente responde
     * no diálogo da Meta AI, distinta da permissão de runtime do Android.
     */
    fun permissaoDeCamera(concedida: Boolean) {
        mockDeviceKit.permissions.set(
            Permission.CAMERA,
            if (concedida) PermissionStatus.Granted else PermissionStatus.Denied,
        )
    }

    /** O que o diálogo de permissão do DAT vai responder na próxima vez. */
    fun respostaDoProximoPedidoDePermissao(concedida: Boolean) {
        mockDeviceKit.permissions.setRequestResult(
            Permission.CAMERA,
            if (concedida) PermissionStatus.Granted else PermissionStatus.Denied,
        )
    }

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
