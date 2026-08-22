package com.claryon.field.ui.telas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Acromático é a base; cor é sinal.** O teste que impede a regra de degradar.
 *
 * Diff de spec de 22/08, decisão do dono — a regra está escrita por extenso na
 * decisão 2 do KDoc de `Cores`. Este arquivo é o que a torna **verificável**, e a
 * razão de ele existir é a metade da história que a regra sozinha não cobre:
 *
 * > Uma regra de paleta escrita em prosa degrada em seis meses, uma linha por vez,
 * > e cada linha parece justificada sozinha. Foi exatamente assim que 45 usos
 * > cromáticos apareceram em 10 arquivos sob a regra anterior — que também estava
 * > escrita, e também era boa.
 *
 * ---
 * ### Por que varre o FONTE, e não os tokens
 *
 * `OrcamentoDeCorTest` mede contraste entre tokens, e é a ferramenta certa para a
 * pergunta dele. Ela não consegue responder a esta: *"quantos elementos desta tela
 * saem coloridos?"* — porque isso não é propriedade de um token, é propriedade do
 * **uso**. O projeto já pagou por essa distinção uma vez, em `rotuloDoBotao`: o
 * teste de contraste passava com a regressão reintroduzida, porque afirmava coisas
 * sobre tokens enquanto o defeito estava na escolha.
 *
 * A varredura é textual de propósito. Um `Cores.Falha` novo numa tela é uma linha
 * de código que alguém escreve; contar linhas é a medida direta do que se quer
 * limitar, e não depende de rodar a interface — que este projeto não consegue fazer,
 * porque não declara `ui-test-junit4`.
 */
class OrcamentoCromaticoTest {

    /**
     * Os tokens que carregam matiz. `Traco`, `Tinta*`, `Vazio`, `Painel`,
     * `Elevado` e `Pressionado` têm croma zero e não entram na conta — é a
     * decisão 1 da paleta, e ela é verificável com `grep`.
     */
    private val cromaticos = listOf("NoAr", "NoArFraco", "P1", "P2", "Falha", "FalhaTexto")

