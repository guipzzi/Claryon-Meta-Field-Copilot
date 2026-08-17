package com.claryon.field.voice

import com.claryon.common.Telemetry
import com.claryon.net.Medicao

/**
 * **Implementação de [Telemetry] — a primeira que existe.**
 *
 * `Telemetry` era uma interface com um único implementador: `NoOp`. Nenhum
 * estágio do ciclo de voz era observável em runtime, então as três metas de
 * latência do produto ("fim da fala → earcon ≤ 500 ms", "→ resposta local
 * ≤ 2,0 s") eram adjetivo.
 *
 * ## O que ela mede, e o que se recusa a medir
 *
 * Marcos vêm de dois lugares, e a diferença importa:
 *
 *  - [Telemetry.Stage.VAD_WINDOW_CLOSED], `STT_DONE`, `INTENT_ROUTED` e
 *    `ACTION_DONE` são marcados pelo [VoiceCycle], que os executa em sequência.
 *  - [Telemetry.Stage.EARCON_PLAYED] e `RESPONSE_FIRST_AUDIO` são marcados pelo
 *    **dono da saída**, no instante em que o PCM começa a ser escrito no
 *    `AudioTrack` — nunca no `emitir`, que só **enfileira**. Marcar no
 *    enfileiramento daria um número bonito e falso: a fila pode estar ocupada
 *    com uma emergência, e o agente ouviria o earcon segundos depois do
 *    "≤ 400 ms" que o relatório afirmaria.
 *
 * [Telemetry.Stage.WAKE_DETECTED] **não tem produtor** e é honesto que não
 * tenha: `WakeWordDetector` é interface sem implementação (Fase 2). O relatório
 * mostra "sem amostras" em vez de zero.
 *
 * ## Ciclo corrente
 *
 * Os marcos de reprodução acontecem noutro objeto e noutra corrotina, então
 * precisam saber a qual ciclo pertencem. Como só existe **um** ciclo de voz por
 * vez (o botão do copiloto é bloqueado por `copilotoOcupado` enquanto roda),
 * um "ciclo corrente" resolve sem precisar propagar identificador por cinco
 * camadas. Se um dia houver ciclos concorrentes, este é o ponto que quebra — e
 * quebra alto, com marcos atribuídos ao ciclo errado, não em silêncio.
 */
class TelemetriaDoCicloDeVoz(private val capacidade: Int = CAPACIDADE) : Telemetry {

    private val trava = Any()

    /** cycleId → (estágio → instante). */
    private val marcos = LinkedHashMap<String, MutableMap<Telemetry.Stage, Long>>()

    /** Durações já fechadas, por transição medida. */
    private val duracoes = HashMap<Transicao, ArrayDeque<Long>>()

    @Volatile
    var cicloCorrente: String? = null
        private set

    /** Transições que valem meta declarada. */
    enum class Transicao(val rotulo: String, val metaMs: Long?) {
        FIM_DA_FALA_ATE_EARCON("fim da fala → earcon", 500),
        FIM_DA_FALA_ATE_RESPOSTA("fim da fala → resposta falada", 2_000),
        STT("transcrição (whisper)", null),
        ACAO("execução da ação", null),

        /**
         * Chegada de um som de emergência → cancelamento do que tocava.
         *
         * Não pertence a um ciclo de voz: é a arbitragem entre subsistemas que a
         * fila única destravou. Fica aqui porque é o mesmo relatório que a banca
         * vai ler, e separar em duas classes por pureza taxonômica só faria o
         * número ficar mais difícil de achar.
         */
        PREEMPCAO_DE_EMERGENCIA("P1 corta o que está tocando", 200),
    }

    /** Registra uma preempção medida. Ver [Transicao.PREEMPCAO_DE_EMERGENCIA]. */
    fun registrarPreempcao(atrasoMs: Long) = synchronized(trava) {
        if (atrasoMs < 0) return@synchronized
        val fila = duracoes.getOrPut(Transicao.PREEMPCAO_DE_EMERGENCIA) { ArrayDeque() }
        fila.addLast(atrasoMs)
        while (fila.size > capacidade) fila.removeFirst()
    }

    fun abrirCiclo(cycleId: String) = synchronized(trava) {
        cicloCorrente = cycleId
        marcos[cycleId] = LinkedHashMap()
        while (marcos.size > capacidade) {
            val maisAntigo = marcos.keys.first()
            marcos.remove(maisAntigo)
        }
    }

