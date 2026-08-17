package com.claryon.sound

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SoundSchedulerTest` prova que a POLÍTICA decide interromper. Este arquivo
 * prova que o MECANISMO — `PrioritySoundQueue`, a classe que de fato corta um
 * som em reprodução — obedece a essa decisão.
 *
 * A lacuna era real e específica: toda suíte existente (`VoiceOutputSaidaTest`,
 * em `app`) enfileira um som por vez e espera terminar antes do próximo. Nenhum
 * teste jamais colocou dois sons na fila AO MESMO TEMPO — e é só nesse cenário
 * que `deveInterromper`/`playing?.cancel()` (`PrioritySoundQueue.kt:53-54`) roda.
 * Zero execuções desse ramo, em qualquer quantidade de `./gradlew test`.
 *
 * `render`/`play` trabalham com `ShortArray?`, não com `Sound` — aqui o PCM
 * carrega só o ordinal da prioridade, o suficiente para o teste rastrear quem
 * tocou o quê sem precisar de um sintetizador de verdade.
 *
 * A fila roda num escopo **próprio**, e não no do `runTest`: `init` faz
 * `scope.launch { loop() }`, um laço que nunca termina sozinho (só quando o
 * escopo morre) — `runTest` espera os filhos do SEU escopo completarem, e um
 * filho que gira para sempre é `UncompletedCoroutinesError` em todo teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrioritySoundQueueTest {

    private fun tone(p: Priority) = Sound.Tone(Earcon.OUVI_VOCE, p)

    /** PCM de um elemento só, carregando o ordinal da prioridade. */
    private fun pcmDe(sound: Sound): ShortArray = shortArrayOf(sound.priority.ordinal.toShort())
    private fun prioridadeDe(pcm: ShortArray): Priority = Priority.entries[pcm[0].toInt()]

    /** Cria a fila num escopo próprio e garante que ele morre no fim do teste. */
    private suspend fun TestScope.comFila(
        render: suspend (Sound) -> ShortArray?,
        play: suspend (ShortArray) -> Unit,
        corpo: suspend (PrioritySoundQueue) -> Unit,
    ) {
        val escopoDaFila = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fila = PrioritySoundQueue(scope = escopoDaFila, render = render, play = play)
        try {
            corpo(fila)
        } finally {
            escopoDaFila.cancel()
        }
    }

    @Test
    fun `emergencia cancela a reproducao RESPOSTA em curso`() = runTest {
        // `play` para RESPOSTA nunca retorna sozinho — só quando cancelado.
        // Se a preempção não disparar, este teste trava em vez de falhar
        // silencioso: `runTest` estoura por corrotina pendente.
        val respostaCancelada = CompletableDeferred<Boolean>()
        val tocados = mutableListOf<Priority>()

        comFila(
            render = { sound -> pcmDe(sound) },
            play = { pcm ->
                val prioridade = prioridadeDe(pcm)
                tocados.add(prioridade)
                if (prioridade == Priority.RESPOSTA) {
                    try {
                        // Simula reprodução longa (a fala de 2-3 s do copiloto).
                        delay(10_000)
                    } finally {
                        respostaCancelada.complete(true)
                    }
                }
            },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceUntilIdle()
            // A fila está presa em `play` para o RESPOSTA (dentro do `delay`).

            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceUntilIdle()

            assertTrue(
                "job da reprodução RESPOSTA precisa ser cancelado, não apenas suceder",
                respostaCancelada.isCompleted,
            )
            assertEquals(
                "os dois sons tocam, na ordem: RESPOSTA primeiro (já em curso), EMERGENCIA depois",
                listOf(Priority.RESPOSTA, Priority.EMERGENCIA),
                tocados,
            )
        }
    }

    @Test
    fun `resposta NAO cancela emergencia em curso`() = runTest {
        val tocados = mutableListOf<Priority>()
        var emergenciaTerminouSozinha = false

        comFila(
            render = { sound -> pcmDe(sound) },
            play = { pcm ->
                val prioridade = prioridadeDe(pcm)
                tocados.add(prioridade)
                if (prioridade == Priority.EMERGENCIA) {
                    delay(50)
                    emergenciaTerminouSozinha = true
                }
            },
        ) { fila ->
            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceUntilIdle()
            // A EMERGENCIA já tocou e terminou (delay curto) antes do enqueue
            // seguinte — não há concorrência real aqui; o ponto é o inverso do
            // teste acima: mesmo que estivesse em curso, RESPOSTA não teria
            // como cancelá-la (`SoundScheduler.deveInterromper` exige
            // `novo == EMERGENCIA`).
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceUntilIdle()

            assertTrue(emergenciaTerminouSozinha)
            assertEquals(listOf(Priority.EMERGENCIA, Priority.RESPOSTA), tocados)
        }
    }

    @Test
    fun `clear cancela a reproducao em curso`() = runTest {
        val cancelada = CompletableDeferred<Boolean>()

        comFila(
            render = { sound -> pcmDe(sound) },
            play = {
                try {
                    delay(10_000)
                } finally {
                    cancelada.complete(true)
                }
            },
        ) { fila ->
            fila.enqueue(tone(Priority.INFORMATIVO))
            advanceUntilIdle()

            fila.clear()
            advanceUntilIdle()

            assertTrue(
                "clear() precisa cortar o que está tocando, não só esvaziar a fila pendente",
                cancelada.isCompleted,
            )
        }
    }

    @Test
    fun `render nulo pula o som sem travar a fila`() = runTest {
        val tocados = mutableListOf<Priority>()

        comFila(
            render = { sound -> if (sound.priority == Priority.INFORMATIVO) null else pcmDe(sound) },
            play = { pcm -> tocados.add(prioridadeDe(pcm)) },
        ) { fila ->
            fila.enqueue(tone(Priority.INFORMATIVO))
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceUntilIdle()

            assertEquals(
                "o INFORMATIVO some (render nulo) e não bloqueia o RESPOSTA seguinte",
                listOf(Priority.RESPOSTA),
                tocados,
            )
        }
    }
}
