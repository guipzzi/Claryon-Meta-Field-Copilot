package com.claryon.field.ui.componentes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claryon.common.Geo
import com.claryon.field.mapa.Frescor
import com.claryon.field.mapa.ParNoMapa
import com.claryon.field.ui.tema.Cores
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * **Mapa de ruas escuro com a guarnição.**
 *
 * Substitui o indicador polar. A troca veio de uma razão operacional que vence o
 * argumento de privacidade que eu vinha usando: **numa emergência, o que se passa
 * pelo rádio é endereço.** "Alfa Dois a 400 metros a nordeste" orienta quem já
 * está olhando para o lado certo; "Alfa Dois na Rui Barbosa com a Anhanguera" é o
 * que o despachante entende, o que a ambulância procura e o que a guarnição de
 * apoio digita no próprio navegador.
 *
 * **O que isso custa, dito com todas as letras.** A posição dos pares chega como
 * distância e rumo; plotá-la sobre ruas exige reconstruí-la por [Geo.destino], e a
 * partir daí a coordenada alheia **existe** na memória deste processo. Era o que o
 * desenho anterior evitava.
 *
 * O que continua valendo: a coordenada nunca trafega, nunca repousa em banco
 * local, e nunca chega a quem não está no talk group — a migração 0010 fechou a
 * leitura direta de `agent_positions`. A reconstrução é feita pelo cliente
 * legítimo, com o dado que ele já recebeu, para tomar a decisão dele.
 *
 * **Tiles do OpenFreeMap**: livre, sem chave de API, sem registro. MapLibre e não
 * Google Maps porque o aparelho da corporação pode não ter Play Services — e um
 * mapa que não abre no aparelho institucional não é um mapa.
 *
 * `MapLibre.getInstance` roda em [com.claryon.field.ClaryonApp], não aqui: a
 * `MapView` lança no construtor se a inicialização não tiver acontecido, e o
 * `remember` que a constrói roda durante a composição, antes de qualquer efeito.
 *
 * O símbolo do portador usa `Cores.Vivo`, não `NoAr`: âmbar significa "você está
 * no ar", e estar no mapa não é estar transmitindo. Cor de uso único perde o uso
 * assim que aparece em dois lugares.
 *
 * **Assinaturas conferidas por `javap` no AAR real** (`android-sdk-11.11.0.aar`,
 * `classes.jar`), não de memória — Regra Zero vale para toda dependência, não só
 * para o DAT.
 */
