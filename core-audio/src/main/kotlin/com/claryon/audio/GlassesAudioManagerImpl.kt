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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Escopo do laço de captura.
     *
     * Não é o do chamador de propósito: o microfone é compartilhado entre
     * consumidores com ciclos de vida diferentes (o pré-roll do rádio, a captura
     * ao vivo do PTT, o cofre), e amarrar o laço ao escopo de quem chegou primeiro
     * faria a saída **dele** derrubar a captura de todos os outros. Quem decide
     * quando o `AudioRecord` fecha é a contagem de assinantes, em
     * [FonteUnicaDeMicrofone], não a vida de um ViewModel.
     */
    private val escopoDaFonte = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Quadros perdidos por consumidor lento. Zero é o normal; ver [quadrosDescartados]. */
    private val descartados = AtomicLong(0)

    /**
     * Quantos quadros de 20 ms um consumidor lento já perdeu.
     *
     * Exposto porque descarte silencioso é a versão sutil de "falha em silêncio":
     * o áudio some, ninguém vê, e a hipótese vira "o microfone está ruim". Este
     * número é o que separa as duas explicações.
     */
    val quadrosDescartados: Long get() = descartados.get()

    private val fonte = FonteUnicaDeMicrofone(
        escopo = escopoDaFonte,
        confereRota = { rota -> audioManager.confereRota(rota) },
        abrirCaptura = { abrirAudioRecord() },
        aoDescartar = { quantos ->
            descartados.addAndGet(quantos.toLong())
            Log.w(TAG, "consumidor lento descartou $quantos quadro(s) de microfone")
        },
    )

    /**
     * **Uma vista da fonte única.** Ver [FonteUnicaDeMicrofone].
     *
     * Assinar é o que abre o `AudioRecord`; sair é o que o fecha. Chamar isto duas
     * vezes não abre dois microfones — que era exatamente o que acontecia durante
     * todo PTT, com o pré-roll e a captura ao vivo disputando o mesmo hardware.
     */
    override fun microfonePcm(route: GlassesAudioRoute): Flow<ShortArray> = fonte.pcm(route)

    // Contrato: o chamador (app) garante RECORD_AUDIO concedido em runtime (o
    // onboarding pede antes de qualquer captura). O lint não enxerga esse fluxo.
    @Suppress("MissingPermission")
    private fun abrirAudioRecord(): FonteUnicaDeMicrofone.CapturaBruta {
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
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "AudioRecord não inicializou (permissão RECORD_AUDIO? rota de áudio?)"
        }
        record.startRecording()
        Log.i(TAG, "AudioRecord aberto a $sampleRateHz Hz")

        val buffer = ShortArray(frameSamples)
        return object : FonteUnicaDeMicrofone.CapturaBruta {
            override val duracaoDoQuadroMs = 20L

            override suspend fun ler(): ShortArray? {
                // `read` bloqueante em `Dispatchers.IO` — é ele o relógio da
                // captura, e é onde a thread deve esperar.
                val n = record.read(buffer, 0, buffer.size)
                return when {
                    n > 0 -> buffer.copyOf(n)
                    // n == 0 é legítimo (buffer ainda sem dados): só continuar.
                    n == 0 -> null
                    // Negativo é erro. Sem este ramo, uma desconexão do HFP
                    // (ERROR_DEAD_OBJECT) faz o laço girar a 100% de CPU para
                    // sempre, sem emitir e sem avisar ninguém — "sem fala
                    // detectada" para o usuário e a bateria indo embora.
                    // Falha nunca é silêncio: encerra o fluxo com erro tipado.
                    else -> throw AudioCaptureException(n)
                }
            }

            override fun fechar() {
                runCatching { record.stop() }
                record.release()
                Log.i(TAG, "AudioRecord fechado")
            }
        }
    }

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
                // **É esta função que sustenta a preempção de `PrioritySoundQueue`
                // (ver `SoundQueue.kt`: "nível 1 interrompe qualquer coisa").**
                // Quando um P1 do rádio cancela a fala do copiloto em curso, o
                // `delay` acima é pulado — o cancelamento entra direto aqui.
                //
                // `pause()` + `flush()` antes de `stop()` torna o corte
                // EXPLÍCITO em vez de depender da coincidência de tempo entre
                // "quanto sobrou no buffer" e "quando o `finally` roda": pause()
                // para a saída no instante em que é chamado, e flush() descarta
                // o que ainda não tocou. No caminho normal (delay já esgotado),
                // o buffer já está vazio e os dois são no-op — sem custo.
                runCatching { track.pause() }
                runCatching { track.flush() }
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

        private val bufferSizeBytes = maxOf(minBuf, minBuf * 4)

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
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()

        /**
         * Cota máxima de áudio que pode estar em voo no buffer nativo no
         * instante de [fechar]: `write()` é bloqueante e nunca deixa mais que
         * [bufferSizeBytes] sem consumir — é o próprio buffer que impõe o teto,
         * não uma estimativa de quanto falta.
         */
        private val duracaoMaximaEmVooMs = (bufferSizeBytes / 2) * 1000L / sampleRateHz

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

        /**
         * Fecha o `AudioTrack`.
         *
         * **`stop()` colado a `release()`, sem espera entre os dois, NÃO
         * drena** — o comentário que esteve aqui antes prometia o oposto, e
         * fazia o oposto: `MODE_STREAM` devolve o controle assim que o pedido de
         * parar é aceito, não quando o que já está no buffer termina de tocar.
         * O resultado era a última sílaba de **toda** transmissão recebida
         * cortada, na ordem de [duracaoMaximaEmVooMs] — centenas de ms a 16 kHz.
         *
         * A espera roda **fora** do chamador: `fechar()` é `fun`, não `suspend`
         * — mudar a assinatura para drenar de forma síncrona propagaria
         * `suspend` até `RadioTatico.fecharFluxoDeSaida()`, hoje chamada de
         * contexto não-suspenso. `escopoDaFonte` (IO, vida do processo) é onde
         * este gerente já faz housekeeping equivalente para a captura — reusado
         * aqui em vez de outro escopo, porque a natureza do trabalho é a mesma:
         * limpeza de recurso nativo, não caminho de áudio ao vivo.
         *
         * `aberto = false` continua síncrono, na entrada: é o que
         * [escrever] confere antes de cada bloco. Não elimina toda corrida — um
         * `write()` já em voo quando isto roda só observa a flag no próximo
         * quadro — mas fecha a maior parte da janela sem exigir uma reescrita de
         * `RadioTatico` neste ciclo. Risco residual: NÃO VERIFICADO em hardware.
         */
        override fun fechar() {
            if (!aberto) return
            aberto = false
            escopoDaFonte.launch {
                delay(duracaoMaximaEmVooMs)
                runCatching { track.stop() }
                runCatching { track.release() }
            }
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
