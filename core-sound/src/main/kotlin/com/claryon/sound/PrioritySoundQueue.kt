package com.claryon.sound

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Fila de som com prioridade — o **mecanismo** de reprodução. A política (ordem,
 * supressão em Modo Tático, interrupção por emergência) vive no [SoundScheduler]
 * (puro e testado); aqui só conduzimos a reprodução com coroutines.
 *
 * @param render converte um [Sound] em PCM (earcon → [EarconSynthesizer]; fala →
 *   TTS). `null` = pular.
 * @param play reproduz o PCM (AudioTrack via GlassesAudioManager). Suspende até o
 *   fim; é cancelado quando uma **emergência** interrompe. Recebe também o
 *   [Sound] de origem: quem reproduz precisa saber **o que** está tocando para
 *   marcar telemetria de reprodução (earcon vs. fala) sem ter de adivinhar pelo
 *   tamanho do PCM.
 */
class PrioritySoundQueue(
    private val scope: CoroutineScope,
    private val render: suspend (Sound) -> ShortArray?,
    private val play: suspend (Sound, ShortArray) -> Unit,
    /**
     * Chamado quando uma emergência **de fato corta** um som em curso, com o
     * tempo entre o `enqueue` da emergência e o cancelamento do job que tocava.
     *
     * É o instrumento do aceite "um P1 chegando durante fala do copiloto corta a
     * fala em ≤ 200 ms". Sem ele, a preempção era demonstrável por teste
     * unitário e **não mensurável** — e meta sem número é aspiração.
     */
    private val aoInterromper: (atrasoMs: Long) -> Unit = {},
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) : SoundQueue {

    private val scheduler = SoundScheduler()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var playing: Job? = null

    @Volatile
    private var playingPriority: Priority? = null

    init {
        scope.launch { loop() }
    }

    override fun enqueue(sound: Sound) {
        // Fora do `launch`: é o instante em que a emergência CHEGOU, não o
        // instante em que a corrotina foi escalonada. Medir lá dentro esconderia
        // justamente a espera que interessa quando a Main está ocupada.
        val chegada = agoraMs()
        scope.launch {
            // Tudo sob o mesmo lock que o laço usa para publicar `playing`/
            // `playingPriority`: ler a prioridade nova e cancelar o job antigo em
            // dois passos fazia a emergência cancelar um job JÁ CONCLUÍDO,
            // deixando o som de menor prioridade tocar até o fim — quebrando
            // "nível 1 interrompe tudo".
            var interrompeu = false
            mutex.withLock {
                if (!scheduler.offer(sound)) return@launch
                if (scheduler.deveInterromper(sound.priority, playingPriority)) {
                    playing?.cancel()
                    interrompeu = true
                }
            }
            // Fora do lock: o observador é do chamador e não pode segurar a fila.
            if (interrompeu) aoInterromper(agoraMs() - chegada)
            wake.trySend(Unit)
        }
    }

    override fun setTacticalMode(enabled: Boolean) {
        scope.launch { mutex.withLock { scheduler.setTactical(enabled) } }
    }

    override fun clear() {
        scope.launch {
            mutex.withLock { scheduler.clear() }
            playing?.cancel()
        }
    }

    /**
     * Laço de reprodução. **Nenhuma exceção pode escapar daqui**: esta fila é o
     * único canal de saída do produto, e deixá-la morrer levaria junto os
     * earcons de emergência — em silêncio, que é exatamente o que o protocolo
     * proíbe. Cada iteração isola a própria falha e segue para o próximo som.
     *
     * A reprodução roda num escopo-filho com [SupervisorJob]: se o `AudioTrack`
     * estourar (rota derrubada no meio), a falha morre no filho e não sobe para
     * o escopo do chamador.
     */
    private suspend fun loop() {
        val playScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        while (coroutineContext.isActive) {
            try {
                val next = mutex.withLock { scheduler.poll() }
                if (next == null) {
                    wake.receive()
                    continue
                }
                val pcm = render(next) ?: continue
                val job = playScope.launch {
                    try {
                        play(next, pcm)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Falha ao reproduzir ${descrever(next)}", e)
                    }
                }
                mutex.withLock {
                    playing = job
                    playingPriority = next.priority
                }
                job.join() // pode ser cancelado por uma emergência
                mutex.withLock {
                    playing = null
                    playingPriority = null
                }
            } catch (e: CancellationException) {
                throw e // cancelamento do escopo: encerrar o laço de verdade
            } catch (e: Throwable) {
                Log.e(TAG, "Erro no laço da fila de som — seguindo para o próximo", e)
            }
        }
    }

    private fun descrever(sound: Sound): String = when (sound) {
        is Sound.Tone -> sound.earcon.name
        is Sound.Speech -> "fala"
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
