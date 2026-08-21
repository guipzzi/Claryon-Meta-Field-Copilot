package com.claryon.field.oculos

import android.util.Log
import com.claryon.agent.Utterance
import com.claryon.common.Priority
import com.claryon.common.Result
import com.claryon.glasses.CameraProfile
import com.claryon.glasses.Frame
import com.claryon.glasses.GlassesFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * O que a leitura pela câmera produziu.
 *
 * Três estados, e a separação entre os dois últimos é a razão do tipo existir:
 * *"apontei e o aparelho não conseguiu ler"* e *"a câmera nem abriu"* pedem coisas
 * diferentes do agente — aproximar-se × abrir as hastes — e um `String?` as
 * colapsaria numa só, que é como se constrói uma resposta que não ajuda ninguém.
 */
sealed interface LeituraDePlaca {

    /** Sete caracteres que passaram por `PlacaValidator`. Nada de imagem sai daqui. */
    data class Lida(val placa: String, val frames: Int, val duracaoMs: Long) : LeituraDePlaca

    /** A câmera entregou [frames] frames e nenhum tinha placa legível. */
    data class Ilegivel(val frames: Int, val duracaoMs: Long) : LeituraDePlaca

    /**
     * A câmera não abriu, ou abriu e não entregou imagem. [codigo] e [frase] vêm da
     * causa tipada do stream (`glasses.stream_error.HINGE_CLOSED` →
     * *"Óculos dobrados. Abra as hastes."*), e não do sintoma genérico.
     */
    data class SemCamera(val codigo: String, val frase: String) : LeituraDePlaca
}

/**
 * Quem transforma um frame em placa. **Interface, e não [PlacaOcr] direto**, porque
 * assim [CapturaDePlaca] — que é onde moram a janela de 5 s, a parada no primeiro
 * acerto e o descarte — fica testável em JVM: `Bitmap` e ML Kit não existem fora do
 * aparelho, e prender a lógica do subfluxo a eles a tornaria só verificável por
 * teste instrumentado.
 */
fun interface LeitorDeFrame {
    /** @return a placa válida encontrada no frame, ou `null`. */
    suspend fun placaEm(frame: Frame): String?
}

/**
 * **O subfluxo de captura: fala, abre a câmera, lê, e devolve sete caracteres.**
 *
 * Passos 6 a 11 de `specs/consulta-de-placa-por-camera.spec.md`. Existe porque
 * `Intent.ConsultarPlaca(placa = null)` — o agente que diz *"Claryon, verifica a
 * placa desse carro"* sem ditar a placa — não tinha nenhum caminho: o executor
 * respondia `CONSULTA_INDISPONIVEL` e a câmera dos óculos nunca era aberta por
 * ninguém. `withCamera`, `capturePhoto` e `PlacaOcr.lerPlaca` tinham **zero
 * chamadores em `src/main`** antes deste arquivo.
 *
 * ## Os frames vivem em RAM e morrem no OCR
 *
 * Regra dura do `CLAUDE.md` §2 — reconhecimento facial ou base biométrica é
 * proibido, sem versão e sem flag —, e uma abordagem tem gente no enquadramento. O
 * mesmo contrato do pré-roll do PTT: nada é persistido, indexado ou enviado. O que
 * atravessa a fronteira deste objeto é [LeituraDePlaca], que carrega **string**, e
 * o `Bitmap` é reciclado dentro de [LeitorDeFrame] assim que o reconhecedor
 * devolve. É asserção, não promessa: `FramesEfemerosTest` varre `filesDir`,
 * `cacheDir` e `getExternalFilesDir` exigindo zero arquivo novo, e tem contra-teste.
 *
 * ## A instrução sai antes da câmera, e não espera
 *
 * [INSTRUCAO] é enfileirada e o fluxo segue: `emitir` não bloqueia (a fila de saída
 * conduz a ordem), e prender a abertura da câmera à síntese custaria ~1 s da janela
 * de 5 s. O preço é que os primeiros frames chegam com o agente ainda ouvindo o que
 * fazer — e por isso a leitura **não** para no primeiro frame, para no primeiro
 * frame com placa.
 */
