package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Prioridade
import com.claryon.common.Result
import com.claryon.evidence.EvidenceVault
import com.claryon.evidence.OccurrenceContext
import com.claryon.evidence.RecordingHandle
import com.claryon.sync.Despacho
import com.claryon.sync.RecipientType
import com.claryon.sync.TacticalDispatcher
import com.claryon.sync.TacticalMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identidade operacional do portador — preenche o template da mensagem tática. */
data class Identidade(
    val agentId: String,
    val unitId: String,
    val vehiclePrefix: String?,
    val destinoPadrao: String,
)

/**
 * Executor real: liga cada intenção ao módulo que faz a coisa acontecer.
 *
 * É aqui que `core-evidence` e `core-sync` — prontos e testados, mas até agora
 * nunca importados pelo código de produção — passam a ser exercitados.
 *
 * Três invariantes que este código sustenta:
 *
 *  1. **Nunca lança.** Toda exceção vira [ActionOutcome.Falhou] com causa
 *     tipada. Num sistema sem display, exceção que sobe é silêncio, e silêncio
 *     é indistinguível de aplicativo morto.
 *  2. **Serializa.** Um `Mutex` protege o handle de gravação. Dois comandos
 *     concorrentes ("gravar" repetido sob estresse) abririam duas ocorrências e
 *     a segunda sobregravaria a primeira — a auditoria já pegou essa classe de
 *     defeito dentro do cofre, e ela reaparece aqui se o executor não serializar.
 *  3. **Não inventa.** Onde a capacidade não existe (consulta a base oficial
 *     está fora do escopo), devolve [FalhaOperacional.CONSULTA_INDISPONIVEL] em
 *     vez de um resultado plausível.
 */
