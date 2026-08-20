package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O batimento cabe embaixo do limiar que faz o marcador esmaecer?**
 *
 * A pergunta parece aritmética e não é: ela liga duas constantes que moravam em
 * arquivos diferentes e nunca tinham sido comparadas. `BATIMENTO_MS` era 3 min e
 * `OBSOLETO_S` é 2 min, então **todo agente parado em serviço ficava esmaecido um
 * terço do tempo** — e um indicador que acende no estado normal ensina a pessoa a
 * ignorá-lo, que é o modo mais silencioso de um instrumento parar de funcionar.
 *
 * Pior: o número declarado nem era o que acontecia. O batimento só é avaliado
 * quando uma correção chega, e correção chega na cadência do provedor — o
 * batimento **efetivo** é o primeiro múltiplo de `intervaloMs`. Em Ocorrência, com
 * intervalo de 15 s, 3 min viravam 3 min; em Ativo, com 60 s, também. Ninguém
 * tinha feito essa conta.
 *
 * Estes testes falham com os valores de antes de 20/08. É essa a régua.
 */
class BatimentoDePosicaoTest {

    @Test
    fun emServico_oAgenteParadoReaparece_ANTES_deEsmaecer() {
        for (modo in listOf(ModoOperacao.ATIVO, ModoOperacao.OCORRENCIA)) {
            val plano = PoliticaDePosicao.planoPara(modo, mapaVisivel = false)
            assertTrue(
                "$modo: batimento efetivo de ${plano.batimentoEfetivoMs / 1000}s contra " +
                    "OBSOLETO_S de ${PoliticaDePosicao.OBSOLETO_S}s — o agente parado " +
                    "esmaece antes de reaparecer, e o esmaecido vira ruído",
                plano.batimentoEfetivoMs < PoliticaDePosicao.OBSOLETO_S * 1000L,
            )
        }
    }

    /**
     * Standby é a exceção **declarada**: 5 min de cadência, marcador esmaecido a
     * maior parte do tempo. Está no teste para que mudar isso seja uma decisão e
     * não um acidente — e para que a exceção não se espalhe para os outros modos.
     */
    @Test
    fun emStandby_oEsmaecido_eALEITURA_correta_eNaoUmDefeito() {
        val plano = PoliticaDePosicao.planoPara(ModoOperacao.STANDBY, mapaVisivel = false)
        assertTrue(
            "se o Standby couber embaixo de OBSOLETO_S, alguém subiu a cadência da " +
                "pausa e a conta de bateria mudou junto — reveja de propósito",
            plano.batimentoEfetivoMs >= PoliticaDePosicao.OBSOLETO_S * 1000L,
        )
    }

    /**
     * O batimento NUNCA pode ser menor que o intervalo: seria um número que não
     * acontece. Foi essa divergência entre o declarado e o real que passou
     * despercebida.
     */
    @Test
    fun oBatimentoEfetivo_eSempreUmMultiploDoIntervalo() {
        for (modo in ModoOperacao.entries) {
            val p = PoliticaDePosicao.planoPara(modo, mapaVisivel = false)
            assertEquals(
                "$modo: batimento efetivo não é múltiplo do intervalo",
                0L,
                p.batimentoEfetivoMs % p.intervaloMs,
            )
            assertTrue("$modo: efetivo menor que o declarado", p.batimentoEfetivoMs >= p.batimentoMs)
            assertTrue("$modo: efetivo menor que o intervalo", p.batimentoEfetivoMs >= p.intervaloMs)
        }
    }

    /**
     * O contra-teste da aritmética: 90 s com intervalo de 60 s **não** dão 90 s,
     * dão 120 s. Se `batimentoEfetivoMs` devolvesse 90, ele estaria descrevendo
     * uma publicação que nunca acontece.
     */
    @Test
    fun noventaSegundosComIntervaloDeSessenta_daCentoEVinte_naoNoventa() {
        val plano = PlanoDePosicao(
            intervaloMs = 60_000,
            deslocamentoMinimoM = 50f,
            altaPrecisao = true,
            assinarPares = false,
            batimentoMs = 90_000,
        )
        assertEquals(120_000L, plano.batimentoEfetivoMs)
    }

    @Test
    fun nenhumModoFicaSemBatimento() {
        for (modo in ModoOperacao.entries) {
            val p = PoliticaDePosicao.planoPara(modo, mapaVisivel = false)
            assertTrue(
                "$modo sem batimento: agente parado sumiria do mapa, e companheiro " +
                    "que some parece em perigo",
                p.batimentoMs > 0,
            )
        }
    }
}
