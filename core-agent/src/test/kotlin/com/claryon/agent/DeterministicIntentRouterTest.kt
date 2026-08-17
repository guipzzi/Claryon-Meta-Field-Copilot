package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aceite do roteador: 20 frases operacionais reais → intenção correta ou
 * [Intent.NaoReconhecida]. E toda resposta falada respeita a laconicidade
 * (≤ 7 palavras).
 */
class DeterministicIntentRouterTest {

    private val router = DeterministicIntentRouter()

    // ── Regressões da revisão adversarial ─────────────────────────────────────

    @Test
    fun locucaoDeRadioNaoViraComando() {
        // "de novo" estava em DETALHAR e era avaliado antes do léxico: o agente
        // gritava um tiroteio recorrente e o app repetia a última resposta — ou
        // dizia "Nada a repetir." — enquanto nenhum alerta saía.
        val i = router.route("tiroteio de novo na Rui Barbosa")
        assertTrue("veio ${i::class.simpleName}", i is Intent.AlertarOcorrencia)
        assertEquals(Intent.Detalhar, router.route("Claryon, repetir"))
    }

    @Test
    fun socorroNaoApagaOTipoNemOEndereco() {
        // "socorro" na lista de emergência do roteador fazia o despacho sair com
        // "Emergência acionada" e sem endereço — perdendo o que o léxico tinha
        // acabado de extrair. Continua escalando prioridade, agora pelo léxico.
        val i = router.route("tiroteio na Rui Barbosa, socorro")
        assertTrue("veio ${i::class.simpleName}", i is Intent.AlertarOcorrencia)
        assertEquals(Prioridade.EMERGENCIA, (i as Intent.AlertarOcorrencia).ocorrencia.prioridade)
        // Pânico explícito sem conteúdo continua sendo emergência genérica.
        assertEquals(Intent.Emergencia, router.route("emergência"))
    }

    @Test
    fun oIndicativoSaiLimpoDePontuacaoEArtigo() {
        // A RPC casa por igualdade exata. Um "?" fazia o agente ouvir "Alfa Dois?
        // não localizado" — afirmação falsa sobre o companheiro, por um caractere.
        // E o Whisper devolve pontuação por padrão, então é o caso normal.
        assertEquals("Alfa Dois", (router.route("onde está Alfa Dois?") as Intent.ConsultarPosicao).indicativo)
        assertEquals("Alfa Dois", (router.route("onde está o Alfa Dois") as Intent.ConsultarPosicao).indicativo)
        assertEquals("Alfa Dois", (router.route("cadê a Alfa Dois!") as Intent.ConsultarPosicao).indicativo)
    }

    @Test
    fun vinteFrasesOperacionais() {
        // Pedir apoio (com prioridade derivada)
        assertTrue(router.route("Claryon, pedir apoio") is Intent.PedirApoio)
        assertEquals(
            Prioridade.EMERGENCIA,
            (router.route("preciso de apoio, suspeito armado") as Intent.PedirApoio).prioridade,
        )
        assertEquals(
            Prioridade.ALTA,
            (router.route("solicitar apoio urgente") as Intent.PedirApoio).prioridade,
        )
        assertEquals(
            Prioridade.NORMAL,
            (router.route("pedir reforço") as Intent.PedirApoio).prioridade,
        )

        // Gravação
        assertTrue(router.route("iniciar gravação") is Intent.IniciarGravacao)
        assertTrue(router.route("Claryon, gravar") is Intent.IniciarGravacao)
        assertEquals(Intent.EncerrarGravacao, router.route("encerrar gravação"))
        assertEquals(Intent.EncerrarGravacao, router.route("parar de gravar"))

        // Placa (com e sem número)
        assertTrue(router.route("consultar placa") is Intent.ConsultarPlaca)
        assertEquals(
            null,
            (router.route("verificar placa pela câmera") as Intent.ConsultarPlaca).placa,
        )
        assertEquals(
            "ABC1D23",
            (router.route("consultar placa ABC 1D23") as Intent.ConsultarPlaca).placa,
        )
        assertEquals(
            "ABC1234",
            (router.route("checar placa ABC-1234") as Intent.ConsultarPlaca).placa,
        )

        // Emergência
        assertEquals(Intent.Emergencia, router.route("emergência"))
        assertEquals(Intent.Emergencia, router.route("código vermelho"))

        // Narrar / detalhar
        assertTrue(router.route("narrar ocorrência: abordagem na rua X") is Intent.NarrarOcorrencia)
        assertEquals(Intent.Detalhar, router.route("Claryon, detalhar"))

        // Modos
        assertEquals(ModoOperacao.STANDBY, (router.route("modo standby") as Intent.TrocarModo).modo)
        assertEquals(ModoOperacao.OCORRENCIA, (router.route("entrar em modo ocorrência") as Intent.TrocarModo).modo)

        // Não reconhecida
        assertTrue(router.route("qual a previsão do tempo") is Intent.NaoReconhecida)
        assertTrue(router.route("") is Intent.NaoReconhecida)
    }

