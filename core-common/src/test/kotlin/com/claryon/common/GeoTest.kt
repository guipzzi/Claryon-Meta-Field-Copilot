package com.claryon.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometria com referências reais de Goiânia — a cidade do piloto.
 *
 * Testar com pontos conhecidos e não com números redondos é intencional: a
 * fórmula errada (lei dos cossenos) passa em qualquer teste de escala
 * continental e falha exatamente na escala de quarteirão, que é a única que
 * importa aqui.
 */
class GeoTest {

    // Praça Cívica e Estádio Serra Dourada, ~2,9 km em linha reta.
    private val pracaCivicaLat = -16.6799
    private val pracaCivicaLon = -49.2550
    private val serraDouradaLat = -16.6942
    private val serraDouradaLon = -49.2792

    @Test
    fun distanciaConfereComReferenciaConhecida() {
        val d = Geo.distanciaM(pracaCivicaLat, pracaCivicaLon, serraDouradaLat, serraDouradaLon)
        assertTrue("veio $d m", d in 2_800.0..3_200.0)
    }

    @Test
    fun precisaoNaEscalaDeQuarteirao() {
        // ~100 m ao norte. É nesta escala que a fórmula errada quebra, e é nesta
        // escala que o agente decide se atravessa a rua ou entra na viatura.
        val d = Geo.distanciaM(-16.6799, -49.2550, -16.6790, -49.2550)
        assertEquals(100.0, d, 5.0)
    }

    @Test
    fun mesmoPonto_daZero() {
        assertEquals(0.0, Geo.distanciaM(-16.6799, -49.2550, -16.6799, -49.2550), 0.001)
    }

    @Test
    fun rumoCobreOsQuatroQuadrantes() {
        val lat = -16.6799
        val lon = -49.2550
        assertEquals("norte", 0.0, Geo.rumoGraus(lat, lon, lat + 0.01, lon), 1.0)
        assertEquals("leste", 90.0, Geo.rumoGraus(lat, lon, lat, lon + 0.01), 1.0)
        assertEquals("sul", 180.0, Geo.rumoGraus(lat, lon, lat - 0.01, lon), 1.0)
        assertEquals("oeste", 270.0, Geo.rumoGraus(lat, lon, lat, lon - 0.01), 1.0)
    }

    @Test
    fun rumoNuncaSaiDoIntervaloFalavel() {
        // `Rumo.deGraus` recebe isto direto. Um valor negativo viraria setor
        // errado — e o agente ouviria "sudoeste" para quem está a nordeste.
        val lat = -16.6799
        val lon = -49.2550
        for (dLat in listOf(-0.02, -0.001, 0.0, 0.001, 0.02)) {
            for (dLon in listOf(-0.02, -0.001, 0.0, 0.001, 0.02)) {
                val r = Geo.rumoGraus(lat, lon, lat + dLat, lon + dLon)
                assertTrue("veio $r", r >= 0.0 && r < 360.0)
            }
        }
    }

    @Test
    fun cruzaOAntimeridiano_semExplodir() {
        // Improvável em Goiânia, mas a fórmula não pode devolver meia volta de
        // Terra por causa de um sinal.
        val d = Geo.distanciaM(0.0, 179.99, 0.0, -179.99)
        assertTrue("veio $d m", d < 3_000.0)
    }

    @Test
    fun polos_naoDividemPorZero() {
        val d = Geo.distanciaM(90.0, 0.0, 90.0, 180.0)
        assertEquals(0.0, d, 1.0)
        assertFalse(Geo.rumoGraus(90.0, 0.0, 89.0, 0.0).isNaN())
    }

    @Test
    fun limiarDeDeslocamentoUsaDistanciaReal() {
        // Meio grau de longitude vale 55 km no equador e quase nada perto do
        // polo. Comparar deltas de coordenada em vez de metros publicaria demais
        // num lugar e de menos no outro.
        assertTrue(Geo.deslocouMaisQue(50f, -16.6799, -49.2550, -16.6790, -49.2550))
        assertFalse(Geo.deslocouMaisQue(50f, -16.6799, -49.2550, -16.67987, -49.2550))
    }
}
