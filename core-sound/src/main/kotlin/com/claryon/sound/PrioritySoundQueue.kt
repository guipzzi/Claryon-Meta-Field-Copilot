package com.claryon.sound

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * ## Síntese e reprodução são **um só** item em curso
 *
 * Até 22/08 o `render` rodava **fora** do escopo de reprodução e a prioridade só
 * era publicada **depois** dele. Consequência medível: uma EMERGÊNCIA que chegava
 * enquanto o Piper sintetizava uma fala de prioridade menor via
 * `prioridadeEmCurso == null`, e `SoundScheduler.deveInterromper` — que exige
 * `atual != null` — respondia `false`. Não havia preempção nenhuma: a emergência
 * ia para a fila pendente, a síntese terminava, a fala **inteira** tocava, e só
 * então o P1 soava. Com síntese real (~1 s) e fala de ~10 s, o P1 esperava
 * ~10,9 s contra os ≤ 200 ms do aceite.
 *
 * Os seis testes de então usavam `render` **instantâneo**, o que dava à janela do
 * defeito largura zero — verdes sobre um caminho que produção percorre a cada
 * frase falada.
 *
 * O conserto tem três partes, e nenhuma delas sozinha basta:
 *  1. **Um único job cobre síntese + reprodução** ([reproduzir]). Existir na fila
 *     de execução já é "em curso", mesmo antes de sair som.
 *  2. **`poll()` e a publicação de [emCurso] acontecem sob o MESMO lock.** Em dois
 *     passos, uma emergência que chegasse na fresta entre eles não veria nada em
 *     curso e esperaria a fala terminar — o mesmo defeito, por outra porta.
 *  3. O job nasce [CoroutineStart.LAZY] e só é iniciado **depois** de publicado:
 *     é o que garante que nenhuma amostra de síntese roda antes de a prioridade
 *     estar visível a quem cancela.
 *
 * ## Por que ABANDONAR a síntese, e não cancelá-la
 *
 * **`cancel()` de corrotina não interrompe uma chamada JNI em curso.** `delay()`
 * é cancelável porque é cooperativo; o Piper (sherpa-onnx) é nativo — uma vez
 * dentro do `synthesize`, a thread só volta quando o nativo devolve. Um conserto
 * que dependesse de o `render` parar quando mandado funcionaria no teste e seria
 * mentira em campo.
 *
 * Por isso a síntese roda numa corrotina **irmã** ([renderScope]), não filha do
 * job de reprodução, e o job apenas a **aguarda**. `await()` é cancelável ainda
 * que o corpo aguardado não seja: ao ser cortado, o job desiste imediatamente e a
 * síntese fica órfã, terminando quando o nativo bem entender — **noutra
 * corrotina, sem segurar a fila**. O PCM que chegar depois não tem quem o toque e
 * morre ali. É a diferença entre "parar a síntese" (impossível) e "parar de
 * esperar por ela" (que é o que o aceite pede).
 *
 * Preço, declarado e não escondido: a síntese abandonada continua queimando CPU
 * até o nativo retornar, e se o motor de TTS não for reentrante, a síntese da
 * própria emergência pode enfileirar **dentro do motor**. Nada disto atrasa o
 * corte do que estava em curso — atrasa, no pior caso, o início da fala da
 * emergência, que em P1 é quase sempre earcon (`EarconSynthesizer`, cache em
 * memória, microssegundos).
 *
 * **Contrato do `render`:** ele precisa *suspender*, não *bloquear a thread do
 * laço. `PiperTts.synthesize` faz `withContext(Dispatchers.Default)` e cumpre. Um
 * `render` que rode trabalho pesado na thread do chamador anula o abandono — e
 * isso **aparece** no número de [aoInterromper], que mede até a corrotina
 * desenrolar de fato.
 *
 * @param render converte um [Sound] em PCM (earcon → [EarconSynthesizer]; fala →
 *   TTS). `null` = pular. Ver o contrato acima: suspender, nunca bloquear.
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
     * tempo entre o `enqueue` da emergência e o instante em que o item anterior
     * **terminou de sair do caminho**.
     *
     * É o instrumento do aceite "um P1 chegando durante fala do copiloto corta a
     * fala em ≤ 200 ms". Sem ele, a preempção era demonstrável por teste
     * unitário e **não mensurável** — e meta sem número é aspiração.
     *
     * **Mede até o job encerrar, não até o `cancel` ser chamado.** A primeira
     * versão media chegada→`cancel` e era um limite inferior desonesto: o
     * `cancel` é cooperativo, e quem de fato silencia o alto-falante é o
     * `pause()`/`flush()` do `finally` da reprodução. O `join` abaixo espera
     * exatamente isso, então o número passou a ser chegada→silêncio.
     *
     * **Desde 22/08 o item em curso pode estar na SÍNTESE, não na reprodução.**
     * Nesse caso não havia som para calar e o número tende a zero — e é um zero
     * *verdadeiro*, não de conveniência: ele continua medindo chegada→fila livre
     * para a emergência, e denuncia um `render` que bloqueie a thread do laço em
     * vez de suspender, porque aí o desenrolar da corrotina demora e o número
     * cresce junto.
     */
    private val aoInterromper: (atrasoMs: Long) -> Unit = {},
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) : SoundQueue {

    private val scheduler = SoundScheduler()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Job do item em curso — **síntese e reprodução**, não só reprodução. */
    private var emCurso: Job? = null

    @Volatile
    private var prioridadeEmCurso: Priority? = null

    init {
        scope.launch { loop() }
    }

    override fun enqueue(sound: Sound) {
        // Fora do `launch`: é o instante em que a emergência CHEGOU, não o
        // instante em que a corrotina foi escalonada. Medir lá dentro esconderia
        // justamente a espera que interessa quando a Main está ocupada.
        val chegada = agoraMs()
        scope.launch {
            // Tudo sob o mesmo lock que o laço usa para publicar `emCurso`/
            // `prioridadeEmCurso`: ler a prioridade nova e cancelar o job antigo em
            // dois passos fazia a emergência cancelar um job JÁ CONCLUÍDO,
            // deixando o som de menor prioridade tocar até o fim — quebrando
            // "nível 1 interrompe tudo".
            var cortado: Job? = null
            mutex.withLock {
                if (!scheduler.offer(sound)) return@launch
                if (scheduler.deveInterromper(sound.priority, prioridadeEmCurso)) {
                    cortado = emCurso
                    emCurso?.cancel()
                }
            }
            // Fora do lock, e por dois motivos: o `join` espera o `finally` da
            // reprodução (pause + flush = silêncio de verdade), e o observador é
            // do chamador — nenhum dos dois pode segurar a fila.
            cortado?.let {
                it.join()
                aoInterromper(agoraMs() - chegada)
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
            emCurso?.cancel()
        }
    }

    /**
     * Laço de reprodução. **Nenhuma exceção pode escapar daqui**: esta fila é o
     * único canal de saída do produto, e deixá-la morrer levaria junto os
     * earcons de emergência — em silêncio, que é exatamente o que o protocolo
     * proíbe. Cada iteração isola a própria falha e segue para o próximo som.
     *
     * Síntese e reprodução rodam em escopos-filhos com [SupervisorJob]: se o
     * `AudioTrack` estourar (rota derrubada no meio) ou o TTS explodir, a falha
     * morre no filho e não sobe para o escopo do chamador. São **dois** escopos e
     * não um só para deixar explícito no tipo que a síntese não é filha da
     * reprodução — se fosse, cancelar a reprodução cancelaria a síntese, e o
     * `await` só voltaria quando o JNI voltasse. Ver o KDoc da classe.
     */
    private suspend fun loop() {
        val playScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val renderScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        while (coroutineContext.isActive) {
            try {
                // `poll` + publicação **atômicos**: ver parte 2 do conserto, no
                // KDoc da classe. O job nasce LAZY para não sintetizar nada
                // enquanto o lock ainda esconde a prioridade de quem cancela.
                val job = mutex.withLock {
                    val next = scheduler.poll()
                    if (next == null) {
                        null
                    } else {
                        playScope.launch(start = CoroutineStart.LAZY) {
                            reproduzir(next, renderScope)
                        }.also {
                            emCurso = it
                            prioridadeEmCurso = next.priority
                        }
                    }
                }
                if (job == null) {
                    wake.receive()
                    continue
                }
                job.start()
                job.join() // pode ser cancelado por uma emergência
                mutex.withLock {
                    emCurso = null
                    prioridadeEmCurso = null
                }
            } catch (e: CancellationException) {
                throw e // cancelamento do escopo: encerrar o laço de verdade
            } catch (e: Throwable) {
                Log.e(TAG, "Erro no laço da fila de som — seguindo para o próximo", e)
            }
        }
    }

    /**
     * Um item, do texto ao alto-falante. Roda no `playScope`, e é **este** job que
     * a emergência cancela — por isso ele engloba a síntese.
     *
     * O `catch` cobre síntese e reprodução: com o `render` aqui dentro, uma
     * exceção do TTS deixou de cair na rede do laço e passaria direto ao
     * `CoroutineExceptionHandler` do processo (o `SupervisorJob` impede que
     * derrube irmãos, não que vire crash). A rede tem de estar dentro do job.
     */
    private suspend fun reproduzir(sound: Sound, renderScope: CoroutineScope) {
        try {
            val pcm = sintetizarSemPrender(sound, renderScope) ?: return
            play(sound, pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao sintetizar ou reproduzir ${descrever(sound)}", e)
        }
    }

    /**
     * Sintetiza numa corrotina **irmã** e espera por ela de forma cancelável.
     *
     * O ponto inteiro é o `catch`: quando a emergência corta, `await()` levanta
     * na hora e **não esperamos o `render` terminar** — o que é a única forma de
     * cumprir os 200 ms com uma síntese JNI que não sabe ser interrompida. O
     * `cancel()` que se segue é cortesia para um `render` cooperativo (que use
     * `delay` ou olhe `isActive`); para o Piper ele não faz nada, e o KDoc da
     * classe diz exatamente isso.
     */
    private suspend fun sintetizarSemPrender(
        sound: Sound,
        renderScope: CoroutineScope,
    ): ShortArray? {
        val sintese = renderScope.async { render(sound) }
        try {
            return sintese.await()
        } catch (e: CancellationException) {
            // Órfã a partir daqui: ninguém mais lê o resultado dela. Falha nunca
            // é silêncio, nem numa síntese abandonada.
            sintese.invokeOnCompletion { causa ->
                if (causa != null && causa !is CancellationException) {
                    Log.e(TAG, "Síntese abandonada de ${descrever(sound)} falhou", causa)
                }
            }
            sintese.cancel()
            throw e
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