    // A laconicidade migrou para UtteranceTest: a fala não é mais derivada da
    // intenção (isso era a mentira), e sim do resultado da ação.

    @Test
    fun narracaoQueMencionaPlacaNaoViraConsulta() {
        // "placa" solto não pode sequestrar a narração — a fala do agente para o
        // boletim se perderia.
        val intent = router.route("narrar ocorrência veículo de placa ABC1234 abandonado")
        assertTrue(intent is Intent.NarrarOcorrencia)
    }

    @Test
    fun verboExplicitoAindaConsultaPlaca() {
        val intent = router.route("consultar placa ABC1D23")
        assertTrue(intent is Intent.ConsultarPlaca)
        assertEquals("ABC1D23", (intent as Intent.ConsultarPlaca).placa)
    }

    // ── Troca de talk group por voz ───────────────────────────────────────────
    // Ver `specs/troca-de-grupo-por-voz.spec.md`, aceite 1.

    @Test
    fun trocaDeGrupo_extraiORotuloFalado() {
        val i = router.route("Claryon, mudar para guarnição três")
        assertTrue("veio $i", i is Intent.TrocarDeGrupo)
        // Normalizado do MESMO jeito que o léxico do servidor compara — sem acento,
        // minúsculo. Normalizar diferente nos dois lados é como não normalizar.
        assertEquals("guarnicao tres", (i as Intent.TrocarDeGrupo).rotuloFalado)
    }

    @Test
    fun trocaDeGrupo_aceitaAsVariacoesNaturais() {
        for (frase in listOf(
            "trocar para guarnição três",
            "muda pra guarnição três",
            "troca para guarnição três",
            "mudar pra guarnição três",
        )) {
            val i = router.route("Claryon, $frase")
            assertTrue("'$frase' deveria virar troca, veio $i", i is Intent.TrocarDeGrupo)
            assertEquals(frase, "guarnicao tres", (i as Intent.TrocarDeGrupo).rotuloFalado)
        }
    }

    @Test
    fun trocaDeGrupo_derrubaOsArtigos() {
        // "mudar para a guarnição três" não pede o grupo chamado "a guarnição três".
        val i = router.route("Claryon, mudar para a guarnição três")
        assertEquals("guarnicao tres", (i as Intent.TrocarDeGrupo).rotuloFalado)
    }

    /**
     * **O contra-teste do aceite 1.** Comando sem destino é comando incompleto, e
     * adivinhar o destino é exatamente o que a spec proíbe: trocar para o grupo
     * errado redireciona a voz do agente sem que ele saiba.
     */
    @Test
    fun trocaDeGrupo_semRotulo_naoViraTroca() {
        for (frase in listOf("Claryon, mudar para", "Claryon, trocar para ", "Claryon, mudar para a")) {
            val i = router.route(frase)
            assertTrue(
                "'$frase' não tem destino e NÃO pode virar troca — veio $i",
                i is Intent.NaoReconhecida,
            )
        }
    }

    /**
     * **A ordem do roteador, e o risco aceito 2 da spec.**
     *
     * "mudar para" aparece em fala corrente. Quem disse o verbo explícito de ditado
     * ganha — mesma lição que já protege "modo abordagem" de virar alerta de
     * ocorrência. Se este teste falhar, o agente perde a narração dele e ainda troca
     * de canal sem querer.
     */
    @Test
    fun ditadoVenceATrocaDeGrupo() {
        val i = router.route("Claryon, narrar ocorrência: o suspeito mudou para a rua Rui Barbosa")
        assertTrue("ditado tem de vencer, veio $i", i is Intent.NarrarOcorrencia)
    }

    /**
     * Trocar de modo também vence: "modo ativo" é comando fechado, e uma frase que
     * contenha as duas coisas é ambígua — resolver em favor do comando mais
     * específico é a política do roteador inteiro.
     */
    @Test
    fun trocaDeModoVenceTrocaDeGrupo() {
        val i = router.route("Claryon, modo ativo")
        assertTrue("veio $i", i is Intent.TrocarModo)
    }
}
