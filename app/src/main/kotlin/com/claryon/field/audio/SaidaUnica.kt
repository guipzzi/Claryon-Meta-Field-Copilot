package com.claryon.field.audio

import android.app.Application
import android.content.Context
import android.util.Log
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.common.Telemetry
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.TelemetriaDoCicloDeVoz
import com.claryon.field.voice.VoiceOutput
import com.claryon.net.SupressorDeSaidaPropria
import com.claryon.sound.Sound
import com.claryon.voice.AndroidTts
import com.claryon.voice.PcmAudio
import com.claryon.voice.PiperTts
import com.claryon.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Dono único da saída de som do processo.**
 *
 * ## O defeito que isto conserta
 *
 * Até aqui, `RadioViewModel` e `DiagnosticsViewModel` construíam **cada um a sua**
 * `VoiceOutput` — duas `PrioritySoundQueue`, cada uma com o próprio
 * `SoundScheduler` e o próprio `playingPriority`. `deveInterromper` só enxerga o
 * que toca **na mesma fila**: um alerta P1 do rádio (earcon `PRIORITARIA`,
 * `Priority.EMERGENCIA`) chegava na fila do rádio e não sabia que a fila do
 * copiloto estava no meio de uma frase. Resultado documentado em `ESTADO.md`: "um
 * P1 do rádio não interrompe fala do copiloto".
 *
 * A correção não é lógica nova — `SoundScheduler.deveInterromper` já faz a coisa
 * certa. É fazer as duas fontes de som **compartilharem a fila**, para que a
 * emergência de uma enxergue a fala da outra.
 *
 * ## Por que um objeto de processo, e não injeção pelo `app`
 *
 * Mesmo raciocínio de [AudioDoAgente]: o recurso disputado — a saída de áudio do
 * aparelho — é global do processo, não do grafo de objetos. `RadioViewModel` e
 * `DiagnosticsViewModel` são instanciados de formas diferentes (`viewModel()` sem
 * fábrica) e nenhum é dono natural do outro; um objeto de processo é a forma que
 * não exige que um dos dois "empreste" a própria fila ao outro.
 *
 * ## Por que a resolução do TTS mora aqui, e não num parâmetro
 *
 * Antes, `DiagnosticsViewModel` guardava `piper`/`piperResolvido`/`ttsMutex` e
 * resolvia sob demanda porque a primeira síntese copia `espeak-ng-data` e carrega
 * o modelo ONNX — trabalho que não pode rodar no construtor do ViewModel. Movido
 * para cá, a resolução acontece **uma vez** por processo, para quem quer que fale
 * primeiro — e o rádio, que hoje só reusa `utteranceFor(SEM_REDE)`, ganha um TTS
 * de verdade em vez de `sintetizar = { null }`.
 *
 * ## O segundo defeito que a unificação destrava: o supressor de eco não via o TTS
 *
 * `SupressorDeSaidaPropria` — a classe que impede a própria saída de voltar pela
 * captura e entrar no pré-roll ou na transmissão — vivia como campo **privado**
 * de `RadioTatico`, e só os earcons do próprio rádio chamavam
 * `registrar`/`abrir`/`fechar`. O KDoc daquela classe enumera "a fala do
 * copiloto (TTS) entraria na transmissão" como o caso 3 que o mecanismo existe
 * para cobrir — mas a ligação nunca foi feita: a fala sintetizada saía por outro
 * objeto inteiramente (o `VoiceOutput` antigo de `DiagnosticsViewModel`) e não
 * registrava janela nenhuma. Alcançável em produção: o agente pergunta ao
 * copiloto, o Piper responde pelo alto-falante *open-ear* a centímetros do array
 * de microfones, a resposta entra no pré-roll, e vai ao ar no PTT seguinte — a
 * mesma classe de defeito que [AudioDoAgente] já corrigiu do lado da entrada,
 * agora do lado da saída.
 *
 * A correção mora aqui porque este é o único ponto por onde **todo** som passa,
 * de qualquer fonte. [supressor] é exposto para quem precisa **filtrar** a
 * captura (`RadioTatico`); [de] registra a janela para quem **produz** o som —
 * com a duração **real** do PCM (`amostras / taxaHz`), não uma estimativa por
 * earcon. Isso também resolve, de graça, o defeito irmão: a janela do earcon
 * `FALHA` de `SEM_REDE` reservava até 2 s de silêncio de captura para uma fala
 * que a fila do rádio, sozinha, nunca sintetizava (`sintetizar = { null }`) — com
 * TTS real, a fala passa a sair, e a duração registrada é a que ela de fato
 * ocupa, não uma constante que já discordava do que tocava.
 */
object SaidaUnica {

