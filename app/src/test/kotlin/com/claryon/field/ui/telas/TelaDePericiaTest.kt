package com.claryon.field.ui.telas

import com.claryon.evidence.Integridade
import com.claryon.evidence.RecordingHandle
import com.claryon.evidence.RegistroDeCustodia
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A tela de perícia diz a verdade sobre a prova — inclusive sobre si mesma.**
 *
 * Três coisas são testadas aqui, e as três já falharam neste projeto de alguma
 * forma:
 *
 *  1. **Todo veredito chega ao agente**, com nome e explicação próprios. Um
 *     `when` que caísse num "indisponível" genérico repetiria o defeito das oito
 *     causas de falha de câmera, que viravam a mesma frase.
 *  2. **A conferência tem chamador em `src/main`.** É a régua do `CLAUDE.md §6`,
 *     pergunta 2, e é literalmente o defeito que esta tela veio consertar:
 *     `verificar()` e `Manifesto.ler()` tinham zero.
 *  3. **A ressalva da âncora não some.** `Confere` não é *inviolável*, e a tela e o
 *     `RELATORIO_DE_IMPACTO_LGPD.md` R8 têm de continuar dizendo isso **os dois**.
 */
class TelaDePericiaTest {

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

    /** Um representante de **cada** veredito que o cofre sabe emitir. */
    private val todosOsVereditos: List<Integridade> = buildList {
        add(Integridade.Integra)
        add(Integridade.ExpurgadaPorPolitica(listOf(0, 1)))
        add(Integridade.Quebrada(2))
        add(Integridade.SegmentoNaoRegistrado(3))
        add(Integridade.Truncada(seladosNoFim = 5, presentesNoManifesto = 3))
        for (m in Integridade.SemAncoraDeFim.Motivo.entries) add(Integridade.SemAncoraDeFim(m))
    }

    /**
     * **A lista acima cobre a hierarquia inteira** — conferido no bytecode, não de
     * memória.
     *
     * Sem isto, um veredito novo em `Integridade` entraria no produto com o `when`
     * exaustivo do compilador satisfeito e **sem teste nenhum** sobre o texto que o
     * agente lê. O `when` garante que existe uma frase; só esta asserção garante que
     * alguém olhou para ela.
     */
    @Test
    fun aVarreduraCobreTodosOsVereditosQueOTipoPermite() {
        val permitidos = Integridade::class.java.permittedSubclasses
        assertTrue(
            "`Integridade` deixou de ser selada no bytecode (`permittedSubclasses` " +
                "veio nulo ou vazio). Sem isso esta varredura não consegue provar " +
                "que cobre tudo, e passar assim seria pior que falhar.",
            permitidos != null && permitidos.isNotEmpty(),
        )
        val esperados = permitidos!!.map { it.simpleName }.toSortedSet()
        val cobertos = todosOsVereditos.map { it.javaClass.simpleName }.toSortedSet()
        assertEquals(
            "Veredito de `Integridade` sem representante nesta lista — o texto que " +
                "o agente leria não passou por teste nenhum.",
            esperados,
            cobertos,
        )
    }

    @Test
    fun cadaVeredito_temNomeEExplicacaoProprios() {
        val rotulos = todosOsVereditos.map { Veredito.rotulo(it) }
        val explicacoes = todosOsVereditos.map { Veredito.explicacao(it) }

        assertTrue("rótulo vazio: $rotulos", rotulos.none { it.isBlank() })
        assertTrue("explicação vazia", explicacoes.none { it.isBlank() })
        assertEquals(
            "Dois vereditos com o MESMO rótulo. Quem periciar não consegue " +
                "distinguir o que aconteceu com a prova: $rotulos",
            rotulos.size,
            rotulos.toSet().size,
        )
        assertEquals(
            "Dois vereditos com a MESMA explicação: as causas são diferentes e a " +
                "conduta também.",
            explicacoes.size,
            explicacoes.toSet().size,
        )
    }

    /**
     * **O número está na frase, e não só no tipo.**
     *
     * `Truncada(5, 3)` existe precisamente para responder *"foram selados 5, o
     * manifesto apresenta 3"* em vez do genérico "MAC não confere". Uma tela que
     * jogasse fora os dois números desfaria isso sem que nada quebrasse.
     */
    @Test
    fun osVereditosQueCarregamNumero_dizemONumero() {
        assertTrue(
            Veredito.explicacao(Integridade.Truncada(5, 3)).let { "5" in it && "3" in it },
        )
        assertTrue("42" in Veredito.explicacao(Integridade.Quebrada(42)))
        assertTrue("42" in Veredito.explicacao(Integridade.SegmentoNaoRegistrado(42)))
        assertTrue(
            "2" in Veredito.explicacao(Integridade.ExpurgadaPorPolitica(listOf(7, 8))),
        )
    }

