package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tabela sustenta o que ela promete?
 *
 * Uma tabela de soletração tem um jeito silencioso de estar errada: duas letras
 * cujas palavras **soam parecido**. Aí o casamento aproximado troca uma pela outra,
 * a placa sai com um caractere diferente, ainda passa na gramática — e o app
 * consulta o veículo errado com toda a confiança. Este arquivo é o que impede isso.
 */
class AlfabetoFoneticoTest {

    private val letras = ('A'..'Z').toList()

    @Test
    fun aTabelaCobreAsVinteESeisLetras() {
        val faltando = letras.filter { l -> AlfabetoFonetico.palavrasDe(l).isEmpty() }
        assertEquals("letras sem palavra: $faltando", emptyList<Char>(), faltando)
    }

    @Test
    fun aLetraSolteiraSoletraASiMesma() {
        // "placa RIO 2 A 18" — o agente diz a letra, não a palavra dela.
        letras.forEach { l ->
            assertEquals("'${l.lowercaseChar()}' devia soletrar $l", l, AlfabetoFonetico.letra(l.lowercase()))
        }
    }

    /**
     * **O contra-teste da tabela.**
     *
     * Toda palavra da tabela, passada pelo casamento por som, tem de voltar como a
     * **própria** letra. Se alguma voltar como outra, existe um par confundível e a
     * placa pode sair trocada — o teste nomeia o par em vez de deixar o defeito
     * aparecer em campo.
     */
    @Test
    fun nenhumaPalavraDaTabelaSoaComoOutraLetra() {
        val conflitos = mutableListOf<String>()
        letras.forEach { l ->
            AlfabetoFonetico.palavrasDe(l).forEach { p ->
                val aproximada = AlfabetoFonetico.letraAproximada(p)
                if (aproximada != null && aproximada != l) {
                    conflitos += "'$p' é $l mas soa como $aproximada"
                }
            }
        }
        assertEquals("pares confundíveis: $conflitos", emptyList<String>(), conflitos)
    }

    /**
     * **As variantes que a pesquisa confirmou — e a que ela não confirmou.**
     *
     * `unido` está na tabela porque é a palavra do exemplo de aceite da spec, e é a
     * única entrada sem fonte externa. `roma` **não** está: foi procurada e não foi
     * achada, e a regra da casa é que variante plausível sem prova não entra.
     *
     * Se alguém achar a fonte de `roma`, o conserto é uma linha — e este teste é o
     * lugar onde a ausência está escrita, em vez de ser um silêncio.
     */
    @Test
    fun asVariantesConfirmadasValem_eAsNaoConfirmadasNaoEntram() {
        // U: internacional (escrito), a forma falada da PMERJ, e a da spec.
        assertEquals('U', AlfabetoFonetico.letra("uniform"))
        assertEquals('U', AlfabetoFonetico.letra("uniforme"))
        assertEquals('U', AlfabetoFonetico.letra("unido"))

        // R: internacional e a forma oficial em português (DECEA MCA 100-16, CBMSC).
        assertEquals('R', AlfabetoFonetico.letra("romeo"))
        assertEquals('R', AlfabetoFonetico.letra("romeu"))

        // X: obrigatório por ato normativo na PM da Bahia.
        assertEquals('X', AlfabetoFonetico.letra("xadrez"))

        // "roma" é do alfabeto telefônico ITALIANO, não do rádio brasileiro.
        assertNull("'roma' não tem fonte brasileira", AlfabetoFonetico.letra("roma"))

        // O alfabeto "nacional" da Marinha é pré-1949 e não está em uso policial —
        // ver o KDoc de NACIONAL_REJEITADO. Estas duas NÃO podem voltar sem medida.
        assertNull("'urso' é do alfabeto naval pré-1949", AlfabetoFonetico.letra("urso"))
        assertNull("'rato' é do alfabeto naval pré-1949", AlfabetoFonetico.letra("rato"))
    }

    /**
     * **Palavra curta não casa por som**, e o par abaixo é o motivo.
     *
     * "dê" e "tê" (D e T) ficam a distância 1 na chave fonética. Se o corte por
     * comprimento sumir, "de" passa a poder virar T — e a asserção quebra.
     */
    @Test
    fun palavraCurtaSoCasaExata() {
        assertTrue("o par que motiva o corte", ChaveFonetica.distancia("de", "te") <= 1)
        assertNull("mas o casamento por som não pode alcançá-lo", AlfabetoFonetico.letraAproximada("te"))
        assertEquals("o casamento exato continua valendo", 'T', AlfabetoFonetico.letra("te"))
    }

    @Test
    fun palavraQueNaoESoletracaoNaoViraLetra() {
        listOf("carro", "viatura", "ocorrencia", "apoio", "central", "suspeito")
            .forEach {
                assertNull("'$it' não é letra", AlfabetoFonetico.letra(it))
                assertNull("'$it' não pode virar letra nem por som", AlfabetoFonetico.letraAproximada(it))
            }
    }
}
