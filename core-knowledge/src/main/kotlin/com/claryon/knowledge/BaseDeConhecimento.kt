package com.claryon.knowledge

/**
 * **O contrato da Etapa A: pergunta entra, trecho de norma sai — ou nada sai.**
 *
 * Não há implementação neste módulo, e isso é intencional. O embedder e o índice
 * vetorial são o item 3 da Etapa A e dependem do corpus; deixar aqui uma
 * implementação de mentira faria o resto do sistema parecer capaz de responder
 * quando ninguém indexou nada — o modo de falha que este projeto já cometeu seis
 * vezes. Enquanto não houver implementação, **não há capacidade**, e é assim que
 * deve parecer de fora.
 *
 * O que uma implementação precisa honrar:
 *
 *  - **Devolve texto que já existia.** O [Trecho] sai do corpus como está. Não
 *    há passo de redação em nenhum lugar deste caminho: a Etapa B (LLM
 *    reescrevendo) é outra camada, atrás de flag, e por cima desta — nunca dentro.
 *  - **`null` é recusa, e é resposta legítima.** Abaixo do limiar não se devolve
 *    o vizinho mais próximo. Ver [PortaDoConhecimento].
 *  - **Não lança.** Índice ausente, corpus corrompido, modelo que não carregou:
 *    tudo isso vira `null`, porque num sistema sem display uma exceção que sobe
 *    é indistinguível de o aplicativo ter morrido.
 *  - **Não fala.** Recuperar e falar são coisas separadas: quem transforma o
 *    [Trecho] em áudio é a camada de voz, não esta.
 *  - **Não age.** Este módulo devolve *texto*; ele não conhece intenção nem
 *    executor, e a fronteira de `core-knowledge` (sem `core-agent` no
 *    classpath) é o que garante que continue assim. Ver
 *    `FronteiraDoConhecimentoTest`.
 *
 * `suspend` porque uma implementação real roda um embedder e varre um índice —
 * trabalho que não pode acontecer na thread principal.
 */
interface BaseDeConhecimento {

    /**
     * Recupera o trecho que responde [pergunta], ou `null` se nenhum trecho do
     * corpus estiver perto o bastante.
     *
     * [pergunta] é a transcrição do que o agente falou, em português, como saiu
     * do STT — com os erros dele. Normalizar é assunto da implementação.
     */
    suspend fun buscar(pergunta: String): Trecho?
}