class CapturaDePlaca(
    /**
     * Resolvida a cada chamada, e não guardada no construtor: a fachada é do
     * processo ([SessaoDosOculos]), e uma referência capturada aqui sobreviveria a
     * um `instalar()` de teste apontando para a instância errada.
     */
    private val facade: () -> GlassesFacade,
    private val leitor: LeitorDeFrame,
    /** Enfileira o que o agente ouve. Não sintetiza aqui: quem fala é a saída única. */
    private val avisar: suspend (Utterance) -> Unit,
    private val agoraMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val janelaMs: Long = JANELA_MS,
) {

    /**
     * Fala a instrução, abre a câmera e lê até achar placa ou estourar a janela.
     *
     * Nunca lança: o executor que a chama tem "nunca lança" como invariante, e uma
     * exceção do SDK subindo daqui viraria `FalhaOperacional.INTERNA` — perdendo a
     * causa que o `errorStream` já tinha entregue.
     */
    suspend fun ler(): LeituraDePlaca {
        avisar(INSTRUCAO)

        var frames = 0
        var placa: String? = null
        val inicio = agoraMs()

        val resultado = try {
            facade().withCamera(CameraProfile.OCR) { fluxo ->
                // O teto de 5 s é o aceite da spec, e vale para a coleta inteira —
                // não por frame. `withTimeoutOrNull` aqui e não em volta de
                // `withCamera`: estourar por fora cancelaria o `finally` que baixa a
                // câmera, e a câmera ficaria de pé depois de a consulta desistir.
                withTimeoutOrNull(janelaMs) {
                    placa = fluxo
                        .onEach { frames++ }
                        .map { leitor.placaEm(it) }
                        // Para no PRIMEIRO acerto: cada frame a mais é bateria e
                        // atraso, e o agente está parado na frente do veículo.
                        // `firstOrNull` cancela a coleta a montante, o que faz o
                        // `finally` de `withCamera` baixar a câmera na hora.
                        .firstOrNull { it != null }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // `withCamera` devolve `Result`, mas o SDK por baixo pode lançar. Sem
            // isto, a exceção subiria até o executor e viraria "Falha interna." —
            // três palavras que não dizem nada ao agente.
            Log.e(TAG, "captura de placa lançou", e)
            return LeituraDePlaca.SemCamera("glasses.camera_threw", "Câmera falhou. Tente de novo.")
        }

        val duracao = agoraMs() - inicio
        val lida = placa

        return when {
            lida != null -> {
                Log.i(TAG, "placa lida em $frames frame(s), ${duracao}ms")
                LeituraDePlaca.Lida(lida, frames, duracao)
            }
            // `withCamera` só devolve `Failure` quando NENHUM frame chegou — e aí a
            // causa é do stream, não do OCR. Dizer "placa ilegível" com os óculos
            // dobrados mandaria o agente aproximar-se de um veículo que o aparelho
            // nunca viu.
            resultado is Result.Failure -> {
                Log.w(TAG, "câmera não entregou imagem: ${resultado.error.code}")
                LeituraDePlaca.SemCamera(resultado.error.code, resultado.error.message)
            }
            else -> {
                Log.i(TAG, "nenhuma placa em $frames frame(s), ${duracao}ms")
                LeituraDePlaca.Ilegivel(frames, duracao)
            }
        }
    }

    companion object {
        /**
         * *"Aponte para a placa."* — quatro palavras, contra o teto de sete de
         * `LaconicityPolicy`. Sustentado por teste, como toda fala operacional.
         */
        val INSTRUCAO: Utterance = Utterance.Falar("Aponte para a placa.", Priority.RESPOSTA)

        /**
         * Teto da coleta, do aceite da spec.
         *
         * Não é folgado: `DatGlassesFacade.PRAZO_PRIMEIRO_FRAME_MS` é 6 s, então uma
         * câmera que nunca entrega imagem é abandonada por ESTE prazo antes do vigia
         * dela — que é o certo, porque quem espera é o agente, não o SDK.
         */
        const val JANELA_MS = 5_000L

        private const val TAG = "ClaryonField"
    }
}
