package com.claryon.field.audio

import com.claryon.audio.GlassesAudioManager
import com.claryon.audio.FluxoDeReproducao
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **O P1 paga remontagem de SCO ao cortar a fala? Medido: não.**
 *
 * Ao consertar a preempção durante a síntese em 22/08, ficou uma ressalva aberta e
 * declarada: se o corte pega a síntese, a rota de áudio pode cair antes de o earcon de
 * emergência tocar — e subir SCO com os óculos custa de centenas de ms a mais de um
 * segundo, o que comeria o ganho inteiro do conserto.
 *
 * O KDoc de [RotaSustentada] **afirma** que não cai:
 *
 * > *"A soltura é agendada no `finally`, então ela acontece mesmo se o bloco for
 * > cancelado no meio — que é o caso do P1 cortando a fala. O que muda é que a soltura
 * > agendada será cancelada pelo earcon que chega em seguida, e a rota atravessa o
 * > corte intacta."*
 *
 * Afirmação em KDoc não é medição. Este teste mede.
 *
 * ## Por que não dava para medir antes
 *
 * [RotaSustentada] recebia `GlassesAudioManagerImpl` concreto, que precisa de
 * `Context`. Sem Robolectric e sem biblioteca de mock no módulo, a classe não tinha um
 * único teste em JVM. Trocado para a interface `GlassesAudioManager` — a classe só usa
 * `iniciar()` e `liberar()`, ambos dela.
 *
 * O emulador não serve para isto: `dumpsys audio` devolve `mBluetoothName=null` e
 * `SCO_STATE_INACTIVE`. **O custo em milissegundos da remontagem continua NÃO MEDIDO**
 * e só sai com fone HFP ou óculos reais. O que este teste decide é o anterior: se há
 * remontagem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotaAtravessaOCorteTest {

    /** Conta as subidas e quedas da rota. É a única coisa que precisamos observar. */
    private class AudioContado : GlassesAudioManager {
        var subidas = 0
        var quedas = 0
        override val taxaDeAmostragemHz = 16_000
        override suspend fun iniciar(): Result<GlassesAudioRoute> {
            subidas++
            return Result.Success(GlassesAudioRoute.paraTesteSomente())
        }
        override fun liberar() { quedas++ }

        // O resto da interface não participa desta medição.
        override fun microfonePcm(route: GlassesAudioRoute) = emptyFlow<ShortArray>()
        override suspend fun reproduzir(pcm: ShortArray, sampleRateHz: Int) = Result.Success(Unit)
        override fun abrirFluxoDeReproducao(sampleRateHz: Int) =
            object : FluxoDeReproducao {
                override suspend fun escrever(pcm: ShortArray) = Result.Success(Unit)
                override fun fechar() {}
            }
    }

    /**
     * **O caso do conserto de 22/08:** a fala está sendo sintetizada, o P1 chega e
     * corta, e o earcon de emergência toca em seguida.
     *
     * Se a rota caísse no corte, `subidas` seria 2 — e o segundo `iniciar()` é a
     * remontagem de SCO que o agente ouviria como o começo do alarme faltando.
     */
    @Test
    fun oCorteDuranteASintese_naoDerrubaARota() = runTest {
        val audio = AudioContado()
        val rota = RotaSustentada(audio, TestScope(testScheduler), carenciaMs = 2_000L)

        // A fala: entra na rota e é cancelada no meio, como o P1 faz.
        val fala = launch {
            rota.emUso {
                withContext(NonCancellable) { delay(50) } // a síntese, que o JNI não cancela
                delay(10_000)                             // a reprodução, que o corte pega
            }
        }
        advanceTimeBy(100)
        fala.cancel(CancellationException("P1 cortou"))
        advanceTimeBy(1)

        assertEquals("a rota subiu mais de uma vez antes mesmo do earcon", 1, audio.subidas)

        // O earcon de P1, imediatamente depois do corte — dentro da carência.
        rota.emUso { delay(30) }
        advanceTimeBy(50)

        assertEquals(
            "A rota foi remontada entre o corte e o earcon de emergência. Em SCO real " +
                "isso custa de centenas de ms a mais de um segundo, e o agente ouviria " +
                "o começo do alarme faltando — o ganho do conserto da preempção seria " +
                "devolvido inteiro.",
            1,
            audio.subidas,
        )
        assertEquals("a rota caiu dentro da carência", 0, audio.quedas)
    }

    /**
     * **E a carência não vaza:** passado o prazo sem uso, a rota cai.
     *
     * O outro lado do contra-teste. Uma rota que nunca cai não é economia, é vazamento:
     * SCO de pé o turno inteiro é justamente o que drena a bateria dos óculos.
     */
    @Test
    fun passadaACarenciaSemUso_aRotaCai() = runTest {
        val audio = AudioContado()
        val rota = RotaSustentada(audio, TestScope(testScheduler), carenciaMs = 2_000L)

        rota.emUso { delay(10) }
        advanceTimeBy(1_900)
        assertEquals("caiu antes da carência terminar", 0, audio.quedas)

        advanceTimeBy(200)
        assertEquals(
            "A rota NÃO caiu depois da carência. SCO de pé sem uso é o que drena a " +
                "bateria dos óculos — a carência existe para não trocar um defeito por outro.",
            1,
            audio.quedas,
        )
    }

    /**
     * **A janela tem tamanho, e ele é 2 s.** Se o earcon demorasse mais que a carência,
     * a remontagem voltaria — este teste registra o limite para quem for mexer no número.
     */
    @Test
    fun earconDepoisDaCarencia_pagaRemontagem_eIssoEOLimiteConhecido() = runTest {
        val audio = AudioContado()
        val rota = RotaSustentada(audio, TestScope(testScheduler), carenciaMs = 2_000L)

        val fala = launch { rota.emUso { delay(10_000) } }
        advanceTimeBy(100)
        fala.cancel(CancellationException("P1 cortou"))
        advanceTimeBy(2_500) // além da carência

        rota.emUso { delay(30) }
        advanceTimeBy(50)

        assertEquals(
            "O earcon veio 2,5 s depois do corte e a rota NÃO foi remontada — então a " +
                "carência deixou de existir e a rota está vazando.",
            2,
            audio.subidas,
        )
    }
}
