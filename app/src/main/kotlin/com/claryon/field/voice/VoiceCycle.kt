package com.claryon.field.voice

import com.claryon.agent.Intent
import com.claryon.agent.IntentRouter
import com.claryon.agent.OperationalResponses
import com.claryon.voice.PcmAudio
import com.claryon.voice.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Orquestrador do **ciclo de voz** — junta as peças provadas isoladamente num
 * único fluxo:
 *
 * ```
 * PCM (HFP) → VAD fecha a janela → [earcon "ouvi você" IMEDIATO]
 *   → STT (whisper, em lote) → IntentRouter → resposta lacônica
 *   → TTS (Piper) → reprodução (AudioTrack/HFP)
 * ```
 *
 * O earcon dispara no **fechamento do VAD**, não no fim do STT: o agente sabe que
 * foi ouvido em ~400 ms, mesmo que a ação leve 2 s. É a otimização de UX de maior
 * retorno do projeto e custa três linhas.
 *
 * Depende só de abstrações (funções + interfaces), então é testável em JVM com
 * fakes e permite trocar back-ends (whisper↔Android, Piper↔Android) sem reescrita.
 */
class VoiceCycle(
    private val pcmInput: () -> Flow<ShortArray>,
    private val vad: VoiceActivityDetector,
    private val sttFn: suspend (pcm: ShortArray, sampleRateHz: Int) -> String,
    private val router: IntentRouter,
    private val ttsFn: suspend (texto: String) -> PcmAudio?,
    private val playFn: suspend (PcmAudio) -> Unit,
    private val onOuviVoce: () -> Unit,
    private val sampleRateHz: Int,
) {

    data class Resultado(
        val transcricao: String,
        val intent: Intent,
        val resposta: String,
    )

    /** Executa um ciclo: espera uma janela de fala, entende e responde por voz. */
    suspend fun runOnce(): Resultado {
        val segmento = vad.segment(pcmInput()).first() // 1ª janela fechada pelo VAD
        onOuviVoce() // earcon IMEDIATO (antes do STT)

        val transcricao = sttFn(segmento.pcm, segmento.sampleRateHz.orDefault(sampleRateHz))
        val intent = router.route(transcricao)
        val resposta = OperationalResponses.para(intent)

        ttsFn(resposta)?.let { playFn(it) }
        return Resultado(transcricao, intent, resposta)
    }

    private fun Int.orDefault(fallback: Int) = if (this > 0) this else fallback
}
