package com.claryon.sound

/**
 * Design de áudio como regra dura (num sistema sem display, é a UX inteira).
 */

/**
 * Prioridade de reprodução.
 *  - [EMERGENCIA] (nível 1): interrompe qualquer coisa.
 *  - [RESPOSTA]   (nível 2): resposta a comando do próprio agente; aguarda nível 1.
 *  - [INFORMATIVO](nível 3): suprimido inteiramente em Modo Tático.
 */
enum class Priority { EMERGENCIA, RESPOSTA, INFORMATIVO }

/** Biblioteca fixa de earcons — sinal não-verbal curto (150–250 ms), significado fixo. */
enum class Earcon(val significado: String) {
    OUVI_VOCE("bipe curto ascendente — 'ouvi você' (~400 ms do fim da fala)"),
    ACAO_EXECUTADA("duplo bipe curto — 'ação executada'"),
    FALHA("bipe grave descendente — 'não entendi / falhou'"),
    GRAVANDO("tom contínuo 2 s — 'gravando' (avisa agente e ambiente)"),
    PRIORITARIA("três bipes rápidos — 'mensagem prioritária chegando'"),

    // Resultado de consulta sensível: codificado, NUNCA falado.
    CONSULTA_SEM_RESTRICAO("1 bipe curto e neutro"),
    CONSULTA_RESTRICAO_ADMIN("2 bipes médios"),
    CONSULTA_FURTO_ROUBO("padrão de alerta distinto, 3 tons"),
}

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
