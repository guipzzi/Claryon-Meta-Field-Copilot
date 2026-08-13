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

/** Um frame de vídeo entregue pelo stream (payload preenchido na implementação). */
data class Frame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val bytes: ByteArray,
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
