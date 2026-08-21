package com.claryon.agent

import com.claryon.common.LaconicityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da regra que define o produto: **toda resposta falada deriva do
 * resultado da ação, nunca do comando recebido.**
 */
class UtteranceTest {

    /**
     * Todos os resultados possíveis. A exaustividade do `when` em `utteranceFor`
     * é garantida pelo compilador (acrescentar um [ActionOutcome] quebra a
     * compilação); esta lista garante que cada um também passe pelas regras de
     * saída abaixo.
     */
    private val todos: List<ActionOutcome> = buildList {
        add(ActionOutcome.ApoioTransmitido(null))
        add(ActionOutcome.ApoioTransmitido(0))
        add(ActionOutcome.ApoioTransmitido(1))
        add(ActionOutcome.ApoioTransmitido(4))
        add(ActionOutcome.ApoioTransmitido(12))
        add(ActionOutcome.ApoioTransmitido(37))
        add(ActionOutcome.ApoioEnfileirado)
        add(ActionOutcome.GravacaoIniciada("GTA-3_007_42"))
        add(ActionOutcome.GravacaoEncerrada(30))
        Restricao.entries.forEach { add(ActionOutcome.PlacaConsultada("ABC1D23", it)) }
        add(ActionOutcome.OcorrenciaRegistrada("oc-1"))
        ModoOperacao.entries.forEach { add(ActionOutcome.ModoTrocado(it)) }
        add(ActionOutcome.GrupoTrocado("GTA-3 Alfa"))
        // O nome vem do CADASTRO. Este é o caso que o teto de sete palavras
        // realmente precisa sustentar — e ele não passaria sem o recorte em
        // `nomeFalavelDeGrupo`.
        add(ActionOutcome.GrupoTrocado("Grupamento Tático de Ações Especiais Três Bravo"))
        add(ActionOutcome.GrupoNaoReconhecido("guarnicao 9"))
        add(ActionOutcome.TransmissaoAberta("GTA-3 Alfa"))
        add(ActionOutcome.ParNaoLocalizado("Alfa Dois"))
        // Os quatro ramos que a lista dizia cobrir e não cobria. Aqui com o pior
        // caso de cada um: indicativo longo, rumo presente (que acrescenta palavra),
        // e contagem desconhecida, que é onde a fala muda de forma.
        Rumo.entries.forEach {
            add(
                ActionOutcome.PosicaoEncontrada(
                    PosicaoRelativa("Alfa Dois", 1_450, it, emMovimento = true, idadeS = 240),
                ),
            )
        }
        TipoDeOcorrencia.entries.forEach { tipo ->
            Prioridade.entries.forEach { p ->
                add(ActionOutcome.AlertaDisparado(tipo, p, destinatarios = null))
                add(ActionOutcome.AlertaDisparado(tipo, p, destinatarios = 12))
            }
        }
        // A citação mais longa que o corpus produz, para o teto valer no pior caso.
        add(ActionOutcome.NormaEncontrada("Art. 359-M-B do CP", "Decreto-Lei 2.848/1940"))
        add(ActionOutcome.NormaNaoEncontrada)
        add(ActionOutcome.NaoEntendi)
        FalhaOperacional.entries.forEach { add(ActionOutcome.Falhou(it)) }
    }

