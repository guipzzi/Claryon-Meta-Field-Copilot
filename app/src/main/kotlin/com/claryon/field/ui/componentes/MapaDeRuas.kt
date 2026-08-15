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
                        mapa.uiSettings.isRotateGesturesEnabled = false
                        mapa.uiSettings.isTiltGesturesEnabled = false
                        mapa.uiSettings.isCompassEnabled = false
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

                        desenhar(mapa, icones, minhaLatitude, minhaLongitude, meuRumoGraus, pares)
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
                    desenhar(mapa, icones, minhaLatitude, minhaLongitude, meuRumoGraus, pares)
                }
                if (ultimoFoco[0] != focoNoIndicativo) {
                    ultimoFoco[0] = focoNoIndicativo
                    animarPara(mapa, minhaLatitude, minhaLongitude, pares, focoNoIndicativo)
                } else if (seguindo && focoNoIndicativo == null) {
                    // Acompanhar é um deslize curto, não um voo: a cada correção
                    // o agente andou alguns metros, e 260 ms de transição fazem o
                    // mapa deslizar por baixo da seta em vez de saltar. Saltar é
                    // o que faz um mapa em movimento parecer quebrado.
                    seguir(mapa, minhaLatitude, minhaLongitude)
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
private fun seguir(mapa: MapLibreMap, lat: Double, lon: Double) {
    val atual = mapa.cameraPosition.target ?: return
    if (Geo.distanciaM(atual.latitude, atual.longitude, lat, lon) < 8.0) return
    mapa.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)), 260)
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
        val cor = if (par.emMovimento) Cores.Vivo.toArgb() else Cores.Tinta.toArgb()
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
            .icon(icones.fromBitmap(setaDoPortador(meuRumoGraus))),
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
private fun setaDoPortador(rumoGraus: Float?): Bitmap {
    val lado = 56
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val centro = lado / 2f

    // Halo de precisão: sugere "por aqui", não "exatamente aqui".
    c.drawCircle(centro, centro, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.NoAr.toArgb()
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
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.NoAr.toArgb() }
        c.save()
        // O rumo do `Location` é graus a partir do norte, sentido horário — a
        // mesma convenção da tela, com o norte travado em cima.
        c.rotate(rumoGraus, centro, centro)
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
            color = Cores.NoAr.toArgb()
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
