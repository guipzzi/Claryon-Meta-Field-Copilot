package com.claryon.field.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PoliticaDePosicao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **Coleta de posição em segundo plano — o compartilhamento contínuo.**
 *
 * É o que faz a guarnição ver o agente com a tela apagada, no bolso, o turno
 * inteiro. O modelo é o do compartilhamento de localização ao vivo que as pessoas
 * já conhecem: liga uma vez, vale até o fim do turno.
 *
 * Quatro decisões carregam quase toda a economia de bateria, e nenhuma é
 * micro-otimização:
 *
 *  1. **O sistema operacional faz o estrangulamento, não um laço nosso.**
 *     `requestLocationUpdates(minTime, minDistance)` deixa o Android decidir
 *     quando acordar o rádio de GPS. Um `while(true) { getLastLocation(); delay() }`
 *     mantém o processo desperto e ignora o *batching* que o próprio sistema faz
 *     com outros apps — é a diferença entre pegar carona num despertar que já ia
 *     acontecer e criar um despertar novo.
 *
 *  2. **Provedor conforme o modo.** Em Standby usa a rede (torre e Wi-Fi), que
 *     custa quase nada e erra 100–1000 m — precisão irrelevante para um agente em
 *     pausa. GPS só em Ativo e Ocorrência, que é quando metro importa.
 *
 *  3. **Deslocamento como gatilho, não só tempo.** Agente parado em ponto fixo —
 *     boa parte do turno — quase não publica. O `minDistance` faz o próprio
 *     sistema não nos acordar, então parado custa zero, não "pouco".
 *
 *  4. **Posição NUNCA vai para fila offline.** Se a publicação falha, a posição é
 *     descartada e a próxima correção carrega o dado novo. Isto contraria o padrão
 *     do resto do produto — mensagem e alerta vão para a `outbox` durável — e é
 *     deliberado: uma posição de dez minutos atrás entregue agora não é
 *     informação atrasada, é **informação errada**. O mapa a mostraria como atual.
 *
 * O batimento existe para o caso do agente parado: sem ele, quem não se move
 * some do mapa por obsolescência, e companheiro que some parece em perigo.
 */
