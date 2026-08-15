package com.claryon.net

/** O que fazer com um toque no gatilho de PTT. */
sealed interface DecisaoDeGatilho {

    /** Começa a transmitir. */
    data object Iniciar : DecisaoDeGatilho

    /** Toque cedo demais depois do anterior — repique de botão ou duplo aperto. */
    data object IgnoradoPorRepique : DecisaoDeGatilho

    /** Já há transmissão em curso; o segundo aperto não abre outra. */
    data object JaTransmitindo : DecisaoDeGatilho
}

/** O que fazer ao soltar. */
sealed interface DecisaoDeSoltura {

    /** Encerramento normal: último quadro, libera canal. */
    data class Encerrar(val duracaoMs: Long) : DecisaoDeSoltura

    /**
     * Toque curto demais para ser fala. **Aborta e descarta**: o agente encostou
     * no botão sem intenção de falar, e transmitir 80 ms de ruído para a
     * guarnição é pior que não transmitir nada.
     */
    data class AbortarPorToqueCurto(val duracaoMs: Long) : DecisaoDeSoltura

    /** Soltura sem aperto correspondente — evento espúrio do sistema. */
    data object SemTransmissao : DecisaoDeSoltura
}

/**
 * **Gatilho do push-to-talk: decide o que é intenção de falar.**
 *
 * O botão físico não é confiável por si só. Três coisas acontecem em campo e
 * todas precisam de resposta:
 *
 *  - **Repique.** Botões mecânicos geram múltiplos eventos por acionamento, e o
 *    agente com luva gera mais ainda. Sem debounce, um aperto vira duas
 *    transmissões — a segunda cortando a primeira no controle de piso.
 *  - **Toque acidental.** Encostar no botão no cinto não pode difundir ruído
 *    para a guarnição.
 *  - **Botão preso.** Coberto pelo teto de duração em [SessaoPtt]; aqui só se
 *    garante que um segundo aperto durante a transmissão não abra outra.
 *
 * **Só existe um acionador: o bloco de fala na tela.** O toque na haste dos óculos
 * foi medido e **pausa a sessão de streaming** — apertar para falar derrubaria a
 * própria transmissão. O botão de volume chegou a ficar registrado como primário,
 * mas nunca passou de texto: sequestrar o volume de um aparelho institucional é
 * efeito colateral que ninguém pediu. Ver `DECISIONS.md`.
 *
 * Isto continua valendo, e é o motivo de a classe existir mesmo com um gatilho só:
 * o dedo na tela repica, escorrega e encosta sem intenção tanto quanto um botão
 * mecânico.
 *
 * Puro e sem relógio: o tempo entra por parâmetro.
 *
 * @param duracaoMinimaMs abaixo disso o toque é acidental. 150 ms é curto o
 *   bastante para não atrapalhar quem fala rápido e longo o bastante para
 *   descartar um encosto.
 * @param repiqueMs janela morta depois de uma soltura.
 */
class GatilhoPtt(
    private val duracaoMinimaMs: Long = DURACAO_MINIMA_MS,
    private val repiqueMs: Long = REPIQUE_MS,
) {

    private var pressionadoEm: Long? = null
    private var ultimaSolturaEm: Long? = null

    /** `true` enquanto uma transmissão está em curso. */
    val transmitindo: Boolean get() = pressionadoEm != null

    fun aoPressionar(agoraMs: Long): DecisaoDeGatilho {
        if (pressionadoEm != null) return DecisaoDeGatilho.JaTransmitindo

        val ultima = ultimaSolturaEm
        if (ultima != null && agoraMs - ultima < repiqueMs) {
            return DecisaoDeGatilho.IgnoradoPorRepique
        }

        pressionadoEm = agoraMs
        return DecisaoDeGatilho.Iniciar
    }

    fun aoSoltar(agoraMs: Long): DecisaoDeSoltura {
        val inicio = pressionadoEm ?: return DecisaoDeSoltura.SemTransmissao
        pressionadoEm = null
        ultimaSolturaEm = agoraMs

        val duracao = agoraMs - inicio
        return if (duracao < duracaoMinimaMs) {
            DecisaoDeSoltura.AbortarPorToqueCurto(duracao)
        } else {
            DecisaoDeSoltura.Encerrar(duracao)
        }
    }

    /**
     * Cancela a transmissão sem passar por soltura — usado quando o canal é
     * tomado por emergência ou a rota de áudio cai. Marca o repique para o
     * agente não reabrir por reflexo em cima do próprio cancelamento.
     */
    fun cancelar(agoraMs: Long) {
        pressionadoEm = null
        ultimaSolturaEm = agoraMs
    }

    companion object {
        const val DURACAO_MINIMA_MS = 150L
        const val REPIQUE_MS = 250L
    }
}
