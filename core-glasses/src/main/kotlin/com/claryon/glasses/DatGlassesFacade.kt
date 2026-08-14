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
import kotlinx.coroutines.flow.map
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

    override suspend fun startSession(): Result<Unit> {
        if (activeSession != null) return Result.success(Unit)

        val deferred = CompletableDeferred<Result<Unit>>()
        Wearables.createSession(deviceSelector)
            .onSuccess { created ->
                activeSession = created
                observeSession(created) // assinar ANTES de start(), para não perder transições
                _session.value = SessionStatus.STARTING
                created.start() // retorna Unit; o resultado é observado em session.state/errors
                deferred.complete(Result.success(Unit))
            }
            .onFailure { error, _ ->
                // Falha nunca é silêncio: registra para o diagnóstico.
                Log.e(TAG, "createSession falhou: ${error.description}")
                deferred.complete(
                    Result.failure(ClaryonError.Glasses("glasses.session_failed", error.description)),
                )
            }
        return deferred.await()
    }

    /**
     * Encerra a sessão na ordem do contrato: **câmera/stream primeiro, sessão
     * depois**. Sem isto, sair da tela cancela apenas os coletores: a sessão e o
     * stream continuam vivos no SDK, os óculos seguem transmitindo por Bluetooth
     * sem nenhum indicador, e um `createSession` seguinte encontra a anterior
     * ativa.
     *
     * `DeviceSession.stop()` confirmado no artefato `mwdat-core:0.9.0`.
     * Sessão parada é terminal: para voltar, criar uma **nova** (não reviver).
     */
    fun stopSession() {
        activeCamera?.stop()
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
        activeCamera?.stop()
    }

    private fun observeStream(stream: Stream) {
        streamObserver?.cancel()
        streamObserver = scope.launch {
            launch {
                stream.state.collect { state ->
                    _stream.value = state.name.toEnumOr(StreamStatus.STOPPED)
                    if (_stream.value == StreamStatus.CLOSED) clearStreamRefs()
                }
            }
            launch {
                var count = 0L
                stream.videoStream.collect { frame ->
                    // Frames só chegam em STREAMING. No M2 mostramos metadados
                    // (dimensões + contagem) como prova de que o pipeline vive.
                    _frameInfo.value = FrameInfo(frame.width, frame.height, ++count)
                }
            }
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
        val stream = camera.stream
        activeStream = stream
        observeStream(stream)
        stream.start()
        return try {
            // `conflate`: um frame por vez, descartando os do meio enquanto a
            // inferência do consumidor roda. Sem isso, todos os frames entram
            // numa fila que só cresce e a latência da inferência vira atraso
            // acumulado — a armadilha "todos os frames em fila".
            block(stream.videoStream.map { it.toFrame() }.conflate())
            Result.success(Unit)
        } finally {
            camera.stop()
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

    private fun cleanupSession() {
        clearStreamRefs()
        activeSession = null
        _session.value = SessionStatus.STOPPED
    }

    private companion object {
        const val TAG = "ClaryonField"
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
