package com.claryon.field.oculos

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.common.LaconicityPolicy
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.glasses.ErroDeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A causa da falha de câmera chega ao ouvido, e chega DIFERENTE.**
 *
 * Até 21/08 as oito causas de [ErroDeStream] viravam a mesma fala — *"Consulta
 * indisponível."* — porque a causa tipada morria num `Log.w` um passo antes do
 * alto-falante. O teste que faltava é este: não basta traduzir, é preciso que a
 * tradução **discrimine**.
 *
 * Um teste que só verificasse "existe fala" passaria com todas as oito mapeadas para
 * o mesmo valor. É o mesmo formato de defeito que este projeto já pegou em
 * `oPtt_transmiteQuadros_naTaxaConfigurada`, que asseria só `quadros.isNotEmpty()` —
 * satisfeito também pelo código defeituoso.
 */
class FalhaDaCameraTest {

    /**
     * **As quatro recuperações distintas produzem quatro falas distintas.**
     *
     * Este é o teste que o defeito reprovaria. Antes do conserto, as quatro
     * devolveriam `CONSULTA_INDISPONIVEL` e as comparações abaixo falhariam.
     *
     * As recuperações são fisicamente diferentes, e é por isso que colapsá-las é
     * defeito e não economia: quem dobrou os óculos abre as hastes em dois segundos;
     * quem está com a câmera negada precisa parar e mexer no celular; quem está com
     * os óculos quentes **piora a situação insistindo**; e quem está sem bateria tem
     * um problema de turno, não de consulta.
     */
    @Test
    fun recuperacoesDiferentes_produzemFalasDiferentes() {
        val falaDe = { erro: ErroDeStream ->
            when (val u = utteranceFor(ActionOutcome.Falhou(FalhaDaCamera.de(erro)))) {
                is Utterance.SinalizarEFalar -> u.texto
                is Utterance.Falar -> u.texto
                is Utterance.Sinalizar -> null
            }
        }

        val distintas = listOf(
            ErroDeStream.HINGE_CLOSED,
            ErroDeStream.PERMISSIONS_DENIED,
            ErroDeStream.THERMAL_HOT,
            ErroDeStream.BATTERY_LOW,
        ).associateWith(falaDe)

        distintas.forEach { (erro, fala) ->
            assertTrue("$erro não produziu fala nenhuma", !fala.isNullOrBlank())
        }

        val unicas = distintas.values.toSet()
        assertEquals(
            "Recuperações distintas colapsaram na mesma fala:\n" +
                distintas.entries.joinToString("\n") { "  ${it.key} → \"${it.value}\"" } +
                "\n\nÉ o defeito que este teste existe para pegar: fala igual para " +
                "gestos diferentes é a versão sonora do silêncio.",
            distintas.size,
            unicas.size,
        )

        // Nenhuma delas pode ser a fala genérica: se for, a causa se perdeu de novo.
        assertTrue(
            "Alguma causa específica virou a fala genérica: $distintas",
            FalhaOperacional.CONSULTA_INDISPONIVEL.causaCurta !in unicas,
        )
    }

    /**
     * **Gestos IGUAIS compartilham fala — e isso é intencional.**
     *
     * `BATTERY_LOW` e `PEAK_POWER_LIMIT` têm frases diferentes no SDK ("bateria
     * acabando" × "sem energia para transmitir"), e o mesmo gesto: pôr no estojo.
     * Vocabulário separado ali seria distinção sem diferença — o agente aprenderia
     * dois sons para uma ação.
     *
     * O teste existe para que o colapso seja **decisão registrada** e não descuido:
     * se alguém os separar, esta asserção obriga a reler a spec antes.
     */
    @Test
    fun energiaNoFim_temUmaFalaSo_porqueOGestoEUmSo() {
        assertEquals(
            "BATTERY_LOW e PEAK_POWER_LIMIT deixaram de compartilhar fala. O gesto é " +
                "o mesmo (pôr no estojo); separá-los é distinção sem diferença. Se a " +
                "separação for intencional, atualize specs/falha-de-camera-falada.spec.md.",
            FalhaDaCamera.de(ErroDeStream.BATTERY_LOW),
            FalhaDaCamera.de(ErroDeStream.PEAK_POWER_LIMIT),
        )
    }

    /**
     * **A tradução é TOTAL — nenhum valor do SDK cai em silêncio.**
     *
     * O `when` de `FalhaDaCamera.de` não tem `else`, então um valor novo em
     * `ErroDeStream` quebra a compilação. Este teste é a segunda camada: ele enumera
     * o enum de origem em runtime e exige fala para todos.
     *
     * Duas camadas porque elas falham em momentos diferentes — o compilador pega
     * quem edita `ErroDeStream`; este pega quem acrescenta valor por reflexão, por
     * geração, ou quem transforme o `when` num `else` "temporário".
     */
    @Test
    fun todoErroDoSdkTemFala() {
        val semFala = ErroDeStream.entries.filter {
            FalhaDaCamera.de(it).causaCurta.isBlank()
        }
        assertEquals("Erros do SDK sem fala: $semFala", emptyList<ErroDeStream>(), semFala)

        // E o caminho por código textual — usado quando só a string atravessou —
        // tem de concordar com o tipado. Duas rotas para a mesma resposta divergem
        // no primeiro ajuste se ninguém as prender juntas.
        ErroDeStream.entries.forEach { erro ->
            assertEquals(
                "A rota por código divergiu da tipada em $erro",
                FalhaDaCamera.de(erro),
                FalhaDaCamera.deCodigo(erro.codigo),
            )
        }
    }

    /**
     * **O teto de 7 palavras vale para as falas novas.**
     *
     * `UtteranceTest` já varre todos os ramos de `utteranceFor`, e `Falhou` entra lá
     * por `FalhaOperacional.entries`. Este teste é redundante de propósito: ele falha
     * **aqui**, ao lado da tradução, em vez de num arquivo de outro módulo — o que
     * encurta a distância entre a frase escrita e o teste que a reprova.
     */
    @Test
    fun asFalasNovasCabemNoTeto() {
        ErroDeStream.entries.forEach { erro ->
            val texto = FalhaDaCamera.de(erro).causaCurta
            assertTrue(
                "Fala de $erro estourou o teto: \"$texto\" (${texto.split(" ").size} palavras)",
                LaconicityPolicy.isWithinLimit(texto),
            )
        }
    }

    /**
     * **Código que não é de stream não inventa recuperação.**
     *
     * `glasses.no_session` e `glasses.camera_threw` nascem do nosso lado e não têm
     * gesto próprio. Mapeá-los para "abra as hastes" seria pior que a fala genérica:
     * mandaria o agente fazer algo que não resolve.
     */
    @Test
    fun codigoDesconhecido_naoViraConselhoErrado() {
        listOf("glasses.no_session", "glasses.camera_threw", "lixo").forEach { codigo ->
            assertEquals(
                "O código \"$codigo\" recebeu recuperação específica que ele não tem",
                FalhaOperacional.CAMERA_INDISPONIVEL,
                FalhaDaCamera.deCodigo(codigo),
            )
        }
        assertNotEquals(
            "O código de hastes fechadas devia ser reconhecido — se não for, a régua " +
                "de `deCodigo` quebrou e TODA causa vira genérica em silêncio.",
            FalhaOperacional.CAMERA_INDISPONIVEL,
            FalhaDaCamera.deCodigo(ErroDeStream.HINGE_CLOSED.codigo),
        )
    }
}
