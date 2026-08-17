package com.claryon.net

/** Uma medição de latência, em milissegundos. */
data class Medicao(val amostras: Int, val p50: Long, val p95: Long, val pior: Long) {
    override fun toString(): String = "n=$amostras p50=${p50}ms p95=${p95}ms pior=${pior}ms"
}

/**
 * **Telemetria do rádio tático — instrumentada junto com a funcionalidade, não depois.**
 *
 * O projeto declara seis metas de latência e, até aqui, nenhuma medição. Diante de
 * banca técnica, meta sem número é aspiração; e a regra que já custou caro em
 * outros projetos vale aqui: *métrica adicionada no fim nunca é adicionada*.
 *
 * ## O que esta classe NÃO mede
 *
 * O **boca a ouvido** completo. Ela mede da captura ao envio, e da recepção à
 * reprodução — mas a saída Bluetooth (40–150 ms) e a rede ficam fora do que
 * qualquer código dentro do aplicativo enxerga. O número que vale para o pitch
 * vem do método do estalo: dois aparelhos, um som seco, gravação por um terceiro.
 * Confundir a medição interna com a real seria publicar um número otimista.
 *
 * Guarda apenas os últimos [capacidade] valores por métrica: um turno inteiro de
 * medições não cabe na memória, e percentil de janela recente é mais útil que
 * média de tudo — a rede de agora importa mais que a de três horas atrás.
 *
 * Thread-safe por sincronização simples: o volume é baixo (dezenas por segundo,
 * não milhares) e a contenção é irrelevante perto de um `AudioRecord`.
 */
class TelemetriaDoRadio(private val capacidade: Int = CAPACIDADE) {

    enum class Metrica {
        /** Toque no PTT → primeiro quadro entregue à rede. Meta: ≤ 120 ms. */
        TOQUE_ATE_PRIMEIRO_QUADRO,

        /** Pedido de canal → resposta do servidor. Meta: ≤ 150 ms. */
        CONCESSAO_DE_CANAL,

        /** Um quadro: entrada no codec → saída do pacote. */
        CODIFICACAO,

        /** Chegada do quadro → PCM entregue à reprodução. */
        RECEPCAO_ATE_AUDIO,
    }

    private val amostras = HashMap<Metrica, ArrayDeque<Long>>()
    private val contadores = HashMap<String, Long>()
    private val trava = Any()

    fun registrar(metrica: Metrica, duracaoMs: Long) = synchronized(trava) {
        val fila = amostras.getOrPut(metrica) { ArrayDeque() }
        fila.addLast(duracaoMs)
        while (fila.size > capacidade) fila.removeFirst()
    }

    /** Conta ocorrências — quadros enviados, perdidos, canais negados. */
    fun contar(evento: String, quantos: Long = 1) = synchronized(trava) {
        contadores[evento] = (contadores[evento] ?: 0) + quantos
    }

    fun medicao(metrica: Metrica): Medicao? = synchronized(trava) {
        val fila = amostras[metrica]?.toList()?.sorted() ?: return null
        if (fila.isEmpty()) return null
        Medicao(
            amostras = fila.size,
            p50 = fila[fila.size / 2],
            p95 = fila[minOf(fila.size - 1, (fila.size * 95) / 100)],
            pior = fila.last(),
        )
    }

    fun contador(evento: String): Long = synchronized(trava) { contadores[evento] ?: 0 }

    /**
     * Relatório legível. É o que vai para o log e para o painel — e o que
     * alimenta o documento da Etapa 5, com a ressalva de que estes números são
     * **internos**, não boca a ouvido.
     */
    fun relatorio(): String = synchronized(trava) {
        buildString {
            appendLine("Telemetria do rádio (medições internas — não incluem Bluetooth nem rede)")
            for (m in Metrica.entries) {
                val med = medicao(m)
                appendLine("  ${m.name.lowercase().replace('_', ' ')}: ${med ?: "sem amostras"}")
            }
            if (contadores.isNotEmpty()) {
                appendLine("  contadores:")
                contadores.toSortedMap().forEach { (k, v) -> appendLine("    $k = $v") }
            }
            // O aceite (d) pede a contagem de MENSAGENS, não de quadros: com o
            // agrupamento de 3, uma mensagem carrega três quadros de 20 ms.
            val msgs = contador(SessaoPtt.MENSAGENS_ENVIADAS)
            val quadrosEnv = contador(QUADROS_ENVIADOS)
            if (msgs > 0) {
                appendLine(
                    "  agrupamento: $quadrosEnv quadros em $msgs mensagens " +
                        "(${"%.1f".format(quadrosEnv.toDouble() / msgs)} quadros/mensagem)",
                )
            }
            val perdidos = contador(QUADROS_PERDIDOS)
            val recebidos = contador(QUADROS_RECEBIDOS)
            if (recebidos + perdidos > 0) {
                val pct = perdidos * 100.0 / (recebidos + perdidos)
                appendLine("  perda medida: ${"%.1f".format(pct)}%  (meta ≤ 5% em 4G urbano)")
            }
        }
    }

    fun zerar() = synchronized(trava) {
        amostras.clear()
        contadores.clear()
    }

    companion object {
        const val CAPACIDADE = 200

        const val QUADROS_ENVIADOS = "quadros_enviados"
        const val QUADROS_NAO_ENTREGUES = "quadros_nao_entregues"
        const val QUADROS_RECEBIDOS = "quadros_recebidos"
        const val QUADROS_PERDIDOS = "quadros_perdidos"
        const val CANAL_NEGADO = "canal_negado"
        const val CANAL_TOMADO = "canal_tomado"
        const val FALAS_DIFERIDAS = "falas_diferidas"
        const val TOQUES_IGNORADOS = "toques_ignorados"

        /** Metas declaradas, para o relatório poder se comparar a elas. */
        const val META_TOQUE_ATE_QUADRO_MS = 120L
        const val META_CONCESSAO_MS = 150L
    }
}
