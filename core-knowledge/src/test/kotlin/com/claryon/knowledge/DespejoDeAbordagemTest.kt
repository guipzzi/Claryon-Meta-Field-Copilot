package com.claryon.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Despeja, em forma que o aparelho consegue ler, o que a Etapa A entrega à
 * Etapa B para cada uma das 100 perguntas de abordagem.**
 *
 * Existe porque `app` **não tem `core-knowledge` no classpath** — e isso é o
 * desenho, não um esquecimento: nenhum módulo depende de `core-knowledge` hoje.
 * Para medir a Etapa B sobre o que a Etapa A de verdade recupera, sem
 * acrescentar dependência de projeto só para medir, o caminho é o mesmo que o
 * GGUF já usa: gerar aqui, `adb push` para `/data/local/tmp`, ler lá.
 *
 * O formato é TSV de quatro colunas, uma linha por pergunta, prefixado por
 * `TRIPLA\t` para poder ser extraído do log do Gradle com `grep`:
 *
 * ```
 * TRIPLA <TAB> pergunta <TAB> citacao <TAB> norma <TAB> confianca <TAB> texto
 * ```
 *
 * **O 1º colocado entra mesmo abaixo do limiar, e isso é declarado.** Em
 * produção a Etapa B só é chamada quando [PortaDoConhecimento] aceita — o que
 * neste banco acontece em 25% das perguntas. Medir só essas daria 15 casos, que
 * não decide nada. A coluna `confianca` diz, por linha, quais chegariam à Etapa B
 * hoje; o relatório separa os dois conjuntos em vez de misturá-los.
 */
class DespejoDeAbordagemTest {

    @Test
    fun despejaAsTriplasParaOAparelho() {
        val indice = IndiceLexical(CorpusDeNormas.embarcado())
        var comPorta = 0
        for (pergunta in PERGUNTAS_DE_ABORDAGEM) {
            val c = indice.buscar(pergunta, quantos = 1).firstOrNull()
            if (c == null) {
                println("TRIPLA\t$pergunta\t-\t-\t0.000\t")
                continue
            }
            if (c.similaridade >= BaseDeConhecimentoLexical.LIMIAR_MEDIDO) comPorta++
            val texto = c.trecho.texto.replace('\t', ' ').replace('\n', ' ')
            println(
                "TRIPLA\t$pergunta\t${c.trecho.citacao}\t${c.trecho.norma}\t" +
                    "%.3f\t%s".format(c.similaridade, texto),
            )
        }
        println("DESPEJO: ${PERGUNTAS_DE_ABORDAGEM.size} triplas · $comPorta passariam a porta em produção")
        assertEquals(100, PERGUNTAS_DE_ABORDAGEM.size)
    }
}
