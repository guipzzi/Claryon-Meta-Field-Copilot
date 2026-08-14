package com.claryon.sound

/**
 * Design de áudio como regra dura (num sistema sem display, é a UX inteira).
 */

/**
 * [Priority] e [Earcon] mudaram para `core-common` (ver `AudioSignals.kt`): o
 * executor de intenções precisa nomear earcons para dizer "isto sai como sinal,
 * não como fala", e `core-agent` não pode depender de `core-sound`.
 *
 * Os apelidos abaixo mantêm `com.claryon.sound.Earcon` válido — um único enum,
 * duas portas de entrada, zero chance de as listas divergirem.
 */
typealias Priority = com.claryon.common.Priority
typealias Earcon = com.claryon.common.Earcon

/** Item reproduzível: um earcon, ou fala sintetizada (sujeita à laconicidade). */
sealed interface Sound {
    val priority: Priority

    data class Tone(val earcon: Earcon, override val priority: Priority) : Sound
    data class Speech(val text: String, override val priority: Priority) : Sound
}

/**
 * Fila de prioridade de reprodução.
 *  - nível 1 interrompe tudo; nível 3 é suprimido em Modo Tático.
 * Contrato fixado no M0; implementação (mixagem, ducking, volume adaptativo) no M5.
 */
interface SoundQueue {
    fun enqueue(sound: Sound)
    fun setTacticalMode(enabled: Boolean)
    fun clear()
}
