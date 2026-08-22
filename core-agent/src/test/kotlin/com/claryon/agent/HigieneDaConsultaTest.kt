package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O filtro do §5, medido nas duas camadas que ele tem.**
 *
 * `specs/consulta-externa.spec.md` §5: *a pergunta que sai NÃO é a transcrição*.
 * A promessa é sustentada por duas coisas diferentes, e testar só uma delas
 * deixaria metade da garantia sem prova:
 *
 *  1. **Construção** — [ConsultaHigienizada.de] só aceita [CategoriaDeLugar]. É a
 *     camada que de fato garante: sem sobrecarga que aceite `String`, nada da fala
 *     tem por onde entrar. Provada aqui pela ausência de caminho (e por reflexão,
 *     porque "não existe sobrecarga" não é uma asserção que o compilador escreva).
 *  2. **Inspeção** — [HigieneDaConsulta.vazamentos] examina um texto qualquer e
 *     **nomeia** o que achou. É a camada que de fato *prova*, porque é o
 *     instrumento com que o teste reprova a versão defeituosa — a que mandaria a
 *     transcrição.
 *
 * O aceite do §6 pede *"teste que inspecione a consulta emitida"*. Este arquivo
 * cobre a inspeção do TEXTO; `ConsultaGeoespacialTest` cobre a inspeção do que
 * chega ao servidor, num socket de verdade. As duas são necessárias: um texto
 * limpo pode virar um corpo sujo, e um corpo pode ir limpo enquanto o texto
 * registrado no aparelho vem sujo.
 */
class HigieneDaConsultaTest {

    // ── A camada 1: não há por onde a fala entrar ─────────────────────────────

    /**
     * **Não existe `ConsultaHigienizada.de(String)`.**
     *
     * É a mesma garantia que `utteranceFor` dá ao recusar `Intent`: enquanto a
     * única entrada for o enum, é *impossível* — não improvável — que um pedaço da
     * fala chegue à rede. Impossibilidade que depende de ninguém acrescentar uma
     * sobrecarga não é impossibilidade; por isso a reflexão.
     */
    @Test
    fun aFabricaSoAceitaCategoria_naoExisteSobrecargaDeTexto() {
        val fabricas = ConsultaHigienizada.Companion::class.java.declaredMethods
            .filter { it.name == "de" }
        assertTrue("nenhuma fábrica `de` encontrada — a reflexão está olhando o lugar errado", fabricas.isNotEmpty())

        val comTexto = fabricas.filter { m ->
            m.parameterTypes.any { it == String::class.java || it == CharSequence::class.java }
        }
        assertEquals(
            "apareceu uma fábrica de ConsultaHigienizada que aceita texto. É por " +
                "essa porta que a transcrição sai do aparelho — a spec §2 proíbe a " +
                "transcrição LITERAL sem exceção, e 'limpa' continua sendo transcrição.",
            emptyList<String>(),
            comTexto.map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" },
        )
    }

    /**
     * O termo de toda categoria passa pelo próprio filtro.
     *
     * A autoconferência já existe dentro de [ConsultaHigienizada.de] como `check`;
     * este teste a exercita para as três, de forma que uma categoria nova com termo
     * sujo reprove na suíte e não só em runtime, no aparelho de um policial.
     */
    @Test
    fun todaCategoria_temTermoQuePassaNoFiltro() {
        for (c in CategoriaDeLugar.entries) {
            val consulta = ConsultaHigienizada.de(c)
            assertEquals(c.termo, consulta.texto)
            assertEquals(
                "a categoria ${c.name} tem termo \"${c.termo}\", que vaza",
                emptySet<HigieneDaConsulta.Vazamento>(),
                HigieneDaConsulta.vazamentos(consulta.texto),
            )
        }
    }

    // ── A camada 2: o detector nomeia o que achou ─────────────────────────────

