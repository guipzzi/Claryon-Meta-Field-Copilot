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
    fun enfileiradaDizNaFila() {
        val i = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.ENFILEIRADA)),
        ).single()
        assertEquals(RotuloDeEntrega.NA_FILA, i.rotuloDeEntrega)
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
    fun leituraEmVozDizQueAindaEstaNaFila() {
        val i = montarTrafego(
            listOf(fala(propria = true, entrega = FalaNoGrupo.Entrega.ENFILEIRADA)),
        ).single()
        assertTrue(i.leituraEmVoz.endsWith("Ainda na fila"))
    }

    @Test
    fun horaDesconhecidaNaoEhAnunciada() {
        val i = montarTrafego(listOf(fala(hora = HORA_DESCONHECIDA))).single()
        assertFalse(i.leituraEmVoz.contains("--"))
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
