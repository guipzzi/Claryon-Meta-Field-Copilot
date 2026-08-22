package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.BuscaDeLugar
import com.claryon.agent.CategoriaDeLugar
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.FonteDaResposta
import com.claryon.agent.Intent
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.claryon.evidence.Anexado
import com.claryon.evidence.CustodyManifest
import com.claryon.evidence.EvidenceVault
import com.claryon.evidence.OccurrenceContext
import com.claryon.evidence.RecordingHandle
import com.claryon.net.FonteGeoespacial
import com.claryon.net.LugarProcurado
import com.claryon.net.ProcedenciaExterna
import com.claryon.net.RespostaGeoespacial
import com.claryon.sync.FakeSyncGateway
import com.claryon.sync.FileOutbox
import com.claryon.sync.TacticalDispatcher
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **A cascata externa LIGADA — do que o agente falou até o que atravessa a rede.**
 *
 * Este arquivo existe porque a capacidade estava *escrita e não construída*, que é
 * o defeito que o `CLAUDE.md` §6 conta seis vezes. `ConsultaGeoespacial`,
 * `HigieneDaConsulta`, `ConsultaHigienizada` e os dois registros existiam, e o
 * `procurarLugar` do executor tinha padrão que **recusava**: em produção o agente
 * ouvia "Sem rede para consultar." com quatro barras de sinal.
 *
 * O que se mede aqui é a costura inteira, com o roteador de verdade e o executor de
 * verdade — só a fonte externa é de mentira, e ela é de mentira **para capturar o
 * que atravessou a fronteira**, que é a asserção que a spec pede.
 *
 * A divisão de trabalho com `ConsultaGeoespacialTest` é deliberada: lá se prova o
 * que os argumentos viram **no fio**, num socket de verdade; aqui se prova que só
 * esses argumentos chegam lá. Um teste sozinho deixaria metade do caminho sem
 * medida.
 */
class ConsultaExternaNoExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Andaime ───────────────────────────────────────────────────────────────

    private class CofreDeMentira : EvidenceVault {
        override suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
        override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<Anexado> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
        override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> =
            Result.failure(ClaryonError.Evidence("EVID_X", "não usado neste teste"))
    }

    /** O que atravessou a fronteira `app` → `core-net`, guardado literalmente. */
    private data class Travessia(val lugar: LugarProcurado, val latitude: Double, val longitude: Double)

    /**
     * Fonte de mentira que **registra o que recebeu**.
     *
     * Não é só para evitar rede no teste: é o instrumento da asserção. O aceite do
     * §6 diz *"verificável por teste que inspecione a consulta emitida"*, e a
     * inspeção do lado de cá é exatamente esta lista.
     */
    private class FonteQueAnota(
        private val resposta: (LugarProcurado) -> RespostaGeoespacial,
    ) : FonteGeoespacial {
        val travessias = mutableListOf<Travessia>()
        override suspend fun maisProximo(
            lugar: LugarProcurado,
            latitude: Double,
            longitude: Double,
        ): RespostaGeoespacial {
            travessias += Travessia(lugar, latitude, longitude)
            return resposta(lugar)
        }
    }

    private fun procedencia() = ProcedenciaExterna(
        servico = "https://overpass-api.de/api/interpreter",
        consultaEmitida = "[out:json][timeout:2];node(around:3000,$LATITUDE,$LONGITUDE)[\"amenity\"=\"hospital\"];out center;",
        trecho = "{\"tags\":{\"name\":\"Hospital Municipal\"}}",
        carimboMillis = RELOGIO,
        duracaoMs = 812,
    )

    private fun montar(
        resposta: (LugarProcurado) -> RespostaGeoespacial = { RespostaGeoespacial.SemRede },
        posicao: Coordenada? = Coordenada(LATITUDE, LONGITUDE, 8f),
        permissao: Boolean = true,
    ): Triple<ClaryonIntentExecutor, FonteQueAnota, DiarioDaConsultaExterna> {
        val fonte = FonteQueAnota(resposta)
        val diario = DiarioDaConsultaExterna()
        val rede = LugarPelaRede(
            fonte = fonte,
            minhaPosicao = { posicao },
            diario = diario,
            agora = { RELOGIO },
            zona = { ZoneId.of("America/Sao_Paulo") },
        )
        return Triple(executor(permissao) { rede.procurar(it) }, fonte, diario)
    }

    /**
     * O executor **com** a dependência ligada — ou sem ela, quando [procurar] é
     * nulo, que é como se mede o comportamento anterior a esta capacidade.
     */
    private fun executor(
        permissao: Boolean = true,
        procurar: (suspend (CategoriaDeLugar) -> BuscaDeLugar)? = null,
    ): ClaryonIntentExecutor {
        val comuns = ClaryonIntentExecutor(
            cofre = CofreDeMentira(),
            despachante = TacticalDispatcher(
                outbox = FileOutbox(tmp.newFolder()),
                gateway = FakeSyncGateway(online = false),
                novoId = { "id" },
                agora = { 0L },
            ),
            identidade = Identidade("007", "GTA-3", "GTA-3", "COPOM"),
            agora = { RELOGIO },
            aoTrocarModo = { _: ModoOperacao -> },
            taxaDeAmostragemHz = { 16_000 },
            permissaoDeLocal = { permissao },
        )
        if (procurar == null) return comuns
        return ClaryonIntentExecutor(
            cofre = CofreDeMentira(),
            despachante = TacticalDispatcher(
                outbox = FileOutbox(tmp.newFolder()),
                gateway = FakeSyncGateway(online = false),
                novoId = { "id" },
                agora = { 0L },
            ),
            identidade = Identidade("007", "GTA-3", "GTA-3", "COPOM"),
            agora = { RELOGIO },
            aoTrocarModo = { _: ModoOperacao -> },
            taxaDeAmostragemHz = { 16_000 },
            procurarLugar = procurar,
            permissaoDeLocal = { permissao },
        )
    }

    // ── A promessa do §5, inspecionada na fronteira ───────────────────────────

    /**
     * **A transcrição não atravessa — e o teste começa na fala, não na intenção.**
     *
     * As frases abaixo carregam os quatro vazamentos que a spec §5 enumera: placa,
     * matrícula, nome próprio e indicativo de guarnição, mais o número de endereço
     * do exemplo literal do documento (*"estou na Rui Barbosa"*). Todas passam pelo
     * roteador de verdade.
     *
     * Um teste que partisse de `Intent.ConsultarLugar(HOSPITAL)` provaria menos:
     * ele já teria jogado fora a transcrição antes de começar a medir.
     */
    @Test
    fun transcricaoEnvenenada_naoAtravessaAFronteiraDeRede() = runTest {
        val router = DeterministicIntentRouter()
        val (exec, fonte, diario) = montar(
            resposta = { RespostaGeoespacial.NadaPorPerto(procedencia()) },
        )

        var roteadas = 0
        for (fala in FALAS_ENVENENADAS) {
            val intent = router.route(fala)
            if (intent !is Intent.ConsultarLugar) continue
            roteadas++
            exec.execute(intent)
        }

        assertTrue(
            "nenhuma das frases virou consulta de lugar — o teste não exercitou " +
                "nada e passaria com qualquer implementação",
            roteadas >= 3,
        )
        assertEquals("houve travessia sem consulta, ou consulta sem travessia", roteadas, fonte.travessias.size)

        // O que atravessou, escrito como texto: um enum e dois números.
        val atravessou = fonte.travessias.joinToString(" ") { t ->
            "${t.lugar.name} ${t.lugar.filtroOsm} ${t.latitude} ${t.longitude}"
        }
        val vazados = VENENOS.filter { it.lowercase() in atravessou.lowercase() }
        assertEquals(
            "estes pedaços da fala do agente atravessaram para a camada de rede: " +
                "travessias=$atravessou",
            emptyList<String>(),
            vazados,
        )

        // E a coordenada é a MINHA, não um número que veio da frase.
        assertTrue(
            "a coordenada que atravessou não é a posição própria",
            fonte.travessias.all { it.latitude == LATITUDE && it.longitude == LONGITUDE },
        )

        // O registro recebe a MESMA string que saiu (spec §5, "um filtro, dois
        // consumidores"): o termo da categoria, e nada da fala.
        val registradas = diario.uso.value.map { it.consulta }
        assertTrue("nenhum uso registrado", registradas.isNotEmpty())
        val sujas = registradas.filter { c -> VENENOS.any { it.lowercase() in c.lowercase() } }
        assertEquals("o registro guardou pedaço da transcrição", emptyList<String>(), sujas)
        assertTrue(
            "o registro não guardou o termo do vocabulário fechado: $registradas",
            registradas.all { c -> CategoriaDeLugar.entries.any { it.termo == c } },
        )
    }

    /**
     * **Contra-teste da régua acima.** Se o detector de veneno não vê veneno, o
     * teste anterior é decorativo — é a pergunta 3 do §6 aplicada a este arquivo.
     */
    @Test
    fun aReguaDeVeneno_reprovaUmaTravessiaEnvenenada() {
        val fingida = "HOSPITAL [\"name\"=\"Sgt Paiva\"] ABC1D23 998877 alfa dois"
        val achados = VENENOS.filter { it.lowercase() in fingida.lowercase() }
        assertTrue(
            "a régua não viu placa, nome, matrícula nem indicativo numa travessia " +
                "que tem os quatro — ela está quebrada",
            achados.size >= 4,
        )
    }

    // ── Sem rede: o comportamento de HOJE, preservado inteiro ─────────────────

    /**
     * **Aceite do §6: sem rede, recusa falada com motivo — sem degradação.**
     *
     * A asserção é sobre a fala, e não sobre o `ActionOutcome`, porque é a fala que
     * o agente recebe: num produto sem display, um resultado tipado que não vira som
     * é indistinguível de aplicativo morto.
     */
    @Test
    fun semRede_recusaFaladaComMotivo() = runTest {
        val (exec, _, diario) = montar(resposta = { RespostaGeoespacial.SemRede })

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.HOSPITAL))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CONSULTA_SEM_REDE), outcome)
        assertEquals(
            Utterance.SinalizarEFalar(Earcon.FALHA, "Sem rede para consultar.", Priority.RESPOSTA),
            utteranceFor(outcome),
        )
        // A pergunta que a rede não respondeu É o produto da decisão 5: é ela que
        // diz para onde o corpus precisa crescer.
        assertEquals(1, diario.uso.value.size)
        assertFalse(diario.uso.value.first().respondida)
        assertEquals(FonteDaResposta.NENHUMA, diario.uso.value.first().fonte)
        assertEquals(
            "houve auditoria sem resposta de fonte nenhuma — isso registra uma " +
                "consulta que não aconteceu",
            0,
            diario.auditoria.value.size,
        )
    }

    /**
     * **Sem NINGUÉM ligado, o comportamento é bit a bit o de antes desta capacidade.**
     *
     * É o que o `CLAUDE.md` chama de "nenhuma regressão por omissão": o padrão de
     * `procurarLugar` recusa, e a recusa é a mesma frase. Sem esta asserção, a
     * garantia de que ligar a cascata não estragou o caminho antigo seria só a
     * leitura do código.
     */
    @Test
    fun semDependenciaInjetada_aRecusaEIdenticaADeAntes() = runTest {
        val comCascata = montar(resposta = { RespostaGeoespacial.SemRede }).first
            .execute(Intent.ConsultarLugar(CategoriaDeLugar.DELEGACIA))
        val semCascata = executor()
            .execute(Intent.ConsultarLugar(CategoriaDeLugar.DELEGACIA))

        assertEquals(semCascata, comCascata)
        assertEquals(utteranceFor(semCascata), utteranceFor(comCascata))
    }

    // ── O prazo é recusa, não espera ──────────────────────────────────────────

    /**
     * **Prazo estourado vira `CONSULTA_DEMOROU` — e não tenta de novo.**
     *
     * Duas asserções, e as duas importam. A fala tem de ser diferente da de falta de
     * rede porque as ações são opostas: aqui o agente repete no mesmo lugar, lá ele
     * anda até pegar sinal. E a fonte tem de ter sido chamada **uma** vez: uma nova
     * tentativa dentro do mesmo comando é espera disfarçada, e a decisão 1 diz que
     * estourar o prazo é recusa.
     */
    @Test
    fun prazoEstourado_viraRecusaPropria_eNaoTentaDeNovo() = runTest {
        val (exec, fonte, diario) = montar(resposta = { RespostaGeoespacial.PrazoEstourado })

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.HOSPITAL))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.CONSULTA_DEMOROU), outcome)
        assertEquals("Consulta demorou. Repita.", FalhaOperacional.CONSULTA_DEMOROU.causaCurta)
        assertTrue(
            "prazo estourado e falta de rede dizem a mesma coisa ao agente, e as " +
                "recuperações são opostas",
            FalhaOperacional.CONSULTA_DEMOROU.causaCurta != FalhaOperacional.CONSULTA_SEM_REDE.causaCurta,
        )
        assertEquals("a consulta foi repetida — isso é espera, não recusa", 1, fonte.travessias.size)
        assertEquals(0, diario.auditoria.value.size)
        assertEquals(1, diario.uso.value.size)
    }

    // ── Sem posição própria: nada sai pela rede ───────────────────────────────

    /**
     * **Sem correção de GPS, a consulta nem sai — e a fala não mente sobre a causa.**
     *
     * *"O hospital mais próximo"* é pergunta relativa. Sem centro não há busca, e
     * dizer "sem rede" mandaria o agente andar atrás de sinal que ele já tem. Este
     * é o desfecho que só apareceu ao LIGAR a capacidade, e por isso `BuscaDeLugar`
     * não o tinha.
     */
    @Test
    fun semPosicaoPropria_naoSaiConsulta_eNaoSoaComoFaltaDeRede() = runTest {
        val (exec, fonte, diario) = montar(posicao = null)

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.HOSPITAL))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.SEM_POSICAO_PROPRIA), outcome)
        assertEquals("saiu consulta pela rede sem eu saber onde estou", 0, fonte.travessias.size)
        assertEquals(1, diario.uso.value.size)
    }

    /** Sem permissão de local, idem — e a causa é outra, porque o gesto é outro. */
    @Test
    fun semPermissaoDeLocal_naoSaiConsulta_eACausaEPropria() = runTest {
        val (exec, fonte, _) = montar(permissao = false)

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.HOSPITAL))

        assertEquals(ActionOutcome.Falhou(FalhaOperacional.SEM_PERMISSAO_DE_LOCAL), outcome)
        assertEquals("saiu consulta pela rede sem permissão de local", 0, fonte.travessias.size)
    }

    // ── Procedência em toda resposta ──────────────────────────────────────────

    @Test
    fun oEncontrado_deixaAuditoriaComServicoTrechoECarimbo() = runTest {
        val (exec, _, diario) = montar(
            resposta = {
                RespostaGeoespacial.Encontrado("Hospital Getúlio Vargas", 800, procedencia())
            },
        )

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.HOSPITAL))

        assertTrue(outcome is ActionOutcome.LugarEncontrado)
        val a = diario.auditoria.value.single()
        assertEquals("https://overpass-api.de/api/interpreter", a.servico)
        assertEquals(RELOGIO, a.carimboMillis)
        assertTrue("o trecho que fundamentou não foi registrado", a.trecho.isNotBlank())
        assertTrue("a consulta emitida não foi registrada", a.consultaEmitida.isNotBlank())
        assertEquals(1, diario.uso.value.size)
        assertTrue(diario.uso.value.single().respondida)
    }

    /**
     * **A resposta que não achou nada TAMBÉM deixa auditoria.**
     *
     * É onde não há linha de resultado para pendurar o flag — e foi exatamente aí
     * que a base veicular quase deixou a `Procedencia` de fora. O aceite do §6 diz
     * **toda** resposta de fonte externa.
     */
    @Test
    fun oNadaPorPerto_tambemDeixaAuditoria() = runTest {
        val (exec, _, diario) = montar(
            resposta = { RespostaGeoespacial.NadaPorPerto(procedencia()) },
        )

        val outcome = exec.execute(Intent.ConsultarLugar(CategoriaDeLugar.DELEGACIA))

        assertEquals(ActionOutcome.LugarNaoEncontrado(CategoriaDeLugar.DELEGACIA), outcome)
        assertEquals(
            "a resposta 'não há nada' não deixou procedência — é a que mais some, " +
                "porque não tem resultado onde pendurá-la",
            1,
            diario.auditoria.value.size,
        )
        assertFalse(diario.uso.value.single().respondida)
        assertEquals(
            "a fonte respondeu, e o registro diz que ninguém respondeu",
            FonteDaResposta.EXTERNA_ESTRUTURADA,
            diario.uso.value.single().fonte,
        )
    }

    // ── A costura dos dois enums espelhados ───────────────────────────────────

    /**
     * **Cada categoria vira a sua, e o teste é por nome, não por posição.**
     *
     * O contra-teste está embutido: uma tradução constante (tudo vira HOSPITAL)
     * passaria numa asserção que só olhasse uma categoria, e reprova aqui na
     * segunda.
     */
    @Test
    fun cadaCategoriaViraOSeuLugarProcurado() = runTest {
        for (c in CategoriaDeLugar.entries) {
            val (exec, fonte, _) = montar(resposta = { RespostaGeoespacial.SemRede })
            exec.execute(Intent.ConsultarLugar(c))
            assertEquals(
                "a categoria $c atravessou como outra coisa",
                c.name,
                fonte.travessias.single().lugar.name,
            )
        }
        assertEquals(
            "os dois enums espelhados divergiram em tamanho — a costura não cobre tudo",
            CategoriaDeLugar.entries.size,
            LugarProcurado.entries.size,
        )
    }

    private companion object {
        const val LATITUDE = -22.9068
        const val LONGITUDE = -43.1729
        const val RELOGIO = 1_787_453_252_000L

        /**
         * Falas reais com os quatro vazamentos do §5 dentro. A terceira é o exemplo
         * literal da spec.
         */
        val FALAS_ENVENENADAS = listOf(
            "claryon qual o hospital mais proximo pra levar o sgt paiva",
            "aqui e a guarnicao alfa dois, delegacia mais proxima",
            "estou na Rui Barbosa 250 em Niteroi, qual o hospital mais proximo",
            "placa ABC1D23 abandonada, cade o pronto socorro mais proximo",
            "matricula 998877, posto de saude mais perto",
        )

        /** O que não pode aparecer do outro lado da fronteira. */
        val VENENOS = listOf(
            "paiva", "sgt", "alfa", "dois", "guarnicao", "Rui", "Barbosa", "Niteroi",
            "250", "ABC1D23", "998877", "matricula", "claryon", "abandonada",
        )
    }
}
