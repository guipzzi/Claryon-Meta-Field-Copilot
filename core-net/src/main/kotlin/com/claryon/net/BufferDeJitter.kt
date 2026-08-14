package com.claryon.net

/** O que o receptor deve fazer no próximo intervalo de quadro. */
sealed interface SaidaDoJitter {

    /** Decodificar e reproduzir este quadro. */
    data class Reproduzir(val quadro: QuadroAudio) : SaidaDoJitter

    /**
     * O quadro [sequencia] não chegou a tempo: reconstruir por **PLC** (a
     * interpolação do próprio Opus), nunca preencher com silêncio. Silêncio soa
     * como corte; interpolação soa como voz.
     */
    data class Interpolar(val sequencia: Int) : SaidaDoJitter

    /** Ainda enchendo o buffer inicial, ou sem nada a tocar. */
    data object Aguardando : SaidaDoJitter

    /** A transmissão terminou e tudo o que havia foi entregue. */
    data object Fim : SaidaDoJitter
}

/**
 * **Buffer de jitter adaptativo do receptor.**
 *
 * É o ponto onde a maioria dos projetos de áudio erra, e erra para os dois lados:
 * buffer grande demais soa lento (atraso desnecessário), pequeno demais soa
 * picotado. A saída é começar pequeno e **crescer só sob perda medida** —
 * buffer fixo grande é a forma mais comum de jogar fora 200 ms sem necessidade.
 *
 * Puro e sem relógio: o chamador aciona [proximo] a cada intervalo de quadro. Isso
 * torna a política inteira testável em milissegundos, sem esperar tempo real.
 *
 * @param inicialMs profundidade inicial. 100 ms ≈ 5 quadros de 20 ms.
 * @param minMs piso em rede estável — ganha ~40 ms sobre o inicial.
 * @param maxMs teto sob perda. Além disso o atraso passa a incomodar mais que o picote.
 */
class BufferDeJitter(
    private val quadroMs: Int = 20,
    private val inicialMs: Int = INICIAL_MS,
    private val minMs: Int = MIN_MS,
    private val maxMs: Int = MAX_MS,
    private val janelaDePerda: Int = JANELA_DE_PERDA,
) {

    private val pendentes = HashMap<Int, QuadroAudio>()

    /** Sequências já entregues (ou interpoladas) — evita tocar atrasado fora de ordem. */
    private var proximaEsperada = 0

    private var iniciado = false
    private var fimSinalizado = false
    private var ultimaSequencia: Int? = null

    /** Histórico recente: `true` = precisou interpolar. Janela deslizante. */
    private val historico = ArrayDeque<Boolean>()

    private var acertosSeguidos = 0

    var atrasoAtualMs: Int = inicialMs
        private set

    /** Fração de quadros perdidos na janela recente, em [0,1]. */
    val perdaRecente: Double
        get() = if (historico.isEmpty()) 0.0 else historico.count { it }.toDouble() / historico.size

    /**
     * `true` quando a perda passa de 30% — o agente precisa **saber que o canal
     * está ruim antes de confiar nele**. Quem consome dispara um tom discreto.
     */
    fun degradado(): Boolean = historico.size >= janelaDePerda / 2 && perdaRecente > LIMIAR_DEGRADACAO

    private val profundidadeEmQuadros: Int get() = maxOf(1, atrasoAtualMs / quadroMs)

    /** Recebe um quadro da rede. Quadros atrasados demais são descartados. */
    fun receber(quadro: QuadroAudio) {
        // Já passou o momento de tocar este: reproduzi-lo agora quebraria a
        // ordem e soaria pior que a interpolação que já saiu no lugar dele.
        if (iniciado && quadro.sequencia < proximaEsperada) return

        pendentes[quadro.sequencia] = quadro
        if (quadro.ultimo) ultimaSequencia = quadro.sequencia
    }

    /**
     * O que tocar agora. Chamado uma vez por intervalo de quadro.
     *
     * Enche até [profundidadeEmQuadros] antes de começar: sem essa espera
     * inicial, a primeira variação de rede já produziria um buraco.
     */
    fun proximo(): SaidaDoJitter {
        if (fimSinalizado) return SaidaDoJitter.Fim

        if (!iniciado) {
            if (pendentes.size < profundidadeEmQuadros) return SaidaDoJitter.Aguardando
            iniciado = true
            proximaEsperada = pendentes.keys.min()
        }

        val esperado = proximaEsperada
        val quadro = pendentes.remove(esperado)

        if (quadro != null) {
            proximaEsperada = esperado + 1
            registrar(perdeu = false)
            if (ultimaSequencia == esperado) fimSinalizado = true
            return SaidaDoJitter.Reproduzir(quadro)
        }

        // Faltou o esperado. Se nada mais chegou depois dele, é fim de fluxo ou
        // rede parada — aguardar. Se já há quadros posteriores, o esperado se
        // perdeu de fato: interpola e segue, senão o áudio travaria esperando.
        val temPosterior = pendentes.keys.any { it > esperado }
        if (!temPosterior) {
            if (ultimaSequencia != null && esperado > ultimaSequencia!!) {
                fimSinalizado = true
                return SaidaDoJitter.Fim
            }
            return SaidaDoJitter.Aguardando
        }

        proximaEsperada = esperado + 1
        registrar(perdeu = true)
        return SaidaDoJitter.Interpolar(esperado)
    }

    /** Reinicia para uma nova transmissão. */
    fun reiniciar() {
        pendentes.clear()
        historico.clear()
        proximaEsperada = 0
        iniciado = false
        fimSinalizado = false
        ultimaSequencia = null
        acertosSeguidos = 0
        atrasoAtualMs = inicialMs
    }

    private fun registrar(perdeu: Boolean) {
        historico.addLast(perdeu)
        while (historico.size > janelaDePerda) historico.removeFirst()

        if (perdeu) {
            acertosSeguidos = 0
            // Cresce um quadro por perda, até o teto.
            atrasoAtualMs = (atrasoAtualMs + quadroMs).coerceAtMost(maxMs)
        } else {
            acertosSeguidos++
            // Encolhe devagar e só depois de estabilidade demonstrada: encolher
            // na primeira sequência boa faria o buffer oscilar junto com a rede.
            if (acertosSeguidos >= ACERTOS_PARA_ENCOLHER) {
                acertosSeguidos = 0
                atrasoAtualMs = (atrasoAtualMs - quadroMs).coerceAtLeast(minMs)
            }
        }
    }

    companion object {
        const val INICIAL_MS = 100
        const val MIN_MS = 60
        const val MAX_MS = 300
        const val JANELA_DE_PERDA = 50
        const val LIMIAR_DEGRADACAO = 0.30
        const val ACERTOS_PARA_ENCOLHER = 25
    }
}
