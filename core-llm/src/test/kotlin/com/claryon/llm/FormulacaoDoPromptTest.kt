package com.claryon.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O que dá para provar sobre o prompt sem o aparelho — e o que não dá.**
 *
 * A qualidade da formulação é medida no emulador, sobre 20 perguntas reais, em
 * `OrcamentoDaEtapaBNoAparelhoTest`. Nada aqui substitui aquilo, e este arquivo
 * não tenta: um teste de JVM que afirmasse "o prompt é bom" seria a mentira que o
 * `CLAUDE.md` §6 persegue.
 *
 * O que este arquivo guarda são as **propriedades estruturais** que sustentam
 * aquela medição — as que, se quebrarem, fazem o número no aparelho descrever
 * outra coisa sem ninguém perceber.
 */
class FormulacaoDoPromptTest {

    private val pedido = PedidoDeRedacao(
        pergunta = "adulterar placa da quantos anos de cadeia",
        trecho = "Art. 311. Adulterar ou remarcar número de chassi ou sinal identificador " +
            "de veículo automotor: Pena - reclusão, de três a seis anos, e multa.",
        procedencia = "Art. 311 — Decreto-Lei 2.848/1940",
    )

    /**
     * **O andaime não pode estar no contexto da formulação de produção.**
     *
     * Medido em 22/08: com `PERGUNTA DO AGENTE:` e `TRECHO DA NORMA:` no `user`,
     * **4 de 13** gerações copiaram o rótulo para a resposta —
     * *"O TRECHO DA NORMA trata sobre…"*. Sem os rótulos, **0 de 17**. O modelo
     * não inventa a string; ele a copia. Devolvê-la ao contexto devolve o defeito.
     */
    @Test
    fun aFormulacaoDeProducao_naoPoeOAndaimeNoContexto() {
        val texto = FormulacaoDoPrompt.PRODUCAO.let { it.instrucao + "\n" + it.usuario(pedido) }
        for (rotulo in listOf("TRECHO DA NORMA", "PERGUNTA DO AGENTE")) {
            assertFalse(
                "A formulação de produção voltou a pôr \"$rotulo\" no contexto. Medido em " +
                    "22/08: 4 de 13 gerações copiam o rótulo para a fala do copiloto.",
                rotulo in texto,
            )
        }
    }

    /**
     * **Contra-teste do de cima.** Sem ele, a asserção acima passaria por vacuidade
     * se `usuario` parasse de devolver o material — e um prompt vazio também não
     * tem andaime.
     *
     * [FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA] existe justamente para reproduzir
     * o defeito, e é contra ela que a bancada compara.
     */
    @Test
    fun aLinhaDeBase_AINDA_temOAndaime_senaoNaoHaComparacao() {
        val texto = FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA.let {
            it.instrucao + "\n" + it.usuario(pedido)
        }
        assertTrue(
            "F0 deixou de conter o andaime. Ela é a LINHA DE BASE da medição de " +
                "22/08 — se ela mudar, a tabela do teste no aparelho passa a comparar " +
                "duas coisas novas e o número histórico não descreve mais nada.",
            "TRECHO DA NORMA" in texto && "PERGUNTA DO AGENTE" in texto,
        )
    }

    /**
     * **A instrução é fixa e não recebe nada do pedido.**
     *
     * Prompt de sistema montado com texto recuperado é o caminho por onde o corpus
     * passa a mandar no modelo. Aqui o material variável só pode viajar no papel de
     * `user`. A varredura é sobre **todas** as formulações, inclusive as que só
     * existem para medir: uma bancada que rodasse com sistema contaminado mediria
     * um produto diferente do que o aceite descreve.
     */
    @Test
    fun nenhumaInstrucaoCarregaMaterialDoPedido() {
        for (f in FormulacaoDoPrompt.TODAS_MEDIDAS) {
            for (material in listOf(pedido.pergunta, pedido.trecho, pedido.procedencia)) {
                assertFalse(
                    "A instrução de ${f.nome} contém material do pedido. O `system` é " +
                        "fixo; o que varia entra no `user`.",
                    material in f.instrucao,
                )
            }
        }
    }

    /**
     * **A procedência nunca entra no caminho do modelo.**
     *
     * O número do artigo e o nome da lei saem do trecho recuperado byte a byte,
     * pela boca de quem chama — nunca pela do modelo. Artigo reescrito é a falha
     * mais cara que existe aqui, e o KDoc de [PedidoDeRedacao] registra que a
     * separação de campo existe por isso.
     */
    @Test
    fun nenhumaFormulacaoMandaAProcedenciaAoModelo() {
        for (f in FormulacaoDoPrompt.TODAS_MEDIDAS) {
            assertFalse(
                "${f.nome} manda a procedência ao modelo. Ela é concatenada por quem " +
                    "chama, para que o modelo não possa reescrevê-la.",
                pedido.procedencia in (f.instrucao + f.usuario(pedido)),
            )
        }
    }

