package com.claryon.net

import com.claryon.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** O que o receptor precisa contar a quem está por cima. */
sealed interface EventoRecepcao {

    /** Alguém vai falar. Momento de aquecer a saída e tocar o clique de canal. */
    data class Chegando(val anuncio: AnuncioDeFala) : EventoRecepcao

    /**
     * **Áudio começou a tocar sem que o anúncio tivesse chegado.**
     *
     * Acontece de verdade e por dois caminhos: o agente liga o rádio (ou entra no
     * talk group) no meio de uma fala, e o anúncio se perde. Nos dois, o áudio é
     * reproduzido — calar uma voz que pode ser um pedido de apoio real seria a
     * falha oposta e pior.
     *
     * Existe porque a alternativa observada era **voz sem autor**: `Chegando` só
     * dispara no anúncio, e quem entrou depois dele ouvia alguém falando com a
     * tela dizendo que ninguém falava. Este evento é a admissão honesta de
     * "alguém está falando e eu ainda não sei quem" — e é o gancho para
     * perguntar ao servidor de quem é o piso desta transmissão.
     */
    data class ChegandoSemAnuncio(val transmissaoId: String) : EventoRecepcao

    /** PCM pronto para o alto-falante, **na taxa do decodificador**. */
    data class Audio(val pcm: ShortArray, val taxaHz: Int) : EventoRecepcao {
        override fun equals(other: Any?) =
            other is Audio && taxaHz == other.taxaHz && pcm.contentEquals(other.pcm)
        override fun hashCode() = 31 * pcm.contentHashCode() + taxaHz
    }

    /**
     * O texto da fala, transcrito pela origem.
     *
     * Carrega o `transmissaoId` porque **não** chega junto com o áudio: vem depois
     * do fim da fala, e outra transmissão pode ter começado no meio. Quem exibe casa
     * pela chave, não pela ordem de chegada.
     */
    data class TextoDaFala(val transmissaoId: String, val texto: String) : EventoRecepcao

    /**
     * Fim da fala.
     *
     * @param motivo **como** a fala acabou. Sem ele, uma fala inteira e uma fala
     *   cortada no meio pela rede chegavam à camada de cima como o mesmo evento,
     *   campo a campo — quem ouviu não tinha como saber que perdeu o final, e
     *   [perdidos] vem zerado justamente no caso truncado, porque o receptor não
     *   sabe quantos quadros nunca existiram.
     */
    data class Terminou(
        val transmissaoId: String,
        val quadros: Int,
        val perdidos: Int,
        val motivo: FimDaFala,
    ) : EventoRecepcao

    /**
     * Canal degradado: perda acima de 30%. **O agente precisa saber que o rádio
     * está ruim antes de confiar nele** — decidir uma abordagem contando com um
     * apoio que talvez não tenha ouvido é pior que saber que não ouviu.
     */
    data object CanalDegradado : EventoRecepcao
}

/**
 * **Como uma fala recebida terminou.** A distinção existe porque a ação de quem
 * ouviu muda: fala encerrada é fala inteira; fala cortada pede *"repita o final"*.
 */
enum class FimDaFala {

    /**
     * O emissor encerrou: veio o quadro `ultimo`, ou o `fim de transmissão` do
     * transporte. A frase chegou até o ponto final.
     */
    ENCERRADA_PELO_EMISSOR,

    /**
     * **O emissor sumiu no meio.** Túnel, bateria, app morto — nada anunciou o
     * fim, e o receptor concluiu por silêncio depois de esperar a janela inteira
     * de jitter. O último pedaço da fala **não** chegou, e o que a tela mostra
     * está incompleto.
     */
    CORTADA_NO_MEIO,
}

/**
 * **Lado receptor do rádio tático.**
 *
 * Junta as peças que já existiam isoladas: eventos do transporte entram no
 * [BufferDeJitter], saem no ritmo do quadro, passam pelo [CodecDeVoz] e viram
 * PCM. Quadro que não chegou vira PLC, nunca silêncio.
 *
 * O laço roda **por tempo**, não por chegada: [BufferDeJitter.proximo] é chamado
 * a cada intervalo de quadro, porque é assim que se toca áudio. Reagir à chegada
 * dos pacotes reproduziria o jitter da rede diretamente no alto-falante — que é
 * exatamente o que o buffer existe para impedir.
 *
 * @param aoEmitirAudio chamado com PCM pronto. Quem consome liga ao `AudioTrack`
 *   **na taxa informada** — ver [CodecDeVoz.taxaDeSaidaHz], que não é a de entrada.
 */
