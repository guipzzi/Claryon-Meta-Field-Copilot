package com.claryon.field.ui.componentes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions

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
 * distância e rumo; plotá-la sobre ruas exige reconstruí-la por
 * [Geo.destino], e a partir daí a coordenada alheia **existe** na memória deste
 * processo. Era o que o desenho anterior evitava.
 *
 * O que continua valendo: a coordenada nunca trafega, nunca repousa em banco
 * local, e nunca chega a quem não está no talk group — a migração 0010 fechou a
 * leitura direta de `agent_positions`. A reconstrução é feita pelo cliente
 * legítimo, com o dado que ele já recebeu, para tomar a decisão dele.
 *
 * **Tiles do OpenFreeMap**: livre, sem chave de API, sem registro. MapLibre e não
 * Google Maps porque o aparelho da corporação pode não ter Play Services — e um
 * mapa que não abre no aparelho institucional não é um mapa.
 */
@Composable
fun MapaDeRuas(
    minhaLatitude: Double,
    minhaLongitude: Double,
    pares: List<ParNoMapa>,
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
        MapLibre.getInstance(contexto)
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

    AndroidView(
        modifier = modifier,
        factory = {
            vista.apply {
                getMapAsync { mapa ->
                    mapa.setStyle(Style.Builder().fromUri(ESTILO_ESCURO)) {
                        // Sem bússola, sem logo deslocado, sem gestos de rotação: o
                        // norte fica em cima. Mapa que gira com o aparelho obriga a
                        // recalibrar a leitura a cada passo, e o agente está
                        // andando.
                        mapa.uiSettings.isRotateGesturesEnabled = false
                        mapa.uiSettings.isTiltGesturesEnabled = false
                        mapa.uiSettings.isCompassEnabled = false
                        mapa.uiSettings.setAttributionMargins(24, 0, 0, 24)

                        desenhar(mapa, icones, minhaLatitude, minhaLongitude, pares)
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { mapa ->
                // Só redesenha com o estilo pronto: `addMarker` antes disso é
                // descartado em silêncio, e o mapa abriria vazio na primeira
                // atualização de posição — que é a mais provável de todas.
                if (mapa.style?.isFullyLoaded == true) {
                    desenhar(mapa, icones, minhaLatitude, minhaLongitude, pares)
                }
            }
        },
    )
}

/**
 * Coloca o portador e os pares, e enquadra todo mundo.
 *
 * Enquadrar em vez de fixar o zoom: uma guarnição pode estar toda no mesmo
 * quarteirão ou espalhada por 5 km, e um zoom fixo erra nos dois casos — mostra
 * um ponto só, ou uma nuvem de pontos sem rua legível.
 */
private fun desenhar(
    mapa: org.maplibre.android.maps.MapLibreMap,
    icones: IconFactory,
    minhaLat: Double,
    minhaLon: Double,
    pares: List<ParNoMapa>,
) {
    mapa.clear()

    val eu = LatLng(minhaLat, minhaLon)
    mapa.addMarker(
        MarkerOptions()
            .position(eu)
            .icon(icones.fromBitmap(marcador(Cores.NoAr.toArgb(), portador = true))),
    )

    val pontos = mutableListOf(eu)
    pares.forEach { par ->
        val rumo = par.rumoGraus ?: return@forEach
        // A reconstrução. Ver o KDoc de `MapaDeRuas` sobre o que ela custa.
        val (lat, lon) = Geo.destino(minhaLat, minhaLon, par.distanciaM.toDouble(), rumo)
        val p = LatLng(lat, lon)
        pontos += p

        // Opacidade por frescor, como na lista: marcador cheio afirma "está ali",
        // esmaecido diz "estava". A regra é uma só, em três saídas.
        val alfa = when (par.frescor) {
            Frescor.ATUAL -> 255
            Frescor.ESMAECIDO -> 110
            Frescor.ANTIGO -> 60
        }
        val cor = if (par.emMovimento) Cores.Vivo.toArgb() else Cores.Tinta.toArgb()
        mapa.addMarker(
            MarkerOptions()
                .position(p)
                .title(par.indicativo)
                .snippet("${par.distanciaFalada} · ${par.atualizadoHa}")
                .icon(icones.fromBitmap(marcador(cor, alfa = alfa))),
        )
    }

    val camera = if (pontos.size > 1) {
        val limites = LatLngBounds.Builder().includes(pontos).build()
        org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(limites, 140)
    } else {
        org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(eu).zoom(15.5).build(),
        )
    }
    mapa.animateCamera(camera, 600)
}

/**
 * Marcador desenhado em código.
 *
 * Sem arquivo de recurso e sem biblioteca de ícones: são duas formas — disco para
 * par, cruz para o portador — e trazer um conjunto de ícones para isso pesaria
 * mais que o desenho inteiro num APK que já carrega dois modelos de IA.
 */
private fun marcador(cor: Int, alfa: Int = 255, portador: Boolean = false): Bitmap {
    val lado = if (portador) 44 else 36
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val centro = lado / 2f
    val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = cor
        this.alpha = alfa
    }

    if (portador) {
        // Cruz de mira: marca origem, e não "mais um par".
        tinta.strokeWidth = 3.5f
        c.drawCircle(centro, centro, centro - 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = cor
            this.alpha = 45
        })
        c.drawLine(centro - 10f, centro, centro + 10f, centro, tinta)
        c.drawLine(centro, centro - 10f, centro, centro + 10f, tinta)
    } else {
        // Halo escuro por baixo: sobre rua clara ou parque, o disco sozinho some.
        c.drawCircle(centro, centro, centro - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Cores.Vazio.toArgb()
            this.alpha = (alfa * 0.85f).toInt()
        })
        c.drawCircle(centro, centro, centro - 8f, tinta)
    }
    return bmp
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )

/** Estilo escuro do OpenFreeMap: 47 camadas, sem chave, sem cota declarada. */
private const val ESTILO_ESCURO = "https://tiles.openfreemap.org/styles/dark"
