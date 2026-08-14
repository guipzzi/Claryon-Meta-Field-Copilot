package com.claryon.agent

/**
 * Que recursos ficam ligados em cada [ModoOperacao]. Política **pura** — o
 * serviço apenas obedece, e o teste prova a economia sem precisar de bateria.
 *
 * O princípio (CLAUDE.md): *gatilho, nunca loop*. A cascata é
 * wake word (barata, sempre que houver HFP) → VAD → STT (caro, quase nunca).
 */
data class PerfilDeEnergia(
    /** Canal HFP aberto (microfone dos óculos capturando). */
    val hfpAberto: Boolean,
    /** Detector de wake word rodando sobre o PCM. */
    val wakeWordAtiva: Boolean,
    /** Stream de câmera ligado por padrão neste modo. */
    val cameraPorPadrao: Boolean,
    /** Teto de FPS quando a câmera subir sob demanda. */
    val fpsMaximo: Int,
    /** Informativos (nível 3) são suprimidos (Modo Tático). */
    val suprimeInformativos: Boolean,
)

object PowerPolicy {

    /**
     *  - **Standby:** HFP fechado — o rádio Bluetooth em SCO é o maior consumidor
     *    contínuo; sem HFP não há wake word (o despertar é pelo botão/tela).
     *  - **Ativo:** HFP aberto com wake word; câmera **desligada por padrão**,
     *    sobe só sob intenção explícita e desce ao terminar.
     *  - **Ocorrência:** tudo ligado, janela curta — é o modo caro, por isso
     *    também é o modo que suprime informativo (Modo Tático).
     */
    fun perfil(modo: ModoOperacao): PerfilDeEnergia = when (modo) {
        ModoOperacao.STANDBY -> PerfilDeEnergia(
            hfpAberto = false,
            wakeWordAtiva = false,
            cameraPorPadrao = false,
            fpsMaximo = 0,
            suprimeInformativos = false,
        )
        ModoOperacao.ATIVO -> PerfilDeEnergia(
            hfpAberto = true,
            wakeWordAtiva = true,
            cameraPorPadrao = false,
            fpsMaximo = FPS_PADRAO,
            suprimeInformativos = false,
        )
        ModoOperacao.OCORRENCIA -> PerfilDeEnergia(
            hfpAberto = true,
            wakeWordAtiva = true,
            cameraPorPadrao = true,
            fpsMaximo = FPS_PADRAO,
            suprimeInformativos = true,
        )
    }

    /**
     * `foregroundServiceType` necessário para o modo — o serviço precisa
     * declarar **exatamente** o que usa (Android 14+ recusa o que não foi
     * declarado no manifest, e declarar demais é pedir permissão à toa).
     */
    fun tiposDeServico(modo: ModoOperacao): Set<TipoServico> {
        val p = perfil(modo)
        return buildSet {
            add(TipoServico.CONNECTED_DEVICE) // sessão com os óculos, sempre
            if (p.hfpAberto) add(TipoServico.MICROPHONE)
            if (p.cameraPorPadrao) add(TipoServico.CAMERA)
        }
    }

    /** FPS baixo por decisão de qualidade: o gargalo é o Bluetooth Classic. */
    const val FPS_PADRAO = 7
}

/** Espelho independente de Android dos `FOREGROUND_SERVICE_TYPE_*` que usamos. */
enum class TipoServico { CONNECTED_DEVICE, MICROPHONE, CAMERA }
