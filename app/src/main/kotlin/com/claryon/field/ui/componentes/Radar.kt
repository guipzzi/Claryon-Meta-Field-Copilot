package com.claryon.field.ui.componentes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import com.claryon.field.mapa.Frescor
import com.claryon.field.mapa.ParNoMapa
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Tipo
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * **Indicador de posição relativa — o mapa da guarnição.**
 *
 * Não é um mapa de ruas, e a escolha é do dado, não da estética. O que o
 * aparelho recebe de cada par é **distância e rumo**, calculados no servidor;
 * plotar isso sobre uma malha viária exigiria derivar a coordenada do par por
 * trigonometria a partir da minha — o que é possível, mas transforma o app num
 * lugar onde a coordenada alheia **existe**, e hoje ela não existe em memória
 * nenhuma deste processo.
 *
 * O que este desenho mostra é exatamente o que se sabe, sem inventar e sem
 * derivar: o portador no centro, cada par no seu rumo verdadeiro, à sua distância
 * verdadeira. Um agente lendo isto sabe para que lado virar e quantos metros
 * andar — que é a decisão que ele precisa tomar. Uma malha de ruas acrescentaria
 * contexto e cobraria foco visual que ele não tem para dar.
 *
 * **Escala logarítmica.** Linear faria a guarnição inteira virar um borrão no
 * centro quando alguém estivesse a 5 km, e 5 km acontece. Os anéis ficam em 100 m,
 * 500 m, 2 km e 5 km — cada um rotulado, porque anel sem número é enfeite.
 *
 * O norte fica **em cima e fixo**, não girando com o aparelho. Rumo que gira com
 * o corpo exige recalibração mental a cada passo; norte fixo é o que uma carta
 * náutica e uma bússola de placa fazem, e é o que o agente já sabe ler.
 */
@Composable
fun RadarDaGuarnicao(
    pares: List<ParNoMapa>,
    distanciasM: List<Int>,
    rumosGraus: List<Double?>,
    modifier: Modifier = Modifier,
) {
    val medidor = rememberTextMeasurer()

    // Varredura lenta: um giro a cada 6 s, tênue. Diz "o instrumento está vivo"
    // sem sugerir que algo foi detectado agora — o dado tem carimbo próprio, e
    // uma varredura rápida imitaria radar de busca, que este não é.
    val varredura by rememberInfiniteTransition(label = "varredura-radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angulo-varredura",
    )

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val centro = Offset(size.width / 2f, size.height / 2f)
            val raioMax = size.minDimension / 2f - 24f

            desenharAneis(centro, raioMax, medidor)
            desenharEixos(centro, raioMax)
            desenharVarredura(centro, raioMax, varredura)

            // Pares colados vão para um arco próprio em volta do portador, e não
            // para cima dele: sobrepostos no centro, eles esconderiam a cruz e uns
            // aos outros. Numa dupla na mesma viatura — a configuração mais comum
            // do policiamento — isso seria o caso normal, não a exceção.
            val juntos = pares.filter { it.juntoDeMim }
            juntos.forEachIndexed { i, par ->
                val angulo = 360.0 / juntos.size * i + 20.0
                desenharMarca(centro, 26f, angulo, par, medidor, comTraco = false)
            }

            pares.forEachIndexed { i, par ->
                if (par.juntoDeMim) return@forEachIndexed
                val rumo = rumosGraus.getOrNull(i) ?: return@forEachIndexed
                val distancia = distanciasM.getOrNull(i) ?: return@forEachIndexed
                desenharMarca(centro, raioPara(distancia, raioMax), rumo, par, medidor)
            }

            desenharPortador(centro)
        }
    }
}

/**
 * Mapeia metros para raio de tela, em escala logarítmica.
 *
 * `ln(1 + d/50) / ln(1 + 5000/50)`: dá resolução generosa perto — onde a decisão
 * é "atravesso a rua ou entro na viatura" — e comprime o longe, onde a única
 * pergunta é a direção.
 */
private fun raioPara(distanciaM: Int, raioMax: Float): Float {
    val normalizado = ln(1f + distanciaM.coerceAtLeast(0) / 50f) / ln(1f + 5000f / 50f)
    return (normalizado * raioMax).coerceIn(0f, raioMax)
}

