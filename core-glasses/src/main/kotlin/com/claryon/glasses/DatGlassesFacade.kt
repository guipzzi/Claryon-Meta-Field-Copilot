package com.claryon.glasses

import android.app.Activity
import android.util.Log
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Implementação de [GlassesFacade] sobre o Meta Wearables DAT 0.9 — **o único
 * ponto do código que toca o SDK**.
 *
 * Todas as assinaturas foram confirmadas no sample oficial `CameraAccess`
 * (Regra Zero): `Wearables.registrationState`/`devices` (Flow), `createSession`
 * → `DatResult` (`.onSuccess`/`.onFailure`), `session.start()` → Unit, e a API
 * de câmera 0.9 `session.addCamera(...)` → `Camera` → `Camera.stream`.
 *
 * Os enums do DAT (`RegistrationState`, `DeviceSessionState`, `StreamState`) são
 * mapeados **por nome** para os nossos, de propósito: evita referenciar
 * constantes de memória e resiste a acréscimos de valores no SDK em preview.
 *
 * Além do contrato [GlassesFacade], expõe StateFlows extras ([streamState],
 * [frameInfo], [deviceCount]) para o painel de diagnóstico do M2.
 *
 * ## O [scope] é o dono desta fachada — e ele NÃO pode ser de tela
 *
 * Tudo aqui roda no escopo recebido: o `stateIn(Eagerly)` de [registration] e
 * [deviceCount], os coletores de `session.state`, `session.errors`, `stream.state`
 * e `stream.errorStream`, e o vigia de primeiro frame de [withCamera]. Cancelar o
 * escopo **é** desligar a fachada inteira.
 *
 * Isto já foi construído com `viewModelScope` em dois ViewModels ao mesmo tempo, e
 * o resultado eram os dois defeitos que se esperam disso: a fachada morria no
 * `onCleared()` — levando sessão, stream e a causa tipada junto —, e havia duas
 * donas de um recurso que é global do aparelho.
 *
 * **Não construa esta classe.** Peça a do processo a
 * `com.claryon.field.oculos.SessaoDosOculos`; uma varredura de fonte
 * (`FachadaDoDatTemDonoUnicoTest`) reprova quem construir outra. Ver
 * `specs/dono-de-processo-para-a-facade-do-dat.spec.md`.
 */
class DatGlassesFacade(private val scope: CoroutineScope) : GlassesFacade {

    override val registration: StateFlow<RegistrationStatus> =
        Wearables.registrationState
            .map { state -> state.name.toEnumOr(RegistrationStatus.UNKNOWN) }
            .stateIn(scope, SharingStarted.Eagerly, RegistrationStatus.UNKNOWN)

    /** Número de dispositivos visíveis (diagnóstico). */
    val deviceCount: StateFlow<Int> =
        Wearables.devices
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _session = MutableStateFlow(SessionStatus.IDLE)
    override val session: StateFlow<SessionStatus> = _session.asStateFlow()

    private val _stream = MutableStateFlow(StreamStatus.STOPPED)
    val streamState: StateFlow<StreamStatus> = _stream.asStateFlow()

    private val _frameInfo = MutableStateFlow<FrameInfo?>(null)
    val frameInfo: StateFlow<FrameInfo?> = _frameInfo.asStateFlow()

    /**
     * Erros de sessão do DAT, que chegam por um fluxo **separado** do estado.
     * `replay = 1` para quem assinar logo depois de um erro ainda o receber;
     * `extraBufferCapacity` para o `tryEmit`/`emit` não bloquear o coletor do SDK.
     */
    private val _sessionErrors = MutableSharedFlow<ClaryonError>(replay = 1, extraBufferCapacity = 8)
    val sessionErrors: SharedFlow<ClaryonError> = _sessionErrors.asSharedFlow()

