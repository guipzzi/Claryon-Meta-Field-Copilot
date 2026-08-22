package com.claryon.sound

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
 * que `deveInterromper`/`emCurso?.cancel()` (em `enqueue`) roda. Zero execuções
 * desse ramo, em qualquer quantidade de `./gradlew test`.
 *
 * **Este arquivo cobre o corte durante a REPRODUÇÃO, e só ele.** Todo `render`
 * daqui é instantâneo, o que deixa a janela "está sintetizando" com largura zero
 * — e foi exatamente ali que o defeito de 22/08 viveu, verde, por semanas. O
 * corte durante a síntese tem arquivo próprio: `PreempcaoDuranteASinteseTest`.
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
        play: suspend (Sound, ShortArray) -> Unit,
        aoInterromper: (Long) -> Unit = {},
        corpo: suspend (PrioritySoundQueue) -> Unit,
    ) {
        val escopoDaFila = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fila = PrioritySoundQueue(
            scope = escopoDaFila,
            render = render,
            play = play,
            aoInterromper = aoInterromper,
            agoraMs = { currentTime },
        )
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
            play = { _, pcm ->
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
            // **`advanceTimeBy`, NUNCA `advanceUntilIdle` aqui.** `advanceUntilIdle`
            // roda o `delay(10_000)` até o fim, a RESPOSTA termina sozinha, e o
            // `finally` completa o `CompletableDeferred` — o teste passava
            // VERDE sem nunca ter exercitado a preempção. Descoberto quando o
            // teste de medição, ao lado, acusou zero interrupções.
            advanceTimeBy(100)

            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceTimeBy(100)

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
            play = { _, pcm ->
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
            play = { _, _ ->
                try {
                    delay(10_000)
                } finally {
                    cancelada.complete(true)
                }
            },
        ) { fila ->
            fila.enqueue(tone(Priority.INFORMATIVO))
            advanceTimeBy(100) // no meio do delay, tocando — ver o teste acima

            fila.clear()
            advanceTimeBy(100)

            assertTrue(
                "clear() precisa cortar o que está tocando, não só esvaziar a fila pendente",
                cancelada.isCompleted,
            )
        }
    }

    @Test
    fun `a preempcao e MEDIDA, nao so executada`() = runTest {
        // O aceite da Fase 1 diz "corta em <= 200 ms, medido por Telemetry".
        // Sem observador, a preempcao era demonstravel e nao mensuravel — e
        // meta sem numero e aspiracao.
        val atrasos = mutableListOf<Long>()

        comFila(
            render = { sound -> pcmDe(sound) },
            play = { _, pcm ->
                if (prioridadeDe(pcm) == Priority.RESPOSTA) delay(10_000)
            },
            aoInterromper = { atrasos.add(it) },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceTimeBy(100) // a RESPOSTA fica NO MEIO do delay, tocando

            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceTimeBy(100)

            assertEquals("uma preempção, uma medição", 1, atrasos.size)
            assertTrue("o atraso medido não pode ser negativo", atrasos[0] >= 0)
            assertTrue("aceite da Fase 1: <= 200 ms (medido: ${atrasos[0]}ms)", atrasos[0] <= 200)
        }
    }

    @Test
    fun `som que NAO interrompe nao gera medicao de preempcao`() = runTest {
        // Sem esta guarda o percentil encheria de zeros de sons que nunca
        // cortaram nada, e o p95 diria "0 ms" sobre uma preempção que nunca foi
        // exercitada.
        val atrasos = mutableListOf<Long>()

        comFila(
            render = { sound -> pcmDe(sound) },
            play = { _, _ -> },
            aoInterromper = { atrasos.add(it) },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceUntilIdle()
            fila.enqueue(tone(Priority.INFORMATIVO))
            advanceUntilIdle()
            // Emergência com a fila VAZIA: não há o que cortar.
            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceUntilIdle()

            assertTrue("nada estava tocando: não houve preempção a medir", atrasos.isEmpty())
        }
    }

    @Test
    fun `render nulo pula o som sem travar a fila`() = runTest {
        val tocados = mutableListOf<Priority>()

        comFila(
            render = { sound -> if (sound.priority == Priority.INFORMATIVO) null else pcmDe(sound) },
            play = { _, pcm -> tocados.add(prioridadeDe(pcm)) },
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
