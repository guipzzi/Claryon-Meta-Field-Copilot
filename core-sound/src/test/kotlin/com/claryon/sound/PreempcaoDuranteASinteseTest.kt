package com.claryon.sound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A janela que `PrioritySoundQueueTest` não conseguia enxergar.**
 *
 * Aquele arquivo prova que uma emergência corta o que está **tocando**. Os seis
 * testes dele usam `render = { pcmDe(sound) }` — instantâneo —, e com síntese
 * instantânea o intervalo "está sintetizando" tem largura **zero**. Verde sobre
 * um estado que produção ocupa cerca de um segundo a cada frase falada.
 *
 * Dentro dessa janela o defeito era total: o `render` rodava fora do escopo de
 * reprodução e `prioridadeEmCurso` só era publicada depois dele, então
 * `deveInterromper(EMERGENCIA, null)` respondia `false`. A emergência não
 * preemptava nada, a síntese terminava, a fala tocava **inteira** e só então o P1
 * soava: ~10,9 s contra os ≤ 200 ms do aceite (b) da Fase 1.
 *
 * ## Por que a síntese aqui é NÃO CANCELÁVEL
 *
 * Porque a de produção também é. O Piper é sherpa-onnx por JNI: `cancel()` de
 * corrotina é cooperativo e uma chamada nativa em curso simplesmente não o
 * observa. Um teste com `delay` cancelável passaria com um conserto que apenas
 * cancelasse o `render` — conserto que em campo não cortaria coisa alguma.
 * [sinteseNaoCancelavel] usa `withContext(NonCancellable)`, o mais próximo que
 * Kotlin puro chega de "o nativo devolve quando quiser": o corte tem de acontecer
 * **sem** a colaboração da síntese, que é o que o aceite exige.
 *
 * A fila roda num escopo **próprio**, e não no do `runTest`, pelo mesmo motivo
 * documentado em `PrioritySoundQueueTest`: `init` lança um laço que só termina
 * quando o escopo morre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreempcaoDuranteASinteseTest {

    private fun tone(p: Priority) = Sound.Tone(Earcon.OUVI_VOCE, p)

    /** PCM de um elemento só, carregando o ordinal da prioridade. */
    private fun pcmDe(sound: Sound): ShortArray = shortArrayOf(sound.priority.ordinal.toShort())
    private fun prioridadeDe(pcm: ShortArray): Priority = Priority.entries[pcm[0].toInt()]

    /**
     * Simula a chamada JNI do Piper: o `cancel()` chega, o corpo **ignora**, e a
     * corrotina só desenrola quando o "nativo" devolve. É a diferença entre o
     * conserto funcionar e o conserto parecer funcionar.
     */
    private suspend fun sinteseNaoCancelavel(duracaoMs: Long = SINTESE_MS) =
        withContext(NonCancellable) { delay(duracaoMs) }

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

    /** Síntese lenta só para a RESPOSTA; a emergência é earcon, e earcon é cache. */
    private suspend fun renderComFalaLenta(sound: Sound): ShortArray {
        if (sound.priority == Priority.RESPOSTA) sinteseNaoCancelavel()
        return pcmDe(sound)
    }

    @Test
    fun `emergencia que chega durante a sintese nao espera a sintese terminar`() = runTest {
        val tocados = mutableListOf<Pair<Priority, Long>>()

        comFila(
            render = { sound -> renderComFalaLenta(sound) },
            play = { _, pcm ->
                val prioridade = prioridadeDe(pcm)
                tocados.add(prioridade to currentTime)
                if (prioridade == Priority.RESPOSTA) delay(FALA_MS)
            },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceTimeBy(100)
            // Montagem do cenário, não o aceite: se isto falhar, o teste seguinte
            // estaria medindo preempção de reprodução — que já tem dono.
            assertTrue(
                "a RESPOSTA tem de estar NA SÍNTESE, não tocando (síntese leva ${SINTESE_MS}ms)",
                tocados.isEmpty(),
            )

            val chegada = currentTime
            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceTimeBy(TETO_DO_ACEITE_MS)

            val emergencia = tocados.firstOrNull { it.first == Priority.EMERGENCIA }
            assertNotNull(
                "a EMERGENCIA não soou dentro de ${TETO_DO_ACEITE_MS}ms: a fila ficou presa " +
                    "esperando a síntese da RESPOSTA terminar (${SINTESE_MS}ms) — o defeito de volta",
                emergencia,
            )
            assertTrue(
                "aceite (b) da Fase 1: ≤ ${TETO_DO_ACEITE_MS}ms. Medido: ${emergencia!!.second - chegada}ms",
                emergencia.second - chegada <= TETO_DO_ACEITE_MS,
            )
            advanceUntilIdle() // deixa a síntese órfã terminar antes de encerrar
        }
    }

    @Test
    fun `a fala cortada durante a sintese nunca chega ao alto-falante`() = runTest {
        // O irmão do teste acima, e o que prova o ABANDONO: a síntese não pode ser
        // cancelada, então ela termina e devolve PCM depois do corte. Esse PCM não
        // pode encontrar ninguém para tocá-lo.
        val tocados = mutableListOf<Priority>()

        comFila(
            render = { sound -> renderComFalaLenta(sound) },
            play = { _, pcm -> tocados.add(prioridadeDe(pcm)) },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceTimeBy(100)

            fila.enqueue(tone(Priority.EMERGENCIA))
            // Até o fim de tudo: a síntese órfã completa em ${SINTESE_MS}ms e o PCM
            // dela chega DEPOIS da emergência já ter tocado.
            advanceUntilIdle()

            assertEquals(
                "a fala preemptada durante a síntese não pode tocar nem depois, quando o " +
                    "PCM abandonado ficar pronto",
                listOf(Priority.EMERGENCIA),
                tocados,
            )
        }
    }

    @Test
    fun `a preempcao durante a sintese e medida`() = runTest {
        // Sem isto o conserto seria demonstrável e não mensurável — e é o número,
        // não o teste, que `TelemetriaDoCicloDeVoz` publica no relatório.
        val atrasos = mutableListOf<Long>()

        comFila(
            render = { sound -> renderComFalaLenta(sound) },
            play = { _, pcm -> if (prioridadeDe(pcm) == Priority.RESPOSTA) delay(FALA_MS) },
            aoInterromper = { atrasos.add(it) },
        ) { fila ->
            fila.enqueue(tone(Priority.RESPOSTA))
            advanceTimeBy(100) // a RESPOSTA está sintetizando

            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceTimeBy(TETO_DO_ACEITE_MS)

            assertEquals(
                "cortar durante a síntese é preempção e tem de aparecer na telemetria",
                1,
                atrasos.size,
            )
            assertTrue("o atraso medido não pode ser negativo", atrasos[0] >= 0)
            assertTrue(
                "aceite (b) da Fase 1: ≤ ${TETO_DO_ACEITE_MS}ms (medido: ${atrasos[0]}ms)",
                atrasos[0] <= TETO_DO_ACEITE_MS,
            )
            advanceUntilIdle()
        }
    }

    @Test
    fun `resposta que chega durante a sintese da emergencia nao corta`() = runTest {
        // O contrapeso: publicar a prioridade ANTES da síntese tornou visível um
        // estado que não existia. Se a visibilidade nova virasse "tudo é
        // preemptável", uma RESPOSTA passaria a cortar a síntese de um P1 — que é
        // o inverso exato da regra.
        val tocados = mutableListOf<Priority>()
        val atrasos = mutableListOf<Long>()

        comFila(
            render = { sound ->
                if (sound.priority == Priority.EMERGENCIA) sinteseNaoCancelavel()
                pcmDe(sound)
            },
            play = { _, pcm -> tocados.add(prioridadeDe(pcm)) },
            aoInterromper = { atrasos.add(it) },
        ) { fila ->
            fila.enqueue(tone(Priority.EMERGENCIA))
            advanceTimeBy(100) // a EMERGENCIA está sintetizando

            fila.enqueue(tone(Priority.RESPOSTA))
            advanceUntilIdle()

            assertEquals(
                "a emergência sintetiza em paz e toca primeiro; a resposta espera a vez",
                listOf(Priority.EMERGENCIA, Priority.RESPOSTA),
                tocados,
            )
            assertTrue("nada foi cortado: não há preempção a medir", atrasos.isEmpty())
        }
    }

    private companion object {
        /** Ordem de grandeza da síntese real do Piper para uma frase curta. */
        const val SINTESE_MS = 1_000L

        /** Ordem de grandeza de uma fala do copiloto já sintetizada. */
        const val FALA_MS = 10_000L

        /** Aceite (b) da Fase 1, em `ROADMAP.md`. */
        const val TETO_DO_ACEITE_MS = 200L
    }
}