@Composable
fun MapaDeRuas(
    minhaLatitude: Double,
    minhaLongitude: Double,
    /** Para onde o portador aponta. `null` parado — a seta vira disco. */
    meuRumoGraus: Float?,
    pares: List<ParNoMapa>,
    /**
     * Indicativo que a câmera deve enquadrar, ou `null` para voltar ao portador.
     * Trocar este valor **é** o comando de animação: a tela pede o destino, o
     * mapa decide o percurso.
     */
    focoNoIndicativo: String?,
    /**
     * `true` = a câmera acompanha o portador a cada correção de GPS.
     *
     * É o contrato da Uber e do Waze, e o teste em movimento provou que ele não é
     * opcional: sem seguir, o agente que anda 500 m sai do próprio mapa e vê o
     * quarteirão que já deixou para trás.
     */
    seguindo: Boolean,
    /** O agente arrastou ou deu pinça — a câmera passa a ser dele. */
    aoGestoManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contexto = LocalContext.current
    val icones = remember(contexto) { IconFactory.getInstance(contexto) }
    val dono = LocalLifecycleOwner.current

    // `MapView` é uma `View` de ciclo de vida manual: sem `onStop`/`onDestroy` ela
    // segura o contexto do GL, o cache de tiles e o executor de rede depois que a
    // tela some. Numa aba que o agente abre e fecha o turno inteiro, isso é
    // vazamento acumulativo — e o mapa é justamente a tela que a política de
    // bateria manda fechar.
    val vista = remember(contexto) { MapView(contexto).also { it.onCreate(null) } }
    DisposableEffect(dono, vista) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> vista.onStart()
                Lifecycle.Event.ON_RESUME -> vista.onResume()
                Lifecycle.Event.ON_PAUSE -> vista.onPause()
                Lifecycle.Event.ON_STOP -> vista.onStop()
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose {
            dono.lifecycle.removeObserver(observador)
            vista.onStop()
            vista.onDestroy()
        }
    }

    // **Por que existem duas assinaturas em vez de redesenhar sempre.**
    //
    // `AndroidView.update` roda a cada recomposição, e as posições chegam a cada
    // poucos segundos. A versão anterior chamava `mapa.clear()` + `addMarker` de
    // todos os pares em toda passada: os marcadores piscavam, a alocação de
    // bitmap acontecia no meio do voo da câmera, e o resultado é o que se sentia
    // como "delay" — não era a animação lenta, era a animação disputando quadro
    // com um redesenho inteiro.
    //
    // Agora cada coisa tem seu gatilho: os marcadores redesenham quando os dados
    // mudam, a câmera voa quando o foco muda, e as duas não se atrapalham.
    val assinaturaDosDados = remember(minhaLatitude, minhaLongitude, meuRumoGraus, pares) {
        buildString {
            append(minhaLatitude).append(',').append(minhaLongitude).append(',').append(meuRumoGraus)
            pares.forEach {
                append('|').append(it.indicativo).append(it.distanciaM)
                    .append(it.rumoGraus).append(it.frescor).append(it.emMovimento)
            }
        }
    }
    val ultimaAssinatura = remember { arrayOfNulls<String>(1) }
    val ultimoFoco = remember { arrayOfNulls<String>(1) }
    val jaAbriu = remember { booleanArrayOf(false) }
    // Lido de dentro do listener do MapLibre, que não é recomposto: sem esta
    // caixa o listener enxergaria para sempre o valor da primeira composição.
    val gestoManual = remember { mutableStateOf(aoGestoManual) }
    gestoManual.value = aoGestoManual

    AndroidView(
        modifier = modifier,
        factory = {
            vista.apply {
                getMapAsync { mapa ->
                    mapa.setStyle(Style.Builder().fromUri(ESTILO_ESCURO)) {
                        // Sem bússola e sem gestos de rotação: o norte fica em
                        // cima. Mapa que gira com o aparelho obriga a recalibrar a
                        // leitura a cada passo, e o agente está andando.
                        //
                        // A PINÇA fica ligada — e não precisou de nada: o `javap`
                        // sobre `MapLibreMapOptions` mostra `zoomGesturesEnabled`
                        // inicializado em `true` no construtor. Deixo a chamada
                        // explícita mesmo assim, porque um default que ninguém
                        // declara é um default que alguém desliga sem perceber.
                        // Rotação por gesto LIGADA, e a bússola junto: se o mapa
                        // pode girar, o agente precisa de um jeito de achar o
                        // norte de volta. Bússola sem rotação seria enfeite;
                        // rotação sem bússola seria armadilha.
                        mapa.uiSettings.isRotateGesturesEnabled = true
                        mapa.uiSettings.isCompassEnabled = true
                        // Empurrada para baixo do cabeçalho flutuante da tela.
                        // Sem isto ela nasce colada no topo direito, exatamente
                        // sob a contagem da guarnição — dois elementos no mesmo
                        // pixel, e o de baixo invisível. Só aparece com o mapa
                        // girado, que é quando ela serve.
                        mapa.uiSettings.setCompassMargins(0, 240, 36, 0)
                        // Some sozinha quando o norte volta para cima: bússola
                        // apontando para o norte num mapa orientado ao norte é
                        // ruído que ocuparia canto de tela o turno inteiro.
                        mapa.uiSettings.setCompassFadeFacingNorth(true)
                        mapa.uiSettings.isTiltGesturesEnabled = false
                        mapa.uiSettings.isZoomGesturesEnabled = true
                        mapa.uiSettings.setAttributionMargins(24, 0, 0, 24)

                        // Limites de zoom: além de 17,5 o OpenFreeMap não tem mais
                        // detalhe para dar e o agente só amplia ruído; aquém de 11
                        // some o nome da rua, que é a única razão deste mapa
                        // existir.
                        mapa.setMinZoomPreference(11.0)
                        mapa.setMaxZoomPreference(17.5)

                        // `REASON_API_GESTURE` é o dedo do agente; as outras duas
                        // razões são as nossas próprias animações. Sem essa
                        // distinção, o `animateCamera` de seguir cancelaria o
                        // modo seguir a cada quadro — o mapa se desligaria
                        // sozinho na primeira atualização de posição.
                        mapa.addOnCameraMoveStartedListener { razao ->
                            if (razao == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                gestoManual.value.invoke()
                            }
                        }

                        desenhar(mapa, icones, minhaLatitude, minhaLongitude, meuRumoGraus, pares, 0.0)
                        ultimaAssinatura[0] = assinaturaDosDados
                        mapa.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(minhaLatitude, minhaLongitude),
                                ZOOM_PADRAO,
                            ),
                        )
                        jaAbriu[0] = true
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { mapa ->
                // Só mexe com o estilo pronto: `addMarker` antes disso é
                // descartado em silêncio, e o mapa abriria vazio na primeira
                // atualização de posição — que é a mais provável de todas.
                if (mapa.style?.isFullyLoaded != true || !jaAbriu[0]) return@getMapAsync

                if (ultimaAssinatura[0] != assinaturaDosDados) {
                    ultimaAssinatura[0] = assinaturaDosDados
                    // O giro que a câmera VAI ter, não o que ela tem agora:
                    // `desenhar` roda antes de `seguir`, e usar o giro corrente
                    // deixaria a seta torta pelo delta da rotação até o próximo
                    // redesenho. É um erro que só aparece com o agente andando.
                    desenhar(mapa, icones, minhaLatitude, minhaLongitude, meuRumoGraus, pares, giroAlvo(mapa, seguindo, focoNoIndicativo, meuRumoGraus))
                }
                if (ultimoFoco[0] != focoNoIndicativo) {
                    ultimoFoco[0] = focoNoIndicativo
                    animarPara(mapa, minhaLatitude, minhaLongitude, pares, focoNoIndicativo)
                } else if (seguindo && focoNoIndicativo == null) {
                    // Acompanhar é um deslize curto, não um voo: a cada correção
                    // o agente andou alguns metros, e 260 ms de transição fazem o
                    // mapa deslizar por baixo da seta em vez de saltar. Saltar é
                    // o que faz um mapa em movimento parecer quebrado.
                    seguir(mapa, minhaLatitude, minhaLongitude, meuRumoGraus)
                }
            }
        },
    )
}

