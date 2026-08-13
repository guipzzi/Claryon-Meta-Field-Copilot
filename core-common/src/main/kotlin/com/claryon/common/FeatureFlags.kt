package com.claryon.common

/**
 * Chaves de feature flag do projeto.
 *
 * As flags existem para permitir "plano B sem reescrita" e para segregar
 * back-ends de risco (ex.: gateway de mensageria) atrás de configuração,
 * decidida em runtime e não em tempo de compilação.
 */
enum class FeatureFlag(val key: String, val default: Boolean) {
    /** Usa a Groups API do WhatsApp (exige Official Business Account). */
    WHATSAPP_GROUPS("whatsapp.groups", default = false),

    /** Força o back-end nativo do Android para STT/TTS (fallback controlado). */
    FORCE_NATIVE_VOICE("voice.forceNative", default = false),

    /** Modo Tático: suprime informativos (nível 3), mantém emergência e earcons. */
    TACTICAL_MODE("sound.tactical", default = false),
}

/** Fonte de feature flags. A implementação (remota/local) é injetada pelo `app`. */
interface FeatureFlags {
    fun isEnabled(flag: FeatureFlag): Boolean

    /** Todas desligadas no default declarado — útil em testes. */
    object Defaults : FeatureFlags {
        override fun isEnabled(flag: FeatureFlag): Boolean = flag.default
    }
}
