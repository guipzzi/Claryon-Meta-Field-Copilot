package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.LexicoDeOcorrencias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O gazetteer chega ao léxico, ou só ao log?**
 *
 * `configurarGazetteer` passou a Fase 1 inteira com chamador só em teste, e o
 * conserto de 20/08 foi ligá-lo no `ClaryonApp`. O log diz `gazetteer: 2
 * logradouros` — mas log é sobre o carregamento, não sobre o USO. Entre carregar e
 * o léxico reconhecer "Rui Barbosa" como logradouro há a normalização, o mapa e a
 * classificação, e nada disso o log toca.
 *
 * Este teste pergunta a coisa certa: **depois do boot do app, o léxico enxerga?**
 * O alvo é o `targetContext`, então o asset lido é o que está no APK de produção.
 */
@RunWith(AndroidJUnit4::class)
class GazetteerEmProducaoTest {

    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    /** Repete o que `ClaryonApp.carregarGazetteer` faz, sobre o asset de produção. */
    private fun carregarComoOApp(): List<String> =
        assets.open("gazetteer/logradouros.txt").bufferedReader().useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }

    @Test
    fun oAssetExisteNoApkDeProducaoEProduzLogradouros() {
        val linhas = carregarComoOApp()
        assertTrue(
            "o asset `gazetteer/logradouros.txt` não entrou no APK, ou só tem " +
                "comentário — o léxico rodaria com o mapa vazio, que é o defeito " +
                "que este item conserta",
            linhas.isNotEmpty(),
        )
        // Grafia CANÔNICA, com acento e maiúscula: é este texto que vai para o
        // despachante. Uma lista em minúscula produziria endereço torto no alerta.
        assertTrue(
            "logradouro sem maiúscula inicial: ${linhas.filterNot { it.first().isUpperCase() }}",
            linhas.all { it.first().isUpperCase() },
        )
    }

    /**
     * **A pergunta que o log não responde.** Carregar não é reconhecer.
     */
    @Test
    fun depoisDeConfigurar_oLexicoREconheceOLogradouro() {
        LexicoDeOcorrencias.configurarGazetteer(carregarComoOApp())

        val comGazetteer = LexicoDeOcorrencias.classificar(
            "roubo de veículo na Rui Barbosa, suspeito armado",
        )
        assertNotNull("o léxico não classificou uma ocorrência com logradouro conhecido", comGazetteer)
        assertEquals(
            "o logradouro não foi extraído na grafia canônica — é este texto que " +
                "vai para o despachante",
            "Rui Barbosa",
            comGazetteer!!.logradouro,
        )
    }

    /**
     * **O contra-teste, e é ele que prova que o gazetteer faz alguma coisa.**
     *
     * Com o mapa VAZIO — o estado em que o app rodou até 20/08 — a mesma fala não
     * pode produzir logradouro. Se produzisse, o gazetteer seria decorativo e o
     * item teria sido consertado no lugar errado.
     */
    @Test
    fun comOGazetteerVAZIO_oLogradouroNaoEReconhecido() {
        LexicoDeOcorrencias.configurarGazetteer(emptyList())
        val semGazetteer = LexicoDeOcorrencias.classificar(
            "roubo de veículo na Rui Barbosa, suspeito armado",
        )

        LexicoDeOcorrencias.configurarGazetteer(carregarComoOApp())
        val comGazetteer = LexicoDeOcorrencias.classificar(
            "roubo de veículo na Rui Barbosa, suspeito armado",
        )

        assertTrue(
            "com o mapa vazio o léxico devolveu o logradouro assim mesmo: o " +
                "gazetteer não está no caminho e o item foi consertado no lugar errado",
            semGazetteer?.logradouro != comGazetteer?.logradouro,
        )
    }
}
