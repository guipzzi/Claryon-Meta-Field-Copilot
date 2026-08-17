package com.claryon.net

import android.util.Log
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Credenciais do projeto Supabase. Carregadas em runtime, nunca versionadas. */
data class ConfigRealtime(val projetoUrl: String, val apiKey: String)

/**
 * [TransporteAoVivo] sobre o WebSocket do Supabase Realtime.
 *
 * **Socket quente:** [conectar] é chamado ao entrar em modo Ativo e o canal
 * permanece aberto. Zero handshake no caminho crítico — estabelecer conexão no
 * instante do toque somaria centenas de milissegundos onde não há folga.
 *
 * A camada frágil (formato de fio) vive em [ProtocoloRealtime] e a política de
 * reconexão em [PoliticaDeReconexao], ambas testadas. O que sobra aqui é
 * encanamento: abrir o socket, empurrar texto, reconectar.
 *
 * ✅ **Verificado contra projeto real** (2026-08-14, `TransporteRealtimeIntegracaoTest`):
 * dois clientes no mesmo talk group, socket aberto, e um quadro de 90 bytes de
 * payload atravessando **byte a byte** de um para o outro. Anúncio de fala também
 * atravessa, com a prioridade preservada.
 */
class TransporteRealtime(
    private val config: ConfigRealtime,
    private val escopo: CoroutineScope,
    private val cliente: OkHttpClient = clientePadrao(),
) : TransporteAoVivo {

    private val ref = AtomicInteger(1)

    /**
     * `DROP_OLDEST`: se o consumidor de áudio atrasar, o quadro velho é o que
     * deve morrer. Segurar quadros antigos aumentaria a latência exatamente onde
     * ela é o produto — e o buffer de jitter já lida com lacunas por PLC.
     */
    private val _eventos = MutableSharedFlow<EventoDeRede>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val reconexao = PoliticaDeReconexao()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var talkGroup: String? = null
    @Volatile private var aberto = false

    override fun conectado(): Boolean = aberto

    override fun eventos(): Flow<EventoDeRede> = _eventos.asSharedFlow()

    override suspend fun conectar(talkGroupId: String): Result<Unit> {
        if (aberto && talkGroup == talkGroupId) return Result.success(Unit)
        talkGroup = talkGroupId
        abrir()
        return Result.success(Unit)
    }

    private fun abrir() {
        val tg = talkGroup ?: return
        val url = "${config.projetoUrl.trimEnd('/')}/realtime/v1/websocket" +
            "?apikey=${config.apiKey}&vsn=1.0.0"

        socket = cliente.newWebSocket(
            Request.Builder().url(url.replaceFirst("http", "ws")).build(),
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ws = webSocket
                    aberto = true
                    reconexao.aoConectar()
                    ws.send(ProtocoloRealtime.join(tg, ref.getAndIncrement()))
                    escopo.launch { baterCoracao(ws) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Mensagem que não entendemos é descartada; uma malformada
                    // não pode derrubar o canal de voz inteiro.
                    // `interpretar` devolve LISTA: uma mensagem agrupada vira N
                    // eventos de quadro. Ver `ProtocoloRealtime.quadros`.
                    for (e in ProtocoloRealtime.interpretar(text)) _eventos.tryEmit(e)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    aberto = false
                    Log.w(TAG, "canal caiu: ${t.message}")
                    escopo.launch { reagendar() }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    aberto = false
                    escopo.launch { reagendar() }
                }
            },
        )
    }

    /**
     * Sem heartbeat o servidor derruba o canal por inatividade — e a queda só
     * apareceria no próximo toque no PTT, que é o pior momento possível.
     */
    private suspend fun baterCoracao(ws: WebSocket) {
        while (aberto && socket === ws) {
            delay(HEARTBEAT_MS)
            if (!aberto) break
            ws.send(ProtocoloRealtime.heartbeat(ref.getAndIncrement()))
        }
    }

    private suspend fun reagendar() {
        val espera = reconexao.proximoAtrasoMs()
        Log.i(TAG, "reconectando em ${espera}ms (tentativa ${reconexao.tentativasSeguidas})")
        delay(espera)
        if (!aberto) abrir()
    }

    /** Chamado pelo callback de conectividade do sistema: retoma na hora. */
    fun aoDetectarRedeDisponivel() {
        reconexao.aoDetectarRedeDisponivel()
        if (!aberto) abrir()
    }

    override suspend fun anunciar(anuncio: AnuncioDeFala): Result<Unit> =
        enviarTexto { tg -> ProtocoloRealtime.anuncio(tg, anuncio, ref.getAndIncrement()) }

    override suspend fun enviar(quadro: QuadroAudio): Result<Unit> =
        enviarTexto { tg -> ProtocoloRealtime.quadro(tg, quadro, ref.getAndIncrement()) }

    override suspend fun enviarGrupo(grupo: List<QuadroAudio>): Result<Unit> {
        if (grupo.isEmpty()) return Result.success(Unit)
        return enviarTexto { tg -> ProtocoloRealtime.quadros(tg, grupo, ref.getAndIncrement()) }
    }

    override suspend fun encerrar(transmissaoId: String): Result<Unit> =
        enviarTexto { tg -> ProtocoloRealtime.fim(tg, transmissaoId, ref.getAndIncrement()) }

    private inline fun enviarTexto(construir: (String) -> String): Result<Unit> {
        val ws = socket
        val tg = talkGroup
        if (ws == null || tg == null || !aberto) {
            return Result.failure(ClaryonError.Sync("net.desconectado", "canal fechado"))
        }
        // `send` devolve false quando a fila de saída estouraria. Reportar em vez
        // de bloquear: a sessão de PTT conta o não entregue e a captura segue.
        return if (ws.send(construir(tg))) {
            Result.success(Unit)
        } else {
            Result.failure(ClaryonError.Sync("net.fila_cheia", "socket sem espaço de saída"))
        }
    }

    override suspend fun desconectar() {
        aberto = false
        socket?.close(1000, "encerrado")
        socket = null
        talkGroup = null
    }

    companion object {
        private const val TAG = "ClaryonField"
        private const val HEARTBEAT_MS = 30_000L

        /**
         * `TCP_NODELAY` é o ponto: o algoritmo de Nagle agruparia quadros de
         * 20 ms para economizar cabeçalho e somaria dezenas de milissegundos —
         * exatamente o oposto do que este transporte existe para fazer.
         */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(NoDelaySocketFactory())
            .build()
    }
}

/** Fábrica de sockets com Nagle desligado. Ver [TransporteRealtime.clientePadrao]. */
private class NoDelaySocketFactory : javax.net.SocketFactory() {
    private val base = javax.net.SocketFactory.getDefault()
    private fun java.net.Socket.semNagle() = apply { tcpNoDelay = true }

    override fun createSocket(): java.net.Socket = base.createSocket().semNagle()
    override fun createSocket(host: String?, port: Int) = base.createSocket(host, port).semNagle()
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) =
        base.createSocket(host, port, localHost, localPort).semNagle()
    override fun createSocket(host: java.net.InetAddress?, port: Int) = base.createSocket(host, port).semNagle()
    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int,
    ) = base.createSocket(address, port, localAddress, localPort).semNagle()
}
