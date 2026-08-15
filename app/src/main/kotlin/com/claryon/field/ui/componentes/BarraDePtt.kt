package com.claryon.field.ui.componentes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo

/** O que a barra mostra. Estado do rádio, não do botão. */
sealed interface EstadoDoPtt {

    /** Canal disponível, ninguém falando. */
    data class Pronto(val canal: String) : EstadoDoPtt

    /** **Nós** estamos transmitindo. Único estado que acende o âmbar. */
    data class NoAr(val decorridoMs: Long, val amplitude: Float) : EstadoDoPtt

    /** Outro agente detém o canal. Meio-duplex: não dá para falar por cima. */
    data class Ocupado(val porQuem: String) : EstadoDoPtt

    /** Sem rota de áudio, sem rede, sem sessão. A causa é dita. */
    data class Indisponivel(val motivo: String) : EstadoDoPtt
}

/**
 * **A barra de push-to-talk — o elemento assinatura da interface.**
 *
 * Em repouso é quase nada: um fio, o nome do canal em monoespaçada e um ponto de
 * estado. Pressionada, vira a coisa mais alta da tela — forma de onda ao vivo,
 * âmbar, e uma moldura de 1 px em volta da tela inteira.
 *
 * Três decisões que vêm do produto, não da estética:
 *
 *  1. **Segurar, nunca alternar.** `onPress` … `awaitRelease()`, sem estado
 *     alternado. Um botão que liga e desliga transmite indefinidamente se o
 *     agente se distrair — e o rádio da guarnição fica preso. Segurar exige
 *     intenção contínua e devolve o canal no instante em que o dedo sai, mesmo
 *     que a mão relaxe por susto.
 *
 *  2. **A moldura da tela inteira.** Numa transmissão acidental a fala do agente
 *     e de quem está ao lado vai para a guarnição inteira. Um ícone de 24 dp não
 *     é aviso suficiente para isso; a moldura é impossível de não ver, mesmo com
 *     o aparelho de canto de olho no suporte da viatura.
 *
 *  3. **Háptico no início e no fim.** O agente está de óculos, olhando a rua. O
 *     retorno tátil confirma a transmissão sem exigir a tela — é o mesmo papel do
 *     earcon, na modalidade que sobra quando o áudio está ocupado pela própria voz.
 *
 * A forma de onda usa amplitude real do microfone. Uma animação decorativa aqui
 * seria pior que nenhuma: o agente leria movimento como "está captando" quando o
 * microfone pode estar mudo.
 */
@Composable
fun BarraDePtt(
    estado: EstadoDoPtt,
    aoPressionar: () -> Unit,
    aoSoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptico = LocalHapticFeedback.current
    val noAr = estado is EstadoDoPtt.NoAr
    val habilitado = estado is EstadoDoPtt.Pronto || noAr

    // Progresso da pressão: 0 em repouso, cresce enquanto o dedo desce. É o
    // retorno visual no intervalo entre o toque e o primeiro quadro sair.
    var pressao by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressao > 0f) {
        if (pressao > 0f) {
            while (pressao < 1f) {
                pressao = (pressao + 0.12f).coerceAtMost(1f)
                delay(16)
            }
        }
    }

    val alturaAlvo = if (noAr) 152.dp else 168.dp
    val altura by animateFloatAsState(
        targetValue = alturaAlvo.value,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "altura-ptt",
    )
    val brilho by animateFloatAsState(
        targetValue = if (noAr) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "brilho-ptt",
    )

    Column(
        modifier
            .fillMaxWidth()
            .background(if (noAr) Cores.Elevado else Cores.Painel)
            .drawBehind {
                // Fio superior: em repouso é estrutura; no ar vira o sinal.
                drawLine(
                    color = lerpCor(Cores.Traco, Cores.NoAr, brilho),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = if (noAr) 2f else 1f,
                )
            }
            .height(altura.dp)
            .pointerInput(habilitado) {
                if (!habilitado) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptico.performHapticFeedback(HapticFeedbackType.LongPress)
                        pressao = 0.05f
                        aoPressionar()
                        // Espera a soltura **aqui**, e não num `onTap`: garante que
                        // soltar por qualquer motivo — dedo escorregando, chamada
                        // entrando, tela apagando — encerre a transmissão.
                        tryAwaitRelease()
                        haptico.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pressao = 0f
                        aoSoltar()
                    },
                )
            }
            .semantics {
                contentDescription = when (estado) {
                    is EstadoDoPtt.Pronto -> "Segure para falar no canal ${estado.canal}"
                    is EstadoDoPtt.NoAr -> "Transmitindo. Solte para encerrar"
                    is EstadoDoPtt.Ocupado -> "Canal ocupado por ${estado.porQuem}"
                    is EstadoDoPtt.Indisponivel -> "Rádio indisponível. ${estado.motivo}"
                }
            }
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        verticalArrangement = Arrangement.Center,
    ) {
        when (estado) {
            is EstadoDoPtt.NoAr -> ConteudoNoAr(estado)
            is EstadoDoPtt.Pronto -> ConteudoPronto(estado.canal, pressao)
            is EstadoDoPtt.Ocupado -> ConteudoOcupado(estado.porQuem)
            is EstadoDoPtt.Indisponivel -> ConteudoIndisponivel(estado.motivo)
        }
    }
}

