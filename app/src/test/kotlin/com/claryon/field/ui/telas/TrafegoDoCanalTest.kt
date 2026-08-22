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
        cortadaPelaRede: Boolean = false,
    ) = FalaNoGrupo(id, indicativo, hora, texto, propria, prioridade, entrega, cortadaPelaRede)

    // ── Fala cortada pela rede ────────────────────────────────────────────────
    //
    // O fato chegava ao OUVIDO (earcon + `FALA_DO_COLEGA_CORTADA`) e não ao BALÃO:
    // a tela desenhava a fala truncada campo por campo igual à inteira. Estes
    // travam os dois canais em que ela agora aparece — o rodapé escrito e a
    // leitura em voz.

    @Test
    fun falaCortada_escreveCortadaNoRodape_eAInteiraNaoEscreve() {
        // Contra-teste: as duas configurações rodam e têm de DIFERIR. Asserir só
        // `"cortada"` no caso cortado passaria com um `marcaDoRodape` que devolvesse
        // "cortada" para tudo.
        val cortada = montarTrafego(listOf(fala(cortadaPelaRede = true))).single()
        val inteira = montarTrafego(listOf(fala(cortadaPelaRede = false))).single()
        assertEquals("cortada", marcaDoRodape(cortada))
        assertNull(marcaDoRodape(inteira))
    }

    @Test
    fun falaCortada_naoRoubaORotuloDeEntregaDaFalaPropria() {
        // `cortadaPelaRede` vem primeiro no `when` de `marcaDoRodape`. Isso só é
        // seguro porque os dois campos são mutuamente exclusivos por construção —
        // recebida não tem entrega, própria não tem corte. Este teste trava a
        // metade que o `when` poderia quebrar em silêncio: a própria continua
        // dizendo "não saiu".
        val propria = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.NAO_SAIU)),
        ).single()
        assertEquals("não saiu", marcaDoRodape(propria))
    }

    @Test
    fun falaCortada_entraNaLeituraEmVoz_eDepoisDoTexto() {
        // O tracejado terminal é sinal visual e não sobrevive ao áudio. A ORDEM é
        // parte do critério: anunciar o corte antes do texto faria o leitor de tela
        // qualificar uma frase que ele ainda não leu.
        val i = montarTrafego(listOf(fala(texto = "Apoio na", cortadaPelaRede = true))).single()
        val voz = i.leituraEmVoz
        assertTrue("a leitura não anuncia o corte: $voz", voz.contains(FALA_CORTADA_EM_VOZ))
        assertTrue(
            "o corte foi anunciado ANTES do texto: $voz",
            voz.indexOf(FALA_CORTADA_EM_VOZ) > voz.indexOf("Apoio na"),
        )
    }

    @Test
    fun falaInteira_naoAnunciaCorteNenhum() {
        // O par do teste acima. Sem ele, um `leituraEmVoz` que sempre acrescentasse
        // a frase passaria no anterior.
        val i = montarTrafego(listOf(fala(cortadaPelaRede = false))).single()
        assertFalse(i.leituraEmVoz.contains(FALA_CORTADA_EM_VOZ))
    }

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

    // ── Tempo: o separador mede LACUNA, não hora cheia ────────────────────────
    //
    // Os dois primeiros são **contra-testes da régua anterior**, e é por isso que
    // vêm em par: cada um passaria errado com o defeito de volta. A régua de hora
    // cheia separava 14:58 de 15:01 (virou a hora) e NÃO separava 15:01 de 15:41
    // (mesma hora) — ou seja, ela punha a marca exatamente onde a informação não
    // estava. Se um dia alguém restaurar `faixaDe`, estes dois falham juntos.

    @Test
    fun falasSeguidasNaoSaoCortadas_mesmoVirandoAHora() {
        // 14:58:12 → 15:01:02 são 2 min 50 s: a mesma conversa. A régua velha
        // cortava aqui, porque virou de 14:00 para 15:00.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", hora = "14:58:12"),
                fala(id = "2", hora = "15:01:02"),
            ),
        )
        assertNull(itens[1].separadorDeTempo)
    }

    @Test
    fun silencioLongoSepara_mesmoDentroDaMesmaHora() {
        // 15:01 → 15:41 são 40 min de canal calado. A régua velha não marcava
        // nada, porque as duas caem na faixa 15:00.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", hora = "15:01:00"),
                fala(id = "2", hora = "15:41:00"),
            ),
        )
        assertEquals("40 min sem tráfego", itens[1].separadorDeTempo)
    }

    @Test
    fun oLimiarEhDeQuinzeMinutos_eOSegundoAbaixoDeleNaoSepara() {
        // Par de fronteira. Sem o lado de baixo, qualquer limiar menor passaria
        // neste arquivo — inclusive o zero, que marcaria toda fala.
        assertEquals(15 * 60, LACUNA_QUE_SEPARA_S)
        assertEquals("15 min sem tráfego", separadorDeLacuna("10:00:00", "10:15:00"))
        assertNull(separadorDeLacuna("10:00:00", "10:14:59"))
    }

    @Test
    fun aViradaDaMeiaNoiteNaoInventaSilencio() {
        // Turno da madrugada. 23:58 → 00:03 são 5 minutos; sem a soma do dia
        // daria −23 h 55 min, e um número negativo aqui vira lixo na tela.
        assertNull(separadorDeLacuna("23:58:00", "00:03:00"))
    }

    @Test
    fun aViradaDaMeiaNoiteAindaMedeOSilencioVerdadeiro() {
        // Contra-teste do anterior: se a virada passasse a devolver `null` sempre,
        // o turno da madrugada perderia a régua inteira sem ninguém notar.
        assertEquals("40 min sem tráfego", separadorDeLacuna("23:30:00", "00:10:00"))
    }

    @Test
    fun falaForaDeOrdemNaoAfirmaVinteETresHorasDeSilencio() {
        // `RadioViewModel` acrescenta as falas locais pendentes NO FIM da lista,
        // então um balão local antigo cai depois de um do servidor mais novo. Com
        // a soma da meia-noite isso viraria "23 h sem tráfego" — silêncio que não
        // houve, e logo abaixo de uma fala que o desmente.
        assertNull(separadorDeLacuna("15:10:00", "15:03:00"))
    }

    @Test
    fun oPrimeiroItemNuncaAbreSeparador() {
        // Antes dele não há intervalo medido: a lista é uma janela sobre o canal,
        // não o começo dele.
        val i = montarTrafego(listOf(fala(hora = "14:05:00"))).single()
        assertNull(i.separadorDeTempo)
        assertNull(separadorDeLacuna(null, "14:05:00"))
    }

    @Test
    fun horaDesconhecida_naoInventaLacuna() {
        // `--:--:--` é o fallback do RadioViewModel. Medir contra ele daria um
        // número inventado — o mesmo erro que o esmaecimento do mapa evita.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", hora = "14:05:00"),
                fala(id = "2", hora = HORA_DESCONHECIDA),
                fala(id = "3", hora = "14:50:00"),
            ),
        )
        assertNull(itens[1].separadorDeTempo)
        // …e a hora desconhecida no meio não apaga a régua: a medida seguinte
        // atravessa o buraco a partir da última hora legível, porque o silêncio
        // de 45 min aconteceu de verdade.
        assertEquals("45 min sem tráfego", itens[2].separadorDeTempo)
    }

    @Test
    fun lacunaDeHorasEhEscritaEmHoras() {
        assertEquals("1 h sem tráfego", separadorDeLacuna("08:00:00", "09:00:00"))
        assertEquals("2 h 40 min sem tráfego", separadorDeLacuna("08:00:00", "10:40:00"))
    }

    @Test
    fun oSilencioAbreSequencia_mesmoSendoOMesmoAutor() {
        // Proximidade é o agrupador desta lista. Duas falas do mesmo par separadas
        // por meia hora não são um turno de fala só, e adensá-las diria que são.
        val itens = montarTrafego(
            listOf(
                fala(id = "1", indicativo = "Bravo Um", hora = "15:01:00"),
                fala(id = "2", indicativo = "Bravo Um", hora = "15:41:00"),
            ),
        )
        assertTrue(itens[1].abreSequencia)
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
