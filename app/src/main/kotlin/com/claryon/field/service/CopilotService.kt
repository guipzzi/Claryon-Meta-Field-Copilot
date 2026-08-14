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
import androidx.core.app.NotificationCompat
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import com.claryon.agent.ThermalGovernor
import com.claryon.agent.TipoServico
import com.claryon.field.MainActivity
import com.claryon.field.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Serviço de primeiro plano do pipeline contínuo (sessão com os óculos, captura
 * HFP e — em ocorrência — câmera).
 *
 * Armadilhas endereçadas (CLAUDE.md):
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

    override fun onCreate() {
        super.onCreate()
        criarCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modo = intent?.getStringExtra(EXTRA_MODO)
            ?.let { runCatching { ModoOperacao.valueOf(it) }.getOrNull() }
            ?: ModoOperacao.ATIVO

        if (modo == ModoOperacao.STANDBY && intent?.action == ACAO_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }

        _modo.value = modo
        entrarEmPrimeiroPlano(modo)
        // START_STICKY: se o sistema matar por memória, o pipeline volta.
        return START_STICKY
    }

    override fun onDestroy() {
        _modo.value = ModoOperacao.STANDBY
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
        private const val CANAL = "claryon_copiloto"
        private const val ID_NOTIFICACAO = 1
        private const val EXTRA_MODO = "modo"
        private const val ACAO_PARAR = "com.claryon.field.PARAR"

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
            context.startService(i)
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
}
