package com.claryon.net

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O que sai pelo fio — inspecionado no fio, não na intenção de quem escreveu.**
 *
 * O aceite do §6 da `specs/consulta-externa.spec.md` diz, literalmente:
 * *"verificável por teste que **inspecione a consulta emitida**"*. Um teste que
 * examinasse o retorno de `montar()` não faria isso: ele examinaria uma função
 * pura, e a consulta emitida é o que o `OkHttp` põe no socket, depois de montar,
 * codificar em formulário e escrever. Entre as duas coisas cabe um cabeçalho, um
 * parâmetro extra, um `User-Agent` com o nome do aparelho.
 *
 * Por isso aqui há um servidor de verdade, num porto de verdade, e o que as
 * asserções leem é **o corpo que chegou do outro lado**.
 *
 * ## A régua é de lista fechada, e é assim de propósito
 *
 * O teste não procura placa, nem matrícula, nem nome. Procurar defeito conhecido
 * só acha defeito conhecido. Ele exige que **todo** símbolo do corpo pertença a um
 * vocabulário enumerado aqui — palavra-chave de Overpass QL, valor de tag do OSM,
 * ou um dos três números que a consulta tem direito de carregar. Qualquer coisa
 * nova reprova, inclusive a que ninguém previu.
 */
class ConsultaGeoespacialTest {

    // ── Servidor de mentira ───────────────────────────────────────────────────

    /**
     * Um servidor HTTP de dez linhas, num porto efêmero.
     *
     * Não há `MockWebServer` no catálogo do projeto, e arrastar a dependência para
     * um teste contraria a regra de dependência nova do `CLAUDE.md` §2 — tamanho,
     * licença e alternativa nativa. A alternativa nativa é esta: `ServerSocket`,
     * que já está no JDK.
     *
     * @param resposta corpo JSON a devolver, ou `null` para nunca responder (é
     *   assim que se mede prazo estourado sem depender da internet).
     * @param demorarMs quanto esperar antes de responder.
     */
    private class ServidorDeMentira(
        private val resposta: String? = null,
        private val demorarMs: Long = 0,
    ) : AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val url: String = "http://127.0.0.1:${server.localPort}/api/interpreter"

        /** O corpo que chegou, já decodificado de `application/x-www-form-urlencoded`. */
        @Volatile
        var corpoRecebido: String? = null

