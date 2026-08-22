package com.claryon.field.radio

import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Utterance
import com.claryon.audio.FluxoDeReproducao
import com.claryon.audio.rotaDeTeste
import com.claryon.common.Earcon
import com.claryon.common.Priority
import com.claryon.common.Result
import com.claryon.net.AnuncioDeFala
import com.claryon.net.ClienteDePiso
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.ResultadoDaLiberacao
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
import org.junit.Assert.assertNotEquals
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
        override suspend fun liberar(concessao: Concessao) = ResultadoDaLiberacao.Devolvido
    }

    /**
     * Piso cujo pedido **não alcança o árbitro**. Distinto de [PisoOcupadoPor]
     * porque o gesto que resolve é o oposto: andar até pegar sinal, não esperar.
     */
    private class PisoSemRede : ClienteDePiso {
        override suspend fun pedir(
            talkGroupId: String,
            agenteId: String,
            transmissaoId: String,
            prioridade: PrioridadeTransmissao,
        ) = ResultadoDoPedido.SemRede

        override suspend fun renovar(concessao: Concessao) = false
        override suspend fun liberar(concessao: Concessao) =
            ResultadoDaLiberacao.NaoDevolvido("sem rede")
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
        override suspend fun liberar(concessao: Concessao) = ResultadoDaLiberacao.Devolvido
    }

    /**
     * Piso local **arbitrado pelo servidor** — a bancada padrão.
     *
     * `ClienteDePisoLocal` declara `arbitradoPeloServidor = false`, e o rádio agora
     * anuncia esse modo degradado por voz ao abrir. Usá-lo como padrão poria um
     * tom de falha no começo de todo teste deste arquivo, mascarando os tons que
     * cada teste existe para medir. O modo degradado tem teste **próprio**, abaixo.
     */
    private class PisoDeBancada(
        private val real: ClienteDePiso = ClienteDePisoLocal(),
    ) : ClienteDePiso by real {
        override val arbitradoPeloServidor: Boolean get() = true
    }

    private class Bancada(
        val radio: RadioTatico,
        val transporte: TransporteFake,
        val supressor: SupressorDeSaidaPropria,
        val emitidos: MutableList<Utterance>,
        val quemFalou: MutableList<String?>,
    ) {
        /**
         * Todo earcon que saiu, sozinho **ou** acompanhado de fala.
         *
         * Antes olhava só [Utterance.Sinalizar], e isso deixou de bastar quando as
         * recusas do piso passaram a falar a causa: um teste que conte só earcons
         * puros diria "não houve falha" sobre uma falha que foi anunciada em voz.
         */
        val earcons: List<Earcon> get() = emitidos.mapNotNull {
            when (it) {
                is Utterance.Sinalizar -> it.earcon
                is Utterance.SinalizarEFalar -> it.earcon
                is Utterance.Falar -> null
            }
        }

        /** O que foi de fato ao alto-falante em palavras. */
        val falas: List<String> get() = emitidos.mapNotNull {
            when (it) {
                is Utterance.Falar -> it.texto
                is Utterance.SinalizarEFalar -> it.texto
                is Utterance.Sinalizar -> null
            }
        }
    }

    private suspend fun TestScope.comRadio(
        piso: ClienteDePiso = PisoDeBancada(),
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
     * único jeito de ele saber — sem display, com os óculos no rosto — é o som.
     *
     * O teste também exige que o gatilho volte a aceitar toque: recusa que trava o
     * botão faria o agente perder a janela em que o canal de fato liberou.
     */
    @Test
    fun oCanalNegado_viraTomEFalaDeOcupado_eODedoPodeTentarDeNovo() = runTest {
        comRadio(piso = PisoOcupadoPor("bravo")) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)

            assertTrue(
                "o agente precisa OUVIR a recusa — sem display, o som é a única saída",
                c.earcons.contains(Earcon.FALHA),
            )
            assertTrue(
                "e o tom sozinho não diz o que FAZER: canal ocupado se resolve " +
                    "esperando, e é isso que a fala precisa carregar (falas: ${c.falas})",
                c.falas.contains(FalhaOperacional.CANAL_OCUPADO.causaCurta),
            )
            assertEquals("nada pode ir para a rede", 0, c.transporte.quadrosDeVoz)
            assertFalse("o gatilho tem de estar livre para a próxima tentativa", c.radio.transmitindo)
        }
    }

    /**
     * **CONSERTADO (22/08): falta de rede não pode soar como canal ocupado.**
     *
     * As duas recusas produziam o mesmo evento e o mesmo tom, e pedem gestos
     * opostos: esperar o colega soltar × andar até pegar sinal. O tom continua
     * sendo [Earcon.FALHA] nos dois — é a categoria certa —, e o que separa é a
     * fala.
     */
    @Test
    fun oPedidoSemRede_falaCausaPROPRIA_diferenteDaDeCanalOcupado() = runTest {
        comRadio(piso = PisoSemRede()) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)

            assertTrue(
                "falha nunca é silêncio, nem quando a causa é a rede",
                c.earcons.contains(Earcon.FALHA),
            )
            assertTrue(
                "a fala tem de ser a de SEM RESPOSTA (falas: ${c.falas})",
                c.falas.contains(FalhaOperacional.PEDIDO_DE_CANAL_SEM_RESPOSTA.causaCurta),
            )
            assertFalse(
                "e NUNCA a de canal ocupado, que manda o agente esperar embaixo de " +
                    "um viaduto por uma vez que nunca chega",
                c.falas.contains(FalhaOperacional.CANAL_OCUPADO.causaCurta),
            )
            assertEquals("nada foi ao ar", 0, c.transporte.quadrosDeVoz)
            assertFalse(c.radio.transmitindo)
        }
    }

    /**
     * **O contra-teste das duas recusas, e ele é uma DESIGUALDADE.**
     *
     * As duas corridas mudam só a causa da recusa e exigem falas diferentes. É a
     * asserção que cai se alguém reintroduzir o `Ocupado(detentor = "?")` do
     * caminho sem rede — ou se as duas causas voltarem a compartilhar frase, que
     * é a versão sonora do silêncio.
     */
    @Test
    fun canalOcupadoESemRede_naoPodemCompartilharFala() = runTest {
        suspend fun falasCom(piso: ClienteDePiso): List<String> {
            var saida: List<String> = emptyList()
            comRadio(piso = piso) { c ->
                c.radio.entrarEmModoAtivo(rota)
                advanceTimeBy(100)
                c.radio.aoPressionar(rota)
                advanceTimeBy(300)
                saida = c.falas
            }
            return saida
        }

        val ocupado = falasCom(PisoOcupadoPor("bravo"))
        val semRede = falasCom(PisoSemRede())

        assertTrue("ocupado precisa falar: $ocupado", ocupado.isNotEmpty())
        assertTrue("sem rede precisa falar: $semRede", semRede.isNotEmpty())
        assertNotEquals(
            "duas causas com ações OPOSTAS não podem chegar ao ouvido do agente " +
                "como a mesma frase",
            ocupado,
            semRede,
        )
    }

    /**
     * **CONSERTADO (22/08): o piso local se declara ao abrir o rádio.**
     *
     * Sem sessão, `RadioViewModel` cai em `ClienteDePisoLocal` e dois aparelhos
     * podem se achar donos do mesmo canal — que é o defeito que o controle de piso
     * existe para impedir. O rádio precisa funcionar em túnel e subsolo, então a
     * degradação fica; o que não podia ficar é ela ser **silenciosa**, e até aqui
     * o único sinal era um `Log.w` que ninguém de óculos lê.
     *
     * Na ABERTURA e uma vez só: o agente precisa saber antes de contar com o
     * rádio, e repetir a cada toque o treinaria a ignorar o aviso.
     */
    @Test
    fun oPisoLocalSemArbitro_seAnunciaNaAbertura_eUmaVezSo() = runTest {
        comRadio(piso = ClienteDePisoLocal()) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            assertEquals(
                "o modo degradado tem de ser dito, e dito uma vez (falas: ${c.falas})",
                1,
                c.falas.count { it == FalhaOperacional.PISO_SEM_ARBITRO.causaCurta },
            )

            // Uma transmissão inteira não pode repetir o aviso.
            c.radio.aoPressionar(rota)
            advanceTimeBy(300)
            c.radio.aoSoltar()
            advanceTimeBy(100)

            assertEquals(
                "repetir a cada PTT treinaria o agente a ignorar o aviso",
                1,
                c.falas.count { it == FalhaOperacional.PISO_SEM_ARBITRO.causaCurta },
            )
        }
    }

    /** O contra-teste: com piso arbitrado pelo servidor, o aviso **não** sai. */
    @Test
    fun comPisoArbitradoPeloServidor_nenhumAvisoDeDegradacao() = runTest {
        comRadio(piso = PisoDeBancada()) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            assertFalse(
                "avisar degradação onde não há degradação é a mesma mentira ao " +
                    "contrário (falas: ${c.falas})",
                c.falas.contains(FalhaOperacional.PISO_SEM_ARBITRO.causaCurta),
            )
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
     * **CONSERTADO (22/08) — era o achado deste arquivo: o piso liberava antes de
     * o receptor concluir, e a fala do agente seguinte sumia inteira.**
     *
     * Dois relógios independentes governavam o mesmo instante:
     *
     *  - O **piso** volta ao grupo quando quem falava solta o botão (ou no TTL).
     *  - O **supressor** só fechava a janela de recepção no
     *    [com.claryon.net.EventoRecepcao.Terminou], que o [com.claryon.net.Receptor]
     *    só emite **2 000 ms** depois do último quadro quando a fala foi cortada
     *    pela rede — não há `ultimo` para encerrá-la antes.
     *
     * Resultado: Alfa conseguia o canal, a barra de PTT subia, o cronômetro andava
     * — e os primeiros 2 s de fala dele **não iam para o fio**. Nenhum tom, nenhum
     * estado de tela, nada. Dois mecanismos corretos, uma composição errada.
     *
     * O conserto tirou a janela sem fim do supressor: quem reproduz registra
     * **bloco a bloco**, com a duração de cada bloco, e a supressão acaba junto
     * com o som — não junto com a conclusão de outro mecanismo. Ver
     * `SupressorDeSaidaPropria`.
     */
    @Test
    fun aposFalaDeColegaCortadaPelaRede_oPisoLibera_eAVozDeAlfaVAIAoFio() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            // Bravo fala e a rede dele cai: quadros chegam, o `ultimo` nunca vem.
            c.transporte.chega("tx-bravo")
            repeat(6) { c.transporte.quadroDe("tx-bravo", it) }
            advanceTimeBy(300)
            assertTrue(
                "enquanto os blocos de Bravo tocam, a captura CONTINUA suprimida — " +
                    "é a disciplina de meio-duplex, e ela não pode ter sido perdida",
                c.supressor.suprimido(currentTime - 20),
            )

            // Passada a cauda do último bloco recebido, a supressão acaba. O
            // receptor ainda vai levar ~2 s para CONCLUIR que a fala acabou, e é
            // justamente isso que não pode mais custar a fala de Alfa.
            advanceTimeBy(200)
            assertFalse(
                "a supressão não pode sobreviver ao som que a justifica",
                c.supressor.suprimido(currentTime),
            )

            // Alfa aperta. O piso está livre — ninguém detém o canal aqui.
            c.radio.aoPressionar(rota)
            advanceTimeBy(40)
            val aposOPreRoll = c.transporte.quadrosDeVoz
            advanceTimeBy(460)

            assertTrue("o rádio está transmitindo", c.radio.transmitindo)
            assertTrue(
                "meio segundo de fala AO VIVO de Alfa tem de chegar ao fio, e não " +
                    "esperar o receptor desistir (antes=$aposOPreRoll, " +
                    "depois=${c.transporte.quadrosDeVoz})",
                c.transporte.quadrosDeVoz >= aposOPreRoll + 20,
            )
            c.radio.aoSoltar()
            advanceTimeBy(100)
        }
    }

    /**
     * **O contra-teste: a supressão que DEVE existir continua existindo.**
     *
     * Consertar o defeito 5 largando a supressão da recepção seria trocar fala
     * perdida por eco difundido para a guarnição — os alto-falantes são
     * *open-ear*, e tudo que tocamos volta pelo microfone. O teste exige que,
     * **enquanto os blocos chegam**, a voz ao vivo de Alfa continue sendo
     * descartada.
     */
    @Test
    fun enquantoAVozDoColegaTOCA_aCapturaDeAlfaContinuaDescartada() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.transporte.chega("tx-bravo")
            repeat(6) { c.transporte.quadroDe("tx-bravo", it) }
            advanceTimeBy(60)

            c.radio.aoPressionar(rota)
            advanceTimeBy(40)
            val soPreRoll = c.transporte.quadrosDeVoz

            // Blocos continuam chegando: a fala do colega ainda está no ar.
            repeat(10) {
                c.transporte.quadroDe("tx-bravo", 6 + it)
                advanceTimeBy(20)
            }

            assertEquals(
                "com o alto-falante tocando, o microfone capta a MISTURA e não há " +
                    "como separá-la — difundir isso de volta seria pior que perder",
                soPreRoll,
                c.transporte.quadrosDeVoz,
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