    /**
     * Erros **do stream de câmera**, tipados — distintos dos de sessão.
     *
     * `Stream.errorStream` é `Flow<StreamError>` (não `StateFlow`), e por baixo é
     * um `MutableSharedFlow(replay = 0, extraBufferCapacity = 16, DROP_OLDEST)`.
     * Confirmado no construtor de `StreamImpl` por `javap -p -c`:
     *
     * ```
     * 55: iconst_0            // replay = 0
     * 56: bipush 16           // extraBufferCapacity = 16
     * 58: getstatic ...BufferOverflow.DROP_OLDEST
     * 63: invokestatic ...MutableSharedFlow$default
     * 66: putfield  _errors
     * ```
     *
     * **`replay = 0` é a razão de assinar antes de `start()`**: erro emitido sem
     * assinante vivo não é bufferizado nem reentregue — some para sempre. É por
     * isso que a coleta entra em [observeStream], que já roda antes do
     * `stream.start()` nos dois caminhos, e não num `LaunchedEffect` de tela.
     *
     * Aqui usamos `replay = 1` de propósito: quem assinar logo depois da falha
     * (o painel, um earcon atrasado) ainda recebe a causa.
     */
    private val _streamErrors = MutableSharedFlow<ClaryonError>(replay = 1, extraBufferCapacity = 8)
    val streamErrors: SharedFlow<ClaryonError> = _streamErrors.asSharedFlow()

    /**
     * A última causa tipada do stream corrente, para virar **motivo** do
     * `Result.Failure` de [withCamera].
     *
     * Sem isto o erro existe, é logado, e o chamador ainda recebe
     * `glasses.no_frames` — "a câmera abriu e não entregou imagem" — que descreve
     * o sintoma e esconde a causa. Saber que parou sem saber por que é o que o
     * item do roadmap chama de "não se sabe por que a demonstração parou".
     */
    private val ultimoErroDeStream =
        java.util.concurrent.atomic.AtomicReference<ErroDeStream?>(null)

    // Uma única instância viva do seletor — como o sample oficial (`by lazy`).
    // Criar um seletor novo a cada createSession pode não ter resolvido o
    // dispositivo ativo ainda (leva a NO_ELIGIBLE_DEVICE).
    private val deviceSelector = AutoDeviceSelector()

    private var activeSession: DeviceSession? = null
    private var activeCamera: Camera? = null
    private var activeStream: Stream? = null

    // Jobs dos coletores; cancelados antes de reobservar para não acumular
    // coletores concorrentes a cada start/stop (evita escrita duplicada em
    // _session/_stream/_frameInfo).
    private var sessionObserver: Job? = null
    private var streamObserver: Job? = null

    /** `capturePhoto()` é uma por vez (regra do DAT). */
    private val capturaEmCurso = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Uma parada por câmera — reposto a `false` a cada `addCamera`.
     *
     * A câmera corrente passou a ter **quatro** caminhos que a param: o vigia de
     * primeiro frame, o `finally` de [withCamera], [stopCameraStream] e agora o
     * estado terminal. Sem o guarda, o caso normal (vigia estoura ⇒ `stop()` ⇒
     * estado vai a `STOPPED` ⇒ terminal ⇒ `stop()` de novo) chamaria `stop()`
     * duas ou três vezes na mesma câmera. Não é um guarda de processo: é por
     * câmera, senão a segunda leitura de placa do turno encontraria o guarda já
     * fechado e nunca pararia nada.
     */
    private val cameraEncerrada = java.util.concurrent.atomic.AtomicBoolean(false)


    // ── Registro ────────────────────────────────────────────────────────────

    override suspend fun ensureRegistered(): Result<Unit> =
        if (registration.value == RegistrationStatus.REGISTERED) {
            Result.success(Unit)
        } else {
            Result.failure(
                ClaryonError.Glasses(
                    "glasses.not_registered",
                    "Não registrado. Dispare o registro pelo app Meta AI ou habilite o MockDeviceKit.",
                ),
            )
        }

    /** Fluxo real de registro — exige uma Activity (deeplink de retorno do Meta AI). */
    fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

    fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

    // ── Sessão ──────────────────────────────────────────────────────────────

