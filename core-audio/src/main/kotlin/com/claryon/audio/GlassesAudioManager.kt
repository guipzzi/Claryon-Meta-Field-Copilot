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

    /** Configura o roteamento SCO. Trata `setCommunicationDevice() == false`. */
    suspend fun iniciar(): Result<Unit>

    /** Fluxo de PCM mono 16-bit capturado do microfone dos óculos. */
    fun microfonePcm(): Flow<ShortArray>

    /** Reproduz PCM no alto-falante open-ear (earcons e TTS). */
    suspend fun reproduzir(pcm: ShortArray, sampleRateHz: Int): Result<Unit>

    /** Encerra o roteamento e devolve o áudio do sistema ao estado normal. */
    fun liberar()
}
