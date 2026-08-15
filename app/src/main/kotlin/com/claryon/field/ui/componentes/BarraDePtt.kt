package com.claryon.field.ui.componentes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.animateColorAsState
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
 * Repouso: um interruptor de instrumento, não um botão de página.
 *
 * O caminho até aqui teve duas versões descartadas, e cada uma ensinou algo. O
 * retângulo com texto centralizado dizia "toque", não "segure". O alvo circular
 * dizia o gesto certo mas destoava de um painel feito de fios retos. E os quatro
 * colchetes simétricos da primeira tentativa liam como **enfeite**, não como
 * marca de registro.
 *
 * A referência do Gotham é HUD — instrumentação militar e de inteligência — e a
 * marca de registro nessa linguagem é **assimétrica e usinada**: duas âncoras em
 * cantos opostos, curtas e grossas, como as marcas de alinhamento de um visor.
 * Quatro finas e iguais são ornamento; duas na diagonal são referência.
 *
 * A estrutura é a de um interruptor de painel, e cada parte carrega dado:
 *
 *  - **Trilho de estado à esquerda**, 4 px, na cor do estado. É o que a visão
 *    periférica pega com o aparelho no suporte da viatura, sem foco.
 *  - **Rótulo alinhado à esquerda** — texto centralizado é gramática de botão de
 *    página; instrumento alinha à esquerda e deixa a leitura à direita.
 *  - **Leitura à direita**, em monoespaçada: o estado por extenso, no lugar onde
 *    um mostrador o colocaria.
 *  - **Barra de pressão na base**, que preenche enquanto o dedo desce.
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
    InterruptorDePtt(pressao = pressao, corDoTrilho = Cores.Vivo, leitura = "PRONTO")
}

/**
 * O interruptor.
 *
 * Desenhado inteiro em `drawBehind` para que trilho, âncoras e barra de pressão
 * fiquem no mesmo sistema de coordenadas — compor isso com `Box` aninhado exigiria
 * alinhar três caixas por padding, e qualquer mudança de altura quebraria o
 * conjunto.
 */
@Composable
private fun InterruptorDePtt(
    pressao: Float,
    corDoTrilho: Color,
    leitura: String,
) {
    val avanco = pressao.coerceIn(0f, 1f)
    val fundo by animateColorAsState(
        targetValue = if (avanco > 0f) Cores.Pressionado else Cores.Elevado,
        animationSpec = tween(durationMillis = 90),
        label = "fundo-interruptor",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(fundo)
            .drawBehind {
                val trilho = 4.dp.toPx()
                // Trilho de estado. Engrossa sob pressão — o mesmo dado, mais
                // presente, sem mudar de cor e sem pedir foco.
                drawRect(
                    color = corDoTrilho,
                    size = androidx.compose.ui.geometry.Size(
                        trilho * (1f + avanco * 0.8f),
                        size.height,
                    ),
                )

                // Âncoras de registro: superior-direita e inferior-esquerda.
                // Assimétricas de propósito — duas na diagonal são referência de
                // alinhamento; quatro iguais viram moldura.
                val braco = 11.dp.toPx()
                val esp = 2.dp.toPx()
                val cor = Cores.TracoForte
                drawLine(cor, Offset(size.width - braco, 0f), Offset(size.width, 0f), esp)
                drawLine(cor, Offset(size.width, 0f), Offset(size.width, braco), esp)
                drawLine(cor, Offset(0f, size.height), Offset(braco, size.height), esp)
                drawLine(cor, Offset(0f, size.height - braco), Offset(0f, size.height), esp)

                // Barra de pressão na base, do trilho para a direita.
                if (avanco > 0f) {
                    drawRect(
                        color = Cores.NoAr,
                        topLeft = Offset(0f, size.height - 3f),
                        size = androidx.compose.ui.geometry.Size(size.width * avanco, 3f),
                    )
                }
            }
            .padding(horizontal = Espaco.Padrao),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SEGURE PARA FALAR", style = Tipo.Acao, color = Cores.Tinta)
            Etiqueta(leitura, cor = Cores.TintaMedia)
        }
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
            PontoDeEstado(Cores.P2, pulsando = true)
            Box(Modifier.size(Espaco.Curto))
            TextoDado(porQuem, cor = Cores.Tinta)
        }
        // "Falando", e não "canal ocupado": diz quem, não o estado do recurso.
        Etiqueta("FALANDO", cor = Cores.TintaMedia)
    }
}

@Composable
private fun ConteudoIndisponivel(motivo: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PontoDeEstado(Cores.Falha)
        Box(Modifier.size(Espaco.Curto))
        TextoCorpoMenor(
            motivo,
            cor = Cores.TintaMedia,
            maxLinhas = 2,
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
                // em vez de equalizador, e mantém a leitura com nível baixo.
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
