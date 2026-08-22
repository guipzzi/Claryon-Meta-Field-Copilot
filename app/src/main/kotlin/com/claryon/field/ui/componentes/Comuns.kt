package com.claryon.field.ui.componentes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.Regua
import com.claryon.field.ui.tema.Tipo
import com.claryon.field.ui.tema.duracaoAcessivel

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

// ── Os três tamanhos de ícone ────────────────────────────────────────────────
//
// **Isto quer ser `Regua.IconeDeAcao/DeLinha/DeAba`**, e não está lá porque
// `ui/tema/` estava fora do território desta sessão. Vive aqui pelo mesmo padrão
// que `MARGEM_DO_LADO_OPOSTO` e `CANTO_DO_BLOCO` já usavam em
// `TelaDeGuarnicao.kt`: um símbolo, com o porquê ao lado, e nunca um literal
// repetido em duas telas.
//
// Os três degraus são deliberadamente **próximos** (1,1× entre eles), e por isso
// não seguem a escala de 1,4× de `Espaco`: espaçamento separa blocos, mas ícone
// acompanha o texto ao lado dele. Cada tamanho é o corpo do estilo que ele
// acompanha, arredondado para par:
//
//  - [IconeDeAcao] 18 dp — ao lado de `Tipo.Acao` (13 sp, caixa-alta).
//  - [IconeDeLinha] 20 dp — na frente de linha de dado, com `Tipo.Corpo` (15 sp).
//  - [IconeDeAba] 22 dp — sozinho **acima** do rótulo da aba, onde não há texto
//    na mesma linha para dar escala e o ícone é a metade que se vê de relance.

/** Ícone dentro de botão ou pílula. */
val IconeDeAcao: Dp = 18.dp

/** Ícone na frente de uma linha de dado. */
val IconeDeLinha: Dp = 20.dp

/** Ícone da barra de abas — o único que fica sozinho. */
val IconeDeAba: Dp = 22.dp

/**
 * Ícone de contorno, pintado por quem o usa.
 *
 * `contentDescription = null` **sempre**, e é decisão e não esquecimento: neste
 * sistema o ícone nunca aparece sozinho — há rótulo de aba, título de linha ou
 * verbo de botão dizendo a mesma coisa ao lado. Descrição aqui faria o leitor de
 * tela repetir cada item. Ver o KDoc de [com.claryon.field.ui.icones.Icones].
 */
@Composable
fun IconeTatico(
    icone: ImageVector,
    cor: Color = Cores.TintaMedia,
    tamanho: Dp = IconeDeLinha,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icone,
        contentDescription = null,
        tint = cor,
        modifier = modifier.size(tamanho),
    )
}

/**
 * Ponto de estado.
 *
 * Quando [pulsando], respira devagar — usado só para "está no ar agora". A
 * pulsação é cara em atenção; reservá-la a uma coisa faz dela informação em vez
 * de enfeite.
 *
 * O tamanho padrão é [Regua.Ponto] e não um `7.dp` próprio: o literal duplicava o
 * token, palavra por palavra, num arquivo que não o importava. A duração e a curva
 * saíram do `tween(900)` digitado aqui para [Movimento.RespiroDePresenca] — o KDoc
 * de lá explica por que este é o único movimento perpétuo que o sistema aceita, e
 * por que ele sobreviveu à remoção do pulso do "no ar" em 22/08.
 */
@Composable
fun PontoDeEstado(
    cor: Color,
    tamanho: Dp = Regua.Ponto,
    pulsando: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pulso by rememberInfiniteTransition(label = "pulso").animateFloat(
        initialValue = ALFA_MINIMO_DO_RESPIRO,
        targetValue = 1f,
        animationSpec = Movimento.RespiroDePresenca(),
        label = "alfa-pulso",
    )
    val alfa = if (pulsando) pulso else 1f

    Box(modifier.size(tamanho).clip(CircleShape).background(cor.copy(alpha = alfa)))
}

/**
 * O fundo do respiro do ponto de presença.
 *
 * 0,35 e não 0: o ponto **não pode sumir** no vale da respiração. Quem olha de
 * relance no vale leria "ninguém no ar", que é o oposto do que o ponto está ali
 * para dizer — e a visão periférica, que é a que capta movimento, é justamente a
 * que pega o quadro errado.
 */
private const val ALFA_MINIMO_DO_RESPIRO = 0.35f

