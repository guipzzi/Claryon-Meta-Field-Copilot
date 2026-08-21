package com.claryon.field.ui.marca

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O que este teste protege: a marca é uma forma só, escrita duas vezes.**
 *
 * [GeometriaDaMarca] alimenta o Compose (abertura e telas); o
 * `res/drawable/marca_claryon.xml` alimenta o ícone do launcher, que não aceita
 * `DrawScope`. Duas escritas da mesma forma divergem em silêncio — ninguém abre a
 * gaveta de aplicativos para conferir proporção de arco, e o dia em que a marca
 * da abertura e a do ícone forem formas diferentes ninguém vai saber dizer qual
 * das duas é a certa.
 *
 * Os testes abaixo não conferem "desenha algo". Cada um deles falha se a decisão
 * de desenho que ele descreve for desfeita — que é o único jeito de um teste de
 * geometria valer alguma coisa.
 */
class MarcaTest {

    /** O raio externo com que o `VectorDrawable` foi escrito, no viewport 108. */
    private val raio = 29f

    private val vetor: String by lazy {
        val candidatos = listOf(
            File("src/main/res/drawable/marca_claryon.xml"),
            File("app/src/main/res/drawable/marca_claryon.xml"),
        )
        candidatos.firstOrNull { it.isFile }?.readText()
            ?: error("marca_claryon.xml não encontrado a partir de ${File("").absolutePath}")
    }

    private val pathDatas: List<String>
        get() = Regex("""android:pathData="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(vetor).map { it.groupValues[1] }.toList()

    private val strokeWidths: List<Float>
        get() = Regex("""android:strokeWidth="([0-9.]+)"""")
            .findAll(vetor).map { it.groupValues[1].toFloat() }.toList()

    /** Todos os raios de arco (`A rx,ry ...`) de um `pathData`, na ordem. */
    private fun raiosDe(pathData: String): List<Float> =
        Regex("""A([0-9.]+),""").findAll(pathData).map { it.groupValues[1].toFloat() }.toList()

    @Test
    fun `o vetor do icone repete exatamente as fracoes da geometria`() {
        assertEquals("4 caminhos: disco + 3 ondas", 4, pathDatas.size)

        // O círculo é escrito como dois semiarcos de mesmo raio.
        raiosDe(pathDatas[0]).forEach {
            assertEquals(GeometriaDaMarca.PUPILA * raio, it, TOLERANCIA)
        }

        GeometriaDaMarca.ONDAS.forEachIndexed { i, onda ->
            assertEquals(
                "eixo da onda ${i + 1}",
                onda.eixo * raio,
                raiosDe(pathDatas[i + 1]).single(),
                TOLERANCIA,
            )
            assertEquals(
                "traço da onda ${i + 1}",
                onda.traco * raio,
                strokeWidths[i],
                TOLERANCIA,
            )
        }
    }

    /**
     * **O anel preto entre a pupila e a íris existe, e tem largura de vão.**
     *
     * É ele que produz a leitura de pupila dentro de íris. Encostadas, pupila e
     * primeira onda viram uma gota única; e um anel mais estreito que os outros
     * vãos faria a primeira onda parecer contorno do disco em vez de onda.
     *
     * Contra-teste do que estava em risco: a primeira versão desta marca tinha a
     * onda começando em 0,290 com a pupila em 0,280 — vão de 0,010, que no
     * aparelho sumiu. Este teste cai com aquele número.
     */
    @Test
    fun `ha um anel preto de largura de vao entre a pupila e a primeira onda`() {
        val anel = GeometriaDaMarca.ONDAS.first().interno - GeometriaDaMarca.PUPILA
        val outrosVaos = GeometriaDaMarca.ONDAS.zipWithNext { a, b -> b.interno - a.externo }
        assertTrue("anel = $anel — pupila e íris encostam", anel > 0f)
        outrosVaos.forEach {
            assertEquals("o anel precisa ter a mesma largura dos outros vãos", it, anel, 0.001f)
        }
    }

    /**
     * **Traço mais grosso que o vão, e a diferença é pequena.**
     *
     * As duas metades importam. Traço fino demais some no ícone de 48 dp (o
     * antialiasing come metade dele); traço muito mais grosso que o vão fecha o
     * desenho e as três ondas viram uma mancha. A faixa aceita aqui é o que
     * sustenta as duas afirmações do KDoc.
     */
    @Test
    fun `o traco e mais grosso que o vao, sem fechar o desenho`() {
        val vaos = GeometriaDaMarca.ONDAS.zipWithNext { a, b -> b.interno - a.externo }
        val tracos = GeometriaDaMarca.ONDAS.map { it.traco }

        (vaos + listOf(GeometriaDaMarca.ONDAS.first().interno - GeometriaDaMarca.PUPILA))
            .forEach { assertTrue("vão $it precisa ser positivo", it > 0f) }

        vaos.forEachIndexed { i, vao ->
            val razao = tracos[i] / vao
            assertTrue("traço/vão = $razao — fino demais para o ícone", razao > 1.05f)
            assertTrue("traço/vão = $razao — grosso demais, fecha o desenho", razao < 1.45f)
        }
    }

    /**
     * **A marca cabe na zona segura do ícone adaptativo.**
     *
     * O launcher recorta os 108 dp com uma máscara arbitrária — redonda, quadrada,
     * squircle — e só os 72 dp centrais são garantidos. A caixa da marca precisa
     * caber num círculo de 72, ou a ponta da onda mais externa é cortada em
     * aparelho de fabricante que use máscara redonda. Isto não se vê no emulador
     * padrão, e é exatamente por isso que é teste.
     */
    @Test
    fun `a caixa da marca cabe no circulo seguro de 72 do icone adaptativo`() {
        val largura = GeometriaDaMarca.LARGURA * raio
        val altura = GeometriaDaMarca.ALTURA * raio
        val diagonal = hypot(largura, altura)
        assertTrue("diagonal da caixa = $diagonal, teto 72", diagonal <= 72f)

        // E está centrada: o centro geométrico do desenho, não o centro do círculo
        // que o gera. Sobra menos marca acima do eixo (a calota) que abaixo (o
        // raio inteiro), então o `cy` do vetor é deslocado para cima.
        val cy = Regex("""M[0-9.]+,([0-9.]+)""").find(pathDatas[0])!!.groupValues[1].toFloat()
        val topo = cy - GeometriaDaMarca.PUPILA * raio
        val base = cy + raio
        assertEquals("a caixa precisa ficar centrada em 54", 54f, (topo + base) / 2f, 0.1f)
    }

    private companion object {
        /** Meio centésimo de unidade de viewport: bem abaixo de um pixel em 48 dp. */
        const val TOLERANCIA = 0.005f
    }
}
