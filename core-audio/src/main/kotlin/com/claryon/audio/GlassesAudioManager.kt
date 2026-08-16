package com.claryon.audio

import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow

/**
 * Roteamento e captura de áudio pelo canal Bluetooth dos óculos (HFP/SCO).
 *
 * **Ordem que não pode ser invertida:** `iniciar()` roteia e confirma o
 * dispositivo de comunicação (TYPE_BLUETOOTH_SCO) ANTES de a sessão de
 * streaming do DAT subir. Inverter isso produz captura de voz que "às vezes
 * funciona" — o bug mais caro do projeto.
 *
 * `liberar()` chama `clearCommunicationDevice()`; sem isso, todo o áudio do
 * sistema fica preso em 8 kHz.
 *
 * Contrato fixado no M0; implementação no M3.
 */
interface GlassesAudioManager {

    /**
     * Taxa em que [microfonePcm] entrega amostras.
     *
     * Está no contrato porque **PCM sem taxa declarada é inaudível**: quem
     * persiste o áudio (cofre de evidência) precisa gravar a taxa junto, e quem o
     * reproduz precisa da mesma. Antes, esse número vivia só no `AudioRecord`
     * desta implementação e o cofre o repetia por coincidência de valores
     * padrão — bastava construir o manager com outra taxa para o manifesto passar
     * a declarar uma taxa que o áudio não tem.
     */
    val taxaDeAmostragemHz: Int

    /**
     * Configura o roteamento SCO. Trata `setCommunicationDevice() == false`.
     *
     * Devolve a [GlassesAudioRoute] — a **prova** de que a rota subiu. É o único
     * jeito de obter uma, e [microfonePcm] a exige: captura sem roteamento não
     * compila (ver [GlassesAudioRoute]).
     */
    suspend fun iniciar(): Result<GlassesAudioRoute>

    /**
     * Fluxo de PCM mono 16-bit capturado do microfone **dos óculos**.
     *
     * @param route prova de roteamento devolvida por [iniciar]. A rota é
     *   reconferida no início da captura: se caiu no intervalo, o fluxo falha em
     *   vez de gravar pelo microfone do celular.
     */
    fun microfonePcm(route: GlassesAudioRoute): Flow<ShortArray>

    /** Reproduz PCM no alto-falante open-ear (earcons e TTS). */
    suspend fun reproduzir(pcm: ShortArray, sampleRateHz: Int): Result<Unit>

    /** Encerra o roteamento e devolve o áudio do sistema ao estado normal. */
    fun liberar()
}