/**
 * Faixa de prioridade — fio vertical na borda esquerda.
 *
 * Cor **e** largura carregam o mesmo dado, de propósito: cerca de 8% dos homens
 * têm alguma deficiência de visão de cor, e a proporção não é menor entre agentes
 * de segurança. A faixa é mais larga em P1: quem não distingue vermelho de âmbar
 * ainda distingue grosso de fino.
 *
 * **Atenção — isto não tem chamador em `src/main`.** `grep -rn "FaixaDePrioridade"
 * app/src` devolve só esta definição; a tela da guarnição desenha a sua faixa por
 * `Modifier.calha`, que acompanha a altura real do bloco sem custar uma medida
 * extra por item de lista. Pelo critério deste projeto, isto é **escrito, não
 * construído**, e ou ganha chamador ou sai. Fica por ora porque os números que ele
 * repetia — os mesmos `4/3/2` de `larguraDaCalha` — eram exatamente a duplicata que
 * [Regua] nasceu para matar, e deixá-lo com literais próprios manteria viva a
 * chance de os dois lados divergirem no primeiro ajuste.
 */
@Composable
fun FaixaDePrioridade(prioridade: Int, modifier: Modifier = Modifier) {
    val (cor, largura) = when (prioridade) {
        1 -> Cores.P1 to Regua.MarcaP1
        2 -> Cores.P2 to Regua.MarcaP2
        else -> Cores.P3 to Regua.MarcaP3
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
 *
 * ---
 * ### O que esta regra cobre hoje, depois de duas revisões no mesmo dia
 *
 * **21/08, manhã:** os balões da guarnição ganharam raio, por decisão humana. A
 * regra recuou para o controle: *"fio, botão e barra continuam retos, e o canto é
 * vocabulário de conteúdo — o que tem começo e fim é o balão, não a ação"*.
 *
 * **21/08, tarde:** a barra de composição também ganhou raio, por decisão humana
 * — a pílula de consulta ao copiloto e o bloco de fala do PTT, na leitura de que a
 * faixa de entrada é a peça que um agente reconhece de outro aplicativo e que ela
 * não estava parecendo campo de entrada nenhum.
 *
 * O escopo que sobrou é este, e ele é o texto, não a lembrança dele: **`BotaoTatico`
 * é reto.** Ele é o botão de ação de tela cheia — "Encerrar turno", "Permitir" —, e
 * é o único controle que continua com canto zero. `Fio` e a barra de navegação
 * também. Não há regra geral de "controle é reto" para citar; há estes três.
 *
 * Se um dia `BotaoTatico` arredondar, arredonda por diff de spec, não por contágio.
 *
 * ---
 * ### O estado desabilitado, medido — e o que estava errado nele
 *
 * O botão desabilitado era `TintaFraca` sobre `Elevado`: **4,70:1**, que passa em
 * AA por 0,2 e mesmo assim era o defeito que a captura do emulador denunciava. O
 * número certo para olhar não era o do texto: era o da **caixa**. `Elevado`
 * (`#1E1E1E`) sobre `Vazio` (`#0C0C0C`) rende **1,17:1** — menos que o fio de 1 px
 * que a paleta já registra como exceção. A borda do controle simplesmente não
 * existia: o que se via na tela de login era uma mancha cinza mal descolada do
 * fundo, com letra cinza dentro. Não parecia apagado; parecia quebrado.
 *
 * Duas correções, e as duas têm número:
 *
 *  1. O rótulo sobe de `TintaFraca` para `TintaMedia`: **4,70 → 7,01:1**. WCAG
 *     dispensa controle desabilitado de contraste mínimo; esta é uma tela de
 *     abertura de turno, e o agente precisa **ler** o que ainda falta.
 *  2. O contorno passa a ser desenhado em `TracoForte` (1 px). Ele não conserta o
 *     contraste — `TracoForte` sobre `Vazio` é **1,72:1**, e a paleta registra que
 *     fio abaixo de 3:1 é exceção consciente deste sistema. O que ele conserta é a
 *     **forma**: com quatro lados marcados, o retângulo volta a ser um controle
 *     desligado em vez de uma área morta, e é a mesma gramática de fio que o resto
 *     do painel usa.
 *
 * O estado habilitado já estava certo e não mudou: `Vazio` sobre `Tinta` é
 * **17,01:1** — o par de maior contraste que esta paleta tem.
 *
 * O destrutivo ganhou o mesmo contorno neutro, pela mesma razão (antes era texto
 * solto sobre o fundo, sem limite nenhum), e o rótulo saiu de `Falha` para
 * `FalhaTexto`: **4,93 → 7,93:1** sobre `Vazio`. `Falha` é token de **marca**, e
 * "ENCERRAR TURNO" é palavra — a distinção está escrita na paleta. O contorno é
 * neutro de propósito: cor em elemento permanente é cor que deixou de significar, e
 * este botão fica na tela o turno inteiro.
 */
@Composable
fun BotaoTatico(
    rotulo: String,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    destrutivo: Boolean = false,
    icone: ImageVector? = null,
) {
    val corTexto = rotuloDoBotao(habilitado, destrutivo)
    val corFundo = fundoDoBotao(habilitado, destrutivo)
    // O preenchido não leva contorno: ele já tem limite de sobra (17,01:1 contra o
    // fundo). Contorno em cima de fundo claro só somaria uma linha que ninguém vê.
    val comContorno = !habilitado || destrutivo

    Row(
        modifier
            .fillMaxWidth()
            .background(corFundo)
            .then(
                if (comContorno) {
                    Modifier.border(Regua.Fio, Cores.TracoForte, RectangleShape)
                } else {
                    Modifier
                },
            )
            .tocavel(habilitado = habilitado, aoTocar = aoTocar)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icone != null) {
            IconeTatico(icone, cor = corTexto, tamanho = IconeDeAcao)
            Box(Modifier.width(Espaco.Medio))
        }
        Text(rotulo.uppercase(), style = Tipo.Acao, color = corTexto)
    }
}

/**
 * **A escolha de cor do [BotaoTatico], como função pura.**
 *
 * Extraída do corpo do composable por uma razão que este projeto já pagou caro
 * para aprender: enquanto a decisão morava dentro do `@Composable`, o teste de
 * contraste só conseguia afirmar coisas sobre os **tokens** — *"`TintaMedia` sobre
 * `Elevado` dá 7,01"* —, e isso continuaria verdadeiro depois de alguém devolver
 * `TintaFraca` ao botão. Medido: com a regressão reintroduzida de propósito no
 * `Comuns.kt`, `OrcamentoDeCorTest` passava. Um teste que passa com o defeito de
 * volta não testa o defeito.
 *
 * Agora o teste chama estas duas funções com os três estados reais e mede o par que
 * o botão de fato desenha.
 *
 * `internal` e não público: quem desenha botão usa [BotaoTatico]; quem precisa da
 * decisão isolada é o teste.
 */
internal fun rotuloDoBotao(habilitado: Boolean, destrutivo: Boolean): Color = when {
    !habilitado -> Cores.TintaMedia
    destrutivo -> Cores.FalhaTexto
    else -> Cores.Vazio
}

/**
 * O fundo correspondente a [rotuloDoBotao].
 *
 * O destrutivo devolve [Color.Transparent], e o teste tem de saber disso: o fundo
 * **efetivo** dele é o da tela ([Cores.Vazio]), não a transparência.
 */
internal fun fundoDoBotao(habilitado: Boolean, destrutivo: Boolean): Color = when {
    !habilitado -> Cores.Elevado
    destrutivo -> Color.Transparent
    else -> Cores.Tinta
}

/**
 * **Pílula de ação: contorno, ícone e rótulo.**
 *
 * A ação secundária deste sistema não tinha forma nenhuma — era `Etiqueta`
 * tocável, ou um `Button` do Material que não pertencia a lugar nenhum. A
 * referência do produto resolve isso com pílula de contorno, e é o que esta é.
 *
 * A diferença para [BotaoTatico] é de **peso**, e ela é dita por três canais ao
 * mesmo tempo, não por um: fundo (preenchido × vazado), forma (reto × pílula) e
 * cor de rótulo (`Vazio` sobre claro × `TintaMedia` sobre o fundo da tela,
 * **8,23:1**). Numa tela com as duas, o polegar acha a primária sem ler.
 *
 * O canto arredondado **não** contraria o que [BotaoTatico] tem escrito: aquela
 * regra nomeia três controles que continuam retos — o próprio `BotaoTatico`, o
 * `Fio` e a barra de navegação. A pílula segue o precedente que a barra de
 * composição da guarnição abriu, por decisão humana, no dia 21/08.
 *
 * [Regua.Toque] de altura mínima, como todo alvo deste produto: quem toca isto está
 * de luva, em pé, numa abordagem.
 */
@Composable
fun PilulaDeAcao(
    rotulo: String,
    icone: ImageVector,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    val cor = if (habilitado) Cores.TintaMedia else Cores.TintaFraca
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Regua.Toque)
            .clip(PILULA)
            .border(Regua.Fio, if (habilitado) Cores.TracoForte else Cores.Traco, PILULA)
            .tocavel(habilitado = habilitado, forma = PILULA, aoTocar = aoTocar)
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconeTatico(icone, cor = cor, tamanho = IconeDeAcao)
        Box(Modifier.width(Espaco.Medio))
        Text(rotulo.uppercase(), style = Tipo.Acao, color = cor)
    }
}

