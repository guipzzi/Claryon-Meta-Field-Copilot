package com.claryon.llm

/**
 * **O contrato da Etapa B: um trecho já recuperado entra, uma fala curta sai —
 * ou nada sai.**
 *
 * O redator é uma camada de *redação*, não de conhecimento e não de decisão.
 * Ele nunca é a fonte do que se diz: o conteúdo vem inteiro de
 * [PedidoDeRedacao.trecho], que a Etapa A recuperou do corpus. Se o redator
 * falhar, recusar ou demorar, quem chama cai no caminho da Etapa A e o produto
 * continua de pé — é esse o desenho registrado no `ROADMAP.md`:
 *
 * > *"O produto nunca fica dependendo do LLM funcionar."*
 *
 * ## O caminho de queda é a CITAÇÃO — e este KDoc dizia outra coisa
 *
 * Até 22/08 estas linhas diziam *"quem chama **lê o trecho verbatim**"*. Nenhuma
 * das duas metades era verdade, e a diferença importa para quem for ligar esta
 * interface:
 *
 *  - **não há "quem chama".** `RedacaoDoCopiloto.redigir` — o único ponto de
 *    entrada desta interface no produto — tem **zero chamadores em `src/main`**;
 *  - **não há leitura verbatim.** O que o agente ouve numa consulta de norma é
 *    `"Art. 306, Lei 9.503"` — a citação e o documento. O corpo do artigo não
 *    atravessa a camada de recuperação: o que ela entrega é o par
 *    `citacao, norma`. Lê-lo em voz alta esbarraria no teto de **7 palavras** do
 *    `CLAUDE.md` §4, e está **proposto** — não construído — em
 *    `specs/leitura-de-norma.spec.md`.
 *
 * Ou seja: a rede de segurança existe e funciona, mas ela diz **onde** está a
 * regra, não **qual é** a regra. Quem for ligar a Etapa B precisa saber que o
 * degrau abaixo dela é esse, e não uma leitura completa do artigo.
 *
 * ## As três coisas que uma implementação não pode fazer
 *
 *  - **Não pode agir.** Este módulo não tem `core-agent` no classpath: os tipos
 *    de intenção, de execução e de resultado de ação não são nomeáveis daqui nem
 *    por engano — o compilador recusa. A fronteira é a mesma de
 *    `core-knowledge`, pelo mesmo motivo, e `FronteiraDoRedatorTest` a guarda.
 *    (Os nomes desses tipos não aparecem escritos em fonte de produção deste
 *    módulo de propósito: `Class.forName` aceita `String`, e o compilador não vê
 *    nome de classe escrito como texto.)
 *  - **Não pode falar do que não recebeu.** A saída passa por
 *    [GuardaDaRedacao] antes de chegar a qualquer alto-falante. Toda cifra tem
 *    de vir do pedido; texto sem lastro é recusado, e recusa vira leitura
 *    verbatim.
 *  - **Não pode lançar.** Modelo ausente, arquivo corrompido, prazo estourado:
 *    tudo vira `null`. Num aparelho sem display, exceção que sobe é
 *    indistinguível de o app ter morrido.
 *
 * `suspend` porque a inferência é trabalho de CPU medido em segundos e não pode
 * acontecer na thread principal.
 */
interface Redator {

    /**
     * Reescreve [pedido] em fala curta, ou devolve `null` quando não houve
     * redação confiável.
     *
     * `null` **não** é erro a ser tratado: é a instrução para o chamador usar o
     * caminho da Etapa A, que já é o padrão do produto.
     */
    suspend fun redigir(pedido: PedidoDeRedacao): String?

    /** Devolve a memória do modelo. Idempotente. */
    fun liberar() {}

    /**
     * **O redator que nunca redige.** É o que o produto usa quando a Etapa B
     * está desligada — por flag, por RAM insuficiente ou por falta do arquivo do
     * modelo.
     *
     * Existe para que o chamador tenha **um** caminho, não dois: a degradação é
     * a troca desta implementação, e não um `if` espalhado por quem fala. Foi
     * assim que o `ROADMAP.md` pediu: *"Nenhum caminho novo, nenhum código
     * morto"*.
     */
    object Inerte : Redator {
        override suspend fun redigir(pedido: PedidoDeRedacao): String? = null
    }
}

/**
 * Tudo o que o redator pode usar, e nada além disso.
 *
 * O tipo é a fronteira: não há campo para "contexto extra", "histórico" ou
 * "conhecimento geral". O que não está aqui não pode aparecer na resposta — e
 * [GuardaDaRedacao] confere isso depois, sobre o texto gerado.
 *
 * **Por que `procedencia` vem separada do `trecho`.** O agente precisa ouvir de
 * onde veio a regra para conferir depois; se a procedência viajasse dentro do
 * texto, o modelo poderia reescrevê-la — e um número de artigo reescrito é
 * exatamente a falha mais cara possível aqui. Ela sai do caminho do modelo e é
 * concatenada pelo chamador, byte a byte como a Etapa A a produziu.
 *
 * @property pergunta a transcrição do que o agente falou, com os erros do STT.
 * @property trecho o recorte de norma, como publicado. É a ÚNICA fonte de
 *   conteúdo permitida.
 * @property procedencia `"Art. 28 — Lei 11.343/2006"`. Vai para o guarda como
 *   material com lastro, mas quem a anuncia é o chamador.
 */
data class PedidoDeRedacao(
    val pergunta: String,
    val trecho: String,
    val procedencia: String,
) {
    init {
        require(trecho.isNotBlank()) {
            "Pedido de redação sem trecho é pedido para inventar norma."
        }
    }
}
