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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import kotlin.math.cos
import kotlin.math.ln

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
 *
 * `MapLibre.getInstance` roda em [com.claryon.field.ClaryonApp], não aqui: a
 * `MapView` lança no construtor se a inicialização não tiver acontecido, e o
 * `remember` que a constrói roda durante a composição, antes de qualquer efeito.
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
        // **`ANTIGO` não entra no mapa.** Acima de dez minutos a lista já para de
        // afirmar posição e passa a dizer idade — e no mapa a afirmação é mais
        // forte, não mais fraca: um ponto sobre uma rua diz *endereço*. Plotar
        // uma correção de quarenta minutos como um pino desenharia a guarnição
        // num quarteirão onde ninguém está, que é o erro que a regra dos dois
        // minutos existe para impedir. A linha do par continua na lista abaixo,
        // dizendo a idade — some do mapa, não do painel.
        if (par.frescor == Frescor.ANTIGO) return@forEach
        val rumo = par.rumoGraus ?: return@forEach
        // A reconstrução. Ver o KDoc de `MapaDeRuas` sobre o que ela custa.
        val (lat, lon) = Geo.destino(minhaLat, minhaLon, par.distanciaM.toDouble(), rumo)
        val p = LatLng(lat, lon)
        pontos += p

        // Opacidade por frescor, como na lista: marcador cheio afirma "está ali",
        // esmaecido diz "estava".
        val alfa = when (par.frescor) {
            Frescor.ATUAL -> 255
            Frescor.ESMAECIDO -> 110
            Frescor.ANTIGO -> 0 // inalcançável: filtrado acima.
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

    // **O portador fica sempre no centro, e o zoom nunca sai da escala de rua.**
    //
    // Enquadrar todo mundo (`newLatLngBounds`) parece a escolha óbvia e é errada:
    // basta um par a 40 km — guarnição de área rural, ou uma posição publicada de
    // outro município — para a câmera abrir no estado inteiro. Foi o que aconteceu
    // no primeiro teste: o mapa mostrou de Curitiba a Brasília, sem uma rua
    // legível em lugar nenhum, para caber um ponto cinza.
    //
    // Rua legível é o motivo de este mapa existir. Então o zoom sai da distância
    // do par mais longe, preso entre bairro (13) e quarteirão (16,5); quem estiver
    // além disso simplesmente não cabe na tela — e a linha dele na lista continua
    // dando distância e rumo exatos, que é a informação precisa mesmo.
    val maisLonge = pares
        .filter { it.frescor != Frescor.ANTIGO && it.rumoGraus != null }
        .maxOfOrNull { it.distanciaM } ?: 0
    mapa.animateCamera(
        org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(eu).zoom(zoomPara(maisLonge, minhaLat)).build(),
        ),
        600,
    )
}

/**
 * Zoom que põe o par mais distante perto da borda, sem sair da escala de rua.
 *
 * Web Mercator: a resolução em metros por pixel é `156543 · cos(latitude) / 2^z`.
 * Invertendo para o raio desejado ocupar ~40% da altura útil (≈ 300 px num
 * aparelho de 720 px de largura), sai o `z` abaixo.
 *
 * Os limites não são arbitrários. **16,5** é o quarteirão — dá para ler o nome da
 * travessa, e é onde a câmera fica quando não há ninguém por perto, porque a
 * pergunta que sobra é "em que rua **eu** estou". **13** é o bairro: abaixo disso
 * o OpenFreeMap para de rotular via local, e um mapa sem nome de rua é o radar de
 * novo, só que mais pesado.
 */
private fun zoomPara(maiorDistanciaM: Int, latitude: Double): Double {
    if (maiorDistanciaM <= 0) return 16.5
    val metrosPorPixel = maiorDistanciaM / 300.0
    val z = ln(156_543.03 * cos(Math.toRadians(latitude)) / metrosPorPixel) / ln(2.0)
    return z.coerceIn(13.0, 16.5)
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
