package com.claryon.field.voice

import android.content.res.AssetManager
import com.claryon.voice.SpeechSegment
import com.claryon.voice.VoiceActivityDetector
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * **Detector de fala por rede neural (Silero), no lugar do limiar de energia.**
 *
 * ## O que o detector por energia erra, e por que isso importa aqui
 *
 * `EnergyVoiceActivityDetector` decide por RMS: som alto é fala. Numa viatura com
 * sirene, rádio da corporação tocando e vento de janela aberta, isso abre janela
 * o tempo todo — e cada janela aberta é uma invocação do Whisper, ~78 MB de
 * modelo trabalhando sobre ruído. O custo não é só bateria: é o gatilho por voz
 * disparando sozinho, que é a diferença entre um copiloto e um incômodo.
 *
 * O Silero é treinado para distinguir **voz** de som. Ele erra também, mas erra
 * na direção certa: ruído de motor não é voz para ele, e voz baixa continua sendo.
 *
 * ## As três coisas que o artefato impôs
 *
 * **1. Re-quadrador 320 → 512, e ele não é detalhe.** O nativo exige janelas de
 * exatamente `windowSize` amostras — padrão **512**, lido do bytecode do AAR. A
 * captura deste projeto entrega **320** (20 ms a 16 kHz). Alimentar 320 num
 * detector que espera 512 não dá erro: dá comportamento indefinido, que é pior.
 * O acumulador abaixo existe para isso, e a consequência de latência é declarada:
 * o VAD só decide a cada 512 amostras = **32 ms**, não a cada 20.
 *
 * **2. A unidade é SEGUNDOS, e isso foi verificado.** Ficou como incerteza aberta
 * na auditoria — se fosse milissegundos, um `12.0f` escrito de boa-fé viraria
 * 12 ms e a janela fecharia no meio da frase. Os padrões no bytecode resolvem:
 * `maxSpeechDuration = 5.0f` (5 ms seria um quarto de um quadro, absurdo para um
 * teto de fala) e `minSilenceDuration = 0.25f` (250 ms, valor clássico de VAD).
 * Segundos. *Verificado por `javap -c` no construtor sintético do AAR 1.13.5.*
 *
 * **3. Uma instância por finalidade, e é imposição do artefato.** `setConfig` é
 * só `putfield` — não reconstrói o objeto nativo. Reconfigurar em runtime **não
 * tem efeito**. Como o gatilho quer segmentos curtos e a transmissão precisa
 * tolerar 30 s, não há como servir os dois com um objeto só.
 *
 * ## O que ele NÃO resolve
 *
 * Continua detectando **ausência de fala**, não ausência da fala *do agente*. Quem
 * isola a voz de quem veste os óculos é o *beamforming* do hardware; o VAD só
 * decide se há voz no que chegou. Um interlocutor falando alto ao lado mantém a
 * janela aberta — e é por isso que a parada por toque continua existindo.
 */
class SileroVoiceActivityDetector(
    private val assets: AssetManager,
    private val sampleRateHz: Int = 16_000,
    /** Acima disto o quadro é considerado voz. Padrão do modelo. */
    private val limiar: Float = 0.5f,
    /** **Segundos.** Silêncio contíguo que fecha a janela. */
    private val silencioParaFecharS: Float = 0.6f,
    /** **Segundos.** Abaixo disto o segmento é descartado como ruído curto. */
    private val falaMinimaS: Float = 0.25f,
    /** **Segundos.** Teto de uma janela — ver o KDoc sobre as duas instâncias. */
    private val falaMaximaS: Float = 12.0f,
) : VoiceActivityDetector {

    /**
     * `Vad` é um handle nativo com `finalize()`. Criado por chamada de [segment] e
     * liberado no `finally`: manter um vivo entre invocações exigiria dono de
     * processo e válvula de memória, como o Whisper — e o modelo aqui tem 629 KB,
     * não 78 MB, então a carga é barata e não justifica.
     */
    private fun novoVad(): Vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODELO,
                threshold = limiar,
                minSilenceDuration = silencioParaFecharS,
                minSpeechDuration = falaMinimaS,
                windowSize = JANELA,
                maxSpeechDuration = falaMaximaS,
            ),
            sampleRate = sampleRateHz,
            numThreads = 1,
        ),
    )

    override fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment> = flow {
        val vad = novoVad()
        // Acumulador do re-quadrador. Ver o item 1 do KDoc: a captura entrega 320
        // e o nativo exige 512.
        val pendente = FloatArray(JANELA)
        var preenchido = 0

        try {
            pcm.collect { quadro ->
                var lido = 0
                while (lido < quadro.size) {
                    val cabe = minOf(JANELA - preenchido, quadro.size - lido)
                    for (i in 0 until cabe) {
                        // PCM 16-bit → float normalizado, como o modelo espera.
                        pendente[preenchido + i] = quadro[lido + i] / 32768.0f
                    }
                    preenchido += cabe
                    lido += cabe

                    if (preenchido == JANELA) {
                        vad.acceptWaveform(pendente.copyOf())
                        preenchido = 0
                        drenar(vad)
                    }
                }
            }
            // O fluxo acabou com fala aberta: `flush` fecha o que houver, para a
            // última frase não morrer com a captura.
            vad.flush()
            drenar(vad)
        } finally {
            // Handle nativo: sem isto o `finalize()` decide quando liberar, e
            // "quando o GC quiser" não é política de recurso.
            vad.release()
        }
    }

    /**
     * Tira do `Vad` tudo o que já fechou.
     *
     * O silêncio final **não** é declarado a partir de `silencioParaFecharS`: o
     * nativo devolve o segmento já recortado, sem o hangover anexado — diferente
     * do detector por energia, que entrega fala + silêncio. Por isso
     * `silencioFinalMs` é o próprio limiar de fechamento: é quanto o detector
     * esperou depois da última voz antes de decidir, e é isso que o consumidor
     * precisa descontar para achar o instante real do fim da fala.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<SpeechSegment>.drenar(vad: Vad) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val pcm16 = ShortArray(seg.samples.size) { i ->
                (seg.samples[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            }
            emit(
                SpeechSegment(
                    pcm = pcm16,
                    sampleRateHz = sampleRateHz,
                    silencioFinalMs = (silencioParaFecharS * 1000).toInt(),
                ),
            )
        }
    }

    private companion object {
        /** Caminho dentro de `assets/`. `noCompress` cobre `.onnx` — o `AssetManager` do sherpa precisa. */
        const val MODELO = "models/silero_vad.onnx"

        /**
         * 512 amostras = 32 ms a 16 kHz. **Não é escolha**: é o `windowSize`
         * padrão do `SileroVadModelConfig`, lido do bytecode do AAR, e o nativo
         * exige exatamente esse tamanho por chamada.
         */
        const val JANELA = 512
    }
}