    /**
     * Abre a sessão e **espera ela ficar utilizável**.
     *
     * A versão anterior completava `Result.success` logo depois de chamar
     * `created.start()`, deixando o resultado real para quem observasse
     * `session.state`. Isso produzia a pior coisa que este projeto tem como
     * regra evitar: **um `Result.Success` que não significa sucesso**.
     *
     * O sintoma foi medido no MockDeviceKit, e é enganoso — `startSession()`
     * devolvia `Success`, `withCamera` logo em seguida devolvia `Success`, e
     * **zero frames chegavam**. Nenhum caminho de erro disparava; nenhum earcon
     * tocava. Num sistema sem display isso é indistinguível de aplicativo morto,
     * que é justamente o cenário que a regra "falha nunca é silêncio" proíbe.
     *
     * Agora o sucesso só volta quando o estado chega a `STARTED`. `STOPPED` e o
     * estouro do prazo viram falha tipada.
     */
    override suspend fun startSession(): Result<Unit> {
        // Sessão já viva E utilizável. `activeSession != null` sozinho não bastava:
        // uma sessão criada que nunca subiu ficava aqui para sempre, e toda
        // tentativa seguinte devolvia sucesso sem nada por trás.
        if (activeSession != null && _session.value == SessionStatus.STARTED) {
            return Result.success(Unit)
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        Wearables.createSession(deviceSelector)
            .onSuccess { created ->
                activeSession = created
                // STARTING **antes** de observar. A ordem inversa era inofensiva
                // enquanto `startSession` devolvia na hora; virou corrida no
                // momento em que passou a esperar `STARTED`: se o coletor
                // emitisse `STARTED` antes desta linha, a atribuição a
                // `STARTING` sobrescreveria o estado bom, o `first { }` esperaria
                // os 12 s inteiros e a sessão **funcionando** seria derrubada por
                // `cleanupSession()`. Defeito nascido dentro da própria correção.
                _session.value = SessionStatus.STARTING
                observeSession(created) // assinar ANTES de start(), para não perder transições
                created.start() // retorna Unit; o estado real chega por session.state
                deferred.complete(Result.success(Unit))
            }
            .onFailure { error, _ ->
                // Falha nunca é silêncio: registra para o diagnóstico.
                Log.e(TAG, "createSession falhou: ${error.description}")
                deferred.complete(
                    Result.failure(ClaryonError.Glasses("glasses.session_failed", error.description)),
                )
            }

        val criada = deferred.await()
        if (criada is Result.Failure) return criada

        // Espera o estado terminal. O prazo existe porque o SDK pode nunca
        // emitir transição — sem permissão, sem aparelho vestido, sem registro —
        // e um `await` sem teto travaria o ciclo de voz inteiro.
        val estado = withTimeoutOrNull(PRAZO_DE_SESSAO_MS) {
            _session.first { it == SessionStatus.STARTED || it == SessionStatus.STOPPED }
        }
        return when (estado) {
            SessionStatus.STARTED -> Result.success(Unit)
            SessionStatus.STOPPED -> {
                cleanupSession()
                Result.failure(
                    ClaryonError.Glasses("glasses.session_stopped", "A sessão encerrou ao iniciar."),
                )
            }
            // Nem subiu nem caiu: o SDK ficou mudo. Limpar a referência é
            // essencial — mantida, a próxima chamada devolveria sucesso imediato
            // apontando para uma sessão que nunca funcionou.
            else -> {
                cleanupSession()
                Result.failure(
                    ClaryonError.Glasses("glasses.session_timeout", "A sessão não subiu a tempo."),
                )
            }
        }
    }

    /**
     * Encerra a sessão na ordem do contrato: **câmera/stream primeiro, sessão
     * depois**. Sem isto, a sessão e o stream continuam vivos no SDK, os óculos
     * seguem transmitindo por Bluetooth sem nenhum indicador, e um `createSession`
     * seguinte encontra a anterior ativa.
     *
     * **Quem chama isto é o FIM DE TURNO, não o fim da tela.** A frase anterior
     * aqui era *"sem isto, sair da tela cancela apenas os coletores"*, e ela
     * descrevia um mundo em que a fachada nascia de `viewModelScope`: sair da tela
     * cancelava o escopo, e com ele a sessão, o stream e a coleta de `errorStream`.
     * Hoje sair da tela não cancela **nada** — a dona é `SessaoDosOculos`, de
     * processo, e é ela quem chama este método quando o agente encerra o turno.
     * Ver `specs/dono-de-processo-para-a-facade-do-dat.spec.md`.
     *
     * `DeviceSession.stop()` confirmado no artefato `mwdat-core:0.9.0`.
     * Sessão parada é terminal: para voltar, criar uma **nova** (não reviver).
     */
    fun stopSession() {
        pararCamera()
        activeSession?.stop()
    }

    private fun observeSession(s: DeviceSession) {
        sessionObserver?.cancel()
        sessionObserver = scope.launch {
            launch {
                s.state.collect { state ->
                    _session.value = state.name.toEnumOr(SessionStatus.IDLE)
                    if (_session.value == SessionStatus.STOPPED) cleanupSession()
                }
            }
            launch {
                // Erros de sessão são one-shot e chegam por um fluxo SEPARADO do
                // estado — observar só o estado perde a causa. Publicamos aqui
                // para o app mapear erro → earcon: falha nunca é silêncio.
                s.errors.collect { erro ->
                    Log.w(TAG, "Erro de sessão: $erro")
                    _sessionErrors.emit(
                        ClaryonError.Glasses("glasses.session_error", erro.toString()),
                    )
                }
            }
        }
    }

    // ── Câmera ──────────────────────────────────────────────────────────────

    /**
     * Liga o stream de câmera e passa a atualizar [streamState]/[frameInfo].
     * Usado pelo painel de diagnóstico (M2). Uma câmera/stream por vez.
     */
    fun startCameraStream(profile: CameraProfile = CameraProfile.EVIDENCE) {
        val s = activeSession ?: return
        if (activeStream != null) return

        s.addCamera(profile.toStreamConfiguration())
            .onSuccess { camera ->
                activeCamera = camera
                cameraEncerrada.set(false)
                val stream = camera.stream
                activeStream = stream
                observeStream(stream)
                _stream.value = StreamStatus.STARTING
                stream.start().onFailure { _, _ -> _stream.value = StreamStatus.STOPPED }
            }
            .onFailure { _, _ -> _stream.value = StreamStatus.STOPPED }
    }

    /** Desliga o stream. `Camera.stop()` desanexa e cascateia para o stream filho. */
    fun stopCameraStream() {
        pararCamera()
    }

    /**
     * Para a câmera corrente **uma vez** e solta as referências.
     *
     * `Camera.stop()` confirmado por `javap` no artefato `mwdat-camera-0.9.0`:
     * `public interface Camera extends java.io.Closeable { getState(); getStream();
     * stop(); }` — `stop()` devolve `void`, não `DatResult`, então não há
     * resultado para conferir; o efeito aparece em `Camera.state`/`Stream.state`.
     */
    private fun pararCamera(alvo: Camera? = null) {
        // `alvo` explícito para o caminho de [withCamera]: se a sessão cair no
        // meio, `cleanupSession()` já zerou `activeCamera`, e sem a referência
        // local a câmera daquela chamada nunca receberia `stop()`. O
        // *cascading stop* da sessão provavelmente a levaria junto — mas
        // "provavelmente" não é a garantia que o `finally` existia para dar.
        val cam = alvo ?: activeCamera ?: return
        if (!cameraEncerrada.compareAndSet(false, true)) return
        runCatching { cam.stop() }
        clearStreamRefs()
    }

    private fun observeStream(stream: Stream) {
        streamObserver?.cancel()
        ultimoErroDeStream.set(null)
        streamObserver = scope.launch {
            launch {
                // **`STOPPED` só é terminal DEPOIS de o stream ter subido.**
                //
                // Medido no artefato, não suposto: `StreamImpl` nasce com
                // `_state = MutableStateFlow(StreamState.STOPPED)` — `javap -p -c`
                // no construtor, `143: getstatic ...StreamState.STOPPED` seguido de
                // `146: invokestatic ...StateFlowKt.MutableStateFlow`.
                //
                // Como `state` é `StateFlow`, o coletor recebe esse valor inicial
                // **na hora em que assina** — e assinamos de propósito antes de
                // `stream.start()`. Tratar esse primeiro `STOPPED` como terminal
                // chamaria `camera.stop()` sobre a câmera recém-criada e mataria o
                // stream antes de ele existir: o conserto do roadmap, aplicado ao
                // pé da letra, quebraria a câmera em vez de consertá-la.
                //
                // A regra inteira — e o porquê de cada ramo — vive em
                // [VidaDoStream], que é função pura e por isso testável sem
                // óculos, sem emulador e sem o decodificador do MockDeviceKit.
                val vida = VidaDoStream()
                stream.state.collect { state ->
                    val nosso = state.name.toEnumOr(StreamStatus.STOPPED)
                    _stream.value = nosso
                    when (vida.aoMudarPara(nosso)) {
                        // Terminal. Sem `Camera.stop()` aqui a câmera continua
                        // anexada à sessão no SDK, e o próximo `addCamera` não
                        // abre — o agente aponta para a placa seguinte e nada
                        // acontece, sem erro nenhum, porque o estado do app diz
                        // que não há stream ativo.
                        AcaoDeStream.PARAR_CAMERA -> pararCamera()
                        AcaoDeStream.SOLTAR_REFERENCIAS -> clearStreamRefs()
                        AcaoDeStream.NADA -> Unit
                    }
                }
            }
            launch {
                // **A coleta que faltava.** Sem ela o estado dizia "parou" e a
                // causa — hastes dobradas, permissão negada, bateria, térmico —
                // era descartada pelo `DROP_OLDEST` sem nunca ter sido lida.
                stream.errorStream.collect { erro ->
                    val nosso = erro.name.toEnumOr(ErroDeStream.DESCONHECIDO)
                    ultimoErroDeStream.set(nosso)
                    Log.w(TAG, "Erro de stream: ${erro.name}")
                    _streamErrors.emit(ClaryonError.Glasses(nosso.codigo, nosso.frase))
                }
            }
            // **Não se coleta `videoStream` aqui.**
            //
            // `Stream.videoStream` é `Flow`, não `SharedFlow` — confirmado por
            // inspeção de `mwdat-camera-0.9.0`. Coletar aqui e de novo em
            // `withCamera` criava **duas assinaturas independentes** do mesmo
            // vídeo: o quadro era entregue e convertido duas vezes, no caminho
            // crítico, e a segunda coleta existia só para alimentar um contador
            // que ninguém lê fora do painel de diagnóstico.
            //
            // Num produto cuja meta de bateria é 12%/h em modo Ativo, decodificar
            // vídeo em dobro para atualizar um rótulo é caro do jeito errado. O
            // `FrameInfo` agora sai da coleta única de `withCamera`.
        }
    }

    /**
     * Abre a câmera de forma escopada (contrato [GlassesFacade]): entrega o fluxo
     * de frames a [block] e garante o encerramento ao final.
     */
    override suspend fun withCamera(
        config: CameraProfile,
        block: suspend (Flow<Frame>) -> Unit,
    ): Result<Unit> {
        val s = activeSession
            ?: return Result.failure(ClaryonError.Glasses("glasses.no_session", "Sessão não iniciada."))

        // Uma câmera/stream por vez (regra do DAT). Sem esta guarda, abrir uma
        // consulta de placa com o painel já streamando sobrescreveria
        // activeCamera/activeStream: o primeiro stream perderia toda referência
        // e ficaria impossível de parar por qualquer caminho de código.
        if (activeStream != null) {
            return Result.failure(
                ClaryonError.Glasses("glasses.stream_busy", "Já existe stream ativo — pare antes de abrir outro."),
            )
        }

        val camDeferred = CompletableDeferred<Camera?>()
        s.addCamera(config.toStreamConfiguration())
            .onSuccess { camDeferred.complete(it) }
            .onFailure { _, _ -> camDeferred.complete(null) }
        val camera = camDeferred.await()
            ?: return Result.failure(ClaryonError.Glasses("glasses.add_camera_failed", "addCamera falhou."))

        activeCamera = camera
        cameraEncerrada.set(false)
        val stream = camera.stream
        activeStream = stream
        observeStream(stream)
        stream.start()

        // **Vigia do primeiro frame.**
        //
        // Medido no MockDeviceKit com a permissão de câmera do DAT negada:
        // `addCamera` devolve sucesso, `stream.start()` não reclama, e **nenhum
        // frame chega, para sempre**. Sem este vigia, `withCamera` devolvia
        // `Success` e o consumidor ficava suspenso indefinidamente esperando uma
        // imagem que nunca vem — sem exceção, sem log, sem earcon.
        //
        // É o mesmo defeito de classe que `startSession` tinha: sucesso que não
        // significa sucesso. Num produto cuja única saída é áudio, o sintoma é
        // silêncio, e silêncio é indistinguível de aplicativo morto.
        val primeiroFrame = CompletableDeferred<Unit>()
        val comVigia = scope.launch {
            if (withTimeoutOrNull(PRAZO_PRIMEIRO_FRAME_MS) { primeiroFrame.await() } == null) {
                Log.w(
                    TAG,
                    "nenhum frame em ${PRAZO_PRIMEIRO_FRAME_MS}ms " +
                        "(causa: ${ultimoErroDeStream.get()?.name ?: "nenhuma no errorStream"}) " +
                        "— abortando o stream",
                )
                // Parar a câmera desfaz o fluxo e faz `block` retornar, em vez de
                // deixar o consumidor suspenso para sempre.
                pararCamera(camera)
            }
        }

        return try {
            // `conflate`: um frame por vez, descartando os do meio enquanto a
            // inferência do consumidor roda. Sem isso, todos os frames entram
            // numa fila que só cresce e a latência da inferência vira atraso
            // acumulado — a armadilha "todos os frames em fila".
            var contagem = 0L
            block(
                stream.videoStream
                    .onEach { frame ->
                        if (!primeiroFrame.isCompleted) primeiroFrame.complete(Unit)
                        // Diagnóstico de carona na coleta que já existe, em vez de
                        // uma segunda assinatura do mesmo vídeo.
                        _frameInfo.value = FrameInfo(frame.width, frame.height, ++contagem)
                    }
                    .map { it.toFrame() }
                    .conflate(),
            )
            if (primeiroFrame.isCompleted) {
                Result.success(Unit)
            } else {
                // **A causa, quando o `errorStream` a entregou.** `glasses.no_frames`
                // descreve o sintoma — "abriu e não entregou imagem" — e é a mesma
                // frase para hastes dobradas, permissão negada, bateria no fim e
                // superaquecimento. Com a coleta ligada, o chamador recebe qual
                // deles foi, e o earcon/fala deixa de ser genérico.
                val causa = ultimoErroDeStream.get()
                Result.failure(
                    if (causa != null) {
                        ClaryonError.Glasses(causa.codigo, causa.frase)
                    } else {
                        ClaryonError.Glasses(
                            "glasses.no_frames",
                            "A câmera abriu e não entregou imagem.",
                        )
                    },
                )
            }
        } finally {
            // Cancelar o vigia AQUI, não no caminho feliz: se `block` lançar, a
            // corrotina sobreviveria até o prazo e chamaria `camera.stop()` sobre
            // uma câmera já parada. Uma corrotina órfã por chamada que falha.
            comVigia.cancel()
            pararCamera(camera)
            // Soltar as referências aqui também: esperar só pelo CLOSED deixaria
            // activeStream apontando para um stream morto se o evento não vier.
            streamObserver?.cancel()
            clearStreamRefs()
        }
    }

    override suspend fun capturePhoto(): Result<PhotoData> {
        val stream = activeStream
            ?: return Result.failure(ClaryonError.Glasses("glasses.no_stream", "Sem stream ativo."))

        // Uma foto por vez: chamadas paralelas devolvem CaptureInProgress. Falhar
        // cedo com erro nosso é mais claro que o erro do SDK no meio da rajada.
        if (!capturaEmCurso.compareAndSet(false, true)) {
            return Result.failure(
                ClaryonError.Glasses("glasses.capture_in_progress", "Já existe captura em curso."),
            )
        }

        val deferred = CompletableDeferred<Result<PhotoData>>()
        stream.capturePhoto()
            .onSuccess { photo ->
                // No M2 não decodificamos a foto; o M6 trata HEIC/Bitmap + rotação.
                deferred.complete(Result.success(PhotoData(ByteArray(0), photo.toString())))
            }
            .onFailure { error, _ ->
                deferred.complete(
                    Result.failure(ClaryonError.Glasses("glasses.capture_failed", error.description)),
                )
            }
        return try {
            deferred.await()
        } finally {
            capturaEmCurso.set(false)
        }
    }

    // ── Limpeza ───────────────────────────────────────────────────────────────

    private fun clearStreamRefs() {
        activeStream = null
        activeCamera = null
        _stream.value = StreamStatus.STOPPED
    }

    /**
     * Solta as referências **e para a sessão no SDK**.
     *
     * Antes só limpava as referências locais. Quando isto passou a ser chamado no
     * estouro de prazo de [startSession], virou vazamento de verdade: a sessão
     * continuava viva dentro do SDK, os óculos seguiam transmitindo por Bluetooth
     * sem nenhum indicador, e o `createSession` seguinte encontrava a anterior
     * ativa — exatamente o que o comentário de [stopSession] adverte.
     *
     * `stop()` é idempotente o bastante para ser chamado sobre uma sessão que já
     * caiu; a alternativa (só limpar) deixa lixo que ninguém mais alcança.
     */
    private fun cleanupSession() {
        clearStreamRefs()
        runCatching { activeSession?.stop() }
        activeSession = null
        _session.value = SessionStatus.STOPPED
    }

    private companion object {
        const val TAG = "ClaryonField"

        /**
         * Teto para a sessão subir. Generoso porque envolve Bluetooth Classic e o
         * pareamento pode estar frio; curto o bastante para não travar o ciclo de
         * voz além do que um agente tolera esperando em silêncio.
         */
        const val PRAZO_DE_SESSAO_MS = 12_000L

        /**
         * Teto para o **primeiro** frame. Curto: a leitura de placa acontece com o
         * agente parado na frente do veículo, e uma consulta que demora mais que
         * isso já perdeu o momento. Frames seguintes não têm prazo — quem consome
         * decide.
         */
        const val PRAZO_PRIMEIRO_FRAME_MS = 6_000L
    }
}

// ── Mapeadores ────────────────────────────────────────────────────────────────

/** Converte o nome de um enum do DAT para o nosso enum equivalente, com fallback. */
private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

private fun CameraProfile.toStreamConfiguration(): StreamConfiguration =
    StreamConfiguration(
        videoQuality = when (quality) {
            CameraProfile.Quality.LOW -> VideoQuality.LOW
            CameraProfile.Quality.MEDIUM -> VideoQuality.MEDIUM
        },
        frameRate = frameRate,
    )

/** VideoFrame (DAT) → Frame (nosso). presentationTimeUs é µs → nanos. */
private fun com.meta.wearable.dat.camera.types.VideoFrame.toFrame(): Frame {
    val src = buffer.duplicate()
    val bytes = ByteArray(src.remaining())
    src.get(bytes)
    return Frame(
        width = width,
        height = height,
        timestampNanos = presentationTimeUs * 1_000,
        bytes = bytes,
    )
}
