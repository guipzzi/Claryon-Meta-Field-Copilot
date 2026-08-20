package com.claryon.agent

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Uma correção do GPS, reduzida ao que decide se ela é confiável. */
data class Correcao(
    val latitude: Double,
    val longitude: Double,
    /** Raio de erro em metros. [PRECISAO_DESCONHECIDA] quando o aparelho não disse. */
    val precisaoM: Float,
    /** `elapsedRealtimeNanos` — monotônico, imune a ajuste de relógio. */
    val nanos: Long,
) {
    companion object {
        /**
         * Precisão ausente **não vira zero**. Zero seria lido como "exato", que é o
         * oposto de "o aparelho não soube dizer".
         */
        const val PRECISAO_DESCONHECIDA = Float.MAX_VALUE
    }
}

/** Por que uma correção foi recusada. Vai para o log — recusa silenciosa é a pior. */
enum class MotivoDaRecusa { COORDENADA_INVALIDA, PRECISAO_DEGRADOU, SALTO_IMPLAUSIVEL }

sealed interface Veredito {
    data object Aceita : Veredito
    data class Recusada(val motivo: MotivoDaRecusa, val detalhe: String) : Veredito
}

/**
 * **Decide se uma correção do GPS merece ser publicada.**
 *
 * Duas famílias de lixo chegam pelo `LocationListener` e hoje passam direto:
 *
 *  1. **Degradação de precisão.** O provedor de rede entrega, no meio de uma
 *     sequência de fixes de GPS a 8 m, um ponto de torre com 1 200 m. O mapa
 *     desenha um marcador — um ponto, sem raio — e a guarnição lê como se fosse
 *     onde o agente está. `agentes_no_raio` decide o fan-out do alerta com ele.
 *
 *  2. **Salto implausível.** Reflexão em fachada de prédio, saída de túnel,
 *     primeira correção depois do avião: 5 km em 2 s. Publicado, isso apaga a
 *     posição boa e vira "o agente está do outro lado da cidade".
 *
 * **A armadilha, que é o motivo de esta classe ter estado.** Um filtro de salto
 * escrito da forma óbvia trava sozinho: ele compara sempre contra a última
 * ACEITA, então quando o salto é **verdadeiro** — o agente entrou numa viatura e
 * andou 3 km enquanto o túnel comia o sinal — toda correção nova é recusada por
 * discordar de um ponto que não é mais verdade, e o agente congela no mapa para
 * sempre. É o pior estado possível, porque o marcador não some: ele mente parado.
 *
 * Por isso [MAX_RECUSAS_SEGUIDAS]. Depois de três recusas em sequência, a porta
 * **cede**: passa a acreditar no aparelho e recomeça dali. O custo de um salto
 * verdadeiro é de três intervalos de correção; o custo de não ter a válvula é um
 * turno inteiro de posição falsa.
 *
 * Pura e testável: nenhuma dependência de Android.
 */
