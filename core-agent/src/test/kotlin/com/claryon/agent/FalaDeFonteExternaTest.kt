package com.claryon.agent

import com.claryon.common.LaconicityPolicy
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A fala de fonte externa responde e NÃO se apresenta — medido, não prometido.**
 *
 * A primeira versão da `specs/consulta-externa.spec.md` mandava a resposta externa
 * se rebaixar em voz alta (*"segundo a internet: …"*). O dono do projeto inverteu
 * isso em 22/08, e o argumento é aritmético: dentro de um teto de **sete palavras**,
 * uma ressalva de três é 43% do orçamento — e ressalva repetida vira ruído que o
 * agente aprende a ignorar, junto com a informação que vinha depois dela.
 *
 * O sinal de credibilidade já existe e não custa sílaba: **a citação**. Resposta
 * interna diz *"Art. 306, Lei 9.503"*; resposta externa não diz nada disso. A
 * ausência é o sinal.
 *
 * Isto é aceite do §6 — *"verificável por teste que confira que nenhuma fala de
 * fonte externa contém termo de rebaixamento"* — e sem este arquivo a regra
 * dependeria de ninguém, um dia, acrescentar `"provavelmente"` numa string. Regra
 * de produto que depende de disciplina é regra que não existe: este projeto já
 * contou quatro vezes de onze em que a disciplina falhou.
 */
class FalaDeFonteExternaTest {

    /**
     * **Todo desfecho que veio de fonte externa.** Se um novo aparecer e não entrar
     * aqui, `aVarreduraCobreTodoDesfechoExterno` reprova com o nome do que faltou.
     */
    private val falasExternas: List<Pair<ActionOutcome, Utterance>> = buildList {
        for (nome in NOMES_DE_OSM) {
            for (d in DISTANCIAS) {
                add(ActionOutcome.LugarEncontrado(LugarProximo(nome, d)))
            }
        }
        CategoriaDeLugar.entries.forEach { add(ActionOutcome.LugarNaoEncontrado(it)) }
    }.map { it to utteranceFor(it) }

    // ── A regra ───────────────────────────────────────────────────────────────

    @Test
    fun nenhumaFalaDeFonteExterna_contemTermoDeRebaixamento() {
        val sujas = falasExternas.mapNotNull { (outcome, u) ->
            val texto = textoDe(u)
            val achados = rebaixamentosEm(texto)
            if (achados.isEmpty()) null else "${outcome::class.simpleName} \"$texto\" → $achados"
        }
        assertEquals(
            "estas falas de fonte externa se rebaixam. A ausência de citação é o " +
                "sinal de origem; ressalva falada é sílaba gasta dentro de sete palavras.",
            emptyList<String>(),
            sujas,
        )
    }

    /**
     * **Controle positivo — sem ele o teste acima passa quando o detector quebra.**
     *
     * `CLAUDE.md` §6, pergunta 3: *se o teste passaria com o defeito de volta, ele
     * não testa o defeito*. O defeito de volta é a proposta original da spec, e ela
     * está escrita aqui letra por letra.
     */
    @Test
    fun oDetector_reprovaAPropostaOriginalDaSpec_senaoElaNaoMedeNada() {
        val comoEraProposto = listOf(
            "Segundo a internet, Hospital Getúlio Vargas.",
            "Provavelmente o Hospital Municipal.",
            "Encontrei na web: delegacia a 400 metros.",
            "Pela web, posto a 200 metros.",
            "Aproximadamente 800 metros, acho.",
        )
        for (frase in comoEraProposto) {
            assertTrue(
                "o detector NÃO viu rebaixamento em \"$frase\" — ele está quebrado, " +
                    "e o teste de cima é decorativo",
                rebaixamentosEm(frase).isNotEmpty(),
            )
        }
    }

