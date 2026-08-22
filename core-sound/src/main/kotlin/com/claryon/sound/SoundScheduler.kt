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

    /**
     * Um [novo] som deve interromper o que está **em curso** em [atual]? Só
     * emergência.
     *
     * **[atual] é o item em curso, não "o que toca agora".** A diferença custou
     * um defeito: enquanto o `render` rodava fora do escopo de reprodução,
     * [PrioritySoundQueue] passava `null` aqui durante toda a síntese — um
     * segundo por frase, com o Piper — e esta função respondia, corretamente,
     * `false` para uma emergência que tinha todo o direito de cortar. A política
     * nunca esteve errada; ela era chamada com um retrato incompleto do
     * mecanismo. Hoje o mecanismo publica a prioridade **antes** de sintetizar.
     */
    fun deveInterromper(novo: Priority, atual: Priority?): Boolean =
        novo == Priority.EMERGENCIA && atual != null && atual != Priority.EMERGENCIA

    fun clear() = pending.clear()

    fun size(): Int = pending.size
}
