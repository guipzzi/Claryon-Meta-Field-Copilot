package com.claryon.field.ui.telas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cada teste aqui trava um critério de aceite de `specs/chat.spec.md`.
 *
 * Rodam na JVM porque `montarTrafego` não conhece Compose — que é a razão de o
 * arquivo existir separado. Sem essa separação, o projeto não tem
 * `ui-test-junit4` e nenhum destes critérios seria verificável.
 */
class TrafegoDoCanalTest {

    private fun fala(
        id: String = "1",
        indicativo: String = "Bravo Um",
        hora: String = "14:32:10",
        texto: String = "Em deslocamento.",
        propria: Boolean = false,
        prioridade: Int? = null,
        entrega: FalaNoGrupo.Entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ) = FalaNoGrupo(id, indicativo, hora, texto, propria, prioridade, entrega)

    // ── Lateralidade (critérios 1 a 3) ────────────────────────────────────────

    @Test
    fun falaPropriaSemPrioridade_vaiParaADireita() {
        val i = montarTrafego(listOf(fala(propria = true))).single()
        assertEquals(FormaDoRegistro.PROPRIO, i.forma)
    }

    @Test
    fun falaRecebidaSemPrioridade_vaiParaAEsquerda() {
        val i = montarTrafego(listOf(fala(propria = false))).single()
        assertEquals(FormaDoRegistro.RECEBIDO, i.forma)
    }

    @Test
    fun alertaOcupaALinhaInteira_mesmoSendoProprio() {
        // O caso que a lateralidade sozinha erraria: um P1 meu não é "minha
        // mensagem", é registro do canal.
        val i = montarTrafego(listOf(fala(propria = true, prioridade = 1))).single()
        assertEquals(FormaDoRegistro.REGISTRO_DE_CANAL, i.forma)
    }

    // ── A inversão em relação ao WhatsApp (critérios 6 e 7) ───────────────────

    @Test
    fun falaPropriaEhARebaixada_naoARealcada() {
        val meu = montarTrafego(listOf(fala(propria = true))).single()
        val dele = montarTrafego(listOf(fala(propria = false))).single()
        assertEquals(TokenDeTinta.TINTA_MEDIA, meu.tintaDoTexto)
        assertEquals(TokenDeTinta.TINTA, dele.tintaDoTexto)
    }

    // ── Agrupamento (critério 9) ──────────────────────────────────────────────

    @Test
    fun sequenciaDoMesmoPar_omiteOIndicativoARepeticao() {
        val itens = montarTrafego(
            listOf(
                fala(id = "1", indicativo = "Bravo Um"),
                fala(id = "2", indicativo = "Bravo Um"),
                fala(id = "3", indicativo = "Alfa Dois"),
            ),
        )
        assertTrue(itens[0].mostraIndicativo)
        assertFalse(itens[1].mostraIndicativo)
        assertTrue(itens[2].mostraIndicativo)
    }

