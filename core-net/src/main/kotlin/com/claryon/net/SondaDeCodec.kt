package com.claryon.net

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** O que o aparelho oferece para um MIME de áudio. */
data class CapacidadeDeCodec(
    val mime: String,
    val encoders: List<String>,
    val decoders: List<String>,
) {
    val temEncoder: Boolean get() = encoders.isNotEmpty()
    val temDecoder: Boolean get() = decoders.isNotEmpty()

    override fun toString(): String = buildString {
        append(mime).append(" · encoder=")
        append(if (temEncoder) encoders.joinToString() else "AUSENTE")
        append(" · decoder=")
        append(if (temDecoder) decoders.joinToString() else "AUSENTE")
    }
}

/**
 * **V2 — o encoder Opus existe neste aparelho?**
 *
 * O decodificador Opus é comum; o **encoder não é garantido**. Se faltar, o
 * caminho é libopus via NDK — a toolchain já está montada do whisper.cpp, então o
 * custo marginal é baixo. O que não pode acontecer é descobrir isso em setembro:
 * a mesma pergunta custa meia hora agora e dois dias depois.
 *
 * A sonda enumera o `MediaCodecList` e devolve o que há. **Não é conclusiva no
 * emulador**: a lista de codecs de um emulador é a do sistema convidado, e difere
 * de aparelho real. Vale como sinal; a resposta que conta vem do celular
 * (ver `docs/VERIFICACOES_COM_HARDWARE.md`).
 */
object SondaDeCodec {

    const val MIME_OPUS = "audio/opus"
    const val MIME_AMR_NB = "audio/3gpp"

    fun capacidade(mime: String): CapacidadeDeCodec {
        val lista = MediaCodecList(MediaCodecList.ALL_CODECS)
        val encoders = ArrayList<String>()
        val decoders = ArrayList<String>()

        for (info in lista.codecInfos) {
            val suporta = info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            if (!suporta) continue
            // Codecs de software genéricos entram na lista; o nome ajuda a
            // distinguir OMX/c2 de hardware na leitura do relatório.
            if (info.isEncoder) encoders.add(info.name) else decoders.add(info.name)
        }
        return CapacidadeDeCodec(mime, encoders, decoders)
    }

    /** Resposta direta do V2. */
    fun encoderOpusDisponivel(): Boolean = capacidade(MIME_OPUS).temEncoder

    /**
     * Relatório legível para o diário e para o log — é o artefato que fica
     * registrado quando a sonda roda no aparelho real.
     */
    fun relatorio(): String = buildString {
        appendLine("Sonda de codec (V2)")
        appendLine("  ${capacidade(MIME_OPUS)}")
        appendLine("  ${capacidade(MIME_AMR_NB)}")
        val opus = capacidade(MIME_OPUS)
        appendLine(
            if (opus.temEncoder) {
                "  → Caminho: MediaCodec (sem dependência nativa nova)."
            } else {
                "  → Caminho: libopus via NDK — encoder Opus AUSENTE no MediaCodec."
            },
        )
    }

    /** Detalhe de perfis suportados, para diagnóstico mais fundo se necessário. */
    fun detalhar(mime: String): String = buildString {
        val lista = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in lista.codecInfos) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            appendLine("${info.name} (encoder=${info.isEncoder})")
            runCatching {
                val cap: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
                cap.audioCapabilities?.let { a ->
                    appendLine("  taxas: ${a.supportedSampleRates?.joinToString() ?: a.supportedSampleRateRanges.joinToString()}")
                    appendLine("  bitrate: ${a.bitrateRange}")
                    appendLine("  canais: ${a.maxInputChannelCount}")
                }
            }
        }
    }
}
