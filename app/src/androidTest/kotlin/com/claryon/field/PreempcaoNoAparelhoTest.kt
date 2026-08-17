package com.claryon.field

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.Utterance
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.voice.TelemetriaDoCicloDeVoz
import com.claryon.field.voice.VoiceOutput
import com.claryon.voice.PcmAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O aceite (b) da Fase 1, medido no aparelho.**
 *
 * "Um P1 chegando durante fala do copiloto corta a fala em ≤ 200 ms, medido por
 * Telemetry."
 *
 * Este teste existe porque o número **não aparecia em uso normal do emulador**: o
 * ciclo de voz precisa de entrada de áudio real para o VAD fechar uma janela, e o
 * emulador não tem microfone com voz. O relatório mostrava "sem amostras" — que é
 * honesto, e é inútil como prova.
 *
 * Aqui o cenário é montado à mão, mas com as peças **de produção**: a
 * `VoiceOutput` real, a `PrioritySoundQueue` real, o `AudioTrack` real pela rota
 * do aparelho. O que se mede é o caminho que roda em campo, não uma simulação
 * dele.
 *
 * A fala longa é sintetizada como PCM direto (2 s de tom) em vez de passar pelo
 * Piper: carregar o modelo levaria segundos e o que está sob medição é a
 * PREEMPÇÃO, não a síntese.
 */
@RunWith(AndroidJUnit4::class)
class PreempcaoNoAparelhoTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var escopo: CoroutineScope? = null

    @After
    fun encerrar() {
        escopo?.coroutineContext?.get(Job)?.cancel()
        escopo = null
        AudioDoAgente.de(ctx).liberarTudo()
    }

    /** 2 s de tom a 16 kHz — a "fala longa" que o P1 tem de cortar. */
    private fun falaLonga(): PcmAudio = PcmAudio(
        samples = ShortArray(16_000 * 2) { i ->
            (Math.sin(2 * Math.PI * 300.0 * i / 16_000) * 8_000).toInt().toShort()
        },
        sampleRateHz = 16_000,
    )

    @Test
    // `: Unit` explícito — `runBlocking` devolve o valor do bloco, e o JUnit
    // recusa método de teste que não retorne void. A falha aparece como
    // `initializationError`, sem apontar o método culpado. Mesma armadilha
    // documentada em `MediaCodecOpusTest`.
    fun p1CortaFalaEmCurso_eOTempoEhMedido(): Unit = runBlocking {
        val telemetria = TelemetriaDoCicloDeVoz()
        val audio = AudioDoAgente.de(ctx)
        val meuEscopo = CoroutineScope(Dispatchers.Main.immediate + Job()).also { escopo = it }
        val rota = com.claryon.field.audio.RotaSustentada(audio, meuEscopo)

        val saida = VoiceOutput(
            scope = meuEscopo,
            sintetizar = { falaLonga() },
            reproduzir = { pcm, sr ->
                // **A mesma `RotaSustentada` que produção usa.** Antes o teste
                // fazia `iniciar`/`liberar` por emissão, e media 286 ms —
                // dos quais 204 ms eram o desmonte da rota SCO acontecendo
                // DEPOIS do som já ter parado. O número era verdadeiro e o
                // caminho era o defeito: a rota caía entre a fala cortada e o
                // earcon de P1, que então esperava a remontagem para soar.
                //
                // Medir um caminho que produção não percorre mais certificaria
                // uma arquitetura que não existe.
                val tocou = rota.emUso { audio.reproduzir(pcm, sr) }
                // Sem rota (emulador sem fone): simula a duração, porque o que
                // se mede é o cancelamento da corrotina, não o som.
                if (tocou == null) delay(2_000)
            },
            aoInterromperPorEmergencia = { atraso -> telemetria.registrarPreempcao(atraso) },
        )

        // 1) Fala longa (RESPOSTA) entra e começa a tocar.
        saida.emitir(Utterance.Falar("resposta longa do copiloto", Priority.RESPOSTA))
        delay(700) // tempo real: a fila renderiza e o AudioTrack começa

        // 2) P1 do rádio chega no meio dela.
        saida.emitir(Utterance.Sinalizar(Earcon.PRIORITARIA, Priority.EMERGENCIA))
        delay(500)

        val m = telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.PREEMPCAO_DE_EMERGENCIA)
        assertNotNull(
            "nenhuma preempção foi medida — o P1 não cortou a fala em curso",
            m,
        )
        assertTrue("esperava 1 amostra, veio ${m!!.amostras}", m.amostras >= 1)
        assertTrue(
            "aceite (b) da Fase 1: corte em ≤ 200 ms. Medido no aparelho: p50=${m.p50}ms pior=${m.pior}ms",
            m.pior <= 200,
        )
        android.util.Log.i("ClaryonField", "PREEMPÇÃO MEDIDA NO APARELHO: $m")
    }
}