    /**
     * **Sem earcon próprio, e isso também é a regra.**
     *
     * Um earcon distinto para resposta externa marcaria a origem no som — seria a
     * mesma ressalva, só que instrumental, e custaria o mesmo atraso na entrega da
     * informação. `Falar` puro, como a norma encontrada.
     */
    @Test
    fun aFalaExterna_naoTemEarconQueMarqueAOrigem() {
        for ((outcome, u) in falasExternas) {
            assertTrue(
                "${outcome::class.simpleName} saiu como ${u::class.simpleName}: um " +
                    "earcon aqui marca a origem no som, que é a ressalva de novo",
                u is Utterance.Falar,
            )
        }
    }

    /** O teto vale aqui como em todo lugar, inclusive com nome longo de OSM. */
    @Test
    fun aFalaExterna_cabeNoTetoDeSetePalavras() {
        val longas = falasExternas
            .map { textoDe(it.second) }
            .filterNot { LaconicityPolicy.isWithinLimit(it) }
        assertEquals(emptyList<String>(), longas)
    }

    /**
     * **A varredura cobre todo desfecho externo — senão ela varre o que alguém lembrou.**
     *
     * Mesmo mecanismo de `UtteranceTest.aListaCobreTodoSubtipoSelado`: reflexão do
     * Java sobre os tipos aninhados, sem `kotlin-reflect`. A lista de nomes é a
     * fronteira: um [ActionOutcome] novo cuja origem seja externa precisa ser
     * acrescentado aos dois lugares.
     */
    @Test
    fun aVarreduraCobreTodoDesfechoExterno() {
        val declarados = ActionOutcome::class.java.declaredClasses
            .filter { ActionOutcome::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()
        assertTrue(
            "a reflexão não achou subtipo nenhum — sem controle positivo esta " +
                "varredura também 'não acha faltante' quando está quebrada",
            declarados.size >= 10,
        )
        val esperados = declarados.filter { it.startsWith("Lugar") }.toSet()
        val cobertos = falasExternas.map { it.first::class.simpleName }.toSet()
        assertEquals(
            "estes desfechos de fonte externa não passam pela varredura de rebaixamento",
            emptySet<String>(),
            esperados - cobertos,
        )
    }

    // ── Detector ──────────────────────────────────────────────────────────────

    private fun textoDe(u: Utterance): String = when (u) {
        is Utterance.Falar -> u.texto
        is Utterance.SinalizarEFalar -> u.texto
        is Utterance.Sinalizar -> ""
    }

    private fun rebaixamentosEm(texto: String): List<String> {
        val normal = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return TERMOS_DE_REBAIXAMENTO.filter { it in normal }
    }

    private companion object {

        /**
         * Termos que rebaixam. Três famílias, e as três são rebaixamento:
         * **anunciar a origem** ("segundo a internet"), **duvidar da própria
         * resposta** ("provavelmente"), e **narrar o próprio trabalho**
         * ("encontrei", "pesquisei") — que gasta palavra sem acrescentar fato.
         *
         * Sem acento porque o detector normaliza: "segundo a internet" e a mesma
         * frase digitada sem acento são o mesmo termo, e uma lista com as duas
         * grafias erra por esquecimento na terceira.
         */
        val TERMOS_DE_REBAIXAMENTO = listOf(
            "internet", "web", "online", "google", "pesquis", "busquei", "encontrei",
            "provavelmente", "possivelmente", "aparentemente", "talvez", "acho",
            "parece", "creio", "aproximadamente", "cerca de", "mais ou menos",
            "nao tenho certeza", "segundo a", "de acordo com", "fonte externa",
            "consultei",
        )

        /**
         * Nomes como o OSM os traz — inclusive o vazio, que é dado e não defeito, e
         * o comprido, que é onde o teto de sete palavras é testado de verdade.
         */
        val NOMES_DE_OSM = listOf(
            "Hospital Getúlio Vargas",
            "Hospital Municipal Souza Aguiar",
            "Hospital Estadual Getúlio Vargas do Estado do Rio de Janeiro",
            "78ª DP",
            "UPA 24h Engenho Novo",
            "",
        )

        /** Cobre os três ramos de `FalaDePosicao.distanciaFalada`. */
        val DISTANCIAS = listOf(40, 480, 800, 1_450, 2_000, 2_950)
    }
}
