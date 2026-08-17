package com.claryon.net

import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow

/**
 * Prioridade de uma transmissão tática. Governa raio de alcance no servidor e
 * comportamento no destinatário.
 *
 * Definida aqui, e não importada de `core-agent`, porque os `core-*` não dependem
 * uns dos outros — e porque prioridade *de transmissão* (quem recebe, em que raio)
 * é conceito distinto de prioridade *de reprodução* (o que interrompe o quê).
 */
enum class PrioridadeTransmissao {
    /** Emergência: raio amplo, **toma o canal** de quem estiver falando. */
    P1_EMERGENCIA,

    /** Apoio: raio médio; entra na fila do destinatário. */
    P2_APOIO,

    /** Informativo: só o talk group; suprimido em Modo Tático. */
    P3_INFORMATIVO,
}

/**
 * Um quadro de áudio codificado, pronto para a rede.
 *
 * **Três quadros viajam numa mensagem** — ver [AgrupadorDeQuadros]. Antes era um
 * por mensagem: ~50 mensagens/s por locutor, cada uma com ~274 B de envelope
 * JSON/base64 do Realtime para ~30 B de Opus útil (~11% de aproveitamento).
 *
 * O agrupamento **não muda o significado de [sequencia]**, e é isso que o tornou
 * possível sem tocar no receptor: cada quadro mantém a própria, a mensagem só os
 * carrega juntos, e `ProtocoloRealtime.interpretar` explode o grupo de volta em N
 * eventos. `BufferDeJitter` continua raciocinando em quadros de 20 ms.
 *
 * @param sequencia contador monotônico **por transmissão**, a partir de 0. É o que
 *   permite ao receptor detectar perda e ordenar — sem ele, o buffer de jitter não
 *   tem como saber que faltou algo, e um quadro perdido viraria um corte seco.
 * @param ultimo marca o fim da fala. Chega junto do último quadro em vez de numa
 *   mensagem separada, que poderia se perder e deixar o receptor esperando para sempre.
 */
class QuadroAudio(
    val transmissaoId: String,
    val sequencia: Int,
    val capturadoEmMs: Long,
    val payload: ByteArray,
    val ultimo: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is QuadroAudio &&
            transmissaoId == other.transmissaoId &&
            sequencia == other.sequencia &&
            capturadoEmMs == other.capturadoEmMs &&
            ultimo == other.ultimo &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var h = transmissaoId.hashCode()
        h = 31 * h + sequencia
        h = 31 * h + capturadoEmMs.hashCode()
        h = 31 * h + ultimo.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    override fun toString(): String =
        "QuadroAudio($transmissaoId #$sequencia, ${payload.size}B${if (ultimo) ", último" else ""})"
}

/**
 * Anúncio de fala — sai **antes** do primeiro quadro.
 *
 * Serve para o receptor aquecer o `AudioTrack` e conferir a rota de saída
 * enquanto o primeiro quadro ainda está sendo codificado. Esconde 50–100 ms e é
 * a razão de o receptor ouvir um clique de canal aberto antes da voz — exatamente
 * como num rádio.
 */
data class AnuncioDeFala(
    val transmissaoId: String,
    val autorIndicativo: String,
    val prioridade: PrioridadeTransmissao,
)

/** Eventos que chegam do transporte, na ordem em que a rede os entrega. */
sealed interface EventoDeRede {
    data class Anuncio(val anuncio: AnuncioDeFala) : EventoDeRede
    data class Quadro(val quadro: QuadroAudio) : EventoDeRede

    /**
     * O emissor soltou o PTT ou o canal expirou. Distinto do quadro `ultimo`:
     * cobre o caso em que a fala foi interrompida sem último quadro (rede caiu,
     * emissor morreu). Sem isto, o receptor esperaria indefinidamente.
     */
    data class FimDeTransmissao(val transmissaoId: String) : EventoDeRede
}

/**
 * Transporte ao vivo do rádio tático.
 *
 * O contrato assume **socket quente**: `conectar` é chamado ao entrar em modo
 * Ativo e o canal permanece aberto. Zero handshake no caminho crítico —
 * estabelecer conexão no instante do toque somaria centenas de milissegundos
 * justamente onde não há folga.
 */
interface TransporteAoVivo {

    /** Abre o canal do talk group e mantém aberto. Idempotente. */
    suspend fun conectar(talkGroupId: String): Result<Unit>

    /** Avisa que a fala vai começar. Ver [AnuncioDeFala]. */
    suspend fun anunciar(anuncio: AnuncioDeFala): Result<Unit>

    /** Empurra um quadro. Falha aqui **não** interrompe a captura. */
    suspend fun enviar(quadro: QuadroAudio): Result<Unit>

    /**
     * Empurra vários quadros numa mensagem só — ver [AgrupadorDeQuadros].
     *
     * Padrão que cai em [enviar] um a um: um transporte que não sabe agrupar
     * continua correto, só não colhe a economia de envelope.
     */
    suspend fun enviarGrupo(grupo: List<QuadroAudio>): Result<Unit> {
        var falhou = false
        for (q in grupo) if (enviar(q) !is Result.Success) falhou = true
        return if (falhou) Result.failure(com.claryon.common.ClaryonError.Sync("net.grupo", "quadro não entregue")) else Result.success(Unit)
    }

    /** Marca o fim da transmissão e libera o canal. */
    suspend fun encerrar(transmissaoId: String): Result<Unit>

    /** Fluxo de eventos recebidos do talk group. */
    fun eventos(): Flow<EventoDeRede>

    /** `true` enquanto o socket está de fato aberto (para o indicador de degradação). */
    fun conectado(): Boolean

    suspend fun desconectar()
}
