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
 * [Telemetry.Stage.WAKE_DETECTED] **passou a ter produtor em 20/08**:
 * `CopilotService.aoDetectar`. Até lá ele era o exemplo desta regra — estágio sem
 * quem o marque mostra "sem amostras", nunca zero, porque zero é uma medição e a
 * ausência dela não é.
 *
 * Esta frase já esteve escrita nos dois tempos ao mesmo tempo ("não tem produtor …
 * hoje ela produz"), o que é pior que estar só desatualizada: contradição no mesmo
 * parágrafo faz o leitor escolher a metade que lhe convém.
 *
 * ## Ciclo corrente
 *
 * Os marcos de reprodução acontecem noutro objeto e noutra corrotina, então
 * precisam saber a qual ciclo pertencem. Como só existe **um** ciclo de voz por
 * vez (o botão do copiloto é bloqueado por `copilotoOcupado` enquanto roda),
 * um "ciclo corrente" resolve sem precisar propagar identificador por cinco
 * camadas. Se um dia houver ciclos concorrentes, este é o ponto que quebra — e
 * quebra alto, com marcos atribuídos ao ciclo errado, não em silêncio.
 *
 * **Não existe `fecharCiclo`, e a ausência é deliberada.** Chegou a existir e
 * era código morto: fechar o ciclo quando `runOnce` retorna descartaria os dois
 * marcos que mais importam, porque a fala só é REPRODUZIDA depois — quando a
 * fila chega nela. O ciclo fica corrente até o próximo [abrirCiclo] tomar o
 * lugar; a guarda "o primeiro marco vence" em [mark] é o que impede um som
 * posterior de outra fonte de corromper a medição.
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
        /**
         * **A meta da palavra de ativação**, do roadmap da Fase 2: *"fim de 'Hey
         * Claryon' → início do earcon `OUVI_VOCE`, p95 ≤ 500 ms"*.
         *
         * Ficou sem produtor até 20/08, e o relatório dizia isso com estas
         * palavras: *"wake word: sem produtor (WakeWordDetector é interface sem
         * implementação)"*. A frase era verdadeira e virou falsa no dia em que
         * `EscutaDeAtivacao` passou a existir — mas continuou impressa, que é como
         * um relatório honesto vira um relatório errado sem ninguém mexer nele.
         *
         * O zero é `WAKE_DETECTED`, marcado quando a janela do detector fecha com
         * escore acima do limiar. Isso é **depois** do fim da palavra falada, por
         * até um passo do detector (80 ms) — então o número medido aqui é
         * ligeiramente OTIMISTA em relação à régua do agente, que começa a contar
         * quando ele para de falar. Registrado para não ser lido como se fosse a
         * régua dele.
         */
        ATIVACAO_ATE_EARCON("palavra de ativação → earcon", 500),

        /**
         * **Fim do comando → "ouvi, estou trabalhando".**
         *
         * Esta métrica quase morreu sem ninguém notar. No caminho da voz existem
         * DOIS earcons `OUVI_VOCE` — o de "pode falar", na detecção, e este — e
         * `EARCON_PLAYED` era primeiro-marco-vence: o instante registrado era o do
         * primeiro, anterior ao fim da fala, e a conta dava negativo.
         *
         * Enquanto havia botão isso era um buraco parcial. **Sem botão, seria
         * total**: zero amostras em produção, para sempre, sem erro nenhum. O
         * desempate mora em [mark].
         */
        FIM_DA_FALA_ATE_EARCON("fim do comando → earcon", 500),
        FIM_DA_FALA_ATE_RESPOSTA("fim da fala → resposta falada", 2_000),
        /**
         * **O custo real do whisper**, de `STT_STARTED` a `STT_DONE`.
         *
         * Antes esta transição era `STT_DONE − VAD_WINDOW_CLOSED` e chamava-se
         * "transcrição (whisper)". Mas `VAD_WINDOW_CLOSED` é ancorado no fim da
         * FALA — 600 ms antes do fechamento da janela — e entre os dois ainda
         * cabe o `emitir` do earcon. O rótulo dizia whisper e o número media
         * hangover + earcon + whisper: **1021 ms para um whisper de ~421**.
         *
         * O erro era invisível porque o número era plausível e internamente
         * coerente. Foi preciso somar as parcelas e ver que não fechavam.
         */
        STT("transcrição (whisper, custo real)", null),

        /**
         * Fim da fala → transcrição pronta. É o que a transição anterior media
         * sem dizer, e é uma métrica legítima — só não é o custo do whisper.
         * Inclui o hangover do VAD, que sozinho é 600 ms.
         */
        FIM_DA_FALA_ATE_TRANSCRICAO("fim da fala → transcrição pronta", null),
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
    /**
     * Instante do earcon da ATIVAÇÃO, separado do earcon do comando.
     *
     * Os dois são `Earcon.OUVI_VOCE` e chegam pelo mesmo estágio, então
     * primeiro-marco-vence fazia o segundo desaparecer. Com o produto **sem botão**
     * — a palavra de ativação é a única entrada —, isso deixaria
     * `FIM_DA_FALA_ATE_EARCON`, que é meta de aceite, sem uma única amostra em
     * produção. Não "com poucas": zero, para sempre, sem erro.
     *
     * Desambiguar aqui e não no `SaidaUnica`: quem sabe distinguir é quem conhece o
     * ciclo. O reprodutor só sabe que tocou um som.
     */
    private val earconDaAtivacao = HashMap<String, Long>()

    override fun mark(cycleId: String, stage: Telemetry.Stage, epochMillis: Long) =
        synchronized(trava) {
            val doCiclo = marcos.getOrPut(cycleId) { LinkedHashMap() }

            // O primeiro earcon de um ciclo que JÁ acordou e ainda NÃO fechou o VAD
            // é o de "estou ouvindo". O seguinte é o de "ouvi o comando".
            if (stage == Telemetry.Stage.EARCON_PLAYED &&
                doCiclo.containsKey(Telemetry.Stage.WAKE_DETECTED) &&
                !doCiclo.containsKey(Telemetry.Stage.VAD_WINDOW_CLOSED) &&
                !earconDaAtivacao.containsKey(cycleId)
            ) {
                earconDaAtivacao[cycleId] = epochMillis
                while (earconDaAtivacao.size > capacidade) {
                    earconDaAtivacao.remove(earconDaAtivacao.keys.first())
                }
                fecharTransicoes(cycleId, doCiclo)
                return@synchronized
            }

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
        // **Antes do `return`.** A ativação tem o próprio zero (`WAKE_DETECTED`) e
        // não depende do VAD: pô-la depois da guarda faria a métrica só existir
        // quando o ciclo chegasse ao fim da fala — e ela morreria justamente nos
        // ciclos que falham, que são os que mais interessam medir.
        doCiclo[Telemetry.Stage.WAKE_DETECTED]?.let { acordou ->
            // O earcon da ativação, e não o do comando: no caminho da voz os dois
            // existem, e usar `EARCON_PLAYED` aqui mediria o segundo.
            (earconDaAtivacao[cycleId] ?: doCiclo[Telemetry.Stage.EARCON_PLAYED])?.let {
                registrar(cycleId, Transicao.ATIVACAO_ATE_EARCON, it - acordou)
            }
        }

        val fim = doCiclo[Telemetry.Stage.VAD_WINDOW_CLOSED] ?: return
        doCiclo[Telemetry.Stage.EARCON_PLAYED]?.let {
            registrar(cycleId, Transicao.FIM_DA_FALA_ATE_EARCON, it - fim)
        }
        doCiclo[Telemetry.Stage.RESPONSE_FIRST_AUDIO]?.let {
            registrar(cycleId, Transicao.FIM_DA_FALA_ATE_RESPOSTA, it - fim)
        }
        doCiclo[Telemetry.Stage.STT_DONE]?.let { pronta ->
            registrar(cycleId, Transicao.FIM_DA_FALA_ATE_TRANSCRICAO, pronta - fim)
            // O custo REAL do whisper só existe se o produtor marcou o início.
            // Ausente, a transição fica sem amostra — que é honesto — em vez de
            // cair para `pronta - fim` e mentir de novo.
            doCiclo[Telemetry.Stage.STT_STARTED]?.let { comecou ->
                registrar(cycleId, Transicao.STT, pronta - comecou)
            }
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
        // **A guarda do negativo vem ANTES de consumir a vaga.** Ao contrário, uma
        // duração impossível gastava o registro do par `(ciclo, transição)` e
        // nenhuma medição válida daquele ciclo entrava depois. É defensivo — hoje
        // primeiro-marco-vence torna o caso raro —, mas gastar a vaga com lixo é
        // errado em qualquer ordem de chegada.
        if (duracaoMs < 0) return
        if (!jaRegistradas.add(cycleId to t)) return
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
            // A linha "wake word: sem produtor" morava aqui e era verdadeira até
            // 20/08. Não foi substituída por outra frase fixa: quem diz se há
            // amostra é o laço acima, que imprime "sem amostras" quando não há.
            // Frase fixa sobre estado é como aquela envelheceu para mentira.
        }
    }

    companion object {
        const val CAPACIDADE = 200
    }
}
