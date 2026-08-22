package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.Utterance
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Telemetry
import com.claryon.field.audio.SaidaUnica
import com.claryon.field.voice.TelemetriaDoCicloDeVoz
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A junção que o teste JVM não alcança: o earcon REAL marca no ciclo certo?**
 *
 * `AtivacaoAteEarconTest` prova o mecanismo da telemetria com marcos injetados à
 * mão. O que ele não pode provar é o pedaço que só existe em runtime: quem chama
 * `marcarNoCicloCorrente(EARCON_PLAYED)` é o `SaidaUnica`, **de dentro do
 * reprodutor**, no instante em que o PCM entra no `AudioTrack`. Entre a emissão e
 * essa marca há uma fila de prioridade, a subida da rota de áudio e uma corrotina.
 *
 * Se qualquer elo aí estiver quebrado, a métrica de 500 ms fica em "sem amostras"
 * para sempre — sem erro, sem log, com a aparência de "ainda não medimos". Foi
 * exatamente essa aparência que a frase *"wake word: sem produtor"* manteve por
 * meses.
 *
 * Aqui o teste faz o que o `CopilotService` faz ao detectar: abre o ciclo, marca a
 * ativação e emite `DESPERTAR` pelo dono único da saída. Só o detector é
 * dispensado — o resto do caminho é o de produção.
 *
 * ### O que este teste NÃO afirma
 *
 * Ele não valida a meta de 500 ms. No emulador a rota cai no dispositivo padrão e
 * o `AudioTrack` aceita quase na hora — mediu **10 ms**, e esse número não tem
 * relação com o mundo. Em HFP de verdade o caro é a subida do canal SCO, que pode
 * levar centenas de milissegundos e que o emulador não exerce.
 *
 * A pergunta aqui é binária: **a métrica coleta?** O valor dela só vale medido com
 * óculos e fone reais, e isso está em `docs/VERIFICACOES_COM_HARDWARE.md`.
 */
@RunWith(AndroidJUnit4::class)
class MetricaDaAtivacaoNoAparelhoTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oEarconDeVerdadeFechaAMetricaDaAtivacao(): Unit = runBlocking {
        val tel: TelemetriaDoCicloDeVoz = SaidaUnica.telemetriaDoCiclo
        val antes = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON)?.amostras ?: 0

        // Exatamente a sequência de `CopilotService.aoDetectar`, e nesta ordem: o
        // ciclo TEM de existir antes do earcon, porque `SaidaUnica` marca no ciclo
        // corrente e um marco sem ciclo se perde em silêncio.
        val cicloId = "ativacao-teste-${antes + 1}"
        tel.abrirCiclo(cicloId)
        tel.mark(cicloId, Telemetry.Stage.WAKE_DETECTED, System.currentTimeMillis())

        // `DESPERTAR` e não outro earcon qualquer: `SaidaUnica.marcarReproducao`
        // tem uma LISTA FECHADA de tons que contam como marco de ciclo, e um
        // earcon de fora dela nunca fecharia esta métrica. O teste tem de emitir
        // o mesmo que a produção emite, senão ele prova outro caminho.
        SaidaUnica.de(app).emitir(Utterance.Sinalizar(Earcon.DESPERTAR, Priority.RESPOSTA))

        // A fila é assíncrona e a rota pode precisar subir. 5 s é folga larga para
        // um earcon de 520 ms; o que se mede é se ele CHEGA, não quanto demora.
        //
        // `while` e não `repeat { ... return@repeat }`: `return@repeat` sai da
        // LAMBDA, não do laço — ele continua iterando, só sem esperar. A primeira
        // versão deste teste fazia isso e rodava as 50 voltas sempre; funcionava
        // por acidente e mentia sobre a própria intenção para quem lesse.
        var depois = antes
        var tentativas = 0
        while (depois <= antes && tentativas < 50) {
            depois = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON)?.amostras ?: 0
            if (depois > antes) break
            delay(100)
            tentativas++
        }

        assertTrue(
            "o earcon real NÃO fechou a métrica da ativação: amostras $antes → $depois. " +
                "A cadeia emitir → fila → AudioTrack → marcarNoCicloCorrente está " +
                "rompida, e a meta de 500 ms ficaria 'sem amostras' para sempre",
            depois > antes,
        )

        val m = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON)
        assertNotNull(m)
        // Sanidade de ordem de grandeza: um número negativo ou de minutos diria que
        // os marcos vieram de relógios ou ciclos diferentes.
        assertTrue("medição implausível: $m", m!!.p50 in 0..30_000)
        android.util.Log.i("ClaryonField", "ATIVAÇÃO → EARCON (aparelho): $m")
    }
}