class PortaDeCorrecao(
    private val velocidadeImplausivelMs: Double = VELOCIDADE_IMPLAUSIVEL_MS,
    private val fatorDeDegradacao: Float = FATOR_DE_DEGRADACAO,
) {

    private var ultimaAceita: Correcao? = null
    private var recusasSeguidas = 0

    /** A última correção que passou pela porta, ou `null` antes da primeira. */
    fun ultima(): Correcao? = ultimaAceita

    fun avaliar(nova: Correcao): Veredito {
        val v = julgar(nova)
        if (v is Veredito.Recusada && recusasSeguidas + 1 <= MAX_RECUSAS_SEGUIDAS) {
            recusasSeguidas++
            return v
        }
        // Aceita — por mérito, ou porque a válvula cedeu na quarta tentativa.
        recusasSeguidas = 0
        val anterior = ultimaAceita
        // Correção mais VELHA que a referência é publicada (o servidor decide se
        // sobrescreve, ver `0020`), mas **não vira referência**: julgar a próxima
        // contra um ponto do passado ressuscitaria o salto que acabamos de aceitar.
        if (anterior == null || nova.nanos >= anterior.nanos) ultimaAceita = nova
        return Veredito.Aceita
    }

    private fun julgar(nova: Correcao): Veredito {
        if (!coordenadaValida(nova)) {
            return Veredito.Recusada(
                MotivoDaRecusa.COORDENADA_INVALIDA,
                "lat=${nova.latitude} lon=${nova.longitude}",
            )
        }

        val anterior = ultimaAceita ?: return Veredito.Aceita
        val idadeS = (nova.nanos - anterior.nanos) / 1_000_000_000.0

        // Correção mais VELHA que a última aceita: o `LocationManager` entrega
        // fora de ordem quando dois provedores concorrem. Não é salto nem
        // degradação — é passado, e passado não substitui presente.
        if (idadeS < 0) return Veredito.Aceita

        // **A porta de precisão é relativa, não absoluta.** Um teto fixo em metros
        // recusaria o modo Standby inteiro, que usa a rede de propósito e erra
        // 100–1000 m. O que denuncia lixo não é o número, é a DEGRADAÇÃO súbita
        // contra uma referência que ainda vale.
        val referenciaVale = idadeS < PoliticaDePosicao.OBSOLETO_S
        if (referenciaVale &&
            anterior.precisaoM.isFinite() &&
            nova.precisaoM > anterior.precisaoM * fatorDeDegradacao
        ) {
            return Veredito.Recusada(
                MotivoDaRecusa.PRECISAO_DEGRADOU,
                "${nova.precisaoM.toInt()}m contra ${anterior.precisaoM.toInt()}m há ${idadeS.toInt()}s",
            )
        }

        val distanciaM = distanciaM(anterior, nova)

        // **A incerteza combinada, e não a distância crua.** Dois pontos com 3 km
        // de erro cada podem "saltar" 5 km sem que ninguém tenha se movido: o
        // salto está dentro do ruído. Exigir que a distância supere a soma dos
        // raios é o que impede este filtro de recusar o Standby normal.
        val ruido = incerteza(anterior) + incerteza(nova)
        if (idadeS > 0 && distanciaM > ruido) {
            val velocidade = (distanciaM - ruido) / idadeS
            if (velocidade > velocidadeImplausivelMs) {
                return Veredito.Recusada(
                    MotivoDaRecusa.SALTO_IMPLAUSIVEL,
                    "${distanciaM.toInt()}m em ${idadeS.toInt()}s = ${velocidade.toInt()}m/s",
                )
            }
        }

        return Veredito.Aceita
    }

    /**
     * Precisão desconhecida vira o teto, não zero: "não sei" tem de tornar o
     * filtro mais tolerante, nunca mais agressivo. Zero faria uma correção sem
     * precisão declarada ser julgada como se fosse exata, e ela derrubaria a boa.
     */
    private fun incerteza(c: Correcao): Double =
        if (c.precisaoM.isFinite()) min(c.precisaoM.toDouble(), TETO_DE_INCERTEZA_M)
        else TETO_DE_INCERTEZA_M

    companion object {
        /**
         * 55 m/s ≈ 200 km/h. Acima disso, numa viatura de solo, é reflexão em
         * fachada ou primeira correção depois de perder o sinal — não deslocamento.
         */
        const val VELOCIDADE_IMPLAUSIVEL_MS = 55.0

        /**
         * 4×. Não é ajuste fino: é a diferença entre GPS (5–15 m) e torre de
         * celular (100–1000 m), que é a troca de provedor que este filtro pega.
         */
        const val FATOR_DE_DEGRADACAO = 4f

        /**
         * Três recusas seguidas; a quarta passa. Veja o KDoc da classe: sem a
         * válvula, um salto VERDADEIRO congela o marcador para sempre — e ele não
         * some, o que seria honesto: ele mente parado.
         */
        const val MAX_RECUSAS_SEGUIDAS = 3

        /**
         * Teto da incerteza que amortece o filtro de salto. Existe por causa de
         * [Correcao.PRECISAO_DESCONHECIDA]: sem teto, uma correção sem precisão
         * declarada daria ruído infinito e **desligaria o filtro**. 5 km é maior
         * que o pior erro de torre que se vê na prática (~1 km), então nenhuma
         * correção honesta encosta nele.
         */
        const val TETO_DE_INCERTEZA_M = 5_000.0

        fun coordenadaValida(c: Correcao): Boolean =
            !c.latitude.isNaN() && !c.longitude.isNaN() &&
                abs(c.latitude) <= 90.0 && abs(c.longitude) <= 180.0 &&
                !(c.latitude == 0.0 && c.longitude == 0.0)

        /**
         * Haversine. Não usa `Location.distanceTo` porque este módulo é puro — e
         * porque a diferença entre os dois, em quilômetros, é de centímetros.
         */
        fun distanciaM(a: Correcao, b: Correcao): Double {
            val rad = Math.PI / 180.0
            val dLat = (b.latitude - a.latitude) * rad
            val dLon = (b.longitude - a.longitude) * rad
            val h = sin(dLat / 2).let { it * it } +
                cos(a.latitude * rad) * cos(b.latitude * rad) * sin(dLon / 2).let { it * it }
            return 2 * RAIO_DA_TERRA_M * asin(sqrt(h).coerceAtMost(1.0))
        }

        private const val RAIO_DA_TERRA_M = 6_371_008.8
    }
}