@Composable
private fun ConteudoNoAr(estado: EstadoDoPtt.NoAr) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Etiqueta("NO AR", cor = Cores.NoAr)
        TextoDado(formatarDuracao(estado.decorridoMs), cor = Cores.NoAr)
    }
    Box(Modifier.height(Espaco.Curto))
    FormaDeOnda(amplitude = estado.amplitude, modifier = Modifier.fillMaxWidth().height(28.dp))
}

/**
 * Repouso: o gesto tem de estar no desenho.
 *
 * A versão anterior era texto centralizado num retângulo com colchetes — e
 * retângulo com texto no meio é a forma universal de **toque**, não de
 * pressão-e-segura. O agente lia "aperte", soltava, e nada acontecia.
 *
 * A forma agora carrega o gesto: um alvo circular grosso, com um anel que existe
 * para ser preenchido enquanto o dedo está lá. Círculo grande é o vocabulário do
 * botão de rádio — o do talkie, o do interfone, o do PTT de headset —, e o polegar
 * o encontra sem a tela ser olhada, que é o requisito de verdade.
 *
 * Ocupa 96 dp de diâmetro: acima do alvo mínimo de acessibilidade por larga
 * margem, porque o dedo que o procura está em movimento, com luva, e no escuro.
 */
@Composable
private fun ConteudoPronto(canal: String, pressao: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoDeEstado(Cores.Vivo)
            Box(Modifier.size(Espaco.Curto))
            Etiqueta(canal, cor = Cores.TintaMedia)
        }
        Etiqueta("meio-duplex", cor = Cores.TintaFraca)
    }

    Box(Modifier.height(Espaco.Medio))

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AlvoDePtt(pressao)
    }
}

/**
 * O alvo circular.
 *
 * Três anéis concêntricos, e cada um diz uma coisa:
 *
 *  - o **externo** é o limite do alvo de toque, fino e discreto;
 *  - o **de progresso** preenche em âmbar enquanto o dedo desce — é o retorno de
 *    que a pressão foi registrada, no intervalo entre o toque e o primeiro quadro
 *    de áudio sair;
 *  - o **miolo** é a superfície, que escurece sob o dedo.
 *
 * O ícone é um traço de microfone desenhado, não uma fonte de ícones: uma
 * dependência de 300 KB para um glifo, num app que fala por áudio, não se paga.
 */
@Composable
private fun AlvoDePtt(pressao: Float) {
    val escala by animateFloatAsState(
        targetValue = if (pressao > 0f) 0.94f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "escala-alvo",
    )

    Box(
        Modifier
            .size(96.dp)
            .scale(escala)
            .drawBehind {
                val raio = size.minDimension / 2f
                val centro = Offset(size.width / 2f, size.height / 2f)

                drawCircle(color = Cores.Elevado, radius = raio, center = centro)
                drawCircle(
                    color = Cores.TracoForte,
                    radius = raio - 1f,
                    center = centro,
                    style = Stroke(width = 1.5f),
                )
                if (pressao > 0f) {
                    drawArc(
                        color = Cores.NoAr,
                        startAngle = -90f,
                        sweepAngle = 360f * pressao.coerceIn(0f, 1f),
                        useCenter = false,
                        style = Stroke(width = 4f, cap = StrokeCap.Round),
                    )
                }
                desenharMicrofone(this, centro, raio * 0.42f, Cores.Tinta)
            },
    )
}

/**
 * Microfone em traços. Cápsula arredondada, arco de suporte e haste.
 *
 * Desenhado em vez de importado: é o único ícone do aplicativo inteiro, e trazer
 * uma biblioteca de ícones para ele acrescentaria peso ao APK que já carrega dois
 * modelos de IA.
 */
