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

    /**
     * Codifica um quadro PCM 16-bit mono e devolve **zero ou mais** pacotes.
     *
     * A lista não é generalidade gratuita: o codificador é um **pipeline**, não
     * uma função. Ele consome alguns quadros antes de emitir o primeiro pacote
     * (aquecimento) e depois pode emitir mais de um numa mesma chamada. Um
     * contrato de 1-entra-1-sai obrigaria a inventar um pacote vazio no
     * aquecimento — que a camada de perda interpretaria como quadro perdido.
     *
     * Como efeito colateral útil, este formato acomoda o agrupamento de quadros
     * (3 × 20 ms numa mensagem) sem mudar a assinatura de novo.
     */
    suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>>

    /**
     * Constrói o codificador **antes** de ele ser necessário.
     *
     * Medido no emulador: a meta "toque → primeiro quadro ≤ 120 ms" batia em
     * 168–221 ms, e o custo não era do rádio nem da rede — era
     * `MediaCodec.createEncoderByType` + `configure` + `start`, que aconteciam na
     * PRIMEIRA chamada de [codificar], ou seja **dentro do caminho crítico do
     * PTT**, com o dedo já no botão.
     *
     * Aquecer ao entrar em modo ativo tira esse custo de onde não há folga e o
     * põe onde há: o rádio abre quando a tela da guarnição aparece, segundos
     * antes do primeiro toque.
     *
     * Idempotente e sem efeito observável além do tempo — quem não implementa
     * segue correto, só continua pagando o custo no primeiro quadro.
     */
    suspend fun preparar(): Result<Unit> = Result.success(Unit)

    /**
     * Decodifica um quadro.
     *
     * @param payload `null` sinaliza **perda**: o decodificador deve gerar o
     *   quadro por interpolação (PLC), não silêncio. É o que transforma um pacote
     *   perdido em voz levemente degradada em vez de um corte seco.
     */
    suspend fun decodificar(payload: ByteArray?): Result<ShortArray>

    /**
     * Taxa em que o decodificador **devolve** PCM, que **não** é a taxa de
     * entrada.
     *
     * Medido no emulador: entram 160 amostras a 8 kHz por quadro de 20 ms e saem
     * **480** — ou seja, 24 kHz. O Opus trabalha internamente a 48 kHz e o
     * decodificador do Android reamostra na saída.
     *
     * Existe no contrato porque o receptor **não pode adivinhar**: configurar o
     * `AudioTrack` com a taxa de entrada faria a voz sair três vezes mais grave e
     * três vezes mais lenta — um defeito que soa como problema de microfone, não
     * de configuração, e que mandaria a equipe procurar no lugar errado.
     *
     * `0` enquanto nada foi decodificado ainda.
     */
    val taxaDeSaidaHz: Int

    fun liberar()
}