/**
 * **Qual das correções disponíveis é a melhor** — não a mais nova.
 *
 * `ultimaPosicao()` escolhia por `elapsedRealtimeNanos` e só. O resultado é
 * conhecido: o provedor de rede responde em milissegundos com 1 200 m de erro,
 * o GPS tem um ponto de 20 s atrás com 8 m, e a mais nova ganha. A consulta por
 * voz — *"onde está o Sgt. Paiva?"* — mede a distância a partir do ponto de
 * torre e devolve um número com uma casa decimal, com cara de precisão.
 *
 * A regra tem três degraus, e o primeiro é o que impede o inverso do defeito:
 * uma correção de 8 m de dez minutos atrás **não** pode ganhar de uma de 500 m
 * de agora, porque em dez minutos o agente saiu dali. Idade primeiro, precisão
 * depois.
 */
object EscolhaDeCorrecao {

    /**
     * @param agoraNanos referência monotônica; injetada para o teste não depender
     *   de relógio.
     */
    fun <T> melhor(
        candidatas: List<T>,
        agoraNanos: Long,
        nanos: (T) -> Long,
        precisaoM: (T) -> Float,
    ): T? = candidatas.reduceOrNull { a, b ->
        if (aPrimeiraGanha(
                idadeSA = (agoraNanos - nanos(a)) / 1_000_000_000.0,
                precisaoA = precisaoM(a),
                idadeSB = (agoraNanos - nanos(b)) / 1_000_000_000.0,
                precisaoB = precisaoM(b),
            )
        ) a else b
    }

    fun aPrimeiraGanha(
        idadeSA: Double,
        precisaoA: Float,
        idadeSB: Double,
        precisaoB: Float,
    ): Boolean {
        // 1. Uma delas já passou do limiar de obsolescência e a outra não. Idade
        //    manda: posição precisa de onde o agente NÃO está mais é pior que
        //    posição grosseira de onde ele está.
        val aObsoleta = PoliticaDePosicao.marcadorObsoleto(idadeSA.toInt())
        val bObsoleta = PoliticaDePosicao.marcadorObsoleto(idadeSB.toInt())
        if (aObsoleta != bObsoleta) return bObsoleta

        // 2. Ambas dentro (ou ambas fora) da validade: decide a precisão. Aqui a
        //    diferença de segundos não importa — o agente não anda 100 m em 20 s
        //    a pé, mas o erro de 1 200 m da torre está lá o tempo todo.
        val a = if (precisaoA.isFinite()) precisaoA else Float.MAX_VALUE
        val b = if (precisaoB.isFinite()) precisaoB else Float.MAX_VALUE
        if (a != b) return a < b

        // 3. Empate técnico: a mais nova.
        return idadeSA <= idadeSB
    }
}
