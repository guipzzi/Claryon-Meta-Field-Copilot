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
/**
 * @param tokenDoAgente devolve o JWT **atual** do agente, ou `null` se não houver
 *   sessão. É função e não string porque o token expira: o transporte precisa
 *   pedir um novo a cada renovação, e guardar uma cópia daria um canal que morre
 *   junto com o primeiro token.
 * @param privado liga `config.private` no `phx_join`. Deixado `false` de propósito
 *   até a política da migração `0012` ser exercitada pelo par headless — virar a
 *   chave antes disso é apostar o rádio numa política nunca testada.
 */
data class ConfigRealtime(
    val projetoUrl: String,
    val apiKey: String,
    val tokenDoAgente: suspend () -> String? = { null },
    val privado: Boolean = false,
)

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

        // **Fecha o anterior antes de abrir o novo.** O guard acima só pega
        // "mesmo grupo, já aberto"; TROCAR de grupo caía direto em `abrir()`, que
        // reatribui `socket` sem fechar — e o WebSocket antigo continuava vivo,
        // inscrito no tópico do grupo antigo. As consequências não são de
        // recurso, são operacionais: o agente que trocou para a guarnição 5
        // continuaria RECEBENDO a guarnição 3, e o `_eventos` misturaria as duas
        // sem nada no evento dizendo de qual canal veio.
        //
        // Só descobrível com dois grupos, que é justamente o que a seleção por
        // voz destrava — por isso o conserto vem antes dela, não depois.
        if (talkGroup != null && talkGroup != talkGroupId) {
            runCatching { socket?.close(1000, "troca de talk group") }
            socket = null
            aberto = false
        }

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
                    // O token é obtido em corrotina: `onOpen` não é contexto de
                    // suspensão e buscá-lo aqui bloquearia a linha do OkHttp.
                    escopo.launch {
                        val token = runCatching { config.tokenDoAgente() }.getOrNull()
                        if (socket !== ws || !aberto) return@launch
                        ws.send(ProtocoloRealtime.join(tg, ref.getAndIncrement(), token, config.privado))
                        launch { baterCoracao(ws) }
                        launch { renovarToken(ws, tg) }
                    }
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

    /**
     * Empurra um JWT novo antes de o atual expirar.
     *
     * A doc do Supabase: *"se um novo JWT nunca for recebido no canal, o cliente é
     * desconectado quando o JWT expira"*. Um rádio operacional que cai sozinho
     * depois de uma hora é pior que um rádio sem JWT, porque falha em campo.
     *
     * O prazo sai do `exp` do próprio token, não de um intervalo chutado: sessão
     * de turno é mais longa que qualquer palpite e o valor do projeto pode mudar
     * no painel sem ninguém avisar o aplicativo. Renova com [MARGEM_TOKEN_MS] de
     * antecedência, e nunca mais rápido que [RENOVACAO_MINIMA_MS] — token ilegível
     * ou já vencido não pode virar laço quente batendo no servidor.
     */
    private suspend fun renovarToken(ws: WebSocket, tg: String) {
        while (aberto && socket === ws) {
            val token = runCatching { config.tokenDoAgente() }.getOrNull()
            val esperaMs = expiracaoMs(token)
                ?.let { it - System.currentTimeMillis() - MARGEM_TOKEN_MS }
                ?.coerceAtLeast(RENOVACAO_MINIMA_MS)
                ?: RENOVACAO_SEM_EXP_MS
            delay(esperaMs)
            if (!aberto || socket !== ws) break
            val novo = runCatching { config.tokenDoAgente() }.getOrNull() ?: continue
            ws.send(ProtocoloRealtime.renovarToken(tg, novo, ref.getAndIncrement()))
            Log.i(TAG, "JWT do canal renovado")
        }
    }

    /**
     * `exp` do JWT em milissegundos, ou `null` se o token não for legível.
     *
     * Lê o payload sem validar assinatura — de propósito. Aqui não se decide
     * autorização nenhuma (quem valida é o servidor); decide-se **quando pedir
     * outro**. Validar exigiria a chave pública do projeto e não mudaria o
     * agendamento em nada.
     */
    private fun expiracaoMs(token: String?): Long? = runCatching {
        val corpo = token?.split(".")?.getOrNull(1) ?: return null
        val json = String(android.util.Base64.decode(corpo, android.util.Base64.URL_SAFE))
        org.json.JSONObject(json).getLong("exp") * 1_000L
    }.getOrNull()

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
         * Antecedência da renovação do JWT. Cinco minutos cobrem uma renovação
         * lenta de rede sem deixar o canal chegar perto do vencimento — e o
         * vencimento é fatal, não degradado: o servidor desconecta.
         */
        private const val MARGEM_TOKEN_MS = 5 * 60_000L

        /** Piso: token ilegível ou já vencido não pode virar laço quente. */
        private const val RENOVACAO_MINIMA_MS = 60_000L

        /**
         * Sem `exp` legível, renova a cada 10 min. Abaixo de qualquer expiração
         * plausível do Supabase (o padrão é 1 h) e barato: é um quadro de texto.
         */
        private const val RENOVACAO_SEM_EXP_MS = 10 * 60_000L

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
