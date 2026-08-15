package com.claryon.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O espelho de posições do talk group.
 *
 * O que estes testes protegem não é o desenho do mapa: é a **reciprocidade** —
 * não existe observar sem ser observado — e a **honestidade temporal**, que é
 * dizer de quando a posição é em vez de afirmá-la como agora.
 */
class CanalDePosicoesTest {

    private class PublicadorFalso(
        var ativo: Boolean = true,
        var aceitaAssinatura: Boolean = true,
    ) : PublicadorDePosicao {
        var assinaturas = 0
        var desassinaturas = 0
        val publicadas = mutableListOf<Triple<Double, Double, Float?>>()

        override fun publicando() = ativo
        override suspend fun publicar(lat: Double, lon: Double, precisaoM: Float, velocidadeMs: Float?) {
            publicadas += Triple(lat, lon, velocidadeMs)
        }
        override suspend fun assinarPares(talkGroupId: String): Boolean {
            assinaturas++
            return aceitaAssinatura
        }
        override suspend fun desassinarPares() { desassinaturas++ }
    }

    private var relogio = 1_000_000L
    private val pub = PublicadorFalso()
    private val canal = CanalDePosicoes(pub) { relogio }

    private fun par(
        indicativo: String = "Alfa Dois",
        lat: Double = -16.6790,
        lon: Double = -49.2550,
        vel: Float? = 8f,
        idadeS: Int = 5,
    ) = PosicaoDePar(indicativo, lat, lon, 12f, vel, relogio - idadeS * 1000L)

    private val minhaLat = -16.6799
    private val minhaLon = -49.2550

    // ── Reciprocidade ─────────────────────────────────────────────────────────

    @Test
    fun naoAssinaSemPublicar(): Unit = runTest {
        // Quem vê é visto. Observar sem ser observado é vigilância, e a
        // assimetria não pode depender de disciplina de quem chama.
        pub.ativo = false
        assertFalse(canal.assinar("tg-1"))
        assertFalse(canal.assinando())
        assertEquals("nem deveria ter tentado", 0, pub.assinaturas)
    }

    @Test
    fun comPublicacaoAtiva_assina(): Unit = runTest {
        assertTrue(canal.assinar("tg-1"))
        assertTrue(canal.assinando())
    }

    @Test
    fun assinaturaRecusadaPeloServidor_naoFicaMeioAberta(): Unit = runTest {
        // O servidor pode negar (fora do talk group). O estado local não pode
        // dizer que está assinado — senão o mapa esperaria pacotes para sempre.
        pub.aceitaAssinatura = false
        assertFalse(canal.assinar("tg-alheio"))
        assertFalse(canal.assinando())
    }

    // ── Só com o mapa visível ─────────────────────────────────────────────────

    @Test
    fun semAssinatura_pacoteEhDescartado(): Unit = runTest {
        // Chegou pacote com o mapa fechado (corrida entre desassinar e o último
        // pacote em voo). Guardar criaria estado que ninguém vai olhar e que
        // envelheceria em silêncio.
        canal.aoReceber(par())
        assertTrue(canal.marcadores(minhaLat, minhaLon).isEmpty())
    }

    @Test
    fun aoFecharOMapa_oEspelhoEhDescartado(): Unit = runTest {
        canal.assinar("tg-1")
        canal.aoReceber(par())
        assertEquals(1, canal.marcadores(minhaLat, minhaLon).size)

        canal.desassinar()
        assertEquals(1, pub.desassinaturas)
        assertTrue("espelho tem de zerar", canal.marcadores(minhaLat, minhaLon).isEmpty())

        // E ao reabrir, não ressuscita o que era verdade horas atrás.
        canal.assinar("tg-1")
        assertTrue(canal.marcadores(minhaLat, minhaLon).isEmpty())
    }

    // ── Honestidade temporal ──────────────────────────────────────────────────

    @Test
    fun idadeEhDerivadaNaLeitura_naoCongelada(): Unit = runTest {
        canal.assinar("tg-1")
        canal.aoReceber(par(idadeS = 5))
        assertEquals(5, canal.marcadores(minhaLat, minhaLon).single().idadeS)

        // Sem receber nada, o tempo passa e a idade tem de acompanhar.
        relogio += 300_000
        assertEquals(305, canal.marcadores(minhaLat, minhaLon).single().idadeS)
    }