    /**
     * Escopo do laço de reprodução. **Não** é o `viewModelScope` de nenhum dos
     * dois chamadores: `radio.fechar()` roda no fim de turno sem destruir
     * `RadioViewModel`, e `DiagnosticsViewModel` nunca fecha enquanto a Activity
     * viver — mas nenhum dos dois é o dono certo de uma fila que serve o outro.
     * Igual à captura, quem decide o ciclo de vida é o processo.
     */
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var instancia: VoiceOutput? = null

    /**
     * Compartilhado com `RadioTatico`, que filtra a captura por
     * `!supressor.suprimido(agoraMs())`. Ver a seção do KDoc da classe sobre o
     * defeito que isto fecha.
     */
    val supressor = SupressorDeSaidaPropria()

    private val mutexDoTts = Mutex()
    private var piper: PiperTts? = null
    private var piperResolvido = false

    /**
     * Telemetria do ciclo de voz. Mora aqui porque é aqui que os dois marcos de
     * **reprodução** acontecem — e eles têm de ser marcados no instante em que o
     * som começa a sair, não quando foi pedido. Ver `TelemetriaDoCicloDeVoz`.
     */
    val telemetriaDoCiclo = TelemetriaDoCicloDeVoz()

    /** A fila única. Criada na primeira chamada. */
    fun de(context: Context): VoiceOutput =
        instancia ?: synchronized(this) {
            instancia ?: run {
                val app = context.applicationContext as Application
                val audio = AudioDoAgente.de(app)
                val fallback = AndroidTts(app)
                VoiceOutput(
                    scope = escopo,
                    sintetizar = { texto -> sintetizar(app, fallback, texto) },
                    reproduzir = { pcm, sr -> reproduzirComRotaESupressao(audio, pcm, sr) },
                    aoIniciarReproducao = { sound -> marcarReproducao(sound) },
                    aoInterromperPorEmergencia = { atraso ->
                        telemetriaDoCiclo.registrarPreempcao(atraso)
                        Log.i(TAG, "P1 cortou a reprodução em curso em ${atraso}ms")
                    },
                ).also { instancia = it }
            }
        }

    /**
     * Registra a janela de supressão pela duração **real** do PCM, sobe o HFP (ou
     * soma mais um usuário, se já estiver de pé — ver `GlassesAudioManagerImpl.iniciar`),
     * toca, e libera.
     *
     * O registro acontece **antes** de `iniciar()`/`reproduzir` — que suspendem —
     * porque o que importa é o instante em que o som *vai* soar, não o instante em
     * que a rota terminou de subir. Registrar tarde deixaria uma fresta no início
     * da reprodução em que a captura ainda não sabe que estamos emitindo.
     */
    private suspend fun reproduzirComRotaESupressao(
        audio: GlassesAudioManagerImpl,
        pcm: ShortArray,
        sampleRateHz: Int,
    ) {
        if (pcm.isNotEmpty()) {
            val duracaoMs = pcm.size * 1000L / sampleRateHz
            supressor.registrar(System.currentTimeMillis(), duracaoMs)
        }
        when (audio.iniciar()) {
            is Result.Success -> try {
                audio.reproduzir(pcm, sampleRateHz)
            } finally {
                audio.liberar()
            }
            // Falha nunca é silêncio — mas aqui o canal de aviso É o som, então só
            // resta registrar. Quem chamou já sabe pelo próprio estado da UI.
            is Result.Failure ->
                Log.w(TAG, "sem rota de áudio para reproduzir")
        }
    }

    /**
     * Traduz "começou a tocar isto" em marco de telemetria.
     *
     * O earcon `OUVI_VOCE` é o do ciclo de voz — os outros tons (falha do rádio,
     * prioritária, gravando) não pertencem a nenhum ciclo e não devem virar
     * `EARCON_PLAYED`, senão a meta "fim da fala → earcon" passaria a medir um
     * bipe do rádio que aconteceu no meio de outra coisa.
     */
    private fun marcarReproducao(sound: Sound) {
        val agora = System.currentTimeMillis()
        when {
            sound is Sound.Tone && sound.earcon == Earcon.OUVI_VOCE ->
                telemetriaDoCiclo.marcarNoCicloCorrente(Telemetry.Stage.EARCON_PLAYED, agora)
            sound is Sound.Speech ->
                telemetriaDoCiclo.marcarNoCicloCorrente(Telemetry.Stage.RESPONSE_FIRST_AUDIO, agora)
        }
    }

    private suspend fun sintetizar(app: Application, fallback: TtsEngine, texto: String): PcmAudio? {
        val engine: TtsEngine = mutexDoTts.withLock {
            if (!piperResolvido) {
                piper = Modelos.piper(app)
                piperResolvido = true
                Log.i(TAG, "TTS = ${if (piper != null) "Piper (neural, on-device)" else "AndroidTts (fallback)"}")
            }
            piper ?: fallback
        }
        return (engine.synthesize(texto) as? Result.Success)?.value
    }

    private const val TAG = "ClaryonField"
}
