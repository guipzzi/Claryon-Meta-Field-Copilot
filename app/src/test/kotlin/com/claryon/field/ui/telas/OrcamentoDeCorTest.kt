package com.claryon.field.ui.telas

import androidx.compose.ui.graphics.Color
import com.claryon.field.ui.componentes.fundoDoBotao
import com.claryon.field.ui.componentes.rotuloDoBotao
import com.claryon.field.ui.tema.Cores
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O orçamento de cor e os contrastes, como afirmação que quebra o build.**
 *
 * Os números desta sessão estavam em KDoc, e KDoc não impede ninguém de baixar um
 * token de volta. Cada teste aqui foi escrito para **falhar com o defeito de
 * volta** — é o contra-teste que `CLAUDE.md` §6 pede, e não uma cerca genérica em
 * 4,5:1 que o código defeituoso também satisfazia.
 *
 * A fórmula do contraste é a do WCAG 2.1, escrita aqui em vez de importada: o
 * ponto do teste é que o número seja **derivado**, e um número que vem de uma
 * biblioteca é um número que ninguém confere.
 */
class OrcamentoDeCorTest {

    // ── Botão primário ────────────────────────────────────────────────────────

    /**
     * O rótulo do botão desabilitado, **medido no par que o botão desenha**.
     *
     * A primeira versão deste teste media os tokens (`TintaMedia` sobre `Elevado`)
     * em vez da escolha do componente, e por isso continuava verde depois de eu
     * devolver `TintaFraca` ao `BotaoTatico` — conferido rodando a regressão de
     * propósito. Foi o que obrigou a extrair [rotuloDoBotao] e [fundoDoBotao].
     *
     * A cerca é 7,0 e não 4,5 **de propósito**: `TintaFraca` sobre `Elevado` rende
     * 4,70:1 e passaria numa cerca de AA — era exatamente o estado que a captura do
     * emulador reprovou. A segunda asserção fixa isso por escrito.
     */
    @Test
    fun oBotaoDesabilitado_temRotuloAcimaDeSeteParaUm() {
        val c = contraste(
            rotuloDoBotao(habilitado = false, destrutivo = false),
            fundoDoBotao(habilitado = false, destrutivo = false),
        )
        assertTrue("rótulo sobre fundo do desabilitado = $c", c >= 7.0)
        assertTrue(
            "o token anterior (TintaFraca) tem de reprovar nesta mesma barra",
            contraste(Cores.TintaFraca, Cores.Elevado) < 7.0,
        )
    }

    /**
     * O destrutivo: fundo transparente, então o fundo **efetivo** é o da tela.
     *
     * A asserção que importa é a segunda — ela afirma que o botão escolheu o token
     * de palavra e não o de marca, que é a distinção escrita na paleta.
     */
    @Test
    fun oBotaoDestrutivo_usaOTokenDePalavra() {
        val rotulo = rotuloDoBotao(habilitado = true, destrutivo = true)
        assertEquals(Color.Transparent, fundoDoBotao(habilitado = true, destrutivo = true))
        val c = contraste(rotulo, Cores.Vazio)
        assertTrue("rótulo destrutivo sobre Vazio = $c", c >= 4.5)
        assertTrue(
            "$c tem de superar a marca (${contraste(Cores.Falha, Cores.Vazio)})",
            c > contraste(Cores.Falha, Cores.Vazio),
        )
    }

    /**
     * A **caixa** do botão desabilitado, que era o número que ninguém tinha olhado.
     *
     * `Elevado` sobre `Vazio` é 1,17:1 — abaixo até do fio de 1 px. O teste fixa o
     * fato: o preenchimento sozinho não desenha limite nenhum, e é por isso que o
     * contorno existe. Se um dia `Elevado` clarear a ponto de passar de 3:1, este
     * teste cai e alguém tem de decidir se o contorno ainda faz sentido.
     */
    @Test
    fun oPreenchimentoDoDesabilitado_naoDesenhaLimite() {
        assertTrue(
            "Elevado sobre Vazio = ${contraste(Cores.Elevado, Cores.Vazio)}",
            contraste(Cores.Elevado, Cores.Vazio) < 3.0,
        )
    }

    /** O habilitado é o par de maior contraste da paleta: 17,01:1. */
    @Test
    fun oBotaoHabilitado_eOParDeMaiorContrasteDaPaleta() {
        val primario = contraste(
            rotuloDoBotao(habilitado = true, destrutivo = false),
            fundoDoBotao(habilitado = true, destrutivo = false),
        )
        assertTrue("rótulo sobre fundo do habilitado = $primario", primario >= 15.0)
    }

    /** O rótulo da pílula, sobre o fundo da aplicação. */
    @Test
    fun aPilulaDeAcao_temRotuloAcimaDeSeteParaUm() {
        assertTrue(
            "TintaMedia sobre Vazio = ${contraste(Cores.TintaMedia, Cores.Vazio)}",
            contraste(Cores.TintaMedia, Cores.Vazio) >= 7.0,
        )
    }

    // ── O orçamento de cor da tela de permissões ──────────────────────────────

    /**
     * **O contra-teste do defeito de 21/08.**
     *
     * A tela abria com cinco frases vermelhas e nenhuma permissão negada. A regra
     * da paleta é que cor marca o excepcional **e** verdadeiro; "ainda não pedimos"
     * não é nem uma coisa nem outra.
     *
     * O teste varre os quatro estados possíveis de uma linha e exige croma zero em
     * todos, menos em [EstadoDaLinha.NEGADA]. Ele reprova com o defeito de volta —
     * bastaria repintar `PENDENTE` de `FalhaTexto` para quebrar —, e reprova também
     * no caminho oposto, se alguém tirar a cor da recusa de verdade.
     */
    @Test
    fun soALinhaNegada_gastaCor() {
        for (estado in EstadoDaLinha.entries) {
            val croma = croma(estado.cor)
            if (estado == EstadoDaLinha.NEGADA) {
                assertTrue("NEGADA tem de ter cor; croma = $croma", croma > 0f)
            } else {
                assertEquals("$estado não pode gastar cor", 0f, croma, 0f)
            }
        }
    }

    /**
     * Toda linha em estado não excepcional continua legível sem depender de cor.
     * Cor removida não pode custar leitura — é a razão de `TintaFraca` ter sido
     * corrigida na paleta e não pode regredir por aqui.
     */
    @Test
    fun osEstadosSemCor_continuamAcimaDeAA() {
        for (estado in EstadoDaLinha.entries) {
            if (estado == EstadoDaLinha.NEGADA) continue
            val c = contraste(estado.cor, Cores.Vazio)
            assertTrue("${estado.rotulo} sobre Vazio = $c", c >= 4.5)
        }
    }

    // ── Fórmulas ──────────────────────────────────────────────────────────────

    /** Croma como a paleta o define: `max(R,G,B) − min(R,G,B)`. */
    private fun croma(cor: Color): Float =
        max(max(cor.red, cor.green), cor.blue) - min(min(cor.red, cor.green), cor.blue)

    /** Razão de contraste do WCAG 2.1, `(L1 + 0,05) / (L2 + 0,05)`. */
    private fun contraste(a: Color, b: Color): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Luminância relativa do WCAG 2.1, sobre os canais linearizados. */
    private fun luminancia(cor: Color): Double =
        0.2126 * linear(cor.red) + 0.7152 * linear(cor.green) + 0.0722 * linear(cor.blue)

    private fun linear(canal: Float): Double {
        val c = canal.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
