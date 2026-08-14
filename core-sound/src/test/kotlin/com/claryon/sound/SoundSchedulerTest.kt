package com.claryon.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSchedulerTest {

    private fun tone(p: Priority) = Sound.Tone(Earcon.OUVI_VOCE, p)

    @Test
    fun modoTatico_suprimeInformativo() {
        val s = SoundScheduler()
        s.setTactical(true)
        assertFalse("INFORMATIVO deve ser suprimido em Tático", s.offer(tone(Priority.INFORMATIVO)))
        assertTrue(s.offer(tone(Priority.RESPOSTA)))
        assertEquals(1, s.size())
    }

    @Test
    fun ativarTatico_descartaInformativoPendente() {
        val s = SoundScheduler()
        s.offer(tone(Priority.INFORMATIVO))
        s.offer(tone(Priority.RESPOSTA))
        s.setTactical(true)
        assertEquals("só o RESPOSTA sobrevive", 1, s.size())
        assertEquals(Priority.RESPOSTA, s.poll()?.priority)
    }

    @Test
    fun poll_devolveMaiorPrioridadePrimeiro() {
        val s = SoundScheduler()
        s.offer(tone(Priority.INFORMATIVO))
        s.offer(tone(Priority.EMERGENCIA))
        s.offer(tone(Priority.RESPOSTA))
        assertEquals(Priority.EMERGENCIA, s.poll()?.priority)
        assertEquals(Priority.RESPOSTA, s.poll()?.priority)
        assertEquals(Priority.INFORMATIVO, s.poll()?.priority)
        assertNull(s.poll())
    }

    @Test
    fun deveInterromper_soEmergenciaSobreMenor() {
        val s = SoundScheduler()
        assertTrue(s.deveInterromper(Priority.EMERGENCIA, Priority.RESPOSTA))
        assertTrue(s.deveInterromper(Priority.EMERGENCIA, Priority.INFORMATIVO))
        assertFalse(s.deveInterromper(Priority.EMERGENCIA, Priority.EMERGENCIA))
        assertFalse(s.deveInterromper(Priority.RESPOSTA, Priority.INFORMATIVO))
        assertFalse(s.deveInterromper(Priority.EMERGENCIA, null))
    }
}
