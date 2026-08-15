package com.claryon.field.mapa

import com.claryon.agent.PoliticaDePosicao
import com.claryon.net.MarcadorDePar
import com.claryon.net.PosicaoDePar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tradução de posição crua em algo desenhável.
 *
 * O que estes testes protegem é uma coisa só, dita de várias formas: **o mapa
 * para de afirmar o que não sabe mais**. Um marcador cheio numa posição de meia
 * hora atrás faz o agente decidir a abordagem contando com um apoio que já saiu
 * dali — e ele não tem como saber que o dado é velho, porque a tela não disse.
 */
class MapaDeParesTest {

    private fun marcador(
        indicativo: String = "Alfa Dois",
        distanciaM: Int = 400,
        rumoGraus: Double = 45.0,
        movendo: Boolean = true,
        idadeS: Int = 5,
    ) = MarcadorDePar(
        posicao = PosicaoDePar(indicativo, -16.67, -49.25, 12f, if (movendo) 8f else 0f, 0L),
        idadeS = idadeS,
        distanciaM = distanciaM,
        rumoGraus = rumoGraus,
        emMovimento = movendo,
    )

    private fun um(m: MarcadorDePar) = MapaDePares.montar(listOf(m), assinado = true).pares.single()

    // ── Frescor ───────────────────────────────────────────────────────────────

    @Test
    fun posicaoRecenteEhAfirmadaInteira() {
        val p = um(marcador(idadeS = 5))
        assertEquals(Frescor.ATUAL, p.frescor)
        assertTrue(p.distanciaFalada.contains("400"))
        assertEquals("nordeste", p.rumoFalado)
        assertNull("posição atual não precisa dizer a idade", p.idadeFalada)
    }

    @Test
    fun acimaDeDoisMinutosOMarcadorEsmaece() {
        assertEquals(Frescor.ATUAL, um(marcador(idadeS = 119)).frescor)
        assertEquals(Frescor.ESMAECIDO, um(marcador(idadeS = 121)).frescor)
    }

    @Test
    fun acimaDeDezMinutosOMarcadorParaDeAfirmarPosicao() {
        val p = um(marcador(idadeS = 900))
        assertEquals(Frescor.ANTIGO, p.frescor)
        assertNotNull("precisa dizer de quando é", p.idadeFalada)
        assertTrue("veio: ${p.idadeFalada}", p.idadeFalada!!.contains("15 minutos"))
    }

    @Test
    fun oLimiarDaTelaEhOMesmoDaFala() {
        // Uma regra, duas saídas. Se divergirem, o mapa mostra o par como atual
        // enquanto a voz já diz que a posição é velha — ou o contrário.
        assertEquals(Frescor.ESMAECIDO, um(marcador(idadeS = PoliticaDePosicao.OBSOLETO_S + 1)).frescor)
        assertEquals(Frescor.ANTIGO, um(marcador(idadeS = PoliticaDePosicao.MUITO_VELHO_S + 1)).frescor)
    }

    // ── Movimento ─────────────────────────────────────────────────────────────

    @Test
    fun movimentoNaoEhAfirmadoSobreDadoVelho() {
        // "Deslocando" é afirmação sobre o presente. Feita a partir de um dado de
        // dez minutos, é adivinhação — o par pode ter parado há nove.
        assertTrue(um(marcador(movendo = true, idadeS = 10)).emMovimento)
        assertFalse(um(marcador(movendo = true, idadeS = 300)).emMovimento)
        assertFalse(um(marcador(movendo = true, idadeS = 900)).emMovimento)
    }

    // ── Identidade ────────────────────────────────────────────────────────────

    @Test
    fun oMapaMostraIndicativo_naoPessoa() {
        // Um mapa que identifica pessoas deixa de ser coordenação e vira controle
        // sobre o próprio efetivo — e o agente que percebe isso desliga o app.
        val p = um(marcador(indicativo = "Alfa Dois"))
        assertEquals("Alfa Dois", p.indicativo)
        val campos = ParNoMapa::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "ParNoMapa ganhou campo de identidade pessoal: $campos",
            campos.none { "nome" in it || "matricula" in it || "cpf" in it },
        )
    }

    // ── Estado da tela ────────────────────────────────────────────────────────

    @Test
    fun mapaVazioEMapaIndisponivelSaoEstadosDiferentes() {
        // São indistinguíveis para quem olha, e a leitura errada é a perigosa:
        // "ninguém por perto" quando a verdade é "não estou recebendo".
        val vazio = MapaDePares.montar(emptyList(), assinado = true)
        assertNull(vazio.motivoIndisponivel)
        assertTrue(vazio.assinado)

        val fora = EstadoDoMapa.indisponivel("Sem sessão. Entre para ver a guarnição.")
        assertNotNull(fora.motivoIndisponivel)
        assertFalse(fora.assinado)
    }

    @Test
    fun aOrdemDaListaEhPreservada() {
        // `CanalDePosicoes` já entrega ordenado por distância — a ordem em que a
        // informação é útil. Reordenar aqui desfaria isso em silêncio.
        val estado = MapaDePares.montar(
            listOf(
                marcador("Alfa Um", distanciaM = 120),
                marcador("Bravo Dois", distanciaM = 800),
                marcador("Charlie Três", distanciaM = 3_400),
            ),
            assinado = true,
        )
        assertEquals(
            listOf("Alfa Um", "Bravo Dois", "Charlie Três"),
            estado.pares.map { it.indicativo },
        )
    }

    // ── Cenários adversos ─────────────────────────────────────────────────────

    @Test
    fun rumoInvalido_naoViraNorte() {
        // `Rumo.deGraus(NaN)` devolvia NORTE. O mapa apontaria uma direção
        // afirmada com confiança a partir de um número que não existe.
        val p = um(marcador(rumoGraus = Double.NaN))
        assertEquals("", p.rumoFalado)
    }

    @Test
    fun idadeAbsurda_naoQuebraAFrase() {
        // Relógio de par muito atrasado, ou pacote de um turno anterior.
        val p = um(marcador(idadeS = 86_400))
        assertEquals(Frescor.ANTIGO, p.frescor)
        assertTrue("veio: ${p.idadeFalada}", p.idadeFalada!!.contains("horas"))
    }

    @Test
    fun distanciaZero_naoQuebra() {
        // Dupla na mesma viatura.
        val p = um(marcador(distanciaM = 0))
        assertTrue(p.distanciaFalada.isNotBlank())
    }

    @Test
    fun guarnicaoInteira_todosTraduzidos() {
        val marcadores = (1..8).map { marcador("Unidade $it", distanciaM = it * 200) }
        assertEquals(8, MapaDePares.montar(marcadores, assinado = true).pares.size)
    }
}
