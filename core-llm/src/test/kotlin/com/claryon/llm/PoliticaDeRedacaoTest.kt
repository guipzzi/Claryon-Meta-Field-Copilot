package com.claryon.llm

import com.claryon.llm.PoliticaDeRedacao.Decisao
import com.claryon.llm.PoliticaDeRedacao.Motivo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **A degradação por RAM, provada por contra-teste.**
 *
 * O `CLAUDE.md` §6, pergunta 3, é explícito sobre o modo de falha que este
 * arquivo existe para evitar: *"Se o teste passaria com o defeito de volta, ele
 * não testa o defeito. O contra-teste é o padrão: rode as duas configurações e
 * exija que difiram."*
 *
 * Aplicado aqui, isso significa que **não basta** afirmar "aparelho fraco cai na
 * Etapa A". Uma política que devolvesse `LerVerbatim` para tudo — inclusive para
 * o aparelho bom — satisfaria essa frase perfeitamente, e a Etapa B nunca
 * rodaria em lugar nenhum sem ninguém notar. Por isso todo teste de recusa aqui
 * tem o par que exige a decisão OPOSTA com a mesma entrada, mudando um número
 * só.
 */
class PoliticaDeRedacaoTest {

    private val modelo = 800L * 1024 * 1024      // um GGUF de 1B em Q4: ~800 MB
    private val ramDeCampo = 6L * 1024 * 1024 * 1024
    private val dispDeCampo = 3L * 1024 * 1024 * 1024

    private fun decidir(
        flag: Boolean = true,
        tamanho: Long = modelo,
        total: Long = ramDeCampo,
        disponivel: Long = dispDeCampo,
        pressao: Boolean = false,
    ) = PoliticaDeRedacao.decidir(flag, tamanho, total, disponivel, pressao)

    // ------------------------------------------------------------- o caso de sucesso

    /**
     * Controle positivo de tudo o que vem depois: **existe** configuração que
     * redige. Sem esta asserção, cada "cai na Etapa A" abaixo passaria por
     * vacuidade.
     */
    @Test
    fun aparelhoDeCampoComModeloEFlagLigadaRedige() {
        assertEquals(Decisao.Redigir, decidir())
    }

    // ------------------------------------------- contra-testes: um número, dois ramos

    /**
     * **O contra-teste do aceite.** Duas execuções idênticas exceto pela RAM
     * total do aparelho, e as decisões TÊM de diferir. É isto que separa
     * "degradação implementada" de "número lido do `ActivityManager`".
     */
    @Test
    fun aMesmaEntradaComRamAltaEBaixaDecideDiferente() {
        val forte = decidir(total = 6L * 1024 * 1024 * 1024)
        val fraco = decidir(total = 2L * 1024 * 1024 * 1024, disponivel = 1_500L * 1024 * 1024)

        assertEquals(Decisao.Redigir, forte)
        assertEquals(Decisao.LerVerbatim(Motivo.APARELHO_FRACO), fraco)
        assertNotEquals(
            "Se a política devolve a mesma coisa para 2 GB e para 6 GB, não há " +
                "degradação nenhuma — só uma leitura de MemoryInfo que ninguém usa.",
            forte,
            fraco,
        )
    }

    /**
     * O par da RAM **disponível**: mesmo aparelho, mesmo modelo, só a memória
     * livre muda. Abaixo da folga, Etapa A; acima, Etapa B.
     */
    @Test
    fun aMesmaEntradaComMemoriaLivreAlemEAquemDaFolgaDecideDiferente() {
        val precisa = (modelo * PoliticaDeRedacao.FOLGA_SOBRE_O_MODELO).toLong()

        val sobra = decidir(disponivel = precisa + 1)
        val aperta = decidir(disponivel = precisa - 1)

        assertEquals(Decisao.Redigir, sobra)
        assertEquals(Decisao.LerVerbatim(Motivo.RAM_INSUFICIENTE), aperta)
        assertNotEquals(sobra, aperta)
    }

    /**
     * O par da flag: a chave humana vence a medição, nos dois sentidos. Se
     * desligar não mudasse nada, o `ROADMAP.md` ("se decepcionar em pt-BR,
     * desliga por flag") descreveria um botão que não faz nada.
     */
    @Test
    fun aMesmaEntradaComFlagLigadaEDesligadaDecideDiferente() {
        val ligada = decidir(flag = true)
        val desligada = decidir(flag = false)

        assertEquals(Decisao.Redigir, ligada)
        assertEquals(Decisao.LerVerbatim(Motivo.DESLIGADO_POR_FLAG), desligada)
        assertNotEquals(ligada, desligada)
    }

    /** O par do modelo ausente — que é o estado de fábrica: o GGUF não vai no APK. */
    @Test
    fun semModeloEmFilesDirNaoRedige_eComModeloRedige() {
        assertEquals(Decisao.LerVerbatim(Motivo.SEM_MODELO), decidir(tamanho = 0L))
        assertEquals(Decisao.Redigir, decidir(tamanho = modelo))
    }

    @Test
    fun sistemaSobPressaoNaoRedige_eSemPressaoRedige() {
        assertEquals(Decisao.LerVerbatim(Motivo.SISTEMA_SOB_PRESSAO), decidir(pressao = true))
        assertEquals(Decisao.Redigir, decidir(pressao = false))
    }

