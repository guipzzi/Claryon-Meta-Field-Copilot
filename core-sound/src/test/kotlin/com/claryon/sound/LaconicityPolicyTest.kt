package com.claryon.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaconicityPolicyTest {

    @Test
    fun compliant_short_response_passes() {
        assertTrue(LaconicityPolicy.isCompliant("Apoio solicitado, guarnição avisada."))
        assertEquals(4, LaconicityPolicy.wordCount("Apoio solicitado, guarnição avisada."))
    }

    @Test
    fun long_response_fails() {
        val verbose = "Sua solicitação de apoio foi enviada com sucesso para o grupo tático"
        assertFalse(LaconicityPolicy.isWithinLimit(verbose))
        assertFalse(LaconicityPolicy.isCompliant(verbose))
    }

    @Test
    fun courtesy_is_rejected() {
        assertFalse(LaconicityPolicy.isCompliant("Por favor aguarde"))
    }
}
