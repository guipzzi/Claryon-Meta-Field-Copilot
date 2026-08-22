package com.claryon.llm

/**
 * **Quem decide, no boot, se este aparelho redige ou apenas lê.**
 *
 * A regra do `ROADMAP.md` é literal: *"Degradação por flag ou RAM disponível no
 * boot: aparelho fraco fica na Etapa A, que já está pronta por construção.
 * Nenhum caminho novo, nenhum código morto."* Este objeto é a única forma da
 * decisão, e ele é **puro** de propósito — sem `Context`, sem `ActivityManager`,
 * sem arquivo.
 *
 * ## Por que puro, quando a entrada vem toda do Android
 *
 * Porque senão a degradação não é testável, e degradação não testada é
 * degradação que ninguém sabe se acontece. Ler `ActivityManager.MemoryInfo`
 * prova que o número foi lido; **não** prova que o caminho de aparelho fraco
 * existe. Com a decisão isolada aqui, o contra-teste é trivial e obrigatório:
 * as mesmas entradas com a RAM baixa e alta têm de produzir decisões
 * DIFERENTES. Um teste que passa nos dois ramos não testa nada
 * (`CLAUDE.md` §6, pergunta 3).
 *
 * ## A ordem das recusas não é arbitrária
 *
 * Da mais barata e mais definitiva para a mais circunstancial. Sem arquivo de
 * modelo não há o que decidir; com a flag desligada a decisão é humana e vence
 * qualquer medida; `lowMemory` é o próprio sistema dizendo que já está
 * matando processos; e só então a conta de folga.
 */
object PoliticaDeRedacao {

    /**
     * **Folga sobre o tamanho do GGUF — medida no emulador, não estimada.**
     *
     * Os pesos entram por `mmap` e contam como page cache, mas o contexto (KV
     * cache), os buffers de computação e o próprio grafo são alocação viva. O
     * fator cobre isso com margem: abaixo dele o sistema começa a despejar
     * páginas dos pesos e a inferência entra em thrash de E/S — que no aparelho
     * aparece como "o copiloto travou", não como erro.
     *
     * **1,35 era chute e a medição o desmentiu.** No emulador arm64 desta
     * sessão, carregar `Llama-3.2-1B-Instruct-Q4_K_M` (807 694 464 B em disco,
     * 762,8 MiB de pesos) com `n_ctx = 1024` levou o PSS do processo de
     * ~110 MB para ~1,60 GB — **Δ 1,49 GiB**, lido por `Debug.getMemoryInfo`
     * em `RedatorNoAparelhoTest`. Sobre o tamanho em disco isso dá **1,94×**.
     *
     * O valor abaixo é esse número medido, arredondado para baixo em favor de
     * permitir a Etapa B onde ela cabe. Trocá-lo exige medir de novo, não
     * estimar de novo.
     */
    const val FOLGA_SOBRE_O_MODELO: Double = 1.90

    /**
     * Piso de RAM total do aparelho. Abaixo disto o LLM pode até caber no
     * instante do boot e sufocar assim que o mapa, o whisper e o Piper
     * estiverem todos vivos — que é o estado normal deste app, não o excepcional.
     */
    const val RAM_TOTAL_MINIMA_BYTES: Long = 3L * 1024 * 1024 * 1024

    /** O que o produto vai fazer com o trecho recuperado. */
    sealed interface Decisao {
        /**
         * Etapa B: o LLM reescreve, com a Etapa A como rede de segurança.
         *
         * **A rede é a CITAÇÃO, não a leitura do artigo** — ver [LerVerbatim].
         */
        data object Redigir : Decisao

        /**
         * Etapa A. [motivo] vai para o log.
         *
         * **O nome deste caso mente, e ficou.** Ele diz `LerVerbatim`, mas o que
         * o produto faz neste ramo é falar `"Art. 306, Lei 9.503"` — quatro
         * palavras, a procedência e nada mais. O texto do artigo **não é lido**:
         * a camada de recuperação entrega ao produto só a citação e o documento,
         * nunca o corpo do artigo. Lê-lo em voz alta esbarra no teto de 7
         * palavras do `CLAUDE.md` §4 e está **proposto**, não construído, em
         * `specs/leitura-de-norma.spec.md`.
         *
         * O nome não foi trocado porque renomear um `sealed` público é diff largo
         * em módulo que outro agente está tocando; a mentira fica **anotada** até
         * a decisão humana sobre aquela spec, que resolve as duas coisas de uma
         * vez — ou o produto passa a ler, e o nome vira verdade, ou não passa, e
         * o nome vira `CitarProcedencia`.
         */
        data class LerVerbatim(val motivo: Motivo) : Decisao
    }

    enum class Motivo {
        /** Não há GGUF em `filesDir`. É o estado de fábrica: o modelo não vai no APK. */
        SEM_MODELO,

        /** Desligada por decisão humana. Vence qualquer medição. */
        DESLIGADO_POR_FLAG,

        /** O próprio Android declarou pressão de memória. */
        SISTEMA_SOB_PRESSAO,

        /** RAM total do aparelho abaixo do piso. */
        APARELHO_FRACO,

        /** Há RAM no aparelho, mas não sobra o bastante agora. */
        RAM_INSUFICIENTE,
    }

    /**
     * @param flagLigada a chave humana. `false` desliga a Etapa B sem discussão.
     * @param tamanhoDoModeloBytes tamanho do GGUF em `filesDir`, ou `0` se ausente.
     * @param ramTotalBytes `ActivityManager.MemoryInfo.totalMem`.
     * @param ramDisponivelBytes `ActivityManager.MemoryInfo.availMem`.
     * @param sistemaSobPressao `ActivityManager.MemoryInfo.lowMemory`.
     */
    fun decidir(
        flagLigada: Boolean,
        tamanhoDoModeloBytes: Long,
        ramTotalBytes: Long,
        ramDisponivelBytes: Long,
        sistemaSobPressao: Boolean,
    ): Decisao {
        if (tamanhoDoModeloBytes <= 0L) return Decisao.LerVerbatim(Motivo.SEM_MODELO)
        if (!flagLigada) return Decisao.LerVerbatim(Motivo.DESLIGADO_POR_FLAG)
        if (sistemaSobPressao) return Decisao.LerVerbatim(Motivo.SISTEMA_SOB_PRESSAO)
        if (ramTotalBytes < RAM_TOTAL_MINIMA_BYTES) {
            return Decisao.LerVerbatim(Motivo.APARELHO_FRACO)
        }
        val precisa = (tamanhoDoModeloBytes * FOLGA_SOBRE_O_MODELO).toLong()
        if (ramDisponivelBytes < precisa) return Decisao.LerVerbatim(Motivo.RAM_INSUFICIENTE)
        return Decisao.Redigir
    }
}