    // ---------------------------------------------------------------- ordem e motivo

    /**
     * **A ordem das recusas é observável, e por isso é testada.**
     *
     * Não é preciosismo: o motivo vai para o log do aparelho, e é por ele que
     * alguém vai diagnosticar "por que o copiloto não está redigindo?" no dia da
     * demonstração. Um aparelho sem GGUF e com a flag desligada tem de dizer
     * `SEM_MODELO` — a causa que o operador consegue consertar com um `adb
     * push` — e não `DESLIGADO_POR_FLAG`, que o mandaria mexer na chave errada.
     */
    @Test
    fun oMotivoRelatadoEOMaisAcionavel() {
        assertEquals(
            Decisao.LerVerbatim(Motivo.SEM_MODELO),
            decidir(flag = false, tamanho = 0L, total = 1L, disponivel = 1L, pressao = true),
        )
        assertEquals(
            Decisao.LerVerbatim(Motivo.DESLIGADO_POR_FLAG),
            decidir(flag = false, total = 1L, disponivel = 1L, pressao = true),
        )
    }

    /**
     * **O emulador desta máquina, com os números que ele reporta de verdade.**
     *
     * `/proc/meminfo` do emulador arm64 (Android 15) usado nesta sessão:
     * `MemTotal: 2 531 992 kB`. Com o Llama 3.2 1B Q4_K_M (807 694 464 B), a
     * política recusa por [Motivo.APARELHO_FRACO] — e isso não é caso
     * hipotético montado para o teste passar: é o aparelho que está na mesa.
     *
     * Vale registrar porque muda o que a medição de latência significa. Os
     * números de carga e de geração desta sessão foram obtidos **contornando**
     * esta decisão de propósito (teste instrumentado constrói o motor direto);
     * pelo caminho de produção, este emulador lê o trecho verbatim.
     */
    @Test
    fun oEmuladorDestaSessaoCaiNaEtapaA() {
        assertEquals(
            Decisao.LerVerbatim(Motivo.APARELHO_FRACO),
            PoliticaDeRedacao.decidir(
                flagLigada = true,
                tamanhoDoModeloBytes = 807_694_464L,
                ramTotalBytes = 2_531_992L * 1024,
                ramDisponivelBytes = 1_387_284L * 1024,
                sistemaSobPressao = false,
            ),
        )
    }

    /**
     * **A troca de modelo de 22/08 reescalou o portão sozinha — e cobrou por isso.**
     *
     * `Llama-3.2-1B-Instruct-Q4_K_M` (807 694 464 B) saiu e entrou
     * `Qwen2.5-1.5B-Instruct-Q4_K_M` (986 048 768 B). [PoliticaDeRedacao] **não
     * mudou uma linha**, porque a exigência é `tamanho × FOLGA_SOBRE_O_MODELO` e
     * não uma constante por modelo:
     *
     * ```
     * Llama  807 694 464 × 1,90 = 1 534 619 481 B  (1 463,5 MiB)
     * Qwen   986 048 768 × 1,90 = 1 873 492 659 B  (1 786,7 MiB)
     * ```
     *
     * Este teste é o **contra-teste da afirmação de que reescalou**: um portão que
     * ignorasse o tamanho do arquivo daria a mesma decisão para os dois, e passaria
     * em qualquer teste que só olhasse um modelo por vez. A asserção exige que as
     * duas decisões **difiram** com a mesma RAM.
     *
     * E ele documenta o preço, que é real e é para o lado ruim: **existe aparelho
     * que redigia com o modelo antigo e recusa com o novo.** 1,58 GiB de `availMem`
     * num aparelho de 4 GB é exatamente essa faixa. Não é defeito — é o portão
     * fazendo o trabalho —, mas quem for medir a Etapa B no aparelho de campo tem
     * de medir a decisão do portão junto, e não só a latência da geração.
     */
    @Test
    fun aTrocaDeModeloReescalaOPortao_eIssoTemPreco() {
        val ram = 4L * 1024 * 1024 * 1024
        val disponivel = 1_700_000_000L        // ~1,58 GiB — entre as duas exigências

        val comLlama = PoliticaDeRedacao.decidir(
            flagLigada = true,
            tamanhoDoModeloBytes = 807_694_464L,
            ramTotalBytes = ram,
            ramDisponivelBytes = disponivel,
            sistemaSobPressao = false,
        )
        val comQwen = PoliticaDeRedacao.decidir(
            flagLigada = true,
            tamanhoDoModeloBytes = 986_048_768L,
            ramTotalBytes = ram,
            ramDisponivelBytes = disponivel,
            sistemaSobPressao = false,
        )

        assertEquals(
            "O modelo antigo cabia nesta RAM. Se esta linha falhar, o portão " +
                "deixou de escalar pelo tamanho do arquivo.",
            Decisao.Redigir,
            comLlama,
        )
        assertEquals(
            "O modelo novo NÃO cabe na mesma RAM, e o produto tem de saber disso " +
                "antes do dia do evento, não durante.",
            Decisao.LerVerbatim(Motivo.RAM_INSUFICIENTE),
            comQwen,
        )
        assertNotEquals(
            "Mesma RAM, mesma flag, decisões iguais: o portão parou de olhar o " +
                "tamanho do GGUF e virou constante disfarçada.",
            comLlama,
            comQwen,
        )
    }
}
