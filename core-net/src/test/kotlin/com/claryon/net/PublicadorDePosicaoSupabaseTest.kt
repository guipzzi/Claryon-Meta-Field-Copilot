package com.claryon.net

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * **O publicador da posição própria — sem um único teste JVM até 21/08.**
 *
 * O que ficava sem cobertura era o caminho de falha, e ele é o assunto inteiro
 * desta classe: `publicar` devolve `Unit` e guarda o resultado num flag interno,
 * então quem chama não tem como saber que fracassou a não ser lendo
 * [PublicadorDePosicao.publicando]. Enquanto ninguém lia esse flag, uma auditoria
 * pôde medir **delta de zero linhas** em `agent_positions` em 20 min de aplicativo
 * aberto sem que nada aparecesse em lugar nenhum.
 *
 * O transporte é falsificado por `Interceptor` em vez de servidor local: o que
 * está sob teste é a decisão (o que vira `true`, o que vira `false`, o que sequer
 * sai do aparelho), não o socket.
 */
class PublicadorDePosicaoSupabaseTest {

    private val config = ConfigRealtime(
        projetoUrl = "https://exemplo.supabase.co",
        apiKey = "chave-anonima",
    )

    /** O que o aparelho tentou mandar. Vazio significa "nem saiu daqui". */
    private val tentativas = mutableListOf<Request>()

    /** Corpo da última requisição, já lido do buffer. */
    private val corpos = mutableListOf<String>()

