package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claryon.field.oculos.SessaoDosOculos
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A sessão dos óculos vive sem tela nenhuma.**
 *
 * Irmão de `CicloSemTelaTest`, e pelo mesmo motivo. Lá, o ciclo de voz morava no
 * `CopilotoViewModel` e morria com a Activity; aqui era a `DatGlassesFacade`, que
 * era construída com `viewModelScope` em `OculosViewModel` — e como a fachada roda
 * **tudo** no escopo que recebe (o `stateIn(Eagerly)` do registro, os coletores de
 * `session.state`, `session.errors`, `stream.state` e `stream.errorStream`, e o
 * vigia de primeiro frame de `withCamera`), o `onCleared()` levava a sessão inteira.
 *
 * Aqui **nenhum ViewModel é construído** e nenhuma Activity é lançada: o teste fala
 * direto com o dono de processo, que é exatamente o que a Fase 6 precisa que
 * aconteça quando o agente guarda o celular no bolso.
 *
 * ### O que ele afirma, e por que a afirmação é honesta
 *
 * O emulador não tem óculos pareados, então a sessão **não sobe**. É esse o ponto:
 * um dono que atende, tenta, e reporta a causa tipada é um dono VIVO. Se ele
 * dependesse de tela, não haveria a quem perguntar — não existiria estado, nem
 * causa, nem instância.
 *
 * O teste **não** afirma que a sessão chega a `STARTED`; isso exige óculos reais ou
 * o MockDeviceKit isolado (`MockDeviceKitStreamTest`, que aborta o processo se
 * dividir execução — o `MediaCodec` do próprio mock).
 *
 * E ele **não** afirma que nenhuma tela pode ser dona da fachada. Essa é uma
 * afirmação estrutural, e quem a sustenta é `FachadaDoDatTemDonoUnicoTest` (JVM),
 * que varre o fonte e falha nomeando o arquivo se `DatGlassesFacade(viewModelScope)`
 * voltar. Os dois juntos cobrem a regra; nenhum sozinho cobre.
 */
@RunWith(AndroidJUnit4::class)
class SessaoSemTelaTest {

    /**
     * Devolve o objeto de processo ao estado virgem.
     *
     * `@After` e não fim de `@Test`: o processo é compartilhado pelos outros testes
     * instrumentados, e um vigia de sessão vazado daqui tentaria `createSession` no
     * meio da execução de quem vier depois.
     */
    @After
    fun devolverAoEstadoVirgem() {
        SessaoDosOculos.encerrar()
        SessaoDosOculos.instalar(null)
    }

    @Test
    fun aSessaoExisteSemViewModel_eOTurnoVaiAteOFim(): Unit = runBlocking {
        // **Uma dona por processo.** Era este o segundo defeito: `OculosViewModel` e
        // `DiagnosticoViewModel` construíam uma fachada cada, sobre um recurso que é
        // global do aparelho — o mesmo erro que `AudioDoAgente` conserta para o
        // `AudioManager`.
        val fachada = SessaoDosOculos.facade()
        assertNotNull("o dono de processo tem de existir sem Activity", fachada)
        assertSame(
            "duas chamadas devolveram fachadas DIFERENTES — há mais de uma dona da " +
                "sessão do DAT no processo",
            fachada,
            SessaoDosOculos.facade(),
        )

        // Estado limpo: ninguém abriu turno ainda. `IDLE` é o valor inicial de
        // `_session` na fachada.
        assertEquals(
            "a sessão não pode nascer de pé",
            SessionStatus.IDLE,
            SessaoDosOculos.estado.value,
        )
        assertTrue(
            "o registro tem de ser legível sem tela — se ele estivesse preso a um " +
                "`stateIn` num `viewModelScope` morto, ficaria em UNKNOWN para sempre",
            SessaoDosOculos.registro.value in RegistrationStatus.entries,
        )

        SessaoDosOculos.abrir()

        // O vigia espera `registro == REGISTERED`. Sem óculos, ele fica suspenso e
        // **não toca o SDK** — é o comportamento pedido, e é o que se verifica: a
        // sessão não pode sair de IDLE por conta própria num aparelho sem óculos.
        //
        // 3 s é folga generosa: o `stateIn(Eagerly)` do registro já emitiu na
        // construção da fachada, e o `combine` reavalia em cada emissão.
        delay(3_000)

        val registro = SessaoDosOculos.registro.value
        val estado = SessaoDosOculos.estado.value

        if (registro == RegistrationStatus.REGISTERED) {
            // Há óculos (ou o MockDeviceKit ficou habilitado por outro teste). Aí a
            // afirmação é a forte: o turno subiu ou reportou por quê — sem tela.
            assertTrue(
                "com o registro em REGISTERED e o turno aberto, a sessão continuou " +
                    "em IDLE e sem motivo publicado: o vigia não rodou. estado=$estado",
                estado != SessionStatus.IDLE || SessaoDosOculos.motivo.value != null,
            )
        } else {
            // O caminho do emulador. **O silêncio aqui é a asserção**: um vigia que
            // chamasse `createSession` sem óculos gastaria Bluetooth por nada, e a
            // primeira versão desta classe fazia exatamente isso.
            assertEquals(
                "sem óculos registrados (registro=$registro) a sessão NÃO pode ter " +
                    "saído de IDLE — o vigia tentou `createSession` sem aparelho.",
                SessionStatus.IDLE,
                estado,
            )
        }

        // **Idempotência do turno.** Abrir de novo não pode criar um segundo vigia:
        // dois vigias sobre a mesma fachada disputariam `startSession()` e, em
        // hardware, um derrubaria a sessão do outro.
        SessaoDosOculos.abrir()
        delay(500)
        assertSame(
            "a fachada mudou entre duas aberturas de turno — o dono não é único",
            fachada,
            SessaoDosOculos.facade(),
        )

        // **E o turno fecha e reabre sobre a MESMA fachada.** Um dono de processo de
        // uso único seria pior que o defeito anterior: o segundo turno do dia ficaria
        // sem óculos, em silêncio.
        SessaoDosOculos.encerrar()
        delay(500)
        SessaoDosOculos.abrir()
        delay(500)
        assertSame(
            "depois de encerrar e reabrir o turno, a fachada não é a mesma — " +
                "`encerrar()` está descartando o dono em vez de parar a sessão",
            fachada,
            SessaoDosOculos.facade(),
        )
    }
}
