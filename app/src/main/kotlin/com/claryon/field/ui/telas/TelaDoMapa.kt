package com.claryon.field.ui.telas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.Frescor
import com.claryon.field.mapa.ParNoMapa
import com.claryon.field.mapa.TracoDoRastro
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.MapaDeRuas
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.duracaoAcessivel

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
 * **O mapa é a tela; a lista é uma gaveta.** A lista abre recolhida e sobe por
 * cima do mapa quando o agente puxa. A hierarquia carrega o modelo mental certo:
 * a pergunta padrão de quem abre esta tela é *onde estamos*, e ela se responde
 * olhando ruas. *Quantos metros exatos até o Bravo Um* é a pergunta seguinte, e
 * quem a tem sabe onde procurar.
 */
@Composable
fun TelaDoMapa(
    estado: EstadoDoMapa,
    aoAbrir: () -> Unit,
    aoFechar: () -> Unit,
    /** Focar um par carrega o rastro dele; `null` desfoca e **apaga** o rastro. */
    aoFocar: (String?) -> Unit,
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

    // Recolhida por padrão, por decisão de produto: o mapa é o foco.
    var listaAberta by remember { mutableStateOf(false) }
    // `null` = câmera no portador. Trocar este valor é o comando de voo.
    var foco by remember { mutableStateOf<String?>(null) }
    // Segue o portador até ele pegar o mapa com a mão. Uber e Waze fazem assim, e
    // o motivo é o mesmo: quem está andando quer ver para onde vai; quem arrastou
    // o mapa quer ver o que arrastou. Adivinhar qual dos dois é errar metade das
    // vezes — então o gesto decide, e o botão de recentrar desfaz.
    var seguindo by remember { mutableStateOf(true) }

    Box(modifier.fillMaxSize().background(Cores.Vazio)) {
        val lat = estado.minhaLatitude
        val lon = estado.minhaLongitude

        if (lat != null && lon != null) {
            MapaDeRuas(
                minhaLatitude = lat,
                minhaLongitude = lon,
                meuRumoGraus = estado.meuRumoGraus,
                pares = estado.pares,
                focoNoIndicativo = foco,
                seguindo = seguindo,
                aoGestoManual = { seguindo = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Sem posição própria não há mapa — só a causa dita. Centrar num
            // ponto arbitrário desenharia a guarnição inteira na rua errada.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(Espaco.Padrao),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Etiqueta("Sem posição própria", cor = Cores.P2)
                    Box(Modifier.height(Espaco.Curto))
                    TextoCorpoMenor(
                        estado.motivoIndisponivel
                            // A causa da transmissão só entra aqui quando ela é
                            // uma causa: com a posição subindo normalmente, ela
                            // diria "enviada há 40 s" embaixo de "sem posição
                            // própria" — duas frases verdadeiras que juntas
                            // mentem. Esse caso é o da correção local vencida
                            // (`ProvedorDeLocal` recusa acima de 2 min), e a
                            // frase certa é a última.
                            ?: estado.motivoDaMinhaPosicao.takeIf { !estado.minhaPosicaoSobe }
                            ?: "Aguardando a primeira correção de GPS.",
                        cor = Cores.TintaMedia,
                    )
                }
            }
        }

        // **O cabeçalho passou a ter duas direções.** Ele dizia "recebendo" com o
        // ponto verde pulsando enquanto a posição do próprio portador não subia há
        // vinte minutos — a única afirmação da tela sobre o estado da rede falava
        // só da metade que funcionava.
        Column(Modifier.align(Alignment.TopCenter)) {
            CabecalhoFlutuante(
                assinado = estado.assinado,
                minhaPosicaoSobe = estado.minhaPosicaoSobe,
                quantos = estado.pares.count { it.frescor != Frescor.ANTIGO },
            )
            // A causa fica **fora da gaveta**, porque a gaveta abre recolhida: um
            // aviso que só aparece depois de um toque não é aviso. Aparece só na
            // falha — acender no funcionamento normal ensina a ignorar.
            if (!estado.minhaPosicaoSobe && estado.motivoDaMinhaPosicao != null) {
                AvisoDaPosicaoPropria(estado.motivoDaMinhaPosicao)
            }
        }

        // Só aparece quando a câmera saiu do portador. Botão permanente seria
        // ruído 90% do tempo; ausente, o agente que arrastou o mapa não teria
        // como voltar sem fechar e reabrir a tela.
        if (!seguindo || foco != null) {
            BotaoRecentrar(
                aoTocar = { foco = null; seguindo = true },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        GavetaDaGuarnicao(
            pares = estado.pares,
            aberta = listaAberta,
            focado = foco,
            aoAlternar = { listaAberta = !listaAberta },
            rastro = estado.rastro.takeIf { estado.rastroDe == foco }.orEmpty(),
            aoTocarPar = { indicativo ->
                // Tocar de novo no mesmo par devolve a câmera ao portador.
                if (foco == indicativo) {
                    foco = null
                    seguindo = true
                    aoFocar(null)
                } else {
                    foco = indicativo
                    aoFocar(indicativo)
                    // Olhar um par suspende o acompanhamento: senão a próxima
                    // correção de GPS arrancaria a câmera de volta em 260 ms, e o
                    // toque do agente pareceria não ter funcionado.
                    seguindo = false
                }
            },
            motivoIndisponivel = estado.motivoIndisponivel,
            temPosicaoPropria = estado.temPosicaoPropria,
            motivoDaMinhaPosicao = estado.motivoDaMinhaPosicao,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Faixa de estado por cima do mapa.
 *
 * Flutua em vez de empurrar o mapa para baixo: cabeçalho sólido custaria 90 dp de
 * rua, e rua é o conteúdo. O fundo é translúcido para o mapa continuar legível
 * por baixo, e a contagem exclui os pares antigos — dizer "3 na guarnição" com
 * dois deles de meia hora atrás seria contar fantasma.
 *
 * **Duas direções, não uma.** [assinado] fala de RECEBER e [minhaPosicaoSobe] de
 * TRANSMITIR, e elas falham separadamente: a auditoria de 21/08 pegou o app
 * recebendo posição de par nenhum e transmitindo nada, com o cabeçalho verde
 * dizendo "recebendo". O aviso de transmissão aparece **só quando ela para** —
 * indicador que acende no estado normal ensina a ser ignorado, e aí não avisa mais
 * nada quando importa. É a mesma regra que decidiu o limiar de esmaecimento.
 */
@Composable
private fun CabecalhoFlutuante(
    assinado: Boolean,
    minhaPosicaoSobe: Boolean,
    quantos: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Cores.Vazio.copy(alpha = 0.82f))
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoDeEstado(
                cor = if (assinado) Cores.Vivo else Cores.TintaFraca,
                pulsando = assinado,
            )
            Box(Modifier.width(Espaco.Curto))
            Etiqueta(
                if (assinado) "recebendo" else "sem sinal",
                cor = if (assinado) Cores.Vivo else Cores.TintaFraca,
            )
        }
        if (!minhaPosicaoSobe) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PontoDeEstado(cor = Cores.Falha, pulsando = false)
                Box(Modifier.width(Espaco.Curto))
                Etiqueta("fora do mapa", cor = Cores.Falha)
            }
        }
        Etiqueta(
            if (quantos == 1) "1 na guarnição" else "$quantos na guarnição",
            cor = Cores.TintaMedia,
        )
    }
}

