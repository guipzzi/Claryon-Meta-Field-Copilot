package com.claryon.field.radio

import com.claryon.agent.FalhaOperacional
import com.claryon.field.agent.ClaryonIntentExecutor.TrocaDeGrupo
import com.claryon.net.GrupoFalado
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de `specs/troca-de-grupo-por-voz.spec.md`, aceite por aceite.
 *
 * O que estes testes protegem não é uma função — é a diferença entre **recusar** e
 * **adivinhar**. Trocar de talk group redireciona a voz do agente para outra
 * guarnição; se a resolução erra, ele pede apoio para a unidade errada acreditando
 * ter pedido para a certa, e não tem como descobrir.
 */
class TrocaDeGrupoPorVozTest {

    private val alfa = GrupoFalado(id = "id-alfa", rotuloFalado = "guarnicao tres", nome = "GTA-3 Alfa")
    private val bravo = GrupoFalado(id = "id-bravo", rotuloFalado = "guarnicao quatro", nome = "GTA-4 Bravo")

    /** Registra o que o rádio recebeu, para os testes de "não tocou no socket". */
    private class RadioFalso(private val aceita: Boolean = true) {
        val trocasPedidas = mutableListOf<String>()
        val trocar: suspend (String) -> Boolean = { id ->
            trocasPedidas += id
            aceita
        }
    }

    private fun politica(
        lexico: List<GrupoFalado>? = listOf(alfa, bravo),
        transmitindo: Boolean = false,
        corrente: String = "id-alfa",
        radio: RadioFalso? = RadioFalso(),
    ) = PoliticaDeTrocaDeGrupo(
        resolvedor = ResolvedorDeGrupo { lexico },
        transmitindo = { transmitindo },
        grupoCorrenteId = { corrente },
        trocador = { radio?.trocar },
    )

    // ── Aceite 2: casa por igualdade e devolve o NOME ─────────────────────────

    @Test
    fun rotuloConhecido_trocaEDevolveONomeDeExibicao() = runTest {
        val radio = RadioFalso()
        val d = politica(radio = radio).decidir("guarnicao quatro")

        assertEquals(TrocaDeGrupo.Trocado("GTA-4 Bravo"), d.resultado)
        assertEquals("pediu o id certo ao rádio", listOf("id-bravo"), radio.trocasPedidas)
        assertEquals("o dono do estado deve adotar o grupo novo", bravo, d.novoGrupo)
    }

    @Test
    fun oRotuloEhComparadoNormalizado_nosDoisLados() = runTest {
        // O STT devolve com acento e caixa; o servidor guarda normalizado. Comparar
        // sem normalizar os dois lados é como não normalizar: falha em silêncio.
        for (dito in listOf("Guarnição Quatro", "GUARNIÇÃO QUATRO", " guarnicao  quatro ")) {
            val d = politica().decidir(dito)
            assertEquals("'$dito' deveria casar", TrocaDeGrupo.Trocado("GTA-4 Bravo"), d.resultado)
        }
    }

    // ── Aceite 3: recusa sem revelar, e SEM casamento aproximado ──────────────

    @Test
    fun rotuloDesconhecido_recusa() = runTest {
        val radio = RadioFalso()
        val d = politica(radio = radio).decidir("guarnicao nove")

        assertEquals(TrocaDeGrupo.NaoReconhecido, d.resultado)
        assertTrue("não pode tocar no rádio", radio.trocasPedidas.isEmpty())
        assertNull(d.novoGrupo)
    }

    /**
     * **O teste que guarda a proibição de casamento aproximado.**
     *
     * "agora nisso são quatro" é literalmente o que o `ggml-tiny` devolveu para
     * "a guarnição" na medição de 2026-08-17. Uma distância de edição resolveria — e
     * é exatamente por isso que está proibido: converteria erro de transcrição em
     * erro de despacho, e esconderia o defeito do STT em vez de expô-lo.
     *
     * Se algum dia este teste começar a falhar, alguém adicionou fuzzy matching. A
     * decisão é da spec, não do código.
     */
    @Test
    fun transcricaoParecida_naoCasa_eIssoEhDeliberado() = runTest {
        val radio = RadioFalso()
        for (lixo in listOf(
            "agora nisso são quatro",
            "guarnição quatru",
            "guarniçao quatro alfa",
            "quatro",
        )) {
            val d = politica(radio = radio).decidir(lixo)
            assertEquals(
                "'$lixo' NÃO pode casar — recusar custa uma repetição, adivinhar custa apoio que não vem",
                TrocaDeGrupo.NaoReconhecido,
                d.resultado,
            )
        }
        assertTrue("nenhuma delas pode ter tocado no rádio", radio.trocasPedidas.isEmpty())
    }

    // ── Aceite 4: grupo repetido não custa reconexão ──────────────────────────

    @Test
    fun grupoEmQueJaEstamos_confirmaSemTocarNoRadio() = runTest {
        val radio = RadioFalso()
        val d = politica(corrente = "id-alfa", radio = radio).decidir("guarnicao tres")

        assertEquals(TrocaDeGrupo.Trocado("GTA-3 Alfa"), d.resultado)
        // O contra-teste: `trocarDeGrupo` derruba receptor, transporte e jitter.
        // Se esta lista não estiver vazia, um comando redundante deixa o agente
        // surdo por alguns quadros sem motivo.
        assertTrue(
            "não pode reconectar para ficar onde já está: ${radio.trocasPedidas}",
            radio.trocasPedidas.isEmpty(),
        )
        assertNull("nada a adotar: já era o corrente", d.novoGrupo)
    }

