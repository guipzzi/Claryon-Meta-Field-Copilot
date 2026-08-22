package com.claryon.field.voice

import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A escuta da palavra de ativação.**
 *
 * O detector real é nativo (ONNX pela `.so` do whisper) e não sobe em JVM. O que se
 * testa aqui é tudo o que **não** é o modelo: quando a escuta existe, quando ela
 * fecha a boca, e se o anel é reiniciado nas bordas da mudez. Essa é a parte que
 * pode estar errada sem nenhum sintoma no aparelho — um detector que ouve o próprio
 * earcon dispara em cascata, e um que emenda áudio pelas bordas da mudez avalia uma
 * janela que nunca existiu no mundo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EscutaDeAtivacaoTest {

    /** Espião no lugar do detector nativo. */
    private class DetectorFalso(
        private val aceitaNoQuadro: Set<Int> = emptySet(),
    ) : OuvidoDeAtivacao {
        var quadrosVistos = 0
        var reinicios = 0
        var fechado = false
        override val ultimoEscore = 0.9f
        override fun aceitar(pcm: ShortArray): Boolean = aceitaNoQuadro.contains(quadrosVistos++)
        override fun reiniciar() { reinicios++ }
        override fun close() { fechado = true }
        fun comoDetector(): OuvidoDeAtivacao = this
    }

    private fun quadros(n: Int): Flow<ShortArray> = flow {
        repeat(n) {
            emit(ShortArray(320))
            yield()
        }
    }

    /**
     * A escuta se pendura em `PowerPolicy.hfpAberto` — a MESMA regra que decide se o
     * serviço em primeiro plano pede o tipo `MICROPHONE`. Se as duas divergirem, o
     * app abre microfone sem o tipo declarado e o Android 14+ derruba o processo.
     */
    @Test
    fun aRegraDaEscuta_eAMesmaDoTipoDoServico_emTodoModo() {
        for (modo in ModoOperacao.entries) {
            val perfil = PowerPolicy.perfil(modo)
            val pedeMicrofone = PowerPolicy.tiposDeServico(modo)
                .contains(com.claryon.agent.TipoServico.MICROPHONE)
            assertEquals(
                "$modo: `hfpAberto` e o tipo MICROPHONE discordam — a escuta abriria " +
                    "microfone sem o tipo declarado",
                perfil.hfpAberto,
                pedeMicrofone,
            )
        }
    }

    @Test
    fun emStandby_aEscutaNaoSobe() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        var abriu = false
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { abriu = true; quadros(10) },
            fecharMicrofone = {},
            criarDetector = { null },
            aoDetectar = {},
        )
        escuta.ajustarPara(ModoOperacao.STANDBY)
        escopo.advanceUntilIdle()

        assertFalse("Standby não abre HFP — a escuta não pode pedir microfone", abriu)
        assertFalse(escuta.escutando)
    }

    @Test
    fun emAtivo_aEscutaSobeEConsomeOsQuadros() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso()
        var fechou = false
        val escuta = escutaCom(escopo, detector, quadros(25), { fechou = true })

        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertEquals("todos os quadros deviam chegar ao detector", 25, detector.quadrosVistos)
        assertTrue("o detector nativo tem de ser fechado — é um ponteiro", detector.fechado)
        assertTrue("a rota tem de ser devolvida", fechou)
    }

    /**
     * **O eco.** O earcon e o TTS saem pelo mesmo fone que o microfone escuta, a
     * centímetros de distância. Sem este filtro, o `DESPERTAR` que a detecção dispara
     * volta pelo microfone e alimenta o detector — cascata.
     */
    @Test
    fun enquantoOCopilotoFala_nenhumQuadroChegaAoDetector() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso()
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { quadros(20) },
            fecharMicrofone = {},
            criarDetector = { detector.comoDetector() },
            aoDetectar = {},
            suprimido = { true }, // o copiloto está falando o tempo todo
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertEquals("quadro nenhum pode passar durante a saída própria", 0, detector.quadrosVistos)
    }

    /** Enquanto o agente segura o PTT ele fala com pessoas, não com o copiloto. */
    @Test
    fun enquantoOAgenteTransmite_nenhumQuadroChegaAoDetector() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso()
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { quadros(20) },
            fecharMicrofone = {},
            criarDetector = { detector.comoDetector() },
            aoDetectar = {},
            ocupadoNoRadio = { true },
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertEquals(0, detector.quadrosVistos)
    }

    /**
     * **O contra-teste da mudez**, e é ele que prova que o filtro não é um `if` que
     * nunca deixa nada passar: com a mesma escuta e a mesma quantidade de quadros,
     * mudando SÓ o supressor, a contagem tem de mudar.
     */
    @Test
    fun aMudezEDoSupressor_naoDaEscuta() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val calado = DetectorFalso()
        escutaCom(escopo, calado, quadros(20), suprimido = { true })
            .also { it.ajustarPara(ModoOperacao.ATIVO) }
        escopo.advanceUntilIdle()

        val solto = DetectorFalso()
        escutaCom(escopo, solto, quadros(20), suprimido = { false })
            .also { it.ajustarPara(ModoOperacao.ATIVO) }
        escopo.advanceUntilIdle()

        assertEquals(0, calado.quadrosVistos)
        assertEquals(20, solto.quadrosVistos)
    }

    /**
     * **A borda da mudez.** O anel do detector guarda 1 s contínuo. Voltando a
     * escutar sem reiniciar, ele emendaria o instante anterior à mudez com o
     * posterior e avaliaria uma janela que **nunca existiu no mundo** — falso
     * positivo por construção.
     *
     * Exige as duas bordas: uma ao calar (para não carregar o que veio antes) e uma
     * ao voltar. E exige que sejam DUAS, não vinte: reiniciar a cada quadro calado
     * seria trabalho à toa e esconderia um filtro que não sabe onde está a borda.
     */
    @Test
    fun oAnelEReiniciadoNasDuasBordasDaMudez_eSoNelas() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso()
        var quadro = 0
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { quadros(30) },
            fecharMicrofone = {},
            criarDetector = { detector.comoDetector() },
            aoDetectar = {},
            // Calado do 10º ao 19º: uma borda de entrada e uma de saída.
            suprimido = { (quadro++) in 10..19 },
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertEquals("20 quadros deviam passar (30 menos os 10 calados)", 20, detector.quadrosVistos)
        assertEquals("duas bordas, dois reinícios — nem um por quadro, nem nenhum", 2, detector.reinicios)
    }

    /**
     * Depois de detectar, o copiloto vai ouvir o agente e depois falar. Sem esta
     * janela ele ouviria o próprio ciclo — e o supressor sozinho não basta, porque
     * ele cobre o que SAI por este processo, não o comando que ainda vai ser dito.
     */
    @Test
    fun depoisDeDetectar_aEscutaCalaPelaJanelaPedida() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso(aceitaNoQuadro = setOf(2))
        var relogio = 0L
        var deteccoes = 0
        lateinit var escuta: EscutaDeAtivacao
        escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { quadros(30) },
            fecharMicrofone = {},
            criarDetector = { detector.comoDetector() },
            aoDetectar = { deteccoes++; escuta.silenciarPor(10_000) },
            // Cada quadro é 20 ms; 30 quadros são 600 ms, dentro da janela de 10 s.
            agoraMs = { relogio += 20; relogio },
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertEquals("uma detecção, não uma cascata", 1, deteccoes)
        assertEquals(
            "depois da detecção só os 3 primeiros quadros contam — o resto é mudez",
            3,
            detector.quadrosVistos,
        )
    }

    @Test
    fun semModelo_aEscutaNaoSobeENaoDerruba() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        var abriu = false
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { abriu = true; quadros(10) },
            fecharMicrofone = {},
            criarDetector = { null },
            aoDetectar = {},
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertFalse("sem modelo não se abre microfone — seria bateria por nada", abriu)
    }

    @Test
    fun semRota_aEscutaDesligaSemVazarDetector() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val detector = DetectorFalso()
        var fechouRota = false
        val escuta = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = { null }, // sem óculos
            fecharMicrofone = { fechouRota = true },
            criarDetector = { detector.comoDetector() },
            aoDetectar = {},
        )
        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()

        assertTrue("o ponteiro nativo tem de ser fechado mesmo sem rota", detector.fechado)
        assertFalse(
            "`iniciar()` falhou e não contou usuário — `liberar()` aqui deixaria " +
                "déficit que o rádio pagaria depois",
            fechouRota,
        )
    }

    // ── costura ───────────────────────────────────────────────────────────────

    private fun escutaCom(
        escopo: TestScope,
        detector: DetectorFalso,
        fluxo: Flow<ShortArray>,
        aoFechar: () -> Unit = {},
        suprimido: (Long) -> Boolean = { false },
    ) = EscutaDeAtivacao(
        escopo = escopo,
        abrirMicrofone = { fluxo },
        fecharMicrofone = aoFechar,
        criarDetector = { detector.comoDetector() },
        aoDetectar = {},
        suprimido = suprimido,
    )

    /**
     * **O laço e o estado publicado não podem divergir.**
     *
     * [EscutaDeAtivacao.escutando] lê o `Job` vivo; [EscutaDeAtivacao.estado] é o valor
     * publicado. São duas fontes para o mesmo fato, e duas fontes envelhecem em
     * direções diferentes se ninguém as prender — foi assim que o KDoc de `escutando`
     * passou a afirmar que ela alimentava o perfil, o que nunca foi verdade.
     *
     * Elas não divergem porque o `finally` de `escutar` leva `OUVINDO` para `EM_PAUSA`
     * e **não suspende**, de modo que roda também no cancelamento. Este teste é o que
     * transforma essa propriedade de detalhe de implementação em invariante: quem puser
     * um `withContext` ou um `delay` naquele `finally` descobre aqui, e não no perfil
     * de um agente afirmando que o microfone está de pé com o laço morto.
     */
    @Test
    fun oLacoEOEstadoNaoDivergem() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        // **Um fluxo que não termina.** `quadros(n)` completa, o `collect` retorna e o
        // `finally` encerra o laço sozinho — a escuta estaria morta por conclusão
        // normal, e não por `parar()`, que é o que este teste quer observar.
        // `awaitCancellation` deixa a corrotina viva e ociosa, que é o estado real de
        // uma escuta esperando alguém falar.
        val vivoEOcioso: Flow<ShortArray> = flow {
            emit(ShortArray(320))
            kotlinx.coroutines.awaitCancellation()
        }
        val escuta = escutaCom(escopo, DetectorFalso(), vivoEOcioso)

        escuta.ajustarPara(ModoOperacao.ATIVO)
        escopo.advanceUntilIdle()
        assertTrue("a escuta não subiu", escuta.escutando)
        assertEquals(EstadoDaEscuta.OUVINDO, escuta.estado.value)

        escuta.parar()
        escopo.advanceUntilIdle()

        assertFalse("o laço sobreviveu ao parar()", escuta.escutando)
        assertEquals(
            "O laço morreu e o estado ficou em OUVINDO. É a divergência que o KDoc " +
                "de `escutando` declara impossível — e é exatamente o perfil " +
                "afirmando microfone de pé sobre uma escuta morta.",
            EstadoDaEscuta.EM_PAUSA,
            escuta.estado.value,
        )
    }
}