/**
 * A causa de a posição própria não estar subindo, debaixo do cabeçalho.
 *
 * Uma linha só, e ela é **derivada** — "o turno não abriu", "sem rede", "sem
 * correção de GPS há 4 min". Nunca uma frase fixa: a frase fixa é o defeito que
 * esta tela inteira acabou de corrigir, e ela reaparece com facilidade porque
 * parece inofensiva.
 */
@Composable
private fun AvisoDaPosicaoPropria(motivo: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Cores.Vazio.copy(alpha = 0.82f))
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Curto),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextoCorpoMenor(motivo, cor = Cores.Falha)
    }
}

/**
 * Recentrar.
 *
 * Fica na borda direita, na altura do polegar de quem segura o aparelho com uma
 * mão só — que é como um agente segura o celular, porque a outra mão está
 * ocupada. Alvo de 48 dp: o mínimo tocável com luva.
 */
@Composable
private fun BotaoRecentrar(aoTocar: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = Espaco.Padrao)
            .size(48.dp)
            .background(Cores.Painel.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = aoTocar,
            )
            .drawBehind {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 14f
                drawCircle(color = Cores.Tinta, radius = r, center = c, style = Stroke(width = 2f))
                drawCircle(color = Cores.Tinta, radius = 2.5f, center = c)
                // Quatro riscos cardeais: é a mira de "centralizar em mim" que a
                // Uber e o Waze usam, e o agente já lê sem aprender.
                listOf(
                    Offset(c.x, c.y - r - 5f) to Offset(c.x, c.y - r + 1f),
                    Offset(c.x, c.y + r - 1f) to Offset(c.x, c.y + r + 5f),
                    Offset(c.x - r - 5f, c.y) to Offset(c.x - r + 1f, c.y),
                    Offset(c.x + r - 1f, c.y) to Offset(c.x + r + 5f, c.y),
                ).forEach { (a, b) ->
                    drawLine(color = Cores.Tinta, start = a, end = b, strokeWidth = 2f)
                }
            },
    )
}

