package com.claryon.field.ui.tema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O pulso do "no ar" não pode pulsar sobre um rádio mudo.**
 *
 * A regra do produto é que o app não mente, e movimento é a mentira mais barata
 * de contar: a visão periférica capta movimento sem leitura, então um pulso
 * animado afirma "estou transmitindo" com mais força do que qualquer rótulo — e
 * um `infiniteRepeatable` afirma isso pelo relógio da tela, que continua andando
 * com o transporte caído.
 *
 * Por isso `faseDoPulso` é contada em **quadros que saíram**, não em
 * milissegundos. Estes testes existem para que a troca de volta por um relógio
 * livre **quebre**, e não passe silenciosa numa refatoração de UI.
 */
class FaseDoPulsoTest {

    /**
     * **O contra-teste, e é este que carrega o valor do arquivo.**
     *
     * Um pulso pelo relógio passaria em todos os outros testes daqui: ele sobe,
     * desce, fica entre 0 e 1 e fecha o ciclo. O que ele **não** faz é parar
     * quando o rádio para. Se alguém trocar a implementação por
     * `infiniteRepeatable`, este é o único teste do conjunto que reprova.
     */
    @Test
    fun `quando o radio para de emitir, o pulso congela`() {
        val ultimoQuadro = 137L
        val fase = Movimento.faseDoPulso(ultimoQuadro)

        // O relógio de parede tem de andar de verdade durante a asserção. Cem
        // voltas fechariam dentro do mesmo milissegundo, e uma implementação por
        // relógio passaria batido — o contra-teste testaria nada. Aqui a espera é
        // real, e várias batidas de 1 s caberiam nela se houvesse relógio.
        val comeco = System.nanoTime()
        var voltas = 0L
        while (System.nanoTime() - comeco < 25_000_000L) { // 25 ms
            assertEquals(
                "a fase mudou sem quadro novo — o pulso voltou a andar pelo relógio",
                fase,
                Movimento.faseDoPulso(ultimoQuadro),
                0f,
            )
            voltas++
        }
        assertTrue("a espera precisa ter rodado", voltas > 0)
    }

    /** E, para o congelamento significar algo, quadro novo tem de mover a fase. */
    @Test
    fun `quadro novo move a fase`() {
        assertNotEquals(
            Movimento.faseDoPulso(137L),
            Movimento.faseDoPulso(138L),
        )
    }

    @Test
    fun `sem quadro nenhum nao ha pulso`() {
        assertEquals(0f, Movimento.faseDoPulso(0L), 0f)
        assertEquals(0f, Movimento.faseDoPulso(-1L), 0f)
    }

    /**
     * A batida fecha em 50 quadros — os 50/s medidos no aparelho, que dão 1 s.
     * O pico fica no meio do ciclo e as pontas encostam em zero.
     */
    @Test
    fun `a batida sobe ate o meio do ciclo e desce ate o fim`() {
        assertEquals("meio do ciclo", 1f, Movimento.faseDoPulso(25L), 1e-6f)
        assertEquals("virada do ciclo", 0f, Movimento.faseDoPulso(50L), 1e-6f)
        assertEquals("meio do ciclo seguinte", 1f, Movimento.faseDoPulso(75L), 1e-6f)

        // Subindo na primeira metade, descendo na segunda.
        assertTrue(Movimento.faseDoPulso(10L) < Movimento.faseDoPulso(20L))
        assertTrue(Movimento.faseDoPulso(30L) > Movimento.faseDoPulso(40L))
    }

    /**
     * Sem descontinuidade na virada: um salto de 1 para 0 estala, e um estalo
     * numa moldura de tela cheia lê como falha de renderização.
     */
    @Test
    fun `a virada do ciclo nao estala`() {
        for (q in 0L..200L) {
            val salto = kotlin.math.abs(
                Movimento.faseDoPulso(q + 1) - Movimento.faseDoPulso(q),
            )
            assertTrue(
                "salto de $salto entre os quadros $q e ${q + 1}",
                salto <= 2f / 50f + 1e-6f,
            )
        }
    }

    @Test
    fun `a fase nunca sai de zero-um`() {
        for (q in 0L..1_000L) {
            val f = Movimento.faseDoPulso(q)
            assertTrue("fase $f fora de [0,1] no quadro $q", f in 0f..1f)
        }
    }
}
