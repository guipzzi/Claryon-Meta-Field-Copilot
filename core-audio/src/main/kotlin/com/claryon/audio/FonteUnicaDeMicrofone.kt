package com.claryon.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * **Um `AudioRecord` por rota, N consumidores.**
 *
 * ## O que estava errado
 *
 * `microfonePcm` construía um `AudioRecord` novo por chamada, e o produto chama
 * mais de uma vez ao mesmo tempo. Em `RadioTatico`, `entrarEmModoAtivo` abre um
 * fluxo de longa duração para alimentar o pré-roll e `aoPressionar` abre **outro**
 * para a captura ao vivo — os dois vivos durante toda a transmissão. Some as três
 * chamadas de `DiagnosticsViewModel` e o teto sobe.
 *
 * Dois `AudioRecord` concorrentes com `VOICE_COMMUNICATION` não é cenário que o
 * Android garanta: a política de captura concorrente decide quem recebe áudio e
 * quem recebe silêncio, e a decisão não é estável entre fabricantes. O modo de
 * falha observável é o pior possível num rádio — o pré-roll enche de silêncio e a
 * primeira sílaba, que é justamente o que ele existe para salvar, some.
 *
 * Além disso, `startRecording()` custa dezenas de milissegundos. Pagá-los no
 * instante do toque no PTT é pagá-los no único ponto do produto onde não há folga.
 * Com a fonte compartilhada o microfone **já está aberto** quando o dedo desce: a
 * captura ao vivo entra como mais um assinante de um fluxo quente.
 *
 * ## Como a exclusividade é sustentada
 *
 * Não por convenção. [GlassesAudioManagerImpl.microfonePcm] passou a devolver uma
 * vista desta fonte, e ele é o **único** lugar do projeto que constrói um
 * `AudioRecord`. Não há caminho novo que possa esquecer de usá-la: para capturar é
 * preciso passar por aqui.
 *
 * A contagem de referência é a assinatura do [SharedFlow], via
 * [SharingStarted.WhileSubscribed]: o `AudioRecord` abre no primeiro assinante e
 * fecha quando sai o último. Sem `stopTimeout` — microfone aberto sem ninguém
 * ouvindo é gravação ambiente, e num produto que promete não captar terceiros isso
 * não é detalhe de bateria.
 *
 * ## Falha não pode virar silêncio no caminho
 *
 * Um `SharedFlow` **não propaga exceção** para os assinantes: se a captura
 * estourar, o fluxo simplesmente para de emitir e cada consumidor vê um `collect`
 * que nunca mais devolve nada. Seria trocar "óculos caíram" por "ninguém está
 * falando" — a confusão exata que este produto não pode ter.
 *
 * Por isso a falha viaja **como valor** ([Sinal.Falha]) e é convertida de volta em
 * exceção na borda, por consumidor. Do lado de fora, o contrato é o mesmo de
 * antes: um `Flow<ShortArray>` que lança `RotaDeAudioPerdidaException` ou
 * `AudioCaptureException`. Nada nos chamadores muda.
 *
 * ## Consumidor lento não pode travar o microfone
 *
 * O cofre de evidência escreve em disco; o codec Opus tem pipeline. Se um deles
 * atrasar, o laço de leitura do `AudioRecord` **não** pode esperar por ele — o
 * buffer do driver estoura e a perda é de áudio real, não de processamento.
 *
 * Cada consumidor recebe seu próprio [buffer] com [BufferOverflow.DROP_OLDEST]:
 * quem atrasa perde os quadros antigos **dele**, e os outros não sentem. O
 * descarte é contado e chega ao consumidor pela lacuna na [Sinal.Amostras.sequencia]
 * — perder áudio em silêncio seria a mesma mentira, um nível abaixo.
 */