private fun DrawScope.desenharAneis(centro: Offset, raioMax: Float, medidor: TextMeasurer) {
    // Anéis rotulados. Anel sem número não informa escala — vira ornamento, que é
    // exatamente o que este painel evita.
    listOf(100 to "100 m", 500 to "500 m", 2_000 to "2 km", 5_000 to "5 km").forEach { (m, rotulo) ->
        val r = raioPara(m, raioMax)
        drawCircle(
            color = Cores.Traco,
            radius = r,
            center = centro,
            style = Stroke(width = 1f),
        )
        val texto = medidor.measure(rotulo, Tipo.Etiqueta.copy(color = Cores.TintaFraca))
        drawText(texto, topLeft = Offset(centro.x + 6f, centro.y - r - texto.size.height - 2f))
    }
}

private fun DrawScope.desenharEixos(centro: Offset, raioMax: Float) {
    val tracejado = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))
    // Eixos cardeais tênues. Norte fixo em cima: o agente lê rumo como leria uma
    // bússola de placa, sem recalibrar mentalmente a cada passo.
    listOf(0f, 90f, 180f, 270f).forEach { angulo ->
        rotate(degrees = angulo, pivot = centro) {
            drawLine(
                color = Cores.Traco.copy(alpha = 0.6f),
                start = centro,
                end = Offset(centro.x, centro.y - raioMax),
                strokeWidth = 1f,
                pathEffect = tracejado,
            )
        }
    }
}

private fun DrawScope.desenharVarredura(centro: Offset, raioMax: Float, angulo: Float) {
    rotate(degrees = angulo, pivot = centro) {
        drawLine(
            color = Cores.NoAr.copy(alpha = 0.16f),
            start = centro,
            end = Offset(centro.x, centro.y - raioMax),
            strokeWidth = 2f,
        )
    }
}

/**
 * Um par.
 *
 * A marca esmaece com a idade pelo mesmo limiar da lista e da fala — uma regra,
 * três saídas. E o rótulo carrega o indicativo, porque uma marca sem nome num
 * painel de guarnição obriga a contar posições para descobrir quem é.
 */
private fun DrawScope.desenharMarca(
    centro: Offset,
    r: Float,
    rumoGraus: Double,
    par: ParNoMapa,
    medidor: TextMeasurer,
    comTraco: Boolean = true,
) {
    val alfa = when (par.frescor) {
        Frescor.ATUAL -> 1f
        Frescor.ESMAECIDO -> 0.4f
        Frescor.ANTIGO -> 0.22f
    }
    val rad = Math.toRadians(rumoGraus - 90.0)
    val p = Offset(
        centro.x + (r * cos(rad)).toFloat(),
        centro.y + (r * sin(rad)).toFloat(),
    )

    val cor = if (par.emMovimento) Cores.Vivo else Cores.Tinta

    // Traço radial ligando o centro à marca: dá o rumo como direção, não como
    // ângulo a inferir da posição. Não vale para quem está colado — ali o traço
    // seria um risco de 26 px sem significado.
    if (comTraco) {
        drawLine(color = cor.copy(alpha = alfa * 0.25f), start = centro, end = p, strokeWidth = 1f)
    }
    drawCircle(color = cor.copy(alpha = alfa), radius = 5f, center = p)
    drawCircle(
        color = cor.copy(alpha = alfa * 0.4f),
        radius = 11f,
        center = p,
        style = Stroke(width = 1f),
    )

    val rotulo = medidor.measure(
        par.indicativo,
        Tipo.Dado.copy(color = Cores.Tinta.copy(alpha = alfa)),
    )
    drawText(rotulo, topLeft = Offset(p.x + 14f, p.y - rotulo.size.height / 2f))
}

/** O portador. Cruz, não ponto: cruz marca origem; ponto seria só mais um par. */
private fun DrawScope.desenharPortador(centro: Offset) {
    val b = 9f
    drawLine(Cores.NoAr, Offset(centro.x - b, centro.y), Offset(centro.x + b, centro.y), 2f)
    drawLine(Cores.NoAr, Offset(centro.x, centro.y - b), Offset(centro.x, centro.y + b), 2f)
}
