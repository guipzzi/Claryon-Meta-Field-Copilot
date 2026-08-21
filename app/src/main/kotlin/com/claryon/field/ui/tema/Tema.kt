package com.claryon.field.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 *
 * ---
 * ### Algarismo tabular, e por que ele está declarado mesmo sendo redundante hoje
 *
 * `FontFamily.Monospace` já é tabular por construção — todo glifo tem a mesma
 * largura, algarismo inclusive. Mesmo assim os estilos numéricos declaram
 * [TABULAR] explicitamente, por duas razões:
 *
 *  1. **A sans não é tabular**, e ela carrega número em prosa ("a 400 metros",
 *     "e mais 7 pontos"). Sem `tnum`, uma contagem que sobe de 9 para 10 empurra
 *     o resto da linha, e um dado que se mexe sozinho parece dado que mudou.
 *  2. **O dia em que uma fonte própria entrar no APK**, a garantia de largura
 *     fixa some junto com `FontFamily.Monospace` — e some em silêncio, sem
 *     quebrar build nem teste. A declaração sobrevive à troca.
 *
 * Verificado por `javap` em `ui-text 1.6.8`: `TextStyle` expõe
 * `getFontFeatureSettings()` e o construtor aceita o `String` correspondente.
 */
object Tipo {

    private val Mono = FontFamily.Monospace
    private val Sans = FontFamily.SansSerif

    /**
     * Algarismo de largura fixa e altura de linha (`lnum`).
     *
     * Indicativo, distância, rumo e idade alinham por coluna — é o que faz a
     * tela ler como instrumento e não como aplicativo de mensagem.
     */
    const val TABULAR = "tnum, lnum"

    /** Indicativo do agente. O nome pelo qual ele existe no rádio. */
    val Indicativo = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.02.em,
        fontFeatureSettings = TABULAR,
    )

    /** Indicativo em destaque — cabeçalho de conversa, cartão de perfil. */
    val IndicativoGrande = TextStyle(
        fontFamily = Mono,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR,
    )

    /** Horário, canal, coordenada, distância. Tabular por natureza. */
    val Dado = TextStyle(
        fontFamily = Mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.02.em,
        fontFeatureSettings = TABULAR,
    )

    /**
     * **Valor de linha de livro-razão.** Alinhado à direita, sempre.
     *
     * É a metade direita da linha `rótulo … valor`. A borda direita comum é o
     * que permite comparar oito distâncias sem ler oito vezes; um valor
     * centralizado ou colado no rótulo destrói a coluna e com ela a comparação.
     */
    val Valor = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR,
        textAlign = TextAlign.End,
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
        fontFeatureSettings = TABULAR,
    )

    val CorpoMenor = TextStyle(
        fontFamily = Sans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR,
    )

    /**
     * Título de tela. Sans, porque é linguagem, não dado.
     *
     * Continua existindo para a marca "CLARYON" e para o cabeçalho de coluna à
     * esquerda. Para o cabeçalho de tela, use [TituloDeTela].
     */
    val Titulo = TextStyle(
        fontFamily = Sans,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
    )

    /**
     * **Cabeçalho de tela: caixa-alta, entreletra larga, centralizado.**
     *
     * O título deixa de ser manchete e vira etiqueta de instrumento. A entreletra
     * de 0,18 em é o que separa "nome de tela" de "frase" — a mesma razão pela
     * qual um painel escreve `VEHICLE STATUS` e não `Vehicle Status`: em caixa
     * alta espaçada o olho reconhece a **forma do bloco** sem ler as letras, e
     * quem já esteve na tela não lê o título nenhuma vez depois da primeira.
     *
     * Pequeno de propósito. Um título de 26 sp compete com o dado; este recua.
     */
    val TituloDeTela = TextStyle(
        fontFamily = Mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.18.em,
        textAlign = TextAlign.Center,
    )

    /**
     * **Número herói.** Grande e leve — o dado principal de uma tela.
     *
     * Leve e não pesado: em corpo grande, o peso vira mancha e a mancha vira
     * ênfase que não foi pedida. O tamanho já é toda a hierarquia de que ele
     * precisa; a espessura fina é o que faz parecer mostrador em vez de anúncio.
     *
     * O rótulo vai **acima** e menor ([Etiqueta]), nunca abaixo: quem olha um
     * painel precisa saber o que é antes de saber quanto é.
     */
    val Heroi = TextStyle(
        fontFamily = Mono,
        fontSize = 44.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR,
    )

    /** Unidade que acompanha o [Heroi] — "km", "m", "min". Nunca do mesmo tamanho. */
    val HeroiUnidade = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.04.em,
    )

    /** Ação: rótulo de botão. Caixa-alta curta, como comando. */
    val Acao = TextStyle(
        fontFamily = Mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.em,
    )
}

