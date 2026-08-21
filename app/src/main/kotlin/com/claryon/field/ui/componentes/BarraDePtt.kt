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

    val alturaAlvo = if (noAr) 160.dp else 136.dp
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
 * Repouso.
 *
 * Terceira reescrita, e a lição é sobre o que eu insisti em errar: **colchetes,
 * âncoras e marcas de registro são maneirismo.** Duas vezes coloquei ornamento
 * geométrico na borda achando que dava linguagem de instrumento, e as duas vezes
 * o resultado foi o oposto — decoração aplicada por fora, que qualquer olho
 * treinado reconhece como enfeite gerado.
 *
 * O que instrumento de verdade tem não é borda: é **hierarquia de superfície**.
 * Um botão de rádio é mais claro que a placa em volta porque é uma peça
 * diferente, não porque alguém desenhou um contorno nele. Aqui o botão é o único
 * bloco claro da tela inteira, e é só isso que o separa — sem borda, sem canto
 * arredondado, sem marca.
 *
 * A disponibilidade fica no próprio bloco, não num rótulo ao lado. Bloco claro e
 * texto legível = dá para falar. Bloco apagado e texto rebaixado = não dá, com a
 * causa escrita dentro dele. O agente lê o estado pela **massa**, de relance, sem
 * precisar decodificar um ponto colorido de 7 px.
 */
@Composable
private fun ConteudoPronto(canal: String, pressao: Float) {
    LinhaDeCanal(canal, "meio-duplex", Cores.Vivo)
    Box(Modifier.height(Espaco.Medio))
    BlocoDeFala(
        rotulo = "Segure para falar",
        habilitado = true,
        pressao = pressao,
    )
}

/**
 * Cabeçalho da barra: canal à esquerda, modo à direita.
 *
 * O canal fica em caixa-alta porque **é** caixa-alta — indicativo de rádio se
 * escreve assim, e a `Etiqueta` só repete o que o dado já diz. O modo à direita
 * saiu dela: `MEIO-DUPLEX` a 0,16 em de entreletra era um dado de configuração
 * gritando ao lado do nome do canal, e o critério para gritar nesta tela é ser
 * excepcional. Meio-duplex é permanente — é a definição do oposto.
 */
@Composable
private fun LinhaDeCanal(canal: String, direita: String, corDoPonto: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoDeEstado(corDoPonto)
            Box(Modifier.size(Espaco.Curto))
            Etiqueta(canal, cor = Cores.TintaMedia)
        }
        TextoDado(direita, cor = Cores.TintaFraca)
    }
}

/**
 * O bloco de fala.
 *
 * Sem borda, sem canto, sem ornamento. A disponibilidade é a **claridade da
 * superfície**: bloco claro convida, bloco apagado recusa. É a mesma leitura que
 * um botão físico dá pelo relevo, traduzida para uma tela plana.
 *
 * A barra de pressão nasce na base e cresce — fica na borda inferior justamente
 * para não competir com o rótulo, e some junto com o dedo.
 */
@Composable
private fun BlocoDeFala(
    rotulo: String,
    habilitado: Boolean,
    pressao: Float,
    detalhe: String? = null,
) {
    val avanco = pressao.coerceIn(0f, 1f)
    val fundo by animateColorAsState(
        targetValue = when {
            !habilitado -> Cores.Painel
            avanco > 0f -> Cores.Tinta
            else -> Cores.Elevado
        },
        animationSpec = tween(durationMillis = 90),
        label = "fundo-bloco",
    )
    val tinta = when {
        !habilitado -> Cores.TintaFraca
        avanco > 0f -> Cores.Vazio
        else -> Cores.Tinta
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(if (detalhe == null) 60.dp else 72.dp)
            .background(fundo)
            .drawBehind {
                if (avanco > 0f) {
                    drawRect(
                        color = Cores.NoAr,
                        topLeft = Offset(0f, size.height - 3f),
                        size = androidx.compose.ui.geometry.Size(size.width * avanco, 3f),
                    )
                }
            }
            .padding(horizontal = Espaco.Padrao),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(rotulo, style = Tipo.Acao, color = tinta)
        detalhe?.let {
            Box(Modifier.height(Espaco.Micro))
            TextoCorpoMenor(it, cor = Cores.TintaFraca, maxLinhas = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConteudoOcupado(porQuem: String) {
    LinhaDeCanal(porQuem, "falando", Cores.P2)
    Box(Modifier.height(Espaco.Medio))
    // Mesmo bloco, apagado. Meio-duplex: falar por cima não é uma opção que o
    // botão deva oferecer e depois recusar.
    BlocoDeFala(
        rotulo = "Canal ocupado",
        habilitado = false,
        pressao = 0f,
        detalhe = "$porQuem está transmitindo.",
    )
}

@Composable
private fun ConteudoIndisponivel(motivo: String) {
    LinhaDeCanal("sem canal", "indisponível", Cores.Falha)
    Box(Modifier.height(Espaco.Medio))
    // A causa vive DENTRO do bloco desabilitado, não num aviso separado: quem
    // olha o botão para falar é quem precisa saber por que não dá.
    BlocoDeFala(
        rotulo = "Não é possível falar",
        habilitado = false,
        pressao = 0f,
        detalhe = motivo,
    )
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