private fun desenharMicrofone(
    escopo: androidx.compose.ui.graphics.drawscope.DrawScope,
    centro: Offset,
    tamanho: Float,
    cor: Color,
) = with(escopo) {
    val larguraCapsula = tamanho * 0.62f
    val alturaCapsula = tamanho * 1.15f
    val topo = centro.y - alturaCapsula / 2f - tamanho * 0.15f

    drawRoundRect(
        color = cor,
        topLeft = Offset(centro.x - larguraCapsula / 2f, topo),
        size = androidx.compose.ui.geometry.Size(larguraCapsula, alturaCapsula),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(larguraCapsula / 2f),
    )
    val raioArco = tamanho * 0.72f
    drawArc(
        color = cor,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(centro.x - raioArco, topo + alturaCapsula * 0.42f),
        size = androidx.compose.ui.geometry.Size(raioArco * 2f, raioArco * 2f),
        style = Stroke(width = tamanho * 0.16f, cap = StrokeCap.Round),
    )
    val baseArco = topo + alturaCapsula * 0.42f + raioArco
    drawLine(
        color = cor,
        start = Offset(centro.x, baseArco),
        end = Offset(centro.x, baseArco + tamanho * 0.3f),
        strokeWidth = tamanho * 0.16f,
        cap = StrokeCap.Round,
    )
}

/**
 * Colchetes de mira: quatro cantos, 14 dp de braço.
 *
 * Vocabulário de retículo de instrumento, não de botão. Enquadra sem preencher —
 * o alvo de toque é a barra inteira, e um retângulo cheio aqui sugeriria que só
 * o retângulo é tocável.
 */
private fun colchetes(escopo: androidx.compose.ui.graphics.drawscope.DrawScope) = with(escopo) {
    val braco = 14.dp.toPx()
    val cor = Cores.TracoForte
    val l = 1.5f
    listOf(
        Offset(0f, 0f) to listOf(Offset(braco, 0f), Offset(0f, braco)),
        Offset(size.width, 0f) to listOf(Offset(size.width - braco, 0f), Offset(size.width, braco)),
        Offset(0f, size.height) to listOf(Offset(braco, size.height), Offset(0f, size.height - braco)),
        Offset(size.width, size.height) to
            listOf(Offset(size.width - braco, size.height), Offset(size.width, size.height - braco)),
    ).forEach { (canto, bracos) ->
        bracos.forEach { fim -> drawLine(cor, canto, fim, strokeWidth = l) }
    }
}

@Composable
private fun ConteudoOcupado(porQuem: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoDeEstado(Cores.P2)
            Box(Modifier.size(Espaco.Curto))
            TextoDado(porQuem, cor = Cores.Tinta)
        }
        // "Falando", e não "canal ocupado": diz quem, não o estado do recurso.
        Etiqueta("FALANDO", cor = Cores.TintaMedia)
    }
}

@Composable
private fun ConteudoIndisponivel(motivo: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PontoDeEstado(Cores.Falha)
        Box(Modifier.size(Espaco.Curto))
        TextoCorpoMenor(
            motivo,
            cor = Cores.TintaMedia,
            maxLinhas = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Forma de onda simétrica, alimentada por amplitude **real** do microfone.
 *
 * Barras fixas com altura proporcional ao sinal, e não uma senoide animada: o
 * agente lê movimento como "está me captando", e uma animação decorativa mentiria
 * exatamente no momento em que a verdade importa — microfone mudo, rota caída,
 * mão sobre o aparelho.
 */
@Composable
private fun FormaDeOnda(amplitude: Float, modifier: Modifier = Modifier) {
    val nivel = amplitude.coerceIn(0f, 1f)
    Box(
        modifier.drawBehind {
            val barras = 48
            val larguraBarra = size.width / (barras * 2f)
            val meio = size.height / 2f
            for (i in 0 until barras) {
                // Envelope decrescente do centro para as bordas: dá forma de voz
                // em vez de equalizador, e mantém a leitura mesmo com nível baixo.
                val distanciaDoCentro = kotlin.math.abs(i - barras / 2f) / (barras / 2f)
                val envelope = 1f - distanciaDoCentro * 0.65f
                val h = (meio * nivel * envelope).coerceAtLeast(1.5f)
                val x = i * larguraBarra * 2f + larguraBarra
                drawLine(
                    color = Cores.NoAr.copy(alpha = 0.35f + 0.65f * envelope),
                    start = Offset(x, meio - h),
                    end = Offset(x, meio + h),
                    strokeWidth = larguraBarra,
                    cap = StrokeCap.Round,
                )
            }
        },
    )
}

private fun formatarDuracao(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun lerpCor(de: Color, para: Color, t: Float): Color = Color(
    red = de.red + (para.red - de.red) * t,
    green = de.green + (para.green - de.green) * t,
    blue = de.blue + (para.blue - de.blue) * t,
    alpha = de.alpha + (para.alpha - de.alpha) * t,
)
