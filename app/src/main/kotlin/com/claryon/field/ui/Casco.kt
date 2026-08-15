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
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco

/** As três telas do aparelho. O rascunho do produto pede exatamente estas. */
enum class Destino(val etiqueta: String) {
    GUARNICAO("Guarnição"),
    MAPA("Mapa"),
    PERFIL("Perfil"),
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
    val intensidade by animateFloatAsState(
        targetValue = if (noAr) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
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
 * Navegação por rótulo, sem ícone.
 *
 * Ícone de "guarnição" ou de "perfil" é sempre uma metáfora que alguém tem de
 * aprender; a palavra já está no vocabulário do agente. E o painel inteiro é
 * feito de texto e fios — um trio de pictogramas seria o único lugar com
 * linguagem diferente.
 *
 * O selecionado é marcado por um fio âmbar-neutro acima do rótulo, não por cor de
 * texto: a cor está reservada, e o fio é o vocabulário desta interface.
 */
@Composable
private fun BarraDeNavegacao(destino: Destino, aoNavegar: (Destino) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cores.Painel)) {
        Fio()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (d in Destino.entries) {
                val selecionado = d == destino
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
                        animationSpec = tween(durationMillis = 180),
                        label = "indicador-aba",
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(largura)
                            .background(Cores.Tinta),
                    )
                    Box(Modifier.height(Espaco.Curto))
                    Etiqueta(
                        d.etiqueta,
                        cor = if (selecionado) Cores.Tinta else Cores.TintaFraca,
                    )
                }
            }
        }
    }
}