    /**
     * **A lista acima dizia "todos" e não era todos — este teste torna a frase verdadeira.**
     *
     * Achado em 21/08, ao acrescentar `NormaEncontrada`: o KDoc de [todos] afirma
     * "Todos os resultados possíveis", e faltavam **quatro** — `TransmissaoAberta`,
     * `PosicaoEncontrada`, `ParNaoLocalizado` e `AlertaDisparado`. Ou seja, o teto de
     * sete palavras nunca tinha sido verificado nesses ramos, e ninguém saberia:
     * a varredura passava verde porque varria só o que alguém lembrou de listar.
     *
     * O compilador garante que `utteranceFor` TRATE todo [ActionOutcome] — `when`
     * sobre `sealed` não fecha sem isso. O que ele não garante é que alguém MEÇA a
     * saída de cada ramo. Essa parte era disciplina, e disciplina falhou quatro
     * vezes de onze.
     *
     * Agora é reflexão: os subtipos selados vêm da própria classe, e a lista tem de
     * cobrir todos. Acrescentar um resultado sem acrescentá-lo aqui quebra ESTE
     * teste com o nome do que faltou — antes de o produto falar frase longa demais
     * no ouvido de um agente em ocorrência.
     */
    @Test
    fun aListaCobreTodoSubtipoSelado_senaoOTetoNaoEVerificado() {
        // **Reflexão do Java, não `sealedSubclasses`.**
        //
        // `KClass.sealedSubclasses` exige `kotlin-reflect` em runtime e a primeira
        // versão morreu com `KotlinReflectionNotSupportedError`. Arrastar
        // `kotlin-reflect` (≈3 MB) para dentro de `core-agent` por causa de UM teste
        // seria pagar caro por conveniência — e o `CLAUDE.md` manda justificar
        // dependência nova por tamanho e alternativa nativa.
        //
        // A alternativa nativa existe e é exata aqui: os subtipos são declarados
        // ANINHADOS dentro da interface, então o compilador os emite como
        // `ActionOutcome$NormaEncontrada` e `getDeclaredClasses()` os enxerga sem
        // biblioteca nenhuma. O filtro por `isAssignableFrom` evita contar tipo
        // aninhado que não seja resultado.
        val selados = ActionOutcome::class.java.declaredClasses
            .filter { ActionOutcome::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()
        assertTrue(
            "Reflexão não achou subtipo nenhum de ActionOutcome. Sem controle " +
                "positivo esta varredura 'não acha faltante' também quando está " +
                "quebrada — e aí não prova nada.",
            selados.size >= 10,
        )
        val cobertos = todos.mapNotNull { it::class.simpleName }.toSet()
        val faltando = (selados - cobertos).sorted()
        assertEquals(
            "Estes ActionOutcome existem e NÃO passam pela varredura de saída, " +
                "então o teto de 7 palavras não é verificado neles:\n" +
                faltando.joinToString("\n") { "  $it" },
            emptyList<String>(),
            faltando,
        )
    }

    private fun textoDe(u: Utterance): String? = when (u) {
        is Utterance.Falar -> u.texto
        is Utterance.SinalizarEFalar -> u.texto
        is Utterance.Sinalizar -> null
    }

    // ── A regra estrutural ────────────────────────────────────────────────────

    /**
     * **O teste que sustenta a honestidade do produto.**
     *
     * `utteranceFor` só pode aceitar [ActionOutcome]. No dia em que alguém
     * acrescentar uma sobrecarga que aceite [Intent] — por conveniência, para
     * "responder mais rápido" — o app volta a poder falar antes de agir, e este
     * teste falha antes de o defeito chegar ao campo.
     */
    @Test
    fun naoExisteCaminhoDaIntencaoParaAFala() {
        val metodos = Class.forName("com.claryon.agent.UtteranceKt").declaredMethods
        val utterances = metodos.filter { it.name == "utteranceFor" }
        assertTrue("utteranceFor sumiu do arquivo Utterance.kt", utterances.isNotEmpty())

        val aceitamIntent = utterances.filter { m ->
            m.parameterTypes.any { Intent::class.java.isAssignableFrom(it) }
        }
        assertTrue(
            "utteranceFor NÃO pode aceitar Intent — a fala tem de derivar do resultado " +
                "da ação. Sobrecargas encontradas: ${aceitamIntent.map { it.parameterTypes.toList() }}",
            aceitamIntent.isEmpty(),
        )
    }

    // ── Regras de saída ───────────────────────────────────────────────────────

    @Test
    fun todaFalaRespeitaOProtocoloDeLaconicidade() {
        for (outcome in todos) {
            val texto = textoDe(utteranceFor(outcome)) ?: continue
            assertTrue(
                "Excede ${LaconicityPolicy.MAX_WORDS} palavras em $outcome: \"$texto\"",
                LaconicityPolicy.isWithinLimit(texto),
            )
            assertFalse(
                "Tem cortesia em $outcome: \"$texto\"",
                LaconicityPolicy.hasCourtesy(texto),
            )
        }
    }

    @Test
    fun falhaNuncaESilencio() {
        for (falha in FalhaOperacional.entries) {
            val u = utteranceFor(ActionOutcome.Falhou(falha))
            assertTrue(
                "Falha $falha saiu sem earcon — silêncio é indistinguível de app morto",
                u is Utterance.Sinalizar || u is Utterance.SinalizarEFalar,
            )
        }
        assertTrue(utteranceFor(ActionOutcome.NaoEntendi) is Utterance.SinalizarEFalar)
    }

    /**
     * **O earcon carrega a restrição SOZINHO, mesmo agora que a fala existe.**
     *
     * Este teste antes exigia `Utterance.Sinalizar` puro — resultado de consulta
     * jamais falado, porque o alto-falante open-ear entrega ao abordado o que o
     * agente acabou de descobrir. **A decisão humana de 21/08 mudou a regra:** a fala
     * entra, com a ponderação de que o vazamento exige silêncio e volume alto. O §7
     * reserva isso a gente, e a premissa virou item medível da Fase 5.
     *
     * O que este teste passa a guardar é a parte que **não** mudou, e que é o motivo
     * de ser `SinalizarEFalar` e não `Falar`:
     *
     *  1. **O earcon continua obrigatório**, e distinto por restrição. Ele chega em
     *     139 ms; a fala de uma placa custa segundos, porque o Piper expande número
     *     por extenso (medido: "Art. 306, Lei 9.503" dá 3518 ms de áudio). Se um P1
     *     do rádio preemptar a fala, o agente **já recebeu a resposta pelo som**.
     *  2. **Restrições diferentes têm earcons diferentes** — senão o earcon vira
     *     "consulta respondida" e a informação passa a existir só na fala, que é
     *     justamente o que a preempção apaga.
     *  3. **A fala respeita o teto de 7 palavras**, como todo o resto.
     *
     * O contra-teste está no item 2: se alguém colapsar os três earcons num só, este
     * teste falha — e falha por uma razão que o KDoc explica, em vez de por gosto.
     */
    @Test
    fun aRestricaoViajaNoEARCON_mesmoComAFalaLigada() {
        val porRestricao = Restricao.entries.associateWith { r ->
            utteranceFor(ActionOutcome.PlacaConsultada("ABC1D23", r))
        }

        porRestricao.forEach { (r, u) ->
            assertTrue(
                "Restrição $r saiu SEM earcon. A fala de uma placa custa segundos e " +
                    "pode ser preemptada por P1; sem earcon o agente fica sem resposta.",
                u is Utterance.SinalizarEFalar,
            )
            val texto = (u as Utterance.SinalizarEFalar).texto
            assertTrue(
                "A fala de $r não cita a placa: \"$texto\" — sem ela o agente não sabe " +
                    "de qual veículo o aparelho está falando.",
                texto.contains("ABC1D23"),
            )
            assertTrue("Teto de 7 palavras estourado em $r: \"$texto\"", LaconicityPolicy.isWithinLimit(texto))
        }

        val earcons = porRestricao.values.map { (it as Utterance.SinalizarEFalar).earcon }
        assertEquals(
            "Restrições diferentes precisam de earcons DIFERENTES. Colapsá-los faz o " +
                "som dizer apenas 'consulta respondida', e a informação passa a existir " +
                "só na fala — que é exatamente o que um P1 apaga.",
            Restricao.entries.size,
            earcons.toSet().size,
        )
    }

    @Test
    fun gravacaoIniciadaEUmTomSemFala() {
        val u = utteranceFor(ActionOutcome.GravacaoIniciada("x"))
        assertTrue(u is Utterance.Sinalizar)
        assertEquals(com.claryon.common.Earcon.GRAVANDO, (u as Utterance.Sinalizar).earcon)
    }

    // ── Honestidade, caso a caso ──────────────────────────────────────────────

    @Test
    fun enfileiradoNuncaAfirmaEntrega() {
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioEnfileirado))!!.lowercase()
        for (palavra in listOf("recebeu", "receberam", "avisada", "entregue", "solicitado")) {
            assertFalse(
                "\"$texto\" sugere entrega, mas a mensagem só entrou na fila",
                texto.contains(palavra),
            )
        }
        assertTrue("O agente precisa ouvir que está sem rede", texto.contains("sem rede"))
    }

    @Test
    fun transmitidoSemContagemNaoInventaNumero() {
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(null)))!!
        assertEquals("Apoio enviado.", texto)
    }

    @Test
    fun zeroDestinatariosEDito_naoMascaradoComoSucesso() {
        // "Apoio solicitado" com zero unidades por perto faria o agente contar
        // com quem não existe.
        val texto = textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(0)))!!.lowercase()
        assertTrue(texto.contains("nenhuma"))
    }

    @Test
    fun contagemConcordaEmNumero() {
        assertEquals("Uma unidade recebeu.", textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(1))))
        assertEquals("Quatro unidades receberam.", textoDe(utteranceFor(ActionOutcome.ApoioTransmitido(4))))
    }

    @Test
    fun apoioTemPrioridadeDeEmergencia() {
        // Apoio não pode ficar atrás de um informativo na fila de som.
        assertEquals(
            com.claryon.common.Priority.EMERGENCIA,
            utteranceFor(ActionOutcome.ApoioTransmitido(2)).priority,
        )
        assertEquals(
            com.claryon.common.Priority.EMERGENCIA,
            utteranceFor(ActionOutcome.ApoioEnfileirado).priority,
        )
    }

    // ── Troca de grupo ────────────────────────────────────────────────────────

    /**
     * **A recusa não pode repetir o rótulo pedido.**
     *
     * Repetir "não conheço guarnição nove" confirmaria, ao ouvido de quem está por
     * perto, qual grupo o agente tentou abrir. É a mesma classe de vazamento que o
     * servidor evita ao devolver grandezas em vez de coordenada de terceiro — e é
     * fácil de reintroduzir por boa intenção de UX.
     */
    @Test
    fun aRecusaDeGrupo_naoRepeteORotuloPedido() {
        val fala = textoDe(utteranceFor(ActionOutcome.GrupoNaoReconhecido("guarnicao 9")))
        assertTrue("deveria falar algo", fala != null)
        assertFalse(
            "a recusa não pode repetir o rótulo tentado: $fala",
            fala!!.contains("9") || fala.lowercase().contains("nove"),
        )
    }

    /**
     * **A confirmação TEM de nomear o grupo.**
     *
     * O efeito da ação é redirecionar a voz do agente. "Pronto" ou "Trocado" o
     * deixaria falando sem saber para quem — e o contra-teste é que a fala mude
     * quando o grupo muda.
     */
    @Test
    fun aConfirmacaoDeTroca_nomeiaOGrupo() {
        val alfa = textoDe(utteranceFor(ActionOutcome.GrupoTrocado("GTA-3 Alfa")))!!
        val bravo = textoDe(utteranceFor(ActionOutcome.GrupoTrocado("GTA-4 Bravo")))!!

        assertTrue("deveria conter o nome: $alfa", alfa.contains("GTA-3 Alfa"))
        assertTrue(
            "a fala tem de MUDAR com o grupo, senão não nomeia nada",
            alfa != bravo,
        )
    }

    /**
     * O recorte é o que sustenta o teto quando o cadastro é generoso. Sem ele o
     * invariante de sete palavras dependeria da disciplina de quem cadastra.
     */
    @Test
    fun nomeLongoDeCadastro_naoEstouraOTetoDeSetePalavras() {
        val fala = textoDe(
            utteranceFor(
                ActionOutcome.GrupoTrocado("Grupamento Tático de Ações Especiais Três Bravo"),
            ),
        )!!
        val palavras = fala.trim().split(Regex("\\s+")).size
        assertTrue("veio com $palavras palavras: \"$fala\"", palavras <= 7)
        // E o recorte não pode virar fala vazia: nomear ainda é obrigatório.
        assertTrue("o recorte apagou o nome: $fala", fala.contains("Grupamento"))
    }
}
