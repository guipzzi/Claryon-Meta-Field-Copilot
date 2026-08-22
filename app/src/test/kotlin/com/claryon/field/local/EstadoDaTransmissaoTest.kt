package com.claryon.field.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A régua da transmissão própria — a que não existia.**
 *
 * O mapa sempre teve régua para a posição do PAR: `atualizadoHa` em toda linha, o
 * marcador esmaecendo aos 2 min e parando de afirmar posição aos 10. Do PORTADOR
 * não havia régua nenhuma, e o custo apareceu numa auditoria: 20 min de aplicativo
 * aberto, **delta de zero linhas** em `agent_positions`, e nada em lugar nenhum da
 * interface mudou de cor.
 *
 * Estes testes fixam a régua nova. Todos são sobre a mesma pergunta — *por que a
 * minha posição não está subindo?* — e o critério é sempre o mesmo: as causas
 * precisam **diferir**. Uma frase única e verdadeira ("posição indisponível")
 * passaria em qualquer teste que olhasse uma causa de cada vez, e não diria ao
 * agente o que fazer, que é a única coisa que a frase existe para fazer.
 */
class EstadoDaTransmissaoTest {

    /** Um estado saudável, do qual cada teste estraga exatamente uma coisa. */
    private fun saudavel(agoraMs: Long = 100_000L) = EstadoDaTransmissao(
        motivoDaColeta = MotivoDaColeta.DE_PE,
        turnoAberto = true,
        provedorAtivo = true,
        ultimaCorrecaoMs = agoraMs - 10_000L,
        ultimaPublicacaoOkMs = agoraMs - 40_000L,
        houveTentativa = true,
        publicando = true,
        silencioToleradoMs = 120_000L,
    )

    // ── As quatro frases do critério de aceite ────────────────────────────────

    /**
     * **O contra-teste central**: as quatro causas do critério, uma por vez, sobre
     * o MESMO estado saudável — e as quatro têm de sair diferentes entre si.
     *
     * Sem a exigência de diferença, uma implementação que devolvesse sempre "sua
     * posição não sobe" passaria nas quatro asserções de conteúdo isoladas. Foi
     * exatamente esse tipo de teste que deixou `oPtt_transmiteQuadros_naTaxaConfigurada`
     * verde sobre o defeito que ele existia para pegar.
     */
    @Test
    fun asQuatroCausasDoAceite_saemDiferentesUmaDaOutra() {
        val agora = 100_000L
        val base = saudavel(agora)

        val semTurno = base.copy(turnoAberto = false).causa(agora)
        val semRede = base.copy(publicando = false).causa(agora)
        val provedorMudo = base.copy(ultimaCorrecaoMs = agora - 240_000L).causa(agora)
        val feliz = base.causa(agora)

        assertTrue("veio: $semTurno", semTurno.contains("turno não abriu"))
        assertTrue("veio: $semRede", semRede.contains("sem rede"))
        assertTrue("veio: $provedorMudo", provedorMudo.contains("Sem correção de GPS há 4 min"))
        assertTrue("veio: $feliz", feliz.contains("Última posição enviada há 40 s"))

        assertEquals(
            "as quatro causas precisam ser quatro frases distintas — uma frase " +
                "genérica que servisse para todas passaria nas asserções acima " +
                "e não diria ao agente o que fazer",
            4,
            setOf(semTurno, semRede, provedorMudo, feliz).size,
        )
    }

    /**
     * A idade do caminho feliz é **medida**, não um rótulo.
     *
     * Três instantes diferentes, três frases diferentes. Com um "está subindo"
     * fixo, o agente não distingue uma posição de 40 s de uma de 40 min — e é
     * justamente essa distinção que o esmaecimento do marcador do par carrega
     * desde sempre.
     */
    @Test
    fun oCaminhoFeliz_dizQuandoSubiu_eOsCarimbosDiferem() {
        val agora = 10_000_000L
        val base = saudavel(agora).copy(ultimaCorrecaoMs = agora - 1_000L)

        val quarentaS = base.copy(ultimaPublicacaoOkMs = agora - 40_000L).causa(agora)
        val quatroMin = base.copy(
            ultimaPublicacaoOkMs = agora - 240_000L,
            // A tolerância sobe junto: sem isto o ramo do provedor mudo venceria e
            // o teste mediria outra coisa.
            silencioToleradoMs = 600_000L,
        ).causa(agora)
        val duasHoras = base.copy(
            ultimaPublicacaoOkMs = agora - 7_200_000L,
            silencioToleradoMs = 10_000_000L,
        ).causa(agora)

        assertTrue("veio: $quarentaS", quarentaS.contains("40 s"))
        assertTrue("veio: $quatroMin", quatroMin.contains("4 min"))
        assertTrue("veio: $duasHoras", duasHoras.contains("2 h"))
        assertEquals(3, setOf(quarentaS, quatroMin, duasHoras).size)
    }

