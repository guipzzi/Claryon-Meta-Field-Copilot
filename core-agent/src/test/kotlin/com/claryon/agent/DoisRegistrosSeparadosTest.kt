package com.claryon.agent

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dois registros, e o que se prova é que são DOIS.**
 *
 * A `specs/consulta-externa.spec.md` pede duas coisas que parecem contraditórias:
 * §4 quer *"o carimbo de tempo da consulta"* junto do serviço e do trecho; §7.5
 * diz que *"a granularidade é o dia, não o segundo"*. Não é contradição — são
 * registros com propósitos opostos, e a decisão 5 explica por quê:
 *
 *  - [RegistroDeAuditoria] serve ao **agente**, fica no aparelho, e precisa da
 *    precisão para que a resposta seja conferível se for contestada;
 *  - [RegistroDeUso] serve ao **corpus**, pode sair do aparelho, e precisa
 *    justamente de NÃO ter precisão: *"sem agente, sem posição e sem hora exata,
 *    duas perguntas do mesmo turno não são ligáveis entre si"* — que é a
 *    propriedade que separa estatística de uso de histórico de um policial.
 *
 * Implementá-los como um só faria a precisão do primeiro vazar para o segundo. Por
 * isso as asserções aqui são sobre a **forma dos tipos**, e não sobre um valor
 * calculado: valor certo hoje não impede campo novo amanhã.
 */
class DoisRegistrosSeparadosTest {

    private val zona = ZoneId.of("America/Sao_Paulo")

    /**
     * 2026-08-22, 23h47min32s em São Paulo — **de propósito perto da virada**.
     * No mesmo instante, em Tóquio, já é dia 23: é assim que o teste do fuso vira
     * medição em vez de tautologia.
     */
    private val instante = 1_787_453_252_000L

    private val consulta = ConsultaHigienizada.de(CategoriaDeLugar.HOSPITAL)

    // ── A estatística: exatamente cinco campos, e nenhum deles é hora ──────────

    /**
     * **A lista de campos é FECHADA, e é isso que impede o vazamento futuro.**
     *
     * A garantia da decisão 5 não é "hoje ninguém preencheu o agente": é **não há
     * onde**. Um teste que só conferisse valores passaria no dia em que alguém
     * acrescentasse `carimboMillis` "só para depurar". Este reprova.
     */
    @Test
    fun aEstatisticaDeUso_temExatamenteOsCamposPublicaveis() {
        // `Companion` é campo declarado da classe e não é dado: filtrar por
        // `isSynthetic` não o pega, porque o Kotlin o emite como campo real.
        val campos = RegistroDeUso::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "Companion" }
            .map { it.name }
            .toSet()

