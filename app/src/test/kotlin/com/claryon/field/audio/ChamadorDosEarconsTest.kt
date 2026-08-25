package com.claryon.field.audio

import com.claryon.common.Earcon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Todo earcon tem de ter quem o toque em `src/main`.**
 *
 * `CLAUDE.md` §6 pergunta 2: *"`grep` do símbolo: quem chama isto em `src/main`?
 * Zero chamador = escrito, não construído."* O projeto conta **seis** vezes em que
 * construiu, testou e não ligou. Um earcon é o caso mais fácil de cometer o sétimo:
 * ele nasce numa entrada de `enum`, ganha um ramo no `when` do sintetizador, passa
 * nos testes de síntese — e nunca sai por um alto-falante. Do lado de fora isso é
 * indistinguível de capacidade pronta.
 *
 * Este teste é a trava. Ele varre a produção de `app` e dos `core-*` e exige que
 * **cada** entrada de [Earcon] apareça num arquivo que não seja um dos três que
 * apenas a *descrevem*:
 *
 *  - `AudioSignals.kt` — onde ela é **declarada**;
 *  - `EarconSynthesizer.kt` — onde ela vira **PCM**. Sintetizar não é tocar: era
 *    exatamente esse o estado que este teste existe para reprovar;
 *  - `SaidaUnica.kt` — onde ela é **classificada** para telemetria (`EARCONS_DO_CICLO`).
 *    Dizer "este tom marca um ciclo de voz" não emite tom nenhum.
 *
 * Os três não são exceções de conveniência: são os únicos lugares em que citar um
 * earcon é falar *sobre* ele em vez de *tocá-lo*, e essa distinção é a que separa
 * "escrito" de "construído".
 *
 * ### Este teste também morre quando deve
 *
 * Se um earcon for aposentado — como o `OUVI_VOCE` foi em 22/08, quando a gramática
 * `BOMMM / bipbip / trimtrim` tomou os dois lugares que ele ocupava —, a resposta
 * certa é tirá-lo do `enum`, não deixá-lo sem chamador. Um earcon órfão continua
 * ocupando memória de cache, aparece na tabela de distinguibilidade e disputa
 * espaço acústico num vocabulário que já vive espremido em 3 kHz.
 */
class ChamadorDosEarconsTest {

