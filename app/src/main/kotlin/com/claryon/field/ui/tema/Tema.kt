package com.claryon.field.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * **Tipografia — duas famílias com trabalhos distintos.**
 *
 * A monoespaçada não é estilo: é o vernáculo do rádio. Indicativo, horário,
 * canal, distância e rumo são **dados de leitura precisa**, e o produto os fala
 * dígito a dígito por essa mesma razão. Alinhados em coluna, com largura fixa, o
 * olho compara sem reler.
 *
 * A sans fica só com prosa — transcrição, mensagem de erro, texto de tela. Onde
 * há palavra corrida, a mono cansa; onde há dado, a proporcional engana.
 */
object Tipo {

    private val Mono = FontFamily.Monospace
    private val Sans = FontFamily.SansSerif

    /** Indicativo do agente. O nome pelo qual ele existe no rádio. */
    val Indicativo = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.02.em,
    )

    /** Indicativo em destaque — cabeçalho de conversa, cartão de perfil. */
    val IndicativoGrande = TextStyle(
        fontFamily = Mono,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.01.em,
    )

    /** Horário, canal, coordenada, distância. Tabular por natureza. */
    val Dado = TextStyle(
        fontFamily = Mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.02.em,
    )

    /**
     * Rótulo de seção. Caixa-alta espaçada, minúsculo.
     *
     * É o elemento que dá o ritmo do painel: cada bloco de dado começa com uma
     * etiqueta que diz o que é, e nunca com um título decorativo.
     */
    val Etiqueta = TextStyle(
        fontFamily = Mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.16.em,
    )

    /** Prosa: transcrição, explicação, erro. */
    val Corpo = TextStyle(
        fontFamily = Sans,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )

    val CorpoMenor = TextStyle(
        fontFamily = Sans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Título de tela. Sans, porque é linguagem, não dado. */
    val Titulo = TextStyle(
        fontFamily = Sans,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
    )

    /** Ação: rótulo de botão. Caixa-alta curta, como comando. */
    val Acao = TextStyle(
        fontFamily = Mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.em,
    )
}

/** Escala de espaçamento. Múltiplos de 4, nomeados pelo uso. */
object Espaco {
    val Fio = 1.dp
    val Micro = 4.dp
    val Curto = 8.dp
    val Medio = 12.dp
    val Padrao = 16.dp
    val Largo = 24.dp
    val Secao = 32.dp
}

private val EsquemaTatico = darkColorScheme(
    primary = Cores.Tinta,
    onPrimary = Cores.Vazio,
    secondary = Cores.TintaMedia,
    background = Cores.Vazio,
    onBackground = Cores.Tinta,
    surface = Cores.Painel,
    onSurface = Cores.Tinta,
    surfaceVariant = Cores.Elevado,
    onSurfaceVariant = Cores.TintaMedia,
    error = Cores.Falha,
    outline = Cores.Traco,
)

/**
 * **Tema único, sempre escuro.**
 *
 * Não há variante clara, e isso é escolha e não omissão. O aparelho é usado em
 * viatura à noite, em ronda, com o agente adaptado ao escuro; uma tela branca
 * queima a visão noturna dele por minutos, e recuperá-la custa mais que qualquer
 * conveniência de leitura ao sol. Onde a luz do dia atrapalha, a resposta certa é
 * o áudio — que é a saída principal do produto de qualquer forma.
 */
@Composable
fun TemaClaryon(content: @Composable () -> Unit) {
    // `isSystemInDarkTheme` é lido de propósito e ignorado: registra que a
    // decisão de não seguir o sistema foi tomada, não esquecida.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = EsquemaTatico,
        typography = Typography(
            bodyLarge = Tipo.Corpo,
            bodyMedium = Tipo.CorpoMenor,
            labelSmall = Tipo.Etiqueta,
            titleLarge = Tipo.Titulo,
        ),
        content = content,
    )
}
