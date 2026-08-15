package com.claryon.field.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.ActionOutcome
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.audio.RotaDeAudioPerdidaException
import com.claryon.common.Result
import com.claryon.evidence.EncryptedEvidenceVault
import com.claryon.field.BuildConfig
import com.claryon.field.agent.ClaryonIntentExecutor
import android.content.pm.PackageManager
import com.claryon.agent.BuscaDePar
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.agent.FalaDePosicao
import com.claryon.agent.PosicaoRelativa
import com.claryon.agent.Rumo
import com.claryon.field.auth.CofreDeSessaoCifrado
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.ConfigRealtime
import com.claryon.net.CanalDePosicoes
import com.claryon.net.ConsultaDePosicao
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.MapaDePares
import com.claryon.net.PublicadorDePosicaoSupabase
import com.claryon.field.agent.Identidade
import com.claryon.field.local.ProvedorDeLocal
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.MockDeviceController
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.VoiceCycle
import com.claryon.field.voice.VoiceOutput
import com.claryon.sync.SemTransporteGateway
import com.claryon.sync.SyncManager
import com.claryon.sync.TacticalDispatcher
import com.claryon.voice.AndroidOnDeviceStt
import com.claryon.voice.AndroidTts
import com.claryon.voice.EnergyVoiceActivityDetector
import com.claryon.voice.PcmAudio
import com.claryon.voice.PiperTts
import com.claryon.voice.TtsEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel do painel de diagnóstico (M2).
 *
 * Orquestra a fachada real do DAT ([DatGlassesFacade]) e, em builds DEBUG, o
 * [MockDeviceController] — permitindo exercitar registro → sessão → stream de
 * câmera **sem óculos e sem o app Meta AI**, com a câmera do celular como fonte.
 *
 * Expõe os StateFlows do SDK direto à UI. O `viewModelScope` é o escopo de vida
 * da fachada (coletas de registro/sessão/stream).
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val facade = DatGlassesFacade(viewModelScope)
    private val mock = if (BuildConfig.DEBUG) MockDeviceController(app) else null
    private var mockEnabled = false

    val registration = facade.registration
    val session = facade.session
    val streamState = facade.streamState
    val frameInfo = facade.frameInfo
    val deviceCount = facade.deviceCount

    val mockAvailable: Boolean = mock != null

    private val _mockStatus = MutableStateFlow("desligado")
    val mockStatus: StateFlow<String> = _mockStatus.asStateFlow()

    /** DEBUG: habilita o MDK, pareia o Ray-Ban simulado e aponta a câmera do celular. */
    fun enableMock() {
        val controller = mock ?: return
        if (mockEnabled) return
        mockEnabled = controller.enableWithPhoneCameraFeed()
        _mockStatus.value =
            if (mockEnabled) "pareado (Ray-Ban simulado + câmera do celular)" else "falha ao parear"
    }

    fun startSession() {
        viewModelScope.launch { facade.startSession() }
    }

    fun startCamera() = facade.startCameraStream()

    fun stopCamera() = facade.stopCameraStream()

    // ── Áudio HFP (M3) ────────────────────────────────────────────────────────

    // Em DEBUG permite fallback para o dispositivo padrão (o emulador/MDK não têm
    // SCO). Em produto, só rota HFP dos óculos.
    private val audio = GlassesAudioManagerImpl(app, allowFallbackToDefault = BuildConfig.DEBUG)

    private val _audioStatus = MutableStateFlow("—")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    /** Ciclo de eco: rotear → gravar 3 s → reproduzir → liberar. Exige fone HFP real. */
    fun echo() {
        // Dispatchers.Default: o acúmulo amostra a amostra e a reprodução não
        // podem rodar na Main (viewModelScope é Main.immediate por padrão).
        viewModelScope.launch(Dispatchers.Default) {
            // iniciar() falhou ⇒ nenhum usuário foi contado ⇒ NÃO chamar liberar()
            // (chamada extra desequilibraria a contagem e derrubaria a rota de
            // quem estivesse capturando em paralelo).
            val prova = when (val r = audio.iniciar()) {
                is Result.Failure -> {
                    _audioStatus.value = "sem rota: ${r.error.message}"
                    return@launch
                }
                is Result.Success -> r.value
            }
            val rota = audio.rotaAtual // capturar ANTES de liberar() (que zera a rota)
            try {
                _audioStatus.value = "gravando 3 s… (rota $rota)"
                val buffer = ArrayList<Short>()
                withTimeoutOrNull(3_000) {
                    audio.microfonePcm(prova).collect { chunk -> chunk.forEach { buffer.add(it) } }
                }
                val pcm = buffer.toShortArray()
                _audioStatus.value = "reproduzindo ${pcm.size} amostras… (rota $rota)"
                audio.reproduzir(pcm, 16_000)
                _audioStatus.value = "eco concluído · ${pcm.size} amostras · rota $rota"
            } catch (e: Exception) {
                // Ex.: RECORD_AUDIO negada, AudioRecord não inicializa. Falha nunca é silêncio.
                _audioStatus.value = "falha no eco: ${e.message}"
            } finally {
                audio.liberar()
            }
        }
    }

    // ── Ciclo de voz — cérebro + saída (M4) ────────────────────────────────────

    private val router = DeterministicIntentRouter()
    private val stt = AndroidOnDeviceStt(app)

    /** Fallback do TTS: sempre disponível, voz do sistema. */
    private val tts = AndroidTts(app)

    /**
     * TTS neural (Piper) resolvido sob demanda — a primeira chamada copia o
     * `espeak-ng-data` e carrega o modelo ONNX, o que leva alguns segundos e não
     * pode acontecer no construtor do ViewModel.
     */
    private var piper: PiperTts? = null
    private var piperResolvido = false
    private val ttsMutex = Mutex()

    private suspend fun sintetizar(texto: String): PcmAudio? {
        val engine: TtsEngine = ttsMutex.withLock {
            if (!piperResolvido) {
                piper = Modelos.piper(getApplication())
                piperResolvido = true
                Log.i(TAG, "TTS = ${if (piper != null) "Piper (neural, on-device)" else "AndroidTts (fallback)"}")
            }
            piper ?: tts
        }
        return (engine.synthesize(texto) as? Result.Success)?.value
    }

    private val _commandStatus = MutableStateFlow("—")
    val commandStatus: StateFlow<String> = _commandStatus.asStateFlow()

    // ── Executor de intenções + saída sonora ──────────────────────────────────
    // É aqui que core-evidence, core-sync e core-sound — prontos e testados, mas
    // até agora nunca importados pelo código de produção — entram no caminho.

    private val cofre = EncryptedEvidenceVault(app)

    private val local = ProvedorDeLocal(app)

    // ── Sessão e rede (C2) ────────────────────────────────────────────────────

    /** `false` quando `local.properties` não trouxe o projeto: sem servidor, sem C2. */
    val redeConfigurada: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private val configRede = ConfigRealtime(
        projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    val autenticacao = AutenticacaoSupabase(
        config = configRede,
        cofre = CofreDeSessaoCifrado(app),
    )

    private val consulta = ConsultaDePosicao(
        config = configRede,
        // `runBlocking` NÃO: a renovação de token não pode travar o ciclo de voz.
        // O token corrente é lido de forma síncrona a partir do cofre, e a
        // renovação acontece antes, no `publicarPosicao` periódico.
        tokenDeSessao = { tokenCorrente },
    )

    @Volatile
    private var tokenCorrente: String? = null

    private val publicador = PublicadorDePosicaoSupabase(
        config = configRede,
        tokenDeSessao = { autenticacao.tokenValido()?.also { tokenCorrente = it } },
    )

    // ── Mapa da guarnição (C5) ────────────────────────────────────────────────

    private val canalDePosicoes = CanalDePosicoes(publicador) { System.currentTimeMillis() }

    private val _estadoDoMapa = MutableStateFlow(
        EstadoDoMapa.indisponivel("Abra o mapa para ver a guarnição."),
    )
    val estadoDoMapa: StateFlow<EstadoDoMapa> = _estadoDoMapa.asStateFlow()

    private var bombaDoMapa: Job? = null

    /**
     * Chamado pelo `ON_START` da tela do mapa.
     *
     * É aqui que a regra de bateria vira código: **a assinatura do canal de
     * posições nasce com a tela e morre com ela**. Numa guarnição de oito,
     * mantê-la aberta o turno inteiro seria 8 × 8 de tráfego permanente para uma
     * tela fechada 95% do tempo.
     *
     * E a reciprocidade é pré-condição, não convenção: [CanalDePosicoes.assinar]
     * recusa se a publicação própria não estiver ativa. Quem vê é visto.
     */
    fun abrirMapa() {
        if (bombaDoMapa != null) return
        bombaDoMapa = viewModelScope.launch {
            if (!redeConfigurada || autenticacao.tokenValido()?.also { tokenCorrente = it } == null) {
                _estadoDoMapa.value =
                    EstadoDoMapa.indisponivel("Sem sessão. Entre para ver a guarnição.")
                return@launch
            }
            // Publicar ANTES de assinar. A ordem é a reciprocidade: sem a própria
            // posição no ar, `assinar` recusa — e recusa é o comportamento certo,
            // não um obstáculo a contornar.
            publicarPosicao()

            if (!canalDePosicoes.assinar(TALK_GROUP_DEMO)) {
                _estadoDoMapa.value = EstadoDoMapa.indisponivel(
                    "Sem publicar sua posição, não é possível ver a dos outros.",
                )
                return@launch
            }

            while (true) {
                val minha = local.ultimaPosicao()
                _estadoDoMapa.value = if (minha == null) {
                    // Sem posição própria não há de onde medir distância nem rumo.
                    // Mostrar os pares sem isso seria uma lista de nomes.
                    EstadoDoMapa.indisponivel("Sem sinal de GPS. Posições indisponíveis.")
                } else {
                    MapaDePares.montar(
                        canalDePosicoes.marcadores(minha.latitude, minha.longitude),
                        assinado = canalDePosicoes.assinando(),
                    )
                }
                // Redesenhar por tempo, e não só por pacote recebido: o
                // esmaecimento depende do relógio, não da chegada de dado novo. Um
                // par que parou de publicar tem de esmaecer sozinho — é
                // exatamente esse o caso que a regra existe para cobrir.
                kotlinx.coroutines.delay(INTERVALO_DE_REDESENHO_MS)
                publicarPosicao()
            }
        }
    }

    /** Chamado pelo `ON_STOP` da tela. Fecha a assinatura e descarta o espelho. */
    fun fecharMapa() {
        bombaDoMapa?.cancel()
        bombaDoMapa = null
        // `viewModelScope`, não o escopo cancelado: `desassinar` precisa rodar até
        // o fim para avisar o servidor. Lançar no job que acabou de ser cancelado
        // deixaria a assinatura aberta do outro lado.
        viewModelScope.launch { canalDePosicoes.desassinar() }
        _estadoDoMapa.value = EstadoDoMapa.indisponivel("Abra o mapa para ver a guarnição.")
    }

    /**
     * Diz em voz alta o que está morto por falta de permissão.
     *
     * Isto fechava um buraco entre a documentação e o código: o catálogo prometia
     * que "toda negativa tem uma frase própria, dita em voz alta, e não um
     * silêncio educado", e `avisoFalado` só era chamado pelos testes. Na prática
     * nenhuma negativa produzia som — o agente com localização negada dava o
     * comando, nada acontecia, e ele concluía que o produto é ruim.
     *
     * Dito **uma vez por abertura**, no nível informativo: repetir a cada comando
     * viraria ruído que o agente aprende a ignorar, e o Modo Tático o suprime
     * durante ocorrência, que é quando ele mais atrapalharia.
     */
    fun anunciarCapacidadesPerdidas() {
        if (jaAnunciouPermissoes) return
        jaAnunciouPermissoes = true

        val concedidas = PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter {
                getApplication<Application>().checkSelfPermission(it) ==
                    PackageManager.PERMISSION_GRANTED
            }
            .toSet()

        val aviso = PermissoesEssenciais.avisoFalado(PermissoesEssenciais.avaliar(concedidas))
            ?: return

        saida.emitir(Utterance.SinalizarEFalar(Earcon.FALHA, aviso, Priority.INFORMATIVO))
    }

    private var jaAnunciouPermissoes = false

    /**
     * Sobe a posição própria. É ela que dá ao servidor o ponto de onde medir a
     * distância na consulta por voz — sem publicar, C2 responde "não sei de onde
     * medir", que é honesto mas inútil.
     */
    suspend fun publicarPosicao() {
        if (!redeConfigurada) return
        val c = local.ultimaPosicao() ?: return
        publicador.publicar(c.latitude, c.longitude, c.precisaoM, null)
    }

    /**
     * Traduz a resposta do servidor em [BuscaDePar].
     *
     * O ramo de [BuscaDePar.PosicaoPropriaVelha] é o que impede o modo de falha
     * mais traiçoeiro: a distância vem medida da **minha última posição
     * publicada**, e se ela é de meia hora atrás o número está errado por
     * quilômetros sem nada no payload denunciando.
     */
    private suspend fun localizar(indicativo: String): BuscaDePar {
        if (!redeConfigurada || autenticacao.tokenValido()?.also { tokenCorrente = it } == null) {
            return BuscaDePar.Indisponivel
        }
        val r = consulta.onde(indicativo).getOrElse { return BuscaDePar.Indisponivel }
            ?: return BuscaDePar.NaoLocalizado

        if (r.idadeDoSolicitanteS > FalaDePosicao.IDADE_MAXIMA_S) {
            return BuscaDePar.PosicaoPropriaVelha
        }
        return BuscaDePar.Encontrado(
            PosicaoRelativa(
                indicativo = r.indicativo,
                distanciaM = r.distanciaM,
                rumo = r.azimuteGraus?.let(Rumo::deGraus),
                emMovimento = (r.velocidadeMs ?: 0f) > 1.0f,
                idadeS = r.idadeS,
            ),
        )
    }

    /**
     * Sem `core-net`, o despacho **sempre** cai na fila durável e o agente ouve
     * "Sem rede. Na fila." — que é a verdade. Quando o transporte existir, troca-se
     * [SemTransporteGateway] aqui e nada mais muda.
     */
    private val despachante = TacticalDispatcher(
        outbox = SyncManager.outbox(app),
        gateway = SemTransporteGateway,
        novoId = { m -> "${m.agentId}-${System.currentTimeMillis()}" },
        agora = { System.currentTimeMillis() },
    )

    private val saida = VoiceOutput(
        scope = viewModelScope,
        sintetizar = { texto -> sintetizar(texto) },
        reproduzir = { pcm, sr -> reproduzirComRota(pcm, sr) },
    )

    private val executor = ClaryonIntentExecutor(
        cofre = cofre,
        despachante = despachante,
        // Identidade de demonstração; no produto vem do onboarding/credencial.
        identidade = Identidade(
            agentId = "007",
            unitId = "GTA-3",
            vehiclePrefix = "GTA-3",
            destinoPadrao = "COPOM",
        ),
        agora = { System.currentTimeMillis() },
        // Modo Ocorrência liga o Modo Tático da fila: informativo é suprimido.
        aoTrocarModo = { modo -> saida.modoTatico(modo == ModoOperacao.OCORRENCIA) },

        minhaPosicao = { local.ultimaPosicao() },
        permissaoDeLocal = { local.temPermissao() },

        // C2 fechado: sai por `ConsultaDePosicao` com o token da sessão. Sem
        // login, sem rede ou sem servidor configurado, devolve `Indisponivel` — o
        // agente ouve "Consulta indisponível." em vez de "Alfa Dois não
        // localizado", que afirmaria que o companheiro sumiu.
        localizarPar = { indicativo -> localizar(indicativo) },
    )


    /** Reprodução com rota garantida — sobe o HFP, toca, e devolve a rota. */
    private suspend fun reproduzirComRota(pcm: ShortArray, sampleRateHz: Int) {
        when (audio.iniciar()) {
            is Result.Success -> try {
                audio.reproduzir(pcm, sampleRateHz)
            } finally {
                audio.liberar()
            }
            // Falha nunca é silêncio — mas aqui o canal de aviso É o som, então
            // só resta registrar. O status da UI já mostra a causa.
            is Result.Failure -> Log.w(TAG, "sem rota de áudio para reproduzir")
        }
    }

    /** Comando por TEXTO (bypassa o STT): roteador → resposta lacônica → TTS. */
    fun runCommand(text: String) {
        viewModelScope.launch { processar(text) }
    }

    /**
     * Ciclo de voz REAL no aparelho: STT on-device (auto-capturador) → roteador
     * → TTS. Fecha falar→transcrever→responder sem NDK. Exige pt-BR baixado
     * (indisponível no emulador → mensagem clara).
     */
    fun falarComando() {
        viewModelScope.launch {
            if (!stt.isAvailable()) {
                _commandStatus.value = "STT on-device indisponível (baixe o pt-BR nas configs de voz)"
                return@launch
            }
            // O SpeechRecognizer captura pela fonte de comunicação do sistema.
            // SEM rotear o HFP antes, ele grava pelo microfone do CELULAR, que é
            // omnidirecional — e aí a fala do interlocutor entra na transcrição.
            // Transcrever terceiros é proibição absoluta do projeto; o
            // beamforming dos óculos é o que garante que só o agente é
            // transcrito. Por isso a rota é pré-condição, não otimização.
            when (val rota = audio.iniciar()) {
                is Result.Failure -> {
                    _commandStatus.value = "sem rota HFP — captura cancelada (${rota.error.message})"
                    audio.liberar()
                    return@launch
                }
                is Result.Success -> Unit
            }
            try {
                _commandStatus.value = "ouvindo… (rota ${audio.rotaAtual})"
                when (val r = stt.recognizeOnce()) {
                    is Result.Success -> processar(r.value.text)
                    is Result.Failure -> _commandStatus.value = "STT: ${r.error.message}"
                }
            } finally {
                audio.liberar()
            }
        }
    }

    /**
     * Roteia o texto, **executa a ação**, e só então emite a resposta.
     *
     * A ordem é a correção central do produto: antes, a frase vinha de
     * `OperationalResponses.para(intent)` — escolhida a partir do comando e dita
     * sem que nada tivesse acontecido.
     */
    private suspend fun processar(text: String) {
        val intent = router.route(text)
        val outcome = executor.execute(intent)
        val utterance = utteranceFor(outcome)
        saida.emitir(utterance)
        aoResultado(outcome)
        _commandStatus.value = descrever(text, intent::class.simpleName, outcome, utterance)
    }

    // ── C4: gravação de evidência de verdade ──────────────────────────────────

    private var gravacaoJob: Job? = null

    /**
     * Efeitos que acompanham um resultado. Abrir o cofre não basta: sem alguém
     * alimentando [ClaryonIntentExecutor.anexarEvidencia], a "gravação" seria um
     * manifesto vazio — o mesmo tipo de mentira que este marco veio corrigir.
     */
    private fun aoResultado(outcome: ActionOutcome) {
        when (outcome) {
            is ActionOutcome.GravacaoIniciada -> iniciarCapturaDeEvidencia()
            is ActionOutcome.GravacaoEncerrada -> {
                gravacaoJob?.cancel()
                gravacaoJob = null
            }
            else -> Unit
        }
    }

    private fun iniciarCapturaDeEvidencia() {
        gravacaoJob?.cancel()
        gravacaoJob = viewModelScope.launch(Dispatchers.Default) {
            val prova = when (val r = audio.iniciar()) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    saida.emitir(utteranceFor(ActionOutcome.Falhou(FalhaOperacional.SEM_ROTA_DE_AUDIO)))
                    return@launch
                }
            }
            try {
                audio.microfonePcm(prova).collect { chunk ->
                    executor.anexarEvidencia(chunk.paraBytesLE())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Evidência interrompida no meio é grave: o manifesto parcial
                // sobrevive (o cofre grava a cada segmento), mas o agente tem de
                // saber que parou de gravar.
                Log.e(TAG, "captura de evidência interrompida", e)
                saida.emitir(utteranceFor(ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL)))
            } finally {
                audio.liberar()
            }
        }
    }

    /** PCM 16-bit para bytes little-endian, como o cofre armazena. */
    private fun ShortArray.paraBytesLE(): ByteArray {
        val out = ByteArray(size * 2)
        for (i in indices) {
            out[i * 2] = (this[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((this[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun descrever(
        entrada: String,
        intent: String?,
        outcome: ActionOutcome,
        utterance: Utterance,
    ): String {
        val saidaTexto = when (utterance) {
            is Utterance.Falar -> "\"${utterance.texto}\""
            is Utterance.Sinalizar -> "[${utterance.earcon.name}]"
            is Utterance.SinalizarEFalar -> "[${utterance.earcon.name}] + \"${utterance.texto}\""
        }
        return "\"$entrada\" → $intent → ${outcome::class.simpleName} → $saidaTexto"
    }

    /**
     * Ciclo de voz COMPLETO (push-to-talk): captura HFP → VAD → earcon → STT
     * (whisper se o modelo estiver em filesDir; senão degrada) → roteador →
     * resposta → TTS → reprodução. É o [VoiceCycle] com os engines reais.
     */
    fun cicloDeVoz() {
        // Fora da Main: o VAD calcula RMS de 50 janelas/s e o STT carrega um
        // modelo de ~75 MB. Na Main isso congela a UI e arrisca ANR justamente
        // na janela em que a meta é responder em ≤ 2,0 s.
        viewModelScope.launch(Dispatchers.Default) {
            // Duas capturas simultâneas abririam dois AudioRecord na mesma fonte
            // de comunicação — a segunda falha ao inicializar ou rouba o fluxo da
            // primeira, e o que se perde é evidência. O caminho definitivo é uma
            // fonte única com fan-out (evidência + STT + PTT bebendo do mesmo
            // AudioRecord); é peça do C1 e será construída com o transporte.
            // Até lá, o acesso é exclusivo e a recusa é audível.
            if (gravacaoJob?.isActive == true) {
                saida.emitir(utteranceFor(ActionOutcome.Falhou(FalhaOperacional.GRAVACAO_JA_ATIVA)))
                _commandStatus.value = "ciclo: gravação de evidência em curso — encerre antes"
                return@launch
            }
            val prova = when (val rota = audio.iniciar()) {
                is Result.Failure -> {
                    // Falha nunca é silêncio: tenta o earcon mesmo assim (a
                    // reprodução reergue a rota por conta própria; se o áudio
                    // estiver inteiramente fora, resta o status na tela).
                    saida.emitir(utteranceFor(ActionOutcome.Falhou(FalhaOperacional.SEM_ROTA_DE_AUDIO)))
                    _commandStatus.value = "ciclo: sem rota de áudio (${rota.error.message})"
                    return@launch
                }
                is Result.Success -> rota.value
            }
            // Assets do APK primeiro, filesDir depois — ver [Modelos].
            val whisper = Modelos.whisper(getApplication())
            val origem = Modelos.fonteDoWhisper(getApplication())?.toString() ?: "indisponível"
            _commandStatus.value = "ciclo: ouvindo… (STT=$origem)"

            val cycle = VoiceCycle(
                pcmInput = { audio.microfonePcm(prova) },
                vad = EnergyVoiceActivityDetector(sampleRateHz = 16_000),
                sttFn = { pcm, sr ->
                    (whisper?.transcribe(pcm, sr) as? Result.Success)?.value?.text.orEmpty()
                },
                router = router,
                executor = executor,
                emitir = { utterance -> saida.emitir(utterance) },
                sampleRateHz = 16_000,
            )
            // Timeout e exceção são coisas DIFERENTES e não podem virar a mesma
            // mensagem: colapsar os dois em "sem fala detectada" mandou o
            // operador procurar o problema no lugar errado (a causa real era
            // RECORD_AUDIO negada). CancellationException tem de propagar —
            // engoli-la faria o corpo seguir depois do escopo morto.
            val resultado = try {
                Resultado.Ok(withTimeoutOrNull(8_000) { cycle.runOnce() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ciclo de voz falhou", e)
                Resultado.Erro(e)
            } finally {
                audio.liberar()
                whisper?.release()
            }
            _commandStatus.value = when (resultado) {
                is Resultado.Erro -> {
                    // Todo caminho de erro tem earcon. A rota caída tem causa
                    // própria: é o cenário operacional mais comum (óculos
                    // dobrados, fone desligado) e o agente precisa distinguir.
                    val falha = if (resultado.causa is RotaDeAudioPerdidaException) {
                        FalhaOperacional.SEM_ROTA_DE_AUDIO
                    } else {
                        FalhaOperacional.INTERNA
                    }
                    saida.emitir(utteranceFor(ActionOutcome.Falhou(falha)))
                    "ciclo: FALHA — ${resultado.causa.message ?: resultado.causa::class.simpleName}"
                }
                is Resultado.Ok -> resultado.valor?.let { r ->
                    aoResultado(r.outcome)
                    descrever(r.transcricao, r.intent::class.simpleName, r.outcome, r.utterance)
                } ?: run {
                    // Timeout sem fala não é erro, mas também não pode ser mudo:
                    // o agente que apertou e falou precisa saber que não pegou.
                    saida.emitir(utteranceFor(ActionOutcome.NaoEntendi))
                    "ciclo: sem fala detectada (8 s)"
                }
            }
        }
    }

    override fun onCleared() {
        // Encerramento na ordem do contrato: câmera → sessão → áudio → engines.
        // Sem parar a sessão, o stream dos óculos segue transmitindo por
        // Bluetooth depois que a tela morre, sem nenhum indicador — e um novo
        // DatGlassesFacade tentaria createSession com a anterior ainda viva.
        gravacaoJob?.cancel()
        facade.stopCameraStream()
        facade.stopSession()
        audio.liberarTudo()
        tts.liberar()
        saida.limpar()
        // Piper segura um runtime ONNX nativo; sem release, o modelo vaza.
        piper?.let { p -> kotlinx.coroutines.runBlocking { p.release() } }
        mock?.disable()
        // A gravação não é finalizada aqui de propósito: `onCleared` não é
        // suspensa e o `viewModelScope` já está morrendo. Não há perda — o cofre
        // grava manifesto parcial a cada segmento, então o que ficou no disco
        // continua com cadeia de custódia demonstrável e verificável.
        super.onCleared()
    }

    /** Distingue "rodou" de "explodiu" no ciclo de voz. */
    private sealed interface Resultado {
        data class Ok(val valor: VoiceCycle.Resultado?) : Resultado
        data class Erro(val causa: Throwable) : Resultado
    }

    private companion object {
        /**
         * Talk group da demonstração. No produto vem do cadastro, junto da
         * identidade — um agente pertence a um ou mais grupos, e o servidor já
         * impõe isso por RLS.
         */
        const val TALK_GROUP_DEMO = "demo"

        /**
         * 5 s. Curto o bastante para o esmaecimento acompanhar o relógio, longo o
         * bastante para não custar bateria com a tela aberta — e a tela do mapa
         * fica aberta pouco tempo, por desenho.
         */
        const val INTERVALO_DE_REDESENHO_MS = 5_000L

        const val TAG = "ClaryonField"
    }
}
