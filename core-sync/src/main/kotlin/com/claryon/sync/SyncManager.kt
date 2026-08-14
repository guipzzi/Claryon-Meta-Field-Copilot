package com.claryon.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cola de rede do app: onde ficam a [Outbox] durável e o [SyncGateway] escolhido.
 * O `Application` chama [instalar] no boot; o [OutboxUploadWorker] lê daqui.
 *
 * Guardar o gateway (com a chave do Supabase) num holder de processo evita
 * gravar credencial na base do WorkManager.
 */
object SyncManager {

    @Volatile private var outbox: Outbox? = null
    @Volatile private var gateway: SyncGateway? = null

    /** Fila de saída durável (criada sob demanda em `<filesDir>/outbox`). */
    fun outbox(context: Context): Outbox =
        outbox ?: synchronized(this) {
            outbox ?: FileOutbox(File(context.applicationContext.filesDir, "outbox")).also { outbox = it }
        }

    fun instalar(context: Context, gateway: SyncGateway) {
        this.gateway = gateway
        this.outbox = outbox(context)
    }

    internal fun gatewayOuNulo(): SyncGateway? = gateway

    /**
     * Enfileira uma drenagem **tática** (leve): basta ter conectividade. Backoff
     * exponencial; trabalho único para não empilhar drenagens concorrentes.
     */
    fun agendarDrenagemTatica(context: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TRABALHO_TATICO, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Drenagem **pesada** (evidência/modelos): só em condições folgadas —
     * carregando, rede não-tarifada, bateria ok (política de energia do PADROES_DE_ENGENHARIA.md).
     */
    fun agendarDrenagemPesada(context: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TRABALHO_PESADO, ExistingWorkPolicy.KEEP, req)
    }

    const val TRABALHO_TATICO = "claryon_outbox_tatico"
    const val TRABALHO_PESADO = "claryon_outbox_pesado"
}

/**
 * Worker que drena a fila. Se o gateway ainda não foi instalado (ex.: reboot
 * antes do boot do app), pede retry — nunca perde o item.
 */
class OutboxUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val gateway = SyncManager.gatewayOuNulo() ?: return Result.retry()
        val outbox = SyncManager.outbox(applicationContext)
        val relatorio = OutboxDrainer(
            outbox,
            gateway,
            // Um descarte é uma mensagem que o agente ouviu "na fila" e que
            // nunca vai chegar. Registrar é o mínimo; o app liga isto ao earcon
            // de falha quando o executor de intenções existir.
            aoDescartar = { item ->
                android.util.Log.e(TAG, "Item DESCARTADO após tentativas: id=${item.id} tipo=${item.type}")
            },
        ).drenar()
        if (relatorio.corrompidos > 0) {
            android.util.Log.w(TAG, "Removidos ${relatorio.corrompidos} itens ilegíveis da fila")
        }
        // Ainda há pendências (rede caiu no meio) → tenta de novo depois.
        return if (relatorio.restantes > 0) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
