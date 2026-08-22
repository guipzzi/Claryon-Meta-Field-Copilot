package com.claryon.llm

/**
 * **As duas metades do prompt em um objeto só, para que trocá-las seja medível.**
 *
 * ## Por que isto virou tipo, e não duas constantes
 *
 * A primeira formulação deste projeto foi trocada **por medição** — seis linhas
 * com cláusula de escape produziam `"NÃO SEI."` em 5 de 6 gerações sobre um
 * artigo do CTB que respondia à pergunta (a história está no KDoc de
 * [ANDAIME_EM_CAIXA_ALTA]). O método estava certo; o que não existia era um jeito
 * de rodar a formulação B sem editar produção, e por isso a comparação seguinte
 * teria de ser feita de novo à mão, de memória.
 *
 * Este tipo existe para que **uma formulação seja um valor**: a bancada roda N
 * delas sobre o mesmo banco de perguntas, no mesmo aparelho, com o mesmo prazo,
 * e a tabela sai do log. O que muda de uma para a outra é **uma coisa**, na
 * ordem em que estão declaradas abaixo.
 *
 * ## As duas metades não são intercambiáveis
 *
 * [instrucao] viaja no papel de `system` e **nunca recebe material de fora**:
 * prompt de sistema montado com texto recuperado é o caminho por onde o corpus
 * passa a mandar no modelo. [usuario] é o único lugar onde entra material
 * variável, e ele carrega apenas o que [PedidoDeRedacao] permite.
 *
 * Nenhuma das duas oferece ação: não há ferramenta, não há campo de comando, não
 * há verbo de aparelho. O `CLAUDE.md` §2 proíbe LLM escolhendo ação, e a garantia
 * forte disso é de classpath (`FronteiraDoRedatorTest`) — a formulação é a
 * primeira camada, não a única.
 *
 * @property nome o rótulo que aparece no log da bancada. É o que liga uma linha
 *   da tabela a uma formulação; sem ele o relatório vira quatro colunas anônimas.
 */
