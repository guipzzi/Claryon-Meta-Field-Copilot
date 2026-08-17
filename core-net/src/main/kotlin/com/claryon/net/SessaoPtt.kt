package com.claryon.net

import com.claryon.common.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** O que o emissor precisa saber enquanto fala. */
sealed interface EventoPtt {

    /** Captura começou. Emitido **antes** de qualquer resposta da rede. */
    data object Capturando : EventoPtt

    /** Canal concedido; a voz está indo ao vivo. */
    data class Transmitindo(val transmissaoId: String) : EventoPtt

    /** Outro agente fala. Tom de ocupado, e o que foi capturado é descartado. */
    data class CanalOcupado(val porQuem: String) : EventoPtt

    /** Uma emergência tomou o canal. Tom distinto: o agente parou de ser ouvido. */
    data object CanalPerdido : EventoPtt

    /** Teto de duração atingido — impede que um botão preso vire captação contínua. */
    data object LimiteDeDuracao : EventoPtt

    /**
     * Quadros que a rede não aceitou. A captura **não** para por isso: rede lenta
     * atrasa a entrega, nunca perde fala.
     */
    data class QuadrosNaoEntregues(val quantidade: Int) : EventoPtt

    data class Encerrada(val transmissaoId: String, val quadros: Int, val duracaoMs: Long) : EventoPtt
}

/** Concessão de canal — local (demo/teste) ou remota (Edge Function). */
interface ClienteDePiso {
    suspend fun pedir(
        talkGroupId: String,
        agenteId: String,
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
    ): ResultadoDoPedido

    suspend fun renovar(concessao: Concessao): Boolean
    suspend fun liberar(concessao: Concessao)
}

/**
 * **Sessão de push-to-talk: uma transmissão, do toque à soltura.**
 *
 * A regra que organiza este arquivo inteiro: **a captura não bloqueia esperando a
 * rede.** O PCM começa a ser consumido e codificado no instante do toque; a
 * concessão de canal corre em paralelo. Bloquear a captura pelo pedido de canal
 * transformaria latência de rede em fala perdida — e é justamente a primeira
 * sílaba, a mais cara, que se perderia.
 *
 * Ordem dos eventos, e por quê:
 *
 * ```
 * [toque] → Capturando ─────────────────────────────► (imediato, sem rede)
 *         → pede canal ──┬─ concedido → Transmitindo → pré-roll + ao vivo
 *                        └─ negado    → CanalOcupado → descarta o capturado
 * [solta] → último quadro → libera canal → Encerrada
 * ```
 *
 * O **pré-roll sai primeiro**, e é o que recupera a fala iniciada antes do dedo
 * chegar ao botão (ver [PreRollBuffer]).
 *
 * Cancelar a corrotina equivale a soltar o PTT: o encerramento ordenado acontece
 * no `finally`, inclusive se a rede tiver caído no meio.
 */