/**
 * A gaveta.
 *
 * Recolhida mostra só a alça e a contagem. Aberta sobe até metade da tela — nunca
 * a tela inteira, porque perder o mapa de vista para ler uma lista sobre o mapa é
 * troca ruim.
 */
@Composable
private fun GavetaDaGuarnicao(
    pares: List<ParNoMapa>,
    aberta: Boolean,
    focado: String?,
    rastro: List<TracoDoRastro>,
    aoAlternar: () -> Unit,
    aoTocarPar: (String) -> Unit,
    motivoIndisponivel: String?,
    /** O servidor tem a minha posição. `false` colapsava com "ninguém publicou". */
    temPosicaoPropria: Boolean,
    /** Por que a minha posição não sobe — derivada, nunca fixa. */
    motivoDaMinhaPosicao: String?,
    modifier: Modifier = Modifier,
) {
    // `Movimento.MEDIO` + `Morfose` no lugar de `tween(260, FastOutSlowInEasing)`:
    // a alça já está na tela e muda de orientação, que é a definição de morfose
    // aqui. `FastOutSlowInEasing` é a curva do Material, e a auditoria de 22/08 a
    // encontrou em três lugares desta tela — a única gramática de movimento do
    // aplicativo importada de fora do vocabulário próprio.
    val giro by animateFloatAsState(
        targetValue = if (aberta) MEIA_VOLTA else 0f,
        animationSpec = tween(
            duracaoAcessivel(Movimento.MEDIO),
            easing = Movimento.Morfose,
        ),
        label = "giro-da-alca",
    )

    Column(
        modifier
            .fillMaxWidth()
            .background(Cores.Painel)
            .drawBehind {
                drawLine(
                    color = Cores.Traco,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    // Sem ripple: o tema é dark-only e sem Material 2, e o
                    // indication padrão já derrubou o app uma vez aqui.
                    indication = null,
                    onClick = aoAlternar,
                )
                .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Etiqueta("Guarnição", cor = Cores.Tinta)
            Box(Modifier.size(20.dp).rotate(giro), contentAlignment = Alignment.Center) {
                Chevron()
            }
        }

        // A gaveta é remanejo de layout — `Movimento.MEDIO` com `Morfose`, que é
        // o que o vocabulário nomeia para "gaveta, expansão e remanejo". O fade
        // acompanha em `CURTO` na entrada e `MICRO` na saída: a superfície some
        // antes de terminar de encolher, senão a lista pisca no último quadro.
        AnimatedVisibility(
            visible = aberta,
            enter = expandVertically(
                tween(duracaoAcessivel(Movimento.MEDIO), easing = Movimento.Morfose),
            ) + fadeIn(tween(duracaoAcessivel(Movimento.CURTO), easing = Movimento.Saida)),
            exit = shrinkVertically(
                tween(duracaoAcessivel(Movimento.MEDIO), easing = Movimento.Morfose),
            ) + fadeOut(tween(duracaoAcessivel(Movimento.MICRO))),
        ) {
            Column {
                Fio()
                when {
                    motivoIndisponivel != null -> Column(
                        Modifier.fillMaxWidth().padding(Espaco.Padrao),
                    ) {
                        Etiqueta("Indisponível", cor = Cores.P2)
                        Box(Modifier.height(Espaco.Curto))
                        TextoCorpoMenor(motivoIndisponivel, cor = Cores.TintaMedia)
                    }

                    // **A causa é MINHA e a frase acusava a guarnição.**
                    //
                    // Este ramo escrevia "Ninguém publicando · Nenhum par do talk
                    // group está enviando posição agora" sobre qualquer lista
                    // vazia. Só que `posicoes_do_grupo` faz `cross join minha`:
                    // quem nunca publicou recebe **zero linhas com a guarnição
                    // inteira publicando**, e essa é a lista vazia mais comum. A
                    // tela transformava um defeito do portador numa afirmação
                    // sobre os companheiros dele.
                    !temPosicaoPropria -> Column(
                        Modifier.fillMaxWidth().padding(Espaco.Padrao),
                    ) {
                        Etiqueta("Você não está no mapa", cor = Cores.Falha)
                        Box(Modifier.height(Espaco.Curto))
                        TextoCorpoMenor(
                            motivoDaMinhaPosicao
                                // Sem causa derivada não se inventa uma: dizer o que
                                // se sabe é melhor que dizer o que soa bem.
                                ?: "Sua posição ainda não subiu para o servidor.",
                            cor = Cores.TintaMedia,
                        )
                        Box(Modifier.height(Espaco.Curto))
                        TextoCorpoMenor(
                            "Sem ela o servidor não tem de onde medir as distâncias, " +
                                "e a guarnição não aparece.",
                            cor = Cores.TintaFraca,
                        )
                    }

                    // Eu publico e não veio ninguém: aí sim a lista vazia fala do
                    // grupo. **Decisão do dono do produto:** o mapa fica em branco,
                    // só com a posição do portador, e a gaveta não acusa ninguém —
                    // um par pode estar em Standby, sem sinal, ou fora do turno, e a
                    // tela não tem como saber qual dos três.
                    pares.isEmpty() -> Column(
                        Modifier.fillMaxWidth().padding(Espaco.Padrao),
                    ) {
                        Etiqueta("Só você no mapa", cor = Cores.TintaFraca)
                        Box(Modifier.height(Espaco.Curto))
                        TextoCorpoMenor(
                            "Sua posição está subindo. Nenhum outro par da guarnição " +
                                "tem posição no mapa agora.",
                            cor = Cores.TintaMedia,
                        )
                    }

                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(pares, key = { it.indicativo }) { par ->
                            LinhaDePar(
                                par = par,
                                focado = par.indicativo == focado,
                                aoTocar = { aoTocarPar(par.indicativo) },
                            )
                            // O rastro fica DEBAIXO do par focado, e some com o
                            // foco. Rastro pendurado na tela de um par que já não
                            // está em foco é dado de vigilância à toa.
                            if (par.indicativo == focado && rastro.isNotEmpty()) {
                                RastroDoPar(rastro)
                            }
                            Fio()
                        }
                    }
                }
            }
        }
    }
}

