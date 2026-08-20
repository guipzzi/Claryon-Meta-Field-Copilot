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

    /** Fim da fala. Fecha a janela de supressão depois da cauda. */
    data class Terminou(val transmissaoId: String, val quadros: Int, val perdidos: Int) : EventoRecepcao

    /**
     * Canal degradado: perda acima de 30%. **O agente precisa saber que o rádio
     * está ruim antes de confiar nele** — decidir uma abordagem contando com um
     * apoio que talvez não tenha ouvido é pior que saber que não ouviu.
     */
    data object CanalDegradado : EventoRecepcao
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
                        transmissaoCorrente = evento.anuncio.transmissaoId
                        aoEvento(EventoRecepcao.Chegando(evento.anuncio))
                        iniciarReproducao(aoEvento)
                    }

                    is EventoDeRede.Quadro -> {
                        // Quadro sem anúncio acontece: o anúncio pode ter se
                        // perdido. Começar mesmo assim é melhor que ficar mudo.
                        if (transmissaoCorrente == null) {
                            transmissaoCorrente = evento.quadro.transmissaoId
                            iniciarReproducao(aoEvento)
                        }
                        jitter.receber(evento.quadro)
                    }

                    is EventoDeRede.FimDeTransmissao -> Unit // o quadro `ultimo` encerra

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
                        if (esperasSeguidas >= ESPERAS_ATE_DESISTIR) {
                            // Emissor sumiu no meio (bateria, túnel) e o `ultimo`
                            // nunca chegou. Encerrar é melhor que segurar o canal:
                            // a próxima fala precisa de um laço limpo.
                            val id = transmissaoCorrente
                            transmissaoCorrente = null
                            if (id != null) {
                                aoEvento(EventoRecepcao.Terminou(id, quadrosTocados, quadrosPerdidos))
                            }
                            return@launch
                        }
                    }

                    SaidaDoJitter.Fim -> {
                        val id = transmissaoCorrente
                        transmissaoCorrente = null
                        if (id != null) {
                            aoEvento(EventoRecepcao.Terminou(id, quadrosTocados, quadrosPerdidos))
                        }
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

    private suspend fun emitir(r: Result<ShortArray>, aoEvento: suspend (EventoRecepcao) -> Unit) {
        val pcm = (r as? Result.Success)?.value ?: return
        if (pcm.isEmpty()) return
        val taxa = codec.taxaDeSaidaHz.takeIf { it > 0 } ?: return
        aoEvento(EventoRecepcao.Audio(pcm, taxa))
    }

    private companion object {
        /** 100 × 20 ms = 2 s de silêncio encerram o laço. */
        const val ESPERAS_ATE_DESISTIR = 100
    }

    fun parar() {
        reproducao?.cancel()
        coleta?.cancel()
        reproducao = null
        coleta = null
        transmissaoCorrente = null
    }
}
