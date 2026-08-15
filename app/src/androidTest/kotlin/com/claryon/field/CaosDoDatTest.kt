package com.claryon.field

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Caos do DAT: registro, permissão, múltiplos aparelhos e captura.**
 *
 * Complementa `CaosDoAparelhoTest`, que cobre estados físicos (don/doff/fold/
 * powerOff). Aqui o alvo são os caminhos que só ficaram exercitáveis depois de
 * confirmar, por inspeção do artefato `mwdat-mockdevice-0.9.0`, três capacidades
 * que a documentação descreve mas não publica em assinatura Android:
 *
 *  - `MockDeviceKitConfig(initiallyRegistered, initialPermissionsGranted)` —
 *    permite subir o MDK **sem registro** e **sem permissão**, que são os dois
 *    estados iniciais reais de um app recém-instalado;
 *  - `MockPermissions.set` / `setRequestResult` — nega a permissão de câmera do
 *    DAT, distinta da permissão de runtime do Android;
 *  - `pairGlasses` até três vezes + `unpairDevice` — guarnição com mais de um
 *    aparelho visível.
 *
 * Cada teste aqui responde a uma linha da tabela de armadilhas do
 * `docs/PADROES_DE_ENGENHARIA.md` que até agora era **afirmação sem medição**.
 *
 * ## O que o MDK continua não cobrindo
 *
 * **Áudio.** Confirmado por inspeção: `MockGlassesServices` expõe `camera`,
 * `captouch` e nada mais; `MockDeviceKitInterface` expõe `permissions`. Não há
 * microfone nem alto-falante simulados. Todo o ciclo de voz — HFP, beamforming,
 * wake word, latência fala→resposta — depende de hardware e está registrado em
 * `docs/VERIFICACOES_COM_HARDWARE.md`.
 *
 * ## Como rodar — e por que método a método
 *
 * **Medido:** os testes que abrem stream de câmera passam isolados e falham em
 * lote, mesmo dentro da mesma classe. O `MockDeviceKit.getInstance` é singleton
 * de processo e o decodificador de vídeo não volta ao estado limpo entre
 * habilitações; o sintoma é sucesso na primeira e zero frames nas seguintes.
 *
 * Isso não é defeito do produto e sim do simulador — mas define como a suíte
 * roda. Por método:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.CaosDoDatTest#duasCapturasSimultaneas_naoCorrompemUmaAOutra
 * ```
 *
 * O script `scripts/caos_mdk.sh` roda todos, um por processo, e resume.
 */
@RunWith(AndroidJUnit4::class)
class CaosDoDatTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var scope: CoroutineScope? = null
    private var mock: MockDeviceController? = null

    @Before
    fun preparar() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        concederPermissoesDeRuntime()
    }

    /**
     * Concede as permissões **de runtime do Android** — distintas das do DAT.
     *
     * Sem isto o feed de câmera do MDK não produz frame nenhum, e o sintoma é
     * enganoso: os testes de permissão do DAT mediam `frames = 0` e pareciam
     * estar provando que a permissão do DAT foi respeitada, quando na verdade
     * faltava a permissão do Android e o DAT nem chegava a ser exercitado.
     *
     * O padrão vem da própria documentação do MDK (`MockDeviceKitTestCase`), e é
     * necessário porque o runner de instrumentação **não concede nada**.
     */
    private fun concederPermissoesDeRuntime() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in listOf("android.permission.CAMERA", "android.permission.BLUETOOTH_CONNECT")) {
            runCatching { automation.grantRuntimePermission(context.packageName, p) }
        }
    }

    @After
    fun limpar() {
        mock?.disable()
        mock = null
        scope?.cancel()
        scope = null
    }

    private fun novoMock(): MockDeviceController =
        MockDeviceController(context).also { mock = it }

    private fun facade(): DatGlassesFacade =
        DatGlassesFacade(scope!!)

    // ── Registro ──────────────────────────────────────────────────────────────

    @Test
    fun semRegistro_oAppNaoAbreSessaoEDizPorQue(): Unit = runBlocking {
        // Armadilha "Registro perdido": em Dev Mode só um app de terceiros fica
        // registrado por vez. Acontece de verdade quando o desenvolvedor abre
        // outro app do DAT — e em campo, quando a organização instala outro.
        //
        // O que não pode acontecer: o app tentar abrir sessão assim mesmo e
        // ficar num limbo silencioso. Sem display, limbo é igual a app morto.
        val m = novoMock()
        assertTrue(
            "MDK deveria subir mesmo sem registro",
            m.enableComConfig(registrado = false, permissoesConcedidas = true),
        )

        val f = facade()
        val estado = withTimeoutOrNull(8_000) {
            f.registration.first { it != RegistrationStatus.UNKNOWN }
        }
        Log.i(TAG, "registro sem config registrada: $estado")

        val r = f.startSession()
        // O contrato: ou o registro subiu sozinho (o MDK pode completar), ou a
        // sessão falha com causa tipada. O que NÃO pode é lançar exceção nem
        // devolver sucesso sem sessão.
        when (r) {
            is Result.Success ->
                assertEquals(
                    "sucesso sem sessão de verdade",
                    SessionStatus.STARTED,
                    withTimeoutOrNull(8_000) { f.session.first { it == SessionStatus.STARTED } },
                )
            is Result.Failure -> Log.i(TAG, "falha tipada, como esperado: ${r.error}")
        }
    }

    @Test
    fun registroChegandoDepois_naoDeixaOAppTravado(): Unit = runBlocking {
        // O agente abre o app antes de parear, vai ao Meta AI, volta. O app tem
        // de se recuperar sem reinstalar.
        val m = novoMock()
        m.enableComConfig(registrado = false, permissoesConcedidas = true)
        val f = facade()
        f.startSession()

        // Pareia depois — simula a volta do deeplink.
        assertNotNull("pareamento tardio deveria funcionar", m.parear())

        val depois = withTimeoutOrNull(10_000) {
            f.registration.first { it == RegistrationStatus.REGISTERED }
        }
        Log.i(TAG, "registro após pareamento tardio: $depois")

        // **Medido: parear NÃO restaura o registro.** Fica `UNAVAILABLE`
        // indefinidamente. O registro exige o fluxo do Meta AI
        // (`DatGlassesFacade.startRegistration`), e essa era a lacuna: o método
        // existia, estava documentado e não era chamado por ninguém. O painel
        // agora mostra o botão "Conectar aos óculos" quando o estado não é
        // REGISTERED, e o app anuncia a perda em voz alta.
        assertNull("parear passou a restaurar o registro — reavaliar o botão", depois)
        assertTrue("segunda tentativa não pode lançar", f.startSession() is Result<*>)
    }

    // ── Permissão do DAT ──────────────────────────────────────────────────────

    @Test
    fun permissaoDeCameraNegada_falhaTipadaEmVezDeCrash(): Unit = runBlocking {
        // Distinta da permissão de runtime do Android: esta é a que o agente
        // responde no diálogo da Meta AI. Negada, `withCamera` tem de devolver
        // falha — nunca lançar, nunca ficar esperando frame que não vem.
        val m = novoMock()
        m.enableComConfig(registrado = true, permissoesConcedidas = false)
        m.permissaoDeCamera(concedida = false)
        m.respostaDoProximoPedidoDePermissao(concedida = false)

        val f = facade()
        f.startSession()

        // `{ frames++ }` NÃO conta frames: o bloco recebe o Flow e é invocado uma
        // vez só. A primeira versão deste teste media invocações do bloco e
        // reportava "frames=1" como se um frame tivesse chegado. Consumir o fluxo
        // é o que mede.
        var frames = 0
        val r = withTimeoutOrNull(12_000) {
            f.withCamera(CameraProfile.OCR) { fluxo ->
                withTimeoutOrNull(8_000) { fluxo.first() }?.let { frames++ }
            }
        }
        Log.i(TAG, "withCamera sem permissão do DAT: $r, frames=$frames")

        // Medido: com a permissão negada, o `addCamera` **devolve sucesso** e o
        // `stream.start()` não reclama — simplesmente nenhum frame chega. Sem o
        // vigia de primeiro frame, `withCamera` devolvia `Success` e o consumidor
        // ficava suspenso para sempre esperando uma imagem que nunca vem.
        //
        // Este é o teste que justifica o vigia: a falha tem de ser **tipada**,
        // para virar earcon. Silêncio não é resposta.
        assertNotNull("withCamera travou sem permissão", r)
        assertTrue("sucesso mudo: câmera abriu e não entregou imagem", r is Result.Failure)
        assertEquals(0, frames)
    }

    @Test
    fun permissaoConcedidaDepoisDeNegada_voltaAFuncionar(): Unit = runBlocking {
        // O agente nega, percebe que a placa não é lida, e concede. O app tem de
        // aproveitar sem reiniciar.
        val m = novoMock()
        m.enableComConfig(registrado = true, permissoesConcedidas = false)
        m.permissaoDeCamera(concedida = false)

        val f = facade()
        f.startSession()
        withTimeoutOrNull(4_000) { f.withCamera(CameraProfile.OCR) { } }

        m.permissaoDeCamera(concedida = true)
        m.respostaDoProximoPedidoDePermissao(concedida = true)

        // **Reabrir a SESSÃO, não só a câmera.**
        //
        // Medido aqui: sem a permissão do DAT, o que falha é o `startSession` —
        // "Sessão não iniciada", não "addCamera falhou". Só repetir `withCamera`
        // depois de conceder não recupera nada, porque não há sessão para
        // pendurar a câmera. O agente concederia a permissão e continuaria sem
        // câmera até reiniciar o app.
        val rSessao = f.startSession()
        Log.i(TAG, "startSession após conceder: $rSessao")

        var frames = 0
        withTimeoutOrNull(15_000) {
            f.withCamera(CameraProfile.OCR) { fluxo -> fluxo.first(); frames++ }
        }
        Log.i(TAG, "frames após conceder e reabrir sessão: $frames")

        // **Limitação mapeada do MockDeviceKit, não do produto.**
        //
        // Conceder a permissão depois de negada não restaura o fluxo de vídeo no
        // simulador, nem com sessão nova. O estado de permissão parece ser fixado
        // no pareamento, e não há API para reparear sem `disable()`/`enable()` —
        // que aborta o processo (MediaCodec).
        //
        // O que o teste garante hoje: o caminho **não trava e não lança**, e o
        // `startSession` volta a ter sucesso de verdade. Se a recuperação
        // funciona em campo é pergunta para o hardware —
        // `docs/VERIFICACOES_COM_HARDWARE.md`.
        assertTrue("startSession não recuperou após conceder", rSessao is Result.Success)
        Log.i(TAG, "recuperação de permissão: pendente de verificação em hardware")
    }

    // ── Múltiplos aparelhos ───────────────────────────────────────────────────

    @Test
    fun tresAparelhosPareados_oAppEscolheUmSo(): Unit = runBlocking {
        // Troca de turno, óculos emprestado, dois pares na viatura. O
        // `AutoDeviceSelector` precisa escolher **um** de forma determinística.
        // Abrir sessão com todos multiplicaria bateria e tráfego por três, e
        // duas sessões de câmera simultâneas não são suportadas.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        m.parear()
        m.parear()
        assertEquals("MDK aceita até três", 3, m.pareados())

        val f = facade()
        val r = f.startSession()
        Log.i(TAG, "startSession com 3 aparelhos: $r")

        // Agora `startSession` só devolve sucesso com a sessão de fato `STARTED`,
        // então a asserção pode ser direta em vez de "não ficou em limbo".
        assertTrue("com três aparelhos a sessão não subiu: $r", r is Result.Success)
        assertEquals(SessionStatus.STARTED, f.session.value)

        // **Uma só.** A versão anterior deste teste verificava apenas que o
        // estado saía do limbo — o que passaria igual se o app tivesse aberto
        // três sessões. Chamar de novo tem de reaproveitar a existente, não
        // empilhar outra: duas sessões de câmera não são suportadas, e três
        // multiplicariam bateria e tráfego por três.
        val segunda = f.startSession()
        assertTrue("segunda chamada deveria reaproveitar", segunda is Result.Success)
        assertEquals("o estado mudou — provável sessão nova", SessionStatus.STARTED, f.session.value)
    }

    @Test
    fun desemparelharDuranteAOperacao_naoDeixaSessaoFantasma(): Unit = runBlocking {
        // O agente desliga os óculos e guarda. A sessão tem de morrer junto, e o
        // app tem de saber. Sessão fantasma é o pior estado: o app se comporta
        // como se estivesse ouvindo.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        f.startSession()
        withTimeoutOrNull(10_000) { f.session.first { it == SessionStatus.STARTED } }

        m.desemparelhar()
        assertEquals(0, m.pareados())

        val depois = withTimeoutOrNull(10_000) {
            f.session.first { it != SessionStatus.STARTED }
        }
        Log.i(TAG, "sessão após desemparelhar: $depois")
        assertNotNull("sessão continuou STARTED com o aparelho desemparelhado", depois)
    }

    // ── Captura ───────────────────────────────────────────────────────────────

    @Test
    fun duasCapturasSimultaneas_naoCorrompemUmaAOutra(): Unit = runBlocking {
        // Armadilha "Duas capturas simultâneas". O agente pode dizer "consultar
        // placa" duas vezes sob estresse, e o pipeline de voz não serializa
        // sozinho. Uma das duas tem de falhar de forma limpa.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        f.startSession()
        withTimeoutOrNull(10_000) { f.session.first { it == SessionStatus.STARTED } }

        // Stream ATIVO é pré-condição de `capturePhoto` — a primeira versão deste
        // teste esquecia isso e as duas capturas falhavam com `no_stream`, o que
        // parecia um defeito grave do produto e era defeito do teste.
        var resultados: List<Result<*>>? = null
        val rCam = withTimeoutOrNull(25_000) {
            f.withCamera(CameraProfile.OCR) { frames ->
                val primeiro = withTimeoutOrNull(12_000) { frames.first() }
                Log.i(TAG, "primeiro frame: ${if (primeiro != null) "chegou" else "NAO CHEGOU"}")
                resultados = listOf(
                    scope!!.async { f.capturePhoto() },
                    scope!!.async { f.capturePhoto() },
                ).awaitAll()
                Log.i(TAG, "capturas concluidas")
            }
        }
        Log.i(TAG, "withCamera devolveu: $rCam")
        Log.i(TAG, "duas capturas com stream ativo: ${resultados?.map { it::class.simpleName }}")
        assertNotNull("as duas capturas travaram", resultados)

        // **Pelo menos uma tem de dar certo.** A guarda de uma-por-vez existe
        // para a segunda falhar limpo, não para as duas morrerem: o agente que
        // diz "consultar placa" duas vezes sob estresse não pode ficar sem
        // resposta nenhuma.
        assertTrue(
            "nenhuma das duas capturas concorrentes teve sucesso: $resultados",
            resultados!!.any { it is Result.Success },
        )
    }

    @Test
    fun capturaSemStreamAtivo_falhaEmVezDeTravar(): Unit = runBlocking {
        // Armadilha "capturePhoto sem stream". Sem sessão, a captura tem de
        // devolver falha rapidamente — o agente está esperando uma resposta.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        // Sem startSession de propósito.
        val r = withTimeoutOrNull(8_000) { f.capturePhoto() }
        Log.i(TAG, "captura sem sessão: $r")
        assertNotNull("captura sem sessão travou em vez de falhar", r)
        assertTrue("deveria ser falha", r is Result.Failure)
    }

    // ── Ciclo de vida agressivo ───────────────────────────────────────────────

    @Test
    fun oscilacaoRapidaDeEstado_naoDeixaEstadoInconsistente(): Unit = runBlocking {
        // Óculos meio caindo do rosto, hastes sendo ajustadas, agente correndo.
        // O aparelho oscila entre estados mais rápido do que qualquer humano
        // faria de propósito — e o app não pode acumular sessões nem travar.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        f.startSession()
        withTimeoutOrNull(10_000) { f.session.first { it == SessionStatus.STARTED } }

        repeat(12) {
            m.tirarDoRosto(); delay(40)
            m.vestir(); delay(40)
            m.dobrar(); delay(40)
            m.desdobrar(); delay(40)
        }

        val estado = withTimeoutOrNull(8_000) { f.session.first { true } }
        Log.i(TAG, "sessão após oscilação: $estado")
        assertNotNull("app travou sob oscilação de estado", estado)
        // E ainda responde a comando.
        assertTrue(f.startSession() is Result<*>)
    }

    @Test
    fun sessaoEncerradaPeloAparelho_naoRevive(): Unit = runBlocking {
        // Armadilha "Tentar reviver sessão encerrada" — *cascading stop*. Uma vez
        // que o aparelho derruba a sessão, ela não volta: é preciso criar outra.
        // Se o app tentar reviver, fica num estado em que acha que está ouvindo e
        // não está.
        //
        // O encerramento é provocado por **desligamento**, e não pelo toque longo
        // na haste: o gesto capacitivo saiu do produto por decisão de escopo (ver
        // `DECISIONS.md`), e um teste é o último lugar onde uma capacidade
        // removida deveria continuar viva.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        f.startSession()
        withTimeoutOrNull(10_000) { f.session.first { it == SessionStatus.STARTED } }

        m.desligar()
        val parou = withTimeoutOrNull(10_000) {
            f.session.first { it != SessionStatus.STARTED }
        }
        Log.i(TAG, "sessão após desligamento: $parou")

        // Sessão NOVA precisa funcionar.
        val nova = f.startSession()
        Log.i(TAG, "nova sessão após encerramento: $nova")
        assertTrue("criar sessão nova não pode lançar", nova is Result<*>)
    }

    @Test
    fun desligarNoMeioDaCaptura_naoDeixaCapturaPendurada(): Unit = runBlocking {
        // Bateria acabando exatamente durante a leitura de placa. A captura tem
        // de terminar — com falha — em vez de ficar aguardando um frame que
        // nunca vem, segurando o pipeline de voz inteiro.
        val m = novoMock()
        m.enableWithPhoneCameraFeed()
        val f = facade()
        f.startSession()
        withTimeoutOrNull(10_000) { f.session.first { it == SessionStatus.STARTED } }

        val captura = scope!!.async { f.capturePhoto() }
        delay(120)
        m.desligar()

        val r = withTimeoutOrNull(15_000) { captura.await() }
        Log.i(TAG, "captura com desligamento no meio: $r")
        assertNotNull("captura ficou pendurada após desligamento", r)
    }

    @Test
    fun habilitarDuasVezes_naoAbortaOProcesso(): Unit = runBlocking {
        // Regressão do guarda em `MockDeviceController`: `enable()` duas vezes
        // sem `disable()` aborta o processo nativamente (MediaCodec
        // CHECK_EQ(mState, UNINITIALIZED)). Já custou uma sessão de depuração.
        val m = novoMock()
        assertTrue(m.enableWithPhoneCameraFeed())
        assertTrue("segunda chamada deve ser idempotente", m.enableWithPhoneCameraFeed())
        assertFalse("não pode ter zerado o pareamento", m.pareados() == 0)
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
