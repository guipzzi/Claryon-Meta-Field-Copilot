package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacaValidatorTest {

    @Test
    fun validaMercosulEAntiga() {
        assertEquals(PlacaValidator.Formato.MERCOSUL, PlacaValidator.formato("ABC1D23"))
        assertEquals(PlacaValidator.Formato.ANTIGA, PlacaValidator.formato("ABC1234"))
        assertTrue(PlacaValidator.isValida("abc-1d23")) // normaliza
        assertFalse(PlacaValidator.isValida("A1B2C3D"))
        assertNull(PlacaValidator.formato("XYZ"))
    }

    @Test
    fun extraiDeTextoRuidoso() {
        assertEquals("ABC1D23", PlacaValidator.extrair("placa lida: ABC 1D23 · UF"))
        assertEquals("ABC1234", PlacaValidator.extrair("veículo ABC-1234 azul"))
        assertNull(PlacaValidator.extrair("sem placa aqui"))
    }

    @Test
    fun naoCasaDentroDeSequenciaMaior() {
        // Sem fronteira de token, "ABC12345" produziria "ABC1234" e o app
        // consultaria uma placa que ninguém falou.
        assertNull(PlacaValidator.extrair("código ABC12345"))
        assertNull(PlacaValidator.extrair("registro 12ABC1234"))
        assertNull(PlacaValidator.extrair("ABC1D234"))
    }
}
