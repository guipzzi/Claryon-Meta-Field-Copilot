package com.claryon.net

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **As duas portas que ficaram dias sem chamador** — `rastro_do_par` e
 * `quem_me_consultou` —, agora com o parsing coberto.
 *
 * Elas foram ligadas e verificadas à mão no aparelho, o que prova que o caminho
 * existe e não prova o que acontece quando a resposta é estranha: azimute nulo
 * (pontos coincidentes, dupla na mesma viatura), lista vazia, campo faltando. É
 * onde parsing costuma inventar zero.
 *
 * Sem `MockWebServer`: um `Interceptor` devolve a resposta enlatada. O
 * `OkHttpClient` já é injetável, e uma dependência de teste a mais precisaria
 * passar pela mesma régua de tamanho e licença que o resto.
 */
class PortasDeTransparenciaTest {

    private fun clienteQueResponde(json: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        ).build()

    private fun historico(json: String) = HistoricoDoCanal(
        config = ConfigRealtime(projetoUrl = "https://exemplo.invalid", apiKey = "anon"),
        tokenDeSessao = { "jwt-de-teste" },
        client = clienteQueResponde(json),
    )

    // ── rastro do par ─────────────────────────────────────────────────────────

    @Test
    fun oRastroEInterpretadoComDistanciaAzimuteEIdade() = runBlocking {
        val r = historico(
            """[{"distancia_m":250.0,"azimute":194.3,"idade_s":44},
                {"distancia_m":1500.0,"azimute":225.1,"idade_s":1244}]""",
        ).rastroDoPar("Bravo Dois").getOrThrow()

        assertEquals(2, r.size)
        assertEquals(250.0, r[0].distanciaM, 0.01)
        assertEquals(194.3, r[0].azimuteGraus!!, 0.01)
        assertEquals(44, r[0].idadeS)
        assertEquals(1244, r[1].idadeS)
    }

    /**
     * `ST_Azimuth` devolve NULL para pontos coincidentes — dupla na mesma viatura.
     * Zero seria NORTE, uma afirmação que o servidor não fez.
     */
    @Test
    fun azimuteNULO_ficaNulo_naoViraNorte() = runBlocking {
        val r = historico("""[{"distancia_m":10.0,"azimute":null,"idade_s":5}]""")
            .rastroDoPar("Bravo Dois").getOrThrow()
        assertNull("azimute nulo virou um número — o mapa apontaria para o norte", r[0].azimuteGraus)
    }

    /**
     * **A garantia que mais importa, e ela é sobre o que NÃO passa.**
     *
     * O servidor devolve grandeza, nunca coordenada. Se um dia alguém acrescentar
     * `lat`/`lon` ao retorno — por engano ou por "seria útil" —, nada disso pode
     * chegar ao cliente. `PontoDoRastro` não tem campo onde guardar, e este teste
     * exercita o caminho com os campos presentes para provar que são ignorados em
     * vez de explodirem ou vazarem.
     */
    @Test
    fun coordenadaNaResposta_NAO_atravessaOParsing() = runBlocking {
        val r = historico(
            """[{"distancia_m":250.0,"azimute":194.0,"idade_s":44,
                 "lat":-16.68,"lon":-49.25,"geom":"POINT(-49.25 -16.68)"}]""",
        ).rastroDoPar("Bravo Dois").getOrThrow()

        assertEquals(1, r.size)
        val campos = PontoDoRastro::class.java.declaredFields.map { it.name }
        assertTrue(
            "PontoDoRastro ganhou campo de coordenada: $campos. O tipo existe " +
                "justamente para não ter onde guardar posição de terceiro",
            campos.none { it.contains("lat", true) || it.contains("lon", true) || it.contains("geom", true) },
        )
    }

    /**
     * Distância ausente é linha inútil: sem ela não há grandeza nenhuma. Descartar
     * é melhor que devolver `NaN`, que o formatador transformaria em texto absurdo.
     */
    @Test
    fun linhaSemDistancia_eDescartada_emVezDeViraNaN() = runBlocking {
        val r = historico(
            """[{"azimute":10.0,"idade_s":5},{"distancia_m":100.0,"azimute":20.0,"idade_s":6}]""",
        ).rastroDoPar("Bravo Dois").getOrThrow()
        assertEquals("a linha sem distância devia sumir", 1, r.size)
        assertEquals(100.0, r[0].distanciaM, 0.01)
    }

    @Test
    fun rastroVazio_eListaVazia_naoFalha() = runBlocking {
        assertTrue(historico("[]").rastroDoPar("Bravo Dois").getOrThrow().isEmpty())
    }

    // ── quem me consultou ─────────────────────────────────────────────────────

    @Test
    fun asConsultasSaoInterpretadas() = runBlocking {
        val c = historico(
            """[{"indicativo_de_quem_consultou":"Bravo Dois","em":"2026-08-20T18:20:11.5+00:00"}]""",
        ).quemMeConsultou().getOrThrow()
        assertEquals(1, c.size)
        assertEquals("Bravo Dois", c[0].indicativo)
        assertTrue(c[0].em.startsWith("2026-08-20"))
    }

    /**
     * **O log guarda o ATO, nunca a resposta.** Se um dia a função passar a
     * devolver distância junto, o tipo não tem onde pôr — e é assim que a garantia
     * da `0017` sobrevive a uma mudança de servidor feita por outra pessoa.
     */
    @Test
    fun aRespostaDaConsulta_NAO_temOndeSerGuardada() {
        val campos = ConsultaRecebida::class.java.declaredFields.map { it.name }
        assertTrue(
            "ConsultaRecebida ganhou campo de resposta: $campos. Um log que guarda " +
                "a resposta é um histórico de posição com outro nome",
            campos.none {
                it.contains("dist", true) || it.contains("azimut", true) ||
                    it.contains("speed", true) || it.contains("veloc", true)
            },
        )
    }

    @Test
    fun listaVaziaDeConsultas_eAfirmacao_naoErro() = runBlocking {
        assertTrue(historico("[]").quemMeConsultou().getOrThrow().isEmpty())
    }
}
