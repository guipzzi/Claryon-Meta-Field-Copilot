package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.field.voice.CopilotoDoAgente
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O ciclo de voz roda sem tela nenhuma.**
 *
 * Este é o teste da metade que faltava. A escuta da palavra de ativação e o earcon
 * já viviam no `CopilotService` e sobreviviam à tela apagada; o ciclo morava no
 * `CopilotoViewModel` e morria com a Activity. O agente que guardasse o celular no
 * bolso — o caso de uso inteiro, registrado em D1 — ouviria o bipe de "estou
 * ouvindo" e o comando não aconteceria.
 *
 * Aqui **nenhum ViewModel é construído** e nenhuma Activity é lançada: o teste fala
 * direto com o dono de processo, que é exatamente o que o `CopilotService` faz.
 *
 * ### O que ele afirma, e por que a afirmação é honesta
 *
 * O emulador não tem microfone com voz, então o ciclo vai até o teto de 8 s e
 * termina em "sem fala detectada". **É esse o ponto**: um ciclo que roda até o fim e
 * relata a ausência de fala é um ciclo VIVO. Se o cérebro dependesse de tela, ele
 * não chegaria nem ao timeout — pararia antes, sem status nenhum.
 *
 * O teste NÃO afirma que a transcrição funciona; isso é do `CicloDeVozNoAparelhoTest`,
 * que injeta áudio. Aqui a pergunta é só uma: existe cérebro sem tela?
 */
@RunWith(AndroidJUnit4::class)
class CicloSemTelaTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oCerebroExisteSemViewModel_eOCicloVaiAteOFim() = runBlocking {
        val cerebro = CopilotoDoAgente.de(app)
        assertNotNull("o dono de processo tem de existir sem Activity", cerebro)

        // Estado limpo: ninguém rodou nada ainda.
        assertTrue("o ciclo não pode nascer ocupado", !cerebro.ocupado.value)

        cerebro.cicloDeVoz()

        // **Ocupado primeiro.** Sem isto, um `cicloDeVoz()` que não fizesse nada
        // passaria neste teste — o status final poderia vir de outra coisa.
        val ficouOcupado = withTimeoutOrNull(3_000) {
            cerebro.ocupado.first { it }
        }
        assertNotNull("`cicloDeVoz()` não marcou ocupado — não começou", ficouOcupado)

        // E depois solta: o `finally` do ciclo tem de destravar mesmo nos caminhos
        // de recusa. Já houve regressão em que o botão ficava preso em "OUVINDO…"
        // para sempre no primeiro caminho de falha.
        val soltou = withTimeoutOrNull(20_000) {
            cerebro.ocupado.first { !it }
        }
        assertNotNull("o ciclo não destravou — o agente ficaria preso em OUVINDO", soltou)

        val status = cerebro.status.value
        assertTrue(
            "o ciclo terminou sem dizer nada: status=\"$status\". Um ciclo que roda " +
                "sem relatar é indistinguível de um que não rodou",
            status != "—" && status.isNotBlank(),
        )
    }
}