class FormulacaoDoPrompt(
    val nome: String,
    val instrucao: String,
    private val corpoDoUsuario: (PedidoDeRedacao) -> String,
) {
    /** O conteúdo da mensagem `user`, montado a partir do pedido. */
    fun usuario(pedido: PedidoDeRedacao): String = corpoDoUsuario(pedido)

    override fun toString(): String = nome

    companion object {

        /**
         * **F0 — o andaime em caixa alta.** A formulação vigente até 22/08.
         *
         * **Ela já é o resultado de uma medição.** A versão anterior tinha seis
         * linhas e uma cláusula de escape (*"se o trecho não responder à
         * pergunta, responda NÃO SEI"*); no emulador, com este mesmo modelo, ela
         * produziu `"NÃO SEI."` em **5 de 6** gerações sobre um artigo do CTB que
         * respondia à pergunta diretamente. A cláusula saiu porque quem decide
         * que não sabe é o limiar de `PortaDoConhecimento`, não o modelo — ali
         * ela era só uma saída fácil.
         *
         * Os dois rótulos (`PERGUNTA DO AGENTE:`, `TRECHO DA NORMA:`) existiam
         * para separar as parcelas dentro da mensagem `user`. Medido no aparelho,
         * eles produzem duas famílias de defeito ao mesmo tempo:
         *
         *  - **vazamento**: o modelo copia o rótulo para a saída —
         *    *"O agente responde: … TRECHO DA NORMA: Art. 16. Possuir, deter…"*;
         *  - **meta-comentário**: o rótulo vira sujeito da frase —
         *    *"O TRECHO DA NORMA trata sobre…"*.
         *
         * E a presença da pergunta produz a terceira: o **eco**, em que a saída é
         * a pergunta devolvida como afirmação, com lastro zero na norma.
         *
         * Fica aqui como **linha de base**, não como alternativa: toda comparação
         * é contra ela.
         */
        val ANDAIME_EM_CAIXA_ALTA: FormulacaoDoPrompt = FormulacaoDoPrompt(
            nome = "F0-andaime-maiusculo",
            instrucao = """
                Resuma o TRECHO DA NORMA em duas frases curtas, em português do Brasil.
                Use somente o que está escrito no trecho. Não cite número de artigo.
                Comece direto pelo resumo, sem preâmbulo e sem aspas.
            """.trimIndent(),
        ) { pedido ->
            buildString {
                append("PERGUNTA DO AGENTE: ").append(pedido.pergunta).append('\n')
                append("TRECHO DA NORMA:\n").append(pedido.trecho).append('\n')
            }
        }

        /**
         * **F1 — o mesmo prompt sem os rótulos em caixa alta.** Muda **uma** coisa
         * em relação a [ANDAIME_EM_CAIXA_ALTA]: o andaime some do `user` e a
         * instrução deixa de nomeá-lo.
         *
         * A hipótese é literal: o modelo não inventa `TRECHO DA NORMA` — ele
         * **copia**, porque a sequência está no contexto, em caixa alta, e
         * caixa alta em modelo pequeno é token saliente. Tirando a string do
         * contexto, não há de onde copiá-la.
         *
         * A pergunta continua entrando, porque tirá-la é a mudança seguinte e
         * misturar as duas não permitiria atribuir o efeito.
         */
        val SEM_ROTULO: FormulacaoDoPrompt = FormulacaoDoPrompt(
            nome = "F1-sem-rotulo",
            instrucao = """
                Você reescreve trechos de lei para um policial ouvir pelo fone.
                Responda em duas frases curtas, em português do Brasil, usando
                somente o que está escrito no texto que o usuário enviar.
                Não cite número de artigo. Comece direto, sem preâmbulo e sem aspas.
            """.trimIndent(),
        ) { pedido ->
            buildString {
                append(pedido.pergunta.trim().removeSuffix(".")).append("?\n\n")
                append(pedido.trecho).append('\n')
            }
        }

        /**
         * **F2 — sem a pergunta.** Muda **uma** coisa em relação a [SEM_ROTULO]:
         * o modelo deixa de ver o que o agente perguntou.
         *
         * ## O que isto fecha, e o que custa
         *
         * Fecha o **eco por construção**: não há pergunta no contexto para ser
         * devolvida. É a versão de prompt do mesmo raciocínio que tirou a pergunta
         * da fonte do guarda em 22/08 (`EcoDaPerguntaNaoELastroTest`) — lá para
         * não dar lastro ao eco, aqui para não haver eco.
         *
         * **Custa foco.** A saída passa a ser resumo do trecho, não resposta à
         * pergunta. O contrapeso é que quem escolheu o trecho foi a Etapa A, a
         * partir da pergunta: o recorte já é a resposta, e o modelo só o encurta.
         * Se a medição mostrar queda de utilidade, é aqui que ela aparece.
         */
        val SEM_A_PERGUNTA: FormulacaoDoPrompt = FormulacaoDoPrompt(
            nome = "F2-sem-a-pergunta",
            instrucao = """
                Você reescreve trechos de lei para um policial ouvir pelo fone.
                Responda em duas frases curtas, em português do Brasil, usando
                somente o que está escrito no texto que o usuário enviar.
                Não cite número de artigo. Comece direto, sem preâmbulo e sem aspas.
            """.trimIndent(),
        ) { pedido -> pedido.trecho.trim() + "\n" }

        /**
         * **F3 — as proibições explícitas.** Muda **uma** coisa em relação a
         * [SEM_A_PERGUNTA]: duas linhas que nomeiam os dois defeitos restantes.
         *
         * A cláusula de escape continua **fora** — *"se não souber, diga NÃO
         * SEI"* foi medida e produziu recusa em 5 de 6 gerações sobre trecho que
         * respondia. Quem decide que não sabe é o limiar de `PortaDoConhecimento`,
         * não o modelo. Proibir defeito não é oferecer saída: não há resposta que
         * satisfaça as duas linhas ficando calado.
         */
        val PROIBICOES_EXPLICITAS: FormulacaoDoPrompt = FormulacaoDoPrompt(
            nome = "F3-proibicoes",
            instrucao = """
                Você reescreve trechos de lei para um policial ouvir pelo fone.
                Responda em duas frases curtas, em português do Brasil, usando
                somente o que está escrito no texto que o usuário enviar.
                Não cite número de artigo. Comece direto, sem preâmbulo e sem aspas.
                Não comente a tarefa nem o texto: escreva o conteúdo, não sobre ele.
                Não acrescente pena, prazo ou consequência que não esteja escrita.
            """.trimIndent(),
        ) { pedido -> pedido.trecho.trim() + "\n" }

        /**
         * **F4 — uma frase.** Muda **uma** coisa em relação a
         * [PROIBICOES_EXPLICITAS]: o tamanho pedido.
         *
         * Não é preferência de estilo, é orçamento. Metade das gerações de
         * produção bate no prazo de 2 500 ms **no meio da segunda frase**, e
         * frase truncada dita a um policial é pior que silêncio: ele ouve metade
         * de uma regra e não sabe que é metade. Pedir uma frase é pedir algo que
         * cabe no prazo.
         */
        val UMA_FRASE: FormulacaoDoPrompt = FormulacaoDoPrompt(
            nome = "F4-uma-frase",
            instrucao = """
                Você reescreve trechos de lei para um policial ouvir pelo fone.
                Responda em UMA frase curta, em português do Brasil, usando
                somente o que está escrito no texto que o usuário enviar.
                Não cite número de artigo. Comece direto, sem preâmbulo e sem aspas.
                Não comente a tarefa nem o texto: escreva o conteúdo, não sobre ele.
                Não acrescente pena, prazo ou consequência que não esteja escrita.
            """.trimIndent(),
        ) { pedido -> pedido.trecho.trim() + "\n" }

        /**
         * **A formulação que [RedatorLlamaCpp] usa quando ninguém escolhe outra.**
         *
         * Trocar esta linha é mudança de comportamento e só acontece **com a
         * medição junto** — é a regra que já governou as duas trocas anteriores.
         *
         * ## Por que [SEM_ROTULO], medido em 22/08
         *
         * Emulador arm64 API 35, 20 perguntas do banco de abordagem acima do
         * limiar de 0,30, prazo de 30 000 ms para que a carga da máquina virasse
         * latência e não mudez
         * (`OrcamentoDaEtapaBNoAparelhoTest.asFormulacoesDePromptSaoComparadasSobreOMesmoBanco`):
         *
         * ```
         * formulação   texto  aprova  eco  andaime  truncadas  utilizáveis
         * F0 (antes)     13      10     2      4        2           1
         * F1 (esta)      17       8     5      0        0           2
         * F2             18      16     0      0        0           1
         * F3             17      14     0      0        6           1
         * F4             16      14     0      0        4           1
         * ```
         *
         * F1 é a única que **domina** a anterior sem trocar um defeito por outro:
         * o vazamento do andaime some por completo (4 → 0, e é a prova de que o
         * defeito era de prompt, não de modelo), a truncagem some (2 → 0), e o
         * número de gerações com texto sobe de 13 para 17.
         *
         * **O eco sobe de 2 para 5 e isso é aceitável — porque o guarda os pega.**
         * As cinco caem na régua de lastro depois que a pergunta saiu da fonte
         * (`EcoDaPerguntaNaoELastroTest`); é a composição consertada no mesmo dia
         * pagando exatamente o que se esperava dela.
         *
         * **A queda de 10 para 8 aprovações não é regressão.** Das 10 de F0, 4
         * eram vazamento do andaime e 2 eram meta-comentário — texto que o guarda
         * aprova porque copiar a fonte dá lastro perfeito. Aprovar menos e vazar
         * zero é o negócio certo.
         *
         * **E `utilizáveis` mal se move: 1 → 2 de 20.** A troca de prompt conserta
         * o que o agente OUVE de errado; ela não faz um modelo de 1B saber
         * responder. Quem for decidir a Etapa B tem de olhar para esta coluna, e
         * não para `aprova` — ver o KDoc do teste, onde as duas estão
         * anticorrelacionadas.
         */
        val PRODUCAO: FormulacaoDoPrompt = SEM_ROTULO

        /** A bancada inteira, na ordem em que uma coisa muda por vez. */
        val TODAS: List<FormulacaoDoPrompt> = listOf(
            ANDAIME_EM_CAIXA_ALTA,
            SEM_ROTULO,
            SEM_A_PERGUNTA,
            PROIBICOES_EXPLICITAS,
            UMA_FRASE,
        )
    }
}
