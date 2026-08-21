package com.claryon.field.ui.marca

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.tema.Cores

/**
 * **A marca do Claryon Field, em vetor — e o porquê de cada proporção.**
 *
 * A marca chegou como imagem e é reconstruída aqui como geometria, não como
 * bitmap. Não é preferência: como bitmap ela não escala para o ícone adaptativo
 * (108 dp) nem para a abertura em tela cheia sem serrilhar, **e não anima** — e a
 * abertura deste produto depende de animar exatamente as ondas desta marca. Um
 * PNG obrigaria a três arquivos por densidade e a uma segunda fonte da verdade
 * para a mesma forma. Aqui há uma só: [GeometriaDaMarca], em frações do raio
 * externo, de onde saem o Compose ([desenharMarca]) e o `VectorDrawable`
 * (`res/drawable/marca_claryon.xml`, que repete os mesmos números já
 * multiplicados — e o `MarcaTest` compara os dois, para não divergirem).
 *
 * ## O que a forma diz
 *
 * Um disco no alto e três ondas concêntricas descendo dele. Lê como onda sonora,
 * como sonar e como olho ao mesmo tempo, e as três leituras são o produto: um
 * copiloto que **escuta**, responde por som, e mostra onde está a guarnição. Não
 * há texto na marca — o produto não tem display nos óculos, e uma marca que
 * depende de ler palavra é uma marca que morre no único lugar onde ele opera.
 *
 * ## As proporções, e a razão de cada uma
 *
 * Tudo é fração do **raio externo** (a borda de fora da terceira onda), então a
 * marca é uma só forma em qualquer tamanho:
 *
 * ```
 *  pupila ... 0,250          ondas:  0,355 → 0,500   traço 0,145
 *                                    0,605 → 0,750   traço 0,145
 *                                    0,855 → 1,000   traço 0,145
 *                            vãos:  0,105 (pupila→1ª) · 0,105 · 0,105
 * ```
 *
 *  - **Traço 0,145 e vão 0,105.** O enunciado da marca pede vão "de largura
 *    semelhante à do próprio arco". Semelhante, não igual: com traço e vão
 *    idênticos a forma vira uma grade cinza no tamanho do ícone (24 dp → traço de
 *    0,6 px), porque o antialiasing come metade de cada um. Traço ~38% mais
 *    grosso que o vão sobrevive à redução; a diferença é imperceptível no tamanho
 *    grande e é o que mantém a marca legível no pequeno — confirmado na gaveta de
 *    aplicativos do emulador, onde a marca ainda lê a 48 dp.
 *
 *  - **Varredura de 180°, ponta reta (`StrokeCap.Butt`).** As ondas terminam na
 *    horizontal do centro, cortadas em reto. Ponta arredondada faria a onda
 *    parecer um traço desenhado à mão; reta faz dela uma **seção** — a fatia de
 *    uma frente que continua. É a diferença entre um rabisco e um instrumento.
 *
 *  - **O disco é um círculo inteiro, e aqui eu divergi do enunciado.** A descrição
 *    pedia que a primeira onda *cortasse* o disco, deixando-o com um raio na
 *    metade de cima e outro na de baixo. Foi a primeira versão, desenhada com
 *    0,280 em cima e 0,161 embaixo — e no aparelho ela não lê como corte: lê como
 *    **pingo**. A calota menor pendurada sob o disco vira um apêndice, e o olho a
 *    interpreta como gota caindo, não como pupila. A captura está em
 *    `docs/` (abertura, 21/08).
 *
 *    O que produz a leitura de **pupila dentro de íris** — que é o que o enunciado
 *    quer — é o oposto: disco inteiro, e um **anel preto de largura de vão** entre
 *    ele e a primeira onda. Esse anel só existe na metade de baixo, porque é só lá
 *    que há onda; o disco fica com um crescente preto sob ele e a primeira onda
 *    passa a envolvê-lo. É a mesma informação que o enunciado descreve, obtida
 *    pela geometria que sobrevive à renderização.
 *
 *  - **Pupila 0,250 e não menor.** Com o vão e os três traços fixos, o raio da
 *    pupila é o que sobra: `1 − 3×0,145 − 3×0,105 = 0,250`. Menor que isso e a
 *    fonte some entre as ondas; a marca vira só ondas, e ondas sem fonte não
 *    contam de onde vieram.
 *
 * ## O que NÃO está aqui
 *
 * Cor. A marca é uma forma; quem pinta é quem desenha. Ela nunca traz `NoAr`,
 * `Vivo` ou `Falha` — essas três cores carregam significado operacional
 * (transmitindo, presente, morto) e uma marca âmbar diria "no ar" a um agente que
 * não está transmitindo. A marca é `Tinta` sobre `Vazio`, sempre.
 */