/**
 * Move a câmera — e **só** quando alguém pediu.
 *
 * O erro que esta função existe para não cometer: reenquadrar a cada atualização
 * de posição. As posições chegam a cada poucos segundos; uma câmera que se
 * recentra sozinha arranca o mapa da mão do agente no meio de uma pinça, e o
 * gesto de "olhar o quarteirão ao lado" vira uma briga com o app. Depois da
 * abertura, a câmera só se mexe por ordem explícita: o agente tocou o nome de um
 * par, ou tocou o botão de voltar para si.
 */
private fun animarPara(
    mapa: MapLibreMap,
    minhaLat: Double,
    minhaLon: Double,
    pares: List<ParNoMapa>,
    focoNoIndicativo: String?,
) {
    val alvo = if (focoNoIndicativo == null) {
        LatLng(minhaLat, minhaLon)
    } else {
        val par = pares.firstOrNull { it.indicativo == focoNoIndicativo } ?: return
        val rumo = par.rumoGraus ?: return
        // Par sem posição afirmável não recebe câmera. Voar até um ponto que o
        // marcador nem desenha entregaria por movimento a certeza que o
        // esmaecimento nega por opacidade.
        if (par.frescor == Frescor.ANTIGO) return
        val (lat, lon) = Geo.destino(minhaLat, minhaLon, par.distanciaM.toDouble(), rumo)
        LatLng(lat, lon)
    }

    // **A duração sai da distância, não de uma constante.**
    //
    // Uma duração fixa erra dos dois lados: 700 ms para um par a 80 metros é uma
    // pausa sem motivo, e 700 ms para um par a 3 km é um borrão em que o agente
    // não vê o percurso — e é o percurso que informa para que lado o mapa andou.
    // Entre 260 ms e 900 ms, proporcional à distância, o olho acompanha nos dois
    // casos.
    val atual = mapa.cameraPosition.target
    val salto = if (atual == null) 0.0 else {
        Geo.distanciaM(atual.latitude, atual.longitude, alvo.latitude, alvo.longitude)
    }
    val duracao = (260.0 + salto / 4.0).coerceAtMost(900.0).toInt()

    mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(alvo, ZOOM_PADRAO), duracao)
}

/**
 * Mantém o portador no centro enquanto ele anda.
 *
 * Só se mexe acima de 8 m: o GPS treme parado, e uma câmera que persegue o ruído
 * do receptor faz o mapa vibrar com o agente imóvel. Oito metros é maior que o
 * tremor típico e menor que um passo largo de viatura.
 */
