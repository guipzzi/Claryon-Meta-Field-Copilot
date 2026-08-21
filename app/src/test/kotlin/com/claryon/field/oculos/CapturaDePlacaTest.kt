package com.claryon.field.oculos

import com.claryon.agent.Utterance
import com.claryon.common.ClaryonError
import com.claryon.common.LaconicityPolicy
import com.claryon.common.Result
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.Frame
import com.claryon.glasses.GlassesFacade
import com.claryon.glasses.PhotoData
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O subfluxo de captura, sem óculos e sem ML Kit.**
 *
 * O que se prova aqui é a lógica que decide: quando falar, quando parar de ler, o
 * que fazer quando a câmera não abre, e o que **nunca** sai daqui. O OCR de verdade
 * e o descarte no disco são do teste instrumentado (`FramesEfemerosTest`) — aqui não
 * há `Bitmap`, e é justamente por isso que [CapturaDePlaca] recebe um
 * [LeitorDeFrame] em vez de construir o reconhecedor.
 */
class CapturaDePlacaTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /**
     * Fachada de mentira com a **mesma regra de resultado da real**: `Failure` só
     * quando nenhum frame chegou. Um fake mais bonzinho — sempre `Success` — deixaria
     * passar o ramo que distingue "não li a placa" de "a câmera não abriu", que é a
     * metade do valor deste tipo.
     */
    private class FachadaFake(
        private val frames: Flow<Frame>,
        private val causaSemFrame: ClaryonError = ClaryonError.Glasses(
            "glasses.stream_error.HINGE_CLOSED",
            "Óculos dobrados. Abra as hastes.",
        ),
    ) : GlassesFacade {

        var perfilPedido: CameraProfile? = null
        var aberturas = 0
        var entregues = 0

        override val registration: StateFlow<RegistrationStatus> =
            MutableStateFlow(RegistrationStatus.REGISTERED)
        override val session: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STARTED)
        override suspend fun ensureRegistered(): Result<Unit> = Result.success(Unit)
        override suspend fun startSession(): Result<Unit> = Result.success(Unit)
        override suspend fun capturePhoto(): Result<PhotoData> =
            Result.failure(ClaryonError.Glasses("x", "y"))

        override suspend fun withCamera(
            config: CameraProfile,
            block: suspend (Flow<Frame>) -> Unit,
        ): Result<Unit> {
            perfilPedido = config
            aberturas++
            block(flow { frames.collect { entregues++; emit(it) } })
            return if (entregues > 0) Result.success(Unit) else Result.failure(causaSemFrame)
        }
    }

    private fun frame(i: Int) = Frame(width = 8, height = 8, timestampNanos = i.toLong(), bytes = ByteArray(96))

    /** Um frame a cada [intervaloMs], para sempre. É o que a câmera faz. */
    private fun fluxoContinuo(intervaloMs: Long = 140L): Flow<Frame> = flow {
        var i = 0
        while (true) {
            delay(intervaloMs)
            emit(frame(i++))
        }
    }

    private fun captura(
        fachada: GlassesFacade,
        leitor: LeitorDeFrame,
        ditas: MutableList<Utterance> = mutableListOf(),
        janelaMs: Long = CapturaDePlaca.JANELA_MS,
        agora: () -> Long,
    ) = CapturaDePlaca(
        facade = { fachada },
        leitor = leitor,
        avisar = { ditas += it },
        agoraMs = agora,
        janelaMs = janelaMs,
    )

    // ── A instrução ───────────────────────────────────────────────────────────

    /**
     * O teto de sete palavras é invariante do produto, e a instrução não é exceção
     * só por não sair de `utteranceFor`.
     */
    @Test
    fun aInstrucaoRespeitaOProtocoloDeLaconicidade() {
        val texto = (CapturaDePlaca.INSTRUCAO as Utterance.Falar).texto
        assertTrue(
            "\"$texto\" tem ${LaconicityPolicy.wordCount(texto)} palavras, teto ${LaconicityPolicy.MAX_WORDS}",
            LaconicityPolicy.isCompliant(texto),
        )
    }

    /**
     * **A instrução sai ANTES da câmera abrir.**
     *
     * Invertido, o agente ouviria "aponte para a placa" com a janela de 5 s já
     * correndo há um segundo — e a captura acabaria antes de ele saber o que fazer.
     */
    @Test
    fun falaAInstrucaoAntesDeAbrirACamera() = runTest {
        val ditas = mutableListOf<Utterance>()
        var jaFalouQuandoAbriu: Boolean? = null
        val fachada = object : GlassesFacade {
            override val registration: StateFlow<RegistrationStatus> =
                MutableStateFlow(RegistrationStatus.REGISTERED)
            override val session: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STARTED)
            override suspend fun ensureRegistered() = Result.success(Unit)
            override suspend fun startSession() = Result.success(Unit)
            override suspend fun capturePhoto(): Result<PhotoData> =
                Result.failure(ClaryonError.Glasses("x", "y"))

            override suspend fun withCamera(
                config: CameraProfile,
                block: suspend (Flow<Frame>) -> Unit,
            ): Result<Unit> {
                jaFalouQuandoAbriu = ditas.isNotEmpty()
                block(flow { })
                return Result.failure(ClaryonError.Glasses("glasses.no_frames", "sem imagem"))
            }
        }

        captura(fachada, { null }, ditas) { currentTime }.ler()

        assertEquals(listOf(CapturaDePlaca.INSTRUCAO), ditas)
        assertEquals("a câmera abriu antes da instrução sair", true, jaFalouQuandoAbriu)
    }

    // ── A leitura ─────────────────────────────────────────────────────────────

    /**
     * Para no primeiro frame com placa, e **não** no primeiro frame.
     *
     * Os dois números importam: `frames` é o custo medido da leitura (bateria e
     * atraso), e um teste que só exigisse `Lida` passaria com uma implementação que
     * lesse a câmera inteira pelos 5 s e devolvesse a última.
     */
    @Test
    fun paraNoPrimeiroFrameComPlaca_eContaQuantosCustou() = runTest {
        val fachada = FachadaFake(fluxoContinuo())
        // Os três primeiros frames não têm placa: o agente ainda está mirando.
        var vistos = 0
        val leitor = LeitorDeFrame { if (++vistos >= 4) "ABC1D23" else null }

        val r = captura(fachada, leitor) { currentTime }.ler()

        assertEquals(LeituraDePlaca.Lida("ABC1D23", frames = 4, duracaoMs = 560L), r)
        assertEquals("a câmera foi aberta mais de uma vez", 1, fachada.aberturas)
        assertEquals(
            "leitura de placa tem de pedir o perfil de OCR, não o de evidência",
            CameraProfile.OCR,
            fachada.perfilPedido,
        )
        // A prova de que parou: o fluxo é infinito e só 4 frames foram entregues.
        assertEquals(4, fachada.entregues)
    }

    /**
     * **A janela de 5 s corta — e o contra-teste é a MESMA configuração sem a janela.**
     *
     * Três execuções, e a régua do §6, pergunta 3: *"rode as duas configurações e
     * exija que difiram"*. A placa aparece sempre no frame 45 (6,3 s):
     *
     *  - com a janela do aceite → `Ilegivel`, cortada em 35 frames;
     *  - **com a janela larga → `Lida`**, provando que quem cortou foi o teto e não
     *    o fluxo, o fake ou o leitor;
     *  - e uma terceira, com placa dentro da janela, para o teto não estar cortando
     *    tudo.
     *
     * Sem o ramo do meio, este teste passaria com a janela removida — bastaria o
     * fluxo acabar. E janela removida é câmera aberta enquanto o agente caminha,
     * que é o custo de bateria que o perfil de OCR existe para conter.
     */
    @Test
    fun aJanelaDeCincoSegundosCorta_eSemElaAMesmaPlacaSeriaLida() = runTest {
        // Placa no frame 45 = 6,3 s. Com a janela do aceite: não dá tempo.
        var a = 0
        val comJanela = FachadaFake(fluxoContinuo())
        val rComJanela = captura(comJanela, { if (++a >= 45) "ABC1D23" else null }) { currentTime }.ler()

        // MESMA placa, MESMO instante, janela larga: dá tempo.
        var b = 0
        val semJanela = FachadaFake(fluxoContinuo())
        val rSemJanela = captura(
            semJanela,
            { if (++b >= 45) "ABC1D23" else null },
            janelaMs = 60_000L,
        ) { currentTime }.ler()

        // Controle: placa em 4,2 s passa pela janela do aceite.
        var c = 0
        val dentro = FachadaFake(fluxoContinuo())
        val rDentro = captura(dentro, { if (++c >= 30) "ABC1D23" else null }) { currentTime }.ler()

        assertTrue(
            "45 frames a 140 ms = 6,3 s: a janela de ${CapturaDePlaca.JANELA_MS} ms " +
                "tem de ter cortado antes. Se este ramo devolveu `Lida`, o teto do " +
                "aceite não existe.",
            rComJanela is LeituraDePlaca.Ilegivel,
        )
        assertEquals(
            "cortou fora do teto: 5000 ms / 140 ms = 35 frames",
            35,
            (rComJanela as LeituraDePlaca.Ilegivel).frames,
        )
        assertTrue(rComJanela.duracaoMs <= CapturaDePlaca.JANELA_MS)

        assertEquals(
            "sem a janela a MESMA configuração tem de ler a placa — senão quem " +
                "cortou não foi o teto, e este teste não testa o teto",
            LeituraDePlaca.Lida("ABC1D23", frames = 45, duracaoMs = 6_300L),
            rSemJanela,
        )
        assertTrue("30 frames a 140 ms = 4,2 s, dentro da janela", rDentro is LeituraDePlaca.Lida)
    }

    // ── Quando a câmera não abre ──────────────────────────────────────────────

    /**
     * **Sem frame nenhum é `SemCamera`, e carrega a causa tipada — não "ilegível".**
     *
     * Dizer "placa ilegível" com os óculos dobrados manda o agente aproximar-se de
     * um veículo que o aparelho nunca viu. A causa vem do `errorStream`, que é o
     * fluxo que diz **por que** o stream parou.
     */
    @Test
    fun cameraQueNaoEntregaImagem_naoViraPlacaIlegivel() = runTest {
        val fachada = FachadaFake(flow { })

        val r = captura(fachada, { "ABC1D23" }) { currentTime }.ler()

        assertEquals(
            LeituraDePlaca.SemCamera(
                "glasses.stream_error.HINGE_CLOSED",
                "Óculos dobrados. Abra as hastes.",
            ),
            r,
        )
    }

    /**
     * O SDK lançando não pode subir até o executor: lá vira "Falha interna.", três
     * palavras que não dizem nada ao agente e apagam a causa.
     */
    @Test
    fun excecaoDoSdkViraCausaTipada_emVezDeSubir() = runTest {
        val fachada = object : GlassesFacade {
            override val registration: StateFlow<RegistrationStatus> =
                MutableStateFlow(RegistrationStatus.REGISTERED)
            override val session: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STARTED)
            override suspend fun ensureRegistered() = Result.success(Unit)
            override suspend fun startSession() = Result.success(Unit)
            override suspend fun capturePhoto(): Result<PhotoData> =
                Result.failure(ClaryonError.Glasses("x", "y"))

            override suspend fun withCamera(
                config: CameraProfile,
                block: suspend (Flow<Frame>) -> Unit,
            ): Result<Unit> = throw IllegalStateException("Wearables.initialize não rodou")
        }

        val r = captura(fachada, { "ABC1D23" }) { currentTime }.ler()

        assertTrue(r is LeituraDePlaca.SemCamera)
        assertEquals("glasses.camera_threw", (r as LeituraDePlaca.SemCamera).codigo)
    }

    // ── O que NÃO sai daqui ───────────────────────────────────────────────────

    /**
     * **A saída é string, e o frame não escapa.**
     *
     * A regra dura do `CLAUDE.md` §2 proíbe base biométrica, e uma abordagem tem
     * gente no enquadramento. O tipo já garante metade — [LeituraDePlaca] não tem
     * campo de imagem —, e o que este teste acrescenta é que o objeto de captura
     * também não **guarda** frame nenhum entre uma leitura e a seguinte: a segunda
     * chamada não pode enxergar o que a primeira viu.
     */
    @Test
    fun nenhumFrameSobreviveAChamada() = runTest {
        val vistos = mutableListOf<Frame>()
        val fachada = FachadaFake(fluxoContinuo())
        val leitor = LeitorDeFrame { f -> vistos += f; if (vistos.size >= 2) "ABC1D23" else null }
        val cap = captura(fachada, leitor) { currentTime }

        val primeira = cap.ler()
        val quantosNaPrimeira = vistos.size
        vistos.clear()
        val segunda = cap.ler()

        assertTrue(primeira is LeituraDePlaca.Lida)
        assertTrue(segunda is LeituraDePlaca.Lida)
        // A segunda leitura vê frames NOVOS — não os da primeira nem um cache.
        assertEquals(2, quantosNaPrimeira)
        assertFalse("a segunda leitura reaproveitou frame da primeira", vistos.isEmpty())
        // E o que atravessa a fronteira é sete caracteres, nunca imagem.
        assertEquals("ABC1D23", (segunda as LeituraDePlaca.Lida).placa)
        assertNull(
            "LeituraDePlaca não pode ganhar campo de imagem sem passar por spec",
            LeituraDePlaca::class.java.declaredFields.firstOrNull {
                it.type.name.contains("Bitmap") || it.type.name.contains("Frame")
            },
        )
    }
}
