package com.claryon.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Arbitragem de piso com N pretendentes — o coração do meio-duplex.**
 *
 * `ControleDePisoTest` cobre a política com um pedido por vez. O que decide se
 * este rádio é usável numa guarnição é o que acontece quando **quatro** aparelhos
 * apertam o botão na mesma ocorrência, e quando o que ganhou some no meio da fala.
 *
 * Cada teste aqui provoca. Onde o comportamento observado é ruim, o teste **trava
 * o número ruim** em vez de descrevê-lo com adjetivo: um teto de 30 s de canal
 * preso é um dado operacional, e escondê-lo atrás de "eventualmente libera" é a
 * forma mais barata de nunca consertá-lo.
 *
 * Ver [BarramentoTatico] para o que esta bancada **não** cobre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaosDePisoComNParesTest {

    private val gta = "gta-3"
    private val sr = 16_000
    private val amostrasPorQuadro = sr / 50

    // ── Bancada ───────────────────────────────────────────────────────────────

    private fun preRollCom(quadros: Int): PreRollBuffer =
        PreRollBuffer(sr, 600, amostrasPorQuadro).apply {
            repeat(quadros) { escrever(ShortArray(amostrasPorQuadro) { 4_000 }) }
        }

    private fun sessaoDe(
        par: ParDeRadio,
        piso: ClienteDePiso,
        relogio: () -> Long,
        preRoll: PreRollBuffer = preRollCom(0),
        renovarACadaMs: Long = SessaoPtt.RENOVAR_MS,
        duracaoMaximaMs: Long = SessaoPtt.DURACAO_MAXIMA_MS,
    ) = SessaoPtt(
        talkGroupId = gta,
        agenteId = par.agenteId,
        preRoll = preRoll,
        codec = CodecDeBancada(),
        transporte = par,
        piso = piso,
        agoraMs = relogio,
        amostrasPorQuadro = amostrasPorQuadro,
        duracaoMaximaMs = duracaoMaximaMs,
        renovarACadaMs = renovarACadaMs,
    )

    /** Fala de [blocos] × 20 ms, com o relógio de domínio andando junto. */
    private fun fala(blocos: Int) = flow {
        repeat(blocos) {
            delay(20)
            emit(ShortArray(amostrasPorQuadro) { 3_000 })
        }
    }

    // ── 1. Dois no mesmo milissegundo ─────────────────────────────────────────

    @Test
    fun oitoAgentesNoMesmoMilissegundo_umSoRecebeOCanal() = runTest {
        // Relógio CONGELADO: os oito pedidos carregam o mesmo instante, que é o
        // caso que "ler, decidir, escrever" resolveria errado.
        val servidor = PisoServidor(relogio = { 0L })

        val respostas = coroutineScope {
            (1..8).map { i ->
                async {
                    servidor.pedir(gta, "agente-$i", "tx-$i", PrioridadeTransmissao.P2_APOIO)
                }
            }.awaitAll()
        }

        assertEquals(
            "duas vozes ao mesmo tempo no ouvido de quem está numa ocorrência",
            1,
            respostas.count { it is ResultadoDoPedido.Concedido },
        )
        assertEquals(7, respostas.count { it is ResultadoDoPedido.Ocupado })
        assertEquals(
            "os sete perdedores têm de apontar o MESMO detentor — apontar detentores " +
                "diferentes significaria que houve mais de uma concessão",
            1,
            respostas.filterIsInstance<ResultadoDoPedido.Ocupado>()
                .map { it.detentor.agenteId }.toSet().size,
        )
    }

    /**
     * **É corrida, não regra.** O contra-teste que separa "determinístico" de
     * "quem chegar primeiro": a mesma dupla, a mesma prioridade, o mesmo
     * milissegundo — e o vencedor muda quando a ordem de chegada ao árbitro muda.
     *
     * Importa porque a ordem de chegada ao Postgres **não é observável pelo
     * cliente**: ela depende de RTT, de fila de conexão e do escalonador do banco.
     * Do ponto de vista dos dois agentes, o resultado é sorteio.
     */
    @Test
    fun oDesempateEhPorOrdemDeChegada_naoPorIdentidadeNemPorPrioridade() = runTest {
        suspend fun vencedorCom(primeiro: String, segundo: String): String {
            val servidor = PisoServidor(relogio = { 0L })
            servidor.pedir(gta, primeiro, "tx-$primeiro", PrioridadeTransmissao.P2_APOIO)
            servidor.pedir(gta, segundo, "tx-$segundo", PrioridadeTransmissao.P2_APOIO)
            return servidor.detentor(gta)!!.agenteId
        }

        assertEquals("alfa", vencedorCom("alfa", "bravo"))
        assertEquals(
            "se o vencedor fosse decidido por identidade, este seria 'alfa' também",
            "bravo",
            vencedorCom("bravo", "alfa"),
        )
    }

    // ── 2. O que o perdedor ouve ──────────────────────────────────────────────

    @Test
    fun oPerdedorNuncaFicaEmSilencio_recebeCanalOcupadoComOAutorDaFala() = runTest {
        val servidor = PisoServidor(relogio = { currentTime })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)

        // Alfa toma o canal e segura.
        servidor.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)

        val preRollDeBravo = preRollCom(10)
        val eventos = mutableListOf<EventoPtt>()
        sessaoDe(bravo, ClienteDePisoDoPar(bravo, servidor), { currentTime }, preRollDeBravo)
            .transmitir("tx-bravo", PrioridadeTransmissao.P2_APOIO, "Bravo Dois", fala(5)) {
                eventos += it
            }
        advanceUntilIdle()

        val ocupado = eventos.filterIsInstance<EventoPtt.CanalOcupado>().firstOrNull()
        assertNotNull("falha nunca é silêncio: o perdedor precisa de um evento", ocupado)
        assertEquals("alfa", ocupado!!.porQuem)
        assertTrue("o perdedor não pode pôr um quadro no fio", bravo.enviados.isEmpty())
        assertTrue("e Alfa não pode ouvir nada de Bravo", alfa.recebidos.isEmpty())
        assertEquals(
            "o capturado durante a recusa não pode vazar na próxima transmissão",
            0,
            preRollDeBravo.tamanho,
        )
    }

    /**
     * **CONSERTADO (22/08): falta de rede tem desfecho próprio.**
     *
     * `ClienteDePisoRemoto.pedir` devolvia `Ocupado(detentor = "?")` quando o RPC
     * não voltava. A decisão de calar em vez de presumir concedido sempre esteve
     * certa; o defeito era o que chegava ao agente: o **mesmo** [EventoPtt] e o
     * **mesmo** tom de um canal de fato ocupado.
     *
     * Em campo as duas causas pedem ações opostas — canal ocupado se resolve
     * **esperando** o colega soltar; falta de rede se resolve **andando** até
     * pegar sinal. Um agente embaixo de um viaduto esperava por uma vez que nunca
     * chegaria.
     *
     * Agora são [EventoPtt.SemRede] e [EventoPtt.CanalOcupado], e o `RadioTatico`
     * fala frases diferentes para cada um.
     */
    @Test
    fun oPedidoSemRede_temDesfechoProprio_eNaoSeDisfarcaDeCanalOcupado() = runTest {
        val servidor = PisoServidor(relogio = { currentTime })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        alfa.rede = false

        val eventos = mutableListOf<EventoPtt>()
        sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { currentTime })
            .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", fala(4)) {
                eventos += it
            }
        advanceUntilIdle()

        assertEquals(
            "rede caída tem de chegar como SemRede — o desfecho que manda ANDAR",
            1,
            eventos.count { it is EventoPtt.SemRede },
        )
        assertTrue(
            "e NUNCA como CanalOcupado, que é o desfecho que manda ESPERAR",
            eventos.none { it is EventoPtt.CanalOcupado },
        )
        assertNull(
            "e o servidor nunca soube do pedido: não há ninguém com a palavra",
            servidor.detentor(gta),
        )
        assertTrue("nada pode ter ido ao fio", alfa.enviados.isEmpty())
    }

    /**
     * **O contra-teste do conserto acima, e ele é uma DESIGUALDADE.**
     *
     * As duas recusas correm na mesma bancada, mudando só a causa, e o teste exige
     * que os desfechos difiram. Se alguém reintroduzir o `Ocupado(detentor = "?")`
     * do caminho sem rede, os dois conjuntos de eventos voltam a ser iguais e este
     * teste cai — que é o único jeito de o defeito não voltar em silêncio.
     */
    @Test
    fun canalOcupadoEFaltaDeRede_produzemDesfechosDIFERENTES() = runTest {
        suspend fun desfechosCom(rede: Boolean): List<EventoPtt> {
            val servidor = PisoServidor(relogio = { currentTime })
            val barramento = BarramentoTatico()
            val alfa = ParDeRadio("alfa", barramento)
            val bravo = ParDeRadio("bravo", barramento)
            // Canal legitimamente ocupado por Bravo, nos dois cenários.
            ClienteDePisoDoPar(bravo, servidor)
                .pedir(gta, "bravo", "tx-bravo", PrioridadeTransmissao.P2_APOIO)
            alfa.rede = rede

            val eventos = mutableListOf<EventoPtt>()
            sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { currentTime })
                .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", fala(4)) {
                    eventos += it
                }
            advanceUntilIdle()
            return eventos
        }

        val comRede = desfechosCom(rede = true)
        val semRede = desfechosCom(rede = false)

        assertEquals(
            "com rede, o canal está ocupado por Bravo",
            listOf(EventoPtt.CanalOcupado("bravo")),
            comRede.filter { it is EventoPtt.CanalOcupado || it is EventoPtt.SemRede },
        )
        assertEquals(
            "sem rede, nem sabemos que Bravo existe",
            listOf<EventoPtt>(EventoPtt.SemRede),
            semRede.filter { it is EventoPtt.CanalOcupado || it is EventoPtt.SemRede },
        )
    }

    // ── 3. Fica bloqueado, enfileirado, ou é ignorado? ────────────────────────

    /**
     * **Não há fila.** Bravo aperta três vezes enquanto Alfa fala e é recusado
     * três vezes; nada fica pendente. Assim que Alfa solta, a quarta tentativa
     * passa — o que prova que a recusa é de estado, não de bloqueio.
     */
    @Test
    fun enquantoAlfaFala_bravoApertaTresVezes_eEhRecusadoTresVezes_semFila() = runTest {
        val servidor = PisoServidor(relogio = { currentTime })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor)
        val pisoDeBravo = ClienteDePisoDoPar(bravo, servidor)

        val concessaoDeAlfa =
            (pisoDeAlfa.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)
                as ResultadoDoPedido.Concedido).concessao

        val desfechos = mutableListOf<ResultadoDoPedido>()
        repeat(3) { i ->
            desfechos += pisoDeBravo.pedir(gta, "bravo", "tx-bravo-$i", PrioridadeTransmissao.P2_APOIO)
        }
        assertEquals(3, desfechos.count { it is ResultadoDoPedido.Ocupado })

        pisoDeAlfa.liberar(concessaoDeAlfa) // Alfa soltou o botão
        val quarta = pisoDeBravo.pedir(gta, "bravo", "tx-bravo-3", PrioridadeTransmissao.P2_APOIO)

        assertTrue(
            "sem fila: a recusa é de ESTADO, e o estado mudou quando Alfa soltou",
            quarta is ResultadoDoPedido.Concedido,
        )
        assertEquals(
            "as três recusas não podem ter deixado nada pendente no servidor",
            "bravo",
            servidor.detentor(gta)!!.agenteId,
        )
    }

    // ── 4. Quanto tempo o piso fica preso ─────────────────────────────────────

    /**
     * **O número que o produto tem de saber: 30 s.**
     *
     * Alfa perde a rede com o canal na mão. O `liberar` sai do aparelho e não
     * chega a lugar nenhum — e não avisa ninguém (ver [ClienteDePisoDoPar]). A
     * guarnição inteira fica muda até o TTL vencer.
     *
     * O teste crava os dois lados da borda: recusado a 29 999 ms, concedido a
     * 30 000 ms. Sem o par de asserções, um TTL de 5 min passaria igual.
     */
    @Test
    fun alfaPerdeARedeComOCanalNaMao_eOGrupoFicaMudoPorExatamente30s() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor)
        val pisoDeBravo = ClienteDePisoDoPar(bravo, servidor)

        val concessao = (pisoDeAlfa.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)
            as ResultadoDoPedido.Concedido).concessao

        alfa.rede = false // túnel
        pisoDeAlfa.liberar(concessao) // sai do aparelho, morre no caminho
        assertEquals("o aparelho ACHA que devolveu o canal", 1, pisoDeAlfa.liberacoesTentadas)

        agora = ControleDePiso.TTL_PADRAO_MS - 1
        assertTrue(
            "um milissegundo antes do TTL a guarnição ainda está muda",
            pisoDeBravo.pedir(gta, "bravo", "tx-b1", PrioridadeTransmissao.P2_APOIO)
                is ResultadoDoPedido.Ocupado,
        )

        agora = ControleDePiso.TTL_PADRAO_MS
        assertTrue(
            "no TTL o canal volta ao grupo — é o que impede um crash de calar a guarnição",
            pisoDeBravo.pedir(gta, "bravo", "tx-b2", PrioridadeTransmissao.P2_APOIO)
                is ResultadoDoPedido.Concedido,
        )
        assertEquals(30_000L, ControleDePiso.TTL_PADRAO_MS)
    }

    /**
     * **Enquanto o piso está preso, uma EMERGÊNCIA passa.** É a válvula que torna
     * os 30 s suportáveis: quem precisa mesmo falar não espera o TTL.
     *
     * Mas ela só existe para P1. P2 e P3 esperam os 30 s inteiros — e o teste
     * acima crava isso.
     */
    @Test
    fun comOPisoPresoPorRedeCaida_aEmergenciaAindaPassa() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor)

        val concessao = (pisoDeAlfa.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)
            as ResultadoDoPedido.Concedido).concessao
        alfa.rede = false
        pisoDeAlfa.liberar(concessao)

        agora = 1_000
        val r = ClienteDePisoDoPar(bravo, servidor)
            .pedir(gta, "bravo", "tx-b", PrioridadeTransmissao.P1_EMERGENCIA)

        assertTrue("emergência não espera 29 s por um aparelho que já morreu", r is ResultadoDoPedido.Tomado)
    }

    // ── 5. O `liberar_canal` que falha em silêncio ────────────────────────────

    /**
     * **CONSERTADO (22/08): o buraco de `ClientesDePiso.kt:110-112` + `:142-157`
     * + `SessaoPtt.kt:377`.**
     *
     * Aqui a rede do RÁDIO está de pé — os quadros saem, a fala é ouvida, a sessão
     * encerra normalmente. O que falhou foi o RPC `liberar_canal`, que é HTTP e
     * independente do WebSocket.
     *
     * Antes: **nenhum evento** distinguia este caso do encerramento limpo, em três
     * camadas somadas — `rpcBooleano` sem `onFailure`, o retorno descartado, e o
     * `runCatching` do encerramento engolindo. O agente que falou achava que tinha
     * devolvido o canal, e a guarnição ficava muda 30 s.
     *
     * O canal continua preso — isso é consequência da rede, e nenhum evento
     * conserta. O que muda é que **alguém sabe**, e o único que pode agir é
     * avisado enquanto ainda está pensando no rádio.
     */
    @Test
    fun oLiberarQueFalha_produzEventoProprio_antesDeEncerrada() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor, liberarFalha = true)

        val eventos = mutableListOf<EventoPtt>()
        sessaoDe(alfa, pisoDeAlfa, { agora })
            .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", flow {
                repeat(4) { agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
            }) { eventos += it }
        advanceUntilIdle()

        assertTrue("a fala foi ao ar normalmente", eventos.any { it is EventoPtt.Encerrada })
        assertTrue("e Bravo ouviu", bravo.recebidos.any { it is EventoDeRede.Quadro })

        val naoDevolvido = eventos.indexOfFirst { it is EventoPtt.CanalNaoDevolvido }
        assertTrue("a devolução falha tem de produzir evento", naoDevolvido >= 0)
        assertTrue(
            "e ANTES de Encerrada: depois dela o agente já voltou a atenção para a " +
                "ocorrência, e o tom chega no vazio",
            naoDevolvido < eventos.indexOfFirst { it is EventoPtt.Encerrada },
        )
        assertEquals("o canal de fato ficou preso", "alfa", servidor.detentor(gta)?.agenteId)

        agora = 1_000
        assertTrue(
            "e Bravo, que ouviu a fala inteira TERMINAR, é recusado — a diferença " +
                "é que agora Alfa foi avisado de que causou isso",
            ClienteDePisoDoPar(bravo, servidor)
                .pedir(gta, "bravo", "tx-b", PrioridadeTransmissao.P2_APOIO)
                is ResultadoDoPedido.Ocupado,
        )
    }

    /**
     * **O contra-teste: a devolução que FUNCIONA não pode produzir o mesmo evento.**
     *
     * Sem ele, `CanalNaoDevolvido` emitido sempre passaria — e um tom de falha ao
     * fim de toda transmissão treina o agente a ignorar o tom, que é a forma mais
     * cara de "consertar" um silêncio.
     */
    @Test
    fun oLiberarQueFunciona_naoProduzEventoDeCanalPreso() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)

        val eventos = mutableListOf<EventoPtt>()
        sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { agora })
            .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", flow {
                repeat(4) { agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
            }) { eventos += it }
        advanceUntilIdle()

        assertTrue(eventos.any { it is EventoPtt.Encerrada })
        assertTrue(
            "canal devolvido não pode soar como canal preso",
            eventos.none { it is EventoPtt.CanalNaoDevolvido },
        )
        assertNull("e o canal voltou mesmo ao grupo", servidor.detentor(gta))
    }

    // ── 6. Emergência durante fala de rotina ──────────────────────────────────

    /**
     * **CONSERTADO (22/08) — o bloqueador: 4 640 ms de duas vozes no fio.**
     *
     * Bravo tomava o canal com P1 no instante *t*, e Alfa só descobria no próximo
     * `renovar` — a cada [SessaoPtt.RENOVAR_MS] = 5 000 ms. Nesse intervalo os
     * **dois** transmitiam: 232 quadros medidos, e Charlie recebia emergência e
     * rotina misturadas. É exatamente o que o controle de piso existe para impedir.
     *
     * O conserto usa um sinal que **já estava no fio**: o anúncio de fala do
     * emissor P1 é difundido para o talk group inteiro, inclusive para quem está
     * transmitindo. Ele vira o gatilho de uma confirmação imediata com o árbitro —
     * e é a resposta do árbitro, nunca o anúncio, que corta a fala. Ver
     * `SessaoPtt.vigiarTomadaDoPiso`.
     *
     * O teste mede a janela em quadros de 20 ms em vez de dizer que ela é
     * "pequena": adjetivo esconde regressão, número não.
     */
    @Test
    fun aEmergenciaTomaOCanal_eOInterrompidoSaiDoFioNaHora() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val charlie = ParDeRadio("charlie", barramento)

        val eventosDeAlfa = mutableListOf<EventoPtt>()
        val job = launch {
            sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { agora }, renovarACadaMs = 5_000)
                .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", flow {
                    repeat(500) { delay(20); agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
                }) { eventosDeAlfa += it }
        }
        advanceTimeBy(400) // Alfa no ar há 400 ms
        val quadrosAntes = charlie.recebidos.count {
            it is EventoDeRede.Quadro && it.quadro.transmissaoId == "tx-alfa"
        }
        assertTrue("a bancada precisa de fala em curso para o teste valer", quadrosAntes > 0)

        // **Bravo aperta com P1 numa sessão de verdade**, e não só pedindo o piso:
        // é a sessão que ANUNCIA, e o anúncio é o sinal que fecha a janela. Um
        // `pedir` cru mediria um mundo em que ninguém avisa ninguém.
        val jobBravo = launch {
            sessaoDe(bravo, ClienteDePisoDoPar(bravo, servidor), { agora })
                .transmitir("tx-bravo", PrioridadeTransmissao.P1_EMERGENCIA, "Bravo Dois", flow {
                    repeat(50) { delay(20); emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
                }) {}
        }

        advanceTimeBy(400) // MUITO antes dos 5 000 ms da renovação
        val depois = charlie.recebidos.count {
            it is EventoDeRede.Quadro && it.quadro.transmissaoId == "tx-alfa"
        } - quadrosAntes

        assertTrue(
            "o interrompido tem de SABER — seguir falando para o vazio é pior que " +
                "ser cortado (eventos: $eventosDeAlfa)",
            eventosDeAlfa.any { it is EventoPtt.CanalPerdido },
        )
        assertTrue(
            "a sobreposição foi de $depois quadros (${depois * 20} ms). O teto é o " +
                "quadro em curso mais o tempo de uma confirmação com o árbitro — " +
                "não o intervalo de renovação.",
            depois <= 3,
        )

        jobBravo.cancel()
        job.join()
    }

    /**
     * **O contra-teste do bloqueador, e ele é uma DESIGUALDADE medida.**
     *
     * As duas corridas mudam **uma** coisa: se quem toma o canal anuncia a fala.
     * Com anúncio, a vigia corta na hora. Sem anúncio — que é o que uma mensagem
     * perdida no Realtime produz —, a descoberta volta a depender da renovação, e
     * a janela volta a ser os 5 000 ms do defeito original.
     *
     * Vale duas vezes: prova que o conserto é a vigia (e não sorte de escalonador)
     * **e** documenta honestamente a degradação quando o anúncio se perde. Se
     * alguém desligar a vigia, as duas corridas voltam a dar o mesmo número e este
     * teste cai.
     */
    @Test
    fun semOAnuncioDoInterruptor_aJanelaVoltaASerOIntervaloDeRenovacao() = runTest {
        suspend fun quadrosDepoisDaTomada(comAnuncio: Boolean): Int {
            var agora = 0L
            val servidor = PisoServidor(relogio = { agora })
            val barramento = BarramentoTatico()
            val alfa = ParDeRadio("alfa", barramento)
            val bravo = ParDeRadio("bravo", barramento)
            val charlie = ParDeRadio("charlie", barramento)
            val job = launch {
                sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { agora }, renovarACadaMs = 5_000)
                    .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa", flow {
                        repeat(500) { delay(20); agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
                    }) {}
            }
            advanceTimeBy(400)
            val antes = charlie.recebidos.count {
                it is EventoDeRede.Quadro && it.quadro.transmissaoId == "tx-alfa"
            }

            val pisoDeBravo = ClienteDePisoDoPar(bravo, servidor)
            pisoDeBravo.pedir(gta, "bravo", "tx-bravo", PrioridadeTransmissao.P1_EMERGENCIA)
            if (comAnuncio) {
                bravo.anunciar(
                    AnuncioDeFala(
                        transmissaoId = "tx-bravo",
                        autorIndicativo = "Bravo Dois",
                        autorAgenteId = "bravo",
                        prioridade = PrioridadeTransmissao.P1_EMERGENCIA,
                    ),
                )
            }

            advanceTimeBy(6_000)
            job.join()
            return charlie.recebidos.count {
                it is EventoDeRede.Quadro && it.quadro.transmissaoId == "tx-alfa"
            } - antes
        }

        val comAnuncio = quadrosDepoisDaTomada(comAnuncio = true)
        val semAnuncio = quadrosDepoisDaTomada(comAnuncio = false)

        assertTrue(
            "se as duas corridas não diferem, a vigia não está fazendo nada " +
                "(com anúncio=$comAnuncio quadros, sem anúncio=$semAnuncio)",
            comAnuncio < semAnuncio,
        )
        assertTrue(
            "com anúncio a sobreposição tem de ser de quadros, não de segundos " +
                "($comAnuncio quadros = ${comAnuncio * 20} ms)",
            comAnuncio <= 3,
        )
        assertTrue(
            "e sem anúncio ela volta a ser a janela de renovação — degradação " +
                "declarada, não escondida ($semAnuncio quadros)",
            semAnuncio * 20 >= 4_000,
        )
    }

    @Test
    fun umaEmergenciaNaoCortaOutraEmergencia_eOSegundoP1SabeDisso() = runTest {
        val servidor = PisoServidor(relogio = { 0L })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)

        ClienteDePisoDoPar(alfa, servidor).pedir(gta, "alfa", "tx-a", PrioridadeTransmissao.P1_EMERGENCIA)
        val r = ClienteDePisoDoPar(bravo, servidor)
            .pedir(gta, "bravo", "tx-b", PrioridadeTransmissao.P1_EMERGENCIA)

        assertTrue("duas P1 se revezando calariam as duas", r is ResultadoDoPedido.Ocupado)
        assertEquals("alfa", (r as ResultadoDoPedido.Ocupado).detentor.agenteId)
    }

    // ── 7. Três ou mais disputando ────────────────────────────────────────────

    @Test
    fun quatroAgentesApertamJuntos_soUmaVozVaiAoFio() = runTest {
        val servidor = PisoServidor(relogio = { currentTime })
        val barramento = BarramentoTatico()
        val pares = listOf("alfa", "bravo", "charlie", "delta").map { ParDeRadio(it, barramento) }

        val eventos = pares.associate { it.agenteId to mutableListOf<EventoPtt>() }
        // **A fala precisa DURAR.** Com um fluxo sem `delay`, o primeiro a rodar
        // termina — e libera o canal — antes de o segundo pedir, e o teste mediria
        // quatro falas em fila em vez de quatro dedos no botão ao mesmo tempo.
        coroutineScope {
            pares.forEach { par ->
                launch {
                    sessaoDe(par, ClienteDePisoDoPar(par, servidor), { currentTime })
                        .transmitir(
                            "tx-${par.agenteId}", PrioridadeTransmissao.P2_APOIO, par.agenteId,
                            fala(6),
                        ) { eventos.getValue(par.agenteId) += it }
                }
            }
        }
        advanceUntilIdle()

        val queFalaram = pares.filter { par ->
            par.enviados.any { it is EventoDeRede.Quadro }
        }
        assertEquals("uma voz por vez, por talk group", 1, queFalaram.size)

        val recusados = pares - queFalaram.toSet()
        recusados.forEach { par ->
            assertTrue(
                "${par.agenteId} foi recusado e precisa de um evento — falha nunca é silêncio",
                eventos.getValue(par.agenteId).any { it is EventoPtt.CanalOcupado },
            )
        }
        // O que cada receptor tirou do fio só pode conter UMA transmissão.
        pares.forEach { par ->
            val transmissoes = par.recebidos.filterIsInstance<EventoDeRede.Quadro>()
                .map { it.quadro.transmissaoId }.toSet()
            assertTrue(
                "${par.agenteId} recebeu $transmissoes — mais de uma voz no mesmo instante",
                transmissoes.size <= 1,
            )
        }
    }

    // ── 8. O agente sai do grupo com o piso na mão ────────────────────────────

    /**
     * **CONSERTADO (22/08, migração `0024`): renovar confere pertencimento.**
     *
     * `pedir_canal` (`0005_controle_de_piso.sql:78-82`) recusava quem não é membro
     * — mas só na hora de PEDIR. `renovar_canal` (`:135-151`) conferia apenas
     * `transmissao_id` + `agent_id` + validade, e [ControleDePiso], a política que
     * espelha o SQL, não tinha a noção de grupo em lugar nenhum.
     *
     * Antes: Alfa saía da guarnição, parava de ser ouvido (o barramento não
     * entrega mais os quadros dele) **e continuava detendo o canal**. A guarnição
     * ficava muda com o piso na mão de quem já não pertencia a ela.
     *
     * Agora a renovação **apaga** a concessão, e não só a recusa: recusar deixaria
     * a linha de pé até o TTL, e os 30 s de silêncio seriam pagos por quem ficou.
     */
    @Test
    fun oAgenteQueSaiDoGrupoComOPisoNaMao_perdeOCanalNaRenovacao() = runTest {
        var agora = 0L
        val barramento = BarramentoTatico()
        // **O árbitro consulta o cadastro**, como `renovar_canal` passou a fazer.
        val servidor = PisoServidor(relogio = { agora }, barramento = barramento)
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor)

        val concessao = (pisoDeAlfa.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)
            as ResultadoDoPedido.Concedido).concessao

        alfa.noGrupo = false // removido do talk group no meio da fala

        agora = 4_000
        assertFalse(
            "quem já não é do grupo não renova o piso dele",
            pisoDeAlfa.renovar(concessao),
        )
        assertNull(
            "e a concessão sai na hora: esperar o TTL cobraria 30 s de silêncio de " +
                "quem não fez nada",
            servidor.detentor(gta),
        )
        assertTrue(
            "a guarnição legítima assume o canal imediatamente",
            ClienteDePisoDoPar(bravo, servidor)
                .pedir(gta, "bravo", "tx-b", PrioridadeTransmissao.P2_APOIO)
                is ResultadoDoPedido.Concedido,
        )
        // A entrega, essa sim, para — e para em SILÊNCIO. Não é o assunto deste
        // conserto e continua registrado: é do transporte, não do piso.
        val envio = alfa.enviar(QuadroAudio("tx-alfa", 0, agora, ByteArray(3)))
        assertTrue(
            "com `ack: false` o aparelho não recebe recusa: ele ACHA que falou",
            envio is com.claryon.common.Result.Success,
        )
        assertTrue("mas ninguém ouve o ex-membro", bravo.recebidos.isEmpty())
    }

    /**
     * O contra-teste: quem **continua** na guarnição renova normalmente.
     *
     * Sem ele, um `renovar` que devolvesse `false` sempre passaria no teste acima
     * — e cortaria toda transmissão de rádio do produto na primeira renovação.
     */
    @Test
    fun quemContinuaNoGrupo_renovaNormalmente() = runTest {
        var agora = 0L
        val barramento = BarramentoTatico()
        val servidor = PisoServidor(relogio = { agora }, barramento = barramento)
        val alfa = ParDeRadio("alfa", barramento)
        val pisoDeAlfa = ClienteDePisoDoPar(alfa, servidor)

        val concessao = (pisoDeAlfa.pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)
            as ResultadoDoPedido.Concedido).concessao

        agora = 4_000
        assertTrue("membro do grupo renova", pisoDeAlfa.renovar(concessao))
        assertEquals("alfa", servidor.detentor(gta)?.agenteId)
    }

    /**
     * **Quem não é do grupo também não PEDE** — e a recusa é tipada.
     *
     * `Ocupado` mandaria o agente esperar por uma vez que nunca chega; `SemRede`
     * o mandaria procurar torre. A resposta honesta é a terceira.
     */
    @Test
    fun quemNaoEhDoGrupo_recebeRecusaTipada_eNaoCanalOcupado() = runTest {
        val barramento = BarramentoTatico()
        val servidor = PisoServidor(relogio = { 0L }, barramento = barramento)
        val alfa = ParDeRadio("alfa", barramento)
        alfa.noGrupo = false

        val r = ClienteDePisoDoPar(alfa, servidor)
            .pedir(gta, "alfa", "tx-alfa", PrioridadeTransmissao.P2_APOIO)

        assertTrue("recusa por autorização, não por ocupação: $r", r is ResultadoDoPedido.Recusado)
        assertNull("e ninguém ficou com a palavra", servidor.detentor(gta))
    }

    /**
     * **A migração `0024` existe e confere `memberships` — lido do arquivo.**
     *
     * A política em Kotlin espelha o SQL, mas quem decide em produção é o Postgres,
     * e ele não roda nesta bancada (ver [BarramentoTatico], limitação 2). Ler a
     * migração é a única verificação possível em JVM — e arquivo ausente reprova,
     * pelo mesmo motivo do teste de identidade por parâmetro: uma trava que se
     * declara inconclusiva é uma trava desligada.
     */
    @Test
    fun aMigracao0024_poeMembershipEmRenovarCanal_semTocarNa0005() {
        val raiz = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "servidor/migracoes").isDirectory }
        assertNotNull("pasta de migrações não achada", raiz)

        val nova = java.io.File(raiz, "servidor/migracoes/0024_renovar_confere_membership.sql")
        assertTrue("a migração 0024 não existe: ${nova.absolutePath}", nova.isFile)

        val corpo = nova.readText()
        assertTrue(
            "a 0024 tem de redefinir renovar_canal",
            Regex("""create or replace function renovar_canal""", RegexOption.IGNORE_CASE)
                .containsMatchIn(corpo),
        )
        assertTrue(
            "sem consulta a memberships, a 0024 não conserta nada",
            corpo.contains("public.memberships"),
        )
        assertTrue(
            "e a concessão do ex-membro tem de SAIR, não só deixar de renovar",
            Regex("""delete\s+from\s+public\.floor_grants""", RegexOption.IGNORE_CASE)
                .containsMatchIn(corpo),
        )
        assertFalse(
            "a identidade continua vindo do JWT, nunca por parâmetro (§2)",
            Regex("""create or replace function renovar_canal\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
                .find(corpo)!!.groupValues[1].contains("agent", ignoreCase = true),
        )

        val antiga = java.io.File(raiz, "servidor/migracoes/0005_controle_de_piso.sql")
        assertFalse(
            "a 0005 é histórico aplicado e não pode ter sido reescrita — ambientes " +
                "que já a rodaram divergiriam em silêncio",
            antiga.readText().contains("public.memberships\n     where agent_id = v_eu and talk_group_id = v_grupo"),
        )
    }

    // ── 9. JWT vencido no meio da sessão ──────────────────────────────────────

    /**
     * **Token vence com o dedo no botão.**
     *
     * O servidor derruba o canal por evento `system` (`ProtocoloRealtime.kt:216-222`),
     * `TransporteRealtime` põe `autorizado = false` e o portão de saída
     * (`:278-302`) passa a recusar. A captura **não** para — é a regra declarada em
     * [EventoPtt.QuadrosNaoEntregues] — e a perda é contada.
     *
     * Observado: a fala é cortada ao meio para quem escuta, e o único sinal que o
     * emissor recebe é a contagem de quadros não entregues **no fim**.
     */
    @Test
    fun oJwtVenceNoMeioDaFala_aCapturaSegue_masOsColegasParamDeOuvir() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)

        val eventos = mutableListOf<EventoPtt>()
        val job = launch {
            sessaoDe(alfa, ClienteDePisoDoPar(alfa, servidor), { agora })
                .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa Um", flow {
                    repeat(60) { delay(20); agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
                }) { eventos += it }
        }
        advanceTimeBy(300)
        val ouvidosAntes = bravo.recebidos.count { it is EventoDeRede.Quadro }
        assertTrue("a bancada precisa de fala no ar", ouvidosAntes > 0)

        alfa.autorizado = false
        alfa.motivoDaRecusa = "token vencido"
        advanceTimeBy(900)
        job.join()

        assertEquals(
            "a partir do vencimento, o colega não ouve mais nada",
            ouvidosAntes,
            bravo.recebidos.count { it is EventoDeRede.Quadro },
        )
        assertFalse("e o transporte sabe que não há canal", alfa.conectado())
        val perdidos = eventos.filterIsInstance<EventoPtt.QuadrosNaoEntregues>().firstOrNull()
        assertNotNull("a perda tem de ser contada, e não engolida", perdidos)
        assertTrue(perdidos!!.quantidade > 0)
        assertEquals(
            "OBSERVADO: o aviso só chega no FIM — é o evento imediatamente anterior a " +
                "Encerrada, e não há nada durante a fala dizendo que ela parou de sair",
            eventos.indexOfFirst { it is EventoPtt.Encerrada } - 1,
            eventos.indexOfFirst { it is EventoPtt.QuadrosNaoEntregues },
        )
    }

    /**
     * O outro lado do mesmo vencimento: quando o JWT também derruba o RPC do piso,
     * `renovar` devolve `false` e a sessão para — que é o desfecho **desejável**,
     * porque calar é melhor que falar sem saber se alguém ouve.
     */
    @Test
    fun quandoOJwtDerrubaTambemOPiso_aSessaoParaEmVezDeFalarParaOVazio() = runTest {
        var agora = 0L
        val servidor = PisoServidor(relogio = { agora })
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)

        val piso = object : ClienteDePiso {
            val real = ClienteDePisoDoPar(alfa, servidor)
            override suspend fun pedir(
                talkGroupId: String,
                agenteId: String,
                transmissaoId: String,
                prioridade: PrioridadeTransmissao,
            ) = real.pedir(talkGroupId, agenteId, transmissaoId, prioridade)
            override suspend fun renovar(concessao: Concessao) = false // 401 do PostgREST
            override suspend fun liberar(concessao: Concessao) = real.liberar(concessao)
        }

        val eventos = mutableListOf<EventoPtt>()
        sessaoDe(alfa, piso, { agora }, renovarACadaMs = 100)
            .transmitir("tx-alfa", PrioridadeTransmissao.P2_APOIO, "Alfa", flow {
                repeat(60) { delay(20); agora += 20; emit(ShortArray(amostrasPorQuadro) { 3_000 }) }
            }) { eventos += it }
        advanceUntilIdle()

        assertTrue(eventos.any { it is EventoPtt.CanalPerdido })
        assertTrue(
            "não pode seguir transmitindo depois de descobrir que não tem canal",
            alfa.enviados.count { it is EventoDeRede.Quadro } < 30,
        )
    }

    // ── 10. A identidade nunca vem por parâmetro ──────────────────────────────

    /**
     * **Regra dura do `CLAUDE.md` §2**, e ela vive no SQL, não no Kotlin: nenhuma
     * das três funções de piso pode receber quem pergunta como argumento. Se
     * recebesse, qualquer cliente pediria — ou liberaria — o canal em nome de
     * outro, e o piso viraria negação de serviço contra a guarnição.
     *
     * O teste lê a migração. Arquivo ausente reprova, e é assim que tem de ser:
     * uma trava que se declara inconclusiva é uma trava desligada.
     */
    @Test
    fun asTresFuncoesDePisoDerivamAIdentidadeDoJwt_enuncaDeParametro() {
        val sql = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "servidor/migracoes/0005_controle_de_piso.sql") }
            .firstOrNull { it.isFile }
        assertNotNull(
            "a migração de piso não foi achada subindo de ${java.io.File("").absolutePath}",
            sql,
        )
        val corpo = sql!!.readText()

        listOf("pedir_canal", "renovar_canal", "liberar_canal").forEach { funcao ->
            val assinatura = Regex(
                """create or replace function $funcao\s*\(([^)]*)\)""",
                RegexOption.IGNORE_CASE,
            ).find(corpo)
            assertNotNull("$funcao sumiu da migração 0005", assinatura)
            val parametros = assinatura!!.groupValues[1]
            assertFalse(
                "$funcao recebe identidade por parâmetro ($parametros) — proibição dura",
                parametros.contains("agent", ignoreCase = true) ||
                    parametros.contains("agente", ignoreCase = true),
            )
        }
        assertEquals(
            "as três funções têm de derivar o solicitante de current_agent_id()",
            3,
            Regex("""private\.current_agent_id\(\)""").findAll(corpo).count(),
        )
    }
}