        private val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    val entrada = s.getInputStream()
                    val cabecalho = ByteArrayOutputStream()
                    // Lê até a linha em branco que separa cabeçalho de corpo.
                    while (!terminaEmLinhaEmBranco(cabecalho.toByteArray())) {
                        val b = entrada.read()
                        if (b < 0) return@use
                        cabecalho.write(b)
                    }
                    val texto = cabecalho.toString(Charsets.UTF_8.name())
                    val tamanho = Regex("(?i)content-length:\\s*(\\d+)")
                        .find(texto)?.groupValues?.get(1)?.toInt() ?: 0
                    val buf = ByteArray(tamanho)
                    var lido = 0
                    while (lido < tamanho) {
                        val n = entrada.read(buf, lido, tamanho - lido)
                        if (n < 0) break
                        lido += n
                    }
                    corpoRecebido = URLDecoder.decode(
                        String(buf, Charsets.UTF_8).removePrefix("data="),
                        Charsets.UTF_8.name(),
                    )
                    if (demorarMs > 0) Thread.sleep(demorarMs)
                    val corpo = (resposta ?: return@use).toByteArray(Charsets.UTF_8)
                    s.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${corpo.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.UTF_8),
                        )
                        write(corpo)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        private fun terminaEmLinhaEmBranco(b: ByteArray): Boolean =
            b.size >= 4 && b[b.size - 4] == '\r'.code.toByte() && b[b.size - 3] == '\n'.code.toByte() &&
                b[b.size - 2] == '\r'.code.toByte() && b[b.size - 1] == '\n'.code.toByte()

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    /**
     * Cliente de teste com prazos LONGOS.
     *
     * O compartilhado tem 2 s de leitura, e com ele um teste de prazo mediria o
     * `readTimeout` em vez do `callTimeout` — passaria pelo motivo errado, e
     * continuaria passando no dia em que o `callTimeout` fosse removido.
     */
    private fun clientePaciente(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun consulta(
        servidor: ServidorDeMentira,
        prazoMs: Long = 5_000,
        raioM: Int = RAIO_DE_TESTE,
    ) = ConsultaGeoespacial(
        endpoint = servidor.url,
        client = clientePaciente(),
        prazoMs = prazoMs,
        raioM = raioM,
        agora = { RELOGIO_FIXO },
    )

    // ── A régua da consulta emitida ───────────────────────────────────────────

    /**
     * Palavras que a consulta emitida pode conter. **Lista fechada e escrita à mão.**
     *
     * Derivá-la de `LugarProcurado.filtroOsm` seria autoabsolvição: uma categoria
     * nova com um filtro de texto livre entraria na lista branca por construção e
     * o teste diria que está tudo certo. Assim, categoria nova que introduza
     * símbolo novo **reprova aqui**, e quem a acrescentar precisa justificar o
     * símbolo — que é exatamente a conversa que se quer ter.
     */
    private val VOCABULARIO_PERMITIDO = setOf(
        // Overpass QL
        "out", "json", "timeout", "node", "way", "relation", "around", "center",
        // Chave e valores das tags do OSM que as três categorias usam
        "amenity", "hospital", "police", "clinic", "doctors",
    )

    /** Tudo que for letra, agrupado em palavras. */
    private fun palavrasDe(corpo: String): List<String> =
        Regex("[A-Za-zÀ-ÖØ-öø-ÿ]+").findAll(corpo).map { it.value }.toList()

    /** Tudo que for número, com sinal e casas decimais. */
    private fun numerosDe(corpo: String): List<String> =
        Regex("-?\\d+(?:\\.\\d+)?").findAll(corpo).map { it.value }.toList()

    private fun estranhas(corpo: String): List<String> =
        palavrasDe(corpo).filterNot { it in VOCABULARIO_PERMITIDO }.distinct()

    /**
     * **O teste que a spec nomeia.** Nada além do vocabulário fechado atravessa.
     *
     * Roda para as TRÊS categorias, porque o filtro OSM é diferente em cada uma e
     * o do posto de saúde é o único com expressão regular — é onde um símbolo
     * inesperado teria a melhor chance de passar despercebido.
     */
    @Test
    fun aConsultaEmitida_soCarregaVocabularioFechado_emTodaCategoria() = runTest {
        for (lugar in LugarProcurado.entries) {
            ServidorDeMentira(resposta = "{\"elements\":[]}").use { servidor ->
                consulta(servidor).maisProximo(lugar, LATITUDE, LONGITUDE)
                val corpo = servidor.corpoRecebido
                assertNotNull("o servidor não recebeu corpo nenhum para $lugar", corpo)

                assertEquals(
                    "estas palavras saíram pelo fio e não são vocabulário fechado " +
                        "($lugar): corpo=$corpo",
                    emptyList<String>(),
                    estranhas(corpo!!),
                )

                assertEquals(
                    "estes números saíram pelo fio e não são o prazo, o raio nem a " +
                        "minha coordenada arredondada ($lugar): corpo=$corpo",
                    emptySet<String>(),
                    numerosDe(corpo).toSet() - NUMEROS_PERMITIDOS,
                )
            }
        }
    }

    /**
     * **Controle positivo da régua acima — sem ele, "não achei nada" não prova nada.**
     *
     * É a lição do `CLAUDE.md` §6, pergunta 3: se o teste passaria com o defeito de
     * volta, ele não testa o defeito. Aqui o "defeito de volta" é um corpo com a
     * transcrição dentro; a régua tem de reprovar os quatro vazamentos que a spec
     * §5 enumera — placa, matrícula, nome próprio e indicativo.
     */
    @Test
    fun aRegua_reprovaCorpoEnvenenado_senaoElaNaoMedeNada() {
        val envenenado = "[out:json][timeout:2];node(around:3000,-22.9068,-43.1729)" +
            "[\"name\"=\"Sgt Paiva\"][\"placa\"=\"ABC1D23\"][\"matricula\"=\"998877\"]" +
            "[\"guarnicao\"=\"Alfa Dois\"];out center;"

        val achadas = estranhas(envenenado)
        assertTrue(
            "a régua de palavras não viu NADA num corpo com nome, placa, matrícula " +
                "e indicativo. Ela está quebrada, e o teste de cima é decorativo.",
            achadas.containsAll(listOf("name", "Sgt", "Paiva", "placa", "matricula", "guarnicao")),
        )
        assertTrue(
            "a régua de números deixou passar a matrícula 998877",
            "998877" in (numerosDe(envenenado).toSet() - NUMEROS_PERMITIDOS),
        )
    }

    /**
     * **A coordenada sai arredondada — menos informação do que o aparelho tem.**
     *
     * Quatro casas decimais são ~11 m, abaixo do erro típico do GPS de celular:
     * não custa precisão na resposta e é estritamente menos que a correção crua.
     * A asserção que importa é a segunda — a coordenada **crua** não aparece.
     */
    @Test
    fun aCoordenadaSaiArredondada_eACruaNaoAparece() = runTest {
        ServidorDeMentira(resposta = "{\"elements\":[]}").use { servidor ->
            consulta(servidor).maisProximo(LugarProcurado.HOSPITAL, LATITUDE_CRUA, LONGITUDE_CRUA)
            val corpo = servidor.corpoRecebido!!

            assertTrue("faltou a latitude arredondada em $corpo", "-22.9068" in corpo)
            assertTrue("faltou a longitude arredondada em $corpo", "-43.1729" in corpo)
            assertTrue(
                "a coordenada CRUA (${LATITUDE_CRUA}) saiu pelo fio: $corpo",
                "-22.90681" !in corpo && "-43.17294" !in corpo,
            )
        }
    }

    // ── Procedência em TODA resposta ──────────────────────────────────────────

    @Test
    fun oEncontrado_carregaProcedenciaCompleta() = runTest {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":-22.9075,"lon":-43.1730,
               "tags":{"amenity":"hospital","name":"Hospital Municipal"}}
            ]}
        """.trimIndent()

        ServidorDeMentira(resposta = json).use { servidor ->
            val r = consulta(servidor).maisProximo(LugarProcurado.HOSPITAL, LATITUDE, LONGITUDE)
            val achado = r as RespostaGeoespacial.Encontrado

            assertEquals("Hospital Municipal", achado.nome)
            assertEquals(servidor.url, achado.procedencia.servico)
            assertEquals(RELOGIO_FIXO, achado.procedencia.carimboMillis)
            assertTrue(
                "o trecho não é o elemento que fundamentou a resposta: " +
                    achado.procedencia.trecho,
                "Hospital Municipal" in achado.procedencia.trecho,
            )
            assertEquals(
                "a consulta emitida registrada difere da que chegou ao servidor",
                servidor.corpoRecebido,
                achado.procedencia.consultaEmitida,
            )
        }
    }

    /**
     * **A resposta que NÃO acha nada também carrega procedência.**
     *
     * É o caso em que não há linha de resultado para pendurar o flag, e por isso é
     * o que se esquece — foi exatamente assim na base veicular (`0023`), onde
     * "nada consta" numa base de demonstração e numa base oficial são a mesma
     * frase com significados opostos. Aqui o custo do esquecimento é menor, mas o
     * aceite do §6 diz **toda** resposta, e "toda" inclui esta.
     *
     * Este é o teste que reprova a versão anterior deste arquivo, em que
     * `NadaPorPerto` era `data object` e não tinha onde carregar nada.
     */
    @Test
    fun oNadaPorPerto_tambemCarregaProcedencia() = runTest {
        ServidorDeMentira(resposta = "{\"elements\":[]}").use { servidor ->
            val r = consulta(servidor).maisProximo(LugarProcurado.DELEGACIA, LATITUDE, LONGITUDE)
            val nada = r as RespostaGeoespacial.NadaPorPerto

            assertEquals(servidor.url, nada.procedencia.servico)
            assertEquals(RELOGIO_FIXO, nada.procedencia.carimboMillis)
            assertEquals(
                "não houve trecho, e o campo tem de dizer isso em vez de inventar um",
                "",
                nada.procedencia.trecho,
            )
            assertEquals(
                "a consulta emitida registrada difere da que chegou ao servidor",
                servidor.corpoRecebido,
                nada.procedencia.consultaEmitida,
            )
        }
    }

    // ── O prazo, e o que ele NÃO é ────────────────────────────────────────────

    /**
     * **Decisão 1 da spec: 2 s, e estourar é recusa, não espera.**
     *
     * O valor é conferido como constante porque um teste que medisse 2 s de parede
     * gastaria 2 s a cada rodada da suíte para provar um literal.
     */
    @Test
    fun oPrazoPadraoEDeDoisSegundos() {
        assertEquals(2_000L, ConsultaGeoespacial.PRAZO_PADRAO_MS)
        assertEquals(
            "o construtor padrão não usa o prazo padrão",
            2_000L,
            ConsultaGeoespacial().prazoMs,
        )
    }

    /**
     * Servidor que recebe e nunca responde: **desiste no prazo**, e o desfecho é
     * `PrazoEstourado` — não `SemRede`.
     *
     * O contra-teste está embutido na asserção: as duas falhas pedem ações opostas
     * do agente (repetir aqui × andar até pegar sinal), e um `assertTrue(r is
     * Falha)` genérico passaria com as duas trocadas.
     */
    @Test
    fun servidorQueNuncaResponde_daPrazoEstourado_eNaoSemRede() = runTest {
        ServidorDeMentira(resposta = "{\"elements\":[]}", demorarMs = 30_000).use { servidor ->
            val inicio = System.currentTimeMillis()
            val r = consulta(servidor, prazoMs = 400)
                .maisProximo(LugarProcurado.HOSPITAL, LATITUDE, LONGITUDE)
            val gasto = System.currentTimeMillis() - inicio

            assertEquals(RespostaGeoespacial.PrazoEstourado, r)
            assertTrue("esperou $gasto ms com prazo de 400 ms — isso é espera, não recusa", gasto < 5_000)
        }
    }

    /**
     * Porto fechado: **`SemRede`, e não `PrazoEstourado`.**
     *
     * O par deste teste com o de cima é o que impede o colapso das duas falhas em
     * uma. Sozinho, cada um passaria com a implementação que devolvesse sempre o
     * mesmo desfecho.
     */
    @Test
    fun semNinguemEscutando_daSemRede_eNaoPrazoEstourado() = runTest {
        val porto = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val r = ConsultaGeoespacial(
            endpoint = "http://127.0.0.1:$porto/api/interpreter",
            client = clientePaciente(),
            prazoMs = 5_000,
            agora = { RELOGIO_FIXO },
        ).maisProximo(LugarProcurado.HOSPITAL, LATITUDE, LONGITUDE)

        assertEquals(RespostaGeoespacial.SemRede, r)
    }

    // ── O "mais próximo" é conta nossa ────────────────────────────────────────

    /**
     * O Overpass não ordena por distância, e o teste garante que a ordem do corpo
     * não decide a resposta: o mais perto vem por último no JSON.
     */
    @Test
    fun escolheOMaisProximo_naoOPrimeiroQueVeio() = runTest {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":-22.9300,"lon":-43.1730,
               "tags":{"amenity":"hospital","name":"Longe"}},
              {"type":"node","id":2,"lat":-22.9070,"lon":-43.1730,
               "tags":{"amenity":"hospital","name":"Perto"}}
            ]}
        """.trimIndent()

        ServidorDeMentira(resposta = json).use { servidor ->
            val r = consulta(servidor).maisProximo(LugarProcurado.HOSPITAL, LATITUDE, LONGITUDE)
            assertEquals("Perto", (r as RespostaGeoespacial.Encontrado).nome)
            assertTrue("distância implausível: ${r.distanciaM} m", r.distanciaM < 100)
        }
    }

    /** Corpo que não é JSON de Overpass não vira resposta inventada. */
    @Test
    fun corpoIlegivel_naoViraLugar() = runTest {
        ServidorDeMentira(resposta = "<html>bloqueado</html>").use { servidor ->
            val r = consulta(servidor).maisProximo(LugarProcurado.HOSPITAL, LATITUDE, LONGITUDE)
            assertEquals(RespostaGeoespacial.SemRede, r)
            assertNull(
                "houve resposta apesar de o corpo ser ilegível",
                r as? RespostaGeoespacial.Encontrado,
            )
        }
    }

    private companion object {
        /** Praça XV, Rio. Já com quatro casas — o que o fio deve ver. */
        const val LATITUDE = -22.9068
        const val LONGITUDE = -43.1729

        /** A mesma coordenada como o GPS a entrega: mais casas do que podem sair. */
        const val LATITUDE_CRUA = -22.906812345
        const val LONGITUDE_CRUA = -43.172941234

        const val RAIO_DE_TESTE = 3_000

        /** Relógio parado: carimbo virou asserção, não fonte de intermitência. */
        const val RELOGIO_FIXO = 1_755_000_000_000L

        /**
         * Os três números que a consulta tem direito de carregar: o prazo que o
         * servidor recebe (`[timeout:2]`), o raio, e a minha coordenada com quatro
         * casas. Qualquer outro é vazamento.
         */
        val NUMEROS_PERMITIDOS = setOf("2", "$RAIO_DE_TESTE", "$LATITUDE", "$LONGITUDE")
    }
}
