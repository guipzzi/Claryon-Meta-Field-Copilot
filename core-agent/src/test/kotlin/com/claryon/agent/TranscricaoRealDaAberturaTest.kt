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

    @Test
    fun oQueOMicrofoneProduzHoje_NAO_abreTransmissao() {
        val abrem = transcricoesReais.filter { router.route(it) is Intent.AbrirTransmissao }
        // Este teste documenta o ESTADO, não o desejo. Ele passa hoje porque a
        // feature não dispara — e é essa a informação que faltava.
        assertTrue(
            "a transcrição real passou a abrir transmissão: ótimo, e então este " +
                "teste tem de ser reescrito como aceite, não como registro. Não " +
                "apague — troque a asserção. Casos: $abrem",
            abrem.isEmpty(),
        )
    }

    /**
     * O motivo é duplo, e separá-los importa porque os consertos são diferentes:
     * um é de padrão, o outro é de vocabulário do modelo.
     */
    @Test
    fun aPalavraDeAtivacaoNoPREFIXO_sozinha_jaImpedeOCasamento() {
        assertTrue(
            "com a grafia certa e só o prefixo do gatilho, ainda recusa — então " +
                "o problema do prefixo existe independente do WER",
            router.route("Claryon, guarnição 1 na escuta.") !is Intent.AbrirTransmissao,
        )
    }

    @Test
    fun aGrafiaERRADA_sozinha_jaImpedeOCasamento() {
        assertTrue(
            "sem prefixo nenhum, só com 'Guarney são' no lugar de 'guarnição', " +
                "ainda recusa — então o problema de WER existe independente do prefixo",
            router.route("Guarney são 1 na escuta") !is Intent.AbrirTransmissao,
        )
    }

    /** E o caminho limpo continua funcionando: o defeito é dos dois acima, não do padrão. */
    @Test
    fun aFraseLIMPA_continuaAbrindo() {
        assertTrue(router.route("guarnição 1 na escuta") is Intent.AbrirTransmissao)
    }
}
