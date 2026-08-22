package com.claryon.field.mapa

import com.claryon.net.RespostaDePosicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A posição do PORTADOR — a única do mapa que ninguém carimba.**
 *
 * A analogia é o Uber Driver: quando o motorista perde GPS ou rede, o app diz na
 * tela, e o passageiro vê que a posição está velha. Estes testes medem os dois
 * lados dessa régua no Claryon, e eles não estão no mesmo lugar:
 *
 *  - **Do PAR**, a régua existe e é boa. [ParNoMapa.atualizadoHa] aparece em toda
 *    linha, o [Frescor] esmaece aos 2 min e para de afirmar posição aos 10.
 *  - **Do PORTADOR**, não existe régua nenhuma. [EstadoDoMapa] não tem campo para
 *    a idade da posição própria, e [MapaDePares.montarDeGrandezas] afirma
 *    `temPosicaoPropria = true` sem ter recebido evidência de posição própria
 *    nenhuma — inclusive sobre a lista VAZIA, que é exatamente o que o servidor
 *    devolve quando quem pergunta nunca publicou (o `cross join minha` de
 *    `posicoes_do_grupo` zera o resultado inteiro).
 *
 * Os testes abaixo **fixam o comportamento de hoje**, com o defeito nomeado no
 * corpo. Não são aspiração: se alguém acrescentar o carimbo da posição própria,
 * eles falham e a falha é o aviso de que este arquivo precisa ser reescrito junto.
 */
class PosicaoPropriaNoMapaTest {

    private fun par(
        indicativo: String = "Alfa Dois",
        idadeS: Int = 5,
        idadeDoSolicitanteS: Int = 5,
    ) = RespostaDePosicao(
        indicativo = indicativo,
        distanciaM = 400,
        azimuteGraus = 45.0,
        velocidadeMs = 0f,
        idadeS = idadeS,
        idadeDoSolicitanteS = idadeDoSolicitanteS,
    )

    // ── O par tem carimbo de idade em toda linha ──────────────────────────────

    /**
     * Contra-teste das três idades que a tarefa de campo pergunta: 30 s, 2 min e
     * 15 min. Exigir que os três DIFIRAM é o que impede um "sempre ATUAL" de
     * passar — um teste que só olhasse 30 s passaria com a régua desligada.
     */
    @Test
    fun oParDistingueAtualDeVelho_aos30s_aos2min_eAos15min() {
        val trinta = MapaDePares
            .montarDeGrandezas(listOf(par(idadeS = 30)), assinado = true).pares.single()
        val doisMin = MapaDePares
            .montarDeGrandezas(listOf(par(idadeS = 121)), assinado = true).pares.single()
        val quinzeMin = MapaDePares
            .montarDeGrandezas(listOf(par(idadeS = 900)), assinado = true).pares.single()

        assertEquals(Frescor.ATUAL, trinta.frescor)
        assertEquals(Frescor.ESMAECIDO, doisMin.frescor)
        assertEquals(Frescor.ANTIGO, quinzeMin.frescor)

        // O carimbo diz *quanto* velho, e os três precisam sair diferentes.
        assertEquals("há 30s", trinta.atualizadoHa)
        assertEquals("há 2min", doisMin.atualizadoHa)
        assertEquals("há 15min", quinzeMin.atualizadoHa)
        assertEquals(
            "os três carimbos precisam diferir, senão a régua está desligada",
            3,
            setOf(trinta.atualizadoHa, doisMin.atualizadoHa, quinzeMin.atualizadoHa).size,
        )

        // E só o mais velho troca a distância pela idade na frase.
        assertNull(trinta.idadeFalada)
        assertNull(doisMin.idadeFalada)
        assertTrue(quinzeMin.idadeFalada!!.contains("15 minutos"))
    }

    // ── O portador não tem carimbo nenhum ─────────────────────────────────────

    /**
     * **O defeito, fixado.** `EstadoDoMapa` não tem onde guardar a idade da posição
     * própria, embora `RespostaDePosicao.idadeDoSolicitanteS` chegue do servidor em
     * TODA linha. O dado existe no fio, é lido em
     * `MapaViewModel.montarMapa` — e morre ali, virando um booleano de tudo-ou-nada.
     */
    @Test
    fun oEstadoDoMapaNaoTemCampoParaAIdadeDaPosicaoPropria() {
        val campos = EstadoDoMapa::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "EstadoDoMapa ganhou campo de idade própria ($campos) — o carimbo do " +
                "portador passou a existir e este teste precisa ser reescrito",
            campos.none { "minhaidade" in it || "idadepropria" in it || "publicando" in it },
        )
        // O que existe é só a coordenada, sem quando.
        assertTrue("minhalatitude" in campos)
        assertTrue("minhalongitude" in campos)
    }

    /**
     * **Lista vazia afirma posição própria que ninguém provou.**
     *
     * É o estado real de quem nunca publicou: `posicoes_do_grupo` faz
     * `cross join minha`, e sem linha em `agent_positions` o resultado é ZERO
     * linhas — não uma linha dizendo "você não publicou". `montarDeGrandezas`
     * carimba `temPosicaoPropria = true` e `motivoIndisponivel = null` em cima
     * disso, e a gaveta da tela lê esse estado como *"Ninguém publicando ·
     * Nenhum par do talk group está enviando posição agora"*: a causa é minha e a
     * frase acusa os outros.
     */
    @Test
    fun listaVazia_aindaAfirmaTerPosicaoPropria() {
        val estado = MapaDePares.montarDeGrandezas(emptyList(), assinado = true)

        assertTrue(
            "hoje o mapa afirma posição própria sem nenhuma evidência dela",
            estado.temPosicaoPropria,
        )
        assertNull(
            "e não diz motivo nenhum — 'não publiquei' e 'ninguém publicou' " +
                "chegam à tela como o mesmo estado",
            estado.motivoIndisponivel,
        )
        assertTrue(estado.pares.isEmpty())
    }

    /**
     * A mesma afirmação sobrevive à posição própria mais velha que o mundo.
     *
     * `montarDeGrandezas` não olha [RespostaDePosicao.idadeDoSolicitanteS] — quem
     * olha é `MapaViewModel.montarMapa`, **e só quando a lista não está vazia**.
     * Este teste marca a fronteira: no objeto puro, a idade do solicitante não
     * muda absolutamente nada.
     */
    @Test
    fun aIdadeDoSolicitanteNaoMudaNadaNoObjetoPuro() {
        val fresca = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 1)),
            assinado = true,
        )
        val podre = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 86_400)),
            assinado = true,
        )
        assertEquals(fresca.temPosicaoPropria, podre.temPosicaoPropria)
        assertEquals(fresca.motivoIndisponivel, podre.motivoIndisponivel)
        assertEquals(
            "a linha do par sai idêntica com a minha posição de um dia atrás",
            fresca.pares.single(),
            podre.pares.single(),
        )
    }
}
