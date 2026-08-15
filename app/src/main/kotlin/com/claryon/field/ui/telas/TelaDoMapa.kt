package com.claryon.field.ui.telas

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.Frescor
import com.claryon.field.mapa.ParNoMapa
import com.claryon.field.ui.componentes.CabecalhoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.MapaDeRuas
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco

/**
 * **Mapa da guarnição.**
 *
 * O ciclo de vida desta tela **é** a regra de bateria. `ON_START` abre a
 * assinatura do canal de posições; `ON_STOP` fecha e descarta o espelho. Numa
 * guarnição de oito, manter a assinatura aberta seria tráfego permanente para uma
 * tela fechada 95% do turno.
 *
 * `ON_START`/`ON_STOP` e não `ON_RESUME`/`ON_PAUSE`: uma notificação por cima
 * dispara `ON_PAUSE` sem que o mapa deixe de estar visível, e fechar ali faria os
 * marcadores sumirem toda vez que uma mensagem chegasse.
 *
 * A representação é **lista ordenada por distância**, não cartografia. É uma
 * escolha, não uma etapa faltando: o dado que decide a ação do agente é
 * *distância, rumo e há quanto tempo* — três grandezas que a lista entrega
 * exatas, e que um ponto sobre um mapa entrega aproximadas e exigindo foco visual
 * que ele não tem para dar.
 */
@Composable
fun TelaDoMapa(
    estado: EstadoDoMapa,
    aoAbrir: () -> Unit,
    aoFechar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> aoAbrir()
                Lifecycle.Event.ON_STOP -> aoFechar()
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose {
            dono.lifecycle.removeObserver(observador)
            // Fechar também aqui: a tela pode sair da composição sem passar por
            // ON_STOP (troca de aba dentro da mesma Activity). Sem isto, a
            // assinatura sobreviveria à tela — vazamento de bateria e de
            // privacidade ao mesmo tempo.
            aoFechar()
        }
    }

    Column(modifier.fillMaxSize()) {
        CabecalhoTatico(
            etiqueta = "Posição relativa",
            titulo = "Guarnição",
            acessorio = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PontoDeEstado(
                        cor = if (estado.assinado) Cores.Vivo else Cores.TintaFraca,
                        pulsando = estado.assinado,
                    )
                    Box(Modifier.width(Espaco.Curto))
                    Etiqueta(
                        if (estado.assinado) "recebendo" else "sem sinal",
                        cor = if (estado.assinado) Cores.Vivo else Cores.TintaFraca,
                    )
                }
            },
        )

        estado.motivoIndisponivel?.let { motivo ->
            // Mapa vazio e mapa indisponível são indistinguíveis para quem olha, e
            // a leitura errada é a perigosa: "ninguém por perto" quando a verdade
            // é "não estou recebendo".
            Column(Modifier.fillMaxWidth().padding(Espaco.Padrao)) {
                Etiqueta("Indisponível", cor = Cores.P2)
                Box(Modifier.height(Espaco.Curto))
                TextoCorpoMenor(motivo, cor = Cores.TintaMedia)
            }
            return@Column
        }

        if (estado.pares.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Vazio(
                    etiqueta = "Ninguém publicando",
                    explicacao = "Nenhum par do talk group está enviando posição agora.",
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                // O mapa primeiro: numa emergência a pergunta é "onde", e "onde"
                // se responde com nome de rua — é o que o despachante entende e o
                // que a guarnição de apoio digita no navegador. A lista embaixo
                // continua dando distância e rumo exatos, que o mapa aproxima.
                val lat = estado.minhaLatitude
                val lon = estado.minhaLongitude
                if (lat != null && lon != null) {
                    MapaDeRuas(
                        minhaLatitude = lat,
                        minhaLongitude = lon,
                        pares = estado.pares,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                } else {
                    // Sem posição própria não há mapa — só lista. Centrar num
                    // ponto arbitrário desenharia a guarnição inteira na rua
                    // errada, que é pior que não desenhar.
                    Box(Modifier.fillMaxWidth().padding(Espaco.Padrao)) {
                        TextoCorpoMenor(
                            "Sem posição própria. As distâncias abaixo vêm do servidor.",
                            cor = Cores.TintaFraca,
                        )
                    }
                }
                Fio()
            }
            items(estado.pares, key = { it.indicativo }) { par ->
                LinhaDePar(par)
                Fio()
            }
        }
    }
}

/**
 * Um par na lista.
 *
 * O esmaecimento carrega informação, não estética: é a diferença entre "está ali"
 * e "estava ali". Opacidade e não cor — cor já está ocupada por prioridade e por
 * transmissão em todo o resto do painel, e uma terceira gramática cromática aqui
 * faria as três perderem sentido.
 */
@Composable
private fun LinhaDePar(par: ParNoMapa) {
    val opacidadeAlvo = when (par.frescor) {
        Frescor.ATUAL -> 1f
        Frescor.ESMAECIDO -> 0.45f
        Frescor.ANTIGO -> 0.28f
    }
    // Transição em vez de salto: o marcador que envelhece na tela aberta esmaece
    // suavemente, e o movimento comunica "isto está ficando velho" melhor que
    // qualquer rótulo.
    val opacidade by animateFloatAsState(
        targetValue = opacidadeAlvo,
        animationSpec = tween(durationMillis = 600),
        label = "frescor",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(Cores.Vazio)
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio)
            .alpha(opacidade),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            TextoIndicativo(par.indicativo)
            Box(Modifier.height(Espaco.Micro))
            TextoDado(
                when {
                    // Acima de dez minutos o marcador para de afirmar posição.
                    // Dizer "a 400 metros, nordeste" a partir de um dado de meia
                    // hora é afirmação sobre o presente com informação do passado.
                    par.frescor == Frescor.ANTIGO -> par.idadeFalada.orEmpty()
                    // "0 metros · oeste" é ruído com cara de dado.
                    par.juntoDeMim -> "com você"
                    else -> listOfNotNull(
                        par.distanciaFalada.removePrefix("a "),
                        par.rumoFalado.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                },
                cor = Cores.TintaMedia,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            // O carimbo aparece em TODAS as linhas, não só nas velhas. O
            // esmaecimento diz "confie menos"; o carimbo diz *quanto* menos — e é
            // a diferença entre decidir por sensação e decidir por número.
            Etiqueta(
                par.atualizadoHa,
                cor = when (par.frescor) {
                    Frescor.ATUAL -> Cores.Vivo
                    Frescor.ESMAECIDO -> Cores.P2
                    Frescor.ANTIGO -> Cores.Falha
                },
            )
            if (par.emMovimento) {
                Box(Modifier.height(Espaco.Micro))
                Etiqueta("deslocando", cor = Cores.TintaMedia)
            }
        }
    }
}
