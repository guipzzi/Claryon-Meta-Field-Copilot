package com.claryon.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piso resolvido **no aparelho**, sobre a política pura [ControleDePiso].
 *
 * Serve para demonstração de um cliente só e para desenvolvimento sem servidor.
 * **Não serve para operação real**: dois aparelhos com instâncias locais não
 * enxergam um ao outro, e ambos se achariam donos do canal. O nome é explícito
 * para que ninguém o instale por engano achando que é o de produção.
 */
class ClienteDePisoLocal(
    private val politica: ControleDePiso = ControleDePiso(),
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) : ClienteDePiso {

    override suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido = politica.pedir(talkGroupId, agenteId, transmissaoId, prioridade, agoraMs())

    override suspend fun renovar(concessao: Concessao): Boolean = politica.renovar(concessao, agoraMs())

    /**
     * Sempre [ResultadoDaLiberacao.Devolvido]: a política mora em RAM deste
     * processo, e não existe caminho pelo qual a devolução se perca. Um "falhou"
     * aqui seria mentira na direção oposta.
     */
    override suspend fun liberar(concessao: Concessao): ResultadoDaLiberacao {
        politica.liberar(concessao)
        return ResultadoDaLiberacao.Devolvido
    }

    /**
     * `false`, e é a razão de esta classe existir com nome explícito: dois
     * aparelhos com instâncias locais não se enxergam, e ambos se acham donos do
     * canal. Quem opera precisa ouvir isso, não deduzi-lo de um log.
     */
    override val arbitradoPeloServidor: Boolean get() = false
}

/**
 * Piso resolvido **no servidor**, por RPC do PostgREST sobre as funções
 * `pedir_canal` / `renovar_canal` / `liberar_canal`.
 *
 * A decisão mora no Postgres, e não numa função serverless, porque a concessão
 * precisa ser **atômica**: dois agentes que apertam o PTT no mesmo instante não
 * podem ambos receber o canal. "Ler, decidir, escrever" em código de aplicação
 * abriria essa janela; `for update` + `on conflict` fecham no banco.
 *
 * A identidade **não é enviada** — o servidor a deriva do JWT. Se o agente
 * viesse por parâmetro, qualquer cliente pediria o canal em nome de outro, e o
 * controle de piso viraria vetor de negação de serviço contra a guarnição.
 *
 * @param jwt token do agente autenticado. Sem ele o servidor recusa.
 */