/**
 * **Por onde o par passou nos últimos 30 minutos** — em grandeza, nunca coordenada.
 *
 * Cada linha é distância e rumo **a partir de onde eu estou agora**. Isso não é
 * detalhe de implementação: é o que separa "ir ao encontro do companheiro" de
 * "reconstruir por onde ele andou". O servidor nunca devolve latitude e longitude
 * de terceiro, e por isso o trajeto absoluto dele não existe deste lado.
 *
 * Mais recente em cima: quem olha quer saber onde ele está agora e de onde veio,
 * nessa ordem.
 */
@Composable
private fun RastroDoPar(rastro: List<TracoDoRastro>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Cores.Vazio)
            .padding(start = Espaco.Largo, end = Espaco.Padrao, top = Espaco.Curto, bottom = Espaco.Curto),
    ) {
        Etiqueta("Últimos 30 min", cor = Cores.TintaFraca)
        Box(Modifier.height(Espaco.Curto))
        rastro.asReversed().take(12).forEach { t ->
            TextoCorpoMenor(
                "${t.idadeFalada} · ${t.distanciaFalada.removePrefix("a ")} · ${t.rumoFalado}",
                cor = Cores.TintaMedia,
            )
        }
        if (rastro.size > 12) {
            TextoCorpoMenor("e mais ${rastro.size - 12} pontos", cor = Cores.TintaFraca)
        }
    }
}