    @Test
    fun alertaSempreMostraOIndicativo_mesmoEmSequencia() {
        // Largura inteira apaga o lado; sem o indicativo, P1 próprio e P1
        // recebido ficam idênticos.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", indicativo = "Bravo Um", prioridade = 1),
                fala(id = "2", indicativo = "Bravo Um", prioridade = 1),
            ),
        )
        assertTrue(itens[1].mostraIndicativo)
    }

    // ── Tempo (critérios 11 e 12) ─────────────────────────────────────────────

    @Test
    fun trocaDeHora_abreSeparadorDeFaixa() {
        val itens = montarTrafego(
            listOf(
                fala(id = "1", hora = "14:05:00"),
                fala(id = "2", hora = "14:58:00"),
                fala(id = "3", hora = "15:02:00"),
            ),
        )
        assertEquals("14:00", itens[0].faixaHoraria)
        assertNull("mesma faixa não repete separador", itens[1].faixaHoraria)
        assertEquals("15:00", itens[2].faixaHoraria)
    }

    @Test
    fun horaDesconhecida_naoInventaFaixa() {
        // `--:--:--` é o fallback do RadioViewModel. Inventar "--:00" seria a
        // interface afirmando o que o dado não sustenta.
        val i = montarTrafego(listOf(fala(hora = HORA_DESCONHECIDA))).single()
        assertNull(i.faixaHoraria)
    }

    // ── Entrega (critérios 17 a 19) ───────────────────────────────────────────

    @Test
    fun rotuloDeEntregaSoApareceNaFalaPropria() {
        val meu = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.ENVIADA)),
        ).single()
        val dele = montarTrafego(
            listOf(fala(propria = false, entrega = FalaNoGrupo.Entrega.RECEBIDA)),
        ).single()
        assertEquals(RotuloDeEntrega.ENVIADA, meu.rotuloDeEntrega)
        assertNull(dele.rotuloDeEntrega)
    }

    @Test
    fun semRedeDizQueNaoSaiu_porqueNaoHaFila() {
        val i = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.NAO_SAIU)),
        ).single()
        assertEquals(RotuloDeEntrega.NAO_SAIU, i.rotuloDeEntrega)
    }

    // ── Gramática cromática (critério 20) ─────────────────────────────────────

    @Test
    fun estadoDeEntregaNuncaProduzTokenDePrioridade() {
        // Cor já significa prioridade e transmissão. Uma terceira gramática faria
        // as três perderem sentido.
        val prioridades = setOf(TokenDeCalha.P1, TokenDeCalha.P2, TokenDeCalha.P3)
        FalaNoGrupo.Entrega.entries.forEach { e ->
            val i = montarTrafego(listOf(fala(propria = true, entrega = e))).single()
            assertFalse(
                "entrega $e não pode pintar calha de prioridade",
                i.calha in prioridades,
            )
        }
    }

    // ── Rolagem (critérios 14 a 16) ───────────────────────────────────────────

    @Test
    fun pertoDoFim_acompanha() {
        assertTrue(deveRolarParaOFim(ultimoVisivel = 47, ultimoIndice = 49))
    }

    @Test
    fun lendoOHistorico_naoArrancaALeituraDaMao() {
        assertFalse(deveRolarParaOFim(ultimoVisivel = 10, ultimoIndice = 49))
    }

    @Test
    fun listaVazia_naoTentaRolar() {
        assertFalse(deveRolarParaOFim(ultimoVisivel = 0, ultimoIndice = -1))
    }

    // ── Acessibilidade (critérios 24 e 25) ────────────────────────────────────

    @Test
    fun leitorDeTelaAnunciaVoce_porqueOLadoNaoSobreviveAoAudio() {
        val i = montarTrafego(listOf(fala(propria = true, texto = "A caminho."))).single()
        assertTrue(i.leituraEmVoz.startsWith("Você"))
        assertTrue(i.leituraEmVoz.contains("A caminho."))
    }

    @Test
    fun leituraEmVozAbreComAClassificacao() {
        val i = montarTrafego(listOf(fala(prioridade = 1, texto = "Tiroteio."))).single()
        assertTrue(i.leituraEmVoz.startsWith("P1 emergência"))
    }

    @Test
    fun leituraEmVozDizQueNaoSaiu() {
        val i = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.NAO_SAIU)),
        ).single()
        assertTrue(i.leituraEmVoz.endsWith("Não saiu"))
    }

    @Test
    fun horaDesconhecidaNaoEhAnunciada() {
        val i = montarTrafego(listOf(fala(hora = HORA_DESCONHECIDA))).single()
        assertFalse(i.leituraEmVoz.contains("--"))
    }

    // ── Procedência: o ataque é personificação, não escuta ────────────────────

    @Test
    fun indicativoVazio_viraOrigemNaoConfirmada() {
        // Vazio é o que `HistoricoDoCanal.falas` devolve quando o `join` de
        // autoria não fecha — o autor não está no cadastro que este agente pode
        // ver. Não é "sem nome": é "não consegui atribuir".
        val i = montarTrafego(listOf(fala(indicativo = ""))).single()
        assertEquals(Procedencia.NAO_CONFIRMADA, i.procedencia)
        assertEquals(AUTOR_NAO_CONFIRMADO, i.autorExibido)
    }

    @Test
    fun autoriaConferida_naoViraNaoConfirmada() {
        // Contra-teste do anterior: se este passasse junto com o de cima sem o
        // `isBlank()`, a marca apareceria em toda fala e não significaria nada.
        val i = montarTrafego(listOf(fala(indicativo = "Bravo Um"))).single()
        assertEquals(Procedencia.CONFIRMADA, i.procedencia)
        assertEquals("Bravo Um", i.autorExibido)
    }

    @Test
    fun falaPropriaNuncaEhNaoConfirmada() {
        // O balão local nasce neste aparelho. Marcá-lo como duvidoso seria o
        // aparelho desconfiando de si mesmo — e gastaria o sinal onde ele não
        // informa nada.
        val i = montarTrafego(listOf(fala(indicativo = "", propria = true))).single()
        assertEquals(Procedencia.CONFIRMADA, i.procedencia)
    }

    @Test
    fun duasNaoConfirmadasSeguidas_naoSaoAgrupadas() {
        // Agrupar afirmaria que são a mesma pessoa. Não há nada que sustente
        // isso: os dois indicativos vazios podem ser dois emissores diferentes.
        val itens = montarTrafego(listOf(fala(id = "1", indicativo = ""), fala(id = "2", indicativo = "")))
        assertTrue(itens[1].abreSequencia)
    }

    @Test
    fun naoConfirmada_naoEscreveOAvisoDuasVezes() {
        // Quem diz de onde veio é a faixa de procedência. Repetir a frase na
        // linha do autor gastava duas linhas do bloco para informar uma — o
        // primeiro desenho fazia isso, e a captura no emulador mostrou.
        val i = montarTrafego(listOf(fala(indicativo = ""))).single()
        assertFalse(i.mostraIndicativo)
        // …e o leitor de tela continua recebendo o aviso, que é o canal que
        // sobrou para quem não vê a faixa.
        assertTrue(i.leituraEmVoz.startsWith(AUTOR_NAO_CONFIRMADO))
    }

    @Test
    fun leituraEmVozAvisaAOrigemDuvidosa_antesDoTexto() {
        // O tracejado da calha e a faixa acima da fala são sinais VISUAIS. Sem
        // isto, quem ouve a tela receberia a frase de um desconhecido com a mesma
        // autoridade da de um colega.
        val i = montarTrafego(listOf(fala(indicativo = "", texto = "Apoio na praça."))).single()
        assertTrue(i.leituraEmVoz.startsWith(AUTOR_NAO_CONFIRMADO))
        assertTrue(i.leituraEmVoz.indexOf(AUTOR_NAO_CONFIRMADO) < i.leituraEmVoz.indexOf("Apoio"))
    }

    @Test
    fun p1NaoConferidoMantemAsDuasMarcas() {
        // Prioridade e procedência são ortogonais, e o caso que mais importa é
        // justamente o cruzamento: um P1 forjado. A calha continua P1 — pode ser
        // pedido de apoio real — e a procedência entra por outro canal.
        val i = montarTrafego(listOf(fala(indicativo = "", prioridade = 1))).single()
        assertEquals(TokenDeCalha.P1, i.calha)
        assertEquals(Procedencia.NAO_CONFIRMADA, i.procedencia)
        // E o critério 26 não é atropelado: o alerta não escreve um nome que não
        // tem. A faixa de procedência ocupa o lugar do indicativo.
        assertFalse(i.mostraIndicativo)
        assertTrue(i.leituraEmVoz.startsWith("P1 emergência"))
        assertTrue(i.leituraEmVoz.contains(AUTOR_NAO_CONFIRMADO))
    }

    @Test
    fun alertaConferidoEscreveOIndicativo() {
        // Contra-teste: se o ramo de `REGISTRO_DE_CANAL` passasse a devolver
        // sempre `false`, o critério 26 morria em silêncio — um P1 próprio
        // ficaria indistinguível de um P1 recebido.
        val i = montarTrafego(listOf(fala(indicativo = "Alfa Dois", prioridade = 1))).single()
        assertTrue(i.mostraIndicativo)
    }

    // ── Agrupamento por proximidade ───────────────────────────────────────────

    @Test
    fun falaPropriaNaoRepeteOIndicativo_porqueOLadoJaDiz() {
        // "VOCÊ" em cada bloco gasta a linha do cabeçalho sem informar: o agente
        // sabe o que disse, e a lateralidade já carrega a autoria.
        val i = montarTrafego(listOf(fala(propria = true))).single()
        assertFalse(i.mostraIndicativo)
        // …mas o leitor de tela continua ouvindo, porque lado não sobrevive ao áudio.
        assertTrue(i.leituraEmVoz.startsWith("Você"))
    }

    @Test
    fun alertaProprioMantemOIndicativo_mesmoSemLado() {
        // Critério 26: largura inteira apaga a lateralidade justamente no
        // registro mais importante.
        val i = montarTrafego(listOf(fala(propria = true, prioridade = 1))).single()
        assertTrue(i.mostraIndicativo)
    }

    @Test
    fun continuacaoDoMesmoAutor_naoAbreSequencia() {
        val itens = montarTrafego(
            listOf(
                fala(id = "1", indicativo = "Bravo Um"),
                fala(id = "2", indicativo = "Bravo Um"),
                fala(id = "3", indicativo = "Alfa Dois"),
            ),
        )
        assertTrue("primeiro item sempre abre", itens[0].abreSequencia)
        assertFalse("mesmo autor adensa", itens[1].abreSequencia)
        assertTrue("autor novo respira", itens[2].abreSequencia)
    }

    @Test
    fun trocaDeLado_abreSequencia() {
        // Contra-teste do anterior: o agrupamento não pode olhar só o indicativo.
        // Recebida e própria do mesmo indicativo são dois turnos, não um.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", indicativo = "Bravo Um", propria = false),
                fala(id = "2", indicativo = "Bravo Um", propria = true),
            ),
        )
        assertTrue(itens[1].abreSequencia)
    }

    // ── Voltar ao fim do histórico ────────────────────────────────────────────

    @Test
    fun lendoLongeDoFim_ofereceOCaminhoDeVolta() {
        // A recusa de `deveRolarParaOFim` é o que cria a necessidade: sem porta,
        // quem subiu fica rolando com o polegar enquanto o canal anda.
        assertEquals(39, registrosAbaixoDaLeitura(ultimoVisivel = 10, ultimoIndice = 49))
    }

    @Test
    fun acompanhandoOCanal_naoOfereceNada() {
        // Contra-teste: enquanto a lista se rola sozinha, um botão para ir ao fim
        // seria um controle que não faz nada. As duas decisões têm de concordar.
        assertEquals(0, registrosAbaixoDaLeitura(ultimoVisivel = 47, ultimoIndice = 49))
        assertTrue(deveRolarParaOFim(ultimoVisivel = 47, ultimoIndice = 49))
    }

    @Test
    fun listaVazia_naoOfereceCaminhoDeVolta() {
        assertEquals(0, registrosAbaixoDaLeitura(ultimoVisivel = 0, ultimoIndice = -1))
    }

    // ── Estabilidade (critério 13) ────────────────────────────────────────────

    @Test
    fun listaIgual_produzResultadoIgual() {
        // Sustenta o reaproveitamento por `remember`: sem igualdade estrutural, a
        // recarga de 10 s remontaria a tela inteira sem nada ter mudado.
        val entrada = listOf(fala(id = "1"), fala(id = "2", propria = true))
        assertEquals(montarTrafego(entrada), montarTrafego(entrada))
    }
}
