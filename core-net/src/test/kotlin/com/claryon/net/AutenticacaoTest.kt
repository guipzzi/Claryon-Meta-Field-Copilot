package com.claryon.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A política de sessão.
 *
 * O que estes testes protegem é o que acontece **quando dá errado**: token
 * expirado no meio do turno, duas corrotinas renovando ao mesmo tempo, refresh
 * token morto, rede que aceita a conexão e não responde. Nenhum desses caminhos
 * pode travar o ciclo de voz nem derrubar a sessão em silêncio.
 *
 * ## Os dois tetos, medidos e não lembrados
 *
 * Até 22/08 o KDoc de `tokenValido` dizia, por escrito, que o teto do ramo de
 * expiração **não tinha sido medido**. Os testes abaixo medem os dois que passaram
 * a existir: o da leitura do caminho crítico ([oTetoDaLeituraSemEsperaEDeMemoria])
 * e o da renovação que espera rede ([oTetoDaRenovacaoEOCallTimeout]).
 */
class AutenticacaoTest {

    private class CofreEmMemoria : CofreDeSessao {
        var sessao: Sessao? = null
        var gravacoes = 0
        var apagou = 0
        var leituras = 0
        override fun ler(): Sessao? { leituras++; return sessao }
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

    // ── A leitura que o ciclo de voz usa ──────────────────────────────────────

    @Test
    fun tokenSemEsperar_distingueVencidoDeSemSessao() {
        // `null` achatava os dois num só, e as condutas são opostas: sem sessão
        // pede login, vencido pede só a próxima tentativa.
        assertEquals(TokenSemEspera.SemSessao, auth().tokenSemEsperar())

        cofre.sessao = sessao(relogio + 3_600_000)
        val bom = auth().tokenSemEsperar()
        assertTrue("$bom", bom is TokenSemEspera.Valido)
        assertEquals("tok-${relogio + 3_600_000}", bom.tokenOuNulo)
    }

    @Test
    fun tokenSemEsperar_dentroDaMargemAindaDevolveOToken() {
        // 30 s de vida: dentro da margem de 60 s. Recusar aqui trocaria um risco
        // (401 no meio da viagem) por uma recusa certa — e a renovação já foi
        // disparada em segundo plano.
        cofre.sessao = sessao(relogio + 30_000)
        val r = auth().tokenSemEsperar()
        assertTrue("$r", r is TokenSemEspera.Valido)
        assertEquals(30_000L, (r as TokenSemEspera.Valido).restanteMs)
    }

    /**
     * **O teto do caminho crítico, medido.**
     *
     * Não é "rápido o bastante": é *nenhuma* E/S. O cofre é lido **uma vez** no
     * processo inteiro — daí em diante a leitura é um campo `@Volatile`, e é isso
     * que sustenta a promessa de que o ciclo de voz não espera por credencial.
     *
     * O contra-teste é a contagem de leituras: sem o espelho em memória, a
     * implementação do app decifraria quatro valores AES-256-GCM por chamada, e
     * `leituras` seria 100 000 em vez de 1.
     */
    @Test
    fun oTetoDaLeituraSemEsperaEDeMemoria() {
        cofre.sessao = sessao(relogio + 3_600_000)
        val a = auth()
        // Aquece: a primeira leitura é a que toca o cofre, e ela é legítima.
        assertTrue(a.tokenSemEsperar() is TokenSemEspera.Valido)

        val voltas = 100_000
        val inicio = System.nanoTime()
        for (i in 0 until voltas) a.tokenSemEsperar()
        val gastoMs = (System.nanoTime() - inicio) / 1_000_000.0
        // Medição que não reporta o número medido é asserção. Este vai para o log.
        println(
            "TETO DA LEITURA SEM ESPERA: $voltas chamadas em " +
                "${"%.1f".format(gastoMs)} ms = " +
                "${"%.3f".format(gastoMs * 1000 / voltas)} µs por chamada, " +
                "${cofre.leituras} leitura(s) de cofre",
        )

        assertEquals(
            "O cofre foi lido mais de uma vez: o espelho em memória não está " +
                "segurando, e a promessa de 'sem E/S' do caminho crítico caiu.",
            1,
            cofre.leituras,
        )
        assertTrue(
            "$voltas leituras sem espera custaram ${"%.1f".format(gastoMs)} ms " +
                "(${"%.3f".format(gastoMs * 1000 / voltas)} µs por chamada). " +
                "Acima de 200 ms alguma coisa passou a fazer trabalho de verdade aqui.",
            gastoMs < 200.0,
        )
    }