private fun seguir(mapa: MapLibreMap, lat: Double, lon: Double, rumo: Float?) {
    val atual = mapa.cameraPosition.target ?: return
    val andou = Geo.distanciaM(atual.latitude, atual.longitude, lat, lon)
    val girou = rumo != null &&
        anguloEntre(mapa.cameraPosition.bearing, rumo.toDouble()) > 12.0
    if (andou < 8.0 && !girou) return

    // **A tela gira com o deslocamento, e a seta para de girar.**
    //
    // É a inversão que todo navegador faz e que a primeira versão deste mapa não
    // fazia. Com norte travado, ler "estou indo para lá" exige traduzir um ângulo
    // na cabeça a cada esquina; com a rua do agente sempre apontando para cima,
    // o que está à direita na tela está à direita no para-brisa. Some a tradução.
    //
    // O custo é real e é o motivo de eu ter escolhido o contrário antes: a malha
    // de ruas passa a chegar torta e os rótulos giram. Vale mesmo assim, porque a
    // decisão que este mapa serve é "para que lado eu sigo", não "onde fica o
    // norte" — e a bússola, agora visível, devolve o norte a um toque.
    val camera = CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .apply { if (rumo != null && rumo.isFinite()) bearing(rumo.toDouble()) }
        .build()
    mapa.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 320)
}

/**
 * Para onde a câmera vai apontar ao fim desta passada.
 *
 * Seguindo e sem foco num par, ela vai girar até o rumo do agente; em qualquer
 * outro caso ela fica onde está. Isolado numa função porque `desenhar` precisa
 * saber disso **antes** de `seguir` acontecer.
 */
private fun giroAlvo(
    mapa: MapLibreMap,
    seguindo: Boolean,
    foco: String?,
    rumo: Float?,
): Double = if (seguindo && foco == null && rumo != null && rumo.isFinite()) {
    rumo.toDouble()
} else {
    mapa.cameraPosition.bearing
}

/** Menor ângulo entre dois rumos, atravessando o zero corretamente. */
private fun anguloEntre(a: Double, b: Double): Double {
    val d = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)
    return d
}

/**
 * Coloca o portador e os pares.
 *
 * Não mexe na câmera: enquadramento é decisão de [animarPara], e misturar as duas
 * coisas foi como a primeira versão acabou reenquadrando a cada dado novo.
 */
private fun desenhar(
    mapa: MapLibreMap,
    icones: IconFactory,
    minhaLat: Double,
    minhaLon: Double,
    meuRumoGraus: Float?,
    pares: List<ParNoMapa>,
    /** Giro que a câmera terá quando esta passada terminar. Ver o chamador. */
    giroDaTela: Double,
) {
    mapa.clear()

    pares.forEach { par ->
        // **`ANTIGO` não entra no mapa.** Acima de dez minutos a lista já para de
        // afirmar posição e passa a dizer idade — e no mapa a afirmação é mais
        // forte, não mais fraca: um ponto sobre uma rua diz *endereço*. Plotar
        // uma correção de quarenta minutos como um pino desenharia a guarnição
        // num quarteirão onde ninguém está, que é o erro que a regra dos dois
        // minutos existe para impedir. A linha do par continua na lista,
        // dizendo a idade — some do mapa, não do painel.
        if (par.frescor == Frescor.ANTIGO) return@forEach
        val rumo = par.rumoGraus ?: return@forEach
        // A reconstrução. Ver o KDoc de `MapaDeRuas` sobre o que ela custa.
        val (lat, lon) = Geo.destino(minhaLat, minhaLon, par.distanciaM.toDouble(), rumo)

        // Opacidade por frescor, como na lista: marcador cheio afirma "está ali",
        // esmaecido diz "estava".
        val alfa = if (par.frescor == Frescor.ESMAECIDO) 120 else 255
        // Posição é ESTADO, não sinal: o par que se desloca deixou de ser
        // verde e passou a ser o degrau de tinta mais alto. A distinção
        // sobrevive porque os dois canais são ortogonais — o NÍVEL de tinta diz
        // se anda, o ALFA logo acima diz o frescor —, e a palavra "deslocando"
        // continua na gaveta para quem precisar do fato sem interpretar pino.
        val cor = if (par.emMovimento) Cores.Tinta.toArgb() else Cores.TintaMedia.toArgb()
        mapa.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lon))
                .title(par.indicativo)
                .snippet("${par.distanciaFalada} · ${par.atualizadoHa}")
                .icon(icones.fromBitmap(discoDePar(cor, alfa))),
        )
    }

    // O portador por último: `addMarker` empilha na ordem de inserção, e quem
    // olha o mapa procura a si mesmo primeiro. Ficar por baixo de um par colado
    // — dupla na mesma viatura, o caso mais comum do policiamento — seria perder
    // justamente a referência que orienta todo o resto.
    mapa.addMarker(
        MarkerOptions()
            .position(LatLng(minhaLat, minhaLon))
            .icon(icones.fromBitmap(setaDoPortador(meuRumoGraus, giroDaTela))),
    )
}

