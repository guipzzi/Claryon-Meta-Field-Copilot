package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A migração `0021` fez uma afirmação por escrito. Este teste a cobra.**
 *
 * O cabeçalho dela diz: *"arredondar duas vezes com o mesmo passo é idempotente. O
 * cliente continua chamando `distanciaFalada` sobre um valor já arredondado e o
 * resultado não muda"*. Era uma afirmação plausível, e plausível é exatamente o
 * estado em que os defeitos deste projeto costumam viver — a própria `0021` foi
 * escrita avisando que servidor e cliente não podem divergir, e divergia, por
 * arredondamento bancário.
 *
 * O que quebraria sem idempotência: o servidor entrega 150 m, o cliente arredonda
 * de novo para 200, e a distância **cresce a cada camada**. O agente ouviria um
 * número que nenhuma das duas pontas calculou.
 *
 * A régua do servidor está replicada aqui em Kotlin, com as mesmas faixas
 * (10/50/100 m) e a mesma política de metade-para-cima. Se alguém mexer numa das
 * duas, este teste cai — que é o ponto de ele existir.
 */
class ArredondamentoIdempotenteTest {

    /** Espelho de `private.distancia_arredondada` da migração `0021`. */
    private fun comoOServidor(m: Int): Int = when {
        m < 100 -> ((m + 5) / 10) * 10
        m < 1_000 -> ((m + 25) / 50) * 50
        else -> ((m + 50) / 100) * 100
    }

    /**
     * Extrai o número que a fala carrega, para comparar grandezas e não strings.
     * "a 150 metros" → 150; "a 1,2 quilômetros" → 1200.
     */
    private fun metrosNaFala(frase: String): Int {
        val num = Regex("""[\d.,]+""").find(frase)!!.value.replace('.', ',')
        val valor = num.replace(",", ".").toDouble()
        return if (frase.contains("quilômetro")) (valor * 1000).toInt() else valor.toInt()
    }

    @Test
    fun aFalaSobreUmValorJaArredondado_naoMudaOValor() {
        val divergentes = mutableListOf<String>()
        for (m in 0..5_000) {
            val doServidor = comoOServidor(m)
            val falado = metrosNaFala(FalaDePosicao.distanciaFalada(doServidor))
            if (falado != doServidor) divergentes += "$m → servidor $doServidor → fala $falado"
        }
        assertTrue(
            "o cliente arredondou POR CIMA do servidor em ${divergentes.size} valores — " +
                "a distância cresce a cada camada e o agente ouve um número que " +
                "nenhuma das pontas calculou:\n" + divergentes.take(8).joinToString("\n"),
            divergentes.isEmpty(),
        )
    }

    /**
     * **O contra-teste.** Se `distanciaFalada` fosse identidade — ou se a régua do
     * servidor aqui fosse cópia da do cliente por acidente —, o teste acima
     * passaria sem provar nada. Sobre um valor NÃO arredondado o cliente tem de
     * mudar o número na maioria das vezes.
     */
    @Test
    fun sobreValorCRU_oClienteMuda_provandoQueEleArredondaMesmo() {
        val mudou = (0..5_000).count { m ->
            metrosNaFala(FalaDePosicao.distanciaFalada(m)) != m
        }
        assertTrue(
            "o cliente não arredonda nada: mudou só $mudou de 5001 valores. " +
                "Então o teste de idempotência acima não estava provando coisa alguma",
            mudou > 4_000,
        )
    }

    /**
     * A fronteira em que a faixa do servidor e a do cliente discordam de banda:
     * o servidor arredonda 95 para 100 usando passo 10 (faixa fina), e 100 já cai
     * na faixa de 50 do cliente. Tem de continuar 100.
     */
    @Test
    fun asFronteirasDeFaixa_naoEscalam() {
        for (m in listOf(94, 95, 96, 99, 100, 101, 994, 995, 999, 1_000, 1_001)) {
            val doServidor = comoOServidor(m)
            assertEquals(
                "fronteira $m: servidor deu $doServidor e a fala mudou",
                doServidor,
                metrosNaFala(FalaDePosicao.distanciaFalada(doServidor)),
            )
        }
    }

    /**
     * Metade para cima nas duas pontas. `round()` de `double precision` no Postgres
     * é BANCÁRIO — foi o defeito real da primeira versão da `0021`, e 95 virava 90
     * no servidor e 100 no cliente.
     */
    @Test
    fun metadeVaiParaCima_naoParaOPar() {
        assertEquals(100, comoOServidor(95))
        assertEquals(150, comoOServidor(125))
        assertEquals(1_300, comoOServidor(1_250))
    }
}