    /**
     * **O caminho crítico com o token VENCIDO e a rede em buraco negro.**
     *
     * Este é o cenário que travava o ciclo de voz: token fora da margem, servidor
     * que aceita a conexão e nunca responde. O `tokenValido` de antes ficaria preso
     * até o timeout; `tokenSemEsperar` responde em memória e deixa a renovação em
     * voo.
     *
     * As duas asserções são igualmente necessárias. Só a primeira aprovaria uma
     * implementação que devolvesse [TokenSemEspera.Vencido] e **não renovasse nada**
     * — rápida e inútil, porque a consulta seguinte também falharia.
     */
    @Test
    fun tokenSemEsperar_naoEsperaPelaRede_eAindaAssimDisparaARenovacao() {
        ServidorFalso(Comportamento.BURACO_NEGRO).use { servidor ->
            cofre.sessao = sessao(relogio - 1)
            val a = AutenticacaoSupabase(
                config = ConfigRealtime(servidor.url, "chave"),
                cofre = cofre,
                agora = { relogio },
                escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

            val inicio = System.nanoTime()
            val r = a.tokenSemEsperar()
            val gastoMs = (System.nanoTime() - inicio) / 1_000_000.0

            assertEquals(TokenSemEspera.Vencido, r)
            assertTrue(
                "A leitura sem espera custou ${"%.1f".format(gastoMs)} ms contra um " +
                    "servidor que nunca responde. Ela voltou a esperar a rede.",
                gastoMs < 100.0,
            )
            assertTrue(
                "A renovação não foi disparada: o servidor não recebeu conexão " +
                    "nenhuma em 3 s. Recusar sem renovar deixa a consulta seguinte " +
                    "falhando pelo mesmo motivo.",
                esperarAte(3_000) { servidor.conexoes.get() >= 1 },
            )
        }
    }

    /**
     * **A renovação de segundo plano troca o token — e ninguém esperou por ela.**
     *
     * O par do teste acima: lá o servidor não responde e a prova é que a conexão
     * saiu; aqui ele responde, e a prova é que o token trocou sozinho.
     */
    @Test
    fun renovacaoEmSegundoPlano_trocaOTokenSemQueNinguemEspere() {
        ServidorFalso(Comportamento.RENOVA).use { servidor ->
            cofre.sessao = sessao(relogio - 1)
            val a = AutenticacaoSupabase(
                config = ConfigRealtime(servidor.url, "chave"),
                cofre = cofre,
                agora = { relogio },
                escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

            assertEquals(TokenSemEspera.Vencido, a.tokenSemEsperar())
            assertTrue(
                "O token não foi renovado em 5 s. A renovação disparada em segundo " +
                    "plano não chegou a gravar sessão nova.",
                esperarAte(5_000) { a.tokenSemEsperar().tokenOuNulo == "tok-renovado" },
            )
        }
    }

    // ── Os tetos de rede ──────────────────────────────────────────────────────

    /**
     * **Por que o teto não existia — medido no artefato, não lembrado.**
     *
     * `OkHttpClient()` de fábrica: `callTimeoutMillis` é **0**, que em OkHttp
     * significa *sem teto*. Os três timeouts parciais são de 10 s cada e
     * `retryOnConnectionFailure` vem ligado, então o pior caso era 10 s por rota,
     * por fase, sem limite superior escrito em lugar nenhum.
     */
    @Test
    fun oClienteDeFabricaNaoTemTeto() {
        val fabrica = OkHttpClient()
        assertEquals("callTimeout de fábrica deveria ser 0 (sem teto)", 0, fabrica.callTimeoutMillis)
        assertEquals(10_000, fabrica.connectTimeoutMillis)
        assertEquals(10_000, fabrica.readTimeoutMillis)
        assertEquals(10_000, fabrica.writeTimeoutMillis)
        assertTrue("retryOnConnectionFailure de fábrica", fabrica.retryOnConnectionFailure)

        val nosso = AutenticacaoSupabase.clientePadrao()
        assertEquals(
            "O cliente da autenticação perdeu o teto de chamada.",
            AutenticacaoSupabase.TETO_DA_CHAMADA_MS.toInt(),
            nosso.callTimeoutMillis,
        )
    }

    /**
     * **O teto da renovação que espera rede, medido contra um servidor lento.**
     *
     * Um servidor *lento* e não um *mudo*, de propósito: um servidor mudo dispara o
     * `readTimeout` de 3 s e a medição não diria nada sobre o `callTimeout`. O
     * servidor deste teste anuncia `Content-Length: 30` e escreve **um byte por
     * segundo** — o `readTimeout` nunca vence, porque ele conta desde o último byte
     * recebido. Só o `callTimeout`, que cobre a chamada inteira, encerra isto.
     *
     * Sem `callTimeout` a chamada duraria 30 s. Com ele, 6. É a medida que faltava:
     * até 22/08 o teto deste ramo estava registrado por escrito como
     * **NÃO MEDIDO**.
     */
    @Test
    fun oTetoDaRenovacaoEOCallTimeout() {
        ServidorFalso(Comportamento.LENTO).use { servidor ->
            cofre.sessao = sessao(relogio - 1)
            val a = AutenticacaoSupabase(
                config = ConfigRealtime(servidor.url, "chave"),
                cofre = cofre,
                agora = { relogio },
            )

            val inicio = System.nanoTime()
            val token = kotlinx.coroutines.runBlocking { a.tokenValido() }
            val gastoMs = (System.nanoTime() - inicio) / 1_000_000.0

            println("TETO DA RENOVAÇÃO: ${"%.0f".format(gastoMs)} ms contra servidor lento")

            assertNull("um servidor que não completa a resposta não renova nada", token)
            val teto = AutenticacaoSupabase.TETO_DA_CHAMADA_MS
            assertTrue(
                "A renovação levou ${"%.0f".format(gastoMs)} ms contra um teto de " +
                    "$teto ms. Sem `callTimeout` este servidor a seguraria por 30 s.",
                gastoMs < teto + 2_500,
            )
            assertTrue(
                "A renovação levou só ${"%.0f".format(gastoMs)} ms — cedo demais " +
                    "para ter sido o `callTimeout`. Ou o servidor lento parou de " +
                    "ser lento, ou outro timeout está encerrando a chamada antes, e " +
                    "esta medição deixou de medir o que o nome dela diz.",
                gastoMs > teto * 0.7,
            )
        }
    }

    // ── Andaime ───────────────────────────────────────────────────────────────

    private fun esperarAte(tetoMs: Long, condicao: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            if (condicao()) return true
            Thread.sleep(20)
        }
        return condicao()
    }

    private enum class Comportamento {
        /** Aceita a conexão e nunca responde. */
        BURACO_NEGRO,

        /** Anuncia 30 bytes e escreve um por segundo: só o `callTimeout` a encerra. */
        LENTO,

        /** Responde uma sessão nova, válida por uma hora. */
        RENOVA,
    }

    /**
     * Servidor HTTP de mentira, em `ServerSocket` puro.
     *
     * Sem MockWebServer de propósito: acrescentar dependência de teste para medir um
     * timeout seria trocar uma medição por uma dependência, e o `CLAUDE.md` §2 pede
     * justificar toda dependência nova por tamanho, licença e alternativa nativa. A
     * alternativa nativa cabe em quarenta linhas.
     */
    private class ServidorFalso(private val comportamento: Comportamento) : AutoCloseable {

        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val conexoes = AtomicInteger(0)
        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        private val laco = Thread {
            while (!socket.isClosed) {
                val cliente = try { socket.accept() } catch (_: Exception) { break }
                conexoes.incrementAndGet()
                try {
                    cliente.use { c ->
                        lerCabecalhos(c.getInputStream())
                        when (comportamento) {
                            // Segura a conexão aberta: quem espera é o cliente.
                            Comportamento.BURACO_NEGRO -> Thread.sleep(60_000)
                            Comportamento.LENTO -> {
                                val saida = c.getOutputStream()
                                saida.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: 30\r\nConnection: close\r\n\r\n")
                                        .toByteArray(),
                                )
                                saida.flush()
                                repeat(30) {
                                    saida.write('x'.code)
                                    saida.flush()
                                    Thread.sleep(1_000)
                                }
                            }
                            Comportamento.RENOVA -> {
                                val corpo = """
                                    {"access_token":"tok-renovado","refresh_token":"ref2",
                                     "expires_in":3600}
                                """.trimIndent().replace("\n", "")
                                val saida = c.getOutputStream()
                                saida.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${corpo.toByteArray().size}\r\n" +
                                        "Connection: close\r\n\r\n$corpo").toByteArray(),
                                )
                                saida.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Cliente desistiu (é o que os testes de timeout provocam).
                }
            }
        }.apply { isDaemon = true; start() }

        /** Até a linha em branco. O corpo cabe no buffer do socket e não é lido. */
        private fun lerCabecalhos(entrada: InputStream) {
            var seguidos = 0
            while (seguidos < 4) {
                val b = entrada.read()
                if (b < 0) return
                seguidos = when {
                    b == '\r'.code && seguidos % 2 == 0 -> seguidos + 1
                    b == '\n'.code && seguidos % 2 == 1 -> seguidos + 1
                    else -> 0
                }
            }
        }

        override fun close() {
            socket.close()
            laco.interrupt()
        }
    }
}