/**
 * **O símbolo do portador — a seta da Uber.**
 *
 * Disco branco com anel escuro e uma seta apontando para o rumo. A escolha não é
 * mimetismo: é a única forma consagrada de dizer *onde estou* e *para onde estou
 * virado* num só glifo, e o agente já a lê sem aprender, porque usa Uber e Waze.
 *
 * `Marker` do MapLibre **não tem rotação** — confirmado por `javap`, a classe
 * expõe só posição, ícone, título e deslocamentos. Então quem gira é o bitmap,
 * via [Canvas.rotate] antes de rasterizar. Custa um bitmap de 56 px por
 * atualização de rumo, o que é irrelevante perto de redesenhar tiles.
 *
 * **Parado, a seta vira disco.** `hasBearing()` é falso sem deslocamento, e
 * apontar para o norte porque o GPS não sabe seria inventar direção — o mesmo
 * erro que `Rumo.deGraus(NaN)` devolvendo NORTE já custou a este projeto.
 */
private fun setaDoPortador(rumoGraus: Float?, giroDaTela: Double): Bitmap {
    val lado = 56
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val centro = lado / 2f

    // Halo de precisão: sugere "por aqui", não "exatamente aqui".
    c.drawCircle(centro, centro, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.Tinta.toArgb()
        alpha = 38
    })
    // Anel escuro por baixo do disco: sobre rua clara, branco em branco some.
    c.drawCircle(centro, centro, 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(190, 6, 8, 10)
    })
    c.drawCircle(centro, centro, 12.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    })

    if (rumoGraus != null && rumoGraus.isFinite()) {
        // `Vazio` e não um degrau de tinta: a seta é desenhada POR CIMA do
        // disco branco de raio 12,5. Tinta clara sobre branco desaparece — é o
        // mesmo motivo do anel escuro logo acima. Marca escura sobre
        // preenchimento claro é a gramática do CTA deste sistema.
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.Vazio.toArgb() }
        c.save()
        // **Zero, não `rumoGraus`.** Quem gira agora é a câmera: com a tela já
        // orientada pelo deslocamento, a seta desenhada no rumo giraria duas
        // vezes e apontaria para o lugar errado — erro que só aparece andando, e
        // por isso escapa de qualquer teste de tela parada.
        // Rumo do agente MENOS o giro já aplicado à câmera. Seguindo, os dois se
        // cancelam e a seta aponta para cima. Com o mapa parado no norte — porque
        // o agente arrastou —, o giro da câmera é zero e a seta volta a apontar
        // para o rumo verdadeiro. Uma expressão cobre os dois casos.
        c.rotate((rumoGraus - giroDaTela).toFloat(), centro, centro)
        val seta = Path().apply {
            moveTo(centro, centro - 8f)
            lineTo(centro + 5.5f, centro + 6f)
            lineTo(centro, centro + 3f)
            lineTo(centro - 5.5f, centro + 6f)
            close()
        }
        c.drawPath(seta, tinta)
        c.restore()
    } else {
        // Sem rumo: disco cheio. Diz "estou aqui" e cala sobre a direção.
        c.drawCircle(centro, centro, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Cores.Vazio.toArgb()
        })
    }
    return bmp
}

/**
 * Um par: disco com halo escuro.
 *
 * Forma diferente da do portador de propósito. Se par e portador fossem o mesmo
 * glifo em cores diferentes, um agente daltônico — ou qualquer um sob sol forte —
 * perderia a distinção mais importante do mapa.
 */
private fun discoDePar(cor: Int, alfa: Int): Bitmap {
    val lado = 36
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val centro = lado / 2f
    c.drawCircle(centro, centro, centro - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.Vazio.toArgb()
        alpha = (alfa * 0.85f).toInt()
    })
    c.drawCircle(centro, centro, centro - 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cor
        alpha = alfa
    })
    return bmp
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )

/**
 * Zoom de quarteirão, fixo.
 *
 * Derivar o zoom da distância do par mais longe — como a versão anterior fazia —
 * dava um mapa que mudava de escala sozinho a cada atualização de posição. O
 * agente perdia a referência de tamanho a cada poucos segundos, que é o oposto
 * do que um mapa de campo precisa fazer. Escala fixa, pinça para mudar: é o
 * contrato da Uber e do Waze, e é o que a mão já sabe.
 */
private const val ZOOM_PADRAO = 16.2


/** Estilo escuro do OpenFreeMap: 47 camadas, sem chave, sem cota declarada. */
private const val ESTILO_ESCURO = "https://tiles.openfreemap.org/styles/dark"
