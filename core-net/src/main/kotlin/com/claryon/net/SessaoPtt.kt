package com.claryon.net

import android.util.Log

import com.claryon.common.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * **O pedido de canal não alcançou o árbitro.** Nada foi ao ar.
     *
     * Separado de [CanalOcupado] em 22/08 porque as duas causas pedem gestos
     * opostos — esperar o colega × andar até pegar sinal — e chegavam ao agente
     * como o mesmo evento e o mesmo tom. Ver [ResultadoDoPedido.SemRede].
     */
    data object SemRede : EventoPtt

    /**
     * O árbitro respondeu **não**: o agente não tem autorização neste canal.
     * Nem ocupado, nem sem rede. Ver [ResultadoDoPedido.Recusado].
     */
    data class PedidoRecusado(val motivo: String) : EventoPtt

    /** Uma emergência tomou o canal. Tom distinto: o agente parou de ser ouvido. */
    data object CanalPerdido : EventoPtt

    /**
     * **A fala acabou, mas o canal não voltou ao grupo.**
     *
     * O `liberar_canal` saiu do aparelho e não chegou. Do lado de quem falou
     * está tudo normal — a voz foi ao ar, [Encerrada] veio —, e a guarnição
     * inteira fica muda até o TTL de 30 s vencer. É o desfecho que faltava:
     * antes, nenhum evento distinguia este caso do encerramento limpo.
     *
     * Sai **antes** de [Encerrada], para o tom de falha não chegar depois de o
     * agente já ter tirado o dedo do botão e voltado a atenção para a ocorrência.
     */
    data class CanalNaoDevolvido(val motivo: String) : EventoPtt

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

    /**
     * Devolve o canal ao grupo. **Tem retorno de propósito** — ver
     * [ResultadoDaLiberacao]: `Unit` aqui era o primeiro dos três lugares em que
     * uma devolução falha desaparecia sem log e sem tom.
     */
    suspend fun liberar(concessao: Concessao): ResultadoDaLiberacao

    /**
     * `true` quando quem arbitra o piso é o **servidor**, e não uma política em
     * RAM deste processo.
     *
     * Existe porque o modo degradado é indistinguível do normal em runtime, e ele
     * não é equivalente: com piso local, dois aparelhos podem se achar donos do
     * mesmo canal e falar por cima. Quem opera precisa saber em qual dos dois
     * está — o `RadioViewModel` cai no local quando não há sessão, e até 22/08 o
     * único sinal disso era uma linha de log.
     */
    val arbitradoPeloServidor: Boolean get() = true
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
    /**
     * Guarda o PCM que foi ao ar, para a transcrição na origem derivar **do mesmo
     * áudio que os receptores ouviram**. `null` desliga o acúmulo sem tocar em
     * nenhum outro caminho — a transcrição é acessória e não pode ser condição do
     * rádio funcionar.
     */
    private val acumulador: AcumuladorDePcm? = null,
    /**
     * Recebe o PCM que foi ao ar, ao fim da transmissão, para transcrever na origem.
     *
     * Suspensa e chamada em `NonCancellable`: soltar o PTT é cancelamento, e uma
     * transcrição disparada aqui morreria antes de começar. Quem implementa decide
     * o escopo — o roadmap pede escopo de **aplicação**, não da tela, para a
     * transcrição não morrer quando o agente troca de aba logo depois de falar.
     */
    private val aoAudioTransmitido: (suspend (String, ShortArray) -> Unit)? = null,
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
            // As três recusas descartam o mesmo áudio e produzem eventos
            // DIFERENTES. O descarte é idêntico porque nada foi ao ar nos três
            // casos; o evento difere porque o gesto que resolve cada um difere.
            ResultadoDoPedido.SemRede -> {
                preRoll.limpar()
                telemetria?.contar(TelemetriaDoRadio.CANAL_NEGADO)
                aoEvento(EventoPtt.SemRede)
                return
            }
            is ResultadoDoPedido.Recusado -> {
                preRoll.limpar()
                telemetria?.contar(TelemetriaDoRadio.CANAL_NEGADO)
                aoEvento(EventoPtt.PedidoRecusado(resultado.motivo))
                return
            }
            is ResultadoDoPedido.Concedido -> resultado.concessao
            is ResultadoDoPedido.Tomado -> {
                telemetria?.contar(TelemetriaDoRadio.CANAL_TOMADO)
                resultado.concessao
            }
        }

        aoEvento(EventoPtt.Transmitindo(transmissaoId))
        // O `agenteId` vai junto e é ele que o receptor resolve: `indicativo` é
        // string livre e qualquer cliente pode escrever o nome de qualquer pessoa.
        // Argumentos nomeados de propósito — a assinatura ganhou um campo no meio,
        // e posicional aqui silenciaria o próximo que ganhar.
        transporte.anunciar(
            AnuncioDeFala(
                transmissaoId = transmissaoId,
                autorIndicativo = indicativo,
                autorAgenteId = agenteId,
                prioridade = prioridade,
            ),
        )

        var sequencia = 0
        var naoEntregues = 0
        var ultimaRenovacao = inicio

        /**
         * Ligada pela vigia quando o árbitro confirma que o piso é de outro.
         *
         * `AtomicBoolean` e não `var` capturada: a vigia e a captura podem rodar
         * em threads diferentes do mesmo `Dispatchers.Default`, e uma bandeira que
         * só é vista na próxima barreira de memória é uma bandeira que atrasa o
         * corte justamente no caso que ela existe para cortar.
         */
        val pisoPerdido = java.util.concurrent.atomic.AtomicBoolean(false)

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
            // Zerado aqui e não no fim: se o encerramento anterior travou na rede,
            // o resto dele não pode aparecer colado no começo desta fala — seria a
            // voz de uma transmissão dentro do texto de outra.
            acumulador?.limpar()

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
            //
            // **A vigia corre EM PARALELO com a captura**, e é ela que fecha a
            // janela de sobreposição de vozes. Ver `vigiarTomadaDoPiso`.
            coroutineScope {
                val falando = this
                val vigia = launch {
                    vigiarTomadaDoPiso(transmissaoId, prioridade, concessao) {
                        pisoPerdido.set(true)
                        falando.cancel(CanalTomado())
                    }
                }
                try {
                    withTimeout((duracaoMaximaMs - (agoraMs() - inicio)).coerceAtLeast(1L)) {
                        pcmAoVivo.collect { bloco ->
                            if (!currentCoroutineContext().isActive) return@collect
                            // Segunda trava, e não redundância: se a vigia
                            // descobriu a tomada entre dois blocos, o quadro
                            // seguinte não vai ao fio nem que o cancelamento
                            // ainda não tenha chegado até aqui.
                            if (pisoPerdido.get()) throw CanalTomado()

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
                } finally {
                    // A vigia coleta um `SharedFlow` que nunca completa: sem este
                    // cancelamento, `coroutineScope` esperaria por ela para sempre
                    // e soltar o PTT nunca encerraria a fala.
                    vigia.cancel()
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
        } catch (e: CancellationException) {
            // **Cancelar o escopo da fala é como a vigia interrompe a captura**, e
            // o que chega aqui pode ser a `CanalTomado` original ou a exceção de
            // cancelamento que o `Job` fabrica por cima dela — depende de qual
            // corrotina foi desenrolada primeiro. Decidir pelo tipo da exceção
            // seria decidir por detalhe de implementação do `kotlinx.coroutines`;
            // a bandeira é o fato.
            //
            // Sem a bandeira, o cancelamento é o do PTT solto — o desfecho normal —
            // e tem de seguir propagando: engoli-lo faria `aoSoltar` parar de
            // encerrar a transmissão.
            if (pisoPerdido.get() && currentCoroutineContext().isActive) {
                aoEvento(EventoPtt.CanalPerdido)
            } else {
                throw e
            }
        } finally {
            // **Soltar o PTT é cancelamento**, e chamada suspensa em `finally`
            // sob cancelamento falha na hora. Sem `NonCancellable`, o último
            // quadro nunca sairia e o receptor esperaria indefinidamente por uma
            // fala que já terminou — o modo de falha mais confuso possível num
            // rádio. O timeout impede que um socket morto trave o encerramento.
            withContext(NonCancellable) {
                withTimeoutOrNull(ENCERRAMENTO_MS) {
                    val devolucao = encerrar(transmissaoId, sequencia, concessao)
                    // **Antes de `Encerrada`, e de propósito.** Depois dela o
                    // agente já tirou o dedo do botão e voltou a atenção para a
                    // ocorrência; o tom que diz "o canal ficou preso" tem de
                    // chegar enquanto ele ainda está pensando no rádio.
                    if (devolucao is ResultadoDaLiberacao.NaoDevolvido) {
                        Log.w(TAG, "canal não devolvido em $transmissaoId: ${devolucao.motivo}")
                        aoEvento(EventoPtt.CanalNaoDevolvido(devolucao.motivo))
                    }
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

                // **A transcrição na origem sai daqui, e fora do `withTimeoutOrNull`
                // acima de propósito.** Dentro dele, um socket lento comeria o
                // orçamento e a transcrição seria abortada sem ninguém saber; o
                // encerramento do canal e o reconhecimento de fala são urgências
                // diferentes e não devem dividir relógio.
                //
                // `consumir()` e não `tudo()`: quem leva o áudio apaga o áudio. Voz
                // em claro não fica esperando alguém lembrar de limpar.
                val falado = acumulador?.consumir()
                if (falado != null && falado.isNotEmpty()) {
                    // Falha aqui não pode derrubar o encerramento: a fala já foi ao
                    // ar e o rádio cumpriu o papel dele. Sem texto, o thread mostra
                    // o balão sem transcrição — que é o comportamento de hoje.
                    runCatching { aoAudioTransmitido?.invoke(transmissaoId, falado) }
                        .onFailure { Log.w(TAG, "transcrição na origem falhou", it) }
                }
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
            // Quadro que a codificação recusou não foi ao ar, e por isso não entra
            // no acumulador: transcrever áudio que ninguém ouviu produziria um
            // texto que o colega não consegue conferir contra o que escutou.
            is Result.Failure -> return 0
        }
        // Depois do sucesso da codificação e antes do envio: é o funil por onde
        // passam TANTO os quadros do pré-roll QUANTO os do `collect` ao vivo.
        acumulador?.acrescentar(pcm)
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

    /**
     * Último quadro, fim de transmissão e devolução do canal. Nunca lança.
     *
     * **Devolve o desfecho da devolução em vez de engoli-lo.** O `runCatching`
     * daqui era a terceira das três camadas em que um `liberar_canal` falho
     * desaparecia (as outras duas são `ClientesDePiso.kt:110-112` e `:142-157`).
     * Ele continua existindo — uma exceção de rede no encerramento não pode
     * derrubar o PTT —, mas agora a exceção **vira desfecho** em vez de virar
     * nada.
     *
     * O último quadro e o `encerrar` do transporte ficam num `runCatching`
     * próprio: um socket que morreu não pode impedir a **tentativa** de devolver
     * o canal, que é a parte que a guarnição inteira paga.
     */
    private suspend fun encerrar(
        transmissaoId: String,
        sequencia: Int,
        concessao: Concessao,
    ): ResultadoDaLiberacao {
        runCatching {
            // O `ultimo` fecha o grupo mesmo incompleto — segurá-lo esperando
            // companhia deixaria o receptor aguardando uma fala que já acabou, e
            // levaria junto os quadros pendentes: a última sílaba.
            val ultimo = QuadroAudio(transmissaoId, sequencia, agoraMs(), ByteArray(0), ultimo = true)
            agrupador.oferecer(ultimo)?.let { transporte.enviarGrupo(it) }
            transporte.encerrar(transmissaoId)
        }.onFailure { Log.w(TAG, "encerramento do fio falhou em $transmissaoId: ${it.message}") }

        return runCatching { piso.liberar(concessao) }
            .getOrElse { ResultadoDaLiberacao.NaoDevolvido(it.message ?: "exceção ao liberar") }
    }

    /**
     * **Vigia a tomada do piso por emergência — para o interrompido saber NA HORA.**
     *
     * O defeito que ela fecha, medido em 22/08 pela bateria de caos: uma P1 tomava
     * o canal e o interrompido só descobria na renovação seguinte. Com
     * [RENOVAR_MS] = 5 000 ms, isso deu **4 640 ms de duas vozes no fio** — 232
     * quadros — e quem recebia ouvia emergência e rotina misturadas. É exatamente
     * o que o controle de piso existe para impedir.
     *
     * ## Por que o anúncio é o GATILHO e não a decisão
     *
     * O anúncio de fala já é difundido para o talk group inteiro, inclusive para
     * quem está transmitindo: **o sinal já estava no fio e ninguém o lia**. Não
     * há RPC novo, não há função de servidor nova, não há tráfego novo.
     *
     * Mas ele não pode ser a decisão. `AnuncioDeFala` é escrito pelo emissor, e
     * um cliente forjado que anunciasse P1 sem ter o piso calaria qualquer agente
     * do grupo — uma negação de serviço barata contra a guarnição, a mesma classe
     * de defeito que a proibição de "identidade por parâmetro" (§2) evita. Por
     * isso o anúncio apenas **antecipa a pergunta**: quem responde é o árbitro,
     * por `renovar`, e é a resposta dele que corta a fala.
     *
     * ## Por que só P1, e só quando eu não sou P1
     *
     * São as duas condições sob as quais o piso muda de mão sem eu soltar o botão
     * ([ControleDePiso.pedir]). Perguntar ao árbitro a cada anúncio de rotina
     * gastaria RPC por um caso que não existe.
     */
    private suspend fun vigiarTomadaDoPiso(
        transmissaoId: String,
        minhaPrioridade: PrioridadeTransmissao,
        concessao: Concessao,
        aoPerder: suspend () -> Unit,
    ) {
        // Emergência não toma de emergência: não há o que vigiar.
        if (minhaPrioridade == PrioridadeTransmissao.P1_EMERGENCIA) return

        transporte.eventos().collect { evento ->
            val anuncio = (evento as? EventoDeRede.Anuncio)?.anuncio ?: return@collect
            if (anuncio.transmissaoId == transmissaoId) return@collect
            if (anuncio.autorAgenteId == agenteId) return@collect
            if (anuncio.prioridade != PrioridadeTransmissao.P1_EMERGENCIA) return@collect

            if (!piso.renovar(concessao)) {
                Log.i(TAG, "piso tomado por ${anuncio.transmissaoId}; cortando $transmissaoId")
                telemetria?.contar(PISO_PERDIDO_POR_EMERGENCIA)
                aoPerder()
            }
        }
    }

    private class LimiteDeDuracaoAtingido : CancellationException("teto de 30 s")
    private class CanalTomado : CancellationException("canal tomado por emergência")

    companion object {
        private const val TAG = "ClaryonField"

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

        /**
         * Quantas vezes uma emergência cortou esta fala **antes** da renovação.
         *
         * Contador e não adjetivo: é o que permite dizer, depois de um turno, se
         * a vigia de tomada disparou alguma vez — e se ela parou de disparar
         * porque o defeito voltou.
         */
        const val PISO_PERDIDO_POR_EMERGENCIA = "piso_perdido_por_emergencia"
    }
}
