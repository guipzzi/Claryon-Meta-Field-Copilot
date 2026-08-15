package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os dois conflitos que o alto-falante *open-ear* cria: nossa própria saída
 * voltando pela nossa entrada, e o botão físico não sendo confiável.
 */
class SupressorDeSaidaPropriaTest {

    private fun supressor() = SupressorDeSaidaPropria(margemMs = 80)

    @Test
    fun quadroDuranteOTom_ehSuprimido() {
        // O tom de início do PTT não pode ir junto para a guarnição.
        val s = supressor().apply { registrar(inicioMs = 1_000, duracaoMs = 150) }

        assertTrue(s.suprimido(1_000))
        assertTrue(s.suprimido(1_075))
        assertTrue(s.suprimido(1_150))
    }

    @Test
    fun aCaudaDeReverberacaoTambemEhSuprimida() {
        // Sem margem, o último eco entra no primeiro quadro transmitido.
        val s = supressor().apply { registrar(inicioMs = 1_000, duracaoMs = 150) }

        assertTrue("dentro da margem", s.suprimido(1_200))
        assertFalse("depois da margem já pode capturar", s.suprimido(1_240))
    }

    @Test
    fun antesDaReproducao_naoSuprime() {
        val s = supressor().apply { registrar(inicioMs = 1_000, duracaoMs = 150) }
        assertFalse(s.suprimido(999))
    }

    @Test
    fun recepcaoEmCurso_suprimeAteFechar() {
        // A cauda de uma transmissão recebida vazaria de volta ao grupo — o
        // controle de piso cobre o miolo, esta janela cobre o fim.
        val s = supressor()
        s.abrir(inicioMs = 2_000)

        assertTrue(s.suprimido(2_500))
        assertTrue(s.suprimido(9_999))

        s.fechar(fimMs = 10_000)
        assertTrue("a margem ainda vale", s.suprimido(10_050))
        assertFalse(s.suprimido(10_100))
    }

    @Test
    fun abrirDuasVezes_naoPerdeOInicioOriginal() {
        val s = supressor()
        s.abrir(2_000)
        s.abrir(5_000) // evento duplicado do player
        s.fechar(6_000)

        assertTrue("a janela tem de cobrir desde o primeiro início", s.suprimido(2_500))
    }

    @Test
    fun fecharSemAbrir_naoExplode() {
        val s = supressor()
        s.fechar(1_000)
        assertFalse(s.suprimido(1_000))
    }

    @Test
    fun janelasSobrepostas_valemTodas() {
        // Earcon de falha por cima da fala do copiloto: acontece.
        val s = supressor().apply {
            registrar(1_000, 300)
            registrar(1_100, 500)
        }
        assertTrue(s.suprimido(1_500))
        assertFalse(s.suprimido(1_700))
    }

    @Test
    fun poda_naoDeixaAListaCrescerSemLimite() {
        // Um turno inteiro acumularia milhares de janelas, e a checagem roda 50
        // vezes por segundo no caminho mais quente do produto.
        val s = supressor()
        // Mil earcons ao longo de ~100 s, um a cada 100 ms.
        repeat(1_000) { s.registrar(inicioMs = it * 100L, duracaoMs = 50) }
        assertEquals(1_000, s.janelasVivas)

        // Poda "agora" = 100 s. Só sobrevive o que ainda pode suprimir daqui
        // para a frente — ou seja, a última janela, que termina em 99.950 + 80.
        s.podarAntesDe(100_000)
        assertTrue("deveria sobrar quase nada, sobrou ${s.janelasVivas}", s.janelasVivas <= 2)
    }

    @Test
    fun poda_naoRemoveJanelaQueAindaImporta() {
        val s = supressor().apply { registrar(inicioMs = 1_000, duracaoMs = 150) }
        s.podarAntesDe(1_100)
        assertTrue("a janela corrente não pode sumir", s.suprimido(1_100))
    }
}

class GatilhoPttTest {

    private fun gatilho() = GatilhoPtt(duracaoMinimaMs = 150, repiqueMs = 250)

    @Test
    fun apertoNormal_inicia_eSoltarEncerra() {
        val g = gatilho()
        assertEquals(DecisaoDeGatilho.Iniciar, g.aoPressionar(1_000))
        assertTrue(g.transmitindo)

        val r = g.aoSoltar(3_000)
        assertEquals(DecisaoDeSoltura.Encerrar(2_000), r)
        assertFalse(g.transmitindo)
    }

    @Test
    fun toqueCurto_abortaEDescarta() {
        // Encostar no botão no cinto não pode difundir 80 ms de ruído.
        val g = gatilho()
        g.aoPressionar(1_000)
        val r = g.aoSoltar(1_080)

        assertTrue(r is DecisaoDeSoltura.AbortarPorToqueCurto)
        assertEquals(80L, (r as DecisaoDeSoltura.AbortarPorToqueCurto).duracaoMs)
    }

    @Test
    fun repique_naoAbreSegundaTransmissao() {
        // Botão mecânico gera múltiplos eventos; sem debounce, um aperto vira
        // duas transmissões e a segunda corta a primeira no controle de piso.
        val g = gatilho()
        g.aoPressionar(1_000)
        g.aoSoltar(2_000)

        assertEquals(DecisaoDeGatilho.IgnoradoPorRepique, g.aoPressionar(2_100))
        assertEquals(DecisaoDeGatilho.Iniciar, g.aoPressionar(2_300))
    }

    @Test
    fun segundoApertoDuranteTransmissao_naoAbreOutra() {
        val g = gatilho()
        g.aoPressionar(1_000)
        assertEquals(DecisaoDeGatilho.JaTransmitindo, g.aoPressionar(1_500))
    }

    @Test
    fun solturaEspuria_semAperto_naoFazNada() {
        assertEquals(DecisaoDeSoltura.SemTransmissao, gatilho().aoSoltar(1_000))
    }

    @Test
    fun cancelar_encerraEArmaODebounce() {
        // Canal tomado por emergência: o agente não pode reabrir por reflexo em
        // cima do próprio cancelamento.
        val g = gatilho()
        g.aoPressionar(1_000)
        g.cancelar(1_500)

        assertFalse(g.transmitindo)
        assertEquals(DecisaoDeGatilho.IgnoradoPorRepique, g.aoPressionar(1_600))
        assertEquals(DecisaoDeGatilho.Iniciar, g.aoPressionar(1_800))
    }

    @Test
    fun apertoNoLimiteExato_conta() {
        val g = gatilho()
        g.aoPressionar(1_000)
        assertTrue(g.aoSoltar(1_150) is DecisaoDeSoltura.Encerrar)
    }
}
