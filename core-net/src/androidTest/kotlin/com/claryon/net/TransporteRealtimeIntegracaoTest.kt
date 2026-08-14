package com.claryon.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
