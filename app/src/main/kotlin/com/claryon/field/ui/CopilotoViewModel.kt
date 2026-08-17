package com.claryon.field.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.pm.PackageManager
import com.claryon.agent.ActionOutcome
import com.claryon.agent.BuscaDePar
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.FalaDePosicao
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PosicaoRelativa
import com.claryon.agent.Rumo
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.audio.RotaDeAudioPerdidaException
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Result
import com.claryon.evidence.EncryptedEvidenceVault
import com.claryon.field.agent.AnexoDeEvidencia
import com.claryon.field.agent.ClaryonIntentExecutor
import com.claryon.field.agent.Identidade
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.audio.SaidaUnica
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.local.ProvedorDeLocal
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.VoiceCycle
import com.claryon.net.ConsultaDePosicao
import com.claryon.sync.SemTransporteGateway
import com.claryon.sync.SyncManager
import com.claryon.sync.TacticalDispatcher
import com.claryon.voice.AndroidOnDeviceStt
import com.claryon.voice.EnergyVoiceActivityDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **O copiloto: ciclo de voz, execução de intenção e gravação de evidência.**
 *
 * ## Por que evidência mora aqui, e não num `EvidenciaViewModel` separado
 *
 * A auditoria propôs três pedaços — mapa, copiloto, evidência. Os dois primeiros
 * separam; o terceiro **não deve**, e insistir nele produziria um defeito real.
 *
 * `executor` guarda o handle da gravação em curso (`gravacaoAtual`) e o protege
 * com mutex próprio. Quem ABRE a gravação é o ciclo de voz, via
 * `Intent.IniciarGravacao`; quem a ALIMENTA é a captura de evidência, via
 * `anexarEvidencia`; quem a FECHA é qualquer um dos dois. `gravacaoJob` é a
 * exclusão mútua entre eles: o ciclo de voz se recusa a rodar enquanto uma
 * gravação está ativa, porque não pode roubar o microfone dela.
 *
 * Ou seja: copiloto e evidência não são dois subsistemas que compartilham uma
 * dependência — são **duas metades da mesma máquina de estado**. Duplicar o
 * executor em dois ViewModels daria dois `gravacaoAtual`: a instância A abriria
 * o manifesto e a instância B devolveria `SEM_GRAVACAO` para todo bloco de
 * áudio, produzindo manifesto aberto e vazio. Exatamente a mentira que o cofre
 * de evidência foi construído para não contar.
 *
 * A evidência é um **modo** do copiloto, não um vizinho dele.
 *
 * ## O que ficou para trás, em `DiagnosticsViewModel`
 *
 * Só diagnóstico de verdade: a fachada do DAT, o MockDeviceKit e o eco de áudio.
 */
class CopilotoViewModel(app: Application) : AndroidViewModel(app) {

    private val audio = AudioDoAgente.de(app)

    private val redeConfigurada: Boolean = SessaoDoAgente.redeConfigurada

    private val consulta = ConsultaDePosicao(
        config = SessaoDoAgente.config,
        // `runBlocking` NÃO: a renovação de token não pode travar o ciclo de voz.
        // Lê o cache já validado; quem renova é `SessaoDoAgente.tokenValido`.
        tokenDeSessao = { SessaoDoAgente.tokenCorrente },
    )

    // ── Ciclo de voz — cérebro + saída (M4) ────────────────────────────────────

    private val router = DeterministicIntentRouter()
    private val stt = AndroidOnDeviceStt(app)

    private val _commandStatus = MutableStateFlow("—")
    val commandStatus: StateFlow<String> = _commandStatus.asStateFlow()

    // ── Executor de intenções + saída sonora ──────────────────────────────────
    // É aqui que core-evidence, core-sync e core-sound — prontos e testados, mas
    // até agora nunca importados pelo código de produção — entram no caminho.

    private val cofre = EncryptedEvidenceVault(app)

