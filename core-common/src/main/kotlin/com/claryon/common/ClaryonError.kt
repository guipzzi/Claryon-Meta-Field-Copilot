package com.claryon.common

/**
 * Erro de domínio, tipado e auditável.
 *
 * O [code] é estável e legível por máquina — usado tanto na telemetria local
 * quanto no mapeamento determinístico de falha para o earcon correspondente
 * (num sistema sem display, "falha nunca é silêncio": todo erro tem som).
 *
 * As categorias abaixo cobrem as fronteiras de risco previstas na arquitetura;
 * novas subclasses devem manter um [code] curto e único.
 */
sealed class ClaryonError(val code: String, val message: String) {

    /** Falha ao registrar/parear os óculos ou ao subir a sessão do DAT. */
    class Glasses(code: String, message: String) : ClaryonError(code, message)

    /** Roteamento HFP/SCO, AudioRecord/AudioTrack. */
    class Audio(code: String, message: String) : ClaryonError(code, message)

    /** Wake word, VAD, STT ou TTS. */
    class Voice(code: String, message: String) : ClaryonError(code, message)

    /** Cofre de evidências, cadeia de custódia, Keystore. */
    class Evidence(code: String, message: String) : ClaryonError(code, message)

    /** Supabase, WhatsApp, fila offline. */
    class Sync(code: String, message: String) : ClaryonError(code, message)

    /** Erro não classificado — deve ser raro e investigado. */
    class Unexpected(code: String, message: String) : ClaryonError(code, message)

    override fun toString(): String = "$code: $message"
}
