package com.claryon.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A frase de abertura, como o whisper REALMENTE a devolve.**
 *
 * `TranscricaoNaOrigemTest` rodou no aparelho com fala humana e servidor real, e a
 * transcrição foi registrada no log:
 *
 * ```
 * "Clareon, Guarney são 1 na escuta. Clareon, Guarney são 2 na escuta."
 * ```
 *
 * O agente disse *"Claryon, guarnição 1 na escuta"*. Duas coisas que os testes
 * sintéticos não podiam mostrar:
 *
 * 1. **A palavra de ativação entra na transcrição.** O whisper não sabe que
 *    "Claryon" foi o gatilho — ele transcreve tudo que ouviu. Um padrão ancorado em
 *    `^guarnicao` recusa toda fala real.
 * 2. **"guarnição" vira "Guarney são".** É erro de WER em pt-BR, e não é ruído
 *    aleatório: é sistemático, porque o modelo não tem a palavra no contexto certo.
 *
 * Estes casos são **dado medido**, não hipótese. Ficam aqui para que qualquer
 * conserto do roteador seja avaliado contra o que o microfone produz, e não contra
 * o que seria bom que ele produzisse.
 */
class TranscricaoRealDaAberturaTest {

    private val router = DeterministicIntentRouter()

    /** Exatamente o que saiu do whisper no aparelho, em 20/08. */
    private val transcricoesReais = listOf(
        "Clareon, Guarney são 1 na escuta.",
        "Clareon, Guarney são 2 na escuta.",
    )

    /**
     * **O aceite.** Este teste já foi um registro de defeito — ele afirmava que a
     * transcrição real NÃO abria transmissão, e passava por isso. A instrução que
     * ficou escrita nele era "não apague, troque a asserção quando mudar". Mudou.
     */
    @Test
    fun oQueOMicrofoneProduzDEVERDADE_abreTransmissao() {
        val recusadas = transcricoesReais.filterNot { router.route(it) is Intent.AbrirTransmissao }
        assertTrue(
            "a transcrição REAL do whisper não abre transmissão: $recusadas. " +
                "Os testes sintéticos passam e o produto não funciona — é a " +
                "diferença entre o que seria bom que o microfone produzisse e o " +
                "que ele produz",
            recusadas.isEmpty(),
        )
    }

    /**
     * **O buffer da bancada traz DUAS elocuções, e ele não pode abrir canal.**
     *
     * O WAV de teste tem cinco segundos com duas tomadas, e o whisper devolve as
     * duas numa string só. Em produção isso não acontece: o Silero fecha a janela a
     * 0,3 s de silêncio e cada frase vira um segmento.
     *
     * Recusar é o certo aqui. São dois comandos, e escolher um em silêncio poria o
     * agente no ar numa guarnição que ele mencionou de passagem — a 1 ou a 2, sem
     * ele saber qual. O casamento integral protege exatamente isso.
     */
    @Test
    fun oBufferComDUASElocucoes_naoAbreCanal() {
        val duas = "Clareon, Guarney são 1 na escuta. Clareon, Guarney são 2 na escuta."
        assertTrue(
            "duas frases numa string só abriram canal — qual das duas guarnições?",
            router.route(duas) !is Intent.AbrirTransmissao,
        )
    }

    /** E o rótulo sai na grafia do CADASTRO, não na do whisper. */
    @Test
    fun oRotuloSaiCANONICO_paraOResolvedorPoderCasar() {
        val i = router.route("Clareon, Guarney são 1 na escuta.") as Intent.AbrirTransmissao
        assertTrue(
            "o rótulo saiu como o whisper escreveu (${i.rotuloFalado}) — o " +
                "resolvedor compara contra `rotulo_falado` e não casaria nunca",
            i.rotuloFalado == "guarnicao 1",
        )
    }

    /**
     * O motivo é duplo, e separá-los importa porque os consertos são diferentes:
     * um é de padrão, o outro é de vocabulário do modelo.
     */
    /** Os dois defeitos eram independentes; os dois consertos também. */
    @Test
    fun oPREFIXO_sozinho_naoImpedeMais() {
        assertTrue(router.route("Claryon, guarnição 1 na escuta.") is Intent.AbrirTransmissao)
        assertTrue(router.route("Hey Claryon, guarnição 1 na escuta") is Intent.AbrirTransmissao)
    }

    @Test
    fun aGRAFIA_sozinha_naoImpedeMais() {
        assertTrue(router.route("Guarney são 1 na escuta") is Intent.AbrirTransmissao)
    }

    /**
     * **O que o prefixo passou a valer.** Ele era o defeito e virou a prova: sem a
     * palavra de ativação no começo, a frase é conversa de rádio entre pessoas.
     *
     * É o segundo estágio da arquitetura de dois: o detector acústico dispara o
     * earcon rápido, e a transcrição confere se o agente falou mesmo com o
     * copiloto. Este teste fixa que a conferência NÃO derruba o caminho sem
     * gatilho — a frase limpa continua abrindo, porque o ciclo por botão e o
     * verificador de bancada dependem dela.
     */
    @Test
    fun semGatilho_aFraseLimpaContinuaValendo() {
        assertTrue(router.route("guarnição 1 na escuta") is Intent.AbrirTransmissao)
    }

    /** E "Claryon" no MEIO é o agente falando SOBRE o copiloto, não COM ele. */
    @Test
    fun oGatilhoNoMEIO_naoEComando() {
        val i = router.route("pergunta pro Claryon guarnição 1 na escuta")
        assertTrue(
            "gatilho no meio da frase virou comando: isso é conversa entre pessoas",
            i !is Intent.AbrirTransmissao,
        )
    }

    /** E o caminho limpo continua funcionando: o defeito é dos dois acima, não do padrão. */
    @Test
    fun aFraseLIMPA_continuaAbrindo() {
        assertTrue(router.route("guarnição 1 na escuta") is Intent.AbrirTransmissao)
    }
}
