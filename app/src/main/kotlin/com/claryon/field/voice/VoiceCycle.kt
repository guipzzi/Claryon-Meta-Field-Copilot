package com.claryon.field.voice

import com.claryon.agent.ActionOutcome
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.IntentRouter
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Telemetry
import com.claryon.voice.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Orquestrador do **ciclo de voz**:
 *
 * ```
 * [earcon CANAL_ABERTO — "bipbip", o microfone abriu]
 *   → PCM (HFP) → VAD fecha a janela → [earcon CANAL_FECHADO — "trimtrim", IMEDIATO]
 *   → STT (whisper, em lote) → IntentRouter → IntentExecutor  ← A AÇÃO ACONTECE
 *   → utteranceFor(RESULTADO) → fila de som (earcon e/ou TTS)
 * ```
 *
 * **A ordem dos dois últimos passos é a correção central do produto.** Antes, a
 * frase saía de `OperationalResponses.para(intent)` — escolhida a partir do
 * *comando*, antes de qualquer ação. O app dizia "Apoio solicitado, guarnição
 * avisada." sem tentar enviar. Agora a intenção passa pelo [IntentExecutor], e a
 * fala é construída a partir do [ActionOutcome] que voltou. Não existe caminho
 * neste arquivo em que a resposta preceda a ação: `utteranceFor` só aceita
 * resultado, e a única forma de obter um é executando.
 *
 * O earcon de fechamento dispara no **fechamento do VAD**, não no fim do STT: o
 * agente sabe que foi ouvido em ~400 ms, mesmo que a ação leve 2 s.
 *
 * ## Os dois tempos da gramática que vivem aqui (22/08)
 *
 * O `CopilotService` toca o **BOMMM** ao ouvir "Claryon". Depois dele ainda
 * acontece trabalho de verdade antes de o microfone estar de fato aberto: o salto
 * de corrotina no serviço, `audio.iniciar()` (que em HFP real é negociação de SCO,
 * centenas de milissegundos), o Whisper vindo do dono de processo. **Nesse
 * intervalo o agente não tinha como saber se já podia falar** — o produto não tem
 * display, e o único sinal chegava antes do microfone.
 *
 * Por isso [Earcon.CANAL_ABERTO] sai **aqui**, na primeira linha de [runOnce], que
 * é o instante em que este objeto começa a consumir o fluxo do microfone: *"canal
 * aberto, pode falar"*. E [Earcon.CANAL_FECHADO] sai quando o VAD fecha a janela:
 * *"você parou de falar, o canal fechou, estou trabalhando"* — que é exatamente o
 * gatilho que o dono escreveu (*"30 s ou parou de falar → trimtrim"*).
 *
 * Os dois são a convenção do rádio, não invenção nossa: chirp subindo abre, chirp
 * descendo fecha. O policial já sabe.
 *
 * Depende só de abstrações, então é testável em JVM com fakes.
 */
class VoiceCycle(
    private val pcmInput: () -> Flow<ShortArray>,
    private val vad: VoiceActivityDetector,
    private val sttFn: suspend (pcm: ShortArray, sampleRateHz: Int) -> String,
    private val router: IntentRouter,
    private val executor: IntentExecutor,
    private val emitir: suspend (Utterance) -> Unit,
    private val sampleRateHz: Int,
    /**
     * `NoOp` por padrão: instrumentação é opt-in e nenhum teste existente
     * precisa aprender um parâmetro novo.
     *
     * Marca só os estágios que **este** objeto executa em sequência. Os dois de
     * reprodução (`EARCON_PLAYED`, `RESPONSE_FIRST_AUDIO`) são marcados pelo
     * dono da saída, no instante em que o PCM entra no `AudioTrack` — marcá-los
     * aqui mediria `emitir`, que só enfileira, e produziria um número
     * consistentemente otimista.
     */
    private val telemetria: Telemetry = Telemetry.NoOp,
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
    private val novoCicloId: () -> String = { "ciclo-${System.currentTimeMillis()}" },
) {

    data class Resultado(
        val transcricao: String,
        val intent: Intent,
        val outcome: ActionOutcome,
        val utterance: Utterance,
    )

    /** Executa um ciclo: espera uma janela de fala, entende, **age**, e responde. */
    suspend fun runOnce(): Resultado {
        val cicloId = novoCicloId()

        // **bipbip — "canal aberto, pode falar".**
        //
        // Antes de qualquer espera: é este objeto que abre o fluxo do microfone, e
        // o instante em que ele começa a consumi-lo é o único momento honesto para
        // dizer que o canal está de pé. Emitir mais cedo (no serviço, junto com o
        // BOMMM) prometeria um microfone que ainda não existe; mais tarde só
        // avisaria depois de o agente já ter falado por cima.
        //
        // Ele NÃO vira `EARCON_PLAYED`: ver a lista fechada em `SaidaUnica`.
        emitir(Utterance.Sinalizar(Earcon.CANAL_ABERTO, Priority.RESPOSTA))

        val segmento = vad.segment(pcmInput()).first() // 1ª janela fechada pelo VAD

        // **O zero de todas as metas de latência do ciclo — descontado.**
        //
        // O que se sabe aqui é que a JANELA fechou; o agente parou de falar um
        // hangover antes disso (600 ms no detector por energia). Cravar o zero no
        // fechamento produziria um número consistentemente otimista em ~600 ms, e
        // internamente coerente — nenhum teste acusaria, e o relatório mostraria
        // uma meta batida que na régua do agente não foi.
        telemetria.mark(
            cicloId,
            Telemetry.Stage.VAD_WINDOW_CLOSED,
            agoraMs() - segmento.silencioFinalMs,
        )

        // **trimtrim — IMEDIATO, antes do STT.** "Parou de falar, o canal fechou":
        // confirma escuta enquanto a ação corre. É o terceiro tempo da gramática, e
        // o gatilho é literalmente o que o dono escreveu — o agente parou de falar.
        emitir(Utterance.Sinalizar(Earcon.CANAL_FECHADO, Priority.RESPOSTA))

        // **O zero do custo REAL do STT.**
        //
        // Sem este marco, `Transicao.STT` media `STT_DONE − VAD_WINDOW_CLOSED` — e
        // `VAD_WINDOW_CLOSED` está ancorado 600 ms no passado (o hangover do VAD),
        // com o `emitir` do earcon ainda no meio. O relatório dizia "transcrição
        // (whisper): 1021 ms" quando o whisper custava ~421. Mentira de rótulo, e
        // dela saíram dois KDoc de produção repetindo o número errado.
        telemetria.mark(cicloId, Telemetry.Stage.STT_STARTED, agoraMs())
        val transcricao = sttFn(segmento.pcm, segmento.sampleRateHz.orDefault(sampleRateHz))
        telemetria.mark(cicloId, Telemetry.Stage.STT_DONE, agoraMs())

        val intent = router.route(transcricao)
        telemetria.mark(cicloId, Telemetry.Stage.INTENT_ROUTED, agoraMs())

        // A ação acontece AQUI. Só depois existe algo a dizer.
        val outcome = executor.execute(intent)
        telemetria.mark(cicloId, Telemetry.Stage.ACTION_DONE, agoraMs())

        val utterance = utteranceFor(outcome)
        emitir(utterance)

        return Resultado(transcricao, intent, outcome, utterance)
    }

    private fun Int.orDefault(fallback: Int) = if (this > 0) this else fallback
}
