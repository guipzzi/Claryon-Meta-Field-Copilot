package com.claryon.net

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * **Registra a transmissão no servidor — o produtor que faltava.**
 *
 * A tabela `transmissions`, a Edge Function `transmit` e o leitor
 * [HistoricoDoCanal.falas] existiam. O **escritor** não: `grep "functions/v1"`
 * sobre o Kotlin devolvia zero. Consequência medida — `transmissions` nunca
 * recebia INSERT, `falas()` devolvia lista vazia **sempre**, e o fio do canal
 * exibia só as inserções otimistas locais, que somem em dez segundos na recarga.
 * A superfície visível do Pilar 1 estava vazia em produção.
 *
 * **O que este arquivo deliberadamente não faz:** enviar áudio. O áudio ao vivo
 * segue por WebSocket em quadros de 20 ms, e é isso que faz o rádio ser rádio.
 * Aqui vai só o **registro** — quem falou, quando, por quanto tempo, e a
 * classificação. Persistir também o áudio pelo mesmo caminho dobraria o tráfego
 * para entregar duas vezes a mesma coisa.
 *
 * **A identidade não vai no corpo.** `author_agent_id` sai do JWT dentro da
 * função, contra `agents.auth_user_id`. Mandá-la daqui foi o defeito que permitia
 * escrever transmissão no nome de qualquer agente — ver `supabase/functions/transmit/index.ts`.
 */
class RegistroDeTransmissao(
    private val config: ConfigRealtime,
    private val tokenDeSessao: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) {

    /**
     * Registra uma transmissão concluída.
     *
     * `id` é o **mesmo** `transmissaoId` usado nos quadros de áudio, e não um novo:
     * é ele que amarra o registro escrito ao que foi ao ar, e é ele que dá
     * idempotência — repetir a chamada depois de uma queda de rede devolve o
     * estado existente em vez de duplicar a fala no histórico.
     *
     * Falha **não** propaga para o caminho de áudio. A fala já foi ouvida pela
     * guarnição; perder a linha do histórico é perda de registro, não de
     * comunicação, e derrubar o PTT por causa dela seria trocar o essencial pelo
     * acessório.
     */
    suspend fun registrar(
        transmissaoId: String,
        talkGroupId: String,
        tipo: String,
        prioridade: Int,
        duracaoMs: Long,
        transcricao: String?,
    ): Result<Unit> {
        val token = tokenDeSessao()
            ?: return Result.failure(
                ClaryonError.Sync("transmit.sem_sessao", "Sem token de sessão."),
            )

        val corpo = JSONObject().apply {
            put("id", transmissaoId)
            put("talk_group_id", talkGroupId)
            put("tipo", tipo)
            put("prioridade", prioridade)
            put("duracao_ms", duracaoMs)
            // `null` explícito e não string vazia: a coluna aceita nulo, e nulo
            // significa "não transcrito", enquanto "" significaria "transcrito
            // como silêncio". A diferença importa para quem for auditar depois.
            if (transcricao != null) put("transcricao", transcricao)
        }

        val req = Request.Builder()
            .url("${config.projetoUrl}/functions/v1/transmit")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("apikey", config.apiKey)
            .post(corpo.toString().toRequestBody(JSON))
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        ClaryonError.Sync(
                            "transmit.http_${resp.code}",
                            "Registro da transmissão recusado (${resp.code}).",
                        ),
                    )
                }
            }
        }.getOrElse {
            Result.failure(
                ClaryonError.Sync("transmit.falhou", it.message ?: "Falha de rede."),
            )
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
