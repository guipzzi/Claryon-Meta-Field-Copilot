package com.claryon.field.audio

import android.util.Log
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **A rota de áudio fica de pé durante a rajada, não durante a frase.**
 *
 * ## O defeito que esta classe existe para consertar
 *
 * Cada fala do copiloto fazia `iniciar()` → tocar → `liberar()`. Como cada earcon
 * é uma emissão e cada frase é outra, a rota SCO era **derrubada e remontada
 * entre dois sons que o agente ouve como um só**: o bipe e a resposta que vem
 * logo atrás.
 *
 * Medido no emulador, o `liberar()` custa **204 ms** — e o emulador é o caso
 * fácil. Em HFP real com os óculos, *subir* SCO leva de centenas de ms a mais de
 * um segundo, porque envolve negociação com o dispositivo. O agente não ouve isso
 * como latência: ouve como o começo da palavra faltando.
 *
 * O caso que dói mais é justamente o que o aceite (b) da Fase 1 mede. Um P1 do
 * rádio corta a fala do copiloto; a fala cai, a rota cai junto, e o earcon de
 * emergência **espera a rota subir de novo para soar**. O alerta mais urgente do
 * produto era o que mais pagava por essa troca.
 *
 * ## Por que carência, e não "deixar aberta"
 *
 * Manter SCO de pé o turno inteiro custa bateria e mantém o telefone em
 * `MODE_IN_COMMUNICATION` sem motivo. Manter só durante a frase paga a remontagem
 * a cada som. A carência resolve os dois: a rota sobrevive ao intervalo entre
 * emissões encadeadas e cai sozinha quando o copiloto realmente se calou.
 *
 * [CARENCIA_MS] é maior que qualquer intervalo entre emissões da mesma fila
 * (síntese e enfileiramento ficam na casa das dezenas de ms) e menor que o tempo
 * que um agente leva para fazer a pergunta seguinte.
 *
 * ## O que ela NÃO muda
 *
 * A contagem de usuários continua sendo de [GlassesAudioManagerImpl]: esta classe
 * segura **um** usuário, não a rota. Se o rádio subir a captura no meio, ele soma
 * o dele e a rota só cai quando os dois soltarem. Sustentar a saída nunca derruba
 * quem captura — que foi exatamente o defeito de duas instâncias resolvido antes,
 * e não vale a pena reintroduzir por outra porta.
 *
 * Segurar a rota **não é capturar**. O que captura é um `AudioRecord`, e ele
 * continua exigindo `GlassesAudioRoute` como prova — o compilador sustenta isso,
 * não esta classe.
 */
class RotaSustentada(
    private val audio: GlassesAudioManagerImpl,
    private val escopo: CoroutineScope,
    private val carenciaMs: Long = CARENCIA_MS,
) {

    private val mutex = Mutex()

    /** Verdadeiro quando esta classe tem **um** usuário contabilizado no manager. */
    private var segurando = false

    /** A soltura agendada. Cancelada por qualquer [emUso] que chegue dentro da carência. */
    private var soltura: Job? = null

    /**
     * Roda [bloco] com a rota garantida de pé, sem derrubá-la ao terminar.
     *
     * Devolve `false` quando não há rota — e aí o chamador **não** deve reproduzir.
     * Falha nunca vira silêncio calado: quem chama registra o aviso, como antes.
     *
     * A soltura é agendada no `finally`, então ela acontece mesmo se [bloco] for
     * cancelado no meio — que é o caso do P1 cortando a fala. O que muda é que a
     * soltura *agendada* será cancelada pelo earcon que chega em seguida, e a rota
     * atravessa o corte intacta.
     */
    suspend fun <T> emUso(bloco: suspend () -> T): T? {
        mutex.withLock {
            soltura?.cancel()
            soltura = null
            if (!segurando) {
                when (val r = audio.iniciar()) {
                    is Result.Success -> segurando = true
                    is Result.Failure -> {
                        Log.w(TAG, "sem rota de áudio: ${r.error}")
                        return null
                    }
                }
            }
        }
        return try {
            bloco()
        } finally {
            agendarSoltura()
        }
    }

    /**
     * Agenda a queda da rota para daqui a [carenciaMs].
     *
     * Roda no escopo do processo, e não no de quem chamou: se ficasse no escopo da
     * reprodução, o cancelamento que corta a fala levaria a soltura junto e a rota
     * ficaria de pé para sempre — o vazamento silencioso que a carência existe
     * para evitar.
     */
    private fun agendarSoltura() {
        soltura?.cancel()
        soltura = escopo.launch {
            delay(carenciaMs)
            mutex.withLock {
                if (segurando) {
                    audio.liberar()
                    segurando = false
                }
            }
        }
    }

    /** Solta agora, sem carência. Para o desligamento do dono do processo. */
    suspend fun soltarAgora() = mutex.withLock {
        soltura?.cancel()
        soltura = null
        if (segurando) {
            audio.liberar()
            segurando = false
        }
    }

    companion object {

        /**
         * 2 s. Cobre com folga o intervalo entre emissões encadeadas (earcon →
         * frase, ou fala cortada → earcon de P1, ambos na casa das dezenas de ms)
         * sem manter o HFP de pé enquanto o agente pensa na pergunta seguinte.
         */
        const val CARENCIA_MS = 2_000L

        private const val TAG = "ClaryonField"
    }
}
