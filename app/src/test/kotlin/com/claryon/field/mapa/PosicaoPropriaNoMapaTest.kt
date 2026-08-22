package com.claryon.field.mapa

import com.claryon.net.RespostaDePosicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A posição do PORTADOR — a que ninguém carimbava.**
 *
 * A analogia é o Uber Driver: quando o motorista perde GPS ou rede, o app diz na
 * tela, e o passageiro vê que a posição está velha. Até 21/08 este arquivo fixava
 * o **defeito**: do par a régua existia e era boa, do portador não existia régua
 * nenhuma, e `montarDeGrandezas` afirmava `temPosicaoPropria = true` sobre a lista
 * VAZIA — que é exatamente o que `posicoes_do_grupo` devolve quando quem pergunta
 * nunca publicou, porque o `cross join minha` zera o resultado inteiro.
 *
 * O KDoc daquela versão terminava assim: *"se alguém acrescentar o carimbo da
 * posição própria, eles falham e a falha é o aviso de que este arquivo precisa ser
 * reescrito junto"*. Foi o que aconteceu. Esta é a versão reescrita, e o assunto
 * dela mudou de lado: em vez de fixar a mentira, ela prova que os **três estados
 * que colapsavam em um** agora são distinguíveis.
 *
 *  1. **Não publiquei** — a causa é do portador.
 *  2. **Ninguém publicou** — a causa é do grupo, e o mapa fica em branco só com a
 *     posição própria, sem acusar ninguém.
 *  3. **Não estou recebendo** — a sondagem falhou; nada aqui dentro é afirmação.
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

    // ── Agora o portador também tem ───────────────────────────────────────────

    /**
     * **O carimbo do portador existe, e vem do dado que já viajava no fio.**
     *
     * `idade_solicitante_s` chega em TODA linha de `posicoes_do_grupo` desde a
     * migração `0007` — não custa requisição nova. Ele era lido no ViewModel e
     * morria ali, virando um booleano de tudo-ou-nada; agora atravessa até o estado
     * da tela.
     *
     * Contra-teste: dois valores diferentes têm de sair diferentes. Um campo
     * cravado em zero passaria numa asserção de existência.
     */
    @Test
    fun oEstadoDoMapaCarregaAIdadeDaPosicaoPropria() {
        val fresca = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 3)),
            assinado = true,
        )
        val velha = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 97)),
            assinado = true,
        )

        assertEquals(3, fresca.minhaIdadeS)
        assertEquals(97, velha.minhaIdadeS)
        assertNotEquals(
            "se as duas saíssem iguais, o campo existiria e não carregaria nada",
            fresca.minhaIdadeS,
            velha.minhaIdadeS,
        )
    }

    /** Sem linha nenhuma não há de onde tirar a idade, e ela é nula em vez de zero. */
    @Test
    fun semLinha_aIdadePropriaEhNula_naoZero() {
        val estado = MapaDePares.montarDeGrandezas(emptyList(), assinado = true)
        assertNull(
            "zero seria 'medida agora' — uma afirmação que a resposta vazia não faz",
            estado.minhaIdadeS,
        )
    }

    // ── Os três estados que colapsavam em um ─────────────────────────────────

    /**
     * **O defeito principal, agora do lado certo da asserção.**
     *
     * Lista vazia com a transmissão parada é "eu não publiquei", e o mapa não pode
     * afirmar posição própria em cima disso. Era esse `true` literal que a gaveta
     * lia como *"Ninguém publicando · Nenhum par do talk group está enviando
     * posição agora"*: a causa era do portador e a frase acusava os outros.
     */
    @Test
    fun listaVazia_semTransmissao_naoAfirmaPosicaoPropria() {
        val estado = MapaDePares.montarDeGrandezas(
            emptyList(),
            assinado = true,
            minhaPosicaoSobe = false,
            motivoDaMinhaPosicao = "Sua posição não sobe: o turno não abriu.",
        )

        assertFalse(
            "sem evidência nenhuma de posição própria, o mapa não pode afirmá-la",
            estado.temPosicaoPropria,
        )
        assertFalse(estado.minhaPosicaoSobe)
        assertTrue(
            "e a causa é a do PORTADOR, não uma sobre a guarnição: " +
                "${estado.motivoDaMinhaPosicao}",
            estado.motivoDaMinhaPosicao!!.contains("Sua posição"),
        )
        assertTrue(estado.pares.isEmpty())
    }

    /**
     * **Contra-teste do mesmo ponto**: a MESMA lista vazia, com a transmissão
     * viva, é o outro estado — "ninguém publicou". Os dois têm de divergir, senão
     * a distinção não existe.
     */
    @Test
    fun listaVazia_comTransmissaoViva_ehOOutroEstado() {
        val naoPubliquei = MapaDePares.montarDeGrandezas(
            emptyList(),
            assinado = true,
            minhaPosicaoSobe = false,
            motivoDaMinhaPosicao = "Sua posição não sobe: sem rede.",
        )
        val ninguemPublicou = MapaDePares.montarDeGrandezas(
            emptyList(),
            assinado = true,
            minhaPosicaoSobe = true,
            motivoDaMinhaPosicao = "Última posição enviada há 12 s.",
        )

        assertFalse(naoPubliquei.temPosicaoPropria)
        assertTrue(
            "eu publico: a lista vazia agora fala do grupo, e só aí ela pode",
            ninguemPublicou.temPosicaoPropria,
        )
        assertNotEquals(
            "com o `true` literal de volta, estes dois estados voltam a ser um só",
            naoPubliquei.temPosicaoPropria,
            ninguemPublicou.temPosicaoPropria,
        )
    }

    /**
     * **Com linhas, o próprio `cross join` é a prova.** O servidor não teria
     * calculado distância nenhuma sem a minha posição — então `temPosicaoPropria`
     * é verdade mesmo com o estado local ainda por atualizar (o serviço acabou de
     * subir, o primeiro POST subiu, o `StateFlow` ainda não foi lido).
     */
    @Test
    fun comLinhas_aPosicaoPropriaEhProvadaPelaRespostaDoServidor() {
        val estado = MapaDePares.montarDeGrandezas(
            listOf(par()),
            assinado = true,
            minhaPosicaoSobe = false,
        )
        assertTrue(
            "houve linha, logo houve `cross join minha`, logo o servidor tem a minha posição",
            estado.temPosicaoPropria,
        )
    }

    /** O terceiro estado: não estou recebendo. Distinto dos outros dois. */
    @Test
    fun naoEstouRecebendo_ehUmTerceiroEstado() {
        val fora = EstadoDoMapa.indisponivel(
            "Não foi possível ler as posições da guarnição.",
            minhaPosicaoSobe = true,
            motivoDaMinhaPosicao = "Última posição enviada há 8 s.",
        )

        assertFalse(fora.assinado)
        assertTrue(fora.motivoIndisponivel!!.contains("ler as posições"))
        assertTrue(
            "receber e transmitir falham separado: a sondagem caiu e a minha " +
                "posição continua subindo",
            fora.minhaPosicaoSobe,
        )
    }

    /**
     * As três causas que chegam à tela precisam ser três frases distintas. Uma
     * frase única e verdadeira ("posição indisponível") passaria em qualquer
     * asserção que olhasse um estado de cada vez.
     */
    @Test
    fun osTresEstados_chegamAtelaComoTresFrasesDiferentes() {
        val naoPubliquei = MapaDePares.montarDeGrandezas(
            emptyList(), assinado = true,
            minhaPosicaoSobe = false,
            motivoDaMinhaPosicao = "Sua posição não sobe: o turno não abriu.",
        )
        val ninguemPublicou = MapaDePares.montarDeGrandezas(
            emptyList(), assinado = true,
            minhaPosicaoSobe = true,
            motivoDaMinhaPosicao = "Última posição enviada há 12 s.",
        )
        val naoRecebo = EstadoDoMapa.indisponivel(
            "Não foi possível ler as posições da guarnição.",
            minhaPosicaoSobe = true,
            motivoDaMinhaPosicao = "Última posição enviada há 12 s.",
        )

        // O trio (temPosicaoPropria, motivoIndisponivel != null) tem de separar os
        // três — é exatamente essa tupla que a tela consulta no `when`.
        val assinaturas = listOf(naoPubliquei, ninguemPublicou, naoRecebo)
            .map { it.temPosicaoPropria to (it.motivoIndisponivel != null) }
        assertEquals("três estados, três leituras da tela", 3, assinaturas.toSet().size)
    }

    /**
     * A idade do solicitante não mexe em nenhuma linha de par: as distâncias já
     * vieram calculadas do servidor. Ela decide sobre a tela INTEIRA, no ViewModel,
     * e essa fronteira é deliberada.
     */
    @Test
    fun aIdadeDoSolicitanteNaoMudaALinhaDoPar() {
        val fresca = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 1)),
            assinado = true,
        )
        val podre = MapaDePares.montarDeGrandezas(
            listOf(par(idadeDoSolicitanteS = 86_400)),
            assinado = true,
        )
        assertEquals(
            "a linha do par sai idêntica com a minha posição de um dia atrás",
            fresca.pares.single(),
            podre.pares.single(),
        )
        assertNotEquals(
            "mas o estado da tela sabe a diferença",
            fresca.minhaIdadeS,
            podre.minhaIdadeS,
        )
    }
}