        assertEquals(
            "os campos de RegistroDeUso mudaram. Cada campo novo é uma chance de " +
                "ligar duas perguntas do mesmo turno — e é isso, e só isso, que " +
                "separa estatística de uso de histórico de um policial.",
            setOf("consulta", "categoria", "respondida", "fonte", "dia"),
            campos,
        )
    }

    /** O dia é `AAAA-MM-DD`. Nada mais fino sobra na string. */
    @Test
    fun oDiaNaoCarregaHora() {
        val r = RegistroDeUso.de(consulta, respondida = true, fonte = FonteDaResposta.EXTERNA_ESTRUTURADA, epochMillis = instante, zona = zona)

        assertTrue("dia fora do formato AAAA-MM-DD: ${r.dia}", Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(r.dia))
        assertFalse("a linha exportável carrega o instante bruto: ${r.paraLinha()}", "$instante" in r.paraLinha())
        assertFalse("a linha exportável carrega hora: ${r.paraLinha()}", Regex("\\d{2}:\\d{2}").containsMatchIn(r.paraLinha()))
    }

    /**
     * **Instantes diferentes do mesmo dia produzem o MESMO registro.**
     *
     * É a asserção que prova a indistinguibilidade, e não só o formato: se dois
     * registros do mesmo dia são iguais campo a campo, não há como ordená-los, e
     * sem ordem não há como reconstruir um turno.
     */
    @Test
    fun duasPerguntasDoMesmoDia_ficamIndistinguiveis() {
        // 23 horas antes: 00h47 do MESMO dia em São Paulo. Um turno inteiro de
        // distância, e ainda assim os dois registros têm de sair idênticos.
        val manha = RegistroDeUso.de(consulta, true, FonteDaResposta.EXTERNA_ESTRUTURADA, instante - 82_800_000L, zona)
        val noite = RegistroDeUso.de(consulta, true, FonteDaResposta.EXTERNA_ESTRUTURADA, instante, zona)

        assertEquals(
            "os dois registros do mesmo dia diferem — então dá para ordená-los, e " +
                "ordem é meio caminho para reconstruir o turno de um agente",
            manha,
            noite,
        )
        assertEquals(manha.paraLinha(), noite.paraLinha())
    }

    /** O fuso é parâmetro. Um teste que não o controlasse mediria a CI. */
    @Test
    fun oDiaDependeDoFusoDeclarado_naoDoRelogioDaMaquina() {
        val saoPaulo = RegistroDeUso.dia(instante, ZoneId.of("America/Sao_Paulo"))
        val tokyo = RegistroDeUso.dia(instante, ZoneId.of("Asia/Tokyo"))
        assertTrue("o fuso não mudou o dia — o parâmetro está sendo ignorado", saoPaulo != tokyo)
    }

    // ── A auditoria: precisão, e ela NÃO vira estatística ─────────────────────

    @Test
    fun aAuditoria_carregaServicoTrechoECarimboPreciso() {
        val a = RegistroDeAuditoria.de(
            consulta = consulta,
            servico = "https://overpass-api.de/api/interpreter",
            consultaEmitida = "[out:json][timeout:2];...",
            trecho = "{\"name\":\"Hospital Municipal\"}",
            carimboMillis = instante,
            duracaoMs = 812,
        )

        assertEquals("hospital", a.consulta)
        assertEquals("HOSPITAL", a.categoria)
        assertEquals(instante, a.carimboMillis)
        assertEquals(812L, a.duracaoMs)
        assertTrue("o serviço não foi registrado", a.servico.isNotBlank())
        assertTrue("a consulta emitida não foi registrada", a.consultaEmitida.isNotBlank())
    }

    /**
     * **Nem agente, nem guarnição, nem aparelho, nem posição — nos DOIS.**
     *
     * O registro de auditoria fica no aparelho do próprio agente, então dizer quem
     * ele é seria redundante; e um dia alguém exporta o log. A varredura é por nome
     * de campo porque é o que sobrevive a uma refatoração que troque tipos.
     */
    @Test
    fun nenhumDosDois_temOndeGuardarQuemPerguntou() {
        val proibidos = listOf(
            "agent", "agente", "unit", "guarnicao", "device", "aparelho", "matricula",
            "latitude", "longitude", "lat", "lon", "posicao", "coordenada", "indicativo",
        )
        for (tipo in listOf(RegistroDeUso::class.java, RegistroDeAuditoria::class.java)) {
            val suspeitos = tipo.declaredFields
                .filterNot { it.isSynthetic || it.name == "Companion" }
                .map { it.name }
                .filter { campo -> proibidos.any { it in campo.lowercase() } }
            assertEquals(
                "${tipo.simpleName} ganhou campo que identifica quem perguntou ou " +
                    "onde estava. Não é omissão de preenchimento que garante a " +
                    "decisão 5 — é ausência de lugar onde guardar.",
                emptyList<String>(),
                suspeitos,
            )
        }
    }

    /**
     * **Não há conversão entre os dois — e a ausência é o que os mantém separados.**
     *
     * Uma função `RegistroDeAuditoria.paraUso()` seria o lugar por onde o carimbo
     * preciso escorreria para o registro publicável na primeira vez que alguém
     * quisesse "aproveitar o que já está montado". Duas chamadas separadas, dois
     * destinos separados.
     */
    @Test
    fun naoExisteConversaoDeUmRegistroNoOutro() {
        val pontes = buildList {
            RegistroDeAuditoria::class.java.declaredMethods
                .filter { it.returnType == RegistroDeUso::class.java }
                .forEach { add("RegistroDeAuditoria.${it.name}") }
            RegistroDeUso::class.java.declaredMethods
                .filter { it.returnType == RegistroDeAuditoria::class.java }
                .forEach { add("RegistroDeUso.${it.name}") }
            RegistroDeUso.Companion::class.java.declaredMethods
                .filter { m -> m.parameterTypes.any { it == RegistroDeAuditoria::class.java } }
                .forEach { add("RegistroDeUso.Companion.${it.name}") }
        }
        assertEquals(
            "apareceu uma ponte entre os dois registros. Ela é por onde a precisão " +
                "do primeiro vaza para o segundo.",
            emptyList<String>(),
            pontes,
        )
    }

    /**
     * **Os dois só nascem de [ConsultaHigienizada]** — spec §5, *um filtro, dois
     * consumidores*. Uma fábrica que aceitasse `String` criaria o segundo lugar
     * onde a higiene pode divergir, e o segundo lugar é o que fica para trás no
     * próximo conserto.
     */
    @Test
    fun asDuasFabricas_soAceitamConsultaHigienizada() {
        val companions = listOf(
            "RegistroDeUso" to RegistroDeUso.Companion::class.java,
            "RegistroDeAuditoria" to RegistroDeAuditoria.Companion::class.java,
        )
        for ((nome, companion) in companions) {
            val de = companion.declaredMethods.filter { it.name == "de" }
            assertTrue("$nome não tem fábrica `de` — a reflexão olhou o lugar errado", de.isNotEmpty())
            assertTrue(
                "$nome.de não recebe ConsultaHigienizada",
                de.all { m -> m.parameterTypes.any { it == ConsultaHigienizada::class.java } },
            )
            assertEquals(
                "$nome.de ganhou sobrecarga que aceita a consulta como texto solto",
                emptyList<String>(),
                de.filter { m -> m.parameterTypes.firstOrNull() == String::class.java }.map { it.name },
            )
        }
    }
}
