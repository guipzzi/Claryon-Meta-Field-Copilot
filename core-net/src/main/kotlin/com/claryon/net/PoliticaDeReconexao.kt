package com.claryon.net

/**
 * **Backoff exponencial com teto — a política que protege a bateria numa zona
 * sem sinal.**
 *
 * Reconectar em laço apertado onde não há cobertura drena a bateria mais rápido
 * que qualquer outra coisa do produto: o rádio fica ligado permanentemente
 * tentando associar. O teto de 5 minutos é o que impede isso.
 *
 * O outro lado da moeda: quando a rede **volta**, esperar até 5 minutos seria
 * inaceitável. Por isso [aoDetectarRedeDisponivel] zera o backoff — o sistema
 * avisa por callback de conectividade, e a retomada é imediata.
 *
 * Pura e sem relógio: o chamador aplica o atraso devolvido.
 */
class PoliticaDeReconexao(
    private val baseMs: Long = BASE_MS,
    private val tetoMs: Long = TETO_MS,
    private val fator: Double = 2.0,
) {

    private var tentativas = 0

    /** Quantas falhas seguidas desde a última conexão bem-sucedida. */
    val tentativasSeguidas: Int get() = tentativas

    /** Próximo atraso, em ms. Cresce a cada falha até [tetoMs]. */
    fun proximoAtrasoMs(): Long {
        val bruto = baseMs * Math.pow(fator, tentativas.toDouble())
        tentativas++
        return bruto.toLong().coerceIn(baseMs, tetoMs)
    }

    /** Conectou: o próximo problema recomeça do início. */
    fun aoConectar() {
        tentativas = 0
    }

    /**
     * O sistema avisou que há rede. Zera o backoff para a retomada ser imediata
     * — sem isto, o agente ficaria offline por minutos depois de sair de um
     * elevador ou de um subsolo.
     */
    fun aoDetectarRedeDisponivel() {
        tentativas = 0
    }

    companion object {
        const val BASE_MS = 500L
        const val TETO_MS = 5 * 60 * 1000L
    }
}