    private val local = ProvedorDeLocal(app)


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
     * Traduz a resposta do servidor em [BuscaDePar].
     *
     * O ramo de [BuscaDePar.PosicaoPropriaVelha] é o que impede o modo de falha
     * mais traiçoeiro: a distância vem medida da **minha última posição
     * publicada**, e se ela é de meia hora atrás o número está errado por
     * quilômetros sem nada no payload denunciando.
     */
    private suspend fun localizar(indicativo: String): BuscaDePar {
        if (!redeConfigurada || SessaoDoAgente.tokenValido(getApplication()) == null) {
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
     * `by lazy` + aquecimento em IO, e não campo direto.
     *
     * `SyncManager.outbox(app)` chama `context.filesDir`, e o
     * `ensurePrivateDirExists` do framework faz E/S de disco na primeira
     * chamada: **965 ms na thread principal**, medidos pelo StrictMode com a
     * cadeia `DiagnosticsViewModel.<init>` → `SyncManager.outbox` →
     * `ContextImpl.getFilesDir`. Um construtor de ViewModel roda durante a
     * composição, então isso era quase um segundo de tela parada.
     *
     * O `filesDir` fica cacheado pelo framework depois da primeira resolução, e
     * é o `init` abaixo que paga essa primeira em `Dispatchers.IO`. Sem o `by
     * lazy` o campo seria construído na hora, no construtor, e o aquecimento
     * chegaria tarde demais.
     */
    private val despachante by lazy {
        TacticalDispatcher(
            outbox = SyncManager.outbox(getApplication()),
            gateway = SemTransporteGateway,
            novoId = { m -> "${m.agentId}-${System.currentTimeMillis()}" },
            agora = { System.currentTimeMillis() },
        )
    }

    init {
        // Paga a primeira resolução do `filesDir` fora da Main. Ver `despachante`.
        viewModelScope.launch(Dispatchers.IO) { despachante }
    }

    // Dono único da saída (`SaidaUnica`) — a mesma fila que o rádio usa para os
    // próprios earcons. Antes deste ViewModel construía a sua própria
    // `VoiceOutput`: um P1 do rádio não enxergava a fala do copiloto porque
    // eram duas filas de prioridade separadas, cada uma com o próprio
    // `playingPriority`. Unificar é o que faz `deveInterromper` funcionar
    // entre as duas fontes — e é também o que passa a registrar a janela do
    // supressor de eco para a fala sintetizada, que antes vazava para o
    // pré-roll do rádio sem proteção nenhuma. Ver `SaidaUnica`.
    private val saida = SaidaUnica.de(app)

    private val executor by lazy {
        ClaryonIntentExecutor(
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
        // A taxa vem do dono único da rota — o mesmo objeto que abre o
        // `AudioRecord` que alimenta o cofre. Repetir o literal aqui recriaria a
        // coincidência que este parâmetro existe para eliminar.
        taxaDeAmostragemHz = { audio.taxaDeAmostragemHz },

        minhaPosicao = { local.ultimaPosicao() },
        permissaoDeLocal = { local.temPermissao() },

        // C2 fechado: sai por `ConsultaDePosicao` com o token da sessão. Sem
        // login, sem rede ou sem servidor configurado, devolve `Indisponivel` — o
        // agente ouve "Consulta indisponível." em vez de "Alfa Dois não
        // localizado", que afirmaria que o companheiro sumiu.
        localizarPar = { indicativo -> localizar(indicativo) },
        )
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
                    // NÃO chamar `audio.liberar()`: `iniciar()` falhou e não
                    // contou usuário nenhum. Chamar mesmo assim decrementa a
                    // contagem compartilhada por um usuário que nunca existiu —
                    // e o próximo `iniciar()` bem-sucedido de QUALQUER caminho
                    // (o rádio, por exemplo) herda esse déficit: o `liberar()`
                    // dele derruba a rota SCO por baixo de quem ainda captura.
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
                    // O retorno de `anexarEvidencia` era **descartado**. Com o
                    // disco cheio, o cofre falhava cinquenta vezes por segundo, o
                    // áudio ia embora, e o agente seguia acreditando que estava
                    // gravando — falha silenciosa, que é a que o produto proíbe.
                    val r = executor.anexarEvidencia(chunk.paraBytesLE())
                    if (r == AnexoDeEvidencia.SEM_ESPACO || r == AnexoDeEvidencia.FALHOU) {
                        // `collect` é `crossinline`: não há retorno não-local. Sair
                        // por exceção é o que encerra o fluxo de verdade — insistir
                        // só desgasta a flash e prolonga a mentira.
                        throw CofreRecusouException(r)
                    }
                }
            } catch (e: CofreRecusouException) {
                Log.e(TAG, "cofre recusou bloco de evidência: ${e.motivo}")
                val falha = when (e.motivo) {
                    AnexoDeEvidencia.SEM_ESPACO -> FalhaOperacional.SEM_ESPACO
                    else -> FalhaOperacional.COFRE_INDISPONIVEL
                }
                // Fecha o cofre para que o manifesto registre onde a gravação parou.
                // Um manifesto aberto para sempre é uma custódia que ninguém fecha.
                executor.execute(Intent.EncerrarGravacao)
                saida.emitir(utteranceFor(ActionOutcome.Falhou(falha)))
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

    /** Sai do `collect` quando o cofre para de aceitar. Nunca escapa deste arquivo. */
    private class CofreRecusouException(val motivo: AnexoDeEvidencia) : Exception()

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
    /**
     * `true` enquanto o ciclo está ouvindo ou pensando.
     *
     * Derivado do ciclo e não do toque: um estado que muda no toque diria
     * "ouvindo" mesmo quando a captura falhou ao abrir — que é exatamente o
     * instante em que o agente precisa saber a verdade.
     */
    private val _copilotoOcupado = MutableStateFlow(false)
    val copilotoOcupado: StateFlow<Boolean> = _copilotoOcupado.asStateFlow()

    fun cicloDeVoz() {
        // Fora da Main: o VAD calcula RMS de 50 janelas/s e o STT carrega um
        // modelo de ~75 MB. Na Main isso congela a UI e arrisca ANR justamente
        // na janela em que a meta é responder em ≤ 2,0 s.
        if (_copilotoOcupado.value) return
        viewModelScope.launch(Dispatchers.Default) {
            _copilotoOcupado.value = true
            try {
            // **O MOTIVO DESTA GUARDA MUDOU, e ela continua necessária.**
            //
            // Antes era limitação técnica: duas capturas abririam dois
            // `AudioRecord` na mesma fonte. Isso acabou — `FonteUnicaDeMicrofone`
            // existe e faz fan-out, e o pré-roll já divide o microfone com a
            // captura ao vivo em todo PTT.
            //
            // O que sobrou é razão de CUSTÓDIA: a captura de evidência abaixo
            // não filtra pelo supressor de saída própria (ver
            // `iniciarCapturaDeEvidencia`), então rodar o ciclo de voz durante
            // uma gravação gravaria a fala sintetizada do copiloto DENTRO da
            // evidência. Um arquivo de custódia com a voz do próprio app
            // misturada à do agente é pior que não ter o arquivo: não dá para
            // separar depois, e a defesa tem argumento.
            //
            // Só sai daqui quando a evidência passar a filtrar pelo supressor —
            // aí as duas podem coexistir de verdade.
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

            // Um id, gerado uma vez, usado pelos dois lados da instrumentação.
            val cicloId = "ciclo-${System.currentTimeMillis()}"
            SaidaUnica.telemetriaDoCiclo.abrirCiclo(cicloId)

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
                // A instrumentação que faltava: `Telemetry.mark` não tinha
                // NENHUM chamador no projeto inteiro, e as três metas de
                // latência do ciclo de voz eram adjetivo. Os marcos de
                // reprodução vêm de `SaidaUnica`, no instante em que o som
                // começa a sair — ver `TelemetriaDoCicloDeVoz`.
                telemetria = SaidaUnica.telemetriaDoCiclo,
                // **O MESMO id** que `abrirCiclo` registrou. Gerar um id aqui e
                // outro ali faria os marcos de reprodução (que usam o ciclo
                // corrente) caírem num ciclo diferente do que o `VoiceCycle`
                // marca — e o relatório mostraria "sem amostras" nas duas metas
                // que mais importam, sem nenhum erro visível.
                novoCicloId = { cicloId },
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
            } finally {
                // `finally` e nao no fim do corpo: os cinco `return@launch` de
                // recusa saltam daqui, e sem isto o botao ficaria travado em
                // "OUVINDO..." para sempre no primeiro caminho de falha —
                // exatamente o caso em que o agente mais precisa tentar de novo.
                _copilotoOcupado.value = false
            }
        }
    }


    /** Distingue "rodou" de "explodiu" no ciclo de voz. */
    private sealed interface Resultado {
        data class Ok(val valor: VoiceCycle.Resultado?) : Resultado
        data class Erro(val causa: Throwable) : Resultado
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
