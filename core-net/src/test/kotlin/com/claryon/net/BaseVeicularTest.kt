package com.claryon.net

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que estes testes protegem não é a tradução do JSON — é a **impossibilidade** de
 * o aparelho dizer "sem restrição" sem que o servidor tenha dito exatamente isso.
 *
 * Cada caso abaixo é uma forma diferente de o app não saber: resposta vazia, código
 * de restrição novo, situação desconhecida, base de demonstração, base ausente. Em
 * nenhuma delas o resultado pode ser lido como veículo liberado.
 */
class BaseVeicularTest {

    private fun resposta(vararg pares: Pair<String, Any?>): JSONObject =
        JSONObject().apply { pares.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    // ── O caminho que trava a abordagem ──────────────────────────────────────

    @Test
    fun `veiculo com roubo devolve a restricao e nao libera`() {
        val r = InterpretadorVeicular.de(
            resposta(
                "situacao" to "encontrado", "procedencia" to "oficial",
                "fonte" to "Detran", "placa_consultada" to "DEM0A01",
                "restricao" to "roubo_furto", "marca_modelo" to "Fiat Uno",
                "cor" to "Branco", "ano" to 2014,
            )
        )
        val encontrado = r as ConsultaVeicular.Encontrado
        assertEquals(RestricaoVeicular.ROUBO_FURTO, encontrado.restricao)
        assertTrue(encontrado.restricao.impedeLiberacao)
        assertFalse(r.liberaSemRestricao)
        assertEquals(2014, encontrado.ano)
    }

    // ── As formas de não saber, uma por uma ──────────────────────────────────

    /**
     * **O teste central.** Um código de restrição que este cliente não conhece só
     * pode virar "não entendi". Se `RestricaoVeicular.de` ganhar um `?: SEM_RESTRICAO`, um
     * servidor mais novo passa a liberar veículos num app mais velho — e nada mais
     * na suíte pegaria isso.
     */
    @Test
    fun `restricao desconhecida NAO vira sem restricao`() {
        assertNull(RestricaoVeicular.de("furto_em_apuracao"))
        assertNull(
            InterpretadorVeicular.de(
                resposta(
                    "situacao" to "encontrado", "procedencia" to "oficial",
                    "placa_consultada" to "DEM0A01", "restricao" to "furto_em_apuracao",
                )
            )
        )
    }

    /** Contra-teste do anterior: os códigos que EXISTEM continuam sendo aceitos. Sem
     *  esta linha, um `de()` que devolvesse nulo sempre passaria no teste acima. */
    @Test
    fun `os codigos conhecidos continuam sendo aceitos`() {
        RestricaoVeicular.entries.forEach { assertEquals(it, RestricaoVeicular.de(it.codigo)) }
        assertEquals(6, RestricaoVeicular.entries.size)
    }

    @Test
    fun `situacao desconhecida vira nulo, nao veiculo limpo`() {
        assertNull(InterpretadorVeicular.de(resposta("situacao" to "talvez", "procedencia" to "oficial")))
        assertNull(InterpretadorVeicular.de(resposta("procedencia" to "oficial")))
    }

    @Test
    fun `nao encontrada e uma resposta, com a procedencia junto`() {
        val r = InterpretadorVeicular.de(
            resposta(
                "situacao" to "nao_encontrada", "procedencia" to "demonstracao",
                "fonte" to "Semente", "placa_consultada" to "ZZZ9Z99",
                "restricao" to null,
            )
        )
        val nao = r as ConsultaVeicular.NaoEncontrada
        assertEquals(Procedencia.DEMONSTRACAO, nao.procedencia)
        assertEquals("ZZZ9Z99", nao.placa)
        assertFalse(r.liberaSemRestricao)
    }

    @Test
    fun `placa invalida e base indisponivel nunca liberam`() {
        val invalida = InterpretadorVeicular.de(
            resposta("situacao" to "placa_invalida", "procedencia" to "demonstracao", "placa_consultada" to null)
        )
        assertTrue(invalida is ConsultaVeicular.PlacaInvalida)
        assertFalse(invalida!!.liberaSemRestricao)

        val semBase = InterpretadorVeicular.de(
            resposta("situacao" to "base_indisponivel", "procedencia" to "demonstracao")
        )
        assertEquals(ConsultaVeicular.BaseIndisponivel, semBase)
        assertFalse(semBase!!.liberaSemRestricao)
    }

    // ── Procedência: assimétrica de propósito ────────────────────────────────

    @Test
    fun `so o literal oficial produz OFICIAL`() {
        assertEquals(Procedencia.OFICIAL, Procedencia.de("oficial"))
        listOf(null, "", "demonstracao", "Oficial", "OFICIAL", "oficial ", "homologacao")
            .forEach { assertEquals("procedencia=$it", Procedencia.DEMONSTRACAO, Procedencia.de(it)) }
    }

    /**
     * O caso que a regra de produto exige: a MESMA situação, com a MESMA restrição,
     * libera na base oficial e não libera na de demonstração. Se as duas
     * respondessem igual, o flag de procedência seria decoração.
     */
    @Test
    fun `sem restricao so libera quando a base e oficial`() {
        fun limpo(procedencia: String) = InterpretadorVeicular.de(
            resposta(
                "situacao" to "encontrado", "procedencia" to procedencia,
                "placa_consultada" to "DEM0A02", "restricao" to "sem_restricao",
            )
        )!!

        assertTrue(limpo("oficial").liberaSemRestricao)
        assertFalse(limpo("demonstracao").liberaSemRestricao)
    }

    @Test
    fun `nada consta na base de demonstracao nao e nada consta`() {
        fun ausente(procedencia: String) = InterpretadorVeicular.de(
            resposta(
                "situacao" to "nao_encontrada", "procedencia" to procedencia,
                "placa_consultada" to "ZZZ9Z99",
            )
        )!!

        assertTrue(ausente("oficial").liberaSemRestricao)
        assertFalse(ausente("demonstracao").liberaSemRestricao)
    }

    // ── O colapso de seis situações em três earcons ──────────────────────────

    /**
     * O earcon distingue três classes; a base declara seis situações. O mapa é uma
     * decisão de produto, e este teste existe para que ela não seja refeita por
     * engano na hora de ligar o executor.
     *
     * As duas linhas que importam: **clonagem soa como furto/roubo**, porque placa
     * clonada acompanha veículo de origem criminosa e o som administrativo é o som
     * calmo; **apreensão soa como administrativa**, porque é ordem judicial e não
     * indício de risco ao agente.
     */
    @Test
    fun `as seis situacoes colapsam nos tres earcons como decidido`() {
        assertEquals(
            mapOf(
                RestricaoVeicular.SEM_RESTRICAO to ClasseDeRestricao.SEM_RESTRICAO,
                RestricaoVeicular.ROUBO_FURTO to ClasseDeRestricao.FURTO_ROUBO,
                RestricaoVeicular.CLONAGEM_SUSPEITA to ClasseDeRestricao.FURTO_ROUBO,
                RestricaoVeicular.BLOQUEIO_JUDICIAL to ClasseDeRestricao.ADMINISTRATIVA,
                RestricaoVeicular.APREENSAO to ClasseDeRestricao.ADMINISTRATIVA,
                RestricaoVeicular.LICENCIAMENTO_VENCIDO to ClasseDeRestricao.ADMINISTRATIVA,
            ),
            RestricaoVeicular.entries.associateWith { it.classe },
        )
    }

    /** Só `SEM_RESTRICAO` cai na classe limpa — nenhuma restrição real pode soar como
     *  veículo liberado. */
    @Test
    fun `nenhuma restricao real soa como veiculo limpo`() {
        assertEquals(
            listOf(RestricaoVeicular.SEM_RESTRICAO),
            RestricaoVeicular.entries.filter { it.classe == ClasseDeRestricao.SEM_RESTRICAO },
        )
        assertEquals(
            RestricaoVeicular.entries.filter { it.impedeLiberacao }.toSet(),
            RestricaoVeicular.entries.filter { it.classe != ClasseDeRestricao.SEM_RESTRICAO }.toSet(),
        )
    }

    // ── O padrão honesto ─────────────────────────────────────────────────────

    @Test
    fun `a base ausente responde indisponivel, e nao silencio`() = runTest {
        val r = BaseVeicularIndisponivel.consultar("DEM0A01")
        assertEquals(ConsultaVeicular.BaseIndisponivel, r.getOrNull())
        assertFalse(r.getOrThrow().liberaSemRestricao)
    }

    /**
     * Nenhuma resposta possível libera, exceto as duas que vêm de base oficial. É a
     * varredura que pega um ramo novo acrescentado sem pensar na liberação.
     */
    @Test
    fun `apenas base oficial pode liberar`() {
        val todas = listOf(
            ConsultaVeicular.BaseIndisponivel,
            ConsultaVeicular.PlacaInvalida("ruido"),
            ConsultaVeicular.NaoEncontrada("ZZZ9Z99", Procedencia.DEMONSTRACAO, null),
            ConsultaVeicular.NaoEncontrada("ZZZ9Z99", Procedencia.OFICIAL, "Detran"),
        ) + RestricaoVeicular.entries.flatMap { r ->
            Procedencia.entries.map { p ->
                ConsultaVeicular.Encontrado("DEM0A01", p, r, null, null, null, null)
            }
        }

        val liberam = todas.filter { it.liberaSemRestricao }
        assertEquals(
            listOf(
                ConsultaVeicular.NaoEncontrada("ZZZ9Z99", Procedencia.OFICIAL, "Detran"),
                ConsultaVeicular.Encontrado(
                    "DEM0A01", Procedencia.OFICIAL, RestricaoVeicular.SEM_RESTRICAO, null, null, null, null
                ),
            ),
            liberam,
        )
    }
}
