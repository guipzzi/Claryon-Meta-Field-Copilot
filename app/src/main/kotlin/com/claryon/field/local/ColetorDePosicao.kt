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
import com.claryon.agent.Correcao
import com.claryon.agent.PlanoDePosicao
import com.claryon.agent.PoliticaDePosicao
import com.claryon.agent.PortaDeCorrecao
import com.claryon.agent.Veredito
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
 *     boa parte do turno — quase não publica. O filtro mora em [aoReceber]: o
 *     `minDistance` do `requestLocationUpdates` fica em **zero**, de propósito.
 *
 *     Ele já esteve preenchido, e por 20/08 esta lista dizia que isso fazia "o
 *     próprio sistema não nos acordar, então parado custa zero". Custava mais que
 *     zero: custava o batimento inteiro. A moldura é explícita — *"if a potential
 *     location update is closer to the last location update than the minimum
 *     update distance, then the potential location update will not occur"* — então
 *     um agente parado **não recebia callback nenhum**, `aoReceber` nunca rodava,
 *     e a linha que testa o batimento era inalcançável. O parágrafo seguinte deste
 *     mesmo KDoc afirmava que o batimento existia para o agente parado. Os dois
 *     não podiam ser verdade, e o errado era este.
 *
 *     Quem sofria mais era o modo Ocorrência: o agente chega no local e fica
 *     parado, que é exatamente quando a guarnição precisa saber onde ele está — e
 *     era aí que ele esmaecia aos 2 min e virava "antigo" aos 10.
 *
 *     O que se perde com `minDistance = 0` é a supressão da ENTREGA, não o ciclo
 *     do rádio de GPS, que é governado por `minTime`. Para um agente em
 *     movimento, o tráfego no fio é idêntico ao de antes: o mesmo filtro, no
 *     mesmo metro, só que num lugar onde o batimento também pode votar.
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
    /**
     * Publica no servidor. Recebe também a velocidade, que alimenta "deslocando",
     * e o `elapsedRealtimeNanos` da correção, de onde sai a idade real.
     */
    private val publicar: suspend (
        lat: Double,
        lon: Double,
        precisaoM: Float,
        velocidadeMs: Float?,
        nanosDaCorrecao: Long,
    ) -> Unit,
    private val agoraMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /**
     * Recriada a cada `ajustarPara`: a porta guarda a última correção aceita, e
     * mudar de provedor troca a escala de precisão inteira (GPS 8 m ↔ rede
     * 800 m). Carregar a referência do GPS para dentro do Standby faria a
     * primeira correção de rede ser recusada por "degradação" três vezes seguidas
     * até a válvula ceder — recusa correta pela regra, errada pelo mundo.
     */
    private var porta = PortaDeCorrecao()

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

        val novo = LocationListener { local -> aoReceber(local, plano) }
        runCatching {
            @Suppress("MissingPermission")
            lm?.requestLocationUpdates(
                provedor,
                plano.intervaloMs,
                // **Zero, e não `plano.deslocamentoMinimoM`.** O filtro de
                // deslocamento continua existindo — mudou de lugar, para dentro de
                // `aoReceber`, onde o batimento também pode votar. Ver o KDoc da
                // classe: enquanto ele estava aqui, agente parado não recebia
                // callback nenhum e o batimento era código inalcançável.
                0f,
                novo,
                Looper.getMainLooper(),
            )
        }.onFailure {
            Log.w(TAG, "requestLocationUpdates falhou: ${it.message}")
            return
        }

        porta = PortaDeCorrecao()
        ouvinte = novo
        modoAtual = modo
        mapaVisivelAtual = mapaVisivel
        Log.i(
            TAG,
            "coleta em $modo por $provedor: ${plano.intervaloMs}ms, publica a cada " +
                "${plano.deslocamentoMinimoM}m ou ${plano.batimentoEfetivoMs / 1000}s parado",
        )
    }

    fun parar() {
        ouvinte?.let { runCatching { lm?.removeUpdates(it) } }
        ouvinte = null
        modoAtual = null
    }

    private fun aoReceber(local: Location, plano: PlanoDePosicao) {
        if (!coordenadaValida(local)) return

        // **A porta de qualidade vem antes da porta de cadência**, e a ordem
        // importa: julgar a cadência primeiro faria uma correção-lixo "resetar" o
        // relógio do batimento sem nunca ser publicada, e o agente sumiria do mapa
        // exatamente enquanto o GPS estivesse ruim — que é quando ele mais precisa
        // aparecer.
        val correcao = Correcao(
            latitude = local.latitude,
            longitude = local.longitude,
            precisaoM = if (local.hasAccuracy()) local.accuracy else Correcao.PRECISAO_DESCONHECIDA,
            nanos = local.elapsedRealtimeNanos,
        )
        when (val v = porta.avaliar(correcao)) {
            is Veredito.Recusada -> {
                // Recusa silenciosa é a pior: some do mapa sem ninguém saber por quê.
                Log.i(TAG, "correção recusada (${v.motivo}): ${v.detalhe}")
                return
            }
            Veredito.Aceita -> Unit
        }

        val anterior = ultimaPublicada
        val idadeDaUltima = agoraMs() - ultimaPublicacaoMs
        val andou = anterior == null ||
            anterior.distanceTo(local) >= plano.deslocamentoMinimoM
        val batimentoVencido = idadeDaUltima >= plano.batimentoMs

        // Publicar só quando andou **ou** quando o batimento venceu. O filtro de
        // deslocamento mora aqui — e não mais no `requestLocationUpdates` — porque
        // lá ele suprimia o callback inteiro e o batimento nunca era avaliado.
        // Para um agente EM MOVIMENTO o comportamento no fio é idêntico ao de
        // antes; a diferença aparece só quando ele para, que é o caso que o
        // batimento existe para cobrir.
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
                    // A idade real da correção. O servidor faz `now() - idade`;
                    // ver `0020_idade_real_da_correcao.sql` para por que não pode
                    // ser um instante.
                    local.elapsedRealtimeNanos,
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
    }
}
