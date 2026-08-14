package com.claryon.sync

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * [SyncGateway] real contra o **Supabase** via PostgREST (`POST /rest/v1/<tabela>`).
 * Sem SDK pesado — só HTTP + JSON. O `payload` do item já é o corpo JSON.
 *
 * `Prefer: resolution=merge-duplicates` torna o upsert idempotente (reenvio após
 * falha de rede não duplica a ocorrência). Credenciais vêm de config em runtime,
 * nunca versionadas.
 *
 * Não verificável offline (exige projeto Supabase + rede); a **política de fila**
 * que a envolve é testada com [FakeSyncGateway].
 */
class SupabaseSyncGateway(
    private val config: SupabaseConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val tabelaPorTipo: (String) -> String = { tipo ->
        when (tipo) {
            TacticalMessageCodec.TYPE -> "tactical_messages"
            else -> "events"
        }
    },
) : SyncGateway {

    override suspend fun push(item: OutboxItem): Result<Unit> = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl.trimEnd('/')}/rest/v1/${tabelaPorTipo(item.type)}"
        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(item.payload.toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        ClaryonError.Sync("SYNC_HTTP_${resp.code}", "Supabase respondeu ${resp.code}"),
                    )
                }
            }
        } catch (e: IOException) {
            Result.failure(ClaryonError.Sync("SYNC_IO", "falha de rede"), e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Configuração do Supabase — carregada em runtime (BuildConfig/remoto), nunca no VCS. */
data class SupabaseConfig(val baseUrl: String, val apiKey: String)
