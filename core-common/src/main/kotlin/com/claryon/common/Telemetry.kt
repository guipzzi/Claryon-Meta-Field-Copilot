package com.claryon.common

/**
 * Telemetria local — instrumentada desde o início, conforme a regra do projeto
 * ("métrica adicionada no fim nunca é adicionada").
 *
 * NÃO é analytics de produto e NÃO sai do aparelho no caminho crítico: serve
 * para medir localmente os alvos da seção "Metas mensuráveis" (fim da fala →
 * earcon ≤ 500 ms, → resposta local ≤ 2,0 s, etc.).
 *
 * Cada estágio do ciclo de voz emite um marco; a diferença entre marcos é a
 * latência percebida. A implementação (buffer em memória + export para
 * inspeção) é injetada pelo `app`.
 */
interface Telemetry {
    /** Marca um instante nomeado para um dado ciclo de interação [cycleId]. */
    fun mark(cycleId: String, stage: Stage, epochMillis: Long)

    enum class Stage {
        WAKE_DETECTED,       // wake word disparou
        VAD_WINDOW_CLOSED,   // fim de fala detectado → earcon dispara aqui
        EARCON_PLAYED,       // primeira nota do earcon de reconhecimento
        STT_STARTED,         // o PCM entrou no whisper — o zero do custo REAL do STT
        STT_DONE,            // transcrição pronta
        INTENT_ROUTED,       // intenção classificada
        ACTION_DONE,         // ação local/rede concluída
        RESPONSE_FIRST_AUDIO // primeira nota da resposta TTS
    }

    object NoOp : Telemetry {
        override fun mark(cycleId: String, stage: Stage, epochMillis: Long) {}
    }
}
