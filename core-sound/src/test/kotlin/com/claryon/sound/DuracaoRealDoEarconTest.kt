package com.claryon.sound

import com.claryon.common.Earcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A janela de supressão fechava antes de o som acabar.**
 *
 * `RadioViewModel` mantinha uma **segunda tabela** de durações, escrita à mão:
 *
 * ```kotlin
 * private fun duracaoDoEarcon(earcon: Earcon): Long = when (earcon) {
 *     Earcon.GRAVANDO -> 2_000L
 *     else -> 320L
 * }
 * ```
 *
 * Ela alimentava a janela em que a captura ignora a própria saída. Já divergia de três
 * dos dez earcons **antes** de o vocabulário mudar em 22/08; depois da mudança, três
 * passaram de 320 ms — `DESPERTAR` 520, `CONSULTA_FURTO_ROUBO` 440,
 * `CONSULTA_RESTRICAO_ADMIN` 330.
 *
 * O sintoma é específico: a janela fecha, o som **ainda está tocando**, a cauda volta
 * pelo microfone *open-ear* a centímetros do alto-falante, e entra no pré-roll do PTT
 * seguinte. É exatamente o defeito que `SupressorDeSaidaPropria` existe para impedir —
 * reintroduzido por uma constante que ninguém atualizou.
 *
 * A causa não era o número errado. Era **haver dois lugares** onde a duração vivia, um
 * deles derivado do PCM e o outro escrito à mão. Duas fontes para o mesmo fato
 * divergem; a questão é só quando.
 */
class DuracaoRealDoEarconTest {

    /**
     * **Nenhum earcon dura menos do que a janela precisa cobrir.**
     *
     * Este é o teste que a tabela antiga reprovaria. Ele não confere números fixos —
     * confere a **relação**: a duração declarada tem de ser a do PCM que vai tocar.
     */
    @Test
    fun aDuracaoDeclarada_eADoPcmQueVaiTocar() {
        Earcon.entries.forEach { e ->
            val amostras = EarconSynthesizer.render(e).size
            val esperado = amostras * 1_000L / EarconSynthesizer.SAMPLE_RATE_HZ
            assertEquals(
                "A duração de $e não é a do PCM. Se alguém devolveu uma tabela à mão, " +
                    "a janela de supressão volta a fechar antes do som acabar e a " +
                    "cauda entra no pré-roll do PTT seguinte.",
                esperado,
                EarconSynthesizer.duracaoMs(e),
            )
        }
    }

    /**
     * **O contra-teste da tabela antiga, com os números que ela daria.**
     *
     * Se a tabela `GRAVANDO → 2000 · else → 320` voltasse, estes earcons ficariam com
     * a janela **curta**. O teste falha nomeando quais — e o valor está aqui para que
     * o próximo a mexer veja de quanto era o erro, não só que havia um.
     */
    @Test
    fun aTabelaAntiga_deixariaAJanelaCurta_emPeloMenosTres() {
        val tabelaAntiga: (Earcon) -> Long = { if (it == Earcon.GRAVANDO) 2_000L else 320L }

        val curtos = Earcon.entries.filter { EarconSynthesizer.duracaoMs(it) > tabelaAntiga(it) }

        assertTrue(
            "A tabela antiga deixou de subestimar earcon nenhum. Ou o vocabulário " +
                "encolheu, ou este teste virou arqueologia — releia antes de apagar. " +
                "Hoje: " + Earcon.entries.joinToString {
                    "$it=${EarconSynthesizer.duracaoMs(it)}ms"
                },
            curtos.size >= 3,
        )
    }

    /**
     * **`GRAVANDO` continua com 2 s exatos.**
     *
     * O único earcon cuja duração é regra de produto e não consequência do desenho: ele
     * marca a janela de gravação, e o número aparece em spec. Se o redesenho do
     * vocabulário o tivesse encurtado, a marca teria mudado de significado em silêncio.
     */
    @Test
    fun gravando_mantemOsDoisSegundosDeSpec() {
        assertEquals(2_000L, EarconSynthesizer.duracaoMs(Earcon.GRAVANDO))
    }
}
