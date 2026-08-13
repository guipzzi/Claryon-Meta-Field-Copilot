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

    @Test
    fun todaRespostaRespeitaLaconicidade() {
        val intents = listOf(
            Intent.PedirApoio(Prioridade.ALTA, null),
            Intent.IniciarGravacao(null),
            Intent.EncerrarGravacao,
            Intent.ConsultarPlaca(null),
            Intent.ConsultarPlaca("ABC1D23"),
            Intent.NarrarOcorrencia("x"),
            Intent.Emergencia,
            Intent.Detalhar,
            Intent.TrocarModo(ModoOperacao.OCORRENCIA),
            Intent.NaoReconhecida("x"),
        )
        for (intent in intents) {
            val palavras = OperationalResponses.para(intent).trim().split(Regex("\\s+")).size
            assertTrue("Resposta excede 7 palavras: ${OperationalResponses.para(intent)}", palavras <= 7)
        }
    }
}
