package com.claryon.net

/**
 * **Junta quadros de 20 ms numa mensagem só, antes de sair para a rede.**
 *
 * ## O problema medido
 *
 * Um quadro de Opus a 12 kbps tem ~30 B. O envelope do Supabase Realtime
 * (JSON do Phoenix + base64 + `transmissaoId` de 36 caracteres) custa ~274 B
 * por mensagem. São **~11% de aproveitamento** e ~50 mensagens/s por locutor.
 *
 * Agrupar três quadros divide o envelope por três: uma mensagem de 60 ms carrega
 * ~90 B de voz no lugar de 30, e a taxa cai para ~17 mensagens/s.
 *
 * ## Por que este arquivo demorou a existir
 *
 * O comentário em [QuadroAudio] afirmava que a classe existia — e ela nunca foi
 * escrita. Quando o defeito foi encontrado, a decisão foi **corrigir a
 * documentação e não construir**, porque agrupar parecia exigir reescrever o
 * receptor: [BufferDeJitter] raciocina em quadros, detecta perda por lacuna de
 * `sequencia` e dispara PLC — e se `sequencia` passasse a numerar *mensagens*,
 * os três mecanismos mudariam de significado ao mesmo tempo.
 *
 * **A saída foi não mexer em `sequencia`.** Cada quadro mantém a própria, e a
 * mensagem só os carrega juntos; `ProtocoloRealtime.interpretar` explode o grupo
 * de volta em N eventos. O receptor não sabe que eles vieram na mesma mensagem, e
 * não precisa saber. Nenhuma linha de [BufferDeJitter] mudou.
 *
 * ## O custo, declarado
 *
 * Latência de empacotamento: os dois primeiros quadros esperam o terceiro, então
 * o pior caso soma **+40 ms** ao caminho boca-a-ouvido. É gasto real, e é o preço
 * de reduzir a taxa a um terço. Com o teto de 20 ms por quadro, 3 é o ponto em
 * que a economia já é grande (67%) e o atraso ainda cabe.
 *
 * O [descarregar] existe para o fim da fala: sem ele, um resto de um ou dois
 * quadros ficaria preso no agrupador e a última sílaba nunca sairia — trocar
 * envelope por sílaba perdida seria um péssimo negócio.
 *
 * Puro e sem relógio: quem decide quando descarregar é o chamador.
 */
class AgrupadorDeQuadros(private val porMensagem: Int = POR_MENSAGEM) {

    init {
        require(porMensagem >= 1) { "agrupamento precisa de ao menos um quadro" }
    }

    private val pendentes = ArrayList<QuadroAudio>(porMensagem)

    /**
     * Acrescenta um quadro e devolve a mensagem **se ela fechou**, ou `null`.
     *
     * O quadro marcado como [QuadroAudio.ultimo] fecha o grupo na hora, mesmo
     * incompleto: ele é o fim da fala, e segurá-lo esperando companhia deixaria o
     * receptor aguardando indefinidamente por uma transmissão que já terminou.
     */
    fun oferecer(quadro: QuadroAudio): List<QuadroAudio>? {
        pendentes.add(quadro)
        return if (quadro.ultimo || pendentes.size >= porMensagem) descarregar() else null
    }

    /** Entrega o que estiver pendente e esvazia. `null` se não havia nada. */
    fun descarregar(): List<QuadroAudio>? {
        if (pendentes.isEmpty()) return null
        val grupo = ArrayList(pendentes)
        pendentes.clear()
        return grupo
    }

    val pendente: Int get() = pendentes.size

    companion object {
        /** 3 × 20 ms = 60 ms por mensagem. Ver o custo declarado no KDoc. */
        const val POR_MENSAGEM = 3
    }
}
