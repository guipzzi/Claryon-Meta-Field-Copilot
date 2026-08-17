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
        fun fluxo(): Flow<ShortArray> = flow {
            assinaturas.incrementAndGet()
            while (true) {
                delay(20)
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
    )

    /**
     * Monta o rádio num escopo próprio e **sempre** o encerra — sem o `finally`,
     * uma asserção que falha deixa os laços infinitos vivos e o próximo teste
     * herda tarefas no mesmo escalonador.
     */
    private suspend fun TestScope.comRadio(
        // Relógio virtual do escalonador, e não uma constante: `GatilhoPtt` mede
        // repique e `SessaoPtt` mede duração por `agoraMs`. Com um relógio
        // parado em 0, o primeiro toque no PTT é descartado como repique e o
        // teste mede o caminho errado — foi o que aconteceu aqui.
        relogio: () -> Long = { currentTime },
        supressor: SupressorDeSaidaPropria = SupressorDeSaidaPropria(),
        corpo: suspend (Bancada) -> Unit,
    ) {
        val escopo = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val transporte = TransporteFake()
        val saida = SaidaFake()
        val microfone = MicrofoneFake(amostrasPorQuadro)
        val emitidos = mutableListOf<Utterance>()
        val radio = RadioTatico(
            escopo = escopo,
            talkGroupId = "gta-3",
            agenteId = "alfa",
            indicativo = "Alfa Um",
            transporte = transporte,
            codec = CodecFake(),
            piso = ClienteDePisoLocal(),
            pcmDoMicrofone = { microfone.fluxo() },
            abrirFluxoDeSaida = { taxaHz -> saida.abrir(taxaHz) },
            emitir = { u -> emitidos.add(u) },
            duracaoDoEarconMs = { 320L },
            agoraMs = relogio,
            sampleRateHz = taxa,
            supressor = supressor,
        )
        try {
            corpo(Bancada(radio, transporte, saida, microfone, supressor, emitidos))
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
        }
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
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", PrioridadeTransmissao.P2_APOIO)),
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
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", PrioridadeTransmissao.P2_APOIO)),
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
                EventoDeRede.Anuncio(AnuncioDeFala("tx2", "Charlie", PrioridadeTransmissao.P2_APOIO)),
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
                EventoDeRede.Anuncio(AnuncioDeFala("tx1", "Bravo", PrioridadeTransmissao.P2_APOIO)),
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
}