object GeometriaDaMarca {

    /** Raio do disco, em frações do raio externo. Círculo inteiro. */
    const val PUPILA: Float = 0.250f

    /** Uma onda: banda anular entre [interno] e [externo], em frações. */
    data class Onda(val interno: Float, val externo: Float) {
        /** Raio do eixo do traço — é aqui que o `Stroke` do Compose é centrado. */
        val eixo: Float get() = (interno + externo) / 2f

        /** Espessura do traço. */
        val traco: Float get() = externo - interno
    }

    /** Da mais interna (a íris, que envolve a pupila) para a mais externa. */
    val ONDAS: List<Onda> = listOf(
        Onda(0.355f, 0.500f),
        Onda(0.605f, 0.750f),
        Onda(0.855f, 1.000f),
    )

    /**
     * Altura da marca em frações do raio externo: a calota de cima (`PUPILA`)
     * mais o raio inteiro para baixo. A largura é `2,0` — a marca é mais larga
     * que alta, na razão `2 : 1,28`.
     */
    const val ALTURA: Float = 1f + PUPILA

    /** Largura em frações do raio externo. */
    const val LARGURA: Float = 2f
}

/**
 * Desenha a marca dentro de [tamanho], centrada, no maior raio que couber.
 *
 * [presencaDasOndas] é o que a abertura anima: um valor por onda, `0` = ausente,
 * `1` = inteira. A onda entra **crescendo do centro para fora**, porque é assim
 * que frente de onda se comporta — e é a mesma física que o produto usa quando
 * transmite. Fora da abertura, todo mundo passa `1f`.
 *
 * A pupila tem alfa próprio ([alfaDaPupila]) porque ela é a primeira coisa a
 * existir: as ondas emanam **dela**, e uma onda que aparece antes da fonte inverte
 * a causa.
 */
fun DrawScope.desenharMarca(
    cor: Color,
    tamanho: Size = size,
    centroDoQuadro: Offset = Offset(tamanho.width / 2f, tamanho.height / 2f),
    presencaDasOndas: List<Float> = List(GeometriaDaMarca.ONDAS.size) { 1f },
    alfaDaPupila: Float = 1f,
) {
    val raio = minOf(
        tamanho.width / GeometriaDaMarca.LARGURA,
        tamanho.height / GeometriaDaMarca.ALTURA,
    )
    if (raio <= 0f) return

    // O centro geométrico da marca não é o centro do seu retângulo: sobra menos
    // marca acima do eixo (a calota da pupila) do que abaixo (o raio inteiro).
    val centro = Offset(
        x = centroDoQuadro.x,
        y = centroDoQuadro.y - raio * GeometriaDaMarca.ALTURA / 2f + raio * GeometriaDaMarca.PUPILA,
    )

    if (alfaDaPupila > 0f) {
        drawCircle(
            color = cor,
            radius = raio * GeometriaDaMarca.PUPILA,
            center = centro,
            alpha = alfaDaPupila.coerceIn(0f, 1f),
        )
    }

    GeometriaDaMarca.ONDAS.forEachIndexed { indice, onda ->
        val presenca = presencaDasOndas.getOrElse(indice) { 1f }
        if (presenca <= 0f) return@forEachIndexed
        // A onda cresce da borda da pupila até o seu lugar. Interpolar o RAIO (e
        // não escalar a marca inteira) é o que faz a frente de onda se afastar da
        // fonte em vez de a marca inchar.
        val eixo = raio * lerp(GeometriaDaMarca.PUPILA, onda.eixo, presenca)
        val traco = raio * onda.traco * presenca.coerceAtLeast(0.35f)
        drawArc(
            color = cor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(centro.x - eixo, centro.y - eixo),
            size = Size(eixo * 2f, eixo * 2f),
            style = Stroke(width = traco, cap = StrokeCap.Butt),
            alpha = presenca.coerceIn(0f, 1f),
        )
    }
}

private fun lerp(de: Float, ate: Float, fracao: Float): Float = de + (ate - de) * fracao

/**
 * A marca, parada.
 *
 * [largura] é a largura da marca; a altura sai da proporção `2 : 1,28` e não é
 * parâmetro — marca com proporção livre é marca deformada, e este é o único lugar
 * onde isso poderia acontecer sem ninguém ver.
 */
@Composable
fun MarcaClaryon(
    modifier: Modifier = Modifier,
    largura: Dp = 96.dp,
    cor: Color = Cores.Tinta,
) {
    val altura = largura * (GeometriaDaMarca.ALTURA / GeometriaDaMarca.LARGURA)
    Box(modifier.width(largura).height(altura)) {
        Canvas(Modifier.width(largura).height(altura)) {
            desenharMarca(cor = cor)
        }
    }
}
