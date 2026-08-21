package com.claryon.field.ui.componentes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.Espaco

// **O vocabulário de movimento NÃO mora aqui.**
//
// Este arquivo teve um `object Movimento` próprio por algumas horas em 21/08,
// porque `ui/tema/` não tinha nenhum quando esta tela foi desenhada. Enquanto isso
// acontecia, outro agente definia `ui/tema/Movimento.kt` — e os dois chegaram, sem
// se falarem, às MESMAS durações (120/180/240) e às MESMAS duas curvas de Bézier,
// bit a bit: (0.165, 0.84, 0.44, 1) e (0.645, 0.045, 0.355, 1).
//
// A convergência é um bom sinal sobre as escolhas, e uma duplicata é uma duplicata:
// dois lugares para o mesmo número divergem no primeiro ajuste. Movimento é
// vocabulário do SISTEMA, não de uma tela. Ficou em `ui/tema/Movimento.kt`.
//
// O mapa, para quem procurar o nome antigo:
//   RAPIDO_MS 120 → Movimento.MICRO      ENTRADA → Movimento.Saida
//   ENTRADA_MS 180 → Movimento.CURTO     MORFOSE → Movimento.Morfose
//   MORFOSE_MS 240 → Movimento.MEDIO

/**
 * **Calha do bloco — faixa vertical na borda esquerda, desenhada e não medida.**
 *
 * `drawBehind` e não um `Box` irmão com `fillMaxHeight`: a altura de um irmão
 * dentro de `Row` exige `IntrinsicSize`, que custa **uma medida extra por item**
 * numa lista que rola. A versão anterior fugia disso fixando 44 dp de altura, o
 * que deixava a calha curta em toda fala de duas linhas. Desenhar resolve os dois
 * — acompanha a altura real e não mede nada.
 *
 * [tracejada] é o canal **não cromático** da procedência. Cor já significa
 * prioridade (P1/P2/P3) e transmissão (âmbar); uma quarta família cromática faria
 * as três perderem sentido. Tracejado é geometria: sobrevive a daltonismo, a sol
 * forte e a captura em preto e branco.
 */
fun Modifier.calha(cor: Color, largura: Dp, tracejada: Boolean = false): Modifier =
    this.drawBehind {
        val w = largura.toPx()
        if (!tracejada) {
            drawRect(color = cor, topLeft = Offset.Zero, size = Size(w, size.height))
            return@drawBehind
        }
        // Traço de 6 px, vão de 5 px: curto o suficiente para a interrupção ser
        // legível em 44 dp de bloco, e longo o suficiente para não virar
        // pontilhado — que a essa escala se lê como linha contínua.
        drawLine(
            color = cor,
            start = Offset(w / 2f, 0f),
            end = Offset(w / 2f, size.height),
            strokeWidth = w,
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
        )
    }

/**
 * **Contorno tracejado — a fala cuja autoria não fechou, desenhada como não fecha.**
 *
 * Substitui a faixa de largura total que este aviso ocupava até 21/08. A faixa era
 * uma **caixa**, e o sistema deste projeto diz fio de 1 px; pior, ela empilhava um
 * retângulo cheio em cima de outro retângulo cheio, que é a origem do "bruto" que
 * a tela tinha. O sinal não perdeu nada ao mudar de meio: continua **geometria**,
 * continua sem cor própria, e continua sobrevivendo a daltonismo, sol forte e
 * captura em preto e branco.
 *
 * O traço fecha o balão inteiro em vez de ocupar uma faixa dele, e a leitura fica
 * literal — **é a única fala da tela cujo perímetro não fecha.** Nenhuma outra
 * pode tomar esta forma, que é a mesma regra que protege o âmbar do "no ar".
 *
 * Desenhado para **dentro** por meia espessura: sob um `clip` arredondado, um
 * traço centrado na borda perderia a metade externa e viraria fio fino irregular.
 */
fun Modifier.contornoTracejado(cor: Color, canto: Dp, largura: Dp = 1.dp): Modifier =
    this.drawBehind {
        val w = largura.toPx()
        val r = canto.toPx()
        drawRoundRect(
            color = cor,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)),
        )
    }

/**
 * **Procedência — o topo do balão cuja autoria não fechou.**
 *
 * Estruturalmente é o lugar que um aplicativo de mensagens usa para a citação da
 * resposta. Aqui não há resposta a citar — tráfego de rádio não tem relação de
 * réplica, e `transmissions` não guarda nenhuma. O que ocupa a fatia é o que este
 * produto tem e um mensageiro não tem: **de onde a fala veio, e se dá para
 * confiar nisso.**
 *
 * Era `FaixaDeProcedencia` e desenhava uma faixa cheia de ponta a ponta do balão.
 * Agora mora dentro do preenchimento, sem fundo próprio, e o cerco fica por conta
 * de [contornoTracejado]. Tinta média e nenhuma cor de sinal — o aviso é sobre a
 * atribuição, não sobre urgência. Quem lê precisa saber que o **nome** é duvidoso;
 * o conteúdo pode ser um pedido de apoio real e continua legível em tinta cheia.
 *
 * Caixa-alta fica no rótulo: é um dos poucos casos genuinamente excepcionais da
 * tela, e o critério para gritar é esse.
 *
 * ---
 * ### A segunda linha: responder "quem", quando a resposta é "ninguém"
 *
 * O bloco mostrava só o aviso, e quem olhava ficava sem saber se o nome tinha sido
 * omitido pela tela ou se ele não existia. São coisas diferentes e levam a
 * decisões diferentes.
 *
 * A resposta que este produto tem é a segunda, e ela é literal: `HistoricoDoCanal`
 * lê `agents.indicativo` pelo `join` de autoria, e quando o vínculo não fecha o
 * campo chega **vazio**. Não há nome a esconder — há nome que não existe. É isso
 * que [explicacao] escreve, e o bloco deixa de fingir que guarda um segredo.
 *
 * Prosa e não etiqueta: é frase, e a `Etiqueta` de 0,16 em já está gasta na linha
 * de cima. Tinta média, 7,01:1 sobre o pior fundo.
 */
