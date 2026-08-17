package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A normalização é o que faz "Guarnição 3" dito pelo agente casar com
 * `guarnicao 3` guardado no banco. Errar aqui não produz exceção — produz um
 * comando que simplesmente não funciona, e o agente conclui que o produto não
 * entende português.
 */
class RotulosFaladosTest {

    private fun n(s: String) = RotulosFalados.normalizar(s)

    @Test
    fun tiraAcento_caixa_eEspacoSobrando() {
        assertEquals("guarnicao 3", n("Guarnição 3"))
        assertEquals("guarnicao 3", n("  GUARNIÇÃO   3  "))
        assertEquals("guarnicao 3", n("guarnicao 3"))
    }

    @Test
    fun aNormalizacaoEhIDEMPOTENTE() {
        // Os dois lados da comparação passam por aqui — o do STT e o do banco.
        // Se normalizar duas vezes mudasse o resultado, o léxico casaria ou não
        // dependendo de quantas vezes o texto tivesse passado pela função.
        val uma = n("Operação Centro")
        assertEquals(uma, n(uma))
    }

    @Test
    fun preservaOQueDISTINGUE_gruposDiferentes() {
        // "guarnição 3" e "guarnição 13" não podem colidir: são canais distintos,
        // e abrir o errado põe o agente falando para quem não o espera.
        assertEquals("guarnicao 13", n("Guarnição 13"))
        assert(n("Guarnição 3") != n("Guarnição 13"))
    }

    @Test
    fun setor_eGuarnicao_naoColidem() {
        assert(n("Setor 3") != n("Guarnição 3"))
    }
}
