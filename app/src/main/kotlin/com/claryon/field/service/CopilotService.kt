package com.claryon.field.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import com.claryon.agent.ThermalGovernor
import com.claryon.agent.TipoServico
import com.claryon.field.local.ColetorDePosicao
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.net.PublicadorDePosicao
import com.claryon.net.PublicadorDePosicaoSupabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.claryon.field.MainActivity
import com.claryon.field.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Serviço de primeiro plano do pipeline contínuo (sessão com os óculos, captura
 * HFP e — em ocorrência — câmera).
 *
 * Armadilhas endereçadas (docs/PADROES_DE_ENGENHARIA.md):
 *  - `foregroundServiceType` declarado **no manifest e em `startForeground()`**
 *    (Android 14+ derruba o serviço se divergirem);
 *  - o tipo é **derivado do modo** por [PowerPolicy.tiposDeServico] — em Standby
 *    o serviço não pede microfone nem câmera;
 *  - **iniciar sempre de tela visível** ([iniciar] é chamado da UI): iniciar FGS
 *    em background lança `ForegroundServiceStartNotAllowedException`;
 *  - trocar de modo **atualiza** o serviço em vez de recriá-lo.
 */
class CopilotService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Escopo do serviço, não do ViewModel.
     *
     * A coleta de posição tem de sobreviver à tela: o agente fecha o app, guarda
     * o celular no bolso, e a guarnição continua vendo onde ele está. Um escopo
     * de ViewModel morre com a composição, que é exatamente o oposto do
     * comportamento de compartilhamento contínuo.
     */
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var coletor: ColetorDePosicao? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        coletor = ColetorDePosicao(
            context = this,
            escopo = escopo,
            publicar = { lat, lon, precisao, velocidade ->
                escritorDePosicao().publicar(lat, lon, precisao, velocidade)
            },
        )
        // Antes de o coletor começar: sem turno, toda publicação é recusada.
        abrirTurno()
    }

    /**
     * O escritor de posição, construído **aqui**, onde a escrita acontece.
     *
     * Preguiçoso e memoizado: o serviço pode ser recriado pelo sistema
     * (`START_STICKY`) sem que nenhuma tela tenha rodado, e nesse caminho não há
     * quem injete nada. Antes disso ele nascia coletando o GPS e descartando,
     * porque `publicador` era nulo — o pior desperdício possível, já que o rádio
     * acorda e o dado morre no caminho.
     */
    /**
     * Abre o turno antes de a primeira posição subir.
     *
     * Sem turno aberto o servidor recusa `publicar_posicao` com `42501` (`0019`),
     * e o coletor acordaria o GPS para nada. É idempotente do lado do servidor, o
     * que torna seguro chamar a cada subida do serviço.
     */
    private fun abrirTurno() {
        escopo.launch {
            val ok = runCatching { escritorDePosicao().iniciarTurno() }.getOrDefault(false)
            if (!ok) Log.w(TAG, "turno NÃO abriu — a posição não vai subir até abrir")
        }
    }

    private fun escritorDePosicao(): PublicadorDePosicao =
        publicador ?: escritorProprio ?: PublicadorDePosicaoSupabase(
            config = SessaoDoAgente.config,
            tokenDeSessao = { SessaoDoAgente.tokenValido(applicationContext) },
        ).also { escritorProprio = it }

    @Volatile
    private var escritorProprio: PublicadorDePosicao? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // `intent == null` significa **recriação pelo sistema** (START_STICKY após
        // morte por memória), não pedido do usuário. Voltar em ATIVO aqui
        // reabriria o microfone sem que ninguém tivesse pedido — regressão de
        // privacidade e de energia, e ainda com o app em background (risco de
        // ForegroundServiceStartNotAllowedException). O certo é encerrar e deixar
        // o operador decidir; o modo é uma escolha explícita, não um padrão.
        if (intent == null) {
            Log.w(TAG, "Serviço recriado pelo sistema sem intent — encerrando (não reabre o microfone)")
            // `startForeground` ANTES de `stopSelf`, e não é cerimônia: quando o
            // sistema entrega um `onStartCommand` originado de
            // `startForegroundService`, ele exige a notificação dentro de ~5 s ou
            // mata o processo com `ForegroundServiceDidNotStartInTimeException`.
            // Sair sem promover é um caminho de encerramento que derruba o app —
            // e derruba justamente na recriação, quando ninguém está olhando.
            entrarEmPrimeiroPlano(ModoOperacao.STANDBY)
            stopSelf()
            return START_NOT_STICKY
        }

        val modo = intent.getStringExtra(EXTRA_MODO)
            ?.let { runCatching { ModoOperacao.valueOf(it) }.getOrNull() }
            ?: ModoOperacao.ATIVO

        if (modo == ModoOperacao.STANDBY && intent.action == ACAO_PARAR) {
            // Mesma razão do bloco acima: promover e só então encerrar.
            entrarEmPrimeiroPlano(ModoOperacao.STANDBY)
            stopSelf()
            return START_NOT_STICKY
        }

        _modo.value = modo
        entrarEmPrimeiroPlano(modo)

        // A coleta acompanha o modo: cadência e provedor mudam, a existência não.
        // Standby também publica — sumir do mapa em pausa criaria a expectativa
        // errada, porque companheiro que desaparece parece em perigo.
        coletor?.ajustarPara(modo, mapaVisivel = false)
        // START_STICKY: se o sistema matar por memória, é recriado — e o ramo
        // acima decide (com segurança) o que fazer nessa recriação.
        return START_STICKY
    }

    override fun onDestroy() {
        _modo.value = ModoOperacao.STANDBY
        // **Fecha o turno onde a coleta termina.**
        //
        // O servidor também encerra por inatividade (`0019`), como rede — mas
        // depender só disso daria 30 min de turno aberto depois de o agente ter
        // largado o aparelho, e turno é o recorte que autoriza saber onde ele
        // esteve. Fora do `escopo`, que morre na linha seguinte.
        val escritor = escritorProprio ?: publicador
        if (escritor != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { escritor.encerrarTurno() }
                    .onFailure { Log.w(TAG, "turno não encerrado", it) }
            }
        }
        // Soltar o GPS antes de morrer. Um listener sobrevivente mantém o rádio
        // de posição acordado sem ninguém consumindo — o pior custo possível,
        // porque não aparece em lugar nenhum da interface.
        coletor?.parar()
        coletor = null
        escopo.cancel()
        super.onDestroy()
    }

    /**
     * Sobe (ou atualiza) o serviço com os tipos exatos que o [modo] usa —
     * **interseccionados com as permissões de runtime concedidas**.
     *
     * Aprendizado de runtime (Android 14+): declarar `camera`/`microphone` no
     * manifest não basta; subir o FGS com esses tipos exige a permissão de
     * runtime correspondente **concedida**, senão é `SecurityException` e o
     * processo morre. Degradar (subir só com o que pode) é o comportamento
     * correto: o pipeline continua e a falta de sensor vira falha audível na
     * feature, não crash do app.
     */
    private fun entrarEmPrimeiroPlano(modo: ModoOperacao) {
        val tipos = PowerPolicy.tiposDeServico(modo)
            .filter { temPermissaoPara(it) }
            .fold(0) { acc, t -> acc or t.androidFlag() }
        val notificacao = construirNotificacao(modo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACAO, notificacao, tipos)
        } else {
            startForeground(ID_NOTIFICACAO, notificacao)
        }
    }

    /** Permissão de runtime que cada tipo de FGS exige (além da do manifest). */
    private fun temPermissaoPara(tipo: TipoServico): Boolean {
        val permissao = when (tipo) {
            TipoServico.CONNECTED_DEVICE -> return true // não exige runtime
            TipoServico.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
            TipoServico.CAMERA -> android.Manifest.permission.CAMERA
            TipoServico.LOCATION -> android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return checkSelfPermission(permissao) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun construirNotificacao(modo: ModoOperacao): Notification {
        val abrir = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val texto = when (modo) {
            ModoOperacao.STANDBY -> "Standby — microfone fechado"
            ModoOperacao.ATIVO -> "Ativo — ouvindo comandos"
            ModoOperacao.OCORRENCIA -> "Ocorrência — gravando"
        }
        return NotificationCompat.Builder(this, CANAL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        val canal = NotificationChannel(CANAL, "Copiloto em campo", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Pipeline de voz e sessão com os óculos" }
        nm.createNotificationChannel(canal)
    }

    companion object {
        private const val TAG = "ClaryonField"
        private const val CANAL = "claryon_copiloto"
        private const val ID_NOTIFICACAO = 1
        private const val EXTRA_MODO = "modo"
        private const val ACAO_PARAR = "com.claryon.field.PARAR"

        /**
         * Quem publica a posição no servidor.
         *
         * Injetado de fora porque o serviço não pode conhecer `core-net` nem a
         * sessão do agente — o token vive no cofre cifrado do `app`. Nulo até o
         * login: sem sessão não há a quem publicar, e o coletor descarta em
         * silêncio em vez de acumular.
         */
        /**
         * Injeção **para teste**. Em produção fica nulo e o serviço constrói o
         * próprio escritor.
         *
         * Era por aqui que o publicador chegava, vindo do `MapaViewModel` pela
         * `MainActivity` — propriedade invertida: a tela do mapa era dona do
         * escritor de posição, e desde que o mapa deixou de publicar (`0016`) ela
         * não tinha mais motivo nenhum para sê-lo. Pior, o serviço dependia de
         * alguém ter passado por aquela linha antes de ele coletar.
         */
        @Volatile
        var publicador: PublicadorDePosicao? = null

        private val _modo = MutableStateFlow(ModoOperacao.STANDBY)

        /** Modo corrente do pipeline — a UI observa para refletir o estado real. */
        val modo: StateFlow<ModoOperacao> = _modo

        /**
         * Sobe/atualiza o serviço no [modo]. **Chamar de tela visível** — iniciar
         * FGS em background é `ForegroundServiceStartNotAllowedException`.
         */
        fun iniciar(context: Context, modo: ModoOperacao) {
            val i = Intent(context, CopilotService::class.java).putExtra(EXTRA_MODO, modo.name)
            context.startForegroundService(i)
        }

        fun parar(context: Context) {
            val i = Intent(context, CopilotService::class.java)
                .setAction(ACAO_PARAR)
                .putExtra(EXTRA_MODO, ModoOperacao.STANDBY.name)
            // `startForegroundService` e não `startService`: a partir do Android 8
            // um serviço em primeiro plano só pode ser iniciado por este caminho
            // quando o app está em background, e `parar()` é chamado exatamente
            // dali. `startService` lançaria `IllegalStateException` — o pedido de
            // PARAR derrubando o app.
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Teto de FPS agora, cruzando a política de modo com o estado térmico.
         * `getThermalHeadroom` pode devolver `NaN` — [ThermalGovernor] trata.
         */
        fun fpsPermitidoAgora(context: Context, modo: ModoOperacao): Int {
            val padrao = PowerPolicy.perfil(modo).fpsMaximo
            if (padrao == 0) return 0
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    context.getSystemService(PowerManager::class.java)
                        .getThermalHeadroom(SEGUNDOS_PREVISAO)
                }.getOrDefault(Float.NaN)
            } else {
                Float.NaN
            }
            return ThermalGovernor.fpsPermitido(headroom, padrao)
        }

        private const val SEGUNDOS_PREVISAO = 10
    }
}

/** Tradução do espelho puro [TipoServico] para as constantes do Android. */
private fun TipoServico.androidFlag(): Int = when (this) {
    TipoServico.CONNECTED_DEVICE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    TipoServico.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    TipoServico.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    TipoServico.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
}
