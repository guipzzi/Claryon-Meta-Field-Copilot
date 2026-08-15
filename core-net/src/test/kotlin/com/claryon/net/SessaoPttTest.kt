package com.claryon.net

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cenários adversos do push-to-talk. O caminho feliz é o menos interessante aqui:
 * o que decide se isto é rádio ou brinquedo é o que acontece quando o canal está
 * ocupado, a rede cai no meio, o botão fica preso ou uma emergência entra.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class SessaoPttTest {

    private val sr = 8_000
    private val amostrasPorQuadro = sr / 50 // 20 ms

    // ── Dublês ────────────────────────────────────────────────────────────────

    private class CodecFake(var falhar: Boolean = false) : CodecDeVoz {
        override suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>> =
            if (falhar) Result.failure(ClaryonError.Unexpected("codec", "falha simulada"))
            else Result.success(listOf(ByteArray(pcm.size / 8) { 1 }))

        override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> =
            Result.success(ShortArray(160))

        override val taxaDeSaidaHz = 24_000
        override fun liberar() = Unit
    }

    private class TransporteFake(
        var falharEnvio: Boolean = false,
        val log: MutableList<String> = mutableListOf(),
    ) : TransporteAoVivo {
        val quadros = mutableListOf<QuadroAudio>()
        var anuncios = 0
        var encerramentos = 0

        override suspend fun conectar(talkGroupId: String) = Result.success(Unit)
        override suspend fun anunciar(anuncio: AnuncioDeFala): Result<Unit> {
            anuncios++; log.add("anuncio"); return Result.success(Unit)
        }

        override suspend fun enviar(quadro: QuadroAudio): Result<Unit> {
            if (falharEnvio && !quadro.ultimo) {
                return Result.failure(ClaryonError.Sync("net", "sem rede"))
            }
            quadros.add(quadro)
            return Result.success(Unit)
        }

        override suspend fun encerrar(transmissaoId: String): Result<Unit> {
            encerramentos++; log.add("encerrar"); return Result.success(Unit)
        }

        override fun eventos(): Flow<EventoDeRede> = flowOf()
        override fun conectado() = true
        override suspend fun desconectar() = Unit
    }

    private class PisoFake(
        private val resultado: (String) -> ResultadoDoPedido,
        val log: MutableList<String>,
        var renovaOk: Boolean = true,
        private val atrasoMs: Long = 0,
    ) : ClienteDePiso {
        var liberado = false

        override suspend fun pedir(
            talkGroupId: String,
            agenteId: String,
            transmissaoId: String,
            prioridade: PrioridadeTransmissao,
        ): ResultadoDoPedido {
            log.add("pedir")
            if (atrasoMs > 0) kotlinx.coroutines.delay(atrasoMs)
            return resultado(transmissaoId)
        }

        override suspend fun renovar(concessao: Concessao) = renovaOk
        override suspend fun liberar(concessao: Concessao) { liberado = true }
    }

    private fun concessao(tx: String) = Concessao(
        "gta-3", "alfa", tx, PrioridadeTransmissao.P2_APOIO, expiraEmMs = 30_000,
    )

    private fun concede(log: MutableList<String>, atrasoMs: Long = 0) =
        PisoFake({ ResultadoDoPedido.Concedido(concessao(it)) }, log, atrasoMs = atrasoMs)

    private fun preRollCom(quadrosDeFala: Int): PreRollBuffer {
        val b = PreRollBuffer(sr, 600, amostrasPorQuadro)
        repeat(quadrosDeFala) { b.escrever(ShortArray(amostrasPorQuadro) { 4000 }) }
        return b
    }

    private fun sessao(
        preRoll: PreRollBuffer,
        codec: CodecDeVoz,
        transporte: TransporteAoVivo,
        piso: ClienteDePiso,
        relogio: () -> Long = { 0L },
        duracaoMaximaMs: Long = 30_000,
    ) = SessaoPtt(
        talkGroupId = "gta-3",
        agenteId = "alfa",
        preRoll = preRoll,
        codec = codec,
        transporte = transporte,
        piso = piso,
        agoraMs = relogio,
        amostrasPorQuadro = amostrasPorQuadro,
        duracaoMaximaMs = duracaoMaximaMs,
    )

    private fun blocosDeFala(n: Int) = flow {
        repeat(n) { emit(ShortArray(amostrasPorQuadro) { 3000 }) }
    }

    // ── Ordem e não-bloqueio ──────────────────────────────────────────────────

    @Test
    fun capturandoEhSinalizadoAntesDeQualquerRede() = runTest {
        val log = mutableListOf<String>()
        val eventos = mutableListOf<String>()
        val s = sessao(preRollCom(0), CodecFake(), TransporteFake(log = log), concede(log))

        s.transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa Dois", blocosDeFala(2)) {
            eventos.add(it::class.simpleName!!)
            log.add("evento:${it::class.simpleName}")
        }

        // O agente sabe que está capturando antes de o servidor responder.
        assertEquals("Capturando", eventos.first())
        assertTrue(log.indexOf("evento:Capturando") < log.indexOf("pedir"))
    }

    @Test
    fun falaDuranteAEsperaDoCanal_naoSePerde() = runTest {
        // A concessão demora 150 ms. Nesse intervalo o agente já está falando —
        // e o pré-roll, alimentado continuamente em modo Ativo, guarda tudo.
        val log = mutableListOf<String>()
        val preRoll = preRollCom(0)
        val transporte = TransporteFake(log = log)
        val piso = concede(log, atrasoMs = 150)
        val s = sessao(preRoll, CodecFake(), transporte, piso)

        val job = launch {
            s.transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(3)) {}
        }
        // Enquanto a rede pensa, a captura segue enchendo o pré-roll.
        repeat(7) { preRoll.escrever(ShortArray(amostrasPorQuadro) { 4000 }) }
        advanceUntilIdle()
        job.join()

        assertTrue(
            "o áudio capturado durante a espera deveria ter sido transmitido",
            transporte.quadros.count { !it.ultimo } >= 7,
        )
    }

    @Test
    fun preRollSaiAntesDoAoVivo_eASequenciaEhContinua() = runTest {
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val s = sessao(preRollCom(5), CodecFake(), transporte, concede(log))

        s.transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(4)) {}

        val seqs = transporte.quadros.filter { !it.ultimo }.map { it.sequencia }
        assertEquals("sequência tem de começar em 0 e ser contígua", (0 until seqs.size).toList(), seqs)
        assertEquals("5 do pré-roll + 4 ao vivo", 9, seqs.size)
    }

    @Test
    fun anuncioSaiAntesDoPrimeiroQuadro() = runTest {
        // O receptor aquece o AudioTrack enquanto o 1º quadro é codificado.
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val s = sessao(preRollCom(2), CodecFake(), transporte, concede(log))

        s.transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(1)) {}
        assertEquals(1, transporte.anuncios)
    }

    // ── Canal ocupado ─────────────────────────────────────────────────────────

    @Test
    fun canalOcupado_naoTransmiteNadaELimpaOPreRoll() = runTest {
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val preRoll = preRollCom(10)
        val piso = PisoFake({ ResultadoDoPedido.Ocupado(concessao("outra").copy(agenteId = "bravo")) }, log)
        val eventos = mutableListOf<EventoPtt>()

        sessao(preRoll, CodecFake(), transporte, piso)
            .transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(5)) { eventos.add(it) }

        assertTrue(eventos.any { it is EventoPtt.CanalOcupado })
        assertTrue("nada pode ir para a rede", transporte.quadros.isEmpty())
        assertEquals("o capturado não pode vazar na próxima transmissão", 0, preRoll.tamanho)
    }

    // ── Rede caindo ───────────────────────────────────────────────────────────

    @Test
    fun redeFalhandoNoMeio_naoInterrompeACaptura() = runTest {
        // Rede lenta atrasa a entrega; jamais perde fala. A sessão precisa
        // sobreviver e contar o que não saiu.
        val log = mutableListOf<String>()
        val transporte = TransporteFake(falharEnvio = true, log = log)
        val eventos = mutableListOf<EventoPtt>()

        sessao(preRollCom(3), CodecFake(), transporte, concede(log))
            .transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(6)) { eventos.add(it) }

        val naoEntregues = eventos.filterIsInstance<EventoPtt.QuadrosNaoEntregues>().firstOrNull()
        assertTrue("a perda tem de ser reportada", naoEntregues != null)
        assertEquals(9, naoEntregues!!.quantidade)
        assertTrue("a sessão tem de encerrar normalmente", eventos.any { it is EventoPtt.Encerrada })
    }

    @Test
    fun codecFalhando_naoDerrubaASessao() = runTest {
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val eventos = mutableListOf<EventoPtt>()

        sessao(preRollCom(2), CodecFake(falhar = true), transporte, concede(log))
            .transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(3)) { eventos.add(it) }

        assertTrue(eventos.any { it is EventoPtt.Encerrada })
    }

    // ── Soltar o PTT ──────────────────────────────────────────────────────────

    @Test
    fun soltarOPtt_aindaEnviaOUltimoQuadro() = runTest {
        // Soltar o PTT é cancelamento. Sem tratamento, a chamada suspensa do
        // encerramento falharia e o RECEPTOR ESPERARIA PARA SEMPRE por uma fala
        // que já acabou — o modo de falha mais confuso possível num rádio.
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val piso = concede(log)
        val pcm = MutableSharedFlow<ShortArray>(replay = 0, extraBufferCapacity = 16)

        val job = launch {
            sessao(preRollCom(2), CodecFake(), transporte, piso)
                .transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", pcm) {}
        }
        advanceUntilIdle()
        pcm.emit(ShortArray(amostrasPorQuadro) { 3000 })
        advanceUntilIdle()

        job.cancel() // ← o agente soltou o botão
        job.join()
        advanceUntilIdle()

        assertTrue("o último quadro tem de sair", transporte.quadros.any { it.ultimo })
        assertEquals("o fim de transmissão tem de ser sinalizado", 1, transporte.encerramentos)
        assertTrue("o canal tem de voltar ao grupo", piso.liberado)
    }

    // ── Botão preso ───────────────────────────────────────────────────────────

    @Test
    fun botaoPreso_cortaNoTetoDeDuracao() = runTest {
        // Sem isto, um botão preso no cinto viraria captação contínua — que é
        // exatamente o que a política de privacidade proíbe.
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        var t = 0L
        val eventos = mutableListOf<EventoPtt>()

        sessao(
            preRollCom(0), CodecFake(), transporte, concede(log),
            relogio = { t }, duracaoMaximaMs = 200,
        ).transmitir(
            "tx1", PrioridadeTransmissao.P2_APOIO, "Alfa",
            flow { repeat(100) { t += 20; emit(ShortArray(amostrasPorQuadro) { 3000 }) } },
        ) { eventos.add(it) }

        assertTrue("deveria cortar no teto", eventos.any { it is EventoPtt.LimiteDeDuracao })
        assertTrue("não pode transmitir os 100 blocos", transporte.quadros.size < 30)
    }

    // ── Emergência toma o canal ───────────────────────────────────────────────

    @Test
    fun canalTomadoPorEmergencia_paraDeTransmitir() = runTest {
        // O agente interrompido precisa saber: continuar falando para o vazio é
        // pior que ser cortado.
        val log = mutableListOf<String>()
        val transporte = TransporteFake(log = log)
        val piso = concede(log).also { it.renovaOk = false }
        var t = 0L
        val eventos = mutableListOf<EventoPtt>()

        sessao(preRollCom(0), CodecFake(), transporte, piso, relogio = { t })
            .transmitir(
                "tx1", PrioridadeTransmissao.P2_APOIO, "Alfa",
                flow { repeat(50) { t += 1_000; emit(ShortArray(amostrasPorQuadro) { 3000 }) } },
            ) { eventos.add(it) }

        assertTrue(eventos.any { it is EventoPtt.CanalPerdido })
        assertTrue("não pode seguir transmitindo depois de perder o piso", transporte.quadros.size < 20)
    }

    // ── Privacidade ───────────────────────────────────────────────────────────

    @Test
    fun aoFim_oPreRollFicaVazio() = runTest {
        val log = mutableListOf<String>()
        val preRoll = preRollCom(10)

        sessao(preRoll, CodecFake(), TransporteFake(log = log), concede(log))
            .transmitir("tx1", PrioridadeTransmissao.P2_APOIO, "Alfa", blocosDeFala(2)) {}

        assertEquals("áudio não sobrevive ao momento de uso", 0, preRoll.tamanho)
        assertFalse(preRoll.desdeOInicioDaFala().any { it.toInt() != 0 })
    }
}
