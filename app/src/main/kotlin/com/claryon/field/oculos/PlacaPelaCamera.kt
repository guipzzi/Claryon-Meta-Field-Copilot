package com.claryon.field.oculos

import android.util.Log
import com.claryon.agent.Utterance
import com.claryon.field.vision.FrameParaBitmap
import com.claryon.field.vision.PlacaOcr
import com.claryon.glasses.Frame
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * **O OCR de placa ligado ao frame dos óculos — e o descarte, no mesmo lugar.**
 *
 * Converte o frame em luminância, entrega ao [PlacaOcr] e **recicla o bitmap no
 * `finally`**. O `finally` não é zelo de memória: é onde o contrato de efemeridade
 * do `CLAUDE.md` §2 acontece de fato. Um caminho de exceção que pulasse o
 * `recycle()` deixaria o quadro de uma abordagem vivo em memória nativa até o GC
 * decidir o contrário.
 *
 * Os contadores existem para que o teste possa **afirmar** o descarte em vez de
 * confiar nele: [convertidos] e [reciclados] têm de terminar iguais.
 */
class OcrDeFrame(private val ocr: PlacaOcr) : LeitorDeFrame {

    private val _convertidos = AtomicInteger(0)
    private val _reciclados = AtomicInteger(0)

    /** Frames que viraram bitmap. Frame recusado (comprimido, curto) não conta. */
    val convertidos: Int get() = _convertidos.get()

    /** Bitmaps devolvidos à memória nativa. Tem de bater com [convertidos]. */
    val reciclados: Int get() = _reciclados.get()

    override suspend fun placaEm(frame: Frame): String? {
        val bitmap = FrameParaBitmap.luminancia(frame) ?: return null
        _convertidos.incrementAndGet()
        return try {
            ocr.lerPlaca(bitmap)
        } catch (e: CancellationException) {
            // **Não é falha do reconhecedor: é a janela de 5 s fechando, ou a placa
            // já tendo sido lida.** `CancellationException` é `Exception` no JVM, e
            // engoli-la no `catch` abaixo faria este frame virar "não achei" em vez
            // de encerrar a coleta — a corrotina seguiria viva depois do escopo
            // morto. Mesmo cuidado que `cicloDeVoz` e `RadioTatico` já tomam.
            throw e
        } catch (e: Exception) {
            // O reconhecedor falhando num frame não é motivo para derrubar a
            // captura: o próximo frame chega em ~140 ms. Falha nunca é silêncio,
            // então vai ao log; e nunca vira placa, porque o retorno é `null`.
            Log.w(TAG, "OCR falhou neste frame: ${e.message}")
            null
        } finally {
            // Depois de o reconhecedor devolver, e não antes: o ML Kit lê o bitmap
            // enquanto a Task não completa.
            bitmap.recycle()
            _reciclados.incrementAndGet()
        }
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}

/**
 * **A montagem de processo do caminho de câmera.**
 *
 * Dona de processo pelo mesmo motivo de [SessaoDosOculos] e de
 * `com.claryon.field.voice.EscutaDoAgente`: o `TextRecognizer` do ML Kit carrega o
 * modelo Latin nativo (`libmlkit_google_ocr_pipeline.so`, 11 MB no APK) e
 * construí-lo por consulta pagaria essa carga dentro da janela de 5 s — o mesmo
 * defeito que o whisper tinha quando era construído a cada ciclo de voz.
 *
 * A sessão do DAT **não** é aberta aqui. Ela precisa estar de pé antes do pedido:
 * `startSession()` tem teto de 12 s contra os 5 s que o aceite dá à captura inteira.
 * Quem a mantém de pé é o vigia de [SessaoDosOculos], instalado no início do turno.
 */
object PlacaPelaCamera {

    private val leitor by lazy { OcrDeFrame(PlacaOcr()) }

    private val _tentativas = AtomicInteger(0)

    /**
     * Quantas capturas foram pedidas desde que o processo subiu.
     *
     * Existe para uma pergunta específica, que é a do §6 do `CLAUDE.md`: **este
     * caminho é alcançável em runtime, ou é só uma classe testada?** O executor
     * responde `CONSULTA_INDISPONIVEL` tanto quando a câmera falha quanto quando a
     * dependência **nem foi injetada** — as duas respostas são idênticas, e um teste
     * que só olhasse o `ActionOutcome` ficaria verde com a composição desligada.
     * Este contador distingue as duas, e é o que `CaminhoDaPlacaEmProducaoTest`
     * afirma.
     */
    val tentativas: Int get() = _tentativas.get()

    /**
     * O código da última causa de [LeituraDePlaca.SemCamera], ou `null`.
     *
     * Guarda **código**, não placa e não frame: é diagnóstico de caminho, e um campo
     * que retivesse resultado de consulta seria estado sensível vivendo por tempo
     * indeterminado num objeto de processo.
     */
    @Volatile
    var ultimaCausa: String? = null
        private set

    /**
     * Lê uma placa pela câmera dos óculos.
     *
     * **Não há costura de teste aqui, e é de propósito.** A tentação seria um
     * `instalar(leitor)` como o de `SessaoDosOculos` — mas [CapturaDePlaca] e
     * [OcrDeFrame] já recebem tudo por construtor, então os testes montam a
     * combinação que precisam sem passar por este objeto. Uma costura pública sem
     * chamador é a mesma classe de coisa que este bloco veio consertar.
     *
     * @param avisar por onde a instrução falada sai. Injetado para que este objeto
     *   não conheça a fila de saída — quem sabe falar é `SaidaUnica`, e quem sabe
     *   ler é este.
     */
    suspend fun ler(avisar: suspend (Utterance) -> Unit): LeituraDePlaca {
        _tentativas.incrementAndGet()
        return CapturaDePlaca(
            facade = { SessaoDosOculos.facade() },
            leitor = leitor,
            avisar = avisar,
        ).ler().also { r ->
            ultimaCausa = (r as? LeituraDePlaca.SemCamera)?.codigo
        }
    }
}