    fun fecharCiclo() = synchronized(trava) { cicloCorrente = null }

    /**
     * **O primeiro marco de cada estágio vence.**
     *
     * Não é detalhe: `RESPONSE_FIRST_AUDIO` diz *first* no nome. E o ciclo
     * continua "corrente" depois que `runOnce` retorna — porque a fala só é
     * reproduzida quando a fila chega nela, o que acontece **depois**. Sem esta
     * regra, um `Sound.Speech` posterior (o "Sem rede." do rádio, por exemplo)
     * sobrescreveria o instante e a métrica passaria a medir outra coisa,
     * atribuída ao ciclo errado.
     */
    override fun mark(cycleId: String, stage: Telemetry.Stage, epochMillis: Long) =
        synchronized(trava) {
            val doCiclo = marcos.getOrPut(cycleId) { LinkedHashMap() }
            if (doCiclo.containsKey(stage)) return@synchronized
            doCiclo[stage] = epochMillis
            fecharTransicoes(cycleId, doCiclo)
        }

    /** Marca no ciclo corrente. Usado por quem reproduz, que não carrega o id. */
    fun marcarNoCicloCorrente(stage: Telemetry.Stage, epochMillis: Long) {
        val id = cicloCorrente ?: return
        mark(id, stage, epochMillis)
    }

    private fun fecharTransicoes(cycleId: String, doCiclo: Map<Telemetry.Stage, Long>) {
        val fim = doCiclo[Telemetry.Stage.VAD_WINDOW_CLOSED] ?: return
        doCiclo[Telemetry.Stage.EARCON_PLAYED]?.let {
            registrar(cycleId, Transicao.FIM_DA_FALA_ATE_EARCON, it - fim)
        }
        doCiclo[Telemetry.Stage.RESPONSE_FIRST_AUDIO]?.let {
            registrar(cycleId, Transicao.FIM_DA_FALA_ATE_RESPOSTA, it - fim)
        }
        doCiclo[Telemetry.Stage.STT_DONE]?.let {
            registrar(cycleId, Transicao.STT, it - fim)
        }
        val roteado = doCiclo[Telemetry.Stage.INTENT_ROUTED]
        val agiu = doCiclo[Telemetry.Stage.ACTION_DONE]
        if (roteado != null && agiu != null) registrar(cycleId, Transicao.ACAO, agiu - roteado)
    }

    private val jaRegistradas = HashSet<Pair<String, Transicao>>()

    private fun registrar(cycleId: String, t: Transicao, duracaoMs: Long) {
        // Cada `mark` reavalia o ciclo inteiro, então sem esta guarda a mesma
        // transição entraria uma vez por marco posterior e o percentil viraria
        // um histograma da ordem de chegada, não da latência. A chave é o ciclo
        // de ORIGEM do marco, não o corrente: os marcos de reprodução chegam
        // tarde, quando o corrente já pode ser outro.
        if (!jaRegistradas.add(cycleId to t)) return
        if (duracaoMs < 0) return
        val fila = duracoes.getOrPut(t) { ArrayDeque() }
        fila.addLast(duracaoMs)
        while (fila.size > capacidade) fila.removeFirst()
    }

    fun medicao(t: Transicao): Medicao? = synchronized(trava) {
        val fila = duracoes[t]?.toList()?.sorted() ?: return null
        if (fila.isEmpty()) return null
        Medicao(
            amostras = fila.size,
            p50 = fila[fila.size / 2],
            p95 = fila[minOf(fila.size - 1, (fila.size * 95) / 100)],
            pior = fila.last(),
        )
    }

    fun relatorio(): String = synchronized(trava) {
        buildString {
            appendLine("Telemetria do ciclo de voz (on-device, sem rede)")
            for (t in Transicao.entries) {
                val m = medicao(t)
                val meta = t.metaMs?.let { "  (meta ≤ ${it}ms)" } ?: ""
                appendLine("  ${t.rotulo}: ${m ?: "sem amostras"}$meta")
            }
            appendLine("  wake word: sem produtor (WakeWordDetector é interface sem implementação)")
        }
    }

    companion object {
        const val CAPACIDADE = 200
    }
}
