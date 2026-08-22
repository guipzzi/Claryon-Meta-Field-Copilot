package com.claryon.net

import com.claryon.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O outro lado do rádio: o que B, C e D de fato ouvem.**
 *
 * `ReceptorTest` cobre o receptor sozinho. Aqui ele entra num grupo com N pares e
 * a rede é maltratada no meio: cai do lado de quem fala, cai do lado de quem ouve,
 * alguém entra no meio da fala, o relógio de um aparelho está errado.
 *
 * Cada teste registra o comportamento **observado**, e vários deles travam um
 * número que hoje é ruim — 2 s até o receptor perceber que a fala foi cortada, por
 * exemplo. Número ruim travado é dívida visível; adjetivo é dívida escondida.
 *
 * **Só `advanceTimeBy`.** O laço de reprodução do [Receptor] roda por relógio, e
 * as esperas até desistir são 100 × 20 ms: o tempo virtual precisa ser conduzido à
 * mão para que o teste meça a espera em vez de atravessá-la sem ver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaosDeRecepcaoComNParesTest {

    private val gta = "gta-3"

    /**
     * PCM que carrega a **identidade do quadro**: a primeira amostra é o byte do
     * payload, que os testes usam como número de sequência.
     *
     * Sem isso, "o receptor tocou 12 quadros" não distingue tocar na ordem certa de
     * tocar embaralhado — e a ordem é justamente o que o buffer de jitter promete.
     */
    private class CodecComCarimbo : CodecDeVoz {
        override suspend fun codificar(pcm: ShortArray): Result<List<ByteArray>> =
            Result.success(listOf(ByteArray(1) { 0 }))

        override suspend fun decodificar(payload: ByteArray?): Result<ShortArray> {
            val marca: Short = if (payload == null || payload.isEmpty()) PLC else payload[0].toShort()
            return Result.success(ShortArray(160) { marca })
        }

        override val taxaDeSaidaHz = 24_000
        override fun liberar() = Unit

        companion object {
            /** Marca do quadro reconstruído por PLC — nunca é um número de sequência. */
            const val PLC: Short = -1
        }
    }

    /** O que a camada de cima recebeu, na ordem. */
    private class Escuta {
        val eventos = mutableListOf<EventoRecepcao>()

        val chegando get() = eventos.filterIsInstance<EventoRecepcao.Chegando>()
        val terminou get() = eventos.filterIsInstance<EventoRecepcao.Terminou>()

        /** A sequência de marcas tocadas — a ORDEM em que a voz saiu do alto-falante. */
        val tocados: List<Short>
            get() = eventos.filterIsInstance<EventoRecepcao.Audio>().map { it.pcm.first() }

        val reproduzidos get() = tocados.count { it != CodecComCarimbo.PLC }
        val interpolados get() = tocados.count { it == CodecComCarimbo.PLC }
    }

    private fun TestScope.escutar(par: ParDeRadio, escopo: CoroutineScope): Escuta {
        val escuta = Escuta()
        Receptor(
            transporte = par,
            codec = CodecComCarimbo(),
            escopo = escopo,
            agoraMs = { currentTime },
        ).iniciar { escuta.eventos += it }
        return escuta
    }

    private fun escopoDeTeste(scope: TestScope) =
        CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler) + Job())

    private fun quadro(tx: String, seq: Int, t: Long = 0L, ultimo: Boolean = false) =
        QuadroAudio(tx, seq, t, ByteArray(1) { seq.toByte() }, ultimo)

    private fun anuncio(tx: String, autor: String = "alfa") = AnuncioDeFala(
        transmissaoId = tx,
        autorIndicativo = autor,
        autorAgenteId = autor,
        prioridade = PrioridadeTransmissao.P2_APOIO,
    )

    // ── 1. A rede cai do lado de quem FALA ────────────────────────────────────

    /**
     * **Alfa entra num túnel no meio da frase.**
     *
     * Bravo ouve a metade que chegou, e depois **2 000 ms de nada** antes de o
     * receptor concluir que acabou. Os 2 s são tolerância de jitter e continuam
     * sendo: cortar antes descartaria fala boa que atrasou na rede.
     *
     * O que mudou em 22/08 é o desfecho. O evento era [EventoRecepcao.Terminou]
     * com os mesmos campos de uma fala encerrada normalmente, e quem ouviu não
     * tinha como saber que perdeu o final — repare que `perdidos` vem **zero**
     * justamente aqui, porque o receptor não sabe contar quadros que nunca
     * existiram. Agora o `motivo` diz.
     */
    @Test
    fun aRedeDeAlfaCaiNoMeioDaFala_bravoOuveAMetade_eODesfechoDizQueFoiCorte() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            repeat(10) { alfa.enviar(quadro("tx-1", it)) }
            advanceTimeBy(400) // tempo de tocar os 10
            alfa.rede = false // túnel: nada mais sai, e o `ultimo` nunca virá
            repeat(10) { alfa.enviar(quadro("tx-1", 10 + it, ultimo = it == 9)) }

            val quandoCortou = currentTime
            assertEquals("Bravo ouviu só o que chegou", 10, escuta.reproduzidos)
            assertTrue("e não sabe ainda que acabou", escuta.terminou.isEmpty())

            advanceTimeBy(2_100)
            val fim = escuta.terminou.firstOrNull()
            assertNotNull("o receptor tem de desistir em algum momento", fim)
            assertEquals("tx-1", fim!!.transmissaoId)
            assertEquals(10, fim.quadros)
            assertEquals(
                "zero quadros contados como PERDIDOS numa fala cortada ao meio — o " +
                    "receptor não sabe contar o que nunca existiu, e é por isso que " +
                    "o corte precisa de campo próprio",
                0,
                fim.perdidos,
            )
            assertEquals(
                "o corte tem de aparecer no desfecho: quem ouviu precisa saber que o " +
                    "final não veio antes de decidir uma abordagem com base nele",
                FimDaFala.CORTADA_NO_MEIO,
                fim.motivo,
            )
            assertTrue(
                "e são 2 s de tolerância de jitter até concluir — número declarado, " +
                    "não indecisão (${escuta.eventos.size} eventos, corte em $quandoCortou)",
                currentTime - quandoCortou >= 2_000,
            )
        } finally {
            escopo.cancel()
        }
    }

    /**
     * **O contra-teste do conserto, e ele é uma DESIGUALDADE.**
     *
     * As duas corridas mudam uma coisa só: se a rede do emissor cai antes do
     * último quadro. Antes de 22/08 os dois [EventoRecepcao.Terminou] eram
     * **iguais campo a campo**, e o teste que existia aqui travava essa igualdade
     * como achado.
     *
     * Agora ele exige que difiram — e que difiram **no motivo**, não por acidente
     * de contagem: `quadros` é o mesmo nos dois, e é justamente por isso que a
     * contagem nunca serviu para distinguir. Se alguém remover o campo, ou passar
     * a preenchê-lo com um valor fixo, este teste cai.
     */
    @Test
    fun oFimNormalEOFimPorRedeCaida_saoDISTINGUIVEISPeloMotivo() = runTest {
        suspend fun desfechoCom(corteNoMeio: Boolean): EventoRecepcao.Terminou {
            val escopo = escopoDeTeste(this)
            val barramento = BarramentoTatico()
            val alfa = ParDeRadio("alfa", barramento)
            val bravo = ParDeRadio("bravo", barramento)
            val escuta = escutar(bravo, escopo)
            try {
                alfa.anunciar(anuncio("tx"))
                repeat(10) { alfa.enviar(quadro("tx", it, ultimo = !corteNoMeio && it == 9)) }
                advanceTimeBy(400)
                if (corteNoMeio) {
                    alfa.rede = false
                    repeat(10) { alfa.enviar(quadro("tx", 10 + it, ultimo = it == 9)) }
                    advanceTimeBy(2_100)
                }
                return escuta.terminou.single()
            } finally {
                escopo.cancel()
            }
        }

        val normal = desfechoCom(corteNoMeio = false)
        val truncado = desfechoCom(corteNoMeio = true)

        assertNotEquals(
            "uma fala inteira e uma fala cortada ao meio não podem chegar à camada " +
                "de cima como o MESMO evento — sem diferença, a tela não tem como marcar",
            normal,
            truncado,
        )
        assertEquals(FimDaFala.ENCERRADA_PELO_EMISSOR, normal.motivo)
        assertEquals(FimDaFala.CORTADA_NO_MEIO, truncado.motivo)
        assertEquals(
            "e a diferença NÃO está na contagem: são 10 quadros ouvidos nos dois. " +
                "É por isso que contar nunca bastou.",
            normal.quadros,
            truncado.quadros,
        )
        assertEquals(0, truncado.perdidos)
    }

    /**
     * **O `fim de transmissão` do transporte deixa de ser descartado.**
     *
     * O `ultimo` se perde: ele é um quadro como qualquer outro. Quando isso
     * acontece com o emissor vivo, o transporte ainda entrega
     * `EventoDeRede.FimDeTransmissao` — e o `Receptor` o ignorava com o comentário
     * "o quadro `ultimo` encerra", que é verdade só quando ele chega.
     *
     * O teste crava as duas metades: o desfecho é **encerramento**, não corte
     * (o emissor de fato terminou), e a conclusão sai em **centenas** de
     * milissegundos em vez dos 2 s de tolerância de jitter.
     */
    @Test
    fun oUltimoQuadroSePerde_masOFimDeTransmissaoChega_eNaoSaoOs2sNemUmCorte() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            // Dez quadros, e o `ultimo` NUNCA sai — perdido no caminho.
            repeat(10) { alfa.enviar(quadro("tx-1", it)) }
            advanceTimeBy(400)
            assertTrue("o fim ainda não pode ter saído", escuta.terminou.isEmpty())

            val quandoOEmissorSoltou = currentTime
            alfa.encerrar("tx-1")
            advanceTimeBy(400)

            val fim = escuta.terminou.singleOrNull()
            assertNotNull("o fim anunciado tem de encerrar a fala", fim)
            assertEquals(
                "o emissor SOLTOU o botão: isto não é corte, e chamá-lo de corte " +
                    "ensinaria o agente a desconfiar de fala inteira",
                FimDaFala.ENCERRADA_PELO_EMISSOR,
                fim!!.motivo,
            )
            assertTrue(
                "e a conclusão sai em ${currentTime - quandoOEmissorSoltou} ms — o " +
                    "bastante para drenar o jitter, longe dos 2 s de quem sumiu",
                currentTime - quandoOEmissorSoltou < 1_000,
            )
            assertEquals(10, fim.quadros)
        } finally {
            escopo.cancel()
        }
    }

    // ── 2. A rede cai do lado de quem OUVE ────────────────────────────────────

    /**
     * **Bravo é quem entra no túnel.**
     *
     * Do ponto de vista do laço de reprodução, é idêntico ao caso anterior — e é
     * esse o ponto. A única coisa que separa os dois na camada de cima é
     * `conectado()`: no corte do emissor, o transporte de Bravo continua verde; no
     * corte dele, fica vermelho. É a diferença que a tela usa para dizer "sem
     * dados" em vez de deixar o agente achar que o colega calou.
     */
    @Test
    fun aRedeDeBravoCaiNoMeioDaRecepcao_soOEstadoDoTransporteDistingueDoOutroCorte() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            repeat(8) { alfa.enviar(quadro("tx-1", it)) }
            advanceTimeBy(400)
            assertTrue("Bravo estava ouvindo", escuta.reproduzidos == 8)
            assertTrue(bravo.conectado())

            bravo.rede = false // agora é o OUVINTE que some
            repeat(12) { alfa.enviar(quadro("tx-1", 8 + it, ultimo = it == 11)) }
            advanceTimeBy(2_100)

            assertEquals("nada mais foi tocado", 8, escuta.reproduzidos)
            assertEquals(8, escuta.terminou.single().quadros)
            assertFalse(
                "e é SÓ isto que a tela tem para distinguir de um colega que calou",
                bravo.conectado(),
            )
        } finally {
            escopo.cancel()
        }
    }

    // ── 3. Entrar no grupo no meio da fala ────────────────────────────────────

    /**
     * **Charlie liga o rádio no meio da ocorrência.**
     *
     * Observado, e é o comportamento certo para voz ao vivo: ele ouve **do ponto em
     * que entrou**, sem PLC para o que perdeu — reconstruir 300 ms de fala que
     * nunca chegou seria inventar áudio.
     *
     * **CONSERTADO (22/08).** Charlie continua perdendo o
     * [EventoRecepcao.Chegando] — o anúncio já passou, e não há como recuperá-lo.
     * O que não podia continuar é o que isso produzia: voz tocando com a tela
     * dizendo que ninguém falava, porque `RadioTatico.tratarRecepcao` só chamava
     * `aoMudarQuemFala` no `Chegando`.
     *
     * Agora o receptor emite [EventoRecepcao.ChegandoSemAnuncio] — a admissão
     * honesta de "alguém está falando e eu ainda não sei quem" —, e é dela que o
     * `RadioTatico` parte para perguntar ao servidor de quem é o piso.
     */
    @Test
    fun charlieEntraNoMeioDaFala_ouveDoPontoEmQueEntrou_eSabeQueAlguemFala() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val charlie = ParDeRadio("charlie", barramento, noGrupo = false)
        val deBravo = escutar(bravo, escopo)
        val deCharlie = escutar(charlie, escopo)

        try {
            alfa.anunciar(anuncio("tx-1", autor = "alfa"))
            repeat(15) { alfa.enviar(quadro("tx-1", it)) }
            advanceTimeBy(400)

            charlie.noGrupo = true // entrou agora
            repeat(15) { alfa.enviar(quadro("tx-1", 15 + it, ultimo = it == 14)) }
            advanceTimeBy(800)

            assertEquals("Bravo, presente desde o começo, ouviu tudo", 30, deBravo.reproduzidos)
            assertEquals(
                "Charlie ouve só a metade que existia depois da entrada dele",
                15,
                deCharlie.reproduzidos,
            )
            assertEquals(
                "e NÃO pode haver PLC pelo começo que ele nunca teve direito de ouvir",
                0,
                deCharlie.interpolados,
            )
            assertEquals(
                "a primeira coisa que Charlie ouve é o quadro 15, não o 0",
                15.toShort(),
                deCharlie.tocados.first(),
            )
            assertTrue("Bravo sabe quem fala", deBravo.chegando.isNotEmpty())
            assertTrue(
                "Charlie não pode receber `Chegando`: o anúncio passou antes de ele " +
                    "entrar, e fabricar um seria inventar autoria",
                deCharlie.chegando.isEmpty(),
            )
            val semAnuncio = deCharlie.eventos
                .filterIsInstance<EventoRecepcao.ChegandoSemAnuncio>()
                .singleOrNull()
            assertNotNull(
                "mas ele TEM de saber que alguém está falando — voz sem nenhum " +
                    "evento de autoria é, na tela, som sem autor",
                semAnuncio,
            )
            assertEquals("tx-1", semAnuncio!!.transmissaoId)
            assertEquals(
                "e o aviso vem ANTES do primeiro áudio: depois dele a tela já mentiu",
                0,
                deCharlie.eventos.indexOf(semAnuncio),
            )
            assertTrue("o fim ele recebe", deCharlie.terminou.isNotEmpty())
        } finally {
            escopo.cancel()
        }
    }

    /**
     * **O contra-teste: quem estava presente NÃO recebe o aviso de anonimato.**
     *
     * Se `ChegandoSemAnuncio` saísse em toda fala, o rótulo "origem não
     * confirmada" apareceria sobre transmissões perfeitamente identificadas — e a
     * tela passaria a mentir na direção oposta, ensinando o agente a ignorar o
     * rótulo justamente quando ele importa.
     */
    @Test
    fun quemOuviuOAnuncio_naoRecebeAvisoDeFalaSemAutor() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1", autor = "alfa"))
            repeat(10) { alfa.enviar(quadro("tx-1", it, ultimo = it == 9)) }
            advanceTimeBy(500)

            assertEquals(1, escuta.chegando.size)
            assertTrue(
                "fala anunciada não pode chegar como fala sem autor",
                escuta.eventos.none { it is EventoRecepcao.ChegandoSemAnuncio },
            )
        } finally {
            escopo.cancel()
        }
    }

    // ── 4. Relógio do cliente errado ──────────────────────────────────────────

    /**
     * **A ordem da fala é a SEQUÊNCIA, nunca o carimbo de tempo.**
     *
     * Um aparelho com o relógio adiantado meia hora — ou zerado por troca de
     * bateria — carimbaria `capturadoEmMs` fora de qualquer ordem plausível. Se o
     * receptor ordenasse por tempo, a fala sairia embaralhada ou seria descartada
     * inteira.
     *
     * Aqui os carimbos vêm em ordem DECRESCENTE e ainda por cima negativos, e a
     * voz sai em ordem.
     */
    @Test
    fun relogioDoEmissorTotalmenteErrado_naoMudaAOrdemDaVoz() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            repeat(12) { i ->
                // Carimbo decrescente, começando no futuro e terminando no passado.
                alfa.enviar(quadro("tx-1", i, t = 1_800_000L - i * 1_000_000L, ultimo = i == 11))
            }
            advanceTimeBy(500)

            assertEquals(
                "a voz saiu na ordem da sequência, não na do relógio",
                (0..11).map { it.toShort() },
                escuta.tocados,
            )
            assertEquals(0, escuta.interpolados)
        } finally {
            escopo.cancel()
        }
    }

    /**
     * O contra-teste: **chegada** fora de ordem, com carimbo certo. O buffer de
     * jitter reordena — que é o que ele existe para fazer, e é o que prova que o
     * teste acima não passou por acidente de ordem de chegada.
     */
    @Test
    fun quadrosQueChegamForaDeOrdem_saemNaOrdem() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            listOf(3, 1, 0, 5, 2, 4, 7, 6).forEach { alfa.enviar(quadro("tx-1", it)) }
            alfa.enviar(quadro("tx-1", 8, ultimo = true))
            advanceTimeBy(500)

            assertEquals((0..8).map { it.toShort() }, escuta.tocados)
        } finally {
            escopo.cancel()
        }
    }

    /**
     * **Quadro que chega tarde demais é descartado, e o buraco já virou PLC.**
     *
     * Tocá-lo depois quebraria a ordem e soaria pior que a interpolação que já
     * saiu no lugar dele. O teste exige as duas metades: a interpolação acontece
     * **e** o retardatário não aparece na saída.
     */
    @Test
    fun oQuadroQueChegaTardeDemais_naoVoltaAToarDepoisDoPlc() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            // O 4 nunca chega na hora; os posteriores chegam.
            listOf(0, 1, 2, 3, 5, 6, 7, 8).forEach { alfa.enviar(quadro("tx-1", it)) }
            advanceTimeBy(400)

            assertTrue("o buraco tem de virar PLC, nunca silêncio", escuta.interpolados >= 1)
            assertFalse(
                "o retardatário não pode aparecer fora de lugar",
                escuta.tocados.contains(4.toShort()),
            )

            alfa.enviar(quadro("tx-1", 4)) // chega atrasado
            alfa.enviar(quadro("tx-1", 9, ultimo = true))
            advanceTimeBy(400)

            assertFalse(
                "e continua não podendo aparecer — a ordem da voz é irrecuperável",
                escuta.tocados.contains(4.toShort()),
            )
            assertTrue(escuta.terminou.isNotEmpty())
        } finally {
            escopo.cancel()
        }
    }

    // ── 5. Três transmissões em sequência ─────────────────────────────────────

    /**
     * **Três falas enfileiradas no mesmo canal, sem vazamento entre elas.**
     *
     * O risco concreto: o buffer de jitter guarda quadros por número de sequência,
     * e todas as transmissões começam em 0. Sem `jitter.reiniciar()` no anúncio, um
     * quadro pendente da fala anterior tocaria dentro da seguinte — a voz de um
     * agente colada dentro da frase de outro.
     */
    @Test
    fun tresFalasSeguidas_naoVazamUmaDentroDaOutra() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val pares = listOf("alfa", "bravo", "charlie").map { ParDeRadio(it, barramento) }
        val delta = ParDeRadio("delta", barramento)
        val escuta = escutar(delta, escopo)

        try {
            pares.forEach { par ->
                val tx = "tx-${par.agenteId}"
                par.anunciar(anuncio(tx, autor = par.agenteId))
                repeat(10) { par.enviar(quadro(tx, it, ultimo = it == 9)) }
                // **A sujeira que faz este teste valer.** Um quadro de sequência
                // alta que chega tarde e nunca é tocado fica pendente no buffer.
                // Sem `jitter.reiniciar()` no anúncio seguinte, `proximaEsperada`
                // continuaria em 10 e os quadros 0..9 da PRÓXIMA fala seriam
                // descartados por "atrasados" — a fala do colega sumiria inteira.
                par.enviar(quadro(tx, 40))
                advanceTimeBy(600)
            }

            assertEquals("uma abertura por fala", 3, escuta.chegando.size)
            assertEquals("um fim por fala", 3, escuta.terminou.size)
            assertEquals(
                listOf("tx-alfa", "tx-bravo", "tx-charlie"),
                escuta.terminou.map { it.transmissaoId },
            )
            assertEquals(
                "cada fala entrega os 10 quadros dela, e só os dela",
                listOf(10, 10, 10),
                escuta.terminou.map { it.quadros },
            )
            assertEquals(
                "e a voz sai em ordem dentro de cada fala, sem quadro de uma dentro da outra",
                (0..9).map { it.toShort() } + (0..9).map { it.toShort() } + (0..9).map { it.toShort() },
                escuta.tocados,
            )
        } finally {
            escopo.cancel()
        }
    }

    // ── 6. O texto chega depois, e com chave própria ──────────────────────────

    /**
     * **O texto de uma fala não pode pousar no balão da seguinte.**
     *
     * A transcrição na origem sai **depois** do fim da fala — e nesse intervalo
     * outra transmissão já pode ter começado. O evento carrega o `transmissaoId`
     * justamente para casar por chave, e sai **fora** do laço de reprodução para
     * não esperar o áudio drenar.
     */
    @Test
    fun oTextoDaFalaAnterior_chegaComAChaveDela_mesmoComOutraFalaTocando() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val charlie = ParDeRadio("charlie", barramento)
        val escuta = escutar(charlie, escopo)

        try {
            alfa.anunciar(anuncio("tx-alfa"))
            repeat(5) { alfa.enviar(quadro("tx-alfa", it, ultimo = it == 4)) }
            advanceTimeBy(300)

            // Bravo já está falando quando o whisper de Alfa termina.
            bravo.anunciar(anuncio("tx-bravo", autor = "bravo"))
            repeat(5) { bravo.enviar(quadro("tx-bravo", it)) }
            alfa.transcrever("tx-alfa", "reforço na Avenida Sete")
            advanceTimeBy(300)

            val texto = escuta.eventos.filterIsInstance<EventoRecepcao.TextoDaFala>().single()
            assertEquals(
                "o texto é de Alfa e tem de continuar sendo, com Bravo no ar",
                "tx-alfa",
                texto.transmissaoId,
            )
            assertEquals("reforço na Avenida Sete", texto.texto)
        } finally {
            escopo.cancel()
        }
    }

    // ── 7. Canal degradado ────────────────────────────────────────────────────

    /**
     * **Perda alta tem de virar aviso, e uma vez só.**
     *
     * Decidir uma abordagem contando com um apoio que talvez não tenha ouvido é
     * pior que saber que o canal está ruim. E repetir o aviso a cada quadro seria
     * ruído por cima de um alerta que o agente já recebeu.
     */
    @Test
    fun perdaAcimaDoLimiar_avisaUmaVezSo() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            // Metade dos quadros some — muito acima dos 30% do limiar.
            (0..79).filter { it % 2 == 0 }.forEach { alfa.enviar(quadro("tx-1", it)) }
            alfa.enviar(quadro("tx-1", 80, ultimo = true))
            advanceTimeBy(3_000)

            val avisos = escuta.eventos.count { it is EventoRecepcao.CanalDegradado }
            assertEquals("um aviso, não um por quadro", 1, avisos)
            assertTrue("e a voz continuou saindo, interpolada", escuta.interpolados > 10)
        } finally {
            escopo.cancel()
        }
    }

    /** Contra-teste: canal limpo **não** dispara o aviso. */
    @Test
    fun canalLimpo_naoAvisaDegradacao() = runTest {
        val escopo = escopoDeTeste(this)
        val barramento = BarramentoTatico()
        val alfa = ParDeRadio("alfa", barramento)
        val bravo = ParDeRadio("bravo", barramento)
        val escuta = escutar(bravo, escopo)

        try {
            alfa.anunciar(anuncio("tx-1"))
            repeat(80) { alfa.enviar(quadro("tx-1", it, ultimo = it == 79)) }
            advanceTimeBy(3_000)

            assertEquals(0, escuta.eventos.count { it is EventoRecepcao.CanalDegradado })
            assertEquals(80, escuta.reproduzidos)
        } finally {
            escopo.cancel()
        }
    }
}
