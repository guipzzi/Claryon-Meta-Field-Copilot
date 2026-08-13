package com.claryon.glasses

/**
 * Tipos de domínio que abstraem os conceitos do DAT. São NOSSOS tipos — não
 * reexportam símbolos do SDK — de modo que uma mudança de assinatura na preview
 * do toolkit fique contida na implementação de [GlassesFacade].
 */

/** Estado de registro dos óculos junto ao app Meta AI. */
enum class RegistrationStatus {
    UNKNOWN,
    AVAILABLE,      // registrável, porém não registrado (ou perdido para outro app)
    REGISTERING,
    REGISTERED,
    ERROR,
}

/** Estado da sessão do DAT. */
enum class SessionStatus {
    IDLE,
    STARTING,
    STARTED,
    STREAMING,
    STOPPED,
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

/** Foto capturada sob comando (HEIC/Bitmap na implementação real). */
data class PhotoData(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PhotoData && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}
