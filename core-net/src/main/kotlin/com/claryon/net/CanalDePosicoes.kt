package com.claryon.net

import com.claryon.common.Geo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Posição de um par, como ela chega da rede: coordenada crua, com carimbo de
 * tempo de **origem** (o relógio de quem mediu), não de recepção.
 *
 * Carimbo de origem porque a idade da posição é o que decide se ela pode ser
 * afirmada como atual. Usar o instante de recepção esconderia exatamente o caso
 * perigoso: um pacote que ficou dez minutos numa fila chegaria "novo em folha".
 */
data class PosicaoDePar(
    val indicativo: String,
    val latitude: Double,
    val longitude: Double,
    /** Precisão informada pelo GPS de origem, em metros. */
    val precisaoM: Float,
    /** Velocidade em m/s. `null` quando a origem não soube medir. */
    val velocidadeMs: Float?,
    val medidaEmMs: Long,
)

/**
 * Estado do mapa: o que mostrar e como.
 *
 * `idadeS` é derivada na leitura, nunca armazenada — uma idade guardada
 * envelhece errado, porque o campo continua dizendo "5 s" enquanto o relógio
 * anda.
 */
data class MarcadorDePar(
    val posicao: PosicaoDePar,
    val idadeS: Int,
    val distanciaM: Int,
    val rumoGraus: Double,
    val emMovimento: Boolean,
)

/**
 * **Espelho das posições do talk group.**
 *
 * Duas regras duras estão codificadas aqui, e as duas são de segurança antes de
 * serem de energia:
 *
 *  1. **A assinatura dos pares só existe enquanto o mapa está visível.**
 *     [assinar] e [desassinar] são chamados pelo ciclo de vida da tela. Fora
 *     dela, o espelho fica vazio de propósito: numa guarnição de oito, difundir
 *     todos para todos seria tráfego permanente para uma tela fechada 95% do
 *     turno.
 *
 *  2. **Reciprocidade.** Não existe [assinar] sem publicar. Quem vê é visto —
 *     a assimetria de visibilidade entre pares é vigilância; a simetria é
 *     coordenação. Isso é garantido no servidor (RLS por talk group) *e* aqui,
 *     onde não há caminho para assinar sem que a própria posição suba.
 *
 * O espelho **não decide** o que é velho demais: ele entrega a idade e quem
 * desenha aplica a política. Uma regra, um dono.
 */