@Composable
fun BlocoDeProcedencia(
    rotulo: String,
    explicacao: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Barra curta tracejada, o mesmo vocabulário da calha e do contorno:
            // três lugares dizendo a mesma coisa pelo mesmo meio, que é como um
            // sinal vira hábito.
            Box(
                Modifier
                    .width(10.dp)
                    .height(2.dp)
                    .calha(Cores.TintaMedia, largura = 10.dp, tracejada = true),
            )
            Box(Modifier.width(Espaco.Curto))
            Etiqueta(rotulo, cor = Cores.TintaMedia)
        }
        Box(Modifier.height(Espaco.Micro))
        TextoCorpoMenor(explicacao, cor = Cores.TintaMedia)
    }
}

/**
 * **Voltar ao fim do histórico.**
 *
 * Existe por causa de uma decisão anterior, não apesar dela: `deveRolarParaOFim`
 * **recusa** arrastar a leitura de quem subiu para procurar o que perdeu. Certo —
 * mas até aqui não havia caminho de volta, e o agente que subiu ficava rolando
 * com o polegar enquanto o canal andava. A regra criou a necessidade; isto é a
 * porta.
 *
 * **O número é medido, não estimado.** [registrosAbaixo] é a distância entre o
 * último item visível e o fim da lista — quantos registros existem abaixo da
 * leitura, agora. Não diz "novas": este aparelho não sabe o que o agente já leu,
 * e um contador de não-lidas seria a interface afirmando o que o dado não
 * sustenta.
 *
 * Alvo de 48 dp porque quem toca isto está dirigindo ou em pé numa abordagem.
 *
 * [canto] vem de fora e não de um valor próprio: é o mesmo raio dos balões, e o
 * controle flutua **por cima** deles. Dois raios diferentes na mesma tela é o tipo
 * de discordância que ninguém nomeia e todo mundo vê.
 */
@Composable
fun BotaoDeIrParaOFim(
    registrosAbaixo: Int,
    aoTocar: () -> Unit,
    canto: Dp,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = registrosAbaixo > 0,
        // Entra subindo de baixo, que é a direção de onde o conteúdo novo veio.
        // Movimento com origem, não aparição — a origem é a informação.
        enter = slideInVertically(
            animationSpec = tween(Movimento.CURTO, easing = Movimento.Saida),
            initialOffsetY = { it },
        ) + fadeIn(tween(Movimento.CURTO, easing = Movimento.Saida)),
        exit = slideOutVertically(
            animationSpec = tween(Movimento.MICRO, easing = Movimento.Saida),
            targetOffsetY = { it },
        ) + fadeOut(tween(Movimento.MICRO)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(canto))
                .background(Cores.Pressionado)
                .tocavel(aoTocar = aoTocar)
                .padding(horizontal = Espaco.Padrao)
                .semantics {
                    contentDescription = "Ir para o fim do histórico. $registrosAbaixo registros abaixo"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SetaParaBaixo()
            Box(Modifier.width(Espaco.Curto))
            // Sem caixa-alta: é um controle transitório, não um estado
            // excepcional. A seta já é o que o olho pega.
            TextoDado("$registrosAbaixo abaixo", cor = Cores.Tinta)
        }
    }
}

/**
 * **Chevron de "abre".** Dois fios, o mesmo vocabulário de [SetaParaBaixo].
 *
 * Existe porque uma porta sem maçaneta não é porta: o nome da guarnição virou
 * tocável, e sem esta marca o agente teria de descobrir isso por acidente — no
 * meio de uma ocorrência, que é quando ninguém explora interface.
 *
 * [Cores.TintaFraca] e não [Cores.Tinta]: é affordance, não dado. Ela precisa
 * estar presente e nunca disputar com o indicativo ao lado.
 */
@Composable
fun SetaParaDireita(modifier: Modifier = Modifier, cor: Color = Cores.TintaFraca) {
    Canvas(modifier.size(8.dp)) {
        val meio = size.height / 2f
        drawLine(
            color = cor,
            start = Offset(0f, 0f),
            end = Offset(size.width, meio),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawLine(
            color = cor,
            start = Offset(0f, size.height),
            end = Offset(size.width, meio),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

/**
 * Seta desenhada com dois fios, e não um glifo.
 *
 * O painel inteiro é feito de fios retos; um caractere de seta viria com a métrica
 * da fonte e o risco de o sistema resolver por emoji. Dois `drawLine` custam menos
 * e obedecem ao mesmo vocabulário do resto da tela.
 */
@Composable
private fun SetaParaBaixo() {
    Canvas(Modifier.size(10.dp)) {
        val meio = size.width / 2f
        drawLine(
            color = Cores.Tinta,
            start = Offset(meio, 0f),
            end = Offset(meio, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawLine(
            color = Cores.Tinta,
            start = Offset(0f, size.height * 0.55f),
            end = Offset(meio, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawLine(
            color = Cores.Tinta,
            start = Offset(size.width, size.height * 0.55f),
            end = Offset(meio, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}