class ClaryonIntentExecutor(
    private val cofre: EvidenceVault,
    private val despachante: TacticalDispatcher,
    private val identidade: Identidade,
    private val agora: () -> Long,
    private val aoTrocarModo: suspend (ModoOperacao) -> Unit,
) : IntentExecutor {

    private val mutex = Mutex()
    private var gravacaoAtual: RecordingHandle? = null

    /** Último resultado, para [Intent.Detalhar]. Não guarda o próprio repetir. */
    private var ultimoResultado: ActionOutcome? = null

    override suspend fun execute(intent: Intent): ActionOutcome {
        // Detalhar não é ação: relê o que já aconteceu. Fora do mutex e fora do
        // registro, senão repetir passaria a ser "o último resultado".
        if (intent is Intent.Detalhar) {
            return ultimoResultado ?: ActionOutcome.Falhou(FalhaOperacional.NADA_A_REPETIR)
        }

        val outcome = mutex.withLock {
            runCatching { executarInterno(intent) }
                .getOrElse { ActionOutcome.Falhou(FalhaOperacional.INTERNA) }
        }
        ultimoResultado = outcome
        return outcome
    }

    private suspend fun executarInterno(intent: Intent): ActionOutcome = when (intent) {

        is Intent.PedirApoio -> despachar(intent.prioridade, intent.resumo ?: "Apoio solicitado")

        Intent.Emergencia -> despachar(Prioridade.EMERGENCIA, "Emergência acionada")

        is Intent.IniciarGravacao -> iniciarGravacao()

        Intent.EncerrarGravacao -> encerrarGravacao()

        is Intent.NarrarOcorrencia -> registrarOcorrencia(intent.texto)

        is Intent.ConsultarPlaca ->
            // Consulta a bases oficiais (Detran/Sinesp) está fora do escopo por
            // dependência externa inviável no prazo. Sem base, a resposta honesta
            // é dizer que não dá — jamais um "sem restrição" inventado, que num
            // contexto de abordagem seria uma informação de segurança falsa.
            ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL)

        is Intent.TrocarModo -> {
            aoTrocarModo(intent.modo)
            ActionOutcome.ModoTrocado(intent.modo)
        }

        is Intent.NaoReconhecida -> ActionOutcome.NaoEntendi

        // Tratado antes do mutex; o ramo existe para o `when` ser exaustivo.
        Intent.Detalhar -> ultimoResultado ?: ActionOutcome.Falhou(FalhaOperacional.NADA_A_REPETIR)
    }

    // ── Despacho tático ───────────────────────────────────────────────────────

    private suspend fun despachar(prioridade: Prioridade, situacao: String): ActionOutcome {
        val msg = TacticalMessage(
            recipientType = RecipientType.GROUP,
            recipient = identidade.destinoPadrao,
            agentId = identidade.agentId,
            vehiclePrefix = identidade.vehiclePrefix,
            location = null,
            latitude = null,
            longitude = null,
            situation = situacao.take(SITUACAO_MAX),
            priority = prioridade.name,
            evidenceStatus = gravacaoAtual?.let { "gravando" },
        )
        return when (val d = despachante.despachar(msg)) {
            is Despacho.Enviada -> ActionOutcome.ApoioTransmitido(d.destinatarios)
            Despacho.Enfileirada -> ActionOutcome.ApoioEnfileirado
        }
    }

    private suspend fun registrarOcorrencia(texto: String): ActionOutcome {
        val msg = TacticalMessage(
            recipientType = RecipientType.GROUP,
            recipient = identidade.destinoPadrao,
            agentId = identidade.agentId,
            vehiclePrefix = identidade.vehiclePrefix,
            location = null,
            latitude = null,
            longitude = null,
            situation = texto.take(SITUACAO_MAX),
            priority = Prioridade.NORMAL.name,
            evidenceStatus = gravacaoAtual?.let { "gravando" },
        )
        // Enfileirada também é "registrada": a fila é durável e sobrevive à morte
        // do processo, então a ocorrência não se perde. O que muda é a entrega.
        return when (despachante.despachar(msg)) {
            is Despacho.Enviada, Despacho.Enfileirada ->
                ActionOutcome.OcorrenciaRegistrada(identidade.agentId + "@" + agora())
        }
    }

    // ── Cofre de evidência ────────────────────────────────────────────────────

    private suspend fun iniciarGravacao(): ActionOutcome {
        if (gravacaoAtual != null) {
            return ActionOutcome.Falhou(FalhaOperacional.GRAVACAO_JA_ATIVA)
        }
        val ctx = OccurrenceContext(
            agentId = identidade.agentId,
            unitId = identidade.unitId,
            startedAtEpochMillis = agora(),
        )
        return when (val r = cofre.beginRecording(ctx)) {
            is Result.Success -> {
                gravacaoAtual = r.value
                ActionOutcome.GravacaoIniciada(r.value.id)
            }
            is Result.Failure -> ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL)
        }
    }

    private suspend fun encerrarGravacao(): ActionOutcome {
        val handle = gravacaoAtual
            ?: return ActionOutcome.Falhou(FalhaOperacional.SEM_GRAVACAO_ATIVA)
        return when (val r = cofre.finalize(handle)) {
            is Result.Success -> {
                gravacaoAtual = null
                ActionOutcome.GravacaoEncerrada(r.value.chain.size)
            }
            // Handle preservado: o cofre pode ter falhado só ao escrever o
            // manifesto, e zerar aqui deixaria a gravação órfã e impossível de
            // fechar por comando de voz.
            is Result.Failure -> ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL)
        }
    }

    /** Anexa um segmento à gravação em curso, se houver. Usado pelo pipeline de áudio. */
    suspend fun anexarEvidencia(chunk: ByteArray): Boolean {
        val handle = mutex.withLock { gravacaoAtual } ?: return false
        return cofre.append(handle, chunk) is Result.Success
    }

    /** `true` se há gravação aberta (para o painel e para o encerramento do app). */
    suspend fun gravando(): Boolean = mutex.withLock { gravacaoAtual != null }

    private companion object {
        /** Limite do campo de situação no template aprovado. */
        const val SITUACAO_MAX = 120
    }
}