/**
 * Pílula — canto totalmente arredondado.
 *
 * `percent` e não `Dp`: a pílula acompanha a altura, e um raio fixo deixaria as
 * pontas retas assim que a linha crescesse com a escala de fonte.
 *
 * **Isto é a segunda cópia deste símbolo** — a primeira é o `PILULA` privado de
 * `TelaDeGuarnicao.kt`, e o KDoc de lá já diz o que os dois querem ser:
 * `Regua.Pilula`. `ui/tema/` estava fora do território das duas sessões que
 * criaram as cópias. Quem mover isto para `Regua` apaga as duas de uma vez.
 */
private val PILULA = RoundedCornerShape(percent = 50)

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
 *
 * [forma] arredonda **o realce**, e nunca o conteúdo. É a diferença que custou uma
 * captura: `Modifier.clip(RoundedCornerShape(12.dp))` no bloco tocável do cabeçalho
 * comeu o "T" de `TALK GROUP`, porque o rótulo nasce exatamente no canto superior
 * esquerdo do nó e o arco passa por cima dele. Passar a forma ao fundo resolve os
 * dois lados: o realce ganha o mesmo raio dos balões e a tipografia fica intacta.
 *
 * ---
 * ### 22/08 — o retorno de toque ganhou tempo, e é assimétrico de propósito
 *
 * O realce era um `if` seco: aparecia e sumia no mesmo quadro. Isso é a ausência de
 * `Movimento` no lugar em que `Movimento.MICRO` está literalmente definido para
 * servir — *"retorno de toque, troca de cor, fio que cresce"* —, e [Movimento.MICRO]
 * não tinha um único chamador em `src/main` até este conserto. Este é **o** controle
 * tocável do sistema: cada botão, aba, pílula, bloco e linha de lista passa por
 * aqui, então ligar o token num lugar só liga a interface inteira.
 *
 * **A entrada é [Movimento.IMEDIATO] e a saída é [Movimento.MICRO], e a assimetria
 * é a informação.** Retorno de toque que chega 120 ms depois do dedo é retorno
 * atrasado, e atraso em painel se lê como aparelho travando — o oposto do que o
 * realce existe para dizer. Mas o rastro que **fica** depois de o dedo sair é o que
 * confirma *"foi aqui que eu toquei"* para quem está de luva, no banco de uma
 * viatura em movimento, e não viu o próprio toque. Descer na hora, subir com calma.
 *
 * Nada de otimismo aqui: o dedo é o único fato que este aparelho conhece com
 * certeza no instante do toque, e é sobre ele — e só sobre ele — que o realce
 * afirma alguma coisa. Ver a regra 2 no KDoc de [Movimento].
 */
@Composable
fun Modifier.tocavel(
    habilitado: Boolean = true,
    forma: Shape = RectangleShape,
    aoTocar: () -> Unit,
): Modifier {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()
    val realce = pressionado && habilitado
    val saida = duracaoAcessivel(Movimento.MICRO)
    val cor by animateColorAsState(
        targetValue = if (realce) Cores.Pressionado else Color.Transparent,
        animationSpec = tween(
            durationMillis = if (realce) Movimento.IMEDIATO else saida,
            easing = Movimento.Sutil,
        ),
        label = "realce-de-toque",
    )
    return this
        .background(color = cor, shape = forma)
        .clickable(
            interactionSource = interacao,
            indication = null,
            enabled = habilitado,
            onClick = aoTocar,
        )
}
