package com.claryon.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * **A implementação da Etapa A: pergunta entra, artigo de lei sai — ou nada
 * sai.** Recuperação extrativa por índice lexical, 100% no aparelho, sem rede,
 * sem modelo e sem um único byte de texto novo.
 *
 * Honra o contrato de [BaseDeConhecimento] item por item:
 *
 *  - **Devolve texto que já existia.** O [Trecho] sai do corpus como veio do
 *    Planalto. Não há passo que redija nada — não por disciplina, por ausência:
 *    não existe função aqui que produza `String` que não tenha sido lida de
 *    `trechos.jsonl`.
 *  - **`null` é recusa.** Quem decide é [PortaDoConhecimento], com o limiar
 *    medido em [LIMIAR_MEDIDO]. Abaixo dele o segundo colocado **não** é
 *    oferecido.
 *  - **Não lança.** Recurso ausente, JSONL corrompido, linha inválida, pergunta
 *    vazia: tudo vira `null`. `BaseDeConhecimentoLexicalTest` prova cada um.
 *  - **Não fala e não age.** Devolve o [Trecho]; quem lê é a camada de voz.
 *
 * ## O que a medição diz, para ninguém prometer mais do que existe
 *
 * Sobre 88 perguntas de policial em campo anotadas à mão contra o corpus, e 12
 * perguntas fora do domínio, no limiar [LIMIAR_MEDIDO]:
 *
 * | | |
 * |---|---|
 * | recall@1 do índice, antes da porta | **46/88 (52,3%)** · recall@3 60/88 |
 * | fala e acerta | **18** |
 * | fala e erra | **5** |
 * | cala tendo o certo em 1º — recusa **custosa** | 28 |
 * | cala e teria errado — recusa **correta** | 37 |
 * | fala sobre pergunta fora do corpus | **0 de 12** |
 *
 * Ou seja: **responde 1 pergunta em 5, e quando responde acerta 78%.** Isso não
 * é um copiloto de conhecimento pronto; é o piso barato contra o qual o próximo
 * mecanismo tem de provar que vale a ponte JNI que ele custa. Está escrito aqui
 * porque um KDoc que dissesse só "recupera o artigo" seria a mentira que este
 * projeto persegue. Os números e como reproduzi-los: `RecuperacaoMedidaTest`.
 *
 * @param limiar confiança mínima para falar. O padrão é o valor **medido**;
 *   quem mudar troca com a medição junto.
 * @param corpus de onde ler o JSONL. `null` — o caso normal — usa o recurso
 *   embarcado no próprio módulo. O parâmetro existe para o corpus da corporação
 *   entrar sem recompilar, e para o teste conseguir corromper o arquivo de
 *   propósito.
 */
class BaseDeConhecimentoLexical(
    private val limiar: Double = LIMIAR_MEDIDO,
    private val corpus: (() -> InputStream)? = null,
) : BaseDeConhecimento {

    private val porta = PortaDoConhecimento(limiar)

    /**
     * Indexar os 1744 trechos custa **112 ms** e alguns MB de RAM; cada busca
     * depois custa **913 µs** (medido em `oCustoDeTempoEMedido`). `lazy` para
     * não pagar a montagem no boot de quem talvez nunca pergunte nada — e para
     * pagá-la **uma vez só**, com a sincronização que `lazy` já dá.
     */
    private val indice: IndiceLexical by lazy {
        IndiceLexical(
            try {
                corpus?.let { abrir -> abrir().use(CorpusDeNormas::ler) } ?: CorpusDeNormas.embarcado()
            } catch (_: Exception) {
                // Fonte que não abre é corpus ausente, e corpus ausente é recusa
                // em toda pergunta — nunca uma exceção subindo para um aparelho
                // sem display, onde ela é indistinguível de o app ter morrido.
                emptyList()
            },
        )
    }

    /** Quantos trechos o índice tem de pé. `0` significa que o corpus não chegou. */
    val trechosIndexados: Int get() = try { indice.tamanho } catch (_: Exception) { 0 }

    override suspend fun buscar(pergunta: String): Trecho? =
        try {
            withContext(Dispatchers.Default) {
                porta.escolher(indice.buscar(pergunta))
            }
        } catch (_: Exception) {
            null
        }

    companion object {
        /**
         * **0,30 — medido, não escolhido, e a conta dos dois lados está aqui.**
         *
         * A curva foi levantada sobre 88 perguntas de domínio e 12 fora dele
         * (`aCurvaDoLimiarEOPontoEscolhido`):
         *
         * | limiar | fala certo | fala errado | fala fora do corpus | precisão | cobertura |
         * |---|---|---|---|---|---|
         * | 0,15 | 30 | 19 | 4 | 56,6% | 34,1% |
         * | 0,20 | 29 | 11 | 1 | 70,7% | 33,0% |
         * | 0,25 | 23 | 6 | 0 | 79,3% | 26,1% |
         * | **0,30** | **18** | **5** | **0** | **78,3%** | **20,5%** |
         * | 0,35 | 11 | 2 | 0 | 84,6% | 12,5% |
         * | 0,50 | 2 | 0 | 0 | 100% | 2,3% |
         *
         * **Por que não menos.** Em 0,20 uma pergunta fora do corpus já é
         * respondida com artigo de lei, e o critério de aceite do `ROADMAP.md` é
         * literal: *"uma pergunta fora do corpus produz 'não encontrei
         * procedimento para isso' e não uma invenção"*.
         *
         * **Por que não 0,25, que parece melhor em tudo.** Ele responde 5
         * perguntas a mais errando só 1 a mais — e mesmo assim foi recusado. A
         * maior confiança observada numa pergunta fora do corpus é **0,238**
         * (*"qual a capital da austrália"*), e as doze formam uma **cauda
         * contínua** — 0,238 · 0,198 · 0,177 · 0,163 · 0,130 —, sem degrau que
         * separe "fora do corpus" de "dentro". Um limiar em 0,25 fica a 0,012
         * do maior valor medido sobre **doze** perguntas: isso não é margem, é
         * coincidência com nome de margem. Em 0,30 a folga é 0,062.
         *
         * **Por que não mais.** Em 0,35 a precisão sobe 6 pontos e a cobertura
         * cai 8; em 0,50 chega a 100% respondendo 2 perguntas em 88. Limiar que
         * quase nunca fala é indistinguível, para o agente, de não existir.
         *
         * **O que este número não prova.** `0 de 12` tem teto de 25% pela regra
         * dos três: doze perguntas fora do domínio não medem uma taxa de falso
         * aceite, medem que ela não é grosseira.
         */
        const val LIMIAR_MEDIDO: Double = 0.30
    }
}
