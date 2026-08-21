package com.claryon.glasses

/**
 * Tipos de domínio que abstraem os conceitos do DAT. São NOSSOS tipos — não
 * reexportam símbolos do SDK — de modo que uma mudança de assinatura na preview
 * do toolkit fique contida na implementação de [GlassesFacade].
 */

/**
 * Estado de registro dos óculos junto ao app Meta AI.
 * Espelha `RegistrationState` do DAT 0.9 (confirmado no sample oficial):
 * UNAVAILABLE, REGISTERING, REGISTERED, UNREGISTERING. `UNKNOWN` é o valor
 * inicial nosso antes da primeira emissão.
 */
enum class RegistrationStatus {
    UNKNOWN,
    UNAVAILABLE,
    REGISTERING,
    REGISTERED,
    UNREGISTERING,
}

/**
 * Estado da sessão do DAT. Espelha `DeviceSessionState` 0.9:
 * IDLE → STARTING → STARTED → PAUSED → STOPPING → STOPPED.
 */
enum class SessionStatus {
    IDLE,
    STARTING,
    STARTED,
    PAUSED,
    STOPPING,
    STOPPED,
}

/**
 * Estado do stream de câmera. Espelha `StreamState` 0.9:
 * STOPPED → STARTING → STARTED → STREAMING → STOPPING → STOPPED → CLOSED
 * (PAUSED quando o usuário dá tap na haste). Frames só chegam em STREAMING.
 */
enum class StreamStatus {
    STOPPED,
    STARTING,
    STARTED,
    STREAMING,
    PAUSED,
    STOPPING,
    CLOSED,
}

/**
 * Causa tipada vinda de `Stream.errorStream` — o fluxo que diz **por que** o
 * stream parou, separado do estado, que só diz **que** parou.
 *
 * Os nomes espelham `StreamError` do DAT 0.9 de propósito, como os enums acima,
 * para que o mapeamento continue sendo por nome e resista a acréscimos do SDK.
 *
 * **`StreamError` é um `enum`, não uma sealed class** — o que importa porque um
 * `when` sobre ele não precisa de `is`, e porque `description` é um método da
 * própria constante. Confirmado por `javap` no artefato `mwdat-camera-0.9.0`:
 *
 * ```
 * javap -cp classes.jar com.meta.wearable.dat.camera.types.StreamError
 * public final class ...StreamError extends java.lang.Enum<...StreamError>
 *     implements com.meta.wearable.dat.core.types.DatError {
 *   STREAM_ERROR; CRITICAL_STREAM_ERROR; HINGE_CLOSED; PERMISSIONS_DENIED;
 *   THERMAL_HOT; BATTERY_LOW; PEAK_POWER_LIMIT; TIMEOUT;
 *   public final java.lang.String getDescription();
 * }
 * ```
 *
 * [frase] é o que o agente **ouve**, no vocabulário dele e dentro do teto de 7
 * palavras da fala operacional — sustentado por teste, não por disciplina.
 * `HINGE_CLOSED` vira "óculos dobrados" porque é isso que aconteceu no mundo: o
 * agente guardou os óculos no bolso sem perceber que a câmera estava aberta.
 */
enum class ErroDeStream(val frase: String) {
    STREAM_ERROR("Câmera falhou. Tente de novo."),
    CRITICAL_STREAM_ERROR("Câmera parou. Reabra a sessão."),
    HINGE_CLOSED("Óculos dobrados. Abra as hastes."),
    PERMISSIONS_DENIED("Libere a câmera no Meta AI."),
    THERMAL_HOT("Óculos quentes. Câmera pausada."),
    BATTERY_LOW("Bateria dos óculos acabando."),
    PEAK_POWER_LIMIT("Óculos sem energia para transmitir."),
    TIMEOUT("Câmera não respondeu."),

    /** O SDK acrescentou um valor que esta versão não conhece. */
    DESCONHECIDO("Câmera falhou."),
    ;

    /** Código estável para telemetria e para o mapeamento erro → earcon. */
    val codigo: String get() = "glasses.stream_error.$name"
}

/**
 * Perfil de câmera pedido ao stream. Deliberadamente modesto: pedir resolução
 * menor reduz a compressão por frame e pode MELHORAR a qualidade visual efetiva
 * sob Bluetooth Classic.
 */
data class CameraProfile(
    val quality: Quality,
    val frameRate: Int,
) {
    enum class Quality { LOW, MEDIUM }

    companion object {
        /** OCR de placa: baixa resolução, FPS baixo. */
        val OCR = CameraProfile(Quality.LOW, frameRate = 7)

        /** Gravação de evidência: resolução média, FPS moderado. */
        val EVIDENCE = CameraProfile(Quality.MEDIUM, frameRate = 15)
    }
}

/**
 * Um frame de vídeo entregue pelo stream (payload preenchido na implementação).
 *
 * [comprimido] espelha `VideoFrame.isCompressed` do DAT, e **não é adorno**: quem
 * consome o payload precisa saber se está olhando para pixels ou para NAL units de
 * H.265. A informação existia no SDK e era descartada na tradução — quem lesse
 * `bytes` como imagem receberia bitstream sem nenhum erro aparecendo.
 *
 * Hoje ela é sempre `false` no nosso caminho, e isso foi **confirmado por `javap`**,
 * não presumido: `StreamConfiguration(VideoQuality, frameRate, compressVideo)` tem
 * o terceiro parâmetro com padrão `false` — no construtor sintético,
 * `iconst_0/istore_3` para o bit 4 da máscara —, e `toStreamConfiguration` só passa
 * os dois primeiros. Se alguém acrescentar o terceiro um dia, o consumidor de OCR
 * recusa o frame em vez de alimentar o reconhecedor com bitstream.
 */
data class Frame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val bytes: ByteArray,
    val comprimido: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Frame && timestampNanos == other.timestampNanos)

    override fun hashCode(): Int = timestampNanos.hashCode()
}

/** Informação leve do último frame — usada no painel de diagnóstico (M2). */
data class FrameInfo(
    val width: Int,
    val height: Int,
    val count: Long,
)

/** Foto capturada sob comando (HEIC/Bitmap na implementação real). */
data class PhotoData(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PhotoData && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}
