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

    /**
     * **A recepção em curso é registrada BLOCO A BLOCO**, e a margem emenda um no
     * outro. É o que substituiu a janela sem fim que `abrir`/`fechar` mantinham.
     *
     * O teste exige as duas metades: continuidade enquanto os blocos chegam, e
     * fim automático quando param. Sem a segunda, a janela sem fim passaria igual
     * — e é exatamente ela que descartava a fala do agente seguinte.
     */
    @Test
    fun recepcaoEmCurso_ehUmaJanelaPorBloco_eEmendaPelaMargem() {
        val s = supressor()
        // 40 blocos de 20 ms, de 2 000 a 2 800 ms — uma fala recebida de 800 ms.
        repeat(40) { s.registrar(inicioMs = 2_000 + it * 20L, duracaoMs = 20) }

        assertTrue("começo da fala recebida", s.suprimido(2_000))
        assertTrue(
            "o miolo não pode ter buraco: dois blocos de 20 ms emendam pela margem",
            (2_000..2_800 step 7).all { s.suprimido(it.toLong()) },
        )
        assertTrue("a cauda ainda é suprimida", s.suprimido(2_850))
    }

    /**
     * **O achado 5 da bateria de caos, no nível da política.**
     *
     * Os blocos param de chegar em 2 800 ms — a fala foi cortada pela rede. O
     * `Receptor` só concluirá isso 2 s depois, e é aí que a janela ANTIGA era
     * fechada. Se a supressão durar até lá, os primeiros 2 s do próximo agente a
     * apertar o PTT são descartados com a barra no ar e sem tom nenhum.
     *
     * O número é duro de propósito: a supressão tem de acabar com a margem, não
     * com a conclusão de outro mecanismo.
     */
    @Test
    fun quandoOsBlocosParam_aSupressaoAcabaComAMargem_eNaoComOReceptor() {
        val s = supressor()
        repeat(40) { s.registrar(inicioMs = 2_000 + it * 20L, duracaoMs = 20) }
        val ultimoFim = 2_000L + 39 * 20 + 20 // 2 800

        assertFalse(
            "passada a margem, a captura volta na hora — e não 2 s depois",
            s.suprimido(ultimoFim + 81),
        )
        assertFalse(
            "muito menos quando o receptor conclui, aos 2 s",
            s.suprimido(ultimoFim + 2_000),
        )
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
