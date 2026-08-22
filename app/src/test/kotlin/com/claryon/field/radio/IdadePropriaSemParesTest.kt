package com.claryon.field.radio

import com.claryon.field.local.EstadoDaTransmissao
import com.claryon.net.RespostaDePosicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **O portador aparecia "sem posição" na própria guarnição enquanto publicava.**
 *
 * `posicoes_do_grupo` (`servidor/migracoes/0021:130-149`) faz `cross join minha` **e**
 * filtra `a.id <> eu.id`: a resposta só traz linhas de **pares**. Até 22/08 a idade da
 * posição própria saía de `lista.firstOrNull()?.idadeDoSolicitanteS` — ou seja, de uma
 * linha que só existe se **outra pessoa** tiver publicado.
 *
 * Sem par com posição, a lista vem vazia e o portador era declarado sem posição. E os
 * casos em que isso acontece não são raros: guarnição de um, colegas numa garagem,
 * começo de turno, ou todo mundo em área sem sinal — que é justamente a região que o
 * relato da PMERJ descreve como rotina.
 *
 * O defeito não era de cálculo. Era usar **evidência sobre os outros** para afirmar
 * algo sobre **si**.
 *
 * O conserto troca a fonte quando a do servidor não vem: `TransmissaoDePosicao` guarda
 * o instante do último POST **aceito**, é escrita pelo coletor, e não depende de par
 * nenhum.
 */
class IdadePropriaSemParesTest {

    // Distância, azimute e velocidade não participam desta decisão — só a idade.
    // `azimuteGraus = null` é a dupla na mesma viatura, que o KDoc de
    // `RespostaDePosicao` chama de configuração mais comum do policiamento.
    private fun par(indicativo: String, idadeS: Int, idadeSolicitanteS: Int) =
        RespostaDePosicao(
            indicativo = indicativo,
            distanciaM = 120,
            azimuteGraus = null,
            velocidadeMs = null,
            idadeS = idadeS,
            idadeDoSolicitanteS = idadeSolicitanteS,
        )

    /** Publicação aceita há [haSegundos], no relógio de [agoraMs]. */
    private fun publicouHa(haSegundos: Int, agoraMs: Long = 1_000_000L) =
        EstadoDaTransmissao(
            publicando = true,
            ultimaPublicacaoOkMs = agoraMs - haSegundos * 1_000L,
        )

    @Test
    fun semNenhumPar_masPublicando_aIdadePropriaVemDaEvidenciaLocal() {
        val idade = idadePropriaDe(
            lista = emptyList(),
            transmissao = publicouHa(40),
            agoraMs = 1_000_000L,
        )

        assertEquals(
            "Lista de pares vazia e publicação aceita há 40 s: o portador foi " +
                "declarado SEM POSIÇÃO enquanto publicava. Se isto falhou, a fonte " +
                "voltou a ser `lista.firstOrNull()` e o defeito de 22/08 voltou junto.",
            40,
            idade,
        )
    }

    /**
     * **O servidor tem precedência, e a ordem não é arbitrária.**
     *
     * Um POST aceito não prova que a linha sobreviveu à retenção do outro lado.
     * Inverter a ordem faria o aparelho confiar no próprio otimismo — e a idade que
     * ele mostraria seria menor que a real, que é o erro mais perigoso dos dois.
     */
    @Test
    fun comResposta_doServidor_elaVence_mesmoQueOLocalSejaMaisNovo() {
        val idade = idadePropriaDe(
            lista = listOf(par("BRAVO UM", idadeS = 12, idadeSolicitanteS = 95)),
            transmissao = publicouHa(3), // local acha que publicou agorinha
            agoraMs = 1_000_000L,
        )

        assertEquals(
            "O local venceu o servidor. A idade mostrada ficaria MENOR que a real, " +
                "que é o erro perigoso: o agente se acha no mapa quando já esmaeceu.",
            95,
            idade,
        )
    }

    @Test
    fun servidorSemValorUtil_caiParaOLocal() {
        // `Int.MAX_VALUE` é como a RPC diz "não sei" (`optInt` com default).
        val idade = idadePropriaDe(
            lista = listOf(par("BRAVO UM", idadeS = 12, idadeSolicitanteS = Int.MAX_VALUE)),
            transmissao = publicouHa(70),
            agoraMs = 1_000_000L,
        )
        assertEquals("MAX_VALUE é 'não sei', e devia ter caído para o local", 70, idade)
    }

    /**
     * **"Sem posição" continua sendo dizível — e é o outro lado do conserto.**
     *
     * Uma idade sempre presente seria tão mentirosa quanto a ausência de 22/08, só que
     * na direção confortável. Quem nunca publicou não tem idade, e a tela precisa poder
     * dizer isso.
     */
    @Test
    fun nenhumaDasDuasFontesSabe_entaoSemPosicaoEVerdade() {
        assertNull(
            "Inventou idade sem nenhuma evidência. Sem par e sem POST aceito, " +
                "'sem posição' é a verdade e a tela precisa poder dizê-la.",
            idadePropriaDe(
                lista = emptyList(),
                transmissao = EstadoDaTransmissao(publicando = true, ultimaPublicacaoOkMs = null),
                agoraMs = 1_000_000L,
            ),
        )
    }

    /**
     * Relógio de teste e reinício de processo produzem diferença negativa. Idade
     * negativa não existe — e mostrá-la seria pior que não mostrar nada.
     */
    @Test
    fun relogioParaTras_naoProduzIdadeNegativa() {
        val idade = idadePropriaDe(
            lista = emptyList(),
            transmissao = EstadoDaTransmissao(publicando = true, ultimaPublicacaoOkMs = 2_000_000L),
            agoraMs = 1_000_000L,
        )
        assertEquals("idade negativa vazou para a tela", 0, idade)
    }
}