    /**
     * **O trecho SEMPRE chega ao modelo — é a única fonte de conteúdo permitida.**
     *
     * Controle positivo dos três testes acima: eles são todos `assertFalse` sobre
     * uma string, e passariam juntos se `usuario` devolvesse vazio.
     */
    @Test
    fun toda_formulacaoEntregaOTrecho() {
        for (f in FormulacaoDoPrompt.TODAS) {
            assertTrue(
                "${f.nome} não entrega o trecho ao modelo. Sem ele o pedido é para " +
                    "inventar norma, e os testes de ausência acima viram vácuo.",
                pedido.trecho.trim() in f.usuario(pedido),
            )
        }
    }

    /**
     * **F2 em diante não mandam a pergunta, e é isso que fecha o eco por construção.**
     *
     * Medido: F1 (com pergunta) rende **5** ecos em 20; F2, F3 e F4 rendem **0**.
     * Não é o modelo que melhorou — é que não há pergunta no contexto para devolver.
     */
    @Test
    fun asFormulacoesSemPergunta_naoMandamAPergunta() {
        val semPergunta = listOf(
            FormulacaoDoPrompt.SEM_A_PERGUNTA,
            FormulacaoDoPrompt.PROIBICOES_EXPLICITAS,
            FormulacaoDoPrompt.UMA_FRASE,
        )
        for (f in semPergunta) {
            assertFalse(
                "${f.nome} manda a pergunta ao modelo. Ela existe exatamente para " +
                    "medir o efeito de NÃO mandar.",
                pedido.pergunta in f.usuario(pedido),
            )
        }

        // Controle: as duas que MANDAM continuam mandando, senão a diferença medida
        // entre os blocos deixa de ter causa.
        for (f in listOf(FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA, FormulacaoDoPrompt.SEM_ROTULO)) {
            assertTrue(
                "${f.nome} parou de mandar a pergunta. Ela é o lado 'com pergunta' do " +
                    "par, e sem ele F2 não isola nada.",
                pedido.pergunta in f.usuario(pedido).lowercase(),
            )
        }
    }

    /**
     * **A única formulação que não entrega o trecho é a da Pista 2, e ela não
     * está em [FormulacaoDoPrompt.TODAS] por isso.**
     *
     * `toda_formulacaoEntregaOTrecho` está certo e continua valendo para geração
     * livre: prompt sem trecho é pedido para inventar norma. O que muda em
     * [FormulacaoDoPrompt.EXTRACAO_SO_A_PERGUNTA] é que a fonte não viaja no
     * contexto — viaja na **gramática**, e fora dela não há token sorteável.
     *
     * Este teste é a fronteira escrita: se um dia F7 aparecer em `TODAS`, o teste
     * de lá quebra; se ela parar de omitir o trecho, quebra este, e a medição de
     * prefill mínimo deixa de medir prefill mínimo.
     */
    @Test
    fun aDaPista2_naoEntregaOTrecho_eNaoEstaEmTODAS() {
        assertFalse(
            "F7 voltou a mandar o trecho ao modelo. Ela existe para medir o prefill " +
                "mínimo: com a gramática na cadeia, a fonte está na jaula, não no contexto.",
            pedido.trecho.trim() in FormulacaoDoPrompt.EXTRACAO_SO_A_PERGUNTA.usuario(pedido),
        )
        assertFalse(
            "F7 entrou em TODAS, onde toda formulação tem de entregar o trecho.",
            FormulacaoDoPrompt.EXTRACAO_SO_A_PERGUNTA in FormulacaoDoPrompt.TODAS,
        )
        // Controle: a irmã dela, que difere APENAS por levar o trecho, leva mesmo.
        // Sem isto o par não isola nada.
        assertTrue(
            "F6 parou de entregar o trecho. Ela é o lado 'com contexto' do par F6/F7.",
            pedido.trecho.trim() in FormulacaoDoPrompt.EXTRACAO_COM_TRECHO.usuario(pedido),
        )
    }

    /** Nome repetido embaralha as linhas da tabela do log, que é lida por humano. */
    @Test
    fun osNomesSaoUnicos() {
        val nomes = FormulacaoDoPrompt.TODAS_MEDIDAS.map { it.nome }
        assertEquals("Nome de formulação repetido: $nomes", nomes.size, nomes.toSet().size)
    }

    /**
     * **A escolha de produção tem de ser uma das medidas.**
     *
     * Apontar `PRODUCAO` para uma formulação fora de [FormulacaoDoPrompt.TODAS]
     * poria em campo um prompt que nunca passou pela bancada — que é a forma mais
     * silenciosa de desfazer a medição de 22/08.
     */
    @Test
    fun aDeProducaoFoiMedida() {
        assertTrue(
            "PRODUCAO não está em TODAS: o prompt de campo não passou pela bancada.",
            FormulacaoDoPrompt.PRODUCAO in FormulacaoDoPrompt.TODAS,
        )
        assertNotEquals(
            "PRODUCAO voltou a ser F0. Medido em 22/08: F0 vaza o andaime em 4 de 13 " +
                "gerações e trunca em 2; F1 vaza 0 e trunca 0, com 17 textos em vez de 13.",
            FormulacaoDoPrompt.ANDAIME_EM_CAIXA_ALTA.nome,
            FormulacaoDoPrompt.PRODUCAO.nome,
        )
    }
}
