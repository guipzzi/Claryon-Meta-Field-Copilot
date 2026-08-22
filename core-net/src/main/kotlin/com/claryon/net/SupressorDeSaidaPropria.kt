package com.claryon.net

/**
 * **Nossa saída nunca entra na nossa entrada.**
 *
 * Os alto-falantes dos óculos são *open-ear*, a centímetros do array de
 * microfones. Todo som que produzimos é um som que vamos capturar — e isso não é
 * um defeito, é uma **classe** de defeitos:
 *
 *  1. O tom de início do PTT entraria na própria transmissão. Pior: sendo alta
 *     energia, o VAD retroativo do pré-roll o marcaria como "início da fala",
 *     ancorando a transmissão no bipe em vez de na voz — o recurso que existe
 *     para não cortar a primeira sílaba passaria a cortá-la.
 *  2. A cauda de uma transmissão recebida entraria na nossa resposta. O controle
 *     de piso cobre o miolo (quem recebe não detém o canal), mas os últimos
 *     quadros ainda tocando depois da liberação vazariam de volta ao grupo.
 *  3. A fala do copiloto (TTS) entraria na transmissão.
 *  4. O detector de palavra de ativação acordaria com a própria saída.
 *
 * Um mecanismo resolve os quatro: registrar as janelas em que **nós** estamos
 * emitindo som e descartar da captura tudo que cair dentro delas.
 *
 * Descartar é a decisão certa, e não um mal menor: enquanto o alto-falante toca,
 * o microfone capta a mistura da nossa saída com a voz do agente, e não há como
 * separá-las. Perder 200 ms de sobreposição é melhor que difundir o próprio
 * áudio para a guarnição inteira. No caso da cauda de recepção, descartar **é** a
 * disciplina de meio-duplex.
 *
 * Puro e sem relógio — o tempo entra por parâmetro, então a política é testável
 * em milissegundos.
 *
 * ## Toda janela tem fim — e isso é conserto de 22/08, não estilo
 *
 * Havia uma segunda forma de janela: `abrir(t)` sem fim previsto, fechada por
 * `fechar(t)` quando a transmissão recebida terminasse. Ela parecia certa (a
 * duração de uma fala alheia só se conhece quando o colega solta o botão) e
 * produziu uma falha silenciosa cara, porque **quem fechava não era quem tocava**:
 * o fechamento vinha de `EventoRecepcao.Terminou`, e o receptor leva 2 s para
 * concluir que uma fala foi cortada pela rede. Nesses 2 s não saía som nenhum do
 * alto-falante, o piso já estava livre, e o próximo agente a apertar o PTT tinha
 * a fala **inteira descartada** com a barra de PTT no ar e nenhum tom avisando.
 *
 * Dois mecanismos corretos, uma composição errada. A correção é estrutural:
 * existe **um** jeito de registrar som nosso, e ele exige dizer por quanto tempo.
 * Quem reproduz um fluxo de duração desconhecida registra bloco a bloco, com a
 * duração de cada bloco — que ele conhece, porque acabou de decodificá-lo. Uma
 * janela sem fim é uma promessa que alguém tem de lembrar de cumprir, e é o tipo
 * de promessa que este projeto já viu não ser cumprida.
 *
 * @param margemMs guarda depois do fim da reprodução, para a cauda de
 *   reverberação do ambiente e o assentamento do alto-falante. Sem ela, o último
 *   eco entra no primeiro quadro transmitido. É também ela que emenda dois blocos
 *   consecutivos de 20 ms numa janela contínua.
 */
class SupressorDeSaidaPropria(private val margemMs: Long = MARGEM_MS) {

    private data class Janela(val inicio: Long, val fim: Long)

    private val janelas = ArrayList<Janela>()

    /**
     * Registra reprodução de duração conhecida — earcon, tom, fala sintetizada,
     * ou **um bloco** de uma transmissão recebida.
     */
    fun registrar(inicioMs: Long, duracaoMs: Long) {
        janelas.add(Janela(inicioMs, inicioMs + duracaoMs))
    }

    /**
     * `true` se o instante cai numa janela em que estávamos emitindo som — logo,
     * o quadro capturado ali contém a nossa própria saída e não pode ser
     * transmitido nem alimentar o pré-roll ou o detector de ativação.
     */
    fun suprimido(instanteMs: Long): Boolean =
        janelas.any { instanteMs >= it.inicio && instanteMs <= it.fim + margemMs }

    /**
     * Descarta janelas que já não podem afetar nada antes de [instanteMs].
     *
     * Sem poda, um turno inteiro de operação acumularia uma janela por earcon —
     * milhares — e cada checagem varreria a lista toda, num caminho que roda 50
     * vezes por segundo.
     */
    fun podarAntesDe(instanteMs: Long) {
        janelas.removeAll { it.fim + margemMs < instanteMs }
    }

    /** Quantas janelas ainda são consideradas (diagnóstico e teste). */
    val janelasVivas: Int get() = janelas.size

    fun limpar() {
        janelas.clear()
    }

    companion object {
        /**
         * 80 ms cobre reverberação de ambiente fechado (viatura, corredor) e o
         * assentamento do alto-falante. Medir em campo e ajustar: é o tipo de
         * número que só o hardware real resolve.
         */
        const val MARGEM_MS = 80L
    }
}