internal class FonteUnicaDeMicrofone(
    private val escopo: CoroutineScope,
    private val confereRota: (GlassesAudioRoute) -> Boolean,
    private val abrirCaptura: () -> CapturaBruta,
    private val aoDescartar: (quantos: Int) -> Unit = {},
) {

    /**
     * O que a fonte emite. Falha é **valor** e não exceção porque um `SharedFlow`
     * engole exceção — ver o KDoc da classe.
     */
    private sealed interface Sinal {
        class Amostras(val sequencia: Long, val pcm: ShortArray) : Sinal
        class Falha(val causa: Throwable) : Sinal
    }

    private val trava = Any()
    private var fonte: SharedFlow<Sinal>? = null
    private var rotaDaFonte: GlassesAudioRoute? = null

    /**
     * Vista de consumidor: um `Flow<ShortArray>` idêntico ao contrato antigo.
     *
     * O [buffer] é por consumidor **de propósito** — ver o KDoc da classe.
     */
    fun pcm(rota: GlassesAudioRoute): Flow<ShortArray> {
        var esperada = -1L
        return compartilhada(rota)
            .buffer(CAPACIDADE_POR_CONSUMIDOR, BufferOverflow.DROP_OLDEST)
            .map { sinal ->
                when (sinal) {
                    is Sinal.Falha -> throw sinal.causa
                    is Sinal.Amostras -> {
                        if (esperada >= 0 && sinal.sequencia > esperada) {
                            aoDescartar((sinal.sequencia - esperada).toInt())
                        }
                        esperada = sinal.sequencia + 1
                        sinal.pcm
                    }
                }
            }
    }

    /**
     * Devolve a fonte da [rota], criando-a se preciso.
     *
     * Rota diferente da corrente **substitui** a fonte. A antiga não é cancelada à
     * força: ela morre sozinha na próxima reconferência periódica, em no máximo
     * [MS_ENTRE_CONFERENCIAS_DE_ROTA] ms, e morre pelo caminho certo — lançando
     * `RotaDeAudioPerdidaException` para quem estava capturando por uma rota que
     * não existe mais. Matá-la aqui entregaria aos consumidores um fluxo que
     * termina sem dizer por quê.
     */
    private fun compartilhada(rota: GlassesAudioRoute): SharedFlow<Sinal> = synchronized(trava) {
        val atual = fonte
        if (atual != null && rotaDaFonte == rota) return atual
        val nova = laco(rota).shareIn(
            scope = escopo,
            // Sem `stopTimeout`: o microfone fecha no instante em que o último
            // consumidor sai. Ver o KDoc da classe.
            started = SharingStarted.WhileSubscribed(),
            replay = 0,
        )
        fonte = nova
        rotaDaFonte = rota
        return nova
    }

    /**
     * O laço de leitura. Roda **uma vez** por rota, por mais consumidores que
     * existam.
     *
     * A reconferência de rota acontece **durante** o stream e não só na abertura.
     * Antes, a prova era tirada no `iniciar()` e a captura podia começar segundos
     * depois e durar minutos: qualquer queda de HFP no meio — óculos dobrados,
     * fone desligado, ligação entrando — fazia o sistema escolher um substituto
     * (o microfone omnidirecional do celular) e o `AudioRecord` seguia lendo dele
     * sem que ninguém percebesse. Com o PTT, esse áudio ia para a guarnição
     * inteira. A janela de exposição passou de "o que durar a captura" para
     * [MS_ENTRE_CONFERENCIAS_DE_ROTA] ms.
     */
    private fun laco(rota: GlassesAudioRoute): Flow<Sinal> = flow {
        try {
            if (!confereRota(rota)) throw RotaDeAudioPerdidaException()
            val captura = abrirCaptura()
            var sequencia = 0L
            var msDesdeAConferencia = 0L
            try {
                while (true) {
                    val quadro = captura.ler()
                    msDesdeAConferencia += captura.duracaoDoQuadroMs
                    if (msDesdeAConferencia >= MS_ENTRE_CONFERENCIAS_DE_ROTA) {
                        msDesdeAConferencia = 0
                        if (!confereRota(rota)) throw RotaDeAudioPerdidaException()
                    }
                    // `null` = leitura vazia legítima; não é quadro e não gasta
                    // sequência, senão o detector de lacuna acusaria descarte que
                    // não houve.
                    if (quadro != null) emit(Sinal.Amostras(sequencia++, quadro))
                }
            } finally {
                captura.fechar()
            }
        } catch (e: CancellationException) {
            // Cancelamento é o último consumidor saindo. Não é falha e não vira
            // earcon: repropagar é o que faz `WhileSubscribed` parar de verdade.
            throw e
        } catch (e: Throwable) {
            emit(Sinal.Falha(e))
        }
    }

    /**
     * O `AudioRecord` visto pelo laço. Existe para que a política acima seja
     * testável em JVM — o mecanismo é o que precisa de aparelho, não a regra de
     * quantos abrem e quando fecham.
     */
    internal interface CapturaBruta {
        /** Duração nominal de um quadro, para o relógio da reconferência. */
        val duracaoDoQuadroMs: Long

        /**
         * Espera até haver amostras. `null` = leitura vazia legítima.
         *
         * `suspend` embora a implementação real bloqueie a thread (é o que
         * `AudioRecord.read` faz, e é correto sob `Dispatchers.IO`): marcar a
         * espera no tipo é o que dá ao laço um ponto de cancelamento a cada
         * quadro — sem ele, o último consumidor sair não pararia a leitura até a
         * próxima emissão — e é o que permite ao teste dirigir a captura em tempo
         * virtual em vez de girar a CPU.
         */
        suspend fun ler(): ShortArray?

        fun fechar()
    }

    private companion object {
        /**
         * 200 ms. É o teto da janela em que a captura pode estar vindo do
         * microfone errado — ver o KDoc de [laco]. Mais curto multiplicaria uma
         * chamada de binder sem ganho real; mais longo aumentaria a janela de uma
         * violação de compliance para poupar microssegundos.
         */
        const val MS_ENTRE_CONFERENCIAS_DE_ROTA = 200L

        /**
         * 50 quadros = 1 s por consumidor. Cobre uma pausa de coleta longa (GC,
         * escrita em disco do cofre) sem deixar um consumidor travado acumular
         * memória indefinidamente.
         */
        const val CAPACIDADE_POR_CONSUMIDOR = 50
    }
}
