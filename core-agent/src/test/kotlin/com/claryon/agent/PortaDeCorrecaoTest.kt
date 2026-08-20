package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Porta de correção e escolha da melhor correção.**
 *
 * Quase todo teste aqui vem em par com o seu contra-teste, porque as duas portas
 * são fáceis de escrever de um jeito que passa e não filtra nada. Um teto fixo de
 * precisão passaria no teste de degradação e recusaria o modo Standby inteiro; um
 * filtro de salto sem incerteza combinada passaria no teste do salto e recusaria
 * toda correção de rede. Por isso cada regra roda nas **duas** configurações e a
 * afirmação é que os resultados diferem.
 */
class PortaDeCorrecaoTest {

    private val base = -16.6800 to -49.2500

    private fun correcao(
        metrosAoNorte: Double = 0.0,
        precisaoM: Float = 8f,
        segundos: Double = 0.0,
    ) = Correcao(
        latitude = base.first + metrosAoNorte / 111_320.0,
        longitude = base.second,
        precisaoM = precisaoM,
        nanos = (segundos * 1_000_000_000L).toLong(),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Porta de precisão
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun aPrimeiraCorrecaoPassa_naoHaContraOQueComparar() {
        val porta = PortaDeCorrecao()
        assertEquals(Veredito.Aceita, porta.avaliar(correcao(precisaoM = 1_500f)))
    }

    @Test
    fun precisaoQueDegradaQuatroVezes_contraReferenciaFRESCA_eRecusada() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(precisaoM = 8f, segundos = 0.0))

        val v = porta.avaliar(correcao(precisaoM = 1_200f, segundos = 10.0))