    // ── Os quatro motivos de coleta, e por que cada um é uma frase ────────────

    /**
     * Sem permissão, sem provedor, assinatura recusada e coleta parada levam a
     * **quatro ações diferentes** do agente: abrir os ajustes de permissão, ligar
     * o GPS, reiniciar o aplicativo, voltar ao serviço. Colapsá-las num "coleta
     * indisponível" devolve o agente ao ponto de partida.
     */
    @Test
    fun osQuatroMotivosDeColeta_naoColapsamNumaFraseSo() {
        val agora = 100_000L
        val base = saudavel(agora)
        val frases = listOf(
            MotivoDaColeta.SEM_PERMISSAO,
            MotivoDaColeta.SEM_PROVEDOR,
            MotivoDaColeta.ASSINATURA_RECUSADA,
        ).map { base.copy(motivoDaColeta = it).causa(agora) }

        assertEquals("três motivos, três frases", 3, frases.toSet().size)
        assertTrue(frases.any { it.contains("permissão") })
        assertTrue(frases.any { it.contains("GPS e rede desligados") })
        assertTrue(frases.any { it.contains("recusou a assinatura") })

        assertNotEquals(
            "'coleta não está ligada' é um quarto estado, não sinônimo dos outros",
            base.copy(motivoDaColeta = MotivoDaColeta.PARADA).causa(agora),
            frases.first(),
        )
    }

    // ── A precedência, que é onde uma causa-raiz vira sintoma ─────────────────

    /**
     * **Turno fechado ganha de tudo.** Sem turno o servidor recusa toda
     * publicação (`0019`), então "sem rede" ali seria diagnóstico errado sobre um
     * sintoma verdadeiro — e mandaria o agente procurar sinal no lugar errado.
     */
    @Test
    fun turnoFechado_ganhaDe_semRede() {
        val agora = 100_000L
        val tudoErrado = saudavel(agora).copy(turnoAberto = false, publicando = false)
        assertTrue(tudoErrado.causa(agora).contains("turno não abriu"))
    }

    /**
     * **GPS mudo ganha de "sem rede".**
     *
     * Este é o caso que o `publicando` congelado cria: o provedor para de entregar
     * correção, nenhuma publicação nova acontece, e o resultado do último POST —
     * que pode ser um fracasso de minutos atrás — continuaria sendo a explicação
     * oferecida. A causa-raiz é o silêncio do provedor; ele vem primeiro.
     */
    @Test
    fun provedorMudo_ganhaDe_semRede() {
        val agora = 100_000L
        val mudoESemRede = saudavel(agora)
            .copy(publicando = false, ultimaCorrecaoMs = agora - 300_000L)
        assertTrue(mudoESemRede.causa(agora).contains("Sem correção de GPS"))
    }

    /**
     * **O GPS desligado nos ajustes tem frase própria** — e é o defeito medido:
     * 70 s de silêncio absoluto porque o ouvinte era uma lambda SAM e
     * `onProviderDisabled` caía no default vazio.
     */
    @Test
    fun provedorDesligado_temFrasePropria_diferenteDoProvedorMudo() {
        val agora = 100_000L
        val desligado = saudavel(agora).copy(provedorAtivo = false).causa(agora)
        val mudo = saudavel(agora).copy(ultimaCorrecaoMs = agora - 300_000L).causa(agora)

        assertTrue("veio: $desligado", desligado.contains("GPS foi desligado"))
        assertNotEquals("desligado e mudo pedem ações diferentes", desligado, mudo)
    }

    /**
     * "Ainda não tentou" **não** é "falhou".
     *
     * Nos primeiros segundos do turno `publicando` é `false` porque nada subiu
     * ainda. Dizer "sem rede" ali seria acusar a rede de um atraso normal — e
     * ensinaria o agente a ignorar a frase quando ela for verdade.
     */
    @Test
    fun antesDaPrimeiraTentativa_naoDiz_semRede() {
        val agora = 100_000L
        val recemLigado = saudavel(agora).copy(
            publicando = false,
            houveTentativa = false,
            ultimaPublicacaoOkMs = null,
        )
        val frase = recemLigado.causa(agora)
        assertFalse("veio: $frase", frase.contains("sem rede"))
        assertTrue("veio: $frase", frase.contains("Aguardando"))
    }

