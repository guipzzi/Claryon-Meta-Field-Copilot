package com.claryon.field.voice

import com.claryon.agent.ActionOutcome
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.Prioridade
import com.claryon.agent.Utterance
import com.claryon.common.Earcon
import com.claryon.voice.EnergyVoiceActivityDetector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica a ORQUESTRAÇÃO do ciclo de voz de ponta a ponta (STT fake; whisper e
 * Piper já são provados nos testes instrumentados).
 *
 * O que importa aqui não é só "o ciclo roda": é que **a ação acontece antes da
 * fala**, e que a fala é derivada do resultado. Os dois primeiros testes provam
 * isso de ângulos diferentes — ordem e conteúdo.
 */
class VoiceCycleTest {

    /** Fala (tom alto) cercada de silêncio, para o VAD abrir e fechar a janela. */
    private fun framesDeFala() = buildList {
        repeat(3) { add(ShortArray(320)) }
        repeat(8) { add(ShortArray(320) { 4000 }) }
        repeat(6) { add(ShortArray(320)) }
    }

    /** Executor que registra a ordem das chamadas e devolve um resultado fixo. */
    private class ExecutorEspiao(
        private val resultado: ActionOutcome,
        val eventos: MutableList<String>,
    ) : IntentExecutor {
        var intentRecebida: Intent? = null
        override suspend fun execute(intent: Intent): ActionOutcome {
            intentRecebida = intent
            eventos.add("execute")
            return resultado
        }
    }

    private fun ciclo(
        executor: IntentExecutor,
        eventos: MutableList<String>,
        emitidas: MutableList<Utterance>,
        transcricao: String = "pedir apoio suspeito armado",
    ) = VoiceCycle(
        pcmInput = { flowOf(*framesDeFala().toTypedArray()) },
        vad = EnergyVoiceActivityDetector(16_000, 500.0, hangoverMs = 60, minSpeechMs = 20),
        sttFn = { _, _ -> transcricao },
        router = DeterministicIntentRouter(),
        executor = executor,
        emitir = { u ->
            emitidas.add(u)
            eventos.add(if (u is Utterance.Sinalizar && u.earcon == Earcon.OUVI_VOCE) "earcon" else "resposta")
        },
        sampleRateHz = 16_000,
    )

    @Test
    fun aAcaoAconteceAntesDaFala() = runBlocking {
        val eventos = mutableListOf<String>()
        val emitidas = mutableListOf<Utterance>()
        val executor = ExecutorEspiao(ActionOutcome.ApoioTransmitido(4), eventos)

        ciclo(executor, eventos, emitidas).runOnce()

        // O earcon de escuta vem primeiro (fechamento do VAD), a ação depois, e
        // só então a resposta. Inverter os dois últimos é o defeito que este
        // ciclo existe para tornar impossível.
        assertEquals(listOf("earcon", "execute", "resposta"), eventos)
    }

    @Test
    fun mesmaIntencao_resultadosDiferentes_falasDiferentes() = runBlocking {
        // A prova de que a fala deriva do RESULTADO e não do comando: a mesma
        // transcrição, com dois desfechos distintos, produz duas respostas.
        val transmitido = mutableListOf<Utterance>()
        val eventosA = mutableListOf<String>()
        ciclo(ExecutorEspiao(ActionOutcome.ApoioTransmitido(4), eventosA), eventosA, transmitido).runOnce()

        val enfileirado = mutableListOf<Utterance>()
        val eventosB = mutableListOf<String>()
        ciclo(ExecutorEspiao(ActionOutcome.ApoioEnfileirado, eventosB), eventosB, enfileirado).runOnce()

        val falaTransmitida = (transmitido.last() as Utterance.Falar).texto
        val falaEnfileirada = (enfileirado.last() as Utterance.SinalizarEFalar).texto

        assertEquals("Quatro unidades receberam.", falaTransmitida)
        assertEquals("Sem rede. Na fila.", falaEnfileirada)
    }

    @Test
    fun oRoteadorAindaEntendeOComando() = runBlocking {
        val eventos = mutableListOf<String>()
        val executor = ExecutorEspiao(ActionOutcome.ApoioTransmitido(1), eventos)

        val r = ciclo(executor, eventos, mutableListOf()).runOnce()

        assertTrue("intenção esperada PedirApoio", r.intent is Intent.PedirApoio)
        assertEquals(Prioridade.EMERGENCIA, (r.intent as Intent.PedirApoio).prioridade)
        // O executor recebeu exatamente a intenção roteada — sem tradução no meio.
        assertEquals(r.intent, executor.intentRecebida)
    }

    @Test
    fun earconDeEscutaDisparaNoFechamentoDoVad_antesDoStt() = runBlocking {
        val eventos = mutableListOf<String>()
        val emitidas = mutableListOf<Utterance>()
        var sttChamado = false

        val cycle = VoiceCycle(
            pcmInput = { flowOf(*framesDeFala().toTypedArray()) },
            vad = EnergyVoiceActivityDetector(16_000, 500.0, hangoverMs = 60, minSpeechMs = 20),
            sttFn = { _, _ ->
                // Quando o STT roda, o earcon JÁ tem de ter saído: é a promessa
                // de "≤ 500 ms do fim da fala até o agente saber que foi ouvido".
                assertTrue("earcon deve preceder o STT", emitidas.isNotEmpty())
                sttChamado = true
                "modo ocorrência"
            },
            router = DeterministicIntentRouter(),
            executor = ExecutorEspiao(ActionOutcome.NaoEntendi, eventos),
            emitir = { emitidas.add(it) },
            sampleRateHz = 16_000,
        )
        cycle.runOnce()

        assertTrue(sttChamado)
        val primeiro = emitidas.first()
        assertTrue(primeiro is Utterance.Sinalizar)
        assertEquals(Earcon.OUVI_VOCE, (primeiro as Utterance.Sinalizar).earcon)
    }
}