class ColetorDePosicao(
    private val context: Context,
    private val escopo: CoroutineScope,
    /** Publica no servidor. Recebe também a velocidade, que alimenta "deslocando". */
    private val publicar: suspend (lat: Double, lon: Double, precisaoM: Float, velocidadeMs: Float?) -> Unit,
    private val agoraMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var ouvinte: LocationListener? = null
    private var modoAtual: ModoOperacao? = null
    private var mapaVisivelAtual = false

    private var ultimaPublicada: Location? = null
    private var ultimaPublicacaoMs = 0L

    /** `true` enquanto a coleta está de pé. Alimenta a prontidão no perfil. */
    val coletando: Boolean get() = ouvinte != null

    /**
     * Liga ou reconfigura a coleta para [modo].
     *
     * Reconfigurar em vez de acumular: `removeUpdates` antes de qualquer novo
     * `requestLocationUpdates`. Sem isso, trocar de modo três vezes deixaria três
     * assinaturas vivas, cada uma acordando o GPS na sua cadência — e o consumo
     * viraria a soma delas.
     */
    fun ajustarPara(modo: ModoOperacao, mapaVisivel: Boolean) {
        if (!temPermissao()) {
            Log.w(TAG, "sem permissão de localização — coleta não sobe")
            return
        }
        if (modo == modoAtual && mapaVisivel == mapaVisivelAtual && coletando) return

        parar()
        val plano = PoliticaDePosicao.planoPara(modo, mapaVisivel)
        val provedor = provedorPara(modo) ?: run {
            Log.w(TAG, "nenhum provedor de localização disponível")
            return
        }

        val novo = LocationListener { local -> aoReceber(local, plano.deslocamentoMinimoM) }
        runCatching {
            @Suppress("MissingPermission")
            lm?.requestLocationUpdates(
                provedor,
                plano.intervaloMs,
                plano.deslocamentoMinimoM,
                novo,
                Looper.getMainLooper(),
            )
        }.onFailure {
            Log.w(TAG, "requestLocationUpdates falhou: ${it.message}")
            return
        }

        ouvinte = novo
        modoAtual = modo
        mapaVisivelAtual = mapaVisivel
        Log.i(
            TAG,
            "coleta em $modo por $provedor: ${plano.intervaloMs}ms / ${plano.deslocamentoMinimoM}m",
        )
    }

    fun parar() {
        ouvinte?.let { runCatching { lm?.removeUpdates(it) } }
        ouvinte = null
        modoAtual = null
    }

    private fun aoReceber(local: Location, deslocamentoMinimoM: Float) {
        if (!coordenadaValida(local)) return

        val anterior = ultimaPublicada
        val idadeDaUltima = agoraMs() - ultimaPublicacaoMs
        val andou = anterior == null ||
            anterior.distanceTo(local) >= deslocamentoMinimoM
        val batimentoVencido = idadeDaUltima >= BATIMENTO_MS

        // Publicar só quando andou **ou** quando o batimento venceu. O
        // `minDistance` já filtra a maior parte, mas o provedor de rede entrega
        // correções por tempo mesmo parado — sem esta porta, um agente sentado na
        // viatura publicaria a cada minuto pelo turno inteiro.
        if (!andou && !batimentoVencido) return

        ultimaPublicada = local
        ultimaPublicacaoMs = agoraMs()

        escopo.launch {
            runCatching {
                publicar(
                    local.latitude,
                    local.longitude,
                    if (local.hasAccuracy()) local.accuracy else Float.MAX_VALUE,
                    if (local.hasSpeed()) local.speed else null,
                )
            }.onFailure {
                // Falhou: **descarta**. A próxima correção carrega dado novo, e uma
                // posição velha entregue depois não é informação atrasada — é
                // informação errada, que o mapa mostraria como atual.
                Log.w(TAG, "publicação de posição falhou, descartando: ${it.message}")
            }
        }
    }

    /**
     * Em Standby, rede. Em operação, GPS.
     *
     * O provedor de rede consome perto de zero — resolve por torre e Wi-Fi, sem
     * ligar o receptor — e erra de 100 m a 1 km. Para um agente em pausa isso é
     * suficiente: a guarnição precisa saber o setor, não a esquina.
     */
    private fun provedorPara(modo: ModoOperacao): String? {
        val preferido = when (modo) {
            ModoOperacao.STANDBY -> LocationManager.NETWORK_PROVIDER
            ModoOperacao.ATIVO, ModoOperacao.OCORRENCIA -> LocationManager.GPS_PROVIDER
        }
        val disponiveis = runCatching { lm?.getProviders(true).orEmpty() }.getOrDefault(emptyList())
        return when {
            preferido in disponiveis -> preferido
            // Degrada em vez de sumir: sem GPS (garagem, subsolo), a posição de
            // rede ainda coloca o agente no mapa. Some do mapa é o pior estado.
            LocationManager.GPS_PROVIDER in disponiveis -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in disponiveis -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun temPermissao(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun coordenadaValida(l: Location): Boolean =
        l.latitude.isFinite() && l.longitude.isFinite() &&
            l.latitude in -90.0..90.0 && l.longitude in -180.0..180.0

    private companion object {
        const val TAG = "ClaryonField"

        /**
         * 3 min. Agente parado precisa reaparecer antes de o marcador esmaecer
         * (2 min) virar "antigo" (10 min) — some do mapa é o pior estado, porque
         * companheiro que desaparece parece em perigo.
         *
         * Publicar a cada 3 min parado custa ~20 requisições por turno de 8 h.
         */
        const val BATIMENTO_MS = 3 * 60 * 1000L
    }
}
