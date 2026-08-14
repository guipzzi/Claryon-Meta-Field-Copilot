package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Prioridade
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.claryon.evidence.ChunkHash
import com.claryon.evidence.CustodyManifest
import com.claryon.evidence.EvidenceVault
import com.claryon.evidence.OccurrenceContext
import com.claryon.evidence.RecordingHandle
import com.claryon.sync.FakeSyncGateway
import com.claryon.sync.FileOutbox
import com.claryon.sync.TacticalDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Cenários de caos do executor de intenções.**
 *
 * O caminho feliz já é coberto pelo `VoiceCycleTest`. Aqui exercitamos o que
 * acontece quando as coisas dão errado — que é onde este produto vive: sem rede,
 * cofre falhando, comandos repetidos sob estresse, exceção inesperada.
 *
 * A invariante que todos compartilham: **nunca lança, nunca fica em silêncio, e
 * nunca afirma uma ação que não aconteceu.**
 */
class ClaryonIntentExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Dublês ────────────────────────────────────────────────────────────────

    private class CofreFake(
        var falharAoAbrir: Boolean = false,
        var falharAoFinalizar: Boolean = false,
        var explodir: Boolean = false,
    ) : EvidenceVault {
        var segmentos = 0
        var aberto: String? = null

        override suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle> {
            if (explodir) error("cofre explodiu")
            if (falharAoAbrir) {
                return Result.failure(ClaryonError.Evidence("EVID_X", "falha simulada"))
            }
            aberto = "oc-1"
            return Result.success(RecordingHandle("oc-1"))
        }

        override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<ChunkHash> {
            segmentos++
            return Result.success(ChunkHash(segmentos - 1, "hash$segmentos", null))
        }

        override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> {
            if (falharAoFinalizar) {
                return Result.failure(ClaryonError.Evidence("EVID_Y", "falha simulada"))
            }
            aberto = null
            return Result.success(
                CustodyManifest(
                    handle = handle,
                    chain = List(segmentos) { ChunkHash(it, "h$it", null) },
                    finalizedAtEpochMillis = 1L,
                ),
            )
        }
    }

    private fun executor(
        cofre: EvidenceVault = CofreFake(),
        online: Boolean = false,
        aoTrocarModo: suspend (ModoOperacao) -> Unit = {},
    ): ClaryonIntentExecutor {
        val outbox = FileOutbox(tmp.newFolder())
        val dispatcher = TacticalDispatcher(
            outbox = outbox,
            gateway = FakeSyncGateway(online = online),
            novoId = { "id-1" },
            agora = { 100L },
        )
        return ClaryonIntentExecutor(
            cofre = cofre,
            despachante = dispatcher,
            identidade = Identidade("007", "GTA-3", "GTA-3", "COPOM"),
            agora = { 42L },
            aoTrocarModo = aoTrocarModo,
        )
    }

    private fun textoDe(o: ActionOutcome): String? = when (val u = utteranceFor(o)) {
        is Utterance.Falar -> u.texto
        is Utterance.SinalizarEFalar -> u.texto
        is Utterance.Sinalizar -> null
    }

    // ── Sem rede ──────────────────────────────────────────────────────────────

    @Test
    fun semRede_apoioEntraNaFila_eNaoAfirmaEnvio() = runTest {
        val outcome = executor(online = false)
            .execute(Intent.PedirApoio(Prioridade.EMERGENCIA, "suspeito armado"))

        assertEquals(ActionOutcome.ApoioEnfileirado, outcome)
        val fala = textoDe(outcome)!!.lowercase()
        assertTrue("o agente precisa ouvir que está sem rede", fala.contains("sem rede"))
        assertFalse("não pode dizer que foi solicitado", fala.contains("solicitado"))
    }

    @Test
    fun comRede_apoioSai_masSemContagemNaoInventaNumero() = runTest {
        val outcome = executor(online = true).execute(Intent.Emergencia)

        assertTrue(outcome is ActionOutcome.ApoioTransmitido)
        // O transporte atual não devolve contagem: null, não zero.
        assertEquals(null, (outcome as ActionOutcome.ApoioTransmitido).destinatarios)
        assertEquals("Apoio enviado.", textoDe(outcome))
    }

    // ── Cofre de evidência ────────────────────────────────────────────────────

    @Test
    fun cofreIndisponivel_falhaAudivel_naoSilencio() = runTest {
        val outcome = executor(cofre = CofreFake(falharAoAbrir = true))
            .execute(Intent.IniciarGravacao(null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL), outcome)
        val u = utteranceFor(outcome)
        assertTrue("falha tem de ter earcon", u is Utterance.SinalizarEFalar)
    }

    @Test
    fun gravarDuasVezes_naoAbreDuasOcorrencias() = runTest {
        val cofre = CofreFake()
        val exec = executor(cofre = cofre)

        assertTrue(exec.execute(Intent.IniciarGravacao(null)) is ActionOutcome.GravacaoIniciada)
        val segunda = exec.execute(Intent.IniciarGravacao(null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.GRAVACAO_JA_ATIVA), segunda)
    }

    @Test
    fun encerrarSemGravar_diagnosticoCerto() = runTest {
        val outcome = executor().execute(Intent.EncerrarGravacao)
        assertEquals(ActionOutcome.Falhou(FalhaOperacional.SEM_GRAVACAO_ATIVA), outcome)
    }

    @Test
    fun falhaAoFinalizar_preservaOHandle_paraPoderTentarDeNovo() = runTest {
        val cofre = CofreFake(falharAoFinalizar = true)
        val exec = executor(cofre = cofre)
        exec.execute(Intent.IniciarGravacao(null))

        assertEquals(
            ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL),
            exec.execute(Intent.EncerrarGravacao),
        )
        // Zerar o handle aqui deixaria a gravação órfã e impossível de fechar
        // por voz — o agente ficaria gravando sem saber e sem poder parar.
        assertTrue("a gravação continua aberta", exec.gravando())

        cofre.falharAoFinalizar = false
        assertTrue(exec.execute(Intent.EncerrarGravacao) is ActionOutcome.GravacaoEncerrada)
        assertFalse(exec.gravando())
    }

    @Test
    fun gravacaoIniciada_saiComoTom_semFala() = runTest {
        val outcome = executor().execute(Intent.IniciarGravacao(null))
        val u = utteranceFor(outcome)
        assertTrue("gravando é tom de 2 s, sem fala", u is Utterance.Sinalizar)
    }

    // ── Exceção inesperada ────────────────────────────────────────────────────

    @Test
    fun excecaoInesperada_viraFalhaTipada_naoPropaga() = runTest {
        // Se a exceção subisse, o ciclo de voz morreria em silêncio — e num
        // sistema sem display o agente não teria como saber.
        val outcome = executor(cofre = CofreFake(explodir = true))
            .execute(Intent.IniciarGravacao(null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.INTERNA), outcome)
    }

    // ── Capacidade ausente ────────────────────────────────────────────────────

    @Test
    fun consultaDePlaca_semBase_naoInventaResultado() = runTest {
        // Um "sem restrição" fabricado numa abordagem é informação de segurança
        // falsa. Enquanto não há base, a resposta honesta é que não dá.
        val outcome = executor().execute(Intent.ConsultarPlaca("ABC1D23"))
        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL), outcome)
    }

    // ── Repetir ───────────────────────────────────────────────────────────────

    @Test
    fun detalharSemHistorico_avisaEmVezDeInventar() = runTest {
        assertEquals(
            ActionOutcome.Falhou(FalhaOperacional.NADA_A_REPETIR),
            executor().execute(Intent.Detalhar),
        )
    }

    @Test
    fun detalhar_repeteOUltimoResultado_eNaoViraOProprioUltimo() = runTest {
        val exec = executor(online = true)
        val original = exec.execute(Intent.Emergencia)

        assertEquals(original, exec.execute(Intent.Detalhar))
        // Repetir duas vezes seguidas continua devolvendo a ação, não o "repetir".
        assertEquals(original, exec.execute(Intent.Detalhar))
    }

    // ── Concorrência ──────────────────────────────────────────────────────────

    @Test
    fun comandosConcorrentes_naoAbremDuasGravacoes() = runTest {
        // "Gravar" repetido sob estresse é o caso real. Sem serialização, os dois
        // passariam pela checagem de handle nulo e abririam duas ocorrências — a
        // segunda sobregravando a primeira.
        val cofre = CofreFake()
        val exec = executor(cofre = cofre)

        val resultados = (1..8)
            .map { async { exec.execute(Intent.IniciarGravacao(null)) } }
            .awaitAll()

        assertEquals(
            "exatamente uma gravação deve abrir",
            1,
            resultados.count { it is ActionOutcome.GravacaoIniciada },
        )
        assertEquals(
            7,
            resultados.count { it == ActionOutcome.Falhou(FalhaOperacional.GRAVACAO_JA_ATIVA) },
        )
    }

    // ── Modo ──────────────────────────────────────────────────────────────────

    @Test
    fun trocarModo_soConfirmaDepoisDeAplicar() = runTest {
        val aplicados = mutableListOf<ModoOperacao>()
        val outcome = executor(aoTrocarModo = { aplicados.add(it) })
            .execute(Intent.TrocarModo(ModoOperacao.OCORRENCIA))

        assertEquals(listOf(ModoOperacao.OCORRENCIA), aplicados)
        assertEquals(ActionOutcome.ModoTrocado(ModoOperacao.OCORRENCIA), outcome)
    }
}