class Receptor(
    private val transporte: TransporteAoVivo,
    private val codec: CodecDeVoz,
    private val escopo: CoroutineScope,
    private val quadroMs: Long = 20,
    private val jitter: BufferDeJitter = BufferDeJitter(),
    /**
     * `null` por padrão — instrumentação é opt-in, para não obrigar todo teste
     * existente a aprender um parâmetro novo. Ver [SessaoPtt.telemetria].
     */
    private val telemetria: TelemetriaDoRadio? = null,
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) {

    private var coleta: Job? = null
    private var reproducao: Job? = null

    @Volatile private var transmissaoCorrente: String? = null
    private var quadrosTocados = 0
    private var quadrosPerdidos = 0
    private var degradadoAvisado = false

    /**
     * O emissor avisou que acabou (`fim de transmissão` do transporte), mesmo que
     * o quadro `ultimo` tenha se perdido.
     *
     * Com ele, a espera cai de [ESPERAS_ATE_DESISTIR] para [ESPERAS_APOS_FIM] — o
     * bastante para drenar o que ainda estava no buffer de jitter — e o desfecho é
     * [FimDaFala.ENCERRADA_PELO_EMISSOR], porque a fala de fato terminou. O
     * evento chegava e era descartado com o comentário "o quadro `ultimo`
     * encerra", que é verdade só quando ele chega.
     */
    @Volatile private var emissorAnunciouFim = false

    /** Começa a ouvir o talk group. Idempotente. */
    fun iniciar(aoEvento: suspend (EventoRecepcao) -> Unit) {
        if (coleta?.isActive == true) return

        coleta = escopo.launch {
            transporte.eventos().collect { evento ->
                when (evento) {
                    is EventoDeRede.Anuncio -> {
                        // Nova fala: zera o buffer para não misturar com a anterior.
                        jitter.reiniciar()
                        quadrosTocados = 0
                        quadrosPerdidos = 0
                        degradadoAvisado = false
                        emissorAnunciouFim = false
                        transmissaoCorrente = evento.anuncio.transmissaoId
                        aoEvento(EventoRecepcao.Chegando(evento.anuncio))
                        iniciarReproducao(aoEvento)
                    }

                    is EventoDeRede.Quadro -> {
                        // Quadro sem anúncio acontece: o anúncio pode ter se
                        // perdido, ou o agente entrou no grupo depois dele.
                        // Começar mesmo assim é melhor que ficar mudo — mas
                        // começar **em silêncio** era o defeito: a voz tocava e a
                        // camada de cima não tinha evento nenhum para dizer que
                        // alguém estava falando, muito menos quem.
                        if (transmissaoCorrente == null) {
                            transmissaoCorrente = evento.quadro.transmissaoId
                            emissorAnunciouFim = false
                            aoEvento(
                                EventoRecepcao.ChegandoSemAnuncio(evento.quadro.transmissaoId),
                            )
                            iniciarReproducao(aoEvento)
                        }
                        jitter.receber(evento.quadro)
                    }

                    // **Não é mais descartado.** O `ultimo` encerra quando chega;
                    // quando ele se perde, este evento é a única prova de que a
                    // fala terminou por vontade do emissor — e a diferença entre
                    // esperar 2 s e esperar 200 ms para dizer isso à tela.
                    is EventoDeRede.FimDeTransmissao -> {
                        if (evento.transmissaoId == transmissaoCorrente) emissorAnunciouFim = true
                    }

                    // **Fora do laço de reprodução, e é o ponto do item.** O texto
                    // não entra no buffer de jitter nem espera o áudio drenar: ele
                    // sai direto para quem exibe. Roteá-lo pelo laço o atrasaria pela
                    // duração da fala inteira e, pior, o amarraria à transmissão que
                    // estiver tocando — que pode já ser outra.
                    is EventoDeRede.Transcricao ->
                        aoEvento(EventoRecepcao.TextoDaFala(evento.transmissaoId, evento.texto))

                    // Estado de canal não é assunto do receptor: quem decide o
                    // que fazer com autorização negada é o transporte, e quem
                    // mostra é a tela. Enumerados de propósito em vez de um
                    // `else` — `else` engoliria em silêncio o próximo evento que
                    // alguém acrescentar, que é exatamente o defeito que a
                    // `CanalRecusado` existe para consertar.
                    EventoDeRede.CanalPronto,
                    is EventoDeRede.CanalRecusado,
                    -> Unit
                }
            }
        }
    }

    /**
     * Laço de reprodução: consome o buffer de jitter no ritmo do quadro.
     *
     * **Encerra sozinho depois de [ESPERAS_ATE_DESISTIR] intervalos sem nada a
     * tocar.** Girar indefinidamente a 50 acordadas por segundo enquanto ninguém
     * fala é desperdício de bateria num aparelho que precisa durar o turno — e o
     * silêncio é o estado normal de um rádio. O próximo anúncio ou quadro
     * recomeça o laço.
     */
    private fun iniciarReproducao(aoEvento: suspend (EventoRecepcao) -> Unit) {
        if (reproducao?.isActive == true) return
        reproducao = escopo.launch {
            var esperasSeguidas = 0
            while (isActive) {
                when (val saida = jitter.proximo()) {
                    is SaidaDoJitter.Reproduzir -> {
                        esperasSeguidas = 0
                        quadrosTocados++
                        telemetria?.contar(TelemetriaDoRadio.QUADROS_RECEBIDOS)
                        // Mede decodificação + entrega ao consumidor: é o trecho
                        // que este processo controla. O que vem depois (buffer do
                        // AudioTrack, elo SCO) não é observável daqui — ver o
                        // KDoc de `TelemetriaDoRadio` sobre boca a ouvido.
                        val antes = agoraMs()
                        emitir(codec.decodificar(saida.quadro.payload), aoEvento)
                        telemetria?.registrar(
                            TelemetriaDoRadio.Metrica.RECEPCAO_ATE_AUDIO,
                            agoraMs() - antes,
                        )
                    }

                    is SaidaDoJitter.Interpolar -> {
                        esperasSeguidas = 0
                        quadrosPerdidos++
                        telemetria?.contar(TelemetriaDoRadio.QUADROS_PERDIDOS)
                        // `null` = perda. Silêncio soa como corte; a interpolação
                        // soa como voz degradada.
                        emitir(codec.decodificar(null), aoEvento)
                    }

                    SaidaDoJitter.Aguardando -> {
                        esperasSeguidas++
                        // Duas esperas, porque são duas perguntas diferentes. Se o
                        // emissor ANUNCIOU o fim, só falta drenar o jitter, e são
                        // 200 ms. Se ele sumiu, a espera é a janela inteira de
                        // tolerância — encurtá-la cortaria fala boa que atrasou.
                        val limite =
                            if (emissorAnunciouFim) ESPERAS_APOS_FIM else ESPERAS_ATE_DESISTIR
                        if (esperasSeguidas >= limite) {
                            // Emissor sumiu no meio (bateria, túnel) e o `ultimo`
                            // nunca chegou. Encerrar é melhor que segurar o canal:
                            // a próxima fala precisa de um laço limpo.
                            encerrarFala(
                                if (emissorAnunciouFim) {
                                    FimDaFala.ENCERRADA_PELO_EMISSOR
                                } else {
                                    FimDaFala.CORTADA_NO_MEIO
                                },
                                aoEvento,
                            )
                            return@launch
                        }
                    }

                    SaidaDoJitter.Fim -> {
                        encerrarFala(FimDaFala.ENCERRADA_PELO_EMISSOR, aoEvento)
                        return@launch
                    }
                }

                if (jitter.degradado() && !degradadoAvisado) {
                    degradadoAvisado = true
                    aoEvento(EventoRecepcao.CanalDegradado)
                }

                delay(quadroMs)
            }
        }
    }

    private suspend fun encerrarFala(
        motivo: FimDaFala,
        aoEvento: suspend (EventoRecepcao) -> Unit,
    ) {
        val id = transmissaoCorrente ?: return
        transmissaoCorrente = null
        emissorAnunciouFim = false
        aoEvento(EventoRecepcao.Terminou(id, quadrosTocados, quadrosPerdidos, motivo))
    }

    private suspend fun emitir(r: Result<ShortArray>, aoEvento: suspend (EventoRecepcao) -> Unit) {
        val pcm = (r as? Result.Success)?.value ?: return
        if (pcm.isEmpty()) return
        val taxa = codec.taxaDeSaidaHz.takeIf { it > 0 } ?: return
        aoEvento(EventoRecepcao.Audio(pcm, taxa))
    }

    private companion object {
        /**
         * 100 × 20 ms = 2 s de silêncio encerram o laço quando **nada** anunciou o
         * fim. É tolerância de jitter, não indecisão: cortar antes descartaria
         * fala boa que atrasou na rede.
         */
        const val ESPERAS_ATE_DESISTIR = 100

        /**
         * 10 × 20 ms = 200 ms, quando o emissor **anunciou** o fim e só falta
         * drenar o buffer. Aqui não há fala futura para esperar: o que não chegou
         * em 200 ms não vai chegar antes de a próxima transmissão começar.
         */
        const val ESPERAS_APOS_FIM = 10
    }

    fun parar() {
        reproducao?.cancel()
        coleta?.cancel()
        reproducao = null
        coleta = null
        transmissaoCorrente = null
        emissorAnunciouFim = false
    }
}
