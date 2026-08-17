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
/**
 * Um `AudioTrack` vivo, alimentado quadro a quadro. Feche sempre — o track
 * segura recurso nativo e a rota de saída.
 */
interface FluxoDeReproducao {
    /** Escreve um quadro. Bloqueia enquanto o buffer estiver cheio: é o relógio. */
    suspend fun escrever(pcm: ShortArray): Result<Unit>

    /** Drena o que falta e libera. Idempotente. */
    fun fechar()
}

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
     *   reconferida no início da captura **e a cada 200 ms enquanto ela dura**:
     *   se cair no meio, o fluxo falha com `RotaDeAudioPerdidaException` em vez
     *   de seguir gravando pelo microfone do celular. Conferir só na abertura
     *   deixava a janela de exposição do tamanho da captura — que, no pré-roll
     *   do rádio, é o turno inteiro.
     */
    fun microfonePcm(route: GlassesAudioRoute): Flow<ShortArray>

    /**
     * Reproduz um bloco fechado — earcon ou frase de TTS.
     *
     * Constrói e libera um `AudioTrack` por chamada, o que é correto para som
     * pontual e **errado para fluxo contínuo**. Para receber rádio use
     * [abrirFluxoDeReproducao].
     */
    suspend fun reproduzir(pcm: ShortArray, sampleRateHz: Int): Result<Unit>

    /**
     * Abre um `AudioTrack` de longa duração para a fala que está CHEGANDO.
     *
     * Existe porque a recepção de PTT construía, tocava, drenava com
     * `delay(dur + 50)` e liberava um `AudioTrack` **por quadro de 20 ms** — 50
     * por segundo, cada um com 50 ms de cauda, em corrotinas concorrentes sem
     * ordem entre si. O resultado é latência de partida variável, underrun a cada
     * quadro e inversão ocasional de ordem quando o `build()` de um demora mais
     * que o do seguinte.
     *
     * O fluxo resolve os três: um track só, `write` sequencial que bloqueia
     * naturalmente enquanto o buffer enche (é ele o relógio, não um `delay`
     * calculado), e ordem garantida por ser um consumidor único.
     *
     * A taxa é parâmetro e não constante porque só se conhece no **primeiro
     * quadro decodificado** — `CodecDeVoz.taxaDeSaidaHz` é 0 até lá.
     */
    fun abrirFluxoDeReproducao(sampleRateHz: Int): FluxoDeReproducao

    /** Encerra o roteamento e devolve o áudio do sistema ao estado normal. */
    fun liberar()
}