    /**
     * **Os quatro vazamentos que a spec §5 enumera, um por um.**
     *
     * A asserção compara o CONJUNTO, e não "achou alguma coisa": uma mensagem de
     * falha que diz "vazou placa" quando também vazou matrícula manda quem for
     * consertar fazer meio conserto e declarar vitória.
     */
    @Test
    fun oDetectorNomeiaCadaVazamentoDaSpec() {
        val casos = mapOf(
            // Placa escrita e placa DITADA — o roteador aceita as duas, o filtro
            // também. Um filtro que só olhasse a grafia deixaria a ditada inteira.
            "consulta a placa ABC1D23" to HigieneDaConsulta.Vazamento.PLACA,
            "tango bravo unido três delta sete zero" to HigieneDaConsulta.Vazamento.PLACA,
            "matricula do agente" to HigieneDaConsulta.Vazamento.MATRICULA,
            "onde esta o sgt paiva" to HigieneDaConsulta.Vazamento.NOME_PROPRIO,
            "posicao da guarnicao alfa dois" to HigieneDaConsulta.Vazamento.INDICATIVO_DE_GUARNICAO,
            "estou na Rui Barbosa" to HigieneDaConsulta.Vazamento.NOME_PROPRIO,
            "hospital 250" to HigieneDaConsulta.Vazamento.NUMERO_DE_ENDERECO,
        )
        for ((texto, esperado) in casos) {
            val achados = HigieneDaConsulta.vazamentos(texto)
            assertTrue(
                "\"$texto\" devia acusar $esperado e acusou $achados",
                esperado in achados,
            )
        }
    }

    /**
     * **A frase inteira da spec §5 é reprovada — e a categoria dela não.**
     *
     * *"Claryon, estou na Rui Barbosa em Niterói, qual o hospital mais próximo"* é
     * o exemplo literal do documento. O que sai é a categoria `hospital`; a frase
     * não sai. Este teste é o contra-teste embutido: se alguém trocar a
     * reconstrução por "mandar a transcrição limpa", a primeira asserção reprova.
     */
    @Test
    fun aFraseDaSpec_reprova_eACategoriaPassa() {
        val transcricao = "Claryon, estou na Rui Barbosa em Niterói, " +
            "qual o hospital mais próximo"

        assertTrue(
            "a transcrição da spec passou no filtro — então mandar a transcrição " +
                "'limpa' pareceria seguro, e ela continua sendo transcrição",
            HigieneDaConsulta.vazamentos(transcricao).isNotEmpty(),
        )
        assertTrue(
            "a categoria reconstruída não passou no filtro",
            HigieneDaConsulta.limpa(ConsultaHigienizada.de(CategoriaDeLugar.HOSPITAL).texto),
        )
    }

    /**
     * **Nenhum dígito sai, e isso é a regra, não exagero.**
     *
     * §5: *"a categoria pode sair, o número não"*. Distinguir número de endereço de
     * número de matrícula seria trabalho para separar dois casos com o mesmo
     * veredito, e cada distinção a mais é uma borda a mais por onde algo passa.
     */
    @Test
    fun qualquerDigitoReprova() {
        for (d in '0'..'9') {
            assertTrue(
                "o dígito $d passou pelo filtro",
                HigieneDaConsulta.Vazamento.NUMERO_DE_ENDERECO in
                    HigieneDaConsulta.vazamentos("hospital $d"),
            )
        }
    }

    /**
     * **Não existe `higienizar(texto): String`.**
     *
     * A ausência é deliberada e está escrita no KDoc de [HigieneDaConsulta]: uma
     * função que "limpa" a transcrição convida a mandar a transcrição limpa. Aqui
     * só há reconstruir ou recusar — e o teste guarda a porta que o KDoc promete.
     */
    @Test
    fun naoExisteFuncaoQueLimpeTranscricao() {
        val limpadoras = HigieneDaConsulta::class.java.declaredMethods.filter { m ->
            m.returnType == String::class.java &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                java.lang.reflect.Modifier.isPublic(m.modifiers)
        }
        assertEquals(
            "apareceu uma função pública que recebe texto e devolve texto em " +
                "HigieneDaConsulta. Texto limpo continua sendo transcrição.",
            emptyList<String>(),
            limpadoras.map { it.name },
        )
    }
}
