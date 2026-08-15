package com.claryon.field.ui.componentes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo

/**
 * Rótulo de seção: caixa-alta, monoespaçada, espaçada.
 *
 * Dá o ritmo do painel. Cada bloco de dado começa dizendo **o que é**, nunca com
 * um título decorativo — é a diferença entre um instrumento e uma revista.
 */
@Composable
fun Etiqueta(texto: String, cor: Color = Cores.TintaFraca, modifier: Modifier = Modifier) {
    Text(texto.uppercase(), style = Tipo.Etiqueta, color = cor, modifier = modifier)
}

@Composable
fun TextoDado(
    texto: String,
    cor: Color = Cores.Tinta,
    modifier: Modifier = Modifier,
) = Text(texto, style = Tipo.Dado, color = cor, modifier = modifier)

@Composable
fun TextoIndicativo(
    texto: String,
    cor: Color = Cores.Tinta,
    modifier: Modifier = Modifier,
) = Text(texto, style = Tipo.Indicativo, color = cor, modifier = modifier)

@Composable
fun TextoCorpo(
    texto: String,
    cor: Color = Cores.Tinta,
    modifier: Modifier = Modifier,
) = Text(texto, style = Tipo.Corpo, color = cor, modifier = modifier)

@Composable
fun TextoCorpoMenor(
    texto: String,
    cor: Color = Cores.TintaMedia,
    modifier: Modifier = Modifier,
    maxLinhas: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = Text(
    texto,
    style = Tipo.CorpoMenor,
    color = cor,
    maxLines = maxLinhas,
    overflow = overflow,
    modifier = modifier,
)

/** Fio de 1 px. A estrutura do layout inteiro é feita disto. */
@Composable
fun Fio(modifier: Modifier = Modifier, cor: Color = Cores.Traco) {
    Box(modifier.fillMaxWidth().height(Espaco.Fio).background(cor))
}

/**
 * Ponto de estado.
 *
 * Quando [pulsando], respira devagar — usado só para "recebendo agora". A
 * pulsação é cara em atenção; reservá-la a uma coisa faz dela informação em vez
 * de enfeite.
 */
@Composable
fun PontoDeEstado(
    cor: Color,
    tamanho: Dp = 7.dp,
    pulsando: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pulso by rememberInfiniteTransition(label = "pulso").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alfa-pulso",
    )
    val alfa = if (pulsando) pulso else 1f

    Box(modifier.size(tamanho).clip(CircleShape).background(cor.copy(alpha = alfa)))
}

/**
 * Faixa de prioridade — 3 px na borda esquerda.
 *
 * Cor **e** posição carregam o mesmo dado, de propósito: cerca de 8% dos homens
 * têm alguma deficiência de visão de cor, e a proporção não é menor entre agentes
 * de segurança. A faixa também é mais larga em P1: quem não distingue vermelho de
 * âmbar ainda distingue grosso de fino.
 */
@Composable
fun FaixaDePrioridade(prioridade: Int, modifier: Modifier = Modifier) {
    val (cor, largura) = when (prioridade) {
        1 -> Cores.P1 to 4.dp
        2 -> Cores.P2 to 3.dp
        else -> Cores.P3 to 2.dp
    }
    Box(modifier.width(largura).background(cor))
}

/** Cabeçalho de tela: título em sans, etiqueta em mono, e um fio embaixo. */
@Composable
fun CabecalhoTatico(
    etiqueta: String,
    titulo: String,
    acessorio: @Composable (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Cores.Vazio)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = Espaco.Padrao,
                    end = Espaco.Padrao,
                    top = Espaco.Largo,
                    bottom = Espaco.Medio,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Etiqueta(etiqueta)
                Box(Modifier.height(Espaco.Micro))
                Text(titulo, style = Tipo.Titulo, color = Cores.Tinta)
            }
            acessorio?.invoke()
        }
        Fio()
    }
}

/**
 * Botão de ação. Retângulo, sem canto arredondado, rótulo em mono caixa-alta.
 *
 * Cantos retos porque o resto do painel é feito de fios retos, e um botão
 * arredondado no meio disso denuncia componente de biblioteca em vez de decisão.
 */
@Composable
fun BotaoTatico(
    rotulo: String,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    destrutivo: Boolean = false,
) {
    val corTexto = when {
        !habilitado -> Cores.TintaFraca
        destrutivo -> Cores.Falha
        else -> Cores.Vazio
    }
    val corFundo = when {
        !habilitado -> Cores.Elevado
        destrutivo -> Color.Transparent
        else -> Cores.Tinta
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(corFundo)
            .tocavel(habilitado = habilitado, aoTocar = aoTocar)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rotulo.uppercase(), style = Tipo.Acao, color = corTexto)
    }
}

/**
 * Estado vazio.
 *
 * Nunca uma tela em branco: vazio e indisponível são indistinguíveis para quem
 * olha, e a leitura errada é a perigosa — "ninguém por perto" quando a verdade é
 * "não estou recebendo".
 */
@Composable
fun Vazio(etiqueta: String, explicacao: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Espaco.Largo),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Etiqueta(etiqueta, cor = Cores.TintaFraca)
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(explicacao, cor = Cores.TintaFraca)
    }
}

/**
 * Toque **sem ripple**.
 *
 * Duas razões, e as duas importam.
 *
 * A de desenho: ripple é vocabulário do Material — uma onda circular que sai do
 * dedo. Este painel é feito de fios retos e mudança de fundo; uma onda no meio
 * dele denuncia componente de biblioteca em vez de decisão. O retorno de toque
 * aqui é o fundo escurecendo, que é o que um instrumento faz.
 *
 * A de sobrevivência: o `clickable` padrão lê `LocalIndication`, e neste projeto
 * o Material 2 entra no classpath por dependência transitiva e fornece um
 * `PlatformRipple` que o Compose Foundation atual **recusa** — o app morria com
 * `IllegalArgumentException` na primeira composição com botão. Passar
 * `indication = null` resolve o crash e o desenho de uma vez.
 */
@Composable
fun Modifier.tocavel(
    habilitado: Boolean = true,
    aoTocar: () -> Unit,
): Modifier {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()
    return this
        .background(if (pressionado && habilitado) Cores.Pressionado else Color.Transparent)
        .clickable(
            interactionSource = interacao,
            indication = null,
            enabled = habilitado,
            onClick = aoTocar,
        )
}
