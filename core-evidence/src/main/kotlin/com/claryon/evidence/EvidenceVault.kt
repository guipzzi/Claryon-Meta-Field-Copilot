package com.claryon.evidence

import com.claryon.common.Result

/**
 * Cofre de evidências com cadeia de custódia auditável.
 *
 * Princípios que a implementação honra:
 *  - repouso: EncryptedFile + chave no Android Keystore (nem o app lê sem desbloqueio);
 *  - integridade: cada segmento com SHA-256 encadeado ao anterior;
 *  - minimização: vídeo não solicitado nunca é persistido; frame de OCR é
 *    descartado após a inferência — só o texto validado sobrevive;
 *  - a fala de terceiros é preservada como áudio bruto para fins probatórios,
 *    mas NÃO é transcrita, classificada ou indexada.
 *
 * ## Por que [append] não devolve mais um hash a cada chamada
 *
 * A versão anterior selava **um arquivo cifrado por quadro de 20 ms** e reescrevia
 * o manifesto inteiro a cada um deles. Um minuto de fala custava 3.000 arquivos e
 * ~607 MB **escritos** para guardar 2 MB de áudio (a conta está em
 * [EncryptedEvidenceVault]). Agora o cofre acumula uma **janela** em RAM e sela um
 * segmento por janela; o retorno diz qual dos dois aconteceu, em vez de fingir um
 * hash que ainda não existe.
 */
interface EvidenceVault {

    /** Abre uma gravação para uma ocorrência e devolve um handle opaco. */
    suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle>

    /**
     * Entrega bytes de áudio ao cofre.
     *
     * O retorno é honesto sobre o que aconteceu: [Anexado.NaJanela] significa
     * "aceito, ainda em RAM, sem hash"; [Anexado.JanelaSelada] significa "gravado
     * e encadeado". Devolver um [ChunkHash] em toda chamada exigiria inventar um
     * — e um hash inventado numa cadeia de custódia é o defeito que este módulo
     * existe para impedir.
     */
    suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<Anexado>

    /** Sela a janela pendente e fecha a gravação, emitindo o manifesto. */
    suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest>
}

/** O que o cofre fez com os bytes entregues a [EvidenceVault.append]. */
sealed interface Anexado {

    /** Aceito na janela corrente, ainda em RAM. Sem hash porque ainda não há. */
    data class NaJanela(val bytesAcumulados: Int) : Anexado

    /** A janela fechou: segmento cifrado no disco, hash encadeado no manifesto. */
    data class JanelaSelada(val hash: ChunkHash) : Anexado
}

/**
 * Contexto mínimo de uma ocorrência.
 *
 * [sampleRateHz] e [formato] entram no manifesto porque **PCM sem taxa declarada
 * é inaudível**: um perito que receba os segmentos precisa saber que são 16 kHz,
 * mono, 16 bits little-endian. Antes essa informação não estava em lugar nenhum —
 * vivia só no `AudioRecord` do aparelho que gravou.
 */
data class OccurrenceContext(
    val agentId: String,
    val unitId: String,
    val startedAtEpochMillis: Long,
    val sampleRateHz: Int = 16_000,
    val formato: String = FORMATO_PCM_S16LE_MONO,
) {
    companion object {
        const val FORMATO_PCM_S16LE_MONO = "pcm_s16le_mono"
    }
}

/** Handle opaco de uma gravação em andamento. */
@JvmInline
value class RecordingHandle(val id: String)

/**
 * Hash SHA-256 de um segmento, encadeado ao anterior.
 *
 * [bytes] é o tamanho do **texto claro** daquele segmento. Está aqui porque a
 * janela é configurável e a última é sempre parcial: sem o tamanho, remontar o
 * áudio a partir dos segmentos exige descriptografar tudo para descobrir onde
 * cada um termina.
 */
data class ChunkHash(
    val sequence: Int,
    val sha256Hex: String,
    val previousSha256Hex: String?,
    val bytes: Int = 0,
)

/** Registro de um segmento apagado por política de retenção. Nunca silencioso. */
data class Purga(
    val sequence: Int,
    val epochMillis: Long,
    val motivo: String,
)

/** Manifesto final: cadeia de hashes que comprova integridade da custódia. */
data class CustodyManifest(
    val handle: RecordingHandle,
    val chain: List<ChunkHash>,
    val finalizedAtEpochMillis: Long,
    val versao: Int = Manifesto.VERSAO_ATUAL,
    val sampleRateHz: Int = 16_000,
    val formato: String = OccurrenceContext.FORMATO_PCM_S16LE_MONO,
    val janelaMs: Int = 0,
    val purgados: List<Purga> = emptyList(),
    /** `null` = encerramento normal. Texto = por que parou (ex.: `DISCO_CHEIO`). */
    val motivoDoFim: String? = null,
) {
    /** Bytes de áudio em claro sob custódia, já descontado o que foi purgado. */
    val bytesRetidos: Long
        get() {
            val purgadas = purgados.map { it.sequence }.toSet()
            return chain.filter { it.sequence !in purgadas }.sumOf { it.bytes.toLong() }
        }
}

/**
 * Veredito da conferência de integridade.
 *
 * [ExpurgadaPorPolitica] existe porque retenção e cadeia de custódia colidem: sem
 * um estado próprio, todo segmento apagado por política seria reportado como
 * **adulterado** — a política de retenção do produto passaria a forjar, sozinha,
 * um sinal de fraude em toda gravação antiga.
 */
sealed interface Integridade {

    /** Todos os segmentos presentes conferem com a cadeia declarada. */
    data object Integra : Integridade

    /**
     * Os segmentos presentes conferem; [sequencias] foram apagadas por política e
     * a integridade **delas** é, por construção, indemonstrável.
     */
    data class ExpurgadaPorPolitica(val sequencias: List<Int>) : Integridade

    /** Primeiro segmento em que a descriptografia falhou ou o hash divergiu. */
    data class Quebrada(val sequencia: Int) : Integridade

    /**
     * Há segmento no disco além do último registrado no manifesto — resultado
     * esperado de um processo morto entre gravar o arquivo e anexar a linha.
     * Não é adulteração: é a ordem de escrita funcionando (ver
     * [EncryptedEvidenceVault]).
     */
    data class SegmentoNaoRegistrado(val sequencia: Int) : Integridade
}

/**
 * Quanto áudio o cofre mantém antes de apagar o mais antigo.
 *
 * **O padrão é [TudoRetido].** Apagar prova é decisão jurídica, não default de
 * engenharia: um cofre que descarta evidência sem alguém ter escolhido isso é
 * pior que um cofre que enche o disco, porque o disco cheio é audível e a prova
 * apagada não é.
 */
sealed interface PoliticaDeRetencao {

    /** Nada é apagado. O disco é o limite, e ele avisa antes de acabar. */
    data object TudoRetido : PoliticaDeRetencao

    /**
     * Mantém apenas os últimos [minutos] de áudio; o restante da ocorrência
     * sobrevive como manifesto (hashes, tamanhos, horários) e como o que a camada
     * de chat tiver transcrito da fala **do próprio agente**.
     *
     * @param motivo vai para o manifesto em toda purga. Um expurgo sem motivo
     *   registrado é indistinguível de um apagamento por terceiro.
     */
    data class UltimosMinutos(
        val minutos: Int,
        val motivo: String = "RETENCAO_CONFIGURADA",
    ) : PoliticaDeRetencao {
        init {
            require(minutos > 0) { "retenção em minutos precisa ser positiva" }
        }
    }
}