    private fun raizDoRepositorio(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Não achei a raiz do repositório a partir de ${File(".").canonicalPath}",
        )
    }

    private fun fontesDeInterface(): List<File> {
        val ui = File(raizDoRepositorio(), "app/src/main/kotlin/com/claryon/field/ui")
        assertTrue("diretório de UI não encontrado: $ui", ui.isDirectory)
        return ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // `Cores.kt` é a definição, não o uso: ele cita cada token uma vez por
            // declaração e uma dúzia de vezes em KDoc.
            .filter { it.name != "Cores.kt" }
            .toList()
    }

    /**
     * Conta usos REAIS, não menções.
     *
     * Descarta linha de KDoc (` * `) e de comentário (`//`), porque este projeto
     * documenta densamente e a prosa cita tokens o tempo todo — inclusive para
     * explicar por que NÃO os usa. Contar prosa faria o teste punir exatamente a
     * documentação que sustenta a regra.
     */
    private fun usosCromaticos(arquivo: File): List<String> =
        arquivo.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .flatMap { linha ->
                cromaticos.filter { token ->
                    Regex("""Cores\.$token\b""").containsMatchIn(linha)
                }
            }

    /**
     * **O orçamento, por arquivo.**
     *
     * Não é um número redondo escolhido por gosto: é o estado de 22/08 depois da
     * varredura, com uma folga de zero. Cada entrada é um sinal nomeado, e o teste
     * falha tanto para MAIS quanto para MENOS — mais é a regra degradando, menos é
     * um sinal que alguém apagou sem querer.
     *
     * `Casco.kt` tem 4 e `BarraDePtt.kt` tem 4 pelo mesmo motivo, e é o único caso
     * em que um número acima de 1 não é violação: **os quatro são o MESMO sinal.**
     * A moldura do "no ar" tem quatro lados e a barra tem rótulo, cronômetro,
     * rastro e onda — é a "forma exclusiva" que a decisão 3 da paleta descreve como
     * o que protege o âmbar, e ela é feita de partes por construção.
     */
    private val orcamento = mapOf(
        // Os quatro lados da moldura de tela cheia do "no ar".
        "Casco.kt" to 4,
        // Rótulo "NO AR", cronômetro, rastro da onda e envelope ao vivo.
        "componentes/BarraDePtt.kt" to 4,
        // A calha de emergência, e só ela.
        "componentes/Comuns.kt" to 1,
        // `corDaPrioridade` e `corDaCalha`, as duas para P1; a escuta recusada.
        "telas/TelaDeGuarnicao.kt" to 3,
        // Aviso que bloqueia ("sem posição própria") + "fora do mapa".
        "telas/TelaDoMapa.kt" to 2,
        // Servidor não configurado + erro de login.
        "telas/TelaDeLogin.kt" to 2,
        // A escuta recusada.
        "telas/TelaDoGrupo.kt" to 1,
        // O VALOR da linha negada — e só ele; ícone e explicação são tinta.
        "telas/TelaDePermissoes.kt" to 1,
        // `error` do esquema Material.
        "tema/Tema.kt" to 1,
    )

    @Test
    fun nenhumArquivoDeInterfaceGastaMaisCorDoQueOOrcamento() {
        val raiz = File(raizDoRepositorio(), "app/src/main/kotlin/com/claryon/field/ui")
        val excedentes = fontesDeInterface().mapNotNull { arquivo ->
            val chave = arquivo.relativeTo(raiz).path
            val usos = usosCromaticos(arquivo)
            val teto = orcamento[chave] ?: 0
            if (usos.size > teto) "$chave: ${usos.size} (teto $teto) → ${usos.distinct()}" else null
        }
        assertTrue(
            "Arquivos acima do orçamento cromático.\n" +
                "Cada cor nova precisa responder à pergunta da decisão 2 de `Cores`: " +
                "isto é SINAL, ou é estrutura/estado sendo decorado?\n" +
                "Se for sinal de verdade, suba o teto NESTE arquivo e escreva por quê.\n" +
                excedentes.joinToString("\n"),
            excedentes.isEmpty(),
        )
    }

    /**
     * O outro lado, e é o que impede o teste de virar decoração.
     *
     * Um teto sozinho passa quando alguém apaga a cor toda — e apagar o vermelho do
     * P1 ou o âmbar do "no ar" é uma regressão de segurança, não uma simplificação.
     * Exigir o número EXATO faz o teste falar das duas direções.
     */
    @Test
    fun osSinaisQueDevemTerCor_continuamTendo() {
        val raiz = File(raizDoRepositorio(), "app/src/main/kotlin/com/claryon/field/ui")
        val medido = fontesDeInterface()
            .associate { it.relativeTo(raiz).path to usosCromaticos(it).size }
            .filterValues { it > 0 }
        assertEquals(
            "O mapa de sinais cromáticos mudou. Some é regressão de sinal; " +
                "sobra é a regra degradando. Os dois casos passam por decisão escrita.",
            orcamento,
            medido,
        )
    }

    /**
     * **O âmbar não aparece fora do "no ar".**
     *
     * A regra mais antiga da paleta, e a única cujo custo é uma transmissão
     * acidental difundida para a guarnição inteira. Vale a pena travá-la sozinha,
     * porque ela é mais estreita que o orçamento: um `Cores.NoAr` em `TelaDoMapa`
     * caberia no teto de 2 daquele arquivo e ainda assim seria a violação grave.
     */
    @Test
    fun oAmbarSoAparece_ondeOAgenteEstaNoAr() {
        val raiz = File(raizDoRepositorio(), "app/src/main/kotlin/com/claryon/field/ui")
        val permitidos = setOf("Casco.kt", "componentes/BarraDePtt.kt")
        val intrusos = fontesDeInterface().filter { arquivo ->
            arquivo.relativeTo(raiz).path !in permitidos &&
                usosCromaticos(arquivo).any { it == "NoAr" || it == "NoArFraco" }
        }
        assertTrue(
            "Âmbar fora da moldura e da barra do PTT: ${intrusos.map { it.name }}. " +
                "O âmbar significa UMA coisa — você está no ar.",
            intrusos.isEmpty(),
        )
    }
}