class ClienteDePisoRemoto(
    private val config: ConfigRealtime,
    private val jwt: () -> String,
    private val agenteIdLocal: String,
    private val ttlSegundos: Int = 30,
    private val cliente: OkHttpClient = OkHttpClient(),
) : ClienteDePiso {

    override suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido {
        val corpo = JSONObject()
            .put("p_talk_group_id", talkGroupId)
            .put("p_transmissao_id", transmissaoId)
            .put("p_prioridade", prioridade.nivel)
            .put("p_ttl_segundos", ttlSegundos)

        val linha = when (val resposta = rpc("pedir_canal", corpo)) {
            // Rede caiu no pedido: **não** presumir concedido. Falar achando que
            // tem o canal produz sobreposição no ouvido de quem está numa
            // ocorrência — pior que não falar.
            //
            // O que mudou em 22/08 não foi essa decisão: foi o que o agente
            // OUVE. Isto devolvia `Ocupado(detentor = "?")`, e o aparelho tocava
            // o tom de canal ocupado — que manda esperar o colega soltar o botão.
            // A ação certa aqui é a oposta: andar até pegar sinal.
            is RespostaRpc.SemResposta -> return ResultadoDoPedido.SemRede
            // O servidor respondeu, e a resposta é uma recusa de autorização
            // (não-membro, token vencido, agente inativo). Nem ocupado, nem sem
            // rede — e mandar o agente procurar torre por um problema de
            // credencial é a mesma mentira com outro rótulo.
            is RespostaRpc.Recusado -> return ResultadoDoPedido.Recusado(resposta.motivo)
            is RespostaRpc.Ok -> resposta.linha ?: return ResultadoDoPedido.Recusado(
                "pedir_canal respondeu sem linha",
            )
        }

        val expira = agoraAproximado() + ttlSegundos * 1000L
        val detentorId = linha.optString("detentor_agent_id").ifEmpty { "?" }

        return when (linha.optString("resultado")) {
            "concedido" -> ResultadoDoPedido.Concedido(
                Concessao(talkGroupId, agenteIdLocal, transmissaoId, prioridade, expira),
            )
            "tomado" -> ResultadoDoPedido.Tomado(
                interrompido = Concessao(talkGroupId, detentorId, "?", PrioridadeTransmissao.P2_APOIO, 0),
                concessao = Concessao(talkGroupId, agenteIdLocal, transmissaoId, prioridade, expira),
            )
            else -> ResultadoDoPedido.Ocupado(
                Concessao(talkGroupId, detentorId, "?", PrioridadeTransmissao.P2_APOIO, expira),
            )
        }
    }

    /**
     * `false` = o canal já não é seu (expirou, foi tomado, ou você saiu do grupo)
     * — sinal para parar de transmitir. Falha de rede também devolve `false`: na
     * dúvida, calar é mais seguro que seguir falando sem saber se alguém ouve.
     *
     * A partir da migração `0024`, "saiu do grupo" entrou na lista: `renovar_canal`
     * confere pertencimento e **apaga** a concessão de quem já não é membro.
     */
    override suspend fun renovar(concessao: Concessao): Boolean {
        val corpo = JSONObject()
            .put("p_transmissao_id", concessao.transmissaoId)
            .put("p_ttl_segundos", ttlSegundos)
        return (rpc("renovar_canal", corpo) as? RespostaRpc.Ok)?.booleano == true
    }

    /**
     * **A devolução tem desfecho, e ele é lido.** Ver [ResultadoDaLiberacao].
     *
     * `true` e `false` são os dois lados do mesmo bom desfecho: o `delete`
     * removeu a concessão, ou não havia concessão nossa a remover — em ambos o
     * canal está livre para o grupo. O que não pode passar em branco é a
     * **ausência de resposta**: aí o piso segue preso até o TTL de 30 s, e é a
     * guarnição inteira que paga em silêncio.
     */
    override suspend fun liberar(concessao: Concessao): ResultadoDaLiberacao =
        when (
            val r = rpc(
                "liberar_canal",
                JSONObject().put("p_transmissao_id", concessao.transmissaoId),
            )
        ) {
            is RespostaRpc.Ok -> ResultadoDaLiberacao.Devolvido
            is RespostaRpc.Recusado -> ResultadoDaLiberacao.NaoDevolvido("HTTP ${r.codigo}")
            is RespostaRpc.SemResposta -> ResultadoDaLiberacao.NaoDevolvido(r.motivo)
        }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    /**
     * **Os três desfechos que um RPC de piso pode ter**, separados porque as
     * ações que eles pedem são diferentes.
     *
     * A versão anterior tinha duas funções (`rpc` e `rpcBooleano`) que colapsavam
     * tudo em `null`, e só uma delas registrava a falha em log. Uma delas ainda
     * descartava o retorno inteiro. Três camadas, um único fato invisível.
     */
    private sealed interface RespostaRpc {

        /** 2xx. [linha] para as funções que devolvem tabela, [booleano] para as escalares. */
        data class Ok(val linha: JSONObject?, val booleano: Boolean?) : RespostaRpc

        /** O servidor respondeu **não** — `raise exception` do plpgsql vira 4xx. */
        data class Recusado(val codigo: Int, val motivo: String) : RespostaRpc

        /** Nada voltou: socket, DNS, timeout. Não é recusa, é ausência. */
        data class SemResposta(val motivo: String) : RespostaRpc
    }

    private suspend fun rpc(funcao: String, corpo: JSONObject): RespostaRpc =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${config.projetoUrl.trimEnd('/')}/rest/v1/rpc/$funcao")
                .addHeader("apikey", config.apiKey)
                .addHeader("Authorization", "Bearer ${jwt()}")
                .addHeader("Content-Type", "application/json")
                .post(corpo.toString().toRequestBody(JSON))
                .build()
            runCatching {
                cliente.newCall(req).execute().use { resp ->
                    val texto = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$funcao respondeu ${resp.code}: ${texto.take(200)}")
                        return@use RespostaRpc.Recusado(resp.code, mensagemDeErro(texto, resp.code))
                    }
                    val podado = texto.trimStart()
                    RespostaRpc.Ok(
                        linha = when {
                            podado.startsWith("[") ->
                                JSONArray(texto).let { if (it.length() > 0) it.getJSONObject(0) else null }
                            podado.startsWith("{") -> JSONObject(texto)
                            else -> null
                        },
                        booleano = texto.trim().takeIf { it.isNotEmpty() }
                            ?.equals("true", ignoreCase = true),
                    )
                }
            }.onFailure {
                // O `onFailure` que faltava em `rpcBooleano`: sem ele, uma
                // devolução de canal perdida não deixava rastro em lugar nenhum.
                Log.w(TAG, "$funcao falhou: ${it.message}")
            }.getOrElse { RespostaRpc.SemResposta(it.message ?: "sem resposta") }
        }

    /** Mensagem do PostgREST, quando existe. Vai para log, nunca para o alto-falante. */
    private fun mensagemDeErro(corpo: String, codigo: Int): String =
        runCatching { JSONObject(corpo).optString("message").ifEmpty { "HTTP $codigo" } }
            .getOrDefault("HTTP $codigo")

    private fun agoraAproximado() = System.currentTimeMillis()

    private companion object {
        const val TAG = "ClaryonField"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Nível numérico que o servidor usa (1 = emergência). */
val PrioridadeTransmissao.nivel: Int
    get() = when (this) {
        PrioridadeTransmissao.P1_EMERGENCIA -> 1
        PrioridadeTransmissao.P2_APOIO -> 2
        PrioridadeTransmissao.P3_INFORMATIVO -> 3
    }
