package com.claryon.net

import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duas notas de harness, ambas custaram tempo:
 *
 *  1. O escopo passado ao [Receptor] é o do próprio `runTest`, **não** o
 *     `backgroundScope`. Nesta versão do `kotlinx-coroutines-test`, corrotinas do
 *     `backgroundScope` não são acordadas por `advanceUntilIdle()` — medido:
 *     `subscriptionCount` ficava em zero e nenhum evento chegava. Usar o escopo do
 *     teste só é seguro porque o laço de reprodução **encerra sozinho**; com um
 *     laço infinito, `runTest` nunca terminaria.
 *  2. Depois de `iniciar`, é preciso `advanceUntilIdle()` antes de emitir:
 *     `MutableSharedFlow` com `replay = 0` descarta o que é emitido sem assinante.
 *  3. O escopo é **filho do agendador, não do teste**: o laço de coleta é
 *     infinito por natureza (um rádio fica escutando), e como filho do `runTest`
 *     ele seguraria o teste para sempre. `escopoDoReceptor.cancel()` ao fim de
 *     cada cenário é o que encerra.
 *
 * Cenários do lado receptor. O caminho feliz importa menos que o adverso: um
 * rádio que trava esperando um quadro que nunca vem é pior que um rádio ruidoso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceptorTest {

    private val taxaSaida = 24_000
    private val amostrasSaida = taxaSaida * 20 / 1000 // 480

    private class CodecFalso(
        var falharDecode: Boolean = false,
        override val taxaDeSaidaHz: Int = 24_000,
    ) : CodecDeVoz {
        var plcs = 0
        override suspend fun codificar(pcm: ShortArray) = Result.success(listOf(ByteArray(4)))
        override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> {
            if (falharDecode) return Result.failure(ClaryonError.Unexpected("x", "falha"))
            if (payload == null) plcs++
            return Result.success(ShortArray(480) { if (payload == null) 100 else 3000 })
        }
        override fun liberar() = Unit
    }

    /**
     * O fluxo se chama `fluxo`, e não `eventos`: uma propriedade com o mesmo nome
     * do método sobrescrito torna `= eventos` ambíguo para quem lê, e é o tipo de
     * detalhe que faz um dublê mentir sem que ninguém perceba.
     */
    private class TransporteFalso(
        val fluxo: MutableSharedFlow<EventoDeRede> = MutableSharedFlow(extraBufferCapacity = 128),
    ) : TransporteAoVivo {
        override suspend fun conectar(talkGroupId: String) = Result.success(Unit)
        override suspend fun anunciar(anuncio: AnuncioDeFala) = Result.success(Unit)
        override suspend fun enviar(quadro: QuadroAudio) = Result.success(Unit)
        override suspend fun encerrar(transmissaoId: String) = Result.success(Unit)
        override fun eventos(): Flow<EventoDeRede> = fluxo
        override fun conectado() = true
        override suspend fun desconectar() = Unit
    }

    /**
     * Escopo no mesmo agendador virtual do teste, mas fora da árvore do
     * `runTest` — o coletor do receptor nunca termina sozinho.
     */
    private fun TestScope.receptorEm(
        t: TransporteFalso,
        codec: CodecDeVoz,
        aoEvento: suspend (EventoRecepcao) -> Unit,
    ): Pair<Receptor, CoroutineScope> {
        val escopo = CoroutineScope(StandardTestDispatcher(testScheduler))
        val r = Receptor(t, codec, escopo, jitter = BufferDeJitter(inicialMs = 60))
        r.iniciar(aoEvento)
        advanceUntilIdle()
        return r to escopo
    }

    private fun quadro(seq: Int, tx: String = "t1", ultimo: Boolean = false) =
        QuadroAudio(tx, seq, seq * 20L, byteArrayOf(1, 2, 3), ultimo)

    private fun anuncio(tx: String = "t1", p: PrioridadeTransmissao = PrioridadeTransmissao.P2_APOIO) =
        EventoDeRede.Anuncio(AnuncioDeFala(tx, "Alfa Dois", prioridade = p))

    // ── Caminho normal ────────────────────────────────────────────────────────

    @Test
    fun anuncioAbreARecepcao_eOAudioSaiNaTaxaDoDecodificador() = runTest {
        // A taxa de saída (24 kHz) NÃO é a de entrada (8 kHz). Se o receptor
        // assumisse a de entrada, a voz sairia 3x mais grave e 3x mais lenta.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio())
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        advanceUntilIdle()

        assertTrue("faltou o aviso de fala chegando", eventos.any { it is EventoRecepcao.Chegando })
        val audios = eventos.filterIsInstance<EventoRecepcao.Audio>()
        assertTrue("nenhum áudio produzido", audios.isNotEmpty())
        assertEquals("a taxa informada tem de ser a do decodificador", taxaSaida, audios.first().taxaHz)
        assertEquals(amostrasSaida, audios.first().pcm.size)

        esc.cancel()
    }

    @Test
    fun ultimoQuadro_encerraEInformaAContagem() = runTest {
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio())
        repeat(3) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        t.fluxo.emit(EventoDeRede.Quadro(quadro(3, ultimo = true)))
        advanceUntilIdle()

        val fim = eventos.filterIsInstance<EventoRecepcao.Terminou>().firstOrNull()
        assertTrue("a transmissão não encerrou", fim != null)
        assertEquals("t1", fim!!.transmissaoId)
        assertTrue("deveria ter tocado quadros", fim.quadros > 0)

        esc.cancel()
    }

    // ── Cenários prováveis ────────────────────────────────────────────────────

    @Test
    fun quadroSemAnuncio_aindaTocaEmVezDeFicarMudo() = runTest {
        // O anúncio se perde: é uma mensagem só, e a rede não garante nada.
        // Ficar mudo esperando por ele desperdiçaria a fala inteira.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        advanceUntilIdle()

        assertTrue("sem anúncio o rádio ficou mudo", eventos.any { it is EventoRecepcao.Audio })

        esc.cancel()
    }

    @Test
    fun quadroFaltando_viraPlc_naoSilencio() = runTest {
        val t = TransporteFalso()
        val codec = CodecFalso()
        val (_r2, esc) = receptorEm(t, codec) { }

        t.fluxo.emit(anuncio())
        // Faltam os ímpares.
        for (i in listOf(0, 2, 4, 6, 8)) t.fluxo.emit(EventoDeRede.Quadro(quadro(i)))
        advanceUntilIdle()

        assertTrue("o codec nunca foi chamado para ocultar perda", codec.plcs > 0)

        esc.cancel()
    }

    @Test
    fun perdaAlta_avisaUmaVezSo() = runTest {
        // O agente precisa saber que o canal está ruim — mas um aviso por quadro
        // perdido viraria metralhadora de earcon no meio da ocorrência.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio())
        for (i in 0 until 90 step 3) t.fluxo.emit(EventoDeRede.Quadro(quadro(i)))
        advanceTimeBy(2_500)

        val avisos = eventos.count { it is EventoRecepcao.CanalDegradado }
        assertTrue("deveria avisar degradação", avisos >= 1)
        assertEquals("avisou mais de uma vez", 1, avisos)

        esc.cancel()
    }

    @Test
    fun novaTransmissao_naoMisturaComAAnterior() = runTest {
        // Duas falas seguidas com numeração reiniciada: sem reiniciar o buffer, os
        // quadros da segunda seriam vistos como atrasados e descartados.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio("t1"))
        repeat(3) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t1"))) }
        t.fluxo.emit(EventoDeRede.Quadro(quadro(3, "t1", ultimo = true)))
        advanceUntilIdle()

        val antes = eventos.count { it is EventoRecepcao.Audio }

        t.fluxo.emit(anuncio("t2"))
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t2"))) }
        advanceUntilIdle()

        assertTrue("a segunda transmissão não tocou", eventos.count { it is EventoRecepcao.Audio } > antes)

        esc.cancel()
    }

    // ── Cenários improváveis ──────────────────────────────────────────────────

    @Test
    fun decodificadorFalhando_naoDerrubaARecepcao() = runTest {
        // Se o codec morrer, o receptor não pode morrer junto: a próxima
        // transmissão ainda precisa de alguém escutando o canal.
        val t = TransporteFalso()
        val codec = CodecFalso(falharDecode = true)
        val eventos = mutableListOf<EventoRecepcao>()
        val (r, esc) = receptorEm(t, codec) { eventos.add(it) }

        t.fluxo.emit(anuncio())
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        advanceUntilIdle()

        assertTrue("nenhum áudio, o que é esperado com o codec falhando",
            eventos.none { it is EventoRecepcao.Audio })

        // E o receptor continua vivo para a próxima.
        codec.falharDecode = false
        t.fluxo.emit(anuncio("t2"))
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t2"))) }
        advanceUntilIdle()
        assertTrue("o receptor morreu junto com o codec", eventos.any { it is EventoRecepcao.Audio })

        esc.cancel()
    }

    @Test
    fun transmissaoQueNuncaTermina_naoTravaOReceptor() = runTest {
        // O emissor sumiu no meio (bateria, túnel). Sem `ultimo`, o receptor
        // aguarda — mas não pode ficar preso: a próxima fala tem de tocar.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio("t1"))
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t1"))) }
        advanceUntilIdle()
        val antes = eventos.count { it is EventoRecepcao.Audio }

        // Nova fala chega sem a anterior ter encerrado.
        t.fluxo.emit(anuncio("t2"))
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t2"))) }
        advanceUntilIdle()

        assertTrue("a fala nova não tocou porque a anterior travou",
            eventos.count { it is EventoRecepcao.Audio } > antes)

        esc.cancel()
    }

    @Test
    fun rajadaFora_deOrdemEDuplicada_naoQuebra() = runTest {
        // Rede móvel entrega fora de ordem e às vezes duplica.
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio())
        for (i in listOf(3, 1, 0, 2, 2, 5, 4, 1)) t.fluxo.emit(EventoDeRede.Quadro(quadro(i)))
        advanceUntilIdle()

        assertTrue("o receptor não produziu áudio", eventos.any { it is EventoRecepcao.Audio })

        esc.cancel()
    }

    @Test
    fun parar_encerraTudo_eIniciarDeNovoFunciona() = runTest {
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }
        t.fluxo.emit(anuncio())
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        advanceUntilIdle()
        r.parar()

        val depoisDeParar = eventos.size
        t.fluxo.emit(EventoDeRede.Quadro(quadro(9)))
        advanceUntilIdle()
        assertEquals("continuou processando depois de parar", depoisDeParar, eventos.size)

        r.iniciar { eventos.add(it) }

        advanceUntilIdle()
        t.fluxo.emit(anuncio("t3"))
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it, "t3"))) }
        advanceUntilIdle()
        assertTrue("não retomou depois de parar", eventos.size > depoisDeParar)

        esc.cancel()
    }

    @Test
    fun iniciarDuasVezes_naoDuplicaAReproducao() = runTest {
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }
        r.iniciar { eventos.add(it) } // toque duplo no botão de ouvir

        t.fluxo.emit(anuncio())
        repeat(6) { t.fluxo.emit(EventoDeRede.Quadro(quadro(it))) }
        advanceUntilIdle()

        val chegando = eventos.count { it is EventoRecepcao.Chegando }
        assertEquals("o anúncio foi processado em duplicidade", 1, chegando)

        esc.cancel()
    }

    @Test
    fun emergenciaChegando_ehSinalizadaComoTal() = runTest {
        val t = TransporteFalso()
        val eventos = mutableListOf<EventoRecepcao>()
        val (_r, esc) = receptorEm(t, CodecFalso()) { eventos.add(it) }

        t.fluxo.emit(anuncio("t1", PrioridadeTransmissao.P1_EMERGENCIA))
        advanceUntilIdle()

        val chegando = eventos.filterIsInstance<EventoRecepcao.Chegando>().firstOrNull()
        assertTrue(chegando != null)
        assertEquals(PrioridadeTransmissao.P1_EMERGENCIA, chegando!!.anuncio.prioridade)

        esc.cancel()
    }
}
