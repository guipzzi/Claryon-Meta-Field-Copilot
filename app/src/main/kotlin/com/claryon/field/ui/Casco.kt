package com.claryon.field.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.IconeDeAba
import com.claryon.field.ui.componentes.IconeTatico
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.icones.Icones
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.duracaoAcessivel

/** As três telas do aparelho. O rascunho do produto pede exatamente estas. */
enum class Destino(val etiqueta: String, val icone: ImageVector) {
    GUARNICAO("Guarnição", Icones.Radio),
    MAPA("Mapa", Icones.Mapa),
    PERFIL("Perfil", Icones.Perfil),
}

/**
 * **Casco da aplicação: barra inferior de navegação e a moldura de "no ar".**
 *
 * A moldura é o compromisso visual mais forte do produto. Enquanto o agente
 * transmite, uma linha âmbar de 2 px contorna a tela **inteira**, por cima de
 * qualquer conteúdo e de qualquer aba. Existe por uma razão específica: uma
 * transmissão acidental difunde a fala do agente *e de quem está ao lado dele*
 * para a guarnição toda. Um ícone de 24 dp não é aviso proporcional a isso.
 *
 * A moldura também não desaparece ao trocar de aba — porque o risco não
 * desaparece. É o único elemento da interface que ignora a navegação.
 */
@Composable
fun CascoTatico(
    destino: Destino,
    aoNavegar: (Destino) -> Unit,
    noAr: Boolean,
    conteudo: @Composable (Modifier) -> Unit,
) {
    // **O sinal do piso, nos dois sentidos.** Migrado do `tween(180)` digitado à
    // mão para os tokens que existiam sem chamador nenhum desde que foram escritos.
    //
    // `noAr` vem de `RadioViewModel._noAr`, que só é ligado depois de o rádio
    // assumir a fala — é estado do RÁDIO, não do dedo, que é a condição que o KDoc
    // de `PisoConcedido` impõe para ele poder ser composto.
    //
    // Acender usa `PisoConcedido` e apagar usa `PisoNegado`, que é mais rápido: uma
    // moldura âmbar que sai devagar diz "talvez você ainda esteja no ar", e essa é
    // a ambiguidade que a moldura existe para não ter.
    val intensidade by animateFloatAsState(
        targetValue = if (noAr) 1f else 0f,
        animationSpec = if (noAr) Movimento.PisoConcedido() else Movimento.PisoNegado(),
        label = "moldura-no-ar",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Cores.Vazio)
            .drawBehind {
                if (intensidade <= 0.01f) return@drawBehind
                val esp = 2.dp.toPx()
                drawRect(
                    color = Cores.NoAr.copy(alpha = intensidade),
                    topLeft = Offset.Zero,
                    size = Size(size.width, esp),
                )
                drawRect(
                    color = Cores.NoAr.copy(alpha = intensidade),
                    topLeft = Offset(0f, size.height - esp),
                    size = Size(size.width, esp),
                )
                drawRect(
                    color = Cores.NoAr.copy(alpha = intensidade),
                    topLeft = Offset.Zero,
                    size = Size(esp, size.height),
                )
                drawRect(
                    color = Cores.NoAr.copy(alpha = intensidade),
                    topLeft = Offset(size.width - esp, 0f),
                    size = Size(esp, size.height),
                )
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            conteudo(Modifier.weight(1f))
            BarraDeNavegacao(destino, aoNavegar)
        }
    }
}

