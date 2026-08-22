package com.claryon.field.radio

import com.claryon.agent.Utterance
import com.claryon.audio.FluxoDeReproducao
import com.claryon.audio.rotaDeTeste
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Result
import com.claryon.net.AnuncioDeFala
import com.claryon.net.ClienteDePiso
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.CodecDeVoz
import com.claryon.net.Concessao
import com.claryon.net.EventoDeRede
import com.claryon.net.PrioridadeTransmissao
import com.claryon.net.QuadroAudio
import com.claryon.net.ResultadoDoPedido
import com.claryon.net.SupressorDeSaidaPropria
import com.claryon.net.TransporteAoVivo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Onde os mecanismos se cruzam — e é no cruzamento que o rádio quebra.**
 *
 * `RadioTaticoTest` cobre cada peça ligada à seguinte. Este arquivo provoca a
 * COMPOSIÇÃO delas: piso negado enquanto o dedo desce, emergência de outro agente
 * chegando durante fala de rotina, o copiloto falando quando o agente aperta o
 * PTT, e a combinação que produz o pior achado deste arquivo — o piso volta a
 * estar livre **antes** de o receptor concluir que a fala anterior acabou.
 *
 * Nada aqui toca hardware. `pcmDoMicrofone` e `abrirFluxoDeSaida` são parâmetros
 * de construtor, e é por isso que a coordenação entre entrada e saída é
 * verificável em JVM.
 *
 * **Só `advanceTimeBy`.** O microfone falso e o laço de reprodução rodam
 * `while (true) { delay(...) }`: em tempo virtual sempre há mais uma tarefa
 * agendada, e `advanceUntilIdle` gira para sempre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaosDoRadioComNParesTest {

    private val rota = rotaDeTeste(11)
    private val taxa = 16_000
    private val amostrasPorQuadro = taxa / 50

    // ── Dublês ────────────────────────────────────────────────────────────────

    private class CodecFake : CodecDeVoz {
        override suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>> =
            Result.success(listOf(ByteArray(30) { 1 }))

        override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> =
            Result.success(ShortArray(480) { 100 })

        override val taxaDeSaidaHz = 24_000
        override fun liberar() = Unit
    }

    private class TransporteFake : TransporteAoVivo {
        val quadros = mutableListOf<QuadroAudio>()
        val entrada = MutableSharedFlow<EventoDeRede>(extraBufferCapacity = 256)
        private var vivo = true

        override suspend fun conectar(talkGroupId: String) = Result.success(Unit)
        override suspend fun anunciar(anuncio: AnuncioDeFala) = Result.success(Unit)
        override suspend fun enviar(quadro: QuadroAudio): Result<Unit> {
            quadros.add(quadro)
            return Result.success(Unit)
        }
        override suspend fun encerrar(transmissaoId: String) = Result.success(Unit)
        override fun eventos(): Flow<EventoDeRede> = entrada
        override fun conectado() = vivo
        override suspend fun desconectar() { vivo = false }

        /** Quadros de voz — o `ultimo` vai vazio e não conta como fala. */
        val quadrosDeVoz get() = quadros.count { !it.ultimo }
    }

    private class SaidaFake {
        fun abrir(@Suppress("UNUSED_PARAMETER") taxaHz: Int): FluxoDeReproducao =
            object : FluxoDeReproducao {
                override suspend fun escrever(pcm: ShortArray) = Result.success(Unit)
                override fun fechar() = Unit
            }
    }

    /** Microfone que entrega um bloco a cada 20 ms, para sempre. */
    private fun microfone(): Flow<ShortArray> = flow {
        while (true) {
            delay(20)
            emit(ShortArray(amostrasPorQuadro) { 6_000 })
        }
    }

    /** Piso que recusa sempre — outro agente está com a palavra. */
    private class PisoOcupadoPor(private val detentor: String) : ClienteDePiso {
        override suspend fun pedir(
            talkGroupId: String,
            agenteId: String,
            transmissaoId: String,
            prioridade: PrioridadeTransmissao,
        ) = ResultadoDoPedido.Ocupado(
            Concessao(talkGroupId, detentor, "tx-do-outro", PrioridadeTransmissao.P2_APOIO, 30_000),
        )

        override suspend fun renovar(concessao: Concessao) = true
        override suspend fun liberar(concessao: Concessao) = Unit
    }

    /** Piso que concede e depois some — o canal é tomado no meio da fala. */
    private class PisoQuePerdeNoMeio : ClienteDePiso {
        override suspend fun pedir(
            talkGroupId: String,
            agenteId: String,
            transmissaoId: String,
            prioridade: PrioridadeTransmissao,
        ) = ResultadoDoPedido.Concedido(
            Concessao(talkGroupId, agenteId, transmissaoId, prioridade, 30_000),
        )

        override suspend fun renovar(concessao: Concessao) = false
        override suspend fun liberar(concessao: Concessao) = Unit
    }

    private class Bancada(
        val radio: RadioTatico,
        val transporte: TransporteFake,
        val supressor: SupressorDeSaidaPropria,
        val emitidos: MutableList<Utterance>,
        val quemFalou: MutableList<String?>,
    ) {
        val earcons get() = emitidos.filterIsInstance<Utterance.Sinalizar>().map { it.earcon }
    }

    private suspend fun TestScope.comRadio(
        piso: ClienteDePiso = ClienteDePisoLocal(),
        supressor: SupressorDeSaidaPropria = SupressorDeSaidaPropria(),
        cadastro: Map<String, String> = mapOf("bravo" to "Bravo Dois"),
        corpo: suspend (Bancada) -> Unit,
    ) {
        val escopo = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val transporte = TransporteFake()
        val emitidos = mutableListOf<Utterance>()
        val quemFalou = mutableListOf<String?>()
        val radio = RadioTatico(
            escopo = escopo,
            talkGroupId = "gta-3",
            agenteId = "alfa",
            indicativo = "Alfa Um",
            resolverAutor = { id -> cadastro[id] },
            transporte = transporte,
            codec = CodecFake(),
            piso = piso,
            pcmDoMicrofone = { microfone() },
            abrirFluxoDeSaida = { taxaHz -> SaidaFake().abrir(taxaHz) },
            emitir = { u -> emitidos.add(u) },
            duracaoDoEarconMs = { 320L },
            aoMudarQuemFala = { quemFalou += it },
            agoraMs = { currentTime },
            sampleRateHz = taxa,
            supressor = supressor,
        )
        try {
            corpo(Bancada(radio, transporte, supressor, emitidos, quemFalou))
        } finally {
            escopo.cancel()
        }
    }

    private suspend fun TransporteFake.chega(
        tx: String,
        autor: String = "bravo",
        prioridade: PrioridadeTransmissao = PrioridadeTransmissao.P2_APOIO,
    ) = entrada.emit(
        EventoDeRede.Anuncio(
            AnuncioDeFala(
                transmissaoId = tx,
                autorIndicativo = autor,
                autorAgenteId = autor,
                prioridade = prioridade,
            ),
        ),
    )

    private suspend fun TransporteFake.quadroDe(tx: String, seq: Int, ultimo: Boolean = false) =
        entrada.emit(EventoDeRede.Quadro(QuadroAudio(tx, seq, 0L, ByteArray(30) { 1 }, ultimo)))

    // ── 1. O canal negado ─────────────────────────────────────────────────────

    /**
     * **Falha nunca é silêncio.** O agente aperta, o canal está com outro, e o
     * único jeito de ele saber — sem display, com os óculos no rosto — é o tom.
     *
     * O teste também exige que o gatilho volte a aceitar toque: recusa que trava o
     * botão faria o agente perder a janela em que o canal de fato liberou.
     */
    @Test
    fun oCanalNegado_viraTomDeFalha_eODedoPodeTentarDeNovo() = runTest {
        comRadio(piso = PisoOcupadoPor("bravo")) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)

            assertTrue(
                "o agente precisa OUVIR a recusa — sem display, o tom é a única saída",
                c.earcons.contains(Earcon.FALHA),
            )
            assertEquals("nada pode ir para a rede", 0, c.transporte.quadrosDeVoz)
            assertFalse("o gatilho tem de estar livre para a próxima tentativa", c.radio.transmitindo)
        }
    }

    /** Contra-teste: com o canal livre, nenhum tom de falha e a voz sai. */
    @Test
    fun oCanalLivre_naoTocaTomDeFalha_eAVozSai() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)
            c.radio.aoSoltar()
            advanceTimeBy(100)

            assertFalse(
                "tom de falha com o canal livre treinaria o agente a ignorar o tom",
                c.earcons.contains(Earcon.FALHA),
            )
            assertTrue("a voz tem de sair", c.transporte.quadrosDeVoz > 0)
        }
    }

    // ── 2. Emergência de outro agente ─────────────────────────────────────────

    /**
     * **P1 de um colega durante o turno: tom prioritário, na prioridade máxima da
     * fila de som.**
     *
     * A prioridade importa tanto quanto o earcon: [Priority.EMERGENCIA] é o que
     * faz `PrioritySoundQueue` **cortar** o que estiver tocando, inclusive uma
     * fala do copiloto em síntese. Um earcon prioritário emitido em prioridade de
     * resposta entraria na fila atrás da frase que ele deveria interromper.
     */
    @Test
    fun aEmergenciaDeUmColega_tocaTomPrioritarioNaPrioridadeQueCortaAFila() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.transporte.chega("tx-p1", prioridade = PrioridadeTransmissao.P1_EMERGENCIA)
            advanceTimeBy(60)

            val prioritario = c.emitidos.filterIsInstance<Utterance.Sinalizar>()
                .firstOrNull { it.earcon == Earcon.PRIORITARIA }
            assertTrue("uma emergência do grupo tem de soar diferente", prioritario != null)
            assertEquals(
                "em prioridade de EMERGÊNCIA, senão ela entra na fila atrás do que devia cortar",
                Priority.EMERGENCIA,
                prioritario!!.priority,
            )
            assertEquals(
                "e a tela precisa dizer quem é",
                listOf<String?>("Bravo Dois"),
                c.quemFalou,
            )
        }
    }

    /** Contra-teste: fala de rotina **não** pode disparar o tom de emergência. */
    @Test
    fun aFalaDeRotinaDeUmColega_naoTocaTomPrioritario() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.transporte.chega("tx-p2", prioridade = PrioridadeTransmissao.P2_APOIO)
            advanceTimeBy(60)

            assertFalse(
                "se toda fala soasse como emergência, nenhuma soaria",
                c.earcons.contains(Earcon.PRIORITARIA),
            )
            assertEquals(listOf<String?>("Bravo Dois"), c.quemFalou)
        }
    }

    // ── 3. O copiloto falando quando o dedo desce ─────────────────────────────

    /**
     * **O agente aperta o PTT enquanto o copiloto fala — e a voz dele some.**
     *
     * É a disciplina de meio-duplex funcionando: os alto-falantes são *open-ear* e
     * tudo que sai volta pelo microfone. Descartar é melhor que difundir a própria
     * saída para a guarnição.
     *
     * O que o teste registra, e o produto não conta ao agente: o descarte dura a
     * janela inteira da fala sintetizada — `RadioTatico.DURACAO_FALA_ESTIMADA_MS`
     * são **2 000 ms**, mais os 80 ms de margem — e **nenhum tom avisa**. O agente
     * fala 2 s para ninguém, com a barra de PTT no ar.
     *
     * **O que a primeira versão deste teste não previa, e é o segundo achado:** o
     * fio não fica vazio. O **pré-roll** — capturado antes de o copiloto começar —
     * é despejado no instante do toque, então a guarnição ouve um fragmento curto
     * de áudio velho e depois silêncio. Por isso o teste mede a voz **ao vivo**
     * separada do pré-roll, em vez de exigir zero e ficar vermelho pelo motivo
     * errado.
     */
    @Test
    fun oPttApertadoEnquantoOCopilotoFala_descartaAVozAoVivoSemAvisar() = runTest {
        val supressor = SupressorDeSaidaPropria()
        comRadio(supressor = supressor) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            // O copiloto começa a falar. Em produção quem registra é `SaidaUnica`,
            // com a MESMA instância de supressor.
            supressor.registrar(currentTime, 2_000)

            c.radio.aoPressionar(rota)
            advanceTimeBy(40) // o pré-roll já foi despejado
            val soPreRoll = c.transporte.quadrosDeVoz
            assertTrue(
                "OBSERVADO: o pré-roll sai mesmo com a captura ao vivo toda suprimida " +
                    "— áudio anterior à fala do copiloto ($soPreRoll quadros)",
                soPreRoll in 1..10,
            )

            advanceTimeBy(1_460)
            assertEquals(
                "OBSERVADO: 1,5 s de fala AO VIVO do agente descartados, e o fio não " +
                    "recebeu um quadro sequer além do pré-roll",
                soPreRoll,
                c.transporte.quadrosDeVoz,
            )
            assertFalse(
                "OBSERVADO: e nenhum tom avisa que a voz não está saindo",
                c.earcons.contains(Earcon.FALHA),
            )

            // Passada a janela (2 000 ms + 80 ms de margem), a voz volta a sair.
            advanceTimeBy(1_000)
            assertTrue(
                "depois da janela a captura tem de voltar",
                c.transporte.quadrosDeVoz > soPreRoll + 20,
            )
            c.radio.aoSoltar()
            advanceTimeBy(100)
        }
    }

    /** Contra-teste: sem o copiloto falando, os mesmos 1,5 s rendem voz no fio. */
    @Test
    fun semOCopilotoFalando_osMesmos1500msRendemVozNoFio() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(1_500)

            assertTrue(
                "se este número também fosse zero, o teste acima não testaria a supressão",
                c.transporte.quadrosDeVoz > 20,
            )
            c.radio.aoSoltar()
            advanceTimeBy(100)
        }
    }

    // ── 4. A composição que morde ─────────────────────────────────────────────

    /**
     * **O achado deste arquivo: o piso libera antes de o receptor concluir.**
     *
     * Dois relógios independentes governam o mesmo instante:
     *
     *  - O **piso** volta ao grupo quando quem falava solta o botão (ou quando o
     *    TTL vence).
     *  - O **supressor** só fecha a janela de recepção no
     *    [com.claryon.net.EventoRecepcao.Terminou], que o [com.claryon.net.Receptor]
     *    só emite **2 000 ms** depois do último quadro quando a fala foi cortada
     *    pela rede — não há `ultimo` para encerrá-la antes.
     *
     * Resultado observado: Alfa consegue o canal, a barra de PTT sobe, o
     * cronômetro anda — e **os primeiros 2 s de fala dele não vão para o fio**.
     * Nenhum tom, nenhum estado de tela, nada. É a interação de dois mecanismos
     * corretos produzindo uma falha silenciosa.
     */
    @Test
    fun aposFalaDeColegaCortadaPelaRede_oPisoLibera_masOsPrimeiros2sDeAlfaSaoDescartados() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            // Bravo fala e a rede dele cai: quadros chegam, o `ultimo` nunca vem.
            c.transporte.chega("tx-bravo")
            repeat(6) { c.transporte.quadroDe("tx-bravo", it) }
            advanceTimeBy(300)
            assertTrue("a janela de recepção tem de estar aberta", c.supressor.suprimido(currentTime))

            // Alfa aperta. O piso está livre — ninguém detém o canal aqui.
            c.radio.aoPressionar(rota)
            advanceTimeBy(40)
            // O que sai é pré-roll: áudio capturado ANTES de a fala de Bravo
            // começar, portanto já velho quando chega ao fio.
            val soPreRoll = c.transporte.quadrosDeVoz
            advanceTimeBy(460)

            assertTrue("o rádio ACHA que está transmitindo", c.radio.transmitindo)
            assertEquals(
                "OBSERVADO: meio segundo de fala AO VIVO de Alfa descartado, com o canal " +
                    "concedido e a barra de PTT no ar",
                soPreRoll,
                c.transporte.quadrosDeVoz,
            )
            assertFalse(
                "OBSERVADO: e nada avisa o agente",
                c.earcons.contains(Earcon.FALHA),
            )

            // O receptor desiste (100 × 20 ms) e fecha a janela; a voz volta.
            advanceTimeBy(2_200)
            assertTrue(
                "depois de o receptor desistir, a captura volta",
                c.transporte.quadrosDeVoz > soPreRoll + 20,
            )
            c.radio.aoSoltar()
            advanceTimeBy(100)
        }
    }

    // ── 5. Perder o piso com o dedo no botão ──────────────────────────────────

    /**
     * **Ser cortado por uma emergência tem de ser audível e tem de soltar o
     * gatilho.**
     *
     * Continuar segurando o botão depois de perder a palavra faria o agente falar
     * para o vazio; e o gatilho travado impediria a próxima tentativa.
     */
    @Test
    fun perderOPisoNoMeio_viraTomDeEmergencia_eSoltaOGatilho() = runTest {
        comRadio(piso = PisoQuePerdeNoMeio()) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            assertTrue("a bancada precisa começar transmitindo", c.radio.transmitindo)
            advanceTimeBy(6_000) // além do intervalo de renovação

            val falha = c.emitidos.filterIsInstance<Utterance.Sinalizar>()
                .lastOrNull { it.earcon == Earcon.FALHA }
            assertTrue("perder a palavra em silêncio faria o agente falar para o vazio", falha != null)
            assertEquals(
                "em prioridade de emergência: é a informação mais urgente do momento",
                Priority.EMERGENCIA,
                falha!!.priority,
            )
            assertFalse("o gatilho tem de ser cancelado", c.radio.transmitindo)
        }
    }
}