        assertTrue("1200 m dez segundos depois de 8 m é troca de provedor, não movimento: $v", v is Veredito.Recusada)
        assertEquals(MotivoDaRecusa.PRECISAO_DEGRADOU, (v as Veredito.Recusada).motivo)
    }

    /**
     * **O contra-teste da porta de precisão.** A MESMA degradação, contra uma
     * referência que já passou de `OBSOLETO_S`, tem de ser ACEITA.
     *
     * Sem esta metade, um teto fixo em metros passaria no teste acima — e
     * recusaria o modo Standby inteiro, que usa a rede de propósito e erra
     * 100–1000 m. Some do mapa é pior que aparecer impreciso.
     */
    @Test
    fun aMesmaDegradacao_contraReferenciaVELHA_eAceita() {
        val fresca = PortaDeCorrecao()
        fresca.avaliar(correcao(precisaoM = 8f, segundos = 0.0))
        val comReferenciaFresca = fresca.avaliar(correcao(precisaoM = 1_200f, segundos = 10.0))

        val velha = PortaDeCorrecao()
        velha.avaliar(correcao(precisaoM = 8f, segundos = 0.0))
        val comReferenciaVelha = velha.avaliar(
            correcao(precisaoM = 1_200f, segundos = PoliticaDePosicao.OBSOLETO_S + 30.0),
        )

        assertEquals(Veredito.Aceita, comReferenciaVelha)
        assertNotEquals(
            "a porta de precisão é relativa E temporal — se os dois casos dão o " +
                "mesmo veredito, ela virou um teto fixo e derruba o Standby",
            comReferenciaVelha,
            comReferenciaFresca,
        )
    }

    @Test
    fun precisaoQueMELHORA_passa_ainda_que_a_diferenca_seja_enorme() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(precisaoM = 1_200f, segundos = 0.0))
        assertEquals(Veredito.Aceita, porta.avaliar(correcao(precisaoM = 8f, segundos = 10.0)))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Teste de salto
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun cincoQuilometrosEmDoisSegundos_comGpsPreciso_eRecusado() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(metrosAoNorte = 0.0, precisaoM = 8f, segundos = 0.0))

        val v = porta.avaliar(correcao(metrosAoNorte = 5_000.0, precisaoM = 8f, segundos = 2.0))

        assertTrue("5 km em 2 s são 9 000 km/h: $v", v is Veredito.Recusada)
        assertEquals(MotivoDaRecusa.SALTO_IMPLAUSIVEL, (v as Veredito.Recusada).motivo)
    }

    /**
     * **O contra-teste do salto.** Os MESMOS 5 km, com 3 km de erro declarado nos
     * dois pontos, têm de ser ACEITOS: o "salto" está dentro do ruído, e ninguém
     * se moveu.
     *
     * Sem esta metade, um filtro por distância crua passaria no teste acima e
     * recusaria toda correção de rede — que é justamente o provedor do Standby.
     */
    @Test
    fun osMesmosCincoQuilometros_dentroDoRuido_saoAceitos() {
        val preciso = PortaDeCorrecao()
        preciso.avaliar(correcao(precisaoM = 8f, segundos = 0.0))
        val comGps = preciso.avaliar(correcao(metrosAoNorte = 5_000.0, precisaoM = 8f, segundos = 2.0))

        val impreciso = PortaDeCorrecao()
        impreciso.avaliar(correcao(precisaoM = 3_000f, segundos = 0.0))
        val comRede = impreciso.avaliar(correcao(metrosAoNorte = 5_000.0, precisaoM = 3_000f, segundos = 2.0))

        assertEquals(Veredito.Aceita, comRede)
        assertNotEquals(
            "a mesma distância, o mesmo intervalo, incertezas diferentes: se o " +
                "veredito não muda, a incerteza combinada não está sendo usada",
            comRede,
            comGps,
        )
    }

    @Test
    fun deslocamentoDeViatura_100mEm10s_passa() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(segundos = 0.0))
        assertEquals(Veredito.Aceita, porta.avaliar(correcao(metrosAoNorte = 100.0, segundos = 10.0)))
    }

    /**
     * **A armadilha que esta classe existe para desarmar.**
     *
     * O agente entrou num túnel, andou 3 km e saiu. Todas as correções novas
     * discordam de um ponto que não é mais verdade. Um filtro sem válvula recusa
     * todas, para sempre, e o marcador **não some — ele mente parado**, que é o
     * estado pior de todos.
     *
     * O teste exige as duas coisas: que as três primeiras sejam recusadas (o
     * filtro funciona) e que a quarta passe (o filtro não trava). Um teste que só
     * verificasse a recusa passaria com o defeito de travamento intacto.
     */
    @Test
    fun quatroSaltosSeguidos_aValvulaCede_eOMarcadorNaoCongela() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(metrosAoNorte = 0.0, segundos = 0.0))

        val vereditos = (1..4).map { i ->
            porta.avaliar(correcao(metrosAoNorte = 3_000.0 + i, segundos = i * 2.0))
        }

        assertTrue("a 1ª tem de ser recusada", vereditos[0] is Veredito.Recusada)
        assertTrue("a 2ª tem de ser recusada", vereditos[1] is Veredito.Recusada)
        assertTrue("a 3ª tem de ser recusada", vereditos[2] is Veredito.Recusada)
        assertEquals("a 4ª TEM de passar, senão o agente congela no mapa", Veredito.Aceita, vereditos[3])

        // E a referência mudou: dali em diante a porta julga contra o lugar novo.
        assertEquals(Veredito.Aceita, porta.avaliar(correcao(metrosAoNorte = 3_010.0, segundos = 12.0)))
    }

    @Test
    fun umaCorrecaoBoaNoMeio_zeraOContadorDaValvula() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(segundos = 0.0))
        porta.avaliar(correcao(metrosAoNorte = 3_000.0, segundos = 2.0))
        porta.avaliar(correcao(metrosAoNorte = 3_001.0, segundos = 4.0))
        // Volta a concordar com a referência: o contador tem de zerar.
        assertEquals(Veredito.Aceita, porta.avaliar(correcao(metrosAoNorte = 10.0, segundos = 6.0)))

        // Se não tivesse zerado, esta seria a 4ª e passaria pela válvula.
        val v = porta.avaliar(correcao(metrosAoNorte = 3_000.0, segundos = 8.0))
        assertTrue("o contador não zerou: a válvula cedeu cedo demais", v is Veredito.Recusada)
    }

    @Test
    fun precisaoDESCONHECIDA_naoViraZero_eNaoDerrubaABoa() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(precisaoM = Correcao.PRECISAO_DESCONHECIDA, segundos = 0.0))
        // Com incerteza tratada como zero, estes 500 m em 1 s seriam "salto".
        assertEquals(
            Veredito.Aceita,
            porta.avaliar(correcao(metrosAoNorte = 500.0, precisaoM = Correcao.PRECISAO_DESCONHECIDA, segundos = 1.0)),
        )
    }

    @Test
    fun coordenadaZeroZero_oIlhaNula_eRecusada() {
        val porta = PortaDeCorrecao()
        val v = porta.avaliar(Correcao(0.0, 0.0, 8f, 0L))
        assertTrue(v is Veredito.Recusada)
        assertEquals(MotivoDaRecusa.COORDENADA_INVALIDA, (v as Veredito.Recusada).motivo)
    }

    @Test
    fun correcaoForaDeOrdem_naoViraReferencia() {
        val porta = PortaDeCorrecao()
        porta.avaliar(correcao(metrosAoNorte = 0.0, segundos = 100.0))
        // Chega uma de 90 s — mais velha. Passa, mas não pode virar referência.
        porta.avaliar(correcao(metrosAoNorte = 3_000.0, segundos = 90.0))
        assertEquals(
            "a referência voltou para o passado e um deslocamento normal virou salto",
            100.0 * 1_000_000_000L,
            porta.ultima()!!.nanos.toDouble(),
            1.0,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escolha da melhor correção
    // ─────────────────────────────────────────────────────────────────────────

    private data class Fix(val nome: String, val nanos: Long, val precisao: Float)

    private fun escolher(vararg fixes: Fix, agoraS: Double = 100.0): Fix? =
        EscolhaDeCorrecao.melhor(
            candidatas = fixes.toList(),
            agoraNanos = (agoraS * 1_000_000_000L).toLong(),
            nanos = { it.nanos },
            precisaoM = { it.precisao },
        )

    private fun s(segundos: Double) = (segundos * 1_000_000_000L).toLong()

    /**
     * O defeito, escrito como cenário: a rede responde agora com 1 200 m, o GPS
     * tem 8 m de 20 s atrás. A regra antiga — `maxByOrNull { elapsedRealtimeNanos }`
     * — escolhia a rede, e a consulta por voz media a distância a partir dali.
     */
    @Test
    fun redeNovaEImprecisa_perdeParaGpsDeVinteSegundosEPreciso() {
        val rede = Fix("rede", s(100.0), 1_200f)
        val gps = Fix("gps", s(80.0), 8f)

        assertSame(gps, escolher(rede, gps))

        // Contra-prova: a regra antiga daria a outra. Se as duas coincidissem,
        // este teste passaria com o defeito de volta.
        assertSame(rede, listOf(rede, gps).maxByOrNull { it.nanos })
    }

    /**
     * O inverso do defeito, que um "escolha a mais precisa" ingênuo introduziria:
     * 8 m de dez minutos atrás **não** pode ganhar de 500 m de agora. Em dez
     * minutos o agente saiu dali, e precisão sobre um lugar onde ele não está é a
     * pior combinação possível.
     */
    @Test
    fun gpsPrecisoMasOBSOLETO_perdeParaRedeGrosseiraEAtual() {
        val gpsVelho = Fix("gps", s(100.0 - PoliticaDePosicao.OBSOLETO_S - 60), 8f)
        val redeNova = Fix("rede", s(100.0), 500f)
        assertSame(redeNova, escolher(gpsVelho, redeNova))
    }

    @Test
    fun ambasObsoletas_decideAPrecisao_naoAIdade() {
        val a = Fix("a", s(100.0 - PoliticaDePosicao.OBSOLETO_S - 10), 800f)
        val b = Fix("b", s(100.0 - PoliticaDePosicao.OBSOLETO_S - 200), 12f)
        assertSame(b, escolher(a, b))
    }

    @Test
    fun empateDePrecisao_decideAMaisNova() {
        val velha = Fix("velha", s(50.0), 10f)
        val nova = Fix("nova", s(99.0), 10f)
        assertSame(nova, escolher(velha, nova))
    }

    @Test
    fun precisaoDESCONHECIDA_perdeParaQualquerUmaQueDeclarou() {
        val semPrecisao = Fix("sem", s(100.0), Correcao.PRECISAO_DESCONHECIDA)
        val com = Fix("com", s(95.0), 900f)
        assertSame(com, escolher(semPrecisao, com))
    }

    @Test
    fun listaVazia_devolveNulo_emVezDeInventar() {
        assertNull(escolher())
    }

    @Test
    fun umaSoCandidata_devolveEla() {
        val unica = Fix("única", s(10.0), 3_000f)
        assertSame(unica, escolher(unica))
    }
}