class CanalDePosicoes(
    private val publicador: PublicadorDePosicao,
    private val agora: () -> Long,
) {

    /**
     * `ConcurrentHashMap`, não `LinkedHashMap`.
     *
     * O espelho é escrito pela corrotina do WebSocket e lido pela tela. Com um
     * mapa comum, `marcadores()` iterava `values` enquanto `aoReceber()` inseria
     * de outra thread — `ConcurrentModificationException` no meio de um turno, na
     * tela que o agente abriu justamente porque precisava dela. A ordem de
     * inserção não fazia falta: a leitura ordena por distância.
     */
    private val espelho = ConcurrentHashMap<String, PosicaoDePar>()

    /**
     * `@Volatile` porque a thread do WebSocket precisa enxergar o `false` que a
     * thread da UI escreveu. Sem isso, um pacote atrasado podia ser gravado
     * **depois** do `clear()` de [desassinar], e o mapa reabriria com posição do
     * turno anterior — exatamente o que descartar o espelho existe para evitar.
     */
    @Volatile
    private var assinado = false

    /**
     * Serializa [assinar] e [desassinar].
     *
     * As duas suspendem no meio (chamada de rede), e sem exclusão mútua havia uma
     * corrida com consequência direta na regra do produto: fechar o mapa enquanto
     * `assinar` estava suspenso fazia `desassinar` rodar inteiro e, **depois**,
     * `assinar` retomar e gravar `assinado = true`. Resultado: assinatura viva com
     * o mapa fechado — a difusão de posição de todos para todos o turno inteiro,
     * que é a regra que esta classe existe para impor.
     */
    private val portao = Mutex()

    /** `true` enquanto a tela do mapa está viva. */
    fun assinando(): Boolean = assinado

    /**
     * Abre a assinatura dos pares. Exige que a publicação própria esteja ativa —
     * é a reciprocidade em forma de pré-condição, não de convenção.
     */
    suspend fun assinar(talkGroupId: String): Boolean = portao.withLock {
        if (!publicador.publicando()) return@withLock false
        assinado = publicador.assinarPares(talkGroupId)
        assinado
    }

    /** Fecha a assinatura e **descarta o espelho**. */
    suspend fun desassinar() = portao.withLock {
        assinado = false
        publicador.desassinarPares()
        // Descartar, não guardar: um espelho preservado voltaria a ser desenhado
        // na próxima abertura do mapa, com posições de horas atrás, antes de o
        // primeiro pacote novo chegar. O mapa mostraria o turno passado.
        espelho.clear()
    }

    /** Recebe uma posição da rede. Ignora se a assinatura não está aberta. */
    fun aoReceber(p: PosicaoDePar) {
        if (!assinado) return

        // Coordenada inválida é erro de sensor ou payload corrompido, não posição.
        // Aceita, `Geo.distanciaM` devolveria NaN, `.toInt()` transformaria em
        // **zero**, e zero ordena o par em primeiro lugar na lista: o mapa
        // afirmaria que o companheiro está em cima do agente. É o pior valor
        // possível para inventar — pior que não mostrar o marcador.
        if (!coordenadaValida(p.latitude, p.longitude)) return
        val anterior = espelho[p.indicativo]
        // Pacote fora de ordem não pode rebobinar o marcador: UDP-sobre-WebSocket
        // reordena, e uma posição antiga chegando depois faria o marcador saltar
        // para trás — que na tela é indistinguível do par ter voltado.
        if (anterior != null && anterior.medidaEmMs > p.medidaEmMs) return
        espelho[p.indicativo] = p
        // Reconferir DEPOIS de gravar. `desassinar` pode ter rodado entre a
        // checagem lá em cima e esta linha; sem isto o pacote sobreviveria ao
        // `clear()` e reapareceria no mapa da próxima abertura.
        if (!assinado) espelho.remove(p.indicativo)
    }

    /**
     * Marcadores relativos à posição informada, ordenados do mais próximo ao mais
     * distante — a ordem em que a informação é útil.
     */
    fun marcadores(minhaLat: Double, minhaLon: Double): List<MarcadorDePar> {
        val t = agora()
        return espelho.values
            .map { p -> marcador(p, minhaLat, minhaLon, t) }
            .sortedBy { it.distanciaM }
    }

    /**
     * Um par específico, por indicativo. Casamento **frouxo** de propósito: o STT
     * entrega "alfa dois" onde o cadastro tem "Alfa-02", e recusar por causa do
     * hífen faria a consulta por voz falhar exatamente onde ela precisa
     * funcionar.
     */
    fun marcadorDe(indicativo: String, minhaLat: Double, minhaLon: Double): MarcadorDePar? {
        val alvo = normalizar(indicativo)
        // Indicativo que normaliza para vazio — o STT devolvendo só pontuação —
        // fazia `startsWith("")` casar com tudo e devolver **o primeiro par do
        // espelho**. O app respondia distância e rumo de um companheiro qualquer,
        // com confiança total.
        if (alvo.isEmpty()) return null

        espelho.values.firstOrNull { normalizar(it.indicativo) == alvo }
            ?.let { return marcador(it, minhaLat, minhaLon, agora()) }

        // Prefixo só vale se for **único**. Com Alfa-01 e Alfa-02 na guarnição,
        // "alfa" devolvia o primeiro por ordem de inserção — o agente ouvia a
        // posição do par errado, que é o defeito que `ParNaoLocalizado` existe
        // para impedir. Ambíguo é o mesmo que não encontrado: melhor perguntar de
        // novo que apontar para o lugar errado.
        val porPrefixo = espelho.values.filter { normalizar(it.indicativo).startsWith(alvo) }
        return porPrefixo.singleOrNull()?.let { marcador(it, minhaLat, minhaLon, agora()) }
    }

    private fun marcador(
        p: PosicaoDePar,
        minhaLat: Double,
        minhaLon: Double,
        t: Long,
    ): MarcadorDePar {
        // coerceAtLeast(0): relógios de aparelhos diferentes divergem, e uma
        // posição "do futuro" viraria idade negativa — que a política de
        // obsolescência leria como recentíssima.
        val idade = ((t - p.medidaEmMs) / 1000L).coerceAtLeast(0L).toInt()
        return MarcadorDePar(
            posicao = p,
            idadeS = idade,
            distanciaM = Geo.distanciaM(minhaLat, minhaLon, p.latitude, p.longitude).toInt(),
            rumoGraus = Geo.rumoGraus(minhaLat, minhaLon, p.latitude, p.longitude),
            emMovimento = (p.velocidadeMs ?: 0f) > PARADO_MS,
        )
    }

    private fun coordenadaValida(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun normalizar(s: String): String =
        s.lowercase().replace(NAO_ALFANUMERICO, "")

    private companion object {
        /**
         * Abaixo disso é ruído de GPS parado, não deslocamento. O receptor de um
         * celular em ponto fixo relata 0,3–0,8 m/s de deriva; dizer "deslocando"
         * para uma viatura estacionada é pior que não dizer nada.
         */
        const val PARADO_MS = 1.0f

        val NAO_ALFANUMERICO = Regex("[^a-z0-9]")
    }
}

/**
 * O lado que fala com a rede. Interface para que o espelho — que carrega as
 * regras — seja testável sem socket.
 */
interface PublicadorDePosicao {
    /** `true` se a posição própria está subindo. Pré-condição da reciprocidade. */
    fun publicando(): Boolean

    suspend fun publicar(lat: Double, lon: Double, precisaoM: Float, velocidadeMs: Float?)

    suspend fun assinarPares(talkGroupId: String): Boolean

    suspend fun desassinarPares()
}