/** Alça da gaveta. Duas linhas em V, giradas 180° quando aberta. */
@Composable
private fun Chevron() {
    Box(
        Modifier.size(20.dp).drawBehind {
            val meio = size.width / 2f
            val topo = size.height * 0.36f
            val base = size.height * 0.62f
            drawLine(
                color = Cores.TintaMedia,
                start = Offset(meio - 6f, base),
                end = Offset(meio, topo),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Cores.TintaMedia,
                start = Offset(meio, topo),
                end = Offset(meio + 6f, base),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        },
    )
}

/**
 * Um par na lista.
 *
 * O esmaecimento carrega informação, não estética: é a diferença entre "está ali"
 * e "estava ali". Opacidade e não cor — cor já está ocupada por prioridade e por
 * transmissão em todo o resto do painel, e uma terceira gramática cromática aqui
 * faria as três perderem sentido.
 *
 * Tocar leva a câmera até ele. Um par `ANTIGO` **não** é tocável: ele não está no
 * mapa, e um voo até um marcador que não existe entregaria por movimento a
 * certeza que o esmaecimento nega por opacidade.
 */
@Composable
private fun LinhaDePar(par: ParNoMapa, focado: Boolean, aoTocar: () -> Unit) {
    val alcancavel = par.frescor != Frescor.ANTIGO && par.rumoGraus != null
    val opacidadeAlvo = when (par.frescor) {
        Frescor.ATUAL -> 1f
        Frescor.ESMAECIDO -> 0.45f
        Frescor.ANTIGO -> 0.28f
    }
    // Transição em vez de salto: o marcador que envelhece na tela aberta esmaece
    // suavemente, e o movimento comunica "isto está ficando velho" melhor que
    // qualquer rótulo.
    //
    // **Era um `tween(600)` digitado, sem curva.** Os 600 ms batiam com
    // `Movimento.DECAIMENTO` dígito por dígito e não havia referência entre os dois
    // — a duplicata clássica, que diverge no primeiro ajuste. `DecaimentoPorIdade`
    // existia desde que o vocabulário foi escrito e **não tinha um único chamador**;
    // este é o momento que ele nomeia, e a curva `Constante` que ele traz é o
    // conteúdo: esmaecimento linear lê como *"tempo passou"*, e com curva lê como
    // *"algo aconteceu"* — leituras opostas, e aqui a primeira é a verdadeira.
    val opacidade by animateFloatAsState(
        targetValue = opacidadeAlvo,
        animationSpec = Movimento.DecaimentoPorIdade(),
        label = "frescor",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focado) Cores.Elevado else Cores.Painel)
            .then(
                if (alcancavel) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = aoTocar,
                    )
                } else {
                    Modifier
                },
            )
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

/**
 * Meia volta — a alça da gaveta aponta para baixo fechada e para cima aberta.
 *
 * Nomeada porque `180f` solto num `animateFloatAsState` é indistinguível de um
 * número de opacidade, de largura ou de rumo, e esta tela tem os três.
 */
private const val MEIA_VOLTA = 180f
