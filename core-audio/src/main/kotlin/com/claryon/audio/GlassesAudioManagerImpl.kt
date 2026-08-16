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

    /** Expõe ao cofre e ao codec a taxa real desta instância. Ver o contrato. */
    override val taxaDeAmostragemHz: Int get() = sampleRateHz

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * O roteamento é **estado global do aparelho**, e vários caminhos do app
     * (eco, comando avulso, ciclo de voz) chamam `iniciar()`/`liberar()`. Sem
     * serialização + contagem, dois caminhos concorrentes fazem o seguinte:
     * o segundo `iniciar()` grava `previousMode = MODE_IN_COMMUNICATION` (o modo
     * que o primeiro acabou de pôr) e o `liberar()` de cada um restaura esse
     * valor — o **celular fica preso em MODE_IN_COMMUNICATION** e todo o áudio
     * do sistema desce a 8 kHz. Pior: o `liberar()` do caminho curto derruba a
     * rota do caminho longo ainda em captura.
     *
     * Portanto: só o **primeiro** `iniciar()` roteia e memoriza o modo anterior;
     * só o **último** `liberar()` desfaz. `lock` protege os três campos.
     */
    private val lock = Any()
    private var usuarios = 0
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var routedDevice: AudioDeviceInfo? = null

    /** Rota efetivada (para diagnóstico). */
    val rotaAtual: String
        get() = synchronized(lock) { routedDevice?.let { deviceLabel(it) } ?: "nenhuma" }

    /**
     * Prova da rota **efetivada** (não da pretendida).
     *
     * SCO real sempre passa pelo caminho estrito. Qualquer outro dispositivo só
     * vira prova pelo caminho de desenvolvimento, que por sua vez exige o flag
     * — em release, [allowFallbackToDefault] é `false` e a prova falha.
     */
    private fun provaDaRotaAtual(): Result<GlassesAudioRoute> =
        if (audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            GlassesAudioRoute.acquire(audioManager)
        } else {
            GlassesAudioRoute.acquireParaDesenvolvimento(
                audioManager,
                buildDebug = allowFallbackToDefault,
            )
        }

    override suspend fun iniciar(): Result<GlassesAudioRoute> = synchronized(lock) {
        if (usuarios > 0) {
            // Já roteado por outro caminho: soma um usuário e reemite a prova.
            // Só conta o usuário se a prova valer — senão o `liberar()` dele
            // desequilibraria a contagem e derrubaria a rota de quem captura.
            val prova = provaDaRotaAtual()
            if (prova is Result.Success) usuarios++
            return@synchronized prova
        }

        val modoAnterior = audioManager.mode
        // MODE_IN_COMMUNICATION prepara o pipeline de voz. Se a captura não subir
        // com MODE_NORMAL, este é o modo a testar (armadilha conhecida).
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val devices = audioManager.availableCommunicationDevices
        val sco = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val target = sco ?: if (allowFallbackToDefault) devices.firstOrNull() else null

        if (target == null) {
            audioManager.mode = modoAnterior
            return@synchronized Result.failure(
                ClaryonError.Audio(
                    "audio.no_sco",
                    "Nenhum dispositivo HFP (TYPE_BLUETOOTH_SCO). Verifique se os óculos/fone estão conectados no app Meta AI.",
                ),
            )
        }

        // setCommunicationDevice pode retornar false — tratar, nunca falha silenciosa.
        val ok = audioManager.setCommunicationDevice(target)
        if (!ok) {
            audioManager.mode = modoAnterior
            return@synchronized Result.failure(
                ClaryonError.Audio(
                    "audio.set_comm_device_false",
                    "setCommunicationDevice retornou false para ${deviceLabel(target)}.",
                ),
            )
        }

        // A prova é tirada do estado efetivado. Se o sistema aceitou o
        // setCommunicationDevice mas roteou para outro lugar, é aqui que se
        // descobre — antes de qualquer captura, não depois.
        val prova = provaDaRotaAtual()
        if (prova is Result.Failure) {
            runCatching { audioManager.clearCommunicationDevice() }
            audioManager.mode = modoAnterior
            return@synchronized prova
        }

        previousMode = modoAnterior
        routedDevice = target
        usuarios = 1
        Log.i(
            TAG,
            "Áudio roteado para ${deviceLabel(target)} (SCO=${target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO})",
        )
        prova
    }

    // Contrato: o chamador (app) garante RECORD_AUDIO concedido em runtime (o
    // onboarding pede antes de qualquer captura). O lint não enxerga esse fluxo.
    @Suppress("MissingPermission")
    override fun microfonePcm(route: GlassesAudioRoute): Flow<ShortArray> = flow {
        // A prova foi tirada no roteamento; a captura pode começar segundos
        // depois. Se o HFP caiu no intervalo (óculos dobrados, fone desligado,
        // ligação entrando), o sistema já escolheu um substituto — o microfone
        // do celular — e capturar aqui captaria terceiros. Falhar é o certo.
        if (!audioManager.confereRota(route)) throw RotaDeAudioPerdidaException()

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
                when {
                    n > 0 -> emit(buffer.copyOf(n))
                    // n == 0 é legítimo (buffer ainda sem dados): só continuar.
                    n == 0 -> Unit
                    // Negativo é erro. Sem este ramo, uma desconexão do HFP
                    // (ERROR_DEAD_OBJECT) faz o laço girar a 100% de CPU para
                    // sempre, sem emitir e sem avisar ninguém — "sem fala
                    // detectada" para o usuário e a bateria indo embora.
                    // Falha nunca é silêncio: encerra o fluxo com erro tipado.
                    else -> throw AudioCaptureException(n)
                }
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

    override fun abrirFluxoDeReproducao(sampleRateHz: Int): FluxoDeReproducao =
        FluxoDeReproducaoImpl(sampleRateHz)

    /**
     * Um `AudioTrack` em `MODE_STREAM`, vivo enquanto a fala chega.
     *
     * `MODE_STREAM` e não `MODE_STATIC`: o estático exige conhecer o áudio
     * inteiro antes de tocar, e aqui ele chega em quadros de 20 ms pela rede.
     *
     * O buffer é quatro vezes o mínimo do sistema. Menor que isso e cada hesitação
     * da rede vira underrun audível; muito maior e a latência boca-a-ouvido cresce
     * sem ganho, que é o oposto do ponto de um rádio.
     */
    private inner class FluxoDeReproducaoImpl(private val sampleRateHz: Int) : FluxoDeReproducao {

        private val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        private val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_VOICE_COMMUNICATION mantém a saída no SCO. Sem isso o
                    // sistema pode escolher A2DP e a fala sai pelo caminho errado.
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
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, minBuf * 4))
            .build()

        @Volatile private var aberto = true

        init {
            runCatching { track.play() }
        }

        override suspend fun escrever(pcm: ShortArray): Result<Unit> =
            withContext(Dispatchers.IO) {
                if (!aberto || pcm.isEmpty()) return@withContext Result.success(Unit)
                var offset = 0
                while (offset < pcm.size) {
                    // `write` bloqueante: ele é o relógio da reprodução. A versão
                    // anterior calculava um `delay` a partir do tamanho do quadro,
                    // o que erra sempre que o dispositivo consome em ritmo
                    // diferente do previsto — e ele consome.
                    val n = track.write(pcm, offset, pcm.size - offset)
                    if (n < 0) {
                        return@withContext Result.failure(
                            ClaryonError.Audio("audio.write_failed", "AudioTrack.write erro $n"),
                        )
                    }
                    offset += n
                }
                Result.success(Unit)
            }

        override fun fechar() {
            if (!aberto) return
            aberto = false
            // `stop()` e não `pause()`: stop drena o que já foi escrito antes de
            // parar, e é isso que impede o corte da última sílaba.
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * Desfaz o roteamento quando o **último** usuário solta (ver [iniciar]).
     * Chamadas extras são no-op — nunca derrubam a rota de quem ainda captura.
     */
    override fun liberar() = synchronized(lock) {
        if (usuarios == 0) return@synchronized
        usuarios--
        if (usuarios > 0) return@synchronized
        // clearCommunicationDevice é OBRIGATÓRIO: sem ele, o áudio do sistema fica
        // preso no canal de voz 8 kHz.
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = previousMode
        routedDevice = null
    }

    /** Solta todos os usuários de uma vez (encerramento do app). */
    fun liberarTudo() = synchronized(lock) {
        if (usuarios == 0) return@synchronized
        usuarios = 1
        liberar()
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

/**
 * `AudioRecord.read()` devolveu código de erro — tipicamente `ERROR_DEAD_OBJECT`
 * (-6, o HFP caiu) ou `ERROR_INVALID_OPERATION` (-3, gravação não iniciada).
 * Carrega o código para o mapeamento erro → earcon.
 */
/**
 * A rota provada por [GlassesAudioRoute] não é mais a rota ativa no instante da
 * captura. Não é erro de programação: HFP cai o tempo todo em campo. O
 * tratamento é earcon de falha e nova tentativa de roteamento — **nunca**
 * capturar mesmo assim.
 */
class RotaDeAudioPerdidaException : IllegalStateException(
    "A rota de áudio dos óculos caiu entre o roteamento e a captura. " +
        "Capturar agora gravaria pelo microfone do celular.",
)

class AudioCaptureException(val codigo: Int) : IllegalStateException(
    "AudioRecord.read retornou $codigo" +
        when (codigo) {
            AudioRecord.ERROR_DEAD_OBJECT -> " (ERROR_DEAD_OBJECT — rota HFP caiu)"
            AudioRecord.ERROR_INVALID_OPERATION -> " (ERROR_INVALID_OPERATION)"
            AudioRecord.ERROR_BAD_VALUE -> " (ERROR_BAD_VALUE)"
            else -> ""
        },
)
