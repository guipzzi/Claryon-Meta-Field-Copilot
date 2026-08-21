package com.claryon.field.veicular

import com.claryon.agent.Restricao
import com.claryon.field.agent.ClaryonIntentExecutor.ConsultaDePlaca
import com.claryon.net.ConsultaVeicular
import com.claryon.net.Procedencia
import com.claryon.net.RestricaoVeicular
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A régua entre a base e a fala — varrida, não amostrada.**
 *
 * Este é o ponto do produto em que um erro devolve a chave de um carro roubado. Os
 * testes de exemplo abaixo existem para nomear os casos; os dois de **varredura**
 * são os que valem, porque prendem o comportamento para todos os valores dos enums
 * e continuam valendo no dia em que a migração `0023` ganhar um código novo.
 */
class ConsultaNaBaseVeicularTest {

    private fun encontrado(
        restricao: RestricaoVeicular,
        procedencia: Procedencia,
    ) = ConsultaVeicular.Encontrado(
        placa = "ABC1D23",
        procedencia = procedencia,
        restricao = restricao,
        fonte = "semente de demonstração",
        marcaModelo = null,
        cor = null,
        ano = null,
    )

    // ── A varredura que importa ───────────────────────────────────────────────

    /**
     * **Nada vindo de base de demonstração pode ser dito como "sem restrição".**
     *
     * O laço cobre os seis códigos da migração `0023` mais as duas respostas sem
     * linha. Um exemplo isolado passaria com um `when` que esquecesse um valor; a
     * varredura não.
     */
    @Test
    fun nenhumaRespostaDeDemonstracaoLiberaOVeiculo() {
        val respostas: List<ConsultaVeicular> =
            RestricaoVeicular.entries.map { encontrado(it, Procedencia.DEMONSTRACAO) } +
                ConsultaVeicular.NaoEncontrada("ABC1D23", Procedencia.DEMONSTRACAO, "semente") +
                ConsultaVeicular.PlacaInvalida("nao era placa") +
                ConsultaVeicular.BaseIndisponivel

        val liberadas = respostas.filter { r ->
            traduzir(r) == ConsultaDePlaca.Respondeu("ABC1D23", Restricao.SEM_RESTRICAO)
        }

        assertEquals(
            "Estas respostas fariam o agente ouvir \"ABC1D23, sem restrição.\" sem " +
                "base oficial que sustente: $liberadas.\n\nÉ o pior desfecho deste " +
                "fluxo: o agente libera um veículo porque o aparelho disse que estava " +
                "limpo. Ver CLAUDE.md §2 e core-net/BaseVeicular.kt.",
            emptyList<ConsultaVeicular>(),
            liberadas,
        )
    }

    /**
     * **Contra-teste da varredura acima: com base OFICIAL, "sem restrição" sai.**
     *
     * Sem isto, a varredura passaria também se `traduzir` devolvesse
     * `NaoRespondeu` para tudo — e o produto teria uma consulta que nunca responde,
     * verde em todos os testes.
     */
    @Test
    fun baseOficialLimpa_dizSemRestricao() {
        assertEquals(
            ConsultaDePlaca.Respondeu("ABC1D23", Restricao.SEM_RESTRICAO),
            traduzir(encontrado(RestricaoVeicular.SEM_RESTRICAO, Procedencia.OFICIAL)),
        )
        assertEquals(
            "base oficial que afirma não ter o veículo é \"nada consta\"",
            ConsultaDePlaca.Respondeu("ABC1D23", Restricao.SEM_RESTRICAO),
            traduzir(ConsultaVeicular.NaoEncontrada("ABC1D23", Procedencia.OFICIAL, "Detran")),
        )
    }

    /**
     * **Restrição declarada é falada mesmo vinda da demonstração.**
     *
     * A assimetria é deliberada: falso alarme custa uma conferência, falso silêncio
     * custa um veículo roubado liberado. Varre os cinco códigos que impedem
     * liberação.
     */
    @Test
    fun restricaoDeclarada_saiAteDaBaseDeDemonstracao() {
        val comRestricao = RestricaoVeicular.entries.filter { it.impedeLiberacao }
        assertEquals("a migração 0023 tem cinco códigos que impedem", 5, comRestricao.size)

        comRestricao.forEach { r ->
            val traduzida = traduzir(encontrado(r, Procedencia.DEMONSTRACAO))
            assertTrue(
                "$r foi silenciada por ser de demonstração — errar para o lado do " +
                    "alarme custa uma conferência; para o outro, um carro roubado",
                traduzida is ConsultaDePlaca.Respondeu,
            )
            assertTrue(
                "$r virou \"sem restrição\"",
                (traduzida as ConsultaDePlaca.Respondeu).restricao != Restricao.SEM_RESTRICAO,
            )
        }
    }

    /**
     * Clonagem suspeita é **furto/roubo**, não administrativa — a decisão está
     * escrita em `core-net`, e o mapeamento não pode desfazê-la ao atravessar.
     */
    @Test
    fun clonagemSuspeita_chegaComoFurtoRoubo() {
        assertEquals(
            ConsultaDePlaca.Respondeu("ABC1D23", Restricao.FURTO_ROUBO),
            traduzir(encontrado(RestricaoVeicular.CLONAGEM_SUSPEITA, Procedencia.OFICIAL)),
        )
    }

    /** Bloqueio judicial e apreensão são judiciais, não risco imediato ao agente. */
    @Test
    fun bloqueioEApreensao_chegamComoAdministrativas() {
        listOf(RestricaoVeicular.BLOQUEIO_JUDICIAL, RestricaoVeicular.APREENSAO).forEach { r ->
            assertEquals(
                r.name,
                ConsultaDePlaca.Respondeu("ABC1D23", Restricao.ADMINISTRATIVA),
                traduzir(encontrado(r, Procedencia.OFICIAL)),
            )
        }
    }

    /** Placa recusada pelo servidor não vira consulta — vira recusa audível. */
    @Test
    fun placaInvalidaEBaseAusente_naoRespondem() {
        assertEquals(ConsultaDePlaca.NaoRespondeu, traduzir(ConsultaVeicular.PlacaInvalida("xyz")))
        assertEquals(ConsultaDePlaca.NaoRespondeu, traduzir(ConsultaVeicular.BaseIndisponivel))
    }
}