    @Test
    fun relogioDoParAdiantado_naoViraIdadeNegativa(): Unit = runTest {
        // Relógios de aparelhos diferentes divergem. Idade negativa seria lida
        // como "recentíssima" pela política de obsolescência — o pior erro
        // possível, porque afirma como atual justamente o que não dá para saber.
        canal.assinar("tg-1")
        canal.aoReceber(par(idadeS = -60))
        assertEquals(0, canal.marcadores(minhaLat, minhaLon).single().idadeS)
    }

    @Test
    fun pacoteForaDeOrdem_naoRebobinaOMarcador(): Unit = runTest {
        canal.assinar("tg-1")
        canal.aoReceber(par(lat = -16.6700, idadeS = 5))
        canal.aoReceber(par(lat = -16.6600, idadeS = 30)) // chegou atrasado
        // Saltar para trás na tela é indistinguível do par ter voltado de fato.
        assertEquals(-16.6700, canal.marcadores(minhaLat, minhaLon).single().posicao.latitude, 1e-9)
    }

    // ── Geometria e ordenação ─────────────────────────────────────────────────

    @Test
    fun ordenaDoMaisProximoAoMaisDistante(): Unit = runTest {
        canal.assinar("tg-1")
        canal.aoReceber(par("Charlie Um", lat = -16.7100))
        canal.aoReceber(par("Alfa Dois", lat = -16.6790))
        canal.aoReceber(par("Bravo Três", lat = -16.6900))

        val ordem = canal.marcadores(minhaLat, minhaLon).map { it.posicao.indicativo }
        assertEquals(listOf("Alfa Dois", "Bravo Três", "Charlie Um"), ordem)
    }

    @Test
    fun derivaDeGpsParado_naoViraDeslocamento(): Unit = runTest {
        // Um receptor parado relata 0,3–0,8 m/s. Dizer "deslocando" para uma
        // viatura estacionada é pior que não dizer nada.
        canal.assinar("tg-1")
        canal.aoReceber(par(vel = 0.6f))
        assertFalse(canal.marcadores(minhaLat, minhaLon).single().emMovimento)

        canal.aoReceber(par(vel = 8f, idadeS = 1))
        assertTrue(canal.marcadores(minhaLat, minhaLon).single().emMovimento)
    }

    @Test
    fun velocidadeDesconhecida_naoAfirmaMovimento(): Unit = runTest {
        canal.assinar("tg-1")
        canal.aoReceber(par(vel = null))
        assertFalse(canal.marcadores(minhaLat, minhaLon).single().emMovimento)
    }

    // ── Casamento de indicativo ───────────────────────────────────────────────

    @Test
    fun casaOIndicativoComoOSttEntrega(): Unit = runTest {
        // Cadastro tem "Alfa-02"; o STT entrega "alfa dois" ou "ALFA 02".
        canal.assinar("tg-1")
        canal.aoReceber(par("Alfa-02"))
        assertNotNull(canal.marcadorDe("alfa 02", minhaLat, minhaLon))
        assertNotNull(canal.marcadorDe("ALFA02", minhaLat, minhaLon))
        assertNull(canal.marcadorDe("bravo 02", minhaLat, minhaLon))
    }

    @Test
    fun indicativoInexistente_devolveNulo(): Unit = runTest {
        // O executor transforma isso em "não localizado" — nunca numa posição
        // plausível. Alucinar aqui manda o agente para o lugar errado.
        canal.assinar("tg-1")
        canal.aoReceber(par("Alfa Dois"))
        assertNull(canal.marcadorDe("Zulu Nove", minhaLat, minhaLon))
    }

    @Test
    fun oEspelhoServeAoMapa_naoAConsultaPorVoz(): Unit = runTest {
        // Este teste registra um achado, não um comportamento desejado.
        //
        // Escrevendo-o, ficou claro que a consulta por voz (C2) NÃO pode sair
        // daqui: o espelho só existe com o mapa aberto, e o mapa fica fechado
        // quase o turno inteiro. Uma consulta de voz que exige a tela ligada
        // contradiz a premissa do produto.
        //
        // C2 passou a ser resolvida no servidor, por `ConsultaDePosicao` — que
        // além de funcionar com a tela apagada, faz a coordenada do par nunca
        // chegar ao aparelho. O espelho ficou sendo o que sempre deveria ter
        // sido: a fonte do mapa (C5), e só dele.
        canal.assinar("tg-1")
        canal.aoReceber(par("Alfa Dois"))
        canal.desassinar()
        assertNull(canal.marcadorDe("Alfa Dois", minhaLat, minhaLon))
    }
}
