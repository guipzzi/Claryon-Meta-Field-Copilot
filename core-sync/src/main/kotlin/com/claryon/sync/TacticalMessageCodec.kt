package com.claryon.sync

/**
 * Serializa [TacticalMessage] em JSON estável para trafegar na [Outbox] e subir
 * ao Supabase. O que trafega é o **objeto tipado preenchendo um template
 * aprovado**, nunca a transcrição bruta (regra do M0). JSON manual e mínimo para
 * não arrastar dependência de serialização.
 */
object TacticalMessageCodec {

    const val TYPE = "tactical_message"

    fun encode(msg: TacticalMessage, id: String, createdAtEpochMillis: Long): OutboxItem =
        OutboxItem(
            id = id,
            type = TYPE,
            payload = toJson(msg),
            createdAtEpochMillis = createdAtEpochMillis,
        )

    fun toJson(m: TacticalMessage): String = buildString {
        append('{')
        campo("recipientType", m.recipientType.name); append(',')
        campo("recipient", m.recipient); append(',')
        campo("agentId", m.agentId); append(',')
        campoOpc("vehiclePrefix", m.vehiclePrefix); append(',')
        campoOpc("location", m.location); append(',')
        campoNum("latitude", m.latitude); append(',')
        campoNum("longitude", m.longitude); append(',')
        campo("situation", m.situation); append(',')
        campo("priority", m.priority); append(',')
        campoOpc("evidenceStatus", m.evidenceStatus)
        append('}')
    }

    private fun StringBuilder.campo(k: String, v: String) {
        append('"').append(k).append("\":\"").append(escape(v)).append('"')
    }

    private fun StringBuilder.campoOpc(k: String, v: String?) {
        append('"').append(k).append("\":")
        if (v == null) append("null") else append('"').append(escape(v)).append('"')
    }

    private fun StringBuilder.campoNum(k: String, v: Double?) {
        append('"').append(k).append("\":").append(v?.toString() ?: "null")
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