    /** Sem correção nenhuma, a frase é a espera — não um silêncio de 4 min de nada. */
    @Test
    fun semCorrecaoNenhuma_aguardaAPrimeira() {
        val agora = 100_000L
        val zerado = saudavel(agora).copy(ultimaCorrecaoMs = null)
        assertTrue(zerado.causa(agora).contains("primeira correção de GPS"))
    }

    // ── `viva` exige os dois lados ────────────────────────────────────────────

    /**
     * `viva = publicando && coletando`, e o contra-teste é exigir que **cada um
     * sozinho** derrube a capacidade. Um `viva = publicando` passaria no primeiro
     * caso; um `viva = coletando`, no segundo.
     */
    @Test
    fun viva_exigeOsDoisLados_publicarEColetar() {
        val base = saudavel()
        assertTrue(base.viva)
        assertFalse("publicar sem coletar é um carimbo velho que não vai se mover",
            base.copy(motivoDaColeta = MotivoDaColeta.SEM_PERMISSAO).viva)
        assertFalse("coletar sem publicar é o GPS acordando para nada",
            base.copy(publicando = false).viva)
    }

    // ── O carimbo de duração ──────────────────────────────────────────────────

    /**
     * Sem "0 s". Zero segundos sugere instantâneo, e o que o agente lê como
     * instantâneo ele usa como certeza — a mesma razão pela qual o carimbo do par
     * diz "agora" em vez de "há 0 s".
     */
    @Test
    fun oCarimbo_naoDizZero_eMudaDeUnidadeNaHoraCerta() {
        assertEquals("1 s", haQuantoTempo(0L))
        assertEquals("1 s", haQuantoTempo(999L))
        assertEquals("59 s", haQuantoTempo(59_000L))
        assertEquals("1 min", haQuantoTempo(60_000L))
        assertEquals("59 min", haQuantoTempo(59 * 60_000L))
        assertEquals("1 h", haQuantoTempo(3_600_000L))
    }

    /**
     * **A tolerância de silêncio é do MODO, não uma constante.**
     *
     * Em Standby a correção chega de 5 em 5 min e 4 min de silêncio é o
     * funcionamento normal; em Ativo, 4 min é o dobro do batimento. Com uma
     * constante única, um dos dois modos passa a mentir — e o que mente com
     * alarme falso é pior, porque ensina a ignorar o indicador.
     */
    @Test
    fun aToleranciaDeSilencio_dependeDoModo() {
        val agora = 1_000_000L
        val quatroMinDeSilencio = saudavel(agora).copy(ultimaCorrecaoMs = agora - 240_000L)

        val emAtivo = quatroMinDeSilencio.copy(silencioToleradoMs = 120_000L).causa(agora)
        val emStandby = quatroMinDeSilencio.copy(silencioToleradoMs = 600_000L).causa(agora)

        assertTrue("em Ativo, 4 min é o dobro do batimento: $emAtivo",
            emAtivo.contains("Sem correção de GPS"))
        assertFalse("em Standby, 4 min é o normal: $emStandby",
            emStandby.contains("Sem correção de GPS"))
    }

    /**
     * O limiar de Ativo é **exatamente** `2 × batimentoEfetivo`, que é
     * `PoliticaDePosicao.OBSOLETO_S`: o agente é avisado no instante em que começa
     * a esmaecer no mapa dos outros. Contra-teste nos dois lados da fronteira.
     */
    @Test
    fun oLimiar_ehOMesmoInstanteEmQueEuComecoAEsmaecerParaOsOutros() {
        val agora = 1_000_000L
        val base = saudavel(agora).copy(silencioToleradoMs = 120_000L)

        val logoAntes = base.copy(ultimaCorrecaoMs = agora - 120_000L).causa(agora)
        val logoDepois = base.copy(ultimaCorrecaoMs = agora - 120_001L).causa(agora)

        assertFalse("no limiar ainda não avisa: $logoAntes", logoAntes.contains("Sem correção"))
        assertTrue("um milissegundo depois avisa: $logoDepois", logoDepois.contains("Sem correção"))
    }
}
