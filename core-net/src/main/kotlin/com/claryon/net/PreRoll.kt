package com.claryon.net

import kotlin.math.sqrt

/**
 * **Pré-roll: buffer circular que garante que nenhuma sílaba seja cortada.**
 *
 * Todo usuário de rádio já perdeu a primeira sílaba — o dedo aperta depois de a
 * boca começar, e sob estresse isso piora. A solução aqui não é adivinhar quanto
 * tempo reservar: é **medir onde a fala começou**.
 *
 * Ao pressionar o PTT, um VAD retroativo varre o buffer de trás para frente
 * procurando o início real da fala e transmite a partir dali:
 *
 * | Situação | Pré-roll fixo de 300 ms | Início detectado (aqui) |
 * |---|---|---|
 * | Fala começou 450 ms antes | corta 150 ms | íntegra |
 * | Fala começou junto com o toque | envia 300 ms de rua | envia só a fala |
 * | Argumento de privacidade | "guardamos 300 ms sempre" | "transmitimos a partir do início da fala" |
 *
 * ## O limite que impede isto de virar escuta ambiente
 *
 * O buffer é circular, tem [duracaoMs] de capacidade, **vive apenas em RAM**,
 * **nunca é escrito em disco**, e é sobrescrito continuamente. Se o PTT não for
 * pressionado, o conteúdo se perde por definição — não existe caminho neste
 * módulo que o persista ou transmita. O que sai na transmissão começa no início
 * detectado da fala, nunca antes.
 *
 * A 8 kHz, 16 bits, mono, 600 ms são **9,6 KB de RAM**. O custo é irrelevante e a
 * margem cobre praticamente qualquer atraso humano de acionamento.
 *
 * Não é thread-safe por escolha: um único produtor (o laço de captura) escreve, e
 * a leitura acontece no mesmo laço ao processar o toque. Sincronizar aqui
 * acrescentaria contenção no caminho mais quente do produto.
 */
class PreRollBuffer(
    private val sampleRateHz: Int,
    private val duracaoMs: Int = DURACAO_PADRAO_MS,
    private val amostrasPorQuadro: Int = sampleRateHz / 50, // 20 ms
    /** RMS acima do qual o quadro é considerado fala. */
    private val limiarRms: Double = LIMIAR_RMS_PADRAO,
) {

    private val capacidade: Int = maxOf(1, (sampleRateHz * duracaoMs / 1000) / amostrasPorQuadro)

    private val quadros = arrayOfNulls<ShortArray>(capacidade)
    private var proximo = 0
    private var preenchidos = 0

    /** Quantos quadros cabem — expõe a capacidade real para diagnóstico e teste. */
    val capacidadeEmQuadros: Int get() = capacidade

    /** Quantos quadros há no buffer agora. */
    val tamanho: Int get() = preenchidos

    /**
     * Guarda mais um quadro, descartando o mais antigo quando cheio.
     *
     * Copia o array: o chamador reaproveita o buffer de leitura do `AudioRecord`,
     * e guardar a referência faria todo o pré-roll apontar para o mesmo conteúdo —
     * defeito que só apareceria como "o pré-roll repete o último quadro".
     */
    fun escrever(quadro: ShortArray) {
        quadros[proximo] = quadro.copyOf()
        proximo = (proximo + 1) % capacidade
        if (preenchidos < capacidade) preenchidos++
    }

    /**
     * Devolve o áudio desde o **início detectado da fala** até o quadro mais
     * recente. Vazio se não houver fala no buffer — nesse caso a transmissão
     * começa no instante do toque, sem ruído de rua na frente.
     *
     * @param silencioParaCortarMs quanto silêncio contíguo, andando para trás,
     *   encerra a busca. Pausas curtas dentro da fala ("apoio… na Rui Barbosa")
     *   não podem partir a frase ao meio.
     */
    fun desdeOInicioDaFala(silencioParaCortarMs: Int = SILENCIO_CORTE_MS): ShortArray {
        if (preenchidos == 0) return ShortArray(0)

        val ordenados = emOrdem()
        val silencioParaCortar = maxOf(1, silencioParaCortarMs / msPorQuadro())

        // Anda do mais recente para o mais antigo. O início é o quadro logo após
        // o último bloco de silêncio contíguo longo o bastante.
        var inicio = 0
        var silencioSeguido = 0
        var viuFala = false

        for (i in ordenados.indices.reversed()) {
            if (ehFala(ordenados[i])) {
                viuFala = true
                silencioSeguido = 0
            } else {
                silencioSeguido++
                if (viuFala && silencioSeguido >= silencioParaCortar) {
                    inicio = i + silencioSeguido
                    break
                }
            }
        }
        if (!viuFala) return ShortArray(0)

        val selecionados = ordenados.drop(inicio.coerceIn(0, ordenados.size - 1))
        val total = selecionados.sumOf { it.size }
        val saida = ShortArray(total)
        var offset = 0
        for (q in selecionados) {
            q.copyInto(saida, offset)
            offset += q.size
        }
        return saida
    }

    /**
     * Zera o buffer. Chamado ao soltar o PTT e ao sair do modo Ativo — não porque
     * a memória seja escassa, mas porque conteúdo de áudio não deve sobreviver
     * ao momento em que poderia ser usado.
     */
    fun limpar() {
        for (i in quadros.indices) {
            quadros[i]?.fill(0)
            quadros[i] = null
        }
        proximo = 0
        preenchidos = 0
    }

    private fun emOrdem(): List<ShortArray> {
        val saida = ArrayList<ShortArray>(preenchidos)
        val primeiro = if (preenchidos < capacidade) 0 else proximo
        for (k in 0 until preenchidos) {
            quadros[(primeiro + k) % capacidade]?.let { saida.add(it) }
        }
        return saida
    }

    private fun msPorQuadro(): Int = maxOf(1, amostrasPorQuadro * 1000 / sampleRateHz)

    private fun ehFala(quadro: ShortArray): Boolean = rms(quadro) > limiarRms

    private fun rms(quadro: ShortArray): Double {
        if (quadro.isEmpty()) return 0.0
        var soma = 0.0
        for (s in quadro) soma += s.toDouble() * s.toDouble()
        return sqrt(soma / quadro.size)
    }

    companion object {
        /**
         * 600 ms, e não 300: o recuo fixo de 300 ms cortaria uma fala iniciada
         * 450 ms antes do toque, que é comum sob estresse. Como o VAD retroativo
         * só transmite a partir do início detectado, aumentar a janela **não**
         * aumenta o que é transmitido — só a margem para encontrá-lo.
         */
        const val DURACAO_PADRAO_MS = 600
        const val SILENCIO_CORTE_MS = 120
        const val LIMIAR_RMS_PADRAO = 500.0
    }
}