    // ── Aceite 5: transmissão em curso ────────────────────────────────────────

    @Test
    fun transmissaoEmCurso_recusa() = runTest {
        val radio = RadioFalso()
        val d = politica(transmitindo = true, radio = radio).decidir("guarnicao quatro")

        assertEquals(
            TrocaDeGrupo.Falhou(FalhaOperacional.TRANSMISSAO_EM_CURSO),
            d.resultado,
        )
        assertTrue("trocar no ar mandaria o fim da frase para a guarnição errada", radio.trocasPedidas.isEmpty())
    }

    /**
     * **A ordem das guardas é um oráculo, se estiver errada.**
     *
     * Com transmissão em curso, um grupo que EXISTE e um que NÃO existe têm de
     * produzir a mesma resposta. Se a resolução viesse primeiro, quem transmite
     * ouviria "não conheço essa guarnição" para grupo alheio e "fale depois de
     * encerrar" para grupo próprio — dois textos diferentes revelam a existência do
     * grupo, que é justamente o que a spec proíbe.
     */
    @Test
    fun transmitindo_aRecusaNaoRevelaSeOGrupoExiste() = runTest {
        val existe = politica(transmitindo = true).decidir("guarnicao quatro").resultado
        val naoExiste = politica(transmitindo = true).decidir("guarnicao nove").resultado

        assertEquals(
            "as duas respostas TÊM de ser idênticas, senão a recusa é um oráculo",
            existe,
            naoExiste,
        )
    }

    // ── Aceite 6: sem léxico nunca cai para o canal do piloto ─────────────────

    @Test
    fun semLexico_recusaEmVezDeCairNoCanalDoPiloto() = runTest {
        val radio = RadioFalso()
        val d = politica(lexico = null, radio = radio).decidir("guarnicao quatro")

        assertEquals(TrocaDeGrupo.Falhou(FalhaOperacional.SEM_LEXICO_DE_CANAIS), d.resultado)
        assertTrue(radio.trocasPedidas.isEmpty())
    }

    @Test
    fun lexicoVazio_ehTratadoComoSemLexico() = runTest {
        // Lista vazia não é "nenhum grupo endereçável": é sintoma de carga que
        // falhou parcialmente. Tratar como "não reconheço" faria o agente crer que
        // errou o nome quando o problema é a sessão.
        val d = politica(lexico = emptyList()).decidir("guarnicao quatro")
        assertEquals(TrocaDeGrupo.Falhou(FalhaOperacional.SEM_LEXICO_DE_CANAIS), d.resultado)
    }

    // ── Rádio ausente ou recusando ────────────────────────────────────────────

    @Test
    fun radioFechado_recusaEmVezDeFingirTroca() = runTest {
        val d = politica(radio = null).decidir("guarnicao quatro")
        assertEquals(TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO), d.resultado)
        assertNull("não pode adotar grupo que o rádio não abriu", d.novoGrupo)
    }

    /**
     * O rádio pediu e recusou. **Não adotar o grupo** é o ponto: adotar faria o
     * estado e o socket discordarem, e o comando seguinte responderia "já estamos
     * lá" para um grupo em que não estamos — a pior forma de falhar, porque é
     * silenciosa e persistente.
     */
    @Test
    fun radioRecusaATroca_oEstadoNaoAvanca() = runTest {
        val radio = RadioFalso(aceita = false)
        val d = politica(radio = radio).decidir("guarnicao quatro")

        assertEquals(TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO), d.resultado)
        assertEquals("tentou de verdade", listOf("id-bravo"), radio.trocasPedidas)
        assertNull("e NÃO avançou o estado", d.novoGrupo)
    }

    // ── O resolvedor, isolado ─────────────────────────────────────────────────

    @Test
    fun resolvedor_distingueSemLexicoDeNaoReconhecido() {
        assertEquals(
            ResolvedorDeGrupo.Resultado.SemLexico,
            ResolvedorDeGrupo { null }.resolver("guarnicao tres"),
        )
        assertEquals(
            ResolvedorDeGrupo.Resultado.NaoReconhecido,
            ResolvedorDeGrupo { listOf(alfa) }.resolver("guarnicao nove"),
        )
        assertEquals(
            ResolvedorDeGrupo.Resultado.Encontrado(alfa),
            ResolvedorDeGrupo { listOf(alfa) }.resolver("Guarnição Três"),
        )
    }

    @Test
    fun resolvedor_rotuloVazioNaoCasaNada() {
        // Rótulo em branco não pode casar o primeiro item da lista por acidente.
        for (vazio in listOf("", "   ", ",.")) {
            assertEquals(
                "'$vazio' não pode casar",
                ResolvedorDeGrupo.Resultado.NaoReconhecido,
                ResolvedorDeGrupo { listOf(alfa, bravo) }.resolver(vazio),
            )
        }
    }
}
