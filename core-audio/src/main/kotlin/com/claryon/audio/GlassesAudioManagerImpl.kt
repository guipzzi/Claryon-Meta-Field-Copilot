package com.claryon.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Roteamento e captura de áudio pelo canal Bluetooth (HFP/SCO) — implementação
 * real do [GlassesAudioManager].
 *
 * **O áudio NÃO passa pelo DAT.** Microfone e alto-falantes são acessados por
 * `AudioManager`/`AudioRecord`/`AudioTrack`. A sequência de roteamento
 * (`availableCommunicationDevices` → `TYPE_BLUETOOTH_SCO` → `setCommunicationDevice`,
 * API 31+) deve estar **completa antes** de qualquer sessão de streaming do DAT
 * que dependa de áudio — senão a captura de voz "às vezes funciona".
 *
 * `liberar()` chama `clearCommunicationDevice()`: sem isso, TODO o áudio do
 * sistema fica preso no canal de voz 8 kHz.
 *
 * @param allowFallbackToDefault quando `true` e não houver SCO, roteia para o
 *   dispositivo de comunicação padrão (embutido). Serve para desenvolvimento e
 *   teste **sem** fone Bluetooth (o MDK não simula áudio). Em produto: `false`.
 */
class GlassesAudioManagerImpl(
    context: Context,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val allowFallbackToDefault: Boolean = false,
) : GlassesAudioManager {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var routedDevice: AudioDeviceInfo? = null

    /** Rota efetivada (para diagnóstico). */
    val rotaAtual: String
        get() = routedDevice?.let { deviceLabel(it) } ?: "nenhuma"

    override suspend fun iniciar(): Result<Unit> {
        previousMode = audioManager.mode
        // MODE_IN_COMMUNICATION prepara o pipeline de voz. Se a captura não subir
        // com MODE_NORMAL, este é o modo a testar (armadilha conhecida).
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val devices = audioManager.availableCommunicationDevices
        val sco = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val target = sco ?: if (allowFallbackToDefault) devices.firstOrNull() else null

        if (target == null) {
            audioManager.mode = previousMode
            return Result.failure(
                ClaryonError.Audio(
                    "audio.no_sco",
                    "Nenhum dispositivo HFP (TYPE_BLUETOOTH_SCO). Verifique se os óculos/fone estão conectados no app Meta AI.",
                ),
            )
        }

        // setCommunicationDevice pode retornar false — tratar, nunca falha silenciosa.
        val ok = audioManager.setCommunicationDevice(target)
        if (!ok) {
            audioManager.mode = previousMode
            return Result.failure(
                ClaryonError.Audio(
                    "audio.set_comm_device_false",
                    "setCommunicationDevice retornou false para ${deviceLabel(target)}.",
                ),
            )
        }

        routedDevice = target
        Log.i(
            TAG,
            "Áudio roteado para ${deviceLabel(target)} (SCO=${target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO})",
        )
        return Result.success(Unit)
    }

    // Contrato: o chamador (app) garante RECORD_AUDIO concedido em runtime (o
    // onboarding pede antes de qualquer captura). O lint não enxerga esse fluxo.
    @Suppress("MissingPermission")
    override fun microfonePcm(): Flow<ShortArray> = flow {
        val frameSamples = sampleRateHz / 50 // janelas de 20 ms
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameSamples * 2 * 4),
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord não inicializou (permissão RECORD_AUDIO? rota de áudio?)"
            }
            record.startRecording()
            val buffer = ShortArray(frameSamples)
            while (currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) emit(buffer.copyOf(n))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun reproduzir(pcm: ShortArray, sampleRateHz: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (pcm.isEmpty()) return@withContext Result.success(Unit)
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .build()
            try {
                track.play()
                var offset = 0
                while (offset < pcm.size) {
                    val n = track.write(pcm, offset, pcm.size - offset)
                    if (n < 0) {
                        return@withContext Result.failure(
                            ClaryonError.Audio("audio.write_failed", "AudioTrack.write erro $n"),
                        )
                    }
                    offset += n
                }
                // Deixa o buffer drenar antes de parar (senão corta o fim do áudio).
                delay((pcm.size * 1000L) / sampleRateHz + 50)
                Result.success(Unit)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }

    override fun liberar() {
        // clearCommunicationDevice é OBRIGATÓRIO: sem ele, o áudio do sistema fica
        // preso no canal de voz 8 kHz.
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = previousMode
        routedDevice = null
    }

    private fun deviceLabel(device: AudioDeviceInfo): String = buildString {
        append(
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Alto-falante"
                else -> "tipo ${device.type}"
            },
        )
        val name = device.productName?.toString()?.trim()
        if (!name.isNullOrEmpty()) append(" ($name)")
    }

    private companion object {
        const val TAG = "ClaryonField"
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
    }
}
