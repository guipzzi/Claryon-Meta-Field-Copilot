package com.claryon.agent

/** O que fazer com a coleta de posição em cada momento. */
data class PlanoDePosicao(
    val intervaloMs: Long,
    val deslocamentoMinimoM: Float,
    val altaPrecisao: Boolean,
    /** Assinar o canal de posições dos pares — só com o mapa à vista. */
    val assinarPares: Boolean,
    /**
     * De quanto em quanto tempo publicar **mesmo parado**.
     *
     * Não pode ser menor que [intervaloMs]: o batimento só acontece quando uma
     * correção chega, e correção só chega na cadência do provedor. O batimento
     * **efetivo** é o primeiro múltiplo de [intervaloMs] que alcança este valor —
     * é [batimentoEfetivoMs] quem calcula, e é ele que precisa caber embaixo de
     * [PoliticaDePosicao.OBSOLETO_S], não este número.
     */
    val batimentoMs: Long,
) {
    /**
     * O batimento que de fato acontece. Escrever `batimentoMs = 90_000` com
     * `intervaloMs = 60_000` não dá 90 s: dá 120 s, porque a correção seguinte
     * só chega aos 120. Foi essa aritmética que fez o número declarado e o
     * número real divergirem sem ninguém notar.
     */
    val batimentoEfetivoMs: Long
        get() = ((batimentoMs + intervaloMs - 1) / intervaloMs) * intervaloMs
}

/**
 * **Política de posição — a decisão que protege a bateria.**
 *
 * Duas regras carregam quase todo o ganho:
 *
 *  1. **Atualizar por deslocamento, não por tempo.** Agente parado quase não
 *     custa energia; o GPS só acorda quando ele anda. Numa guarnição em ponto
 *     fixo — que é boa parte do turno — a diferença é de ordens de grandeza.
 *  2. **Assinar o canal dos pares apenas enquanto o mapa está visível.** Numa
 *     guarnição de oito, difundir posição de todos para todos seria 8 × 8 de
 *     tráfego permanente **para uma tela que fica fechada 95% do turno**. A
 *     posição própria continua subindo — é ela que alimenta a consulta por voz e
 *     o fan-out do alerta.
 *
 * E uma que é de segurança, não de energia: **Standby reduz cadência, não
 * desliga**. Um agente em pausa reporta a cada 5 minutos. Sumir do mapa criaria a
 * expectativa errada — companheiro que desaparece parece em perigo.
 *
 * Pura e testável: nenhuma dependência de Android.
 */
object PoliticaDePosicao {

    fun planoPara(modo: ModoOperacao, mapaVisivel: Boolean): PlanoDePosicao = when (modo) {

        // Pausa: cadência baixa, mas **nunca zero**. Aqui, e só aqui, o batimento
        // efetivo (5 min) passa de OBSOLETO_S e o marcador aparece esmaecido a
        // maior parte do tempo. É deliberado e é a leitura correta: a posição de
        // um agente em pausa, colhida pela rede com 250 m de tolerância, **pode
        // mesmo não ser mais verdade**. Esmaecer diz isso; publicar de minuto em
        // minuto para manter o marcador cheio seria mentir com mais bateria.
        ModoOperacao.STANDBY -> PlanoDePosicao(
            intervaloMs = 5 * 60 * 1000,
            deslocamentoMinimoM = 250f,
            altaPrecisao = false,
            assinarPares = mapaVisivel,
            batimentoMs = 5 * 60 * 1000,
        )

        // 60 s parado, não 3 min. Com 3 min o batimento caía DEPOIS dos 120 s de
        // OBSOLETO_S, e todo agente parado em serviço ficava esmaecido um terço
        // do tempo — um indicador que acende no estado normal ensina a ignorá-lo,
        // e aí ele não avisa mais nada quando importa.
        ModoOperacao.ATIVO -> PlanoDePosicao(
            intervaloMs = 60 * 1000,
            deslocamentoMinimoM = 50f,
            altaPrecisao = true,
            assinarPares = mapaVisivel,
            batimentoMs = 60 * 1000,
        )

        // Ocorrência: a posição dos pares vira informação tática de segundo a
        // segundo, e o custo de bateria é aceitável numa janela curta. É também o
        // modo em que o agente MAIS fica parado — chegou no local e ficou — e
        // portanto aquele em que o batimento inalcançável doía mais.
        ModoOperacao.OCORRENCIA -> PlanoDePosicao(
            intervaloMs = 15 * 1000,
            deslocamentoMinimoM = 10f,
            altaPrecisao = true,
            assinarPares = mapaVisivel,
            batimentoMs = 60 * 1000,
        )
    }

    /**
     * `true` se o marcador deve ser mostrado como **esmaecido** — posição que
     * pode não ser mais verdade.
     *
     * Não é polimento: mostrar posição velha como atual é requisito de segurança
     * invertido. O agente decidiria a abordagem contando com um apoio que já saiu
     * dali.
     */
    fun marcadorObsoleto(idadeS: Int): Boolean = idadeS > OBSOLETO_S

    /** Acima disso, nem esmaecido: o marcador passa a dizer há quanto tempo é. */
    fun marcadorMuitoVelho(idadeS: Int): Boolean = idadeS > MUITO_VELHO_S

    /** 2 min — mesmo limiar de [FalaDePosicao.IDADE_MAXIMA_S]. Uma regra, duas saídas. */
    const val OBSOLETO_S = 120

    /** 10 min. */
    const val MUITO_VELHO_S = 600
}
