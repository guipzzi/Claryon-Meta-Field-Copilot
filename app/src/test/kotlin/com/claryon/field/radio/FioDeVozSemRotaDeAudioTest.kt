package com.claryon.field.radio

import com.claryon.agent.FalhaOperacional
import com.claryon.field.agent.ClaryonIntentExecutor.TrocaDeGrupo
import com.claryon.net.GrupoFalado
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Sem rota de áudio, o fio até o rádio existe — e recusa com motivo.**
 *
 * ## O defeito que estes testes prendem
 *
 * `CanaisDoAgente.registrarRadio` tinha um único chamador em `src/main`, e ele
 * morava **dentro** do `viewModelScope.launch` de `RadioViewModel.abrir`, **depois**
 * do `return@launch` da falha de rota de áudio:
 *
 * ```kotlin
 * is Result.Failure -> { _estado.value = Indisponivel("Sem rota…"); return@launch }
 * // …110 linhas…
 * CanaisDoAgente.registrarRadio(trocar = …, transmitindoAgora = …, abrir = …)
 * ```
 *
 * Em qualquer aparelho sem HFP — óculos não pareados, fone ausente, emulador — o
 * registro nunca acontecia. `trocador` continuava `null` e `abridor` continuava
 * `{ false }`: *"Claryon, guarnição 3 na escuta"* e a troca de grupo por voz eram
 * recusadas **sem motivo**, com detector, whisper e roteador todos funcionando.
 *
 * ## Por que dois testes de natureza diferente
 *
 * O conserto tem duas metades, e uma metade sozinha reintroduz o defeito na outra:
 *
 *  1. **Atar o fio cedo.** Só isso faria "há trocador" virar verdade permanente, e
 *     a guarda "já estamos lá" devolveria `Trocado` com o rádio fora do ar. O
 *     executor então chamaria `abrirTransmissao`, o abridor devolveria `false` cru,
 *     e o agente ouviria *"Canal ocupado."* num canal que não está ocupado.
 *  2. **`noAr` como predicado.** Só isso, com o registro de volta atrás do
 *     `return@launch`, não muda nada: sem fio não há a quem perguntar.
 *
 * Então há um teste de **comportamento** (a política recusa com motivo, com o fio
 * atado e o rádio fora do ar) e um de **ordem no arquivo** (o registro está antes do
 * `return@launch`). O primeiro passaria com o registro de volta atrás do `return`;
 * o segundo passaria com o `noAr` removido. Juntos, não.
 *
 * §6 do `CLAUDE.md`, pergunta 3: *"Se o teste passaria com o defeito de volta, ele
 * não testa o defeito."* Contra-teste rodado em 22/08 — ver o relato no fim deste
 * arquivo.
 */
class FioDeVozSemRotaDeAudioTest {

    private val alfa = GrupoFalado(id = "id-alfa", rotuloFalado = "guarnicao tres", nome = "GTA-3 Alfa")
    private val bravo = GrupoFalado(id = "id-bravo", rotuloFalado = "guarnicao quatro", nome = "GTA-4 Bravo")

    /**
     * O fio ATADO, do jeito que `RadioViewModel` o ata antes da rota: os lambdas
     * existem e leem o `RadioTatico` a cada chamada. Sem rota, ele é nulo — e é isso
     * que `noAr` reporta.
     */
    private class FioAtado(private val radioNoAr: Boolean) {
        var trocasPedidas = mutableListOf<String>()
        var aberturasPedidas = 0

        val trocar: suspend (String) -> Boolean = { id ->
            trocasPedidas += id
            radioNoAr
        }
        val abrir: suspend () -> Boolean = {
            aberturasPedidas++
            radioNoAr
        }
        val noAr: () -> Boolean = { radioNoAr }
    }

    private fun politica(fio: FioAtado, corrente: String = "id-alfa") = PoliticaDeTrocaDeGrupo(
        resolvedor = ResolvedorDeGrupo { listOf(alfa, bravo) },
        transmitindo = { false },
        grupoCorrenteId = { corrente },
        trocador = { fio.trocar },
        noAr = fio.noAr,
    )

