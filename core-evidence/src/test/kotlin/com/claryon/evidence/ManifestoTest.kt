package com.claryon.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * O manifesto é a única parte do cofre que roda sem Keystore, e é onde estava o
 * defeito de 605 MB/min. Estes testes cobrem o que a bancada instrumentada não
 * alcança de graça: o formato, a **migração** da v1, e o rasgo no fim do arquivo.
 */
class ManifestoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = OccurrenceContext(
        agentId = "007",
        unitId = "GTA-3",
        startedAtEpochMillis = 1_700_000_000_000,
        sampleRateHz = 16_000,
    )

    private fun h(n: Int) = "%064x".format(n)

    // ── Formato v2 ────────────────────────────────────────────────────────────

    @Test
    fun v2_idaEVolta_preservaCadeiaPurgasEMetadados() {
        val dir = tmp.newFolder()
        val handle = RecordingHandle("GTA-3_007_1700000000000")
        Manifesto.Escritor(File(dir, Manifesto.NOME)).use { e ->
            e.cabecalho(ctx(), handle, janelaMs = 10_000)
            e.segmento(ChunkHash(0, h(1), null, 320_000))
            e.segmento(ChunkHash(1, h(2), h(1), 320_000))
            e.purga(Purga(0, 1_700_000_099_000, "RETENCAO_CONFIGURADA"))
            e.fim(1_700_000_100_000, motivo = null)
        }

        val lido = assertNotNull(Manifesto.ler(dir)).let { Manifesto.ler(dir)!! }
        assertEquals(2, lido.versao)
        assertEquals(handle, lido.handle)
        assertEquals(16_000, lido.sampleRateHz)
        assertEquals(10_000, lido.janelaMs)
        assertEquals(OccurrenceContext.FORMATO_PCM_S16LE_MONO, lido.formato)
        assertEquals(listOf(0, 1), lido.cadeia.map { it.sequence })
        assertEquals(h(1), lido.cadeia[1].previousSha256Hex)
        assertEquals(320_000, lido.cadeia[0].bytes)
        assertEquals(listOf(0), lido.purgados.map { it.sequence })
        assertTrue(lido.finalizado)
        assertNull(lido.motivoDoFim)
    }

    @Test
    fun v2_registraPorQueParou() {
        val dir = tmp.newFolder()
        Manifesto.Escritor(File(dir, Manifesto.NOME)).use { e ->
            e.cabecalho(ctx(), RecordingHandle("x"), janelaMs = 10_000)
            e.fim(1L, motivo = "EVID_SEM_ESPACO")
        }
        // "encerrada pelo agente" e "encerrada porque o disco acabou" não podem
        // parecer a mesma coisa para quem periciar depois.
        assertEquals("EVID_SEM_ESPACO", Manifesto.ler(dir)!!.motivoDoFim)
    }

    @Test
    fun v2_escritaEhAppendOnly_naoQuadratica() {
        val dir = tmp.newFolder()
        val arquivo = File(dir, Manifesto.NOME)
        var maiorTamanho = 0L
        Manifesto.Escritor(arquivo).use { e ->
            e.cabecalho(ctx(), RecordingHandle("x"), janelaMs = 10_000)
            var prev: String? = null
            for (i in 0 until 600) {
                e.segmento(ChunkHash(i, h(i + 1), prev, 320_000))
                prev = h(i + 1)
                maiorTamanho = maxOf(maiorTamanho, arquivo.length())
            }
        }
        // Cada `segmento` só acrescenta: o arquivo cresce monotonicamente e o
        // total escrito é o tamanho final, não a soma dos tamanhos intermediários
        // (que era o custo quadrático da v1).
        assertEquals(arquivo.length(), maiorTamanho)
        assertEquals(600, Manifesto.ler(dir)!!.cadeia.size)
        assertTrue("manifesto de 600 segmentos < 90 kB", arquivo.length() < 90_000)
    }

    // ── Rasgo no fim ──────────────────────────────────────────────────────────

    @Test
    fun linhaTruncadaNoFim_descartaSoOResto() {
        val dir = tmp.newFolder()
        val arquivo = File(dir, Manifesto.NOME)
        Manifesto.Escritor(arquivo).use { e ->
            e.cabecalho(ctx(), RecordingHandle("x"), janelaMs = 10_000)
            e.segmento(ChunkHash(0, h(1), null, 320_000))
            e.segmento(ChunkHash(1, h(2), h(1), 320_000))
        }
        // Processo morto no meio do write da terceira linha.
        arquivo.appendText("S\t2\t${h(2)}\t${h(3).take(20)}")

        val lido = Manifesto.ler(dir)!!
        assertEquals(
            "manifesto rasgado é um manifesto mais curto, não um corrompido",
            listOf(0, 1),
            lido.cadeia.map { it.sequence },
        )
    }

    // ── Migração ──────────────────────────────────────────────────────────────

    @Test
    fun v1_semCampoDeVersao_continuaLegivel() {
        val dir = tmp.newFolder()
        // Exatamente o que a versão anterior escrevia (sem `versao=`).
        File(dir, Manifesto.NOME).writeText(
            buildString {
                appendLine("handle=GTA-3_007_1700000000000")
                appendLine("finalizado=true")
                appendLine("finalizedAt=1700000100000")
                appendLine("0\t-\t${h(1)}")
                appendLine("1\t${h(1)}\t${h(2)}")
            },
        )

        val lido = Manifesto.ler(dir)!!
        assertEquals(1, lido.versao)
        assertEquals("GTA-3_007_1700000000000", lido.handle.id)
        assertEquals(listOf(0, 1), lido.cadeia.map { it.sequence })
        assertEquals(h(1), lido.cadeia[1].previousSha256Hex)
        // A v1 não registrava tamanho. Zero é a admissão da lacuna, não um palpite.
        assertEquals(0, lido.cadeia[0].bytes)
        assertEquals(20, lido.janelaMs)
        assertTrue(lido.finalizado)
    }

    @Test
    fun v1_interrompida_naoAparecaComoFinalizada() {
        val dir = tmp.newFolder()
        File(dir, Manifesto.NOME).writeText(
            "handle=x\nfinalizado=false\n0\t-\t${h(1)}\n",
        )
        assertTrue(!Manifesto.ler(dir)!!.finalizado)
    }

    @Test
    fun semArquivo_retornaNulo() {
        assertNull(Manifesto.ler(tmp.newFolder()))
    }

    // ── Custódia derivada ─────────────────────────────────────────────────────

    @Test
    fun bytesRetidos_descontamOsPurgados() {
        val m = CustodyManifest(
            handle = RecordingHandle("x"),
            chain = listOf(
                ChunkHash(0, h(1), null, 320_000),
                ChunkHash(1, h(2), h(1), 320_000),
                ChunkHash(2, h(3), h(2), 160_000),
            ),
            finalizedAtEpochMillis = 1L,
            purgados = listOf(Purga(0, 1L, "RETENCAO_CONFIGURADA")),
        )
        // O hash do segmento purgado continua na cadeia — é o que ancora o elo
        // seguinte —, mas os bytes não estão mais sob custódia.
        assertEquals(480_000L, m.bytesRetidos)
        assertEquals(3, m.chain.size)
    }
}
