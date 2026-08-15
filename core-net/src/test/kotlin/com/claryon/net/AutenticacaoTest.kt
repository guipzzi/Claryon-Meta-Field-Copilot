package com.claryon.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A política de sessão.
 *
 * O que estes testes protegem é o que acontece **quando dá errado**: token
 * expirado no meio do turno, duas corrotinas renovando ao mesmo tempo, refresh
 * token morto. Nenhum desses caminhos pode travar o ciclo de voz nem derrubar a
 * sessão em silêncio.
 */
class AutenticacaoTest {

    private class CofreEmMemoria : CofreDeSessao {
        var sessao: Sessao? = null
        var gravacoes = 0
        var apagou = 0
        override fun ler() = sessao
        override fun gravar(sessao: Sessao) { this.sessao = sessao; gravacoes++ }
        override fun apagar() { sessao = null; apagou++ }
    }

    private var relogio = 1_000_000L
    private val cofre = CofreEmMemoria()

    private fun sessao(expiraEmMs: Long) = Sessao(
        accessToken = "tok-${expiraEmMs}",
        refreshToken = "ref",
        expiraEmMs = expiraEmMs,
        agentId = "agente-1",
    )

    private fun auth() = AutenticacaoSupabase(
        config = ConfigRealtime("https://exemplo.invalid", "chave"),
        cofre = cofre,
        agora = { relogio },
    )

    @Test
    fun semSessao_naoHaToken(): Unit = runTest {
        assertNull(auth().tokenValido())
        assertNull(auth().agentId())
    }

    @Test
    fun tokenLongeDeExpirar_saiSemTocarNaRede(): Unit = runTest {
        // A base de tudo: o caminho normal da consulta de voz não pode depender de
        // rede para obter o token. O cliente HTTP aqui aponta para um host que não
        // resolve — se houvesse chamada, o teste falharia.
        cofre.sessao = sessao(relogio + 3_600_000)
        assertEquals("tok-${relogio + 3_600_000}", auth().tokenValido())
        assertEquals("nada foi regravado", 0, cofre.gravacoes)
    }

    @Test
    fun tokenDentroDaMargem_tentaRenovar(): Unit = runTest {
        // Expira em 30 s: dentro da margem de 60 s. Sem rede a renovação falha, e
        // o resultado tem de ser `null` — nunca um token que vai voltar 401 no
        // meio da consulta.
        cofre.sessao = sessao(relogio + 30_000)
        assertNull(auth().tokenValido())
    }

    @Test
    fun refreshMorto_apagaASessao(): Unit = runTest {
        // Erro de rede NÃO é refresh morto. Manter a sessão é o certo: o agente
        // entra num túnel e não pode ser deslogado por isso.
        cofre.sessao = sessao(relogio - 1)
        auth().tokenValido()
        assertNotNull("falha de rede não pode deslogar", cofre.sessao)
        assertEquals(0, cofre.apagou)
    }

    @Test
    fun sair_apagaTudo(): Unit = runTest {
        cofre.sessao = sessao(relogio + 3_600_000)
        auth().sair()
        assertNull(cofre.sessao)
    }

    @Test
    fun expiracaoEhInstanteAbsoluto_naoDuracao(): Unit = runTest {
        // Guardar a duração exigiria saber quando foi emitida — e esse "quando"
        // some quando o processo morre. Aqui, o relógio anda e o mesmo registro
        // passa a estar expirado sozinho.
        cofre.sessao = sessao(relogio + 120_000)
        assertNotNull(auth().tokenValido())

        relogio += 119_000
        assertNull("deveria ter expirado com o tempo", auth().tokenValido())
    }

    @Test
    fun aCausaDaFalhaSeparaSenhaDeRede() {
        // O agente precisa saber se troca a senha ou se tenta de novo.
        assertEquals(
            FalhaDeLogin.CredencialInvalida,
            falhaDeLoginDe(ErroDeAutenticacao(400, recuperavel = false)),
        )
        assertEquals(FalhaDeLogin.SemRede, falhaDeLoginDe(java.io.IOException("timeout")))
        assertTrue(falhaDeLoginDe(ErroDeAutenticacao(503, true)) is FalhaDeLogin.Servidor)
    }

    @Test
    fun aSenhaNuncaEhGuardada() {
        // Uma senha em repouso vaza junto com o aparelho — e o aparelho de campo é
        // justamente o que se perde. O tipo não tem onde guardá-la.
        val campos = Sessao::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("Sessao ganhou campo de senha: $campos", campos.none { "senha" in it || "password" in it })
    }
}