    // ── Comportamento: fio atado, rádio fora do ar ────────────────────────────

    /**
     * **Grupo DIFERENTE do corrente, sem rota:** recusa nomeada, e não `false` mudo.
     *
     * `RADIO_FECHADO` fala *"Abra o rádio primeiro."*. O que não pode acontecer é o
     * comando morrer sem resposta, que é o que acontecia quando ninguém tinha
     * registrado nada.
     */
    @Test
    fun semRotaDeAudio_trocarParaOutroGrupo_recusaComMotivo() = runTest {
        val fio = FioAtado(radioNoAr = false)
        val d = politica(fio).decidir("guarnicao quatro")

        assertEquals(
            "sem rádio no ar a recusa tem de ter nome — um `false` mudo vira " +
                "silêncio ou, pior, 'Canal ocupado.'",
            TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO),
            d.resultado,
        )
        assertEquals("o estado não pode avançar sem o rádio", null, d.novoGrupo)
    }

    /**
     * **O buraco que o atalho abria — e é este o caso de *"guarnição 3 na escuta"*.**
     *
     * O agente quase sempre pede para abrir no grupo em que já está. Aí a política
     * caía no atalho "já estamos lá" e devolvia `Trocado` sem olhar para o rádio; o
     * executor seguia para `abrirTransmissao`, que devolvia `false`, e a fala saía
     * como *"Canal ocupado."* — um canal que não está ocupado, num aparelho que nem
     * rota de áudio tem.
     *
     * Se este teste começar a falhar com `Trocado("GTA-3 Alfa")`, alguém devolveu a
     * guarda `noAr` para depois do atalho.
     */
    @Test
    fun semRotaDeAudio_abrirNoGrupoCorrente_recusaComMotivo_eNaoConfirma() = runTest {
        val fio = FioAtado(radioNoAr = false)
        val d = politica(fio, corrente = "id-alfa").decidir("guarnicao tres")

        assertEquals(
            "confirmar 'já estamos lá' com o rádio fora do ar entrega o comando ao " +
                "abridor, e lá só existe `false` — que é falado como 'Canal ocupado.'",
            TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO),
            d.resultado,
        )
        assertEquals(0, fio.aberturasPedidas)
    }

    /**
     * **Contra-teste do par:** com o rádio NO AR, o mesmo fio abre.
     *
     * Sem isto, as duas asserções acima seriam satisfeitas por uma política que
     * recusa sempre — e uma política que recusa sempre passa nos testes de recusa.
     */
    @Test
    fun comRadioNoAr_oMesmoFioTroca_eOAtalhoContinuaBarato() = runTest {
        val fio = FioAtado(radioNoAr = true)

        val outro = politica(fio).decidir("guarnicao quatro")
        assertEquals(TrocaDeGrupo.Trocado("GTA-4 Bravo"), outro.resultado)
        assertEquals("tocou no socket de verdade", listOf("id-bravo"), fio.trocasPedidas)

        val mesmo = politica(fio, corrente = "id-alfa").decidir("guarnicao tres")
        assertEquals(TrocaDeGrupo.Trocado("GTA-3 Alfa"), mesmo.resultado)
        assertEquals(
            "a guarda `noAr` não pode custar uma reconexão: derrubar receptor, " +
                "transporte e jitter por um comando redundante deixa o agente surdo",
            listOf("id-bravo"),
            fio.trocasPedidas,
        )
    }

    /**
     * **O dono de processo esquece o fio, e o esquecimento é honesto.**
     *
     * `onCleared` chama `esquecerRadio()`. Se `noAr` ficasse com o predicado antigo,
     * um ViewModel morto continuaria respondendo "estou no ar".
     */
    @Test
    fun esquecerRadio_derrubaNoAr_eOAbridorVoltaARecusar() = runTest {
        CanaisDoAgente.registrarRadio(
            trocar = { true },
            transmitindoAgora = { true },
            noAr = { true },
            abrir = { true },
        )
        assertTrue("o fio atado abre", CanaisDoAgente.abrirTransmissao())

        CanaisDoAgente.esquecerRadio()
        assertFalse(
            "depois do `onCleared` o abridor não pode dizer que pôs o agente no ar",
            CanaisDoAgente.abrirTransmissao(),
        )
        CanaisDoAgente.limpar()
    }

    // ── Ordem no arquivo: o registro está ANTES do `return@launch` ────────────

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

    private fun fonteDoViewModel(): String {
        val f = raizDoRepositorio()
            .resolve("app/src/main/kotlin/com/claryon/field/radio/RadioViewModel.kt")
        assertTrue(
            "Não achei $f. Varredura que não varre não é prova — se o arquivo " +
                "mudou de lugar, este teste tem de falhar, não passar vazio.",
            f.isFile,
        )
        return f.readText()
    }

    /**
     * **A trava de ordem.** `registrarRadio` tem de aparecer antes do primeiro
     * `return@launch` de `abrir`.
     *
     * ## Por que uma varredura de fonte, e não um teste de runtime
     *
     * `RadioViewModel` é `AndroidViewModel`: construí-lo pede `Application`,
     * `AudioDoAgente`, `SaidaUnica` e `BuildConfig`, e este módulo não tem
     * Robolectric (ver `app/build.gradle.kts`, `isReturnDefaultValues`). O que se
     * quer provar é **posição de uma chamada dentro de um `launch`**, e isso é
     * exatamente o que uma varredura consegue provar sem mentir sobre o que mediu.
     *
     * Mesma forma — e mesmo filtro de comentário — de
     * `FachadaDoDatTemDonoUnicoTest`: **falar sobre** o símbolo não é **chamá-lo**,
     * senão o teste reprovaria a própria documentação da regra e ensinaria a
     * apagá-la. É por isso que o KDoc acima pode citar `registrarRadio` à vontade.
     */
    @Test
    fun oRegistroDoFioVemAntesDoRetornoDaFalhaDeRota() {
        val fonte = semComentarios(fonteDoViewModel())

        val registro = fonte.indexOf("CanaisDoAgente.registrarRadio(")
        assertTrue(
            "Ninguém chama `CanaisDoAgente.registrarRadio(` em RadioViewModel.kt. " +
                "Sem chamador, o fio entre o roteador de voz e o rádio não existe " +
                "em runtime — CLAUDE.md §6, pergunta 2.",
            registro >= 0,
        )

        val retorno = fonte.indexOf("return@launch")
        assertTrue(
            "Não achei `return@launch` em RadioViewModel.kt. Ou a falha de rota " +
                "deixou de retornar cedo, ou a varredura cegou; nos dois casos esta " +
                "trava precisa de olhos humanos antes de continuar verde.",
            retorno >= 0,
        )

        assertTrue(
            "`CanaisDoAgente.registrarRadio(` está DEPOIS do `return@launch` da " +
                "falha de rota de áudio (registro em $registro, retorno em " +
                "$retorno).\n\n" +
                "Este é o defeito de 22/08 de volta: em todo aparelho sem HFP — " +
                "óculos não pareados, fone ausente, emulador — o registro não roda, " +
                "`CanaisDoAgente` fica com `trocador == null` e " +
                "`abridor == { false }`, e \"Claryon, guarnição 3 na escuta\" é " +
                "recusado sem motivo com detector, whisper e roteador funcionando.\n\n" +
                "O fio não depende de rota: são lambdas que rodam no celular. Quem " +
                "depende de rota é o rádio funcionar, e quem responde isso é o " +
                "predicado `noAr`.",
            registro < retorno,
        )

        // **O léxico é a outra metade do motivo.** Com `lexico == null`, o
        // resolvedor devolve `SemLexico` e curto-circuita a guarda `noAr`: a recusa
        // sai como "Sem lista. Entre de novo.", que manda o agente refazer o login
        // por um problema de fone. Só há motivo verdadeiro se a lista tiver sido
        // carregada também no caminho da falha de rota.
        val lexico = fonte.indexOf("CanaisDoAgente.carregar(")
        assertTrue(
            "Ninguém chama `CanaisDoAgente.carregar(` antes do `return@launch` da " +
                "falha de rota (carga em $lexico, retorno em $retorno). Sem a lista, " +
                "\"guarnição 3 na escuta\" é recusado por SEM_LEXICO_DE_CANAIS — " +
                "\"Sem lista. Entre de novo.\" — e o agente refaz o login por um " +
                "problema de fone. A carga não depende de HFP.",
            lexico in 0 until retorno,
        )
    }

    /**
     * **O registro precisa declarar `noAr`, e o predicado tem de ler o campo.**
     *
     * Atar o fio cedo capturando um `RadioTatico` que ainda não existe, ou passar
     * `noAr = { true }` fixo, troca a recusa muda por uma recusa MENTIROSA — o
     * atalho "já estamos lá" confirma, o executor chama o abridor e a fala vira
     * "Canal ocupado." num aparelho sem rota de áudio.
     */
    @Test
    fun oRegistroDeclaraNoAr_lendoOCampoDoRadio() {
        val fonte = semComentarios(fonteDoViewModel())
        val registro = fonte.indexOf("CanaisDoAgente.registrarRadio(")
        assertTrue(registro >= 0)
        val chamada = fonte.substring(registro, minOf(fonte.length, registro + 1_200))

        assertTrue(
            "A chamada de `registrarRadio` não passa `noAr`. Sem ele, \"fio atado\" " +
                "e \"rádio pronto\" voltam a ser a mesma coisa, e a recusa sai como " +
                "\"Canal ocupado.\" — ver PoliticaDeTrocaDeGrupo, guarda 3.",
            chamada.contains("noAr"),
        )
        assertTrue(
            "`noAr` não lê o campo `radio`. Capturar um valor no registro o " +
                "congelaria em `null`, porque o `RadioTatico` nasce depois — e o " +
                "fio ficaria permanentemente recusando mesmo com os óculos ligados.",
            Regex("""noAr\s*=\s*\{[^}]*\bradio\b""").containsMatchIn(chamada),
        )
    }

    /**
     * **Contra-teste da varredura: ela ainda pega o defeito de volta.**
     *
     * Sem isto, `semComentarios` ou os `indexOf` poderiam ter sido afrouxados até
     * cegar, e a trava ficaria verde para sempre aprovando qualquer coisa. As duas
     * asserções prendem o filtro pelos dois lados.
     */
    @Test
    fun aVarreduraAINDAPegaORegistroAtrasDoRetorno() {
        val defeituoso = """
            fun abrir() {
                viewModelScope.launch {
                    val r = when (val res = audio.iniciar()) {
                        is Result.Success -> res.value
                        is Result.Failure -> { return@launch }
                    }
                    CanaisDoAgente.registrarRadio(trocar = { true }, noAr = { radio != null })
                }
            }
        """.trimIndent()

        val consertado = """
            fun abrir() {
                CanaisDoAgente.registrarRadio(trocar = { true }, noAr = { radio != null })
                viewModelScope.launch {
                    val r = when (val res = audio.iniciar()) {
                        is Result.Success -> res.value
                        is Result.Failure -> { return@launch }
                    }
                }
            }
        """.trimIndent()

        fun antesDoRetorno(fonte: String): Boolean {
            val t = semComentarios(fonte)
            return t.indexOf("CanaisDoAgente.registrarRadio(") < t.indexOf("return@launch")
        }

        assertFalse(
            "A varredura aprovou um `abrir` com o registro atrás do `return@launch`. " +
                "Enquanto isto for verdade, a trava não guarda nada.",
            antesDoRetorno(defeituoso),
        )
        assertTrue(
            "A varredura reprovou o código consertado — ela passou a punir a forma " +
                "correta, e o próximo passo é alguém afrouxá-la até não valer nada.",
            antesDoRetorno(consertado),
        )
    }

    /** Tira `/* */`, KDoc e `//`. Mesmo filtro — e mesmo limite — do teste irmão. */
    private fun semComentarios(fonte: String): String =
        fonte.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