    /**
     * A gravação em curso **não** pode ser lida como custódia interrompida.
     *
     * Os dois casos produzem `SemAncoraDeFim(NAO_FINALIZADA)`, e a diferença entre
     * "estou gravando agora" e "o processo morreu antes de fechar" é a diferença
     * entre normalidade e incidente.
     */
    @Test
    fun gravacaoEmCurso_naoPareceCustodiaInterrompida() {
        val v = Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA)
        val emCurso = Veredito.rotulo(v, emAndamento = true)
        val morta = Veredito.rotulo(v, emAndamento = false)
        assertTrue("emCurso=$emCurso morta=$morta", emCurso != morta)
        assertTrue(
            "A explicação da gravação em curso não diz que ela está aberta.",
            "aberta" in Veredito.explicacao(v, emAndamento = true),
        )
    }

    @Test
    fun aGravidadeSeparaOQueAcusaDoQueNaoAcusa() {
        assertEquals(Veredito.Gravidade.CONFERE, Veredito.gravidade(Integridade.Integra))

        // Adulteração e formato rebaixado: os três exigem leitura.
        for (grave in listOf(
            Integridade.Quebrada(0),
            Integridade.Truncada(5, 3),
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.AUSENTE),
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR),
        )) {
            assertEquals("$grave deveria ser GRAVE", Veredito.Gravidade.GRAVE, Veredito.gravidade(grave))
        }

        // Nenhum destes acusa ninguém: expurgo é decisão registrada, segmento sem
        // linha é queda entre dois passos, e Keystore mudo é uma conferência que
        // não pôde ser feita.
        for (ressalva in listOf(
            Integridade.ExpurgadaPorPolitica(listOf(1)),
            Integridade.SegmentoNaoRegistrado(4),
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL),
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA),
        )) {
            assertEquals(
                "$ressalva deveria ser RESSALVA",
                Veredito.Gravidade.RESSALVA,
                Veredito.gravidade(ressalva),
            )
        }
    }

    @Test
    fun inicioNaoRegistrado_naoViraUmaDataPlausivel() {
        // Manifesto v1 não gravava o início. `0` como epoch sairia 01/01/1970 —
        // data plausível e falsa numa tela de perícia é pior que a lacuna admitida.
        val r = RegistroDeCustodia(
            handle = RecordingHandle("GTA-3_007_0"),
            versao = 1,
            inicioEpochMillis = 0L,
            fimEpochMillis = null,
            motivoDoFim = null,
            segmentos = 3,
            purgados = 0,
            bytesRetidos = 1_024,
            emAndamento = false,
            veredito = Integridade.SemAncoraDeFim(
                Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR,
            ),
        )
        val texto = descricaoDoRegistro(r, ZoneId.of("America/Sao_Paulo"))
        assertTrue("não admitiu a lacuna: $texto", "não registrado" in texto)
        assertFalse("inventou uma data de 1970: $texto", "1970" in texto)
        assertTrue("sem fim registrado" in texto)
        assertTrue("manifesto v1" in texto)
    }

    @Test
    fun tamanhoSaiEmUnidadeLegivel() {
        assertEquals("0 B", tamanho(0))
        assertEquals("512 B", tamanho(512))
        assertEquals("2 kiB", tamanho(2_048))
        assertEquals("1,9 MiB", tamanho(1_940_000).replace('.', ','))
    }

    // ── Construído, não escrito ───────────────────────────────────────────────

    private fun semComentarios(fonte: String): String =
        fonte.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * **A pergunta 2 do §6, virada teste.**
     *
     * *"`grep` do símbolo: quem chama isto em `src/main`? Zero chamador = escrito,
     * não construído."* `verificar()` e `Manifesto.ler()` passaram semanas com zero,
     * e a única razão de alguém ter percebido foi uma auditoria. Isto percebe
     * sozinho.
     */
    @Test
    fun aConferenciaDaCustodiaTemChamadorAlcancavelEmProducao() {
        val raiz = raizDoRepositorio()

        val cofre = raiz.resolve(
            "core-evidence/src/main/kotlin/com/claryon/evidence/EncryptedEvidenceVault.kt",
        )
        assertTrue("$cofre não existe", cofre.isFile)
        val fonteDoCofre = semComentarios(cofre.readText())
        assertTrue(
            "`periciar()` não chama mais `verificar(`. Sem isso a conferência volta " +
                "a existir só nos testes, e periciar volta a exigir `adb` sobre o " +
                "diretório privado.",
            Regex("""verificar\(lido\.handle\)""").containsMatchIn(fonteDoCofre),
        )
        assertTrue(
            "`periciar()` não lê mais o manifesto do disco.",
            Regex("""Manifesto\.ler\(""").containsMatchIn(fonteDoCofre),
        )

        // E `periciar` precisa ser alcançável a partir da interface — construir a
        // função sem tomada é o mesmo defeito com um degrau a mais.
        val app = raiz.resolve("app/src/main/kotlin")
        val chamadores = app.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\.periciar\(\)""").containsMatchIn(semComentarios(it.readText())) }
            .map { it.name }
            .toList()
        assertEquals(
            "`EncryptedEvidenceVault.periciar()` precisa de exatamente um chamador " +
                "em `app/src/main` — o ViewModel da perícia. Zero é capacidade " +
                "escrita e não construída; mais de um é conferência disparada de " +
                "dois lugares sobre o mesmo cofre.",
            listOf("PericiaViewModel.kt"),
            chamadores,
        )

        // E a tela precisa ser alcançável: um ViewModel sem tela é o mesmo buraco.
        val telas = app.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "TelaDePericia.kt" }
            .filter { Regex("""TelaDePericia\s*\(""").containsMatchIn(semComentarios(it.readText())) }
            .map { it.name }
            .toList()
        assertEquals(
            "`TelaDePericia` precisa ser composta por alguém. Zero chamador = " +
                "tela escrita e nunca aberta.",
            listOf("MainActivity.kt"),
            telas,
        )
    }

    /**
     * **A ressalva do R8 vive nos DOIS lugares, e é a mesma.**
     *
     * `Confere` não é *inviolável*: a chave da âncora vive no Keystore do aparelho e
     * é usável **pelo próprio app**. Isso está em `docs/RELATORIO_DE_IMPACTO_LGPD.md`
     * R8, e a tela não pode contradizê-lo nem calá-lo — uma tela de perícia é o
     * lugar onde a omissão custa mais caro.
     *
     * O teste é por conceito, não por frase: reescrever o texto é livre, apagar a
     * limitação não.
     */
    @Test
    fun aTelaEOrelatorioDizemAMesmaCoisaSobreOQueAAncoraNaoFecha() {
        val raiz = raizDoRepositorio()
        val tela = raiz.resolve(
            "app/src/main/kotlin/com/claryon/field/ui/telas/TelaDePericia.kt",
        )
        val relatorio = raiz.resolve("docs/RELATORIO_DE_IMPACTO_LGPD.md")
        assertTrue("$tela não existe", tela.isFile)
        assertTrue("$relatorio não existe", relatorio.isFile)

        // **Sem comentários, e é a metade que dá sentido ao teste.** O que precisa
        // estar na tela é o que o AGENTE lê — literal de string —, não a prosa que
        // explica a regra. Com o KDoc dentro, um arquivo que só *falasse* sobre a
        // ressalva passaria, e o arquivo que a documenta e não a exibe é justamente
        // o defeito.
        val textoDaTela = semComentarios(tela.readText())
        val textoDoRelatorio = relatorio.readText()

        // Os três fatos que sustentam "não é inforjável". Se um sumir da tela, o
        // agente lê "confere" como garantia absoluta.
        val obrigatorios = mapOf(
            "inforjável" to "a palavra que nega a leitura de garantia absoluta",
            "Keystore" to "onde a chave vive, e por que ela não é externa",
            "âncora externa" to "o que faltaria para fechar de verdade",
        )
        for ((termo, porque) in obrigatorios) {
            assertTrue(
                "A tela de perícia não fala mais em \"$termo\" ($porque). " +
                    "Reescrever o texto é livre; apagar a limitação do R8 não é.",
                termo in textoDaTela,
            )
            assertTrue(
                "O R8 do relatório de impacto não fala mais em \"$termo\" — a tela e " +
                    "o relatório deixaram de dizer a mesma coisa.",
                termo in textoDoRelatorio,
            )
        }

        assertFalse(
            "A tela de perícia chamou a custódia de \"inviolável\". Ela não é: " +
                "quem executa como o app sela âncora válida para qualquer cadeia.",
            "inviolável" in textoDaTela.lowercase(),
        )
        assertTrue(
            "A tela precisa dizer que NÃO exporta: sem isso, a ausência de " +
                "exportação passa por capacidade e a corregedoria descobre tarde.",
            "adb" in textoDaTela,
        )
    }
}
