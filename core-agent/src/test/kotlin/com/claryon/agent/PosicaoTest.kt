package com.claryon.agent

import com.claryon.common.LaconicityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A consulta por voz (C2) e a política de posição (C5).
 *
 * O que estes testes protegem é a **honestidade temporal**: uma posição de dez
 * minutos atrás, dita como se fosse agora, faz o agente decidir a abordagem
 * contando com um apoio que já saiu dali.
 */
class FalaDePosicaoTest {

    private fun posicao(
        distancia: Int = 1_200,
        rumo: Rumo = Rumo.NORDESTE,
        movendo: Boolean = true,
        idade: Int = 5,
    ) = PosicaoRelativa("Alfa Dois", distancia, rumo, movendo, idade)

    @Test
    fun aFraseDeReferenciaDoAditivo() {
        val fala = FalaDePosicao.para(posicao())
        assertTrue("veio: $fala", fala.contains("Alfa Dois"))
        assertTrue("veio: $fala", fala.contains("nordeste"))
        assertTrue("veio: $fala", fala.contains("1,2 quilômetros") || fala.contains("mil"))
    }

    @Test
    fun toda_falaRespeitaALaconicidade() {
        // A mesma regra de sete palavras que vale no resto do produto.
        val casos = listOf(
            posicao(distancia = 80, idade = 1),
            posicao(distancia = 350),
            posicao(distancia = 1_200),
            posicao(distancia = 6_400, movendo = false),
            posicao(idade = 400),
        )
        for (p in casos) {
            val fala = FalaDePosicao.para(p)
            assertTrue(
                "excede ${LaconicityPolicy.MAX_WORDS} palavras: \"$fala\"",
                LaconicityPolicy.isWithinLimit(fala),
            )
            assertFalse("tem cortesia: \"$fala\"", LaconicityPolicy.hasCourtesy(fala))
        }
        assertTrue(LaconicityPolicy.isWithinLimit(FalaDePosicao.naoEncontrado("Charlie Três")))
    }

    @Test
    fun distanciaEhArredondadaParaAPrecisaoDoGps() {
        // Dizer "1.237 metros" sugere exatidão que o sinal não tem.
        assertEquals("a 1,2 quilômetros", FalaDePosicao.distanciaFalada(1_237))
        assertEquals("a 350 metros", FalaDePosicao.distanciaFalada(337))
        assertEquals("a 80 metros", FalaDePosicao.distanciaFalada(78))
        assertTrue(FalaDePosicao.distanciaFalada(2_000).contains("2 quilômetros"))
    }

    @Test
    fun movimentoMudaAFrase() {
        assertTrue(FalaDePosicao.para(posicao(movendo = true)).contains("deslocando"))
        assertFalse(FalaDePosicao.para(posicao(movendo = false)).contains("deslocando"))
    }

    @Test
    fun posicaoVelha_naoEhAfirmadaComoAtual() {
        // O ponto central: em vez de dizer onde o par "está", diz de quando é.
        val fala = FalaDePosicao.para(posicao(idade = 400))
        assertTrue("deveria informar a idade: $fala", fala.contains("minutos"))
        assertFalse("não pode afirmar rumo de posição velha", fala.contains("nordeste"))
    }

    @Test
    fun naoEncontrado_naoInventaPosicao() {
        // Alucinar aqui é dizer a um policial que o apoio está a 800 m quando
        // está a 6 km.
        val fala = FalaDePosicao.naoEncontrado("Charlie Três")
        assertTrue(fala.contains("não localizado"))
    }

    @Test
    fun rumoCardinalCobreOsOitoSetores() {
        assertEquals(Rumo.NORTE, Rumo.deGraus(0.0))
        assertEquals(Rumo.NORDESTE, Rumo.deGraus(45.0))
        assertEquals(Rumo.LESTE, Rumo.deGraus(90.0))
        assertEquals(Rumo.NOROESTE, Rumo.deGraus(315.0))
        assertEquals("360 tem de voltar ao norte", Rumo.NORTE, Rumo.deGraus(360.0))
        assertEquals("negativo é normalizado", Rumo.OESTE, Rumo.deGraus(-90.0))
        assertEquals("arredonda para o setor mais próximo", Rumo.NORTE, Rumo.deGraus(20.0))
    }
}

class PoliticaDePosicaoTest {

    @Test
    fun standbyReduzCadencia_masNaoDesliga() {
        // Companheiro que some do mapa parece em perigo.
        val p = PoliticaDePosicao.planoPara(ModoOperacao.STANDBY, mapaVisivel = false)
        assertTrue("Standby não pode parar de reportar", p.intervaloMs in 1..(10 * 60 * 1000))
        assertFalse("Standby não precisa de alta precisão", p.altaPrecisao)
    }

    @Test
    fun ocorrenciaEhMaisFrequenteQueAtivo_queEhMaisQueStandby() {
        val standby = PoliticaDePosicao.planoPara(ModoOperacao.STANDBY, false).intervaloMs
        val ativo = PoliticaDePosicao.planoPara(ModoOperacao.ATIVO, false).intervaloMs
        val ocorrencia = PoliticaDePosicao.planoPara(ModoOperacao.OCORRENCIA, false).intervaloMs

        assertTrue("$ocorrencia < $ativo < $standby", ocorrencia < ativo && ativo < standby)
    }

    @Test
    fun assinaturaDosPares_soComOMapaVisivel() {
        // Numa guarnição de oito, difundir todos para todos seria tráfego
        // permanente para uma tela fechada 95% do turno.
        for (modo in ModoOperacao.entries) {
            assertFalse(
                "$modo não pode assinar com o mapa fechado",
                PoliticaDePosicao.planoPara(modo, mapaVisivel = false).assinarPares,
            )
            assertTrue(
                "$modo deveria assinar com o mapa aberto",
                PoliticaDePosicao.planoPara(modo, mapaVisivel = true).assinarPares,
            )
        }
    }

    @Test
    fun aPosicaoPropriaSobeMesmoComOMapaFechado() {
        // É ela que alimenta a consulta por voz e o fan-out do alerta — desligar
        // com o mapa fechado cegaria as duas capacidades.
        val p = PoliticaDePosicao.planoPara(ModoOperacao.ATIVO, mapaVisivel = false)
        assertTrue("a coleta própria não pode parar", p.intervaloMs > 0)
    }

    @Test
    fun atualizaPorDeslocamento_naoSoPorTempo() {
        // Agente parado quase não custa energia.
        for (modo in ModoOperacao.entries) {
            assertTrue(
                "$modo precisa de limiar de deslocamento",
                PoliticaDePosicao.planoPara(modo, false).deslocamentoMinimoM > 0f,
            )
        }
    }

    @Test
    fun obsolescenciaUsaOMesmoLimiarDaFala() {
        // Uma regra, duas saídas: o marcador esmaece quando a fala para de
        // afirmar a posição como atual.
        assertEquals(FalaDePosicao.IDADE_MAXIMA_S, PoliticaDePosicao.OBSOLETO_S)
        assertFalse(PoliticaDePosicao.marcadorObsoleto(119))
        assertTrue(PoliticaDePosicao.marcadorObsoleto(121))
        assertTrue(PoliticaDePosicao.marcadorMuitoVelho(700))
        assertFalse(PoliticaDePosicao.marcadorMuitoVelho(300))
    }
}