    private fun cliente(resposta: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val req = chain.request()
                tentativas += req
                corpos += Buffer().also { req.body?.writeTo(it) }.readUtf8()
                resposta(req)
            },
        ).build()

    private fun respondendo(codigo: Int) = cliente { req ->
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(codigo)
            .message(if (codigo in 200..299) "OK" else "erro")
            .body("[]".toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun explodindo() = cliente { throw IOException("rede caiu no meio do turno") }

    private fun publicador(
        client: OkHttpClient,
        token: String? = "jwt-do-agente",
        agoraNanos: Long = 5_000_000_000L,
    ) = PublicadorDePosicaoSupabase(
        config = config,
        tokenDeSessao = { token },
        client = client,
        agoraNanos = { agoraNanos },
    )

    // ── O flag que ninguém lia, agora medido nos três desfechos ───────────────

    /**
     * **Contra-teste dos três desfechos, e eles têm de DIFERIR.**
     *
     * `publicando()` nasce `false`, e um teste que olhasse só o caminho de erro
     * passaria com a implementação que nunca escreve nada no flag. Exigir que o
     * sucesso vire `true` e que os dois fracassos voltem para `false` é o que fecha
     * essa porta.
     */
    @Test
    fun sucessoRecusaEQuedaDeRede_saemDiferentes() = runBlocking {
        val ok = publicador(respondendo(200))
        ok.publicar(-16.67, -49.25, 8f, 1.4f, 1_000_000_000L)
        val depoisDoSucesso = ok.publicando()

        val recusado = publicador(respondendo(403))
        recusado.publicar(-16.67, -49.25, 8f, 1.4f, 1_000_000_000L)
        val depoisDaRecusa = recusado.publicando()

        val semRede = publicador(explodindo())
        semRede.publicar(-16.67, -49.25, 8f, 1.4f, 1_000_000_000L)
        val depoisDaQueda = semRede.publicando()

        assertTrue("HTTP 200 é a posição no servidor", depoisDoSucesso)
        assertFalse("403 é turno fechado (`0019`) — não subiu", depoisDaRecusa)
        assertFalse("exceção de rede não pode virar sucesso silencioso", depoisDaQueda)
        assertNotEquals(depoisDoSucesso, depoisDaRecusa)
    }

    /**
     * **Um sucesso não fica valendo para sempre.**
     *
     * O flag é o resultado do ÚLTIMO POST. Se ele só subisse, a interface diria
     * "sua posição está no mapa" para sempre depois da primeira publicação de um
     * turno — que é exatamente a mentira que a auditoria encontrou na tela.
     */
    @Test
    fun oFlagAcompanhaOUltimoPost_naoOMelhorDeles() = runBlocking {
        var codigo = 200
        val p = PublicadorDePosicaoSupabase(
            config = config,
            tokenDeSessao = { "jwt" },
            client = cliente { req ->
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(codigo).message("x")
                    .body("[]".toResponseBody("application/json".toMediaType())).build()
            },
            agoraNanos = { 5_000_000_000L },
        )

        p.publicar(-16.67, -49.25, 8f, null, null)
        assertTrue(p.publicando())

        codigo = 500
        p.publicar(-16.67, -49.25, 8f, null, null)
        assertFalse("o servidor caiu depois — o flag tem de cair junto", p.publicando())
    }

    // ── O retorno silencioso do token ─────────────────────────────────────────

    /**
     * **O primeiro dos três defeitos, fixado.** `tokenDeSessao() ?: run {
     * ultimaPublicacaoOk = false; return }` estava correto na decisão e mudo na
     * comunicação: nenhuma requisição sai, o flag cai, e o `logcat` não registrava
     * nada. Isto aqui prova a metade verificável em JVM — que **nada sai do
     * aparelho** — e o log entrou no mesmo commit.
     */
    @Test
    fun semToken_nadaSaiDoAparelho_eOFlagCai() = runBlocking {
        val p = publicador(respondendo(200), token = null)
        p.publicar(-16.67, -49.25, 8f, null, 1_000_000_000L)

        assertEquals("sem sessão não se chama o servidor", 0, tentativas.size)
        assertFalse("e o estado precisa dizer que não subiu", p.publicando())
    }

    @Test
    fun semToken_oTurnoNaoAbre_eNaoChamaOServidor() = runBlocking {
        val p = publicador(respondendo(200), token = null)
        assertFalse(p.iniciarTurno())
        assertEquals(0, tentativas.size)
    }

    // ── O turno ───────────────────────────────────────────────────────────────

    @Test
    fun iniciarTurno_acompanhaOCodigoHttp() = runBlocking {
        assertTrue(publicador(respondendo(200)).iniciarTurno())
        tentativas.clear()
        assertFalse(publicador(respondendo(500)).iniciarTurno())
        assertFalse(publicador(explodindo()).iniciarTurno())
    }

    @Test
    fun iniciarTurno_chamaARpcCerta_comOTokenNoCabecalho() = runBlocking {
        publicador(respondendo(200)).iniciarTurno()
        val req = tentativas.single()
        assertTrue(req.url.toString().endsWith("/rest/v1/rpc/iniciar_turno"))
        assertEquals("Bearer jwt-do-agente", req.header("Authorization"))
        assertEquals("chave-anonima", req.header("apikey"))
    }

    // ── O corpo: o que este publicador se recusa a afirmar ────────────────────

    /**
     * A idade sai **daqui**, e não de quem chamou. Entre a correção do GPS e este
     * ponto existe a fila do coletor, o `withContext` e a espera pelo token;
     * calcular lá em cima congelaria um número que envelhece sozinho no caminho.
     */
    @Test
    fun aIdadeEhMedidaNoEnvio_naoNaCorrecao() = runBlocking {
        publicador(respondendo(200), agoraNanos = 5_000_000_000L)
            .publicar(-16.67, -49.25, 8f, null, 1_000_000_000L)

        val corpo = JSONObject(corpos.single())
        assertEquals("4 s entre a correção e o envio", 4_000L, corpo.getLong("idade_ms"))
    }

    /**
     * Sem `nanosDaCorrecao`, `idade_ms` vai **nulo** — não zero. Zero é a afirmação
     * "medido exatamente agora", e a origem não disse isso.
     */
    @Test
    fun idadeDesconhecida_viaNula_naoZero() = runBlocking {
        publicador(respondendo(200)).publicar(-16.67, -49.25, 8f, null, null)
        val corpo = JSONObject(corpos.single())
        assertTrue("veio: ${corpos.single()}", corpo.isNull("idade_ms"))
        assertNotEquals(0L, corpo.opt("idade_ms"))
    }

    /**
     * `Float.MAX_VALUE` é o sentinela de "o aparelho não soube dizer". Mandá-lo
     * faria o servidor guardar uma precisão de 3×10³⁸ metros, que num relatório
     * pareceria dado real.
     */
    @Test
    fun precisaoSentinela_viraNula_ePrecisaoRealPassa() = runBlocking {
        publicador(respondendo(200)).publicar(-16.67, -49.25, Float.MAX_VALUE, null, null)
        assertTrue(JSONObject(corpos.last()).isNull("precisao_m"))

        publicador(respondendo(200)).publicar(-16.67, -49.25, 12.5f, null, null)
        assertEquals(12.5, JSONObject(corpos.last()).getDouble("precisao_m"), 1e-6)

        // O contra-teste da fronteira: 10 km é o corte, e os dois lados diferem.
        publicador(respondendo(200)).publicar(-16.67, -49.25, 20_000f, null, null)
        assertTrue("20 km de erro não é posição", JSONObject(corpos.last()).isNull("precisao_m"))
    }

    @Test
    fun velocidadeAusente_viaNula() = runBlocking {
        publicador(respondendo(200)).publicar(-16.67, -49.25, 8f, null, null)
        assertTrue(JSONObject(corpos.last()).isNull("velocidade_ms"))

        publicador(respondendo(200)).publicar(-16.67, -49.25, 8f, 3.2f, null)
        assertEquals(3.2, JSONObject(corpos.last()).getDouble("velocidade_ms"), 1e-5)
    }

    /**
     * **Nenhuma identidade no corpo.** O `agent_id` vem do JWT, e é isso que impede
     * a existência de uma porta que escreva a posição de outro agente. Se alguém
     * acrescentar um campo de identidade aqui, este teste cai.
     */
    @Test
    fun oCorpoNaoCarregaIdentidadeDeNinguem() = runBlocking {
        publicador(respondendo(200)).publicar(-16.67, -49.25, 8f, null, null)
        val chaves = JSONObject(corpos.single()).keys().asSequence().toSet()
        assertEquals(
            setOf("lat", "lon", "idade_ms", "precisao_m", "velocidade_ms"),
            chaves,
        )
    }

    /**
     * `assinarPares` devolve `false` de propósito: este publicador **sozinho** não
     * habilita o mapa, e um `true` otimista abriria o espelho sem que nada fosse
     * chegar nele.
     */
    @Test
    fun assinarPares_naoMenteQueOEspelhoAbriu() = runBlocking {
        assertFalse(publicador(respondendo(200)).assinarPares("grupo-qualquer"))
        assertNull("e não chama o servidor para isso", tentativas.firstOrNull())
    }
}
