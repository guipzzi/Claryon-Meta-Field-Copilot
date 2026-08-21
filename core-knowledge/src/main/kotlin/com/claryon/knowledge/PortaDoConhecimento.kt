package com.claryon.knowledge

/**
 * **Decide se o melhor candidato é bom o bastante para ser dito em voz alta.**
 *
 * A porta tem uma regra só, e ela é a regra de produto inteira da Etapa A:
 * abaixo do [limiar] a resposta é **recusa** — `null` —, nunca "o mais próximo".
 *
 * **Por que "o mais próximo" é a resposta errada, e não uma aproximação boa.**
 * Um índice sempre tem um vizinho mais próximo: perguntando sobre uma pistola
 * emperrada, algum artigo do Código de Trânsito vai ser o menos distante, e a
 * distância dele não aparece na fala. O agente ouve um artigo de lei, com número
 * e tudo, dito com a mesma naturalidade de um acerto, e não tem display para
 * conferir. Trecho errado dito com confiança para quem está em abordagem é pior
 * que "não sei": "não sei" devolve a decisão para o agente, que sabe o que fazer
 * sem o copiloto; o trecho errado a toma no lugar dele.
 *
 * A porta é pura e sem estado — nada de Android, nada de índice, nada de
 * modelo. Ela recebe o que o índice achou e decide. É a peça que dá para provar
 * hoje, antes de existir embedder.
 *
 * @param limiar similaridade mínima para falar. Comparação é `>=`: no limiar
 *   exato, aceita. **O valor padrão não é medido** — ver [LIMIAR_PADRAO].
 */
class PortaDoConhecimento(
    private val limiar: Double = LIMIAR_PADRAO,
) {
    init {
        require(limiar.isFinite() && limiar in 0.0..1.0) {
            "Limiar fora de 0..1: $limiar"
        }
    }

    /**
     * O trecho a ser lido verbatim, ou `null` quando nenhum candidato alcança o
     * [limiar] — inclusive quando a lista vem vazia.
     *
     * `null` significa exatamente uma coisa, e ela é dizível: *"não encontrei
     * procedimento para isso"*. Não significa erro, não significa tente de novo,
     * e não autoriza o chamador a pegar o segundo colocado.
     */
    fun escolher(candidatos: List<Candidato>): Trecho? =
        candidatos
            .maxByOrNull { it.similaridade }
            ?.takeIf { it.similaridade >= limiar }
            ?.trecho

    companion object {
        /**
         * **Provisório e não medido.** Calibrar limiar exige o corpus e o
         * embedder reais (item 3 da Etapa A), e nenhum dos dois existe quando
         * esta linha é escrita — um número apresentado como "ajustado" seria
         * invenção.
         *
         * Enquanto não medido, o erro é deliberadamente para o lado da recusa:
         * recusar demais custa um "não sei"; aceitar demais custa uma norma
         * errada lida como se fosse a certa. Quem calibrar troca este valor
         * **com a medição junto**, e não antes dela.
         */
        const val LIMIAR_PADRAO: Double = 0.60
    }
}