class SessaoPtt(
    private val talkGroupId: String,
    private val agenteId: String,
    private val preRoll: PreRollBuffer,
    private val codec: CodecDeVoz,
    private val transporte: TransporteAoVivo,
    private val piso: ClienteDePiso,
    private val agoraMs: () -> Long,
    private val amostrasPorQuadro: Int,
    private val duracaoMaximaMs: Long = DURACAO_MAXIMA_MS,
    private val renovarACadaMs: Long = RENOVAR_MS,
    /**
     * `null` por padrão: instrumentação é opt-in, não um parâmetro que toda
     * chamada de teste precisa aprender a passar. Duas das seis metas do
     * projeto (`TOQUE_ATE_PRIMEIRO_QUADRO`, `CONCESSAO_DE_CANAL`) só existem
     * como número quando isto é passado — antes, `TelemetriaDoRadio` compilava,
     * tinha teste próprio, e não tinha nenhum chamador em `src/main`.
     */
    private val telemetria: TelemetriaDoRadio? = null,
    /**
     * Junta quadros antes de sair. Ver [AgrupadorDeQuadros] para o custo
     * declarado (+40 ms de empacotamento em troca de 1/3 das mensagens).
     */
    private val agrupador: AgrupadorDeQuadros = AgrupadorDeQuadros(),
) {

    /**
     * Transmite enquanto o [pcmAoVivo] emitir e a corrotina estiver ativa.
     *
     * @param transmissaoId gerado pelo chamador (UUIDv7). Vem de fora para o
     *   envio ser **idempotente**: retry após queda de rede não pode duplicar a
     *   transmissão — falha que soa amadora numa demonstração.
     */
    suspend fun transmitir(
        transmissaoId: String,
        prioridade: PrioridadeTransmissao,
        indicativo: String,
        pcmAoVivo: Flow<ShortArray>,
        aoEvento: suspend (EventoPtt) -> Unit,
    ) {
        val inicio = agoraMs()
        aoEvento(EventoPtt.Capturando)

        // A rede é consultada aqui, mas nada do que vem abaixo espera por ela
        // além do necessário para saber se pode falar.
        val resultado = piso.pedir(talkGroupId, agenteId, transmissaoId, prioridade)
        // Meta declarada: concessão de canal ≤ 150 ms.
        telemetria?.registrar(TelemetriaDoRadio.Metrica.CONCESSAO_DE_CANAL, agoraMs() - inicio)

        val concessao = when (resultado) {
            is ResultadoDoPedido.Ocupado -> {
                // Tom de ocupado e descarte: o que foi capturado até aqui não vai
                // a lugar nenhum, e o pré-roll é limpo para não vazar na próxima.
                preRoll.limpar()
                telemetria?.contar(TelemetriaDoRadio.CANAL_NEGADO)
                aoEvento(EventoPtt.CanalOcupado(resultado.detentor.agenteId))
                return
            }
            is ResultadoDoPedido.Concedido -> resultado.concessao
            is ResultadoDoPedido.Tomado -> {
                telemetria?.contar(TelemetriaDoRadio.CANAL_TOMADO)
                resultado.concessao
            }
        }

        aoEvento(EventoPtt.Transmitindo(transmissaoId))
        transporte.anunciar(AnuncioDeFala(transmissaoId, indicativo, prioridade))

        var sequencia = 0
        var naoEntregues = 0
        var ultimaRenovacao = inicio

        /**
         * Marca a meta "toque → primeiro quadro entregue à rede" (≤ 120 ms) na
         * primeira vez em que a sequência de fato avança.
         *
         * É por sequência e não por chamada de [enviar] porque o codec é um
         * pipeline: as primeiras chamadas consomem PCM sem emitir pacote, e medir
         * ali cravaria um número que não corresponde a nenhum áudio tendo saído
         * do aparelho.
         *
         * **Bandeira, e não `sequencia == 1`.** A primeira versão comparava com 1
         * e perdia a medição sempre que a primeira chamada de [enviar] rendia
         * dois pacotes de uma vez — o que acontece de verdade a partir da segunda
         * transmissão, com o `MediaCodec` já aquecido. Medido no emulador: duas
         * transmissões, `n=1`. Erro de poste que só apareceu porque a métrica foi
         * lida, e não só escrita.
         */
        var jaMarcouPrimeiroQuadro = false
        fun marcarPrimeiroQuadroSePreciso() {
            if (!jaMarcouPrimeiroQuadro && sequencia > 0) {
                jaMarcouPrimeiroQuadro = true
                telemetria?.registrar(
                    TelemetriaDoRadio.Metrica.TOQUE_ATE_PRIMEIRO_QUADRO,
                    agoraMs() - inicio,
                )
            }
        }

        try {
            // 1) Pré-roll: a fala que começou antes do dedo chegar ao botão.
            for (quadro in fatiar(preRoll.desdeOInicioDaFala())) {
                naoEntregues += enviar(transmissaoId, { sequencia++ }, quadro)
                marcarPrimeiroQuadroSePreciso()
            }
            preRoll.limpar()

            // 2) Ao vivo, com o teto contado POR RELÓGIO, não por chegada de áudio.
            //
            // **O teto não pode depender de o fluxo emitir.** Ele vivia dentro do
            // `collect`, o que o tornava refém da fonte: com o microfone parado —
            // HFP caído, `AudioRecord` travado, fonte suspensa — a condição nunca
            // voltava a ser avaliada e o canal ficava **tomado indefinidamente**,
            // com a guarnição em silêncio esperando um agente que já não
            // transmite. Subir o teto de 12 para 30 s dobraria a janela desse
            // dano sem tocar na causa.
            //
            // `withTimeout` e não um vigia que faz *polling*: é **um** temporizador
            // em vez de um laço que acorda quatro vezes por segundo pelo turno
            // inteiro, e termina sozinho. Um laço com `delay` infinito também
            // seria intestável — `advanceUntilIdle` nunca fica ocioso com ele
            // pendente, que foi como a primeira versão disto quebrou três testes.
            //
            // A renovação do piso **continua dentro** do `collect`, e isso é
            // deliberado: renovar existe para não perder a palavra ENQUANTO se
            // fala. Sem áudio chegando não há o que preservar, e o teto acima já
            // encerra.
            // **Desconta o que já passou.** `withTimeout(duracaoMaximaMs)` cru
            // começaria a contar aqui — depois da concessão de canal e do
            // pré-roll — e o teto viraria "30 s de áudio ao vivo" em vez de
            // "30 s desde o toque". Mudança silenciosa de significado do aceite,
            // e para o lado errado: quanto mais lenta a rede, mais tempo de
            // captação o agente ganharia.
            withTimeout((duracaoMaximaMs - (agoraMs() - inicio)).coerceAtLeast(1L)) {
                pcmAoVivo.collect { bloco ->
                    if (!currentCoroutineContext().isActive) return@collect

                    if (agoraMs() - ultimaRenovacao >= renovarACadaMs) {
                        ultimaRenovacao = agoraMs()
                        // Perder o canal no meio da fala é informação operacional:
                        // o agente precisa parar de falar para o vazio.
                        if (!piso.renovar(concessao)) throw CanalTomado()
                    }

                    for (quadro in fatiar(bloco)) {
                        naoEntregues += enviar(transmissaoId, { sequencia++ }, quadro)
                        marcarPrimeiroQuadroSePreciso()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // O teto de `withTimeout`. Distinto de `LimiteDeDuracaoAtingido`, que
            // ficou como o tipo interno — os dois viram o mesmo evento para quem
            // ouve, porque para o agente é o mesmo fato: a transmissão acabou por
            // tempo, não por ele ter soltado.
            aoEvento(EventoPtt.LimiteDeDuracao)
        } catch (e: LimiteDeDuracaoAtingido) {
            aoEvento(EventoPtt.LimiteDeDuracao)
        } catch (e: CanalTomado) {
            aoEvento(EventoPtt.CanalPerdido)
        } finally {
            // **Soltar o PTT é cancelamento**, e chamada suspensa em `finally`
            // sob cancelamento falha na hora. Sem `NonCancellable`, o último
            // quadro nunca sairia e o receptor esperaria indefinidamente por uma
            // fala que já terminou — o modo de falha mais confuso possível num
            // rádio. O timeout impede que um socket morto trave o encerramento.
            withContext(NonCancellable) {
                withTimeoutOrNull(ENCERRAMENTO_MS) {
                    encerrar(transmissaoId, sequencia, concessao)
                    if (naoEntregues > 0) aoEvento(EventoPtt.QuadrosNaoEntregues(naoEntregues))
                    aoEvento(
                        EventoPtt.Encerrada(
                            transmissaoId = transmissaoId,
                            quadros = sequencia,
                            duracaoMs = agoraMs() - inicio,
                        ),
                    )
                }
                // O pré-roll é limpo mesmo que a rede tenha travado o
                // encerramento: áudio não sobrevive ao momento de uso.
                preRoll.limpar()
            }
        }
    }

    /** Divide um bloco de PCM em quadros do tamanho do codec, descartando o resto. */
    private fun fatiar(pcm: ShortArray): List<ShortArray> {
        if (pcm.size < amostrasPorQuadro) return emptyList()
        val saida = ArrayList<ShortArray>(pcm.size / amostrasPorQuadro)
        var i = 0
        while (i + amostrasPorQuadro <= pcm.size) {
            saida.add(pcm.copyOfRange(i, i + amostrasPorQuadro))
            i += amostrasPorQuadro
        }
        return saida
    }

    /**
     * Codifica e envia. Devolve quantos pacotes **não** foram entregues.
     *
     * O codificador é um pipeline: um quadro de entrada pode render zero pacotes
     * (aquecimento) ou mais de um. Por isso a sequência avança por **pacote
     * enviado**, não por quadro capturado — numerar por quadro deixaria buracos
     * na sequência, e o receptor os interpretaria como perda, disparando PLC
     * sobre áudio que nunca existiu.
     */
    private suspend fun enviar(transmissaoId: String, sequencia: () -> Int, pcm: ShortArray): Int {
        val antesDaCodificacao = agoraMs()
        val pacotes = when (val c = codec.codificar(pcm)) {
            is Result.Success -> c.value
            is Result.Failure -> return 0
        }
        // Só mede quando o pipeline de fato emitiu: no aquecimento o codec
        // consome sem produzir, e contar isso como "codificação instantânea"
        // enviesaria o p50 para baixo justamente no início de cada fala.
        if (pacotes.isNotEmpty()) {
            telemetria?.registrar(
                TelemetriaDoRadio.Metrica.CODIFICACAO,
                agoraMs() - antesDaCodificacao,
            )
        }
        var naoEntregues = 0
        for (payload in pacotes) {
            val quadro = QuadroAudio(transmissaoId, sequencia(), agoraMs(), payload)
            // O agrupador segura os dois primeiros e devolve a mensagem no
            // terceiro. Os contadores são POR QUADRO mesmo assim: a métrica que
            // interessa é "quanto da voz do agente chegou", e ela não muda
            // porque o envelope passou a levar três de cada vez.
            val grupo = agrupador.oferecer(quadro) ?: continue
            if (transporte.enviarGrupo(grupo) !is Result.Success) {
                naoEntregues += grupo.size
                telemetria?.contar(TelemetriaDoRadio.QUADROS_NAO_ENTREGUES, grupo.size.toLong())
            } else {
                telemetria?.contar(TelemetriaDoRadio.QUADROS_ENVIADOS, grupo.size.toLong())
                telemetria?.contar(MENSAGENS_ENVIADAS)
            }
        }
        return naoEntregues
    }

    /** Último quadro, fim de transmissão e devolução do canal. Nunca lança. */
    private suspend fun encerrar(transmissaoId: String, sequencia: Int, concessao: Concessao) {
        runCatching {
            // O `ultimo` fecha o grupo mesmo incompleto — segurá-lo esperando
            // companhia deixaria o receptor aguardando uma fala que já acabou, e
            // levaria junto os quadros pendentes: a última sílaba.
            val ultimo = QuadroAudio(transmissaoId, sequencia, agoraMs(), ByteArray(0), ultimo = true)
            agrupador.oferecer(ultimo)?.let { transporte.enviarGrupo(it) }
            transporte.encerrar(transmissaoId)
            piso.liberar(concessao)
        }
    }

    private class LimiteDeDuracaoAtingido : CancellationException("teto de 30 s")
    private class CanalTomado : CancellationException("canal tomado por emergência")

    companion object {
        /** Impede que um botão preso vire captação contínua. */
        const val DURACAO_MAXIMA_MS = 30_000L
        const val RENOVAR_MS = 5_000L

        /** Teto para o encerramento — um socket morto não pode travar o PTT. */
        const val ENCERRAMENTO_MS = 2_000L


        /**
         * Mensagens de fato postas no socket. Com o agrupamento, é ~1/3 de
         * `QUADROS_ENVIADOS` — e é este o número que o aceite (d) pede.
         */
        const val MENSAGENS_ENVIADAS = "mensagens_enviadas"
    }
}
