package com.claryon.field.radio

import com.claryon.agent.Utterance
import com.claryon.audio.FluxoDeReproducao
import com.claryon.audio.rotaDeTeste
import com.claryon.common.ClaryonError
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.net.AnuncioDeFala
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.CodecDeVoz
import com.claryon.net.EventoDeRede
import com.claryon.net.PrioridadeTransmissao
import com.claryon.net.QuadroAudio
import com.claryon.net.SupressorDeSaidaPropria
import com.claryon.net.TelemetriaDoRadio
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * `radio/` é onde viveram os defeitos mais caros do projeto — taxa de amostragem
 * divergente, dois `AudioRecord` concorrentes, reprodução fora de ordem — e não
 * tinha **nenhum** teste. Estes travam o que a Fase 1 consertou.
 *
 * Nada aqui toca hardware: `pcmDoMicrofone` e `abrirFluxoDeSaida` são parâmetros
 * de construtor justamente para que a coordenação entre entrada e saída — que é
 * o que `RadioTatico` de fato faz — seja verificável em JVM.
 *
 * **Só `advanceTimeBy`, nunca `advanceUntilIdle`.** Tanto o laço de reprodução do
 * `Receptor` quanto o microfone falso rodam `while (true) { delay(...) }`: em
 * tempo virtual eles sempre têm mais uma tarefa agendada, então `advanceUntilIdle`
 * gira para sempre e o processo de teste morre com `OutOfMemoryError` — foi
 * exatamente o que aconteceu na primeira versão deste arquivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioTaticoTest {

    private val rota = rotaDeTeste(7)
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
        val entrada = MutableSharedFlow<EventoDeRede>(extraBufferCapacity = 64)

        override suspend fun conectar(talkGroupId: String) = Result.success(Unit)
        override suspend fun anunciar(anuncio: AnuncioDeFala) = Result.success(Unit)
        override suspend fun enviar(quadro: QuadroAudio): Result<Unit> {
            quadros.add(quadro)
            return Result.success(Unit)
        }
        override suspend fun encerrar(transmissaoId: String) = Result.success(Unit)
        override fun eventos(): Flow<EventoDeRede> = entrada
        override fun conectado() = true
        override suspend fun desconectar() = Unit
    }

    /** Registra o que foi escrito e quantos tracks foram abertos/fechados. */
    private class SaidaFake {
        val aberturas = AtomicInteger()
        val fechamentos = AtomicInteger()
        val blocos = mutableListOf<ShortArray>()
        var falharEscrita = false

        fun abrir(@Suppress("UNUSED_PARAMETER") taxaHz: Int): FluxoDeReproducao {
            aberturas.incrementAndGet()
            return object : FluxoDeReproducao {
                override suspend fun escrever(pcm: ShortArray): Result<Unit> {
                    if (falharEscrita) {
                        return Result.failure(ClaryonError.Audio("audio.write_failed", "falha simulada"))
                    }
                    blocos.add(pcm)
                    return Result.success(Unit)
                }
                override fun fechar() {
                    fechamentos.incrementAndGet()
                }
            }
        }
    }

    /**
     * Microfone falso que conta **assinaturas**. `RadioTatico` chama
     * `pcmDoMicrofone` duas vezes — uma para o pré-roll, outra para a captura ao
     * vivo — e é `FonteUnicaDeMicrofone`, atrás deste parâmetro em produção, que
     * garante um `AudioRecord` só. Aqui contamos as assinaturas para provar que a
     * segunda chamada acontece de fato, e portanto que a fonte única é
     * necessária, não decorativa.
     */
    private class MicrofoneFake(private val amostrasPorQuadro: Int) {
        val assinaturas = AtomicInteger()

        /** Quantos blocos a captura AO VIVO entregou (a 2ª assinatura). */
        val blocosEmitidos = AtomicInteger()

        fun fluxo(): Flow<ShortArray> = flow {
            val ehAoVivo = assinaturas.incrementAndGet() == 2
            while (true) {
                delay(20)
                if (ehAoVivo) blocosEmitidos.incrementAndGet()
                emit(ShortArray(amostrasPorQuadro) { 6_000 })
            }
        }
    }

    private class Bancada(
        val radio: RadioTatico,
        val transporte: TransporteFake,
        val saida: SaidaFake,
        val microfone: MicrofoneFake,
        val supressor: SupressorDeSaidaPropria,
        val emitidos: MutableList<Utterance>,
        /** Sequência de nomes que a tela recebeu para "quem fala". */
        val quemFalou: MutableList<String?>,
    )

    /**
     * Monta o rádio num escopo próprio e **sempre** o encerra — sem o `finally`,
     * uma asserção que falha deixa os laços infinitos vivos e o próximo teste
     * herda tarefas no mesmo escalonador.
     */
    private suspend fun TestScope.comRadio(
        taxaDoRadio: Int = taxa,
        // Relógio virtual do escalonador, e não uma constante: `GatilhoPtt` mede
        // repique e `SessaoPtt` mede duração por `agoraMs`. Com um relógio
        // parado em 0, o primeiro toque no PTT é descartado como repique e o
        // teste mede o caminho errado — foi o que aconteceu aqui.
        relogio: () -> Long = { currentTime },
        supressor: SupressorDeSaidaPropria = SupressorDeSaidaPropria(),
        /** Cadastro do grupo. Vazio no padrão: nada resolve, tudo é não confirmado. */
        cadastro: Map<String, String> = emptyMap(),
        /** O que o servidor responde sobre a autoria. `null` = "não sei". */
        autorNoServidor: (String) -> String? = { null },
        corpo: suspend (Bancada) -> Unit,
    ) {
        val escopo = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val transporte = TransporteFake()
        val saida = SaidaFake()
        val microfone = MicrofoneFake(amostrasPorQuadro)
        val emitidos = mutableListOf<Utterance>()
        val quemFalou = mutableListOf<String?>()
        val radio = RadioTatico(
            escopo = escopo,
            talkGroupId = "gta-3",
            agenteId = "alfa",
            indicativo = "Alfa Um",
            resolverAutor = { id -> cadastro[id] },
            conferirAutor = { tx -> autorNoServidor(tx) },
            aoMudarQuemFala = { quemFalou += it },
            transporte = transporte,
            codec = CodecFake(),
            piso = ClienteDePisoLocal(),
            pcmDoMicrofone = { microfone.fluxo() },
            abrirFluxoDeSaida = { taxaHz -> saida.abrir(taxaHz) },
            emitir = { u -> emitidos.add(u) },
            duracaoDoEarconMs = { 320L },
            agoraMs = relogio,
            sampleRateHz = taxaDoRadio,
            supressor = supressor,
        )
        try {
            corpo(Bancada(radio, transporte, saida, microfone, supressor, emitidos, quemFalou))
        } finally {
            escopo.cancel()
        }
    }

    // ── Captura ───────────────────────────────────────────────────────────────

    @Test
    fun oPtt_transmiteQuadros_naTaxaConfigurada() = runTest {
        // O defeito que tornava o produto não-demonstrável: `sampleRateHz` tinha
        // 8_000 como padrão e a captura entregava 16 kHz, então a voz saía uma
        // oitava abaixo com o dobro da duração. Aqui a taxa REAL (16 kHz) é a que
        // dimensiona `amostrasPorQuadro` na sessão.
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(200)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)
            c.radio.aoSoltar()
            advanceTimeBy(100)

            assertTrue("nada foi transmitido", c.transporte.quadros.isNotEmpty())

            assertTrue("o teste precisa de captura para valer", c.microfone.blocosEmitidos.get() > 0)
        }
    }

    @Test
    fun taxaDivergente_dobraAContagemDeQuadros_eEhDetectavel() = runTest {
        // **O teste que faltava, e o que ele prova.** A versão anterior parava
        // em "transmitiu alguma coisa" — e transmitir alguma coisa é o que o
        // código defeituoso TAMBÉM fazia: ele saía a 8 kHz contra um microfone
        // de 16 kHz, uma oitava abaixo, com o dobro da duração. O produto não
        // era demonstrável e a suíte ficava verde.
        //
        // O que distingue os dois casos é a CONTAGEM. `SessaoPtt.fatiar` corta
        // em `sampleRateHz / 50`: a 16 kHz são 320 amostras por quadro e cada
        // bloco do microfone rende UM quadro; a 8 kHz seriam 160, e o MESMO
        // bloco rende DOIS. Rodar a mesma bancada nas duas taxas e comparar é a
        // prova direta — não uma asserção sobre um número mágico.
        suspend fun quadrosCom(taxaDoRadio: Int): Int {
            var n = 0
            comRadio(taxaDoRadio = taxaDoRadio) { c ->
                c.radio.entrarEmModoAtivo(rota)
                advanceTimeBy(200)
                c.radio.aoPressionar(rota)
                advanceTimeBy(300)
                c.radio.aoSoltar()
                advanceTimeBy(100)
                n = c.transporte.quadros.count { !it.ultimo }
            }
            return n
        }

        val certo = quadrosCom(16_000)   // casa com o microfone
        val errado = quadrosCom(8_000)   // o defeito original

        assertTrue("a bancada precisa transmitir para o teste valer", certo > 0)
        assertTrue(
            "taxa divergente tem de dobrar a contagem de quadros (16k=$certo, 8k=$errado)",
            errado >= certo * 2 - 2,
        )
    }

    @Test
    fun oPtt_assinaOMicrofoneUmaSegundaVez_eEhPorIssoQueAFonteUnicaExiste() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)
            assertEquals("o pré-roll assina ao entrar em modo ativo", 1, c.microfone.assinaturas.get())

            c.radio.aoPressionar(rota)
            advanceTimeBy(100)

            // Duas assinaturas simultâneas. Em produção as duas caem na MESMA
            // `FonteUnicaDeMicrofone`, que abre um `AudioRecord` só — ver
            // `FonteUnicaDeMicrofoneTest`. Se este número voltar a 1, a captura
            // ao vivo parou de acontecer e o PTT transmite só o pré-roll.
            assertEquals(2, c.microfone.assinaturas.get())

            c.radio.aoSoltar()
            advanceTimeBy(100)
        }
    }

    // ── Serialização da reprodução ────────────────────────────────────────────

    @Test
    fun recepcao_abreUmUnicoAudioTrack_paraMuitosQuadros() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)

            c.transporte.entrada.emit(
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", prioridade = PrioridadeTransmissao.P2_APOIO)),
            )
            repeat(10) { i ->
                c.transporte.entrada.emit(
                    EventoDeRede.Quadro(QuadroAudio("tx1", i, 0L, ByteArray(30) { 1 })),
                )
            }
            advanceTimeBy(500)

            // O defeito antigo: um `AudioTrack` construído e liberado POR QUADRO
            // de 20 ms — 50 por segundo, em corrotinas concorrentes sem ordem
            // entre si.
            assertEquals("um track por transmissão, não um por quadro", 1, c.saida.aberturas.get())
            assertTrue("nada foi reproduzido", c.saida.blocos.isNotEmpty())
        }
    }

    @Test
    fun aTransmissaoSeguinte_abreTrackNovo_eNaoHerdaACaudaDaAnterior() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)

            c.transporte.entrada.emit(
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", prioridade = PrioridadeTransmissao.P2_APOIO)),
            )
            // ≥ 5 quadros: `BufferDeJitter` só começa a entregar depois de encher
            // a profundidade inicial (100 ms / 20 ms). Com menos que isso ele
            // devolve `Aguardando` e a transmissão nunca chega ao fim.
            repeat(6) { i ->
                c.transporte.entrada.emit(
                    EventoDeRede.Quadro(QuadroAudio("tx1", i, 0L, ByteArray(30) { 1 })),
                )
            }
            advanceTimeBy(200)
            c.transporte.entrada.emit(
                EventoDeRede.Quadro(QuadroAudio("tx1", 6, 0L, ByteArray(0), ultimo = true)),
            )
            advanceTimeBy(400)

            assertEquals("o track precisa fechar no fim da fala", 1, c.saida.fechamentos.get())

            // Se `filaDeSaida` não fosse drenada em `fecharFluxoDeSaida`, um
            // quadro remanescente tocaria na frente desta segunda fala — a voz
            // do locutor anterior com o indicativo errado na tela.
            val blocosAntes = c.saida.blocos.size
            c.transporte.entrada.emit(
                EventoDeRede.Anuncio(AnuncioDeFala("tx2", "Charlie", prioridade = PrioridadeTransmissao.P2_APOIO)),
            )
            repeat(6) { i ->
                c.transporte.entrada.emit(
                    EventoDeRede.Quadro(QuadroAudio("tx2", i, 0L, ByteArray(30) { 2 })),
                )
            }
            advanceTimeBy(300)

            assertEquals("a segunda fala abre um track novo", 2, c.saida.aberturas.get())
            assertTrue("a segunda fala precisa reproduzir", c.saida.blocos.size > blocosAntes)
        }
    }

    @Test
    fun falhaDeEscrita_viraEarcon_umaVez_eNaoSilencio() = runTest {
        comRadio { c ->
            c.saida.falharEscrita = true
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)

            c.transporte.entrada.emit(
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", prioridade = PrioridadeTransmissao.P2_APOIO)),
            )
            repeat(5) { i ->
                c.transporte.entrada.emit(
                    EventoDeRede.Quadro(QuadroAudio("tx1", i, 0L, ByteArray(30) { 1 })),
                )
            }
            advanceTimeBy(300)

            // O `Result` de `escrever` era descartado: rota derrubada no meio da
            // recepção não chegava a earcon nem a log. "Falha nunca é silêncio"
            // vale para o áudio recebido também.
            val falhas = c.emitidos.count { it is Utterance.Sinalizar && it.earcon == Earcon.FALHA }
            assertTrue("falha de escrita precisa virar earcon", falhas >= 1)
            assertEquals("um bipe por transmissão, não um por quadro", 1, falhas)
        }
    }

    // ── Supressor compartilhado ───────────────────────────────────────────────

    @Test
    fun somDeForaDoRadio_suprimeACapturaDoRadio() = runTest {
        // O furo que a Fase 1 fechou: o supressor era campo PRIVADO de
        // `RadioTatico`, então a fala do copiloto — que toca por outra fila — não
        // suprimia nada, entrava no pré-roll e ia ao ar no PTT seguinte. Agora a
        // instância vem de fora (`SaidaUnica.supressor`), e quem produz som
        // registra a janela lá.
        val supressor = SupressorDeSaidaPropria()
        comRadio(supressor = supressor) { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)

            val agora = currentTime
            supressor.registrar(agora, 500)

            assertTrue(
                "quem produz som fora do rádio precisa conseguir suprimir a captura dele",
                supressor.suprimido(agora + 100),
            )
            assertFalse(supressor.suprimido(agora + 5_000))
        }
    }

    // ── Telemetria ────────────────────────────────────────────────────────────

    @Test
    fun aTelemetriaDoRadio_temChamadorReal_eAcumulaAmostras() = runTest {
        comRadio { c ->
            c.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(100)

            c.radio.aoPressionar(rota)
            advanceTimeBy(300)
            c.radio.aoSoltar()
            advanceTimeBy(100)

            // A meta "toque → primeiro quadro ≤ 120 ms" só é verificável se este
            // caminho tiver chamador. Antes da Fase 1, `TelemetriaDoRadio` não
            // tinha nenhum em `src/main`.
            assertTrue(
                "o relatório precisa refletir uma transmissão real",
                c.radio.telemetria.contador(TelemetriaDoRadio.QUADROS_ENVIADOS) > 0,
            )
            assertTrue(c.radio.telemetria.relatorio().contains("toque ate primeiro quadro"))
        }
    }
    // ── Autoria da fala ───────────────────────────────────────────────────────

    /**
     * **O indicativo que aparece na tela vem do cadastro, não do fio.**
     *
     * `autorIndicativo` é string livre: qualquer cliente pode escrever o nome de
     * qualquer pessoa, e o servidor **não pode barrar** — medido em 18/08, a
     * política de `realtime.messages` recebe `payload` nulo, então ela não
     * enxerga o conteúdo do broadcast (ver `DECISIONS.md`). A defesa é resolver
     * no receptor contra `cadastro_do_grupo`, que a RLS já filtrou.
     */
    @Test
    fun oNomeExibidoVemDoCadastroENaoDoQueOEmissorEscreveu() = runTest {
        comRadio(cadastro = mapOf("agente-real" to "Alfa Dois")) { b ->
            // Sem modo ativo o rádio não coleta eventos do transporte, e o teste
            // mediria o silêncio em vez da resolução.
            b.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)
            b.transporte.entrada.emit(
                EventoDeRede.Anuncio(
                    AnuncioDeFala(
                        transmissaoId = "tx-1",
                        autorIndicativo = "NOME QUE O EMISSOR INVENTOU",
                        autorAgenteId = "agente-real",
                        prioridade = PrioridadeTransmissao.P2_APOIO,
                    ),
                ),
            )
            advanceTimeBy(200)
            assertEquals(listOf("Alfa Dois"), b.quemFalou)
        }
    }

    /**
     * **O contra-teste, e é ele que fecha a personificação.**
     *
     * Id que não está no cadastro **não** cai de volta na string livre. Exibir o
     * rótulo que o próprio forjador digitou é pior que não exibir nada, porque dá
     * autoridade à mentira: o colega leria "Alfa Um" com o P1 de outra pessoa.
     *
     * O áudio continua tocando — calar uma voz que pode ser pedido de apoio real
     * seria a falha oposta, e mais cara.
     */
    @Test
    fun idForaDoCadastroNaoViraONomeQueOEmissorEscreveu() = runTest {
        comRadio(cadastro = mapOf("agente-real" to "Alfa Dois")) { b ->
            // Sem modo ativo o rádio não coleta eventos do transporte, e o teste
            // mediria o silêncio em vez da resolução.
            b.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)
            b.transporte.entrada.emit(
                EventoDeRede.Anuncio(
                    AnuncioDeFala(
                        transmissaoId = "tx-2",
                        autorIndicativo = "Alfa Um",
                        autorAgenteId = "agente-forjado",
                        prioridade = PrioridadeTransmissao.P1_EMERGENCIA,
                    ),
                ),
            )
            advanceTimeBy(200)
            assertTrue(
                "a tela recebeu o indicativo que o emissor escreveu: $b.quemFalou",
                b.quemFalou.none { it == "Alfa Um" },
            )
            assertEquals(listOf("Origem não confirmada"), b.quemFalou)
        }
    }


    /**
     * **O que fecha a personificação entre colegas do mesmo grupo.**
     *
     * O cadastro sozinho não fechava: um membro pode escrever o `agentId` de outro
     * membro, que **está** no cadastro e resolve. A prova que o payload não dá vem
     * de `floor_grants`, onde `pedir_canal` carimba o autor a partir do JWT —
     * ninguém obtém piso em nome de terceiro.
     */
    @Test
    fun autoriaDivergenteDoPisoDerrubaONome() = runTest {
        comRadio(
            cadastro = mapOf("colega" to "Alfa Dois", "impostor" to "Bravo Um"),
            autorNoServidor = { "impostor" },
        ) { b ->
            b.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)
            b.transporte.entrada.emit(
                EventoDeRede.Anuncio(
                    AnuncioDeFala(
                        transmissaoId = "tx-3",
                        autorIndicativo = "Alfa Dois",
                        autorAgenteId = "colega", // está no cadastro, mas não tem o piso
                        prioridade = PrioridadeTransmissao.P1_EMERGENCIA,
                    ),
                ),
            )
            advanceTimeBy(300)
            assertEquals(
                "o nome do colega ficou de pé mesmo com o piso sendo de outro",
                "Origem não confirmada",
                b.quemFalou.last(),
            )
        }
    }

    /**
     * **Rede caída não pode transformar toda fala em suspeita.**
     *
     * "Não sei" é diferente de "divergiu". Se a conferência falhar por rede — e é
     * exatamente na queda que o rádio mais importa — o rótulo resolvido pelo
     * cadastro fica de pé. Só a divergência derruba.
     */
    @Test
    fun servidorSemRespostaNaoDerrubaONomeResolvido() = runTest {
        comRadio(
            cadastro = mapOf("colega" to "Alfa Dois"),
            autorNoServidor = { null },
        ) { b ->
            b.radio.entrarEmModoAtivo(rota)
            advanceTimeBy(50)
            b.transporte.entrada.emit(
                EventoDeRede.Anuncio(
                    AnuncioDeFala("tx-4", "qualquer coisa", "colega", PrioridadeTransmissao.P2_APOIO),
                ),
            )
            advanceTimeBy(300)
            assertEquals("Alfa Dois", b.quemFalou.last())
        }
    }


}
