package com.claryon.field.voice

import com.claryon.agent.ActionOutcome
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.IntentRouter
import com.claryon.agent.Utterance
import com.claryon.agent.utteranceFor
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.voice.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Orquestrador do **ciclo de voz**:
 *
 * ```
 * PCM (HFP) → VAD fecha a janela → [earcon "ouvi você" IMEDIATO]
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
 * O earcon de "ouvi você" dispara no **fechamento do VAD**, não no fim do STT: o
 * agente sabe que foi ouvido em ~400 ms, mesmo que a ação leve 2 s.
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
) {

    data class Resultado(
        val transcricao: String,
        val intent: Intent,
        val outcome: ActionOutcome,
        val utterance: Utterance,
    )

    /** Executa um ciclo: espera uma janela de fala, entende, **age**, e responde. */
    suspend fun runOnce(): Resultado {
        val segmento = vad.segment(pcmInput()).first() // 1ª janela fechada pelo VAD

        // Earcon IMEDIATO, antes do STT: confirma escuta enquanto a ação corre.
        emitir(Utterance.Sinalizar(Earcon.OUVI_VOCE, Priority.RESPOSTA))

        val transcricao = sttFn(segmento.pcm, segmento.sampleRateHz.orDefault(sampleRateHz))
        val intent = router.route(transcricao)

        // A ação acontece AQUI. Só depois existe algo a dizer.
        val outcome = executor.execute(intent)

        val utterance = utteranceFor(outcome)
        emitir(utterance)

        return Resultado(transcricao, intent, outcome, utterance)
    }

    private fun Int.orDefault(fallback: Int) = if (this > 0) this else fallback
}
