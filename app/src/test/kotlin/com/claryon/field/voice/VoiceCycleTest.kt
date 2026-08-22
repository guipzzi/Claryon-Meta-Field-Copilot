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
            eventos.add(
                when {
                    u is Utterance.Sinalizar && u.earcon == Earcon.CANAL_ABERTO -> "abriu"
                    u is Utterance.Sinalizar && u.earcon == Earcon.CANAL_FECHADO -> "fechou"
                    else -> "resposta"
                },
            )
        },
        sampleRateHz = 16_000,
    )

    @Test
    fun aAcaoAconteceAntesDaFala() = runBlocking {
        val eventos = mutableListOf<String>()
        val emitidas = mutableListOf<Utterance>()
        val executor = ExecutorEspiao(ActionOutcome.ApoioTransmitido(4), eventos)

        ciclo(executor, eventos, emitidas).runOnce()

        // A gramática inteira, na ordem: o canal abre ANTES de o agente falar, o
        // canal fecha quando ele para (fechamento do VAD), a ação acontece, e só
        // então a resposta. Inverter os dois últimos é o defeito que este ciclo
        // existe para tornar impossível; abrir o canal DEPOIS da fala seria avisar
        // que o microfone estava de pé quando o agente já falou por cima.
        assertEquals(listOf("abriu", "fechou", "execute", "resposta"), eventos)
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

    /**
     * **O `trimtrim` sai no fechamento do VAD, não depois do STT.**
     *
     * A promessa é *"≤ 500 ms do fim da fala até o agente saber que foi ouvido"*, e
     * o whisper leva ~420 ms sozinho. Se o earcon esperasse a transcrição, a meta
     * seria impossível por construção — e o agente ficaria em silêncio justamente
     * na janela em que ele mais precisa saber que o comando entrou.
     *
     * O teste também prende a ORDEM dos dois earcons de canal: `bipbip` na abertura
     * do microfone, `trimtrim` no fim da fala. Trocá-los inverteria a convenção do
     * rádio e ensinaria o agente a falar depois do sinal de fechar.
     */
    @Test
    fun oCanalFechaNoFechamentoDoVad_antesDoStt_eOCanalAbreAntesDaFala() = runBlocking {
        val eventos = mutableListOf<String>()
        val emitidas = mutableListOf<Utterance>()
        var earconsAntesDoStt = 0

        val cycle = VoiceCycle(
            pcmInput = { flowOf(*framesDeFala().toTypedArray()) },
            vad = EnergyVoiceActivityDetector(16_000, 500.0, hangoverMs = 60, minSpeechMs = 20),
            sttFn = { _, _ ->
                earconsAntesDoStt = emitidas.count { it is Utterance.Sinalizar }
                "modo ocorrência"
            },
            router = DeterministicIntentRouter(),
            executor = ExecutorEspiao(ActionOutcome.NaoEntendi, eventos),
            emitir = { emitidas.add(it) },
            sampleRateHz = 16_000,
        )
        cycle.runOnce()

        assertEquals(
            "os DOIS earcons de canal têm de preceder o STT — o de abertura porque " +
                "o agente ainda vai falar, o de fechamento porque a meta de 500 ms " +
                "não cabe depois do whisper",
            2,
            earconsAntesDoStt,
        )
        val sinais = emitidas.filterIsInstance<Utterance.Sinalizar>()
        assertEquals(Earcon.CANAL_ABERTO, sinais[0].earcon)
        assertEquals(Earcon.CANAL_FECHADO, sinais[1].earcon)
    }

    /**
     * **Contra-teste da ordem: com o `bipbip` no lugar errado, o de cima passaria.**
     *
     * `earconsAntesDoStt == 2` sozinho não distingue "abriu, fechou" de "fechou,
     * abriu" — as duas sequências dão dois. É o par de `assertEquals` acima que
     * separa, e este teste existe para provar que ele separa: se alguém trocar os
     * dois `emitir` dentro de `runOnce`, a asserção de índice 0 é a que reprova.
     *
     * Escrito como asserção sobre a lista inteira, e não sobre a contagem, porque
     * contagem é exatamente a régua que o defeito satisfaz.
     */
    @Test
    fun aOrdemDosDoisEarconsDeCanalEParteDoContrato() = runBlocking {
        val emitidas = mutableListOf<Utterance>()
        val eventos = mutableListOf<String>()
        ciclo(ExecutorEspiao(ActionOutcome.NaoEntendi, eventos), eventos, emitidas).runOnce()

        assertEquals(
            listOf(Earcon.CANAL_ABERTO, Earcon.CANAL_FECHADO),
            emitidas.filterIsInstance<Utterance.Sinalizar>().map { it.earcon },
        )
        assertTrue(
            "o ciclo tem de terminar falando o resultado, não só sinalizando",
            emitidas.last() is Utterance.SinalizarEFalar || emitidas.last() is Utterance.Falar,
        )
    }
}
