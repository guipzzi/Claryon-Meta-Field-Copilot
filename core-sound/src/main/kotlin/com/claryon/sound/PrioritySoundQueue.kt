package com.claryon.sound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 *   fim; é cancelado quando uma **emergência** interrompe.
 */
class PrioritySoundQueue(
    private val scope: CoroutineScope,
    private val render: suspend (Sound) -> ShortArray?,
    private val play: suspend (ShortArray) -> Unit,
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
        scope.launch {
            val aceito = mutex.withLock { scheduler.offer(sound) }
            if (!aceito) return@launch
            // Emergência interrompe algo de menor prioridade tocando agora.
            if (scheduler.deveInterromper(sound.priority, playingPriority)) {
                playing?.cancel()
            }
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

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            val next = mutex.withLock { scheduler.poll() }
            if (next == null) {
                wake.receive()
                continue
            }
            val pcm = render(next) ?: continue
            playingPriority = next.priority
            val job = scope.launch { play(pcm) }
            playing = job
            runCatching { job.join() } // pode ser cancelado por uma emergência
            playingPriority = null
        }
    }
}
