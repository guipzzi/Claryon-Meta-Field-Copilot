package com.claryon.field.oculos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.field.voice.CopilotoDoAgente
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A pergunta 2 do §6: quem chama isto em produção?**
 *
 * `withCamera` e `PlacaOcr.lerPlaca` tinham zero chamadores em `src/main` — eram
 * declaração, implementação e KDoc, sem nenhuma chamada vinda do `app`. Ligar isso
 * é o trabalho, e o teste que prova que ficou ligado **não pode ser um teste de
 * unidade com dublê**: o dublê prova que a classe funciona, não que alguém a chama.
 *
 * ## Por que o `ActionOutcome` sozinho não serviria
 *
 * `Intent.ConsultarPlaca(null)` devolve `Falhou(CONSULTA_INDISPONIVEL)` em **dois**
 * casos indistinguíveis pela resposta:
 *
 *  1. a câmera foi aberta e não entregou imagem — o caminho ligado, sem óculos;
 *  2. `lerPlacaPelaCamera` nem foi injetado e o padrão que recusa respondeu.
 *
 * Um teste que só olhasse o resultado ficaria **verde com a composição desligada** —
 * que é exatamente a forma como as três Edge Functions ficaram sem chamador por
 * meses. Por isso a asserção é sobre `PlacaPelaCamera.tentativas` e sobre a causa,
 * e não sobre a fala.
 *
 * ## O que este teste NÃO prova
 *
 * Que a leitura funciona. Sem óculos e sem `MockDeviceKit` não há sessão do DAT, e a
 * causa esperada é `glasses.no_session`. O OCR de verdade é medido em
 * `FramesEfemerosTest`; aqui a pergunta é só uma: **o fio está ligado?**
 */
@RunWith(AndroidJUnit4::class)
class CaminhoDaPlacaEmProducaoTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oExecutorDeProducao_chegaAteACameraDosOculos() = runBlocking {
        val antes = PlacaPelaCamera.tentativas

        // O executor **de produção**, montado por `CerebroDoCopiloto` — o mesmo
        // objeto que o ciclo de voz usa. Nenhum dublê é construído aqui.
        val executor = CopilotoDoAgente.de(app).executor

        val outcome = withTimeoutOrNull(30_000) {
            executor.execute(Intent.ConsultarPlaca(placa = null))
        }
        assertNotNull("a consulta de placa não terminou em 30 s", outcome)

        assertEquals(
            "A composição de produção não pediu captura nenhuma. `lerPlacaPelaCamera` " +
                "voltou a ser o padrão que recusa, e a câmera dos óculos é outra vez " +
                "capacidade escrita e nunca construída — ver CLAUDE.md §6.",
            antes + 1,
            PlacaPelaCamera.tentativas,
        )

        // E a causa veio do DAT de verdade: `glasses.no_session` é a frase que
        // `DatGlassesFacade.withCamera` emite quando `activeSession == null`.
        // Nenhum dublê deste repositório produz esse código.
        val causa = PlacaPelaCamera.ultimaCausa
        assertTrue(
            "a causa \"$causa\" não veio da fachada do DAT — o caminho pode estar " +
                "curto-circuitado antes do SDK",
            causa != null && causa.startsWith("glasses."),
        )

        // Sem óculos, a resposta honesta é que não deu. Jamais "sem restrição".
        assertEquals(
            ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL),
            outcome,
        )
    }
}
