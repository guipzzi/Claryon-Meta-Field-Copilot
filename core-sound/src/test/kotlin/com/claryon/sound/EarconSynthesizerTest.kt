package com.claryon.sound

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * O que sobrou aqui depois de 22/08: **o cache**.
 *
 * As duas asserções que este arquivo tinha — "não vazio" e "não silencioso" — e o
 * `assertEquals(32_000, GRAVANDO)` mudaram para
 * [DistinguibilidadeDosEarconsTest], que é onde as réguas do vocabulário passaram a
 * viver juntas. Repeti-las aqui daria dois lugares para a mesma pergunta e um deles
 * envelheceria.
 *
 * A identidade do `ShortArray` fica: ela é o contrato de que
 * `EarconSynthesizer.render` sintetiza **uma vez por processo** e devolve sempre o
 * mesmo objeto. Não é micro-otimização — o `render` estava sendo chamado na Main a
 * cada reprodução, e o earcon do ciclo de voz é o som mais frequente do produto.
 * Um teste de conteúdo (`assertArrayEquals`) passaria com o cache removido; só o de
 * identidade prende o comportamento.
 */
class EarconSynthesizerTest {

    @Test
    fun cadaEarconESintetizadoUmaVezSo() {
        for (earcon in Earcon.entries) {
            assertSame(
                "${earcon.name} foi sintetizado de novo — o cache de EarconSynthesizer " +
                    "deixou de valer e o render voltou para o caminho quente",
                EarconSynthesizer.render(earcon),
                EarconSynthesizer.render(earcon),
            )
        }
    }
}