/**
 * **Escala de espaçamento.** Múltiplos de 4, nomeados pelo uso.
 *
 * Conferida contra a regra dos 40% (`rad-spacing`): cada nível é ~1,4× o filho,
 * arredondado para o incremento de 8 mais próximo — ou o de 4, quando ele fica
 * mais perto. Partindo de [Curto] = 8:
 *
 * | nível | cálculo | valor |
 * |---|---|---|
 * | [Curto] | base | 8 |
 * | [Medio] | 8 × 1,4 = 11,2 | 12 |
 * | [Padrao] | 12 × 1,4 = 16,8 | 16 |
 * | [Largo] | 16 × 1,4 = 22,4 | 24 |
 * | [Secao] | 24 × 1,4 = 33,6 | 32 |
 * | [Bloco] | 32 × 1,4 = 44,8 | 48 |
 *
 * **A escala que já existia estava certa** e não foi mexida — a auditoria a
 * conferiu contra a regra e ela bate em todos os degraus. [Bloco] é o único
 * acréscimo, e é o teto: acima dele a regra manda comprimir em vez de continuar
 * multiplicando.
 */
object Espaco {
    val Fio = 1.dp
    val Micro = 4.dp
    val Curto = 8.dp
    val Medio = 12.dp
    val Padrao = 16.dp
    val Largo = 24.dp
    val Secao = 32.dp

    /** Teto da escala. Separa blocos de tela, nunca itens de lista. */
    val Bloco = 48.dp
}

/**
 * **A régua do fio e da linha.**
 *
 * Espessura e altura são gramática, não espaçamento — por isso vivem separadas de
 * [Espaco]. Um fio de 1 px e um fio de 2 px dizem coisas diferentes neste
 * produto, e a diferença precisava de nome antes de precisar de valor.
 *
 * Os três valores de [MarcaP1]/[MarcaP2]/[MarcaP3] estavam **duplicados por
 * digitação** em `Comuns.kt` e `TelaDeGuarnicao.kt` — dois lugares com os mesmos
 * `4.dp/3.dp/2.dp`, sem referência entre eles. Um canal de acessibilidade que
 * depende de dois literais concordarem não é um canal; é uma coincidência.
 */
object Regua {

    /** Fio de estrutura. A interface inteira é construída disto. */
    val Fio = 1.dp

    /** Fio que virou sinal — só a moldura e o topo da barra de PTT no ar. */
    val FioDeSinal = 2.dp

    /**
     * **Altura de linha do livro-razão.** Ícone → rótulo → valor à direita.
     *
     * Generosa de propósito: o ar entre as linhas é o que faz a tela parecer
     * instrumento em vez de lista. Também é o que permite tocar com luva sem
     * mirar, num alvo que já cumpre o mínimo sozinho.
     */
    val Linha = 56.dp

    /** Linha densa — histórico do canal, onde a quantidade importa mais que o ar. */
    val LinhaDensa = 44.dp

    /** Alvo mínimo tocável. Com luva, no banco de uma viatura em movimento. */
    val Toque = 48.dp

    /** Ponto de estado. */
    val Ponto = 7.dp

    // ── Marcas de prioridade: cor E largura carregam o mesmo dado ─────────────

    /**
     * Cerca de 8% dos homens têm alguma deficiência de visão de cor, e a
     * proporção não é menor entre agentes de segurança. Quem não distingue
     * vermelho de âmbar ainda distingue grosso de fino — e desde que `P3` virou
     * cinza, a largura é o canal **principal** entre P2 e P3, não o reforço.
     */
    val MarcaP1 = 4.dp
    val MarcaP2 = 3.dp
    val MarcaP3 = 2.dp
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
