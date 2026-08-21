package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Restricao
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.claryon.evidence.Anexado
import com.claryon.evidence.CustodyManifest
import com.claryon.evidence.EvidenceVault
import com.claryon.evidence.OccurrenceContext
import com.claryon.evidence.RecordingHandle
import com.claryon.field.agent.ClaryonIntentExecutor.ConsultaDePlaca
import com.claryon.field.agent.ClaryonIntentExecutor.LeituraDePlaca
import com.claryon.sync.FakeSyncGateway
import com.claryon.sync.FileOutbox
import com.claryon.sync.TacticalDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **O portão único da consulta de placa.**
 *
 * O ramo `Intent.ConsultarPlaca` respondia `CONSULTA_INDISPONIVEL` para tudo, e a
 * placa nula — *"Claryon, verifica a placa desse carro"* — não tinha caminho nenhum.
 * O que se prova aqui é o que passou a existir e, mais importante, **o que continua
 * impossível**: consultar uma placa que ninguém leu.
 *
 * O executor sozinho não é o produto: `CapturaDePlacaTest` prova a janela e a
 * câmera, `FramesEfemerosTest` prova o descarte no aparelho, e este prova a decisão.
 */
class ConsultaDePlacaNoExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Andaime ───────────────────────────────────────────────────────────────

    /** Nenhum teste daqui abre o cofre; ele existe só porque o executor o exige. */
    private class CofreDeMentira : EvidenceVault {
        override suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
        override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<Anexado> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
        override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
    }

    private fun executor(
        lerPlaca: suspend () -> LeituraDePlaca = { LeituraDePlaca.SemCamera(FalhaOperacional.CAMERA_INDISPONIVEL) },
        consultar: suspend (String) -> ConsultaDePlaca = { ConsultaDePlaca.NaoRespondeu },
    ) = ClaryonIntentExecutor(
        cofre = CofreDeMentira(),
        despachante = TacticalDispatcher(
            outbox = FileOutbox(tmp.newFolder()),
            gateway = FakeSyncGateway(online = false),
            novoId = { "id" },
            agora = { 0L },
        ),
        identidade = Identidade("007", "GTA-3", "GTA-3", "COPOM"),
        agora = { 0L },
        aoTrocarModo = { _: ModoOperacao -> },
        taxaDeAmostragemHz = { 16_000 },
        lerPlacaPelaCamera = lerPlaca,
        consultarPlaca = consultar,
    )

    // ── A placa nula abre a câmera ────────────────────────────────────────────

    /**
     * **O ramo que não existia.** Placa nula é pedido de captura, não falha.
     */
    @Test
    fun placaNula_abreACamera_eConsultaOQueFoiLido() = runTest {
        var camerasAbertas = 0
        val consultadas = mutableListOf<String>()

        val outcome = executor(
            lerPlaca = { camerasAbertas++; LeituraDePlaca.Lida("ABC1D23") },
            consultar = { p ->
                consultadas += p
                ConsultaDePlaca.Respondeu(p, Restricao.FURTO_ROUBO)
            },
        ).execute(Intent.ConsultarPlaca(placa = null))

        assertEquals(1, camerasAbertas)
        assertEquals(listOf("ABC1D23"), consultadas)
        assertEquals(ActionOutcome.PlacaConsultada("ABC1D23", Restricao.FURTO_ROUBO), outcome)
    }

    /**
     * **Contra-teste do ramo acima: com a placa DITADA, a câmera não abre.**
     *
     * Sem esta asserção, uma implementação que abrisse a câmera sempre passaria no
     * teste anterior — e o agente que já disse a placa ouviria "aponte para a placa"
     * e esperaria 5 s por nada.
     */
    @Test
    fun placaDitada_naoAbreACamera() = runTest {
        var camerasAbertas = 0

        val outcome = executor(
            lerPlaca = { camerasAbertas++; LeituraDePlaca.Lida("ZZZ9Z99") },
            consultar = { p -> ConsultaDePlaca.Respondeu(p, Restricao.SEM_RESTRICAO) },
        ).execute(Intent.ConsultarPlaca("ABC1D23"))

        assertEquals("a câmera abriu com a placa já ditada", 0, camerasAbertas)
        assertEquals(ActionOutcome.PlacaConsultada("ABC1D23", Restricao.SEM_RESTRICAO), outcome)
    }

    // ── O que impede a placa fabricada ────────────────────────────────────────

    /**
     * **Saída que não casa com as duas gramáticas NÃO vira consulta.**
     *
     * É o pior modo de falha do fluxo inteiro: consultar — e possivelmente liberar —
     * um veículo que o OCR fabricou. A asserção que importa é a segunda: a base não
     * pode ter sido tocada.
     */
    @Test
    fun leituraForaDoFormato_viraRecusa_eNaoTocaABase() = runTest {
        val consultadas = mutableListOf<String>()
        val exec = executor(
            lerPlaca = { LeituraDePlaca.Lida("PLACA") },
            consultar = { p -> consultadas += p; ConsultaDePlaca.Respondeu(p, Restricao.SEM_RESTRICAO) },
        )

        val outcome = exec.execute(Intent.ConsultarPlaca(placa = null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.PLACA_NAO_LIDA), outcome)
        assertEquals(
            "o OCR fabricou texto e a base foi consultada mesmo assim",
            emptyList<String>(),
            consultadas,
        )
    }

    /**
     * O portão vale para a placa **ditada** também — inclusive para a que um modelo
     * de linguagem normalizar quando a Etapa B ligar `PlacaDitada`. A regra dura diz
     * que o LLM só preenche campo; o que a torna verificável é o formato ser
     * conferido depois, aqui, num ponto que nenhuma fonte contorna.
     */
    @Test
    fun placaDitadaForaDoFormato_tambemNaoTocaABase() = runTest {
        var consultas = 0
        val outcome = executor(
            consultar = { p -> consultas++; ConsultaDePlaca.Respondeu(p, Restricao.SEM_RESTRICAO) },
        ).execute(Intent.ConsultarPlaca("ABC12345"))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.PLACA_NAO_LIDA), outcome)
        assertEquals(0, consultas)
    }

    /** Placa com hífen e minúscula é a mesma placa. Normaliza, e só então confere. */
    @Test
    fun placaComHifen_eNormalizadaAntesDaConsulta() = runTest {
        val consultadas = mutableListOf<String>()
        executor(consultar = { p -> consultadas += p; ConsultaDePlaca.NaoRespondeu })
            .execute(Intent.ConsultarPlaca("abc-1d23"))

        assertEquals(listOf("ABC1D23"), consultadas)
    }

    // ── As duas falhas, que são diferentes ────────────────────────────────────

    /**
     * **"Placa ilegível" e "consulta indisponível" não podem colapsar.**
     *
     * As recuperações são opostas: aproximar-se do veículo × abrir as hastes. Um
     * `String?` no lugar de [LeituraDePlaca] teria colapsado as duas, e o agente com
     * os óculos dobrados ouviria para chegar mais perto de um veículo que o aparelho
     * nunca viu.
     */
    @Test
    fun ilegivelESemCamera_produzemFalasDiferentes() = runTest {
        val ilegivel = executor(lerPlaca = { LeituraDePlaca.Ilegivel })
            .execute(Intent.ConsultarPlaca(placa = null))
        val semCamera = executor(lerPlaca = { LeituraDePlaca.SemCamera(FalhaOperacional.CAMERA_INDISPONIVEL) })
            .execute(Intent.ConsultarPlaca(placa = null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.PLACA_NAO_LIDA), ilegivel)
        // **A causa injetada tem de ATRAVESSAR.** Antes de 21/08 o executor achatava
        // qualquer falha de câmera em CONSULTA_INDISPONIVEL, e as oito causas de
        // `ErroDeStream` soavam iguais. Esta asserção é o que impede a volta.
        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CAMERA_INDISPONIVEL), semCamera)
        assertTrue("as duas falhas colapsaram numa só", ilegivel != semCamera)
    }

    /**
     * **Base que não responde nunca vira "sem restrição".**
     *
     * A asserção é sobre a FALA, não sobre o tipo: um `PlacaConsultada(_,
     * SEM_RESTRICAO)` devolvido aqui produziria *"ABC1D23, sem restrição."* — a
     * informação de segurança falsa que o §2 do `CLAUDE.md` proíbe.
     */
    @Test
    fun baseQueNaoResponde_naoDizSemRestricao() = runTest {
        val outcome = executor(
            lerPlaca = { LeituraDePlaca.Lida("ABC1D23") },
            consultar = { ConsultaDePlaca.NaoRespondeu },
        ).execute(Intent.ConsultarPlaca(placa = null))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL), outcome)
        assertNull(
            "a resposta não pode ser uma consulta com situação declarada",
            outcome as? ActionOutcome.PlacaConsultada,
        )
    }

    /**
     * **O executor sem as dependências não pode PARECER capaz de ver.**
     *
     * O padrão de `lerPlacaPelaCamera` recusa, como o de `trocarDeGrupo` e o de
     * `abrirTransmissao`. Um padrão que "tentasse" faria uma composição incompleta
     * parecer completa — que é como as três Edge Functions ficaram sem chamador.
     */
    @Test
    fun executorSemCamera_recusaEmVezDeFingir() = runTest {
        val outcome = executor().execute(Intent.ConsultarPlaca(placa = null))
        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CAMERA_INDISPONIVEL), outcome)
    }

    /** `Detalhar` relê o último resultado — inclusive o da placa. */
    @Test
    fun detalharRepeteAConsultaDePlaca() = runTest {
        val exec = executor(
            lerPlaca = { LeituraDePlaca.Lida("ABC1D23") },
            consultar = { p -> ConsultaDePlaca.Respondeu(p, Restricao.ADMINISTRATIVA) },
        )
        val original = exec.execute(Intent.ConsultarPlaca(placa = null))

        assertEquals(original, exec.execute(Intent.Detalhar))
    }
}
