package com.claryon.sound

/**
 * **Política** de escalonamento da fila de som — pura e testável (sem coroutines).
 *
 * Regras (design de áudio):
 *  - **nível 1 (EMERGENCIA) interrompe qualquer coisa**;
 *  - nível 2 (RESPOSTA) aguarda o nível 1;
 *  - **nível 3 (INFORMATIVO) é suprimido em Modo Tático**.
 *
 * O mecanismo (reprodução via AudioTrack, coroutine) fica em [PrioritySoundQueue]
 * e apenas consome estas decisões.
 */
class SoundScheduler {

    private val pending = ArrayList<Sound>()
    var tactical: Boolean = false
        private set

    /** Ativa/desativa o Modo Tático; ao ativar, descarta os INFORMATIVO pendentes. */
    fun setTactical(enabled: Boolean) {
        tactical = enabled
        if (enabled) pending.removeAll { it.priority == Priority.INFORMATIVO }
    }

    /** Enfileira; retorna `false` se suprimido (INFORMATIVO em Modo Tático). */
    fun offer(sound: Sound): Boolean {
        if (tactical && sound.priority == Priority.INFORMATIVO) return false
        pending.add(sound)
        return true
    }

    /** Próximo a tocar: maior prioridade; empate = ordem de chegada. Remove-o. */
    fun poll(): Sound? {
        val next = pending.minByOrNull { it.priority.ordinal } ?: return null
        pending.remove(next)
        return next
    }

    /** Um [novo] som deve interromper o que toca agora em [atual]? Só emergência. */
    fun deveInterromper(novo: Priority, atual: Priority?): Boolean =
        novo == Priority.EMERGENCIA && atual != null && atual != Priority.EMERGENCIA

    fun clear() = pending.clear()

    fun size(): Int = pending.size
}
