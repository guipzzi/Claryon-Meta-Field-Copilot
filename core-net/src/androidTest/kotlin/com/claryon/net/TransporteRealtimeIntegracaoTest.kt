package com.claryon.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Integração real com o Supabase Realtime.**
 *
 * É o teste que fecha a metade "transporte" do portão da Fase 2: dois clientes no
 * mesmo talk group, um transmite quadros e o outro os recebe — que é exatamente a
 * topologia da demonstração (emissor nos óculos, receptores em cliente web).
 *
 * Usa **dois clientes** em vez de eco do próprio emissor de propósito: em
 * produção o emissor **não** deve receber a própria voz de volta, então testar
 * por auto-eco validaria uma configuração que não queremos.
 *
 * Credenciais em `local.properties` (`supabase_url`, `supabase_anon_key`) ou nas
 * envs `SUPABASE_URL`/`SUPABASE_ANON_KEY`. Ausentes, o teste se **declara pulado**
 * — ninguém deve precisar de credencial para rodar a suíte.
 */
@RunWith(AndroidJUnit4::class)
class TransporteRealtimeIntegracaoTest {

    private val config = ConfigRealtime(
        projetoUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val talkGroup = "teste-integracao"

    private var emissor: TransporteRealtime? = null
    private var receptor: TransporteRealtime? = null

    /** Corpo tipado como Unit: `Log`/`runCatching` devolvem valor e o JUnit exige void. */
    @After
    fun encerrar(): Unit = runBlocking {
        runCatching { emissor?.desconectar() }
        runCatching { receptor?.desconectar() }
        escopo.cancel()
    }

    private fun exigeCredenciais() {
        Assume.assumeTrue(
            "sem credenciais do Supabase em local.properties — teste de integração pulado",
            config.projetoUrl.isNotBlank() && config.apiKey.isNotBlank(),
        )
    }

    /** Espera o socket abrir de fato; devolve `false` no estouro. */
    private suspend fun esperarConexao(t: TransporteRealtime, limiteMs: Long = 15_000): Boolean =
        withTimeoutOrNull(limiteMs) {
            while (!t.conectado()) delay(100)
            true
        } == true

    @Test
    fun socketAbreEEntraNoCanal(): Unit = runBlocking {
        exigeCredenciais()
        val t = TransporteRealtime(config, escopo).also { emissor = it }
        t.conectar(talkGroup)

        assertTrue("o canal não abriu — confira URL e anon key", esperarConexao(t))
    }

    @Test
    fun doisClientes_umTransmite_oOutroRecebe(): Unit = runBlocking {
        exigeCredenciais()
        val rx = TransporteRealtime(config, escopo).also { receptor = it }
        val tx = TransporteRealtime(config, escopo).also { emissor = it }

        rx.conectar(talkGroup)
        tx.conectar(talkGroup)
        assertTrue("receptor não conectou", esperarConexao(rx))
        assertTrue("emissor não conectou", esperarConexao(tx))

        // O `join` do canal é assíncrono depois do socket abrir.
        delay(1_500)

        val payload = ByteArray(90) { (it * 3 - 128).toByte() }
        val enviado = QuadroAudio("tx-integracao", sequencia = 7, capturadoEmMs = 1234, payload = payload)

        val aguardando = async {
            withTimeoutOrNull(10_000) {
                rx.eventos().first { it is EventoDeRede.Quadro } as EventoDeRede.Quadro
            }
        }
        delay(300)
        tx.enviar(enviado)

        val recebido = aguardando.await()
        assertNotNull("o quadro não chegou ao outro cliente em 10 s", recebido)

        // O que importa: os bytes do áudio atravessaram intactos. Um byte trocado
        // vira estalo no ouvido de quem está numa ocorrência.
        assertEquals(enviado.sequencia, recebido!!.quadro.sequencia)
        assertEquals(enviado.transmissaoId, recebido.quadro.transmissaoId)
        assertTrue(
            "o payload Opus chegou corrompido",
            payload.contentEquals(recebido.quadro.payload),
        )
        Log.i("ClaryonField", "Integração: quadro atravessou o Realtime intacto (${payload.size} B)")
    }

    @Test
    fun anuncioEFimAtravessam(): Unit = runBlocking {
        exigeCredenciais()
        val rx = TransporteRealtime(config, escopo).also { receptor = it }
        val tx = TransporteRealtime(config, escopo).also { emissor = it }
        rx.conectar(talkGroup); tx.conectar(talkGroup)
        assertTrue(esperarConexao(rx)); assertTrue(esperarConexao(tx))
        delay(1_500)

        val aguardando = async {
            withTimeoutOrNull(10_000) { rx.eventos().first { it is EventoDeRede.Anuncio } }
        }
        delay(300)
        tx.anunciar(AnuncioDeFala("tx-1", "Alfa Dois", PrioridadeTransmissao.P1_EMERGENCIA))

        val evento = aguardando.await() as? EventoDeRede.Anuncio
        assertNotNull("o anúncio de fala não chegou", evento)
        assertEquals(PrioridadeTransmissao.P1_EMERGENCIA, evento!!.anuncio.prioridade)
    }
    // ── Canal privado ────────────────────────────────────────────────────────

    /**
     * Credenciais por argumento de instrumentação, nunca versionadas:
     *
     * ```
     * -e par_email guido@... -e par_senha ... -e par_grupo <uuid>
     * ```
     *
     * Sem elas o teste **pula**. Quem clona o repositório não herda bancada
     * quebrada, e senha de teste não entra em arquivo.
     */
    private fun arg(nome: String): String? =
        InstrumentationRegistry.getArguments().getString(nome)?.takeIf { it.isNotBlank() }

    /** Token do agente, pelo mesmo endpoint que o app usa. */
    private fun autenticar(email: String, senha: String): String? = runCatching {
        val corpo = JSONObject().put("email", email).put("password", senha).toString()
        val req = Request.Builder()
            .url("${config.projetoUrl.trimEnd('/')}/auth/v1/token?grant_type=password")
            .addHeader("apikey", config.apiKey)
            .post(corpo.toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(req).execute().use { r ->
            JSONObject(r.body?.string().orEmpty()).optString("access_token").takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun configPrivada(token: String) = ConfigRealtime(
        projetoUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
        tokenDoAgente = { token },
        privado = true,
    )

    /**
     * **O app entra no canal privado e o áudio atravessa.**
     *
     * A asserção NÃO pode ser `conectado()`: esse campo vira `true` no `onOpen`,
     * **antes** da resposta do `phx_join`, e o join é justamente o que a RLS
     * julga. Um teste sobre `conectado()` passaria com a política recusando tudo
     * — mediria o socket TCP, não a autorização.
     *
     * O que prova é o quadro chegar do outro lado. Se a política da migração
     * `0012` recusar o join, nenhum byte cruza e este teste falha.
     */
    @Test
    fun canalPrivado_oQuadroAtravessaEntreDoisClientes(): Unit = runBlocking {
        exigeCredenciais()
        val email = arg("par_email")
        val senha = arg("par_senha")
        val grupo = arg("par_grupo")
        Assume.assumeTrue(
            "sem -e par_email/-e par_senha/-e par_grupo — teste de canal privado pulado",
            email != null && senha != null && grupo != null,
        )
        val token = autenticar(email!!, senha!!)
        Assume.assumeTrue("login falhou para $email", token != null)

        val cfg = configPrivada(token!!)
        val rx = TransporteRealtime(cfg, escopo).also { receptor = it }
        val tx = TransporteRealtime(cfg, escopo).also { emissor = it }
        rx.conectar(grupo!!)
        tx.conectar(grupo)
        assertTrue("receptor não abriu socket", esperarConexao(rx))
        assertTrue("emissor não abriu socket", esperarConexao(tx))
        delay(2_000) // o join é assíncrono depois do socket

        val payload = ByteArray(90) { (it * 5 - 128).toByte() }
        val enviado = QuadroAudio("tx-privado", sequencia = 11, capturadoEmMs = 4321, payload = payload)
        val aguardando = async {
            withTimeoutOrNull(10_000) {
                rx.eventos().first { it is EventoDeRede.Quadro } as EventoDeRede.Quadro
            }
        }
        delay(300)
        tx.enviar(enviado)

        val recebido = aguardando.await()
        assertNotNull(
            "nada atravessou o canal PRIVADO em 10 s: ou o join foi recusado pela " +
                "política 0012, ou o access_token não chegou ao servidor",
            recebido,
        )
        assertTrue("payload corrompido", payload.contentEquals(recebido!!.quadro.payload))
        Log.i("ClaryonField", "Canal privado: quadro atravessou com JWT e RLS de pé")
    }

    /**
     * **O contra-teste, e é ele que dá sentido ao de cima.**
     *
     * Mesmo token, grupo de que o agente **não** é membro. Se um quadro atravessar
     * aqui, a política não está sendo consultada e o teste anterior estava
     * medindo o nada — foi por isso que o `status=ok` sozinho não bastou quando
     * verifiquei pelo par headless.
     */
    @Test
    fun canalPrivado_grupoAlheio_naoDeixaNadaPassar(): Unit = runBlocking {
        exigeCredenciais()
        val email = arg("par_email")
        val senha = arg("par_senha")
        Assume.assumeTrue("sem credenciais de par", email != null && senha != null)
        val token = autenticar(email!!, senha!!)
        Assume.assumeTrue("login falhou", token != null)

        val alheio = "99999999-0000-0000-0000-000000000009"
        val cfg = configPrivada(token!!)
        val rx = TransporteRealtime(cfg, escopo).also { receptor = it }
        val tx = TransporteRealtime(cfg, escopo).also { emissor = it }
        rx.conectar(alheio)
        tx.conectar(alheio)
        delay(3_000)

        val aguardando = async {
            withTimeoutOrNull(6_000) {
                rx.eventos().first { it is EventoDeRede.Quadro }
            }
        }
        delay(300)
        tx.enviar(QuadroAudio("tx-alheio", 1, 0, ByteArray(40)))

        assertNull(
            "um quadro atravessou um grupo de que o agente NÃO é membro: a política " +
                "0012 não está sendo consultada, e o canal privado é decorativo",
            aguardando.await(),
        )
        Log.i("ClaryonField", "Canal privado: grupo alheio bloqueado, como a política manda")
    }

}
