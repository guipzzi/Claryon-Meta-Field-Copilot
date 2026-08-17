package com.claryon.field.voice

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.claryon.voice.WhisperCppStt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Dono único do contexto do Whisper no processo — quente entre invocações.**
 *
 * ## O que custava
 *
 * `cicloDeVoz` construía um `WhisperCppStt` novo a cada volta e chamava
 * `release()` no `finally`. Como o contexto nativo é carregado na primeira
 * transcrição e morre com a instância, **todo ciclo que transcreve pagava a
 * carga inteira**: 77 691 713 B de `ggml-tiny.bin` lidos do asset e mapeados
 * pelo JNI, no caminho crítico de uma meta de 2,0 s.
 *
 * O número correto é "por ciclo **que transcreve**", não "por ciclo": a carga é
 * preguiçosa (`WhisperCppStt.transcribe` só abre o contexto quando chega PCM),
 * então o ciclo que estoura o timeout sem o VAD fechar janela não paga nada.
 *
 * ## Por que isto não é só "guardar num `object`"
 *
 * Manter ~78 MB nativos residentes **exige uma válvula**, e o app não tinha
 * nenhuma: `grep onTrimMemory` devolvia zero. O processo já segura o Piper, o
 * runtime do sherpa-onnx e o MapLibre, e roda sob *foreground service* com
 * microfone. Somar 78 MB permanentes sem devolvê-los sob pressão é entregar ao
 * *Low Memory Killer* a decisão de quem morre — e o que ele mata é o serviço de
 * rádio, que é a capacidade que não pode cair.
 *
 * Por isso [aoAparar] existe e é ligado no `Application`: sob
 * `TRIM_MEMORY_RUNNING_LOW` ou pior, o contexto é liberado. A próxima
 * transcrição paga a carga de novo, que é exatamente o negócio certo — melhor
 * um ciclo de voz lento que um rádio morto.
 *
 * ## Por que não `ThermalGovernor`
 *
 * O `ROADMAP` propunha liberar por política térmica. Descartado: `ThermalGovernor`
 * não tem consumidor em release (`fpsPermitidoAgora` só é chamado da tela de
 * diagnóstico, que vive em `src/debug`), então pendurar a liberação ali seria
 * construir sem chamador — o padrão que o `CLAUDE.md` §6 manda caçar. E o
 * critério estaria errado de qualquer forma: **contexto ocioso é RAM, não calor.**
 */
object EscutaDoAgente {

    private val trava = Mutex()

    @Volatile
    private var instancia: WhisperCppStt? = null

    /**
     * O motor, ou `null` se o modelo não está embarcado nem em `filesDir`.
     *
     * `null` é estado legítimo e **audível**: o ciclo de voz degrada e diz que o
     * STT está indisponível, em vez de fingir que ouviu.
     */
    suspend fun de(context: Context): WhisperCppStt? = trava.withLock {
        instancia ?: Modelos.whisper(context.applicationContext)?.also {
            instancia = it
            Log.i(TAG, "whisper carregado e mantido quente")
        }
    }

    /**
     * Devolve os ~78 MB nativos ao sistema.
     *
     * Chamado pelo `Application.onTrimMemory`. **Não** é chamado no fim de cada
     * ciclo de voz — é justamente isso que o mantém quente.
     */
    suspend fun liberar() = trava.withLock {
        instancia?.let {
            it.release()
            Log.i(TAG, "whisper liberado por pressão de memória")
        }
        instancia = null
    }

    /**
     * Traduz o nível do `onTrimMemory` em decisão.
     *
     * `TRIM_MEMORY_RUNNING_LOW` (não `MODERATE`) é o corte: abaixo dele o sistema
     * só está pedindo educadamente, e devolver o modelo a cada aviso leve faria o
     * copiloto recarregar 78 MB o tempo todo — trocaria um risco por um custo
     * garantido.
     *
     * `TRIM_MEMORY_UI_HIDDEN` também libera: o app foi para segundo plano, e o
     * ciclo de voz não roda sem tela. O rádio continua, e é ele que precisa da
     * memória.
     */
    fun deveLiberar(nivel: Int): Boolean =
        nivel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            nivel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    private const val TAG = "ClaryonField"
}
