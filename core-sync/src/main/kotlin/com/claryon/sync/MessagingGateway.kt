package com.claryon.sync

import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow

/**
 * Gateway de mensageria tática — **contrato sem implementação**.
 *
 * O canal concreto está por definir (ver `DECISIONS.md`, M7). O destino é
 * abstraído por [TacticalMessage.recipientType], permitindo trocar 1:1 por grupo
 * sem reescrita, e a mensagem é um **objeto tipado preenchendo um template
 * aprovado** — nunca a transcrição bruta, para que erro de transcrição não vire
 * erro operacional.
 *
 * A saída de rede que existe hoje é o Supabase, por [SyncGateway] + [Outbox].
 */
interface MessagingGateway {
    suspend fun send(msg: TacticalMessage): Result<MessageId>
    fun incoming(): Flow<InboundMessage>
}

/** Tipo de destinatário — abstrai 1:1 (Cloud API) vs. grupo (Groups API). */
enum class RecipientType { INDIVIDUAL, GROUP }

/**
 * Mensagem tática estruturada (preenche um template aprovado, ≤120 caracteres
 * no campo de situação). Reduz ambiguidade sob estresse e evita que erro de
 * transcrição vire erro operacional.
 */
data class TacticalMessage(
    val recipientType: RecipientType,
    val recipient: String,
    val agentId: String,
    val vehiclePrefix: String?,
    val location: String?,
    val latitude: Double?,
    val longitude: Double?,
    val situation: String,
    val priority: String,
    val evidenceStatus: String?,
)

@JvmInline
value class MessageId(val value: String)

/** Mensagem recebida (webhook → FCM → app → fila de prioridade → TTS no ouvido). */
data class InboundMessage(
    val id: MessageId,
    val from: String,
    val text: String,
    val receivedAtEpochMillis: Long,
)
