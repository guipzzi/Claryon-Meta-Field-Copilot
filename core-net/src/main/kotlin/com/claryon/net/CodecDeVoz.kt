package com.claryon.net

import com.claryon.common.Result

/**
 * Configuração do codec de voz do rádio tático.
 *
 * Opus, e não AMR-NB, por um motivo que decide a qualidade em campo: **FEC**. Em
 * rede móvel a perda de pacotes é regra, não exceção. O FEC embute uma versão de
 * baixa qualidade do quadro anterior dentro do quadro atual — perdeu um pacote, o
 * seguinte reconstrói, **sem retransmissão e sem latência adicional**. Numa 4G
 * urbana congestionada, é a diferença entre voz inteligível e voz picotada.
 *
 * @param quadroMs 20 ms é o padrão; 10 ms corta ~10–15 ms de latência ao custo de
 *   ~15% mais cabeçalho, e só compensa combinado com agrupamento menor.
 * @param complexidade 3–5 (baixa) em vez de 10: economiza CPU e alguns
 *   milissegundos, com diferença imperceptível em banda estreita. Menos CPU
 *   também é menos calor — e o freio térmico demora mais a atuar.
 */
data class ConfigOpus(
    val sampleRateHz: Int = 8_000,
    val bitrate: Int = 12_000,
    val quadroMs: Int = 20,
    val complexidade: Int = 4,
    val fecAtivado: Boolean = true,
    val perdaEsperadaPct: Int = 10,
)

/**
 * Codec de voz. Duas implementações previstas, e a escolha depende de medição em
 * aparelho real (ver [SondaDeCodec]):
 *
 *  - `MediaCodec` com MIME `audio/opus` — sem dependência nativa nova;
 *  - **libopus via NDK** — controle fino de FEC e DTX. A toolchain (NDK 27 +
 *    CMake) já está montada do whisper.cpp, então o custo marginal é baixo.
 */
interface CodecDeVoz {

    /** Codifica um quadro PCM 16-bit mono. Tamanho do quadro fixado em [ConfigOpus]. */
    suspend fun codificar(pcm: ShortArray): Result<ByteArray>

    /**
     * Decodifica um quadro.
     *
     * @param payload `null` sinaliza **perda**: o decodificador deve gerar o
     *   quadro por interpolação (PLC), não silêncio. É o que transforma um pacote
     *   perdido em voz levemente degradada em vez de um corte seco.
     */
    suspend fun decodificar(payload: ByteArray?): Result<ShortArray>

    fun liberar()
}