    /**
     * Onde um earcon pode ser tocado: produção de `app` e dos módulos. Testes ficam
     * de fora **de propósito** — emitir um earcon num teste é medir, e medir não é
     * ligar. É a mesma linha que `ChamadorDaRedacaoTest` traça, pelo mesmo motivo.
     */
    private fun fontesDeProducao(): List<File> {
        val raiz = raizDoRepositorio()
        return raiz.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "app" || it.name.startsWith("core-")) }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            // `/cpp/`, e não `/cpp/llama/`: o exemplo vendorizado do whisper.cpp tem
            // um `MainActivity.kt` que sobrescrevia o deste aplicativo no índice por
            // nome de [emissoesDe]. Aqui a cegueira dava falso VERMELHO — um earcon
            // tocado só na raiz de composição apareceria como órfão —, mas é o mesmo
            // defeito de `ChamadorDaRedacaoTest`, onde a direção é a perigosa.
            .filterNot { it.path.contains("/cpp/") }
    }

    /** Arquivo → linhas que citam `Earcon.NOME`, já sem comentário e sem KDoc. */
    private fun emissoesDe(earcon: String): Map<String, List<String>> =
        fontesDeProducao()
            .filterNot { it.name in DESCREVEM_SEM_TOCAR }
            .associate { arquivo ->
                arquivo.name to arquivo.readLines()
                    .map { it.trim() }
                    .filter {
                        it.contains("Earcon.$earcon") &&
                            !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*")
                    }
            }
            .filterValues { it.isNotEmpty() }

    // -------------------------------------------------------------------- a trava

    @Test
    fun todoEarconTemChamadorAlcancavelEmProducao() {
        val fontes = fontesDeProducao()
        assertTrue(
            "Varri ${fontes.size} arquivos de produção. Varredura vazia não prova " +
                "nada — ou a raiz do repositório mudou, ou este teste está olhando " +
                "para o lugar errado, e aí o verde dele é vácuo.",
            fontes.size > 100,
        )

        val orfaos = Earcon.entries
            .filter { emissoesDe(it.name).isEmpty() }
            .map { it.name }

        assertEquals(
            "Earcon SEM CHAMADOR em `src/main`: $orfaos\n\n" +
                "Pela régua do CLAUDE.md §6 isso é *escrito*, não *construído* — o " +
                "som existe no sintetizador e nunca sai pelo alto-falante, e do lado " +
                "de fora parece capacidade pronta. Duas saídas honestas:\n" +
                "  1. ligue o earcon onde ele significa alguma coisa; ou\n" +
                "  2. tire-o do `enum` — vocabulário de dez sinais em 3 kHz não " +
                "     comporta entrada decorativa.\n\n" +
                "Arquivos que citam um earcon SEM tocá-lo (e por isso não contam): " +
                "$DESCREVEM_SEM_TOCAR",
            emptyList<String>(),
            orfaos,
        )
    }

    // -------------------------------------------------------------- contra-teste

    /**
     * **Prova que a varredura consegue não achar, e consegue achar.**
     *
     * Sem este par, `todoEarconTemChamadorAlcancavelEmProducao` passaria por
     * vacuidade em todo cenário em que a busca estivesse quebrada — raiz errada,
     * `filterNot` largo demais, símbolo mal formado. É o padrão do §6 pergunta 3:
     * *se o teste passaria com o defeito de volta, ele não testa o defeito*.
     *
     * O negativo usa um nome que **não existe** no `enum`: se a varredura o
     * "encontrar", ela está casando substring de qualquer coisa e o verde do teste
     * de cima não vale.
     */
    @Test
    fun aVarreduraDistingueEarconTocadoDeEarconInexistente() {
        assertTrue(
            "A varredura não achou quem toca `Earcon.FALHA`, que é emitido em vários " +
                "pontos do rádio e do copiloto. Se ela não vê ESTE, o zero de " +
                "qualquer outro não significa nada.",
            emissoesDe(Earcon.FALHA.name).isNotEmpty(),
        )
        assertEquals(
            "A varredura 'achou' um earcon que não existe — ela está casando " +
                "substring em vez de símbolo, e o teste de cima é vácuo.",
            emptyMap<String, List<String>>(),
            emissoesDe("ESTE_EARCON_NAO_EXISTE"),
        )
    }

    /**
     * **A gramática pedida em 22/08 está ligada — os três tempos, nos três lugares.**
     *
     * Não basta "tem algum chamador": o `DESPERTAR` só é marca sonora se soar na
     * palavra de ativação, e o par de canal só é convenção de rádio se soar na
     * abertura e no fechamento do canal de escuta. Um `DESPERTAR` tocado no lugar
     * do earcon de falha passaria no teste de cima e seria outro produto.
     */
    @Test
    fun aGramaticaDeTresTemposEstaLigadaNosLugaresCertos() {
        assertTrue(
            "O BOMMM tem de sair de `CopilotService` — é lá que a palavra de " +
                "ativação é detectada, e a meta `fim de \"Claryon\" → earcon ≤ 500 ms` " +
                "só existe se quem confirma a escuta for quem ouviu a palavra. " +
                "Achei: ${emissoesDe(Earcon.DESPERTAR.name).keys}",
            "CopilotService.kt" in emissoesDe(Earcon.DESPERTAR.name),
        )
        assertTrue(
            "O bipbip tem de sair de `VoiceCycle` — é o único objeto que sabe o " +
                "instante em que o microfone de fato abriu. Achei: " +
                "${emissoesDe(Earcon.CANAL_ABERTO.name).keys}",
            "VoiceCycle.kt" in emissoesDe(Earcon.CANAL_ABERTO.name),
        )
        assertTrue(
            "O trimtrim tem de sair de `VoiceCycle`, no fechamento do VAD — que é " +
                "literalmente o gatilho pedido, \"parou de falar\". Achei: " +
                "${emissoesDe(Earcon.CANAL_FECHADO.name).keys}",
            "VoiceCycle.kt" in emissoesDe(Earcon.CANAL_FECHADO.name),
        )
    }

    private fun raizDoRepositorio(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "Não achei a raiz do repositório a partir de ${File(".").canonicalPath}. " +
                "Teste que não acha o que precisa conferir tem de falhar, não passar.",
        )
    }

    private companion object {
        /** Ver o KDoc da classe: citar não é tocar. */
        val DESCREVEM_SEM_TOCAR = setOf("AudioSignals.kt", "EarconSynthesizer.kt", "SaidaUnica.kt")
    }
}
