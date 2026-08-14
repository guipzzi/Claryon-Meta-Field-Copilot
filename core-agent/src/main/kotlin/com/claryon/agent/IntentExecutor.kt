package com.claryon.agent

/**
 * Executa uma [Intent] e devolve **o que de fato aconteceu**.
 *
 * É a camada que faltava entre "entendi o comando" e "falei a resposta". Antes
 * dela, o ciclo de voz ia do roteador direto para a frase — e o produto afirmava
 * ações que nunca tentou.
 *
 * Contrato que as implementações precisam honrar:
 *  - **nunca lançar** no caminho crítico: toda falha vira
 *    [ActionOutcome.Falhou] com causa tipada, porque falha silenciosa num
 *    sistema sem display é indistinguível de morte do aplicativo;
 *  - o resultado descreve o **estado do mundo depois** da tentativa, não a
 *    intenção que a originou;
 *  - ações com efeito externo (despacho, gravação) devolvem o dado que o agente
 *    precisa para decidir o próximo passo — quantos receberam, se entrou na fila.
 */
interface IntentExecutor {
    suspend fun execute(intent: Intent): ActionOutcome
}