/**
 * **Navegação por ícone e rótulo.**
 *
 * ## O que mudou aqui, e por que a regra anterior caiu
 *
 * Este KDoc dizia *"navegação por rótulo, sem ícone"*, e defendia a ausência com
 * dois argumentos: que ícone de "guarnição" ou de "perfil" é metáfora que alguém
 * tem de aprender, e que um trio de pictogramas seria a única linguagem diferente
 * num painel feito de texto e fios. A decisão tinha precedência sobre gosto, então
 * ela é refutada por escrito, e não apagada.
 *
 * O primeiro argumento continua verdadeiro e deixou de importar: **o rótulo não
 * saiu**. Ícone *em vez de* palavra obrigaria a aprender a metáfora; ícone *acima*
 * da palavra não obriga a nada — a palavra ensina o ícone na primeira olhada, e da
 * segunda em diante é a silhueta que o polegar acha, porque forma se reconhece na
 * visão periférica e texto de 10 sp não.
 *
 * O segundo argumento estava errado no fato: o painel **não** é feito só de texto e
 * fios. A marca do produto já é geometria desenhada
 * ([com.claryon.field.ui.marca.GeometriaDaMarca]), e os ícones nascem da mesma
 * regra que ela — contorno sem preenchimento, ponta reta, junta em esquadro. Eles
 * não são uma linguagem a mais; são a linguagem que já existia, aplicada onde
 * faltava. Ver [com.claryon.field.ui.icones.Icones].
 *
 * A ordem vertical é **fio → ícone → rótulo**, e não a do Material (ícone com o
 * rótulo abaixo e o realce por trás). O fio no topo é o vocabulário desta
 * interface, é o que já marcava a aba selecionada, e mantê-lo acima preserva a
 * leitura de instrumento: a marca de seleção fica na régua, não em volta do
 * conteúdo.
 *
 * O selecionado continua marcado por fio e por tinta, **nunca por cor**: a cor
 * está reservada a transmissão e a prioridade, e aba escolhida não é nem uma coisa
 * nem outra. O ícone não selecionado sai em `TintaFraca` (5,15:1 sobre `Painel`) e
 * o selecionado em `Tinta` (15,88:1) — o mesmo par do rótulo, para que os dois
 * canais digam a mesma coisa.
 */
@Composable
private fun BarraDeNavegacao(destino: Destino, aoNavegar: (Destino) -> Unit) {
    // Migrado do literal `180` para o token: a barra usava um `tween` com duração
    // digitada e a curva padrão do Compose, então ela era o único movimento do
    // aplicativo fora do vocabulário de `Movimento` — e o único que ignorava a
    // preferência de acessibilidade do aparelho.
    //
    // A espessura do indicador continua literal, e de propósito: `Regua.FioDeSinal`
    // tem escopo escrito — *"só a moldura e o topo da barra de PTT no ar"* —, e
    // amarrar a aba nele faria aquele KDoc mentir no dia em que alguém mexesse num
    // dos dois.
    //
    // `Morfose` e não `Saida`: o fio já está na tela e muda de tamanho, que é a
    // definição de morfose no vocabulário deste projeto. `CURTO` e não `MEDIO`
    // porque trocar de aba é gesto frequente, e o teto para o frequente é o curto.
    val duracao = duracaoAcessivel(Movimento.CURTO)

    Column(Modifier.fillMaxWidth().background(Cores.Painel)) {
        Fio()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (d in Destino.entries) {
                val selecionado = d == destino
                val cor = if (selecionado) Cores.Tinta else Cores.TintaFraca
                Column(
                    Modifier
                        .weight(1f)
                        .tocavel { aoNavegar(d) }
                        .padding(vertical = Espaco.Medio),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Fio indicador: cresce em vez de aparecer. O movimento
                    // curto amarra o toque à mudança de tela sem custar atenção.
                    val largura by animateDpAsState(
                        targetValue = if (selecionado) 28.dp else 0.dp,
                        animationSpec = tween(duracao, easing = Movimento.Morfose),
                        label = "indicador-aba",
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(largura)
                            .background(Cores.Tinta),
                    )
                    Box(Modifier.height(Espaco.Medio))
                    IconeTatico(d.icone, cor = cor, tamanho = IconeDeAba)
                    Box(Modifier.height(Espaco.Curto))
                    Etiqueta(d.etiqueta, cor = cor)
                }
            }
        }
    }
}
