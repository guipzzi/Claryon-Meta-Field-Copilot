package com.claryon.evidence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * O cofre real depende do Android Keystore e **não roda em JVM** — por isso estes
 * testes são instrumentados. O [Manifesto] tem cobertura separada em
 * `src/test/` porque é a única parte que roda sem chave.
 *
 * A versão anterior deste arquivo passou a mentir quando a janela entrou: ela
 * assumia **um segmento por `append`** (`assertEquals(30, manifesto.chain.size)`
 * depois de 30 chamadas) e comparava o retorno de `verificar` com `-1`, que era o
 * `Int` da API antiga. Os dois pressupostos morreram na correção de amplificação
 * de escrita.
 *
 * ## Por que as taxas aqui são artificiais
 *
 * `bytesPorJanela = taxaHz × 2 × janelaMs / 1000`. Para exercitar selagem e
 * retenção sem mover megabytes num emulador, os testes declaram taxas pequenas em
 * [OccurrenceContext.sampleRateHz]. O caminho de código é idêntico ao de 16 kHz —
 * só o tamanho do balde muda.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedEvidenceVaultTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun limpar() {
        File(ctx.filesDir, "evidence").deleteRecursively()
    }

    /** Taxa fictícia para que a janela caiba em poucos kB. Ver KDoc da classe. */
    private fun ocorrencia(taxaHz: Int = TAXA_DE_BANCADA) = OccurrenceContext(
        agentId = "007",
        unitId = "GTA-3",
        startedAtEpochMillis = 42L,
        sampleRateHz = taxaHz,
    )

    private fun cofre(
        janelaMs: Int = JANELA_MS,
        reserva: Long = 0L,
        retencao: PoliticaDeRetencao = PoliticaDeRetencao.TudoRetido,
    ) = EncryptedEvidenceVault(
        appContext = ctx,
        clockMillis = { 1_700_000_000_000L },
        janelaMs = janelaMs,
        reservaDeDiscoBytes = reserva,
        retencao = retencao,
    )

    private fun dirDe(handle: RecordingHandle) =
        File(File(ctx.filesDir, "evidence"), handle.id)

    private fun segmentosNoDisco(handle: RecordingHandle): List<File> =
        dirDe(handle).listFiles { f -> f.isFile && f.name.endsWith(".enc") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun abrir(vault: EncryptedEvidenceVault, taxaHz: Int = TAXA_DE_BANCADA) =
        runBlocking { (vault.beginRecording(ocorrencia(taxaHz)) as Result.Success).value }

    // ── Janela: a correção de amplificação de escrita ─────────────────────────

    /**
     * Guarda de regressão do defeito de 617 MB/min. Se alguém voltar a selar um
     * arquivo por quadro, este teste é o que grita: 40 chamadas cabendo em 4
     * janelas não podem produzir 40 arquivos.
     */
    @Test
    fun umArquivoPorJanela_naoPorChamada() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        val decimo = ByteArray(BYTES_POR_JANELA / 10) { 0x5A }

        repeat(9) { assertTrue(vault.append(handle, decimo) is Result.Success) }
        assertEquals("nada selado antes da janela encher", 0, segmentosNoDisco(handle).size)

        // A décima chamada completa a janela.
        val selado = (vault.append(handle, decimo) as Result.Success).value
        assertTrue("décima chamada devia selar", selado is Anexado.JanelaSelada)
        assertEquals(1, segmentosNoDisco(handle).size)

        repeat(30) { vault.append(handle, decimo) }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        // 40 chamadas = 4 janelas cheias. A v1 teria escrito 40 arquivos.
        assertEquals(4, manifesto.chain.size)
        assertEquals(4, segmentosNoDisco(handle).size)
    }

    /**
     * A janela final é sempre parcial, e são os últimos segundos da ocorrência —
     * onde costuma estar o que importa. Perdê-los no fechamento seria pior que o
     * desperdício que a janela veio corrigir.
     */
    @Test
    fun finalize_selaAJanelaParcial() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        val pedaco = ByteArray(BYTES_POR_JANELA / 4) { 0x11 }
        repeat(2) { vault.append(handle, pedaco) }

        val manifesto = (vault.finalize(handle) as Result.Success).value
        assertEquals(1, manifesto.chain.size)
        assertEquals(BYTES_POR_JANELA / 2, manifesto.chain[0].bytes)
        assertEquals((BYTES_POR_JANELA / 2).toLong(), manifesto.bytesRetidos)
    }

    // ── Repouso cifrado e cadeia ──────────────────────────────────────────────

    @Test
    fun gravaCifrado_finaliza_eCadeiaIntegra() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        val padrao = ByteArray(BYTES_POR_JANELA) { 0x7E }
        repeat(3) { assertTrue(vault.append(handle, padrao) is Result.Success) }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        assertEquals(3, manifesto.chain.size)
        assertEquals(Manifesto.VERSAO_ATUAL, manifesto.versao)
        assertEquals(TAXA_DE_BANCADA, manifesto.sampleRateHz)

        // O segmento no disco não pode conter o padrão em claro. Como o texto
        // claro é um único byte repetido, basta procurar por ele.
        val seg0 = segmentosNoDisco(handle).first().readBytes()
        assertFalse("segmento não deveria estar em claro", seg0.all { it == 0x7E.toByte() })
        assertTrue("cabeçalho Tink + tag GCM engordam o segmento", seg0.size > BYTES_POR_JANELA)

        assertEquals(Integridade.Integra, vault.verificar(handle, manifesto))
    }

    /**
     * O caminho do perito: conferir a custódia **sem** o objeto em memória, lendo
     * o manifesto do próprio diretório. Se este teste passa e o de cima também, o
     * manifesto append-only é fiel ao que a sessão viveu.
     */
    @Test
    fun verificaLendoOManifestoDoDisco() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(2) { vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x33 }) }
        vault.finalize(handle)

        assertEquals(Integridade.Integra, vault.verificar(handle))

        val lido = Manifesto.ler(dirDe(handle))!!
        assertEquals(Manifesto.VERSAO_ATUAL, lido.versao)
        assertEquals(JANELA_MS, lido.janelaMs)
        assertEquals(handle, lido.handle)
        assertTrue(lido.finalizado)
    }

    @Test
    fun adulterarUmByte_apontaOSegmento() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(4) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        val manifesto = (vault.finalize(handle) as Result.Success).value
        assertEquals(Integridade.Integra, vault.verificar(handle, manifesto))

        val alvo = File(dirDe(handle), "seg_00002.enc")
        RandomAccessFile(alvo, "rw").use { raf ->
            val pos = raf.length() / 2
            raf.seek(pos)
            val b = raf.readByte()
            raf.seek(pos)
            raf.writeByte(b.toInt() xor 0x01)
        }

        // O AES-GCM já recusa autenticar; o hash encadeado é a segunda barreira.
        assertEquals(Integridade.Quebrada(2), vault.verificar(handle, manifesto))
    }

    /**
     * O mesmo byte adulterado, mas conferido pelo caminho que a **corregedoria**
     * usaria: sem o [CustodyManifest] em memória, lendo o manifesto do próprio
     * diretório.
     *
     * O teste acima passa o manifesto que o cofre acabou de devolver — o que só
     * existe no processo que gravou. Um perito recebe um diretório, e é a
     * sobrecarga de um argumento só de [EncryptedEvidenceVault.verificar] que
     * ele exercita. Sem este teste, a sobrecarga do perito ficava provada apenas
     * no caminho feliz ([verificaLendoOManifestoDoDisco]).
     */
    @Test
    fun adulterarUmByte_apontaOSegmento_lendoOManifestoDoDisco() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(4) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        vault.finalize(handle)
        assertEquals(Integridade.Integra, vault.verificar(handle))

        val alvo = File(dirDe(handle), "seg_00001.enc")
        RandomAccessFile(alvo, "rw").use { raf ->
            val pos = raf.length() / 2
            raf.seek(pos)
            val b = raf.readByte()
            raf.seek(pos)
            raf.writeByte(b.toInt() xor 0x01)
        }

        assertEquals(Integridade.Quebrada(1), vault.verificar(handle))
    }

    /**
     * **O limite da cadeia, exercitado em vez de prometido.**
     *
     * O KDoc de [Manifesto] admite em uma frase que o manifesto não é assinado e
     * que "quem tiver acesso de escrita ao diretório pode reescrevê-lo". Este
     * teste mostra o que essa frase custa, e o ataque **não precisa de chave
     * nenhuma**: basta apagar os últimos segmentos e as linhas correspondentes.
     *
     * A cadeia sobrevivente é aritmeticamente perfeita — cada elo ancora no
     * anterior — e [EncryptedEvidenceVault.verificar] responde
     * [Integridade.Integra]. O que sumiu foi o **fim** da ocorrência, e nada no
     * diretório registra que ele existiu.
     *
     * [HashChain.verificar] percorre o maior dos dois tamanhos justamente para
     * pegar truncamento; mas aquilo só funciona quando o manifesto continua
     * inteiro. Contra quem edita os dois, hash encadeado não tem o que fazer:
     * integridade não é autenticidade. Fechar isto exige assinar o manifesto com
     * uma chave que o app não possa usar para forjar — âncora externa (servidor
     * ou HSM da corregedoria), não o mesmo Keystore que já grava os segmentos.
     *
     * Este teste é permanente e afirma o estado atual. Quando a assinatura
     * existir, ele **falha** — e é assim que se descobre que a documentação
     * precisa mudar junto.
     */
    @Test
    fun manifestoNaoAssinado_truncarAOcorrenciaPassaPorIntegra() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(5) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        val original = (vault.finalize(handle) as Result.Success).value
        assertEquals(5, original.chain.size)
        assertEquals(Integridade.Integra, vault.verificar(handle))

        // O ataque: apagar os dois últimos segmentos e as duas últimas linhas
        // `S` do manifesto. Nenhuma chave é usada.
        val dir = dirDe(handle)
        File(dir, "seg_00004.enc").delete()
        File(dir, "seg_00003.enc").delete()
        val manifesto = File(dir, Manifesto.NOME)
        var restantes = 3
        val podadas = manifesto.readLines().filter { linha ->
            if (linha.startsWith("S\t")) (restantes-- > 0) else true
        }
        manifesto.writeText(podadas.joinToString("\n", postfix = "\n"))

        val lido = Manifesto.ler(dir)!!
        assertEquals("o manifesto agora declara uma gravação mais curta", 3, lido.cadeia.size)
        assertEquals(
            "a cadeia truncada é internamente consistente — e é esse o problema",
            Integridade.Integra,
            vault.verificar(handle),
        )
    }

    /**
     * Segmento no disco sem linha no manifesto é o resultado de morrer entre os
     * dois passos da escrita. É o modo de falha que a **ordem** escolheu ter, e
     * precisa ser reportado como benigno — não como adulteração, que é o que um
     * perito leria se caísse em [Integridade.Quebrada].
     */
    @Test
    fun segmentoSemLinhaNoManifesto_naoEhAdulteracao() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(2) { vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x22 }) }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        File(dirDe(handle), "seg_00002.enc").writeBytes(ByteArray(64))

        assertEquals(Integridade.SegmentoNaoRegistrado(2), vault.verificar(handle, manifesto))
    }

    // ── Retenção ──────────────────────────────────────────────────────────────

    /**
     * Sem [Integridade.ExpurgadaPorPolitica], a política de retenção do produto
     * passaria a **forjar sozinha** um sinal de fraude em toda gravação antiga: o
     * segmento apagado por configuração seria reportado como adulterado.
     */
    @Test
    fun retencao_apagaOMaisAntigo_registraEContinuaVerificavel() = runBlocking {
        // 1 min de retenção com janela de 30 s = 2 janelas retidas.
        val vault = cofre(
            janelaMs = 30_000,
            retencao = PoliticaDeRetencao.UltimosMinutos(minutos = 1, motivo = "TESTE"),
        )
        val handle = abrir(vault)
        val janela = ByteArray(TAXA_DE_BANCADA * 2 * 30_000 / 1_000) { 0x44 }
        repeat(3) { vault.append(handle, janela) }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        assertEquals(listOf(0), manifesto.purgados.map { it.sequence })
        assertEquals("TESTE", manifesto.purgados[0].motivo)
        assertFalse(File(dirDe(handle), "seg_00000.enc").exists())

        // O hash do purgado permanece na cadeia — é o que ancora o elo seguinte.
        assertEquals(3, manifesto.chain.size)
        assertEquals(
            Integridade.ExpurgadaPorPolitica(listOf(0)),
            vault.verificar(handle, manifesto),
        )
        // E os bytes purgados saem da conta do que está sob custódia.
        assertTrue(manifesto.bytesRetidos < manifesto.chain.sumOf { it.bytes.toLong() })
    }

    @Test
    fun semPolitica_nadaEhApagado() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(3) { vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x55 }) }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        assertTrue("o padrão não apaga prova", manifesto.purgados.isEmpty())
        assertEquals(3, segmentosNoDisco(handle).size)
    }

    // ── Disco cheio ───────────────────────────────────────────────────────────

    /**
     * Antes, disco cheio produzia o pior desfecho possível: o cofre falhava
     * cinquenta vezes por segundo e **ninguém ouvia**, porque o retorno era
     * descartado. Aqui a reserva impossível força a condição sem encher o
     * emulador.
     */
    @Test
    fun reservaImpossivel_recusaAbrirComCodigoProprio() = runBlocking {
        val vault = cofre(reserva = Long.MAX_VALUE)
        val r = vault.beginRecording(ocorrencia())
        assertTrue(r is Result.Failure)
        assertEquals("EVID_SEM_ESPACO", (r as Result.Failure).error.code)
    }

    /**
     * "Encerrada pelo agente" e "encerrada porque o disco acabou" não podem
     * parecer a mesma coisa para quem periciar depois.
     */
    @Test
    fun finalizarComMotivo_ficaNoManifesto() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x66 })
        val manifesto = (vault.finalizar(handle, motivo = "EVID_SEM_ESPACO") as Result.Success).value

        assertEquals("EVID_SEM_ESPACO", manifesto.motivoDoFim)
        assertEquals("EVID_SEM_ESPACO", Manifesto.ler(dirDe(handle))!!.motivoDoFim)
    }

    // ── Ciclo de vida do handle ───────────────────────────────────────────────

    /**
     * Reabrir a mesma ocorrência reiniciaria `seq` em 0 e sobregravaria
     * `seg_00000.enc` — destruição silenciosa de evidência já gravada.
     */
    @Test
    fun reabrirOcorrencia_recusa() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        vault.append(handle, ByteArray(BYTES_POR_JANELA))
        vault.finalize(handle)

        val r = vault.beginRecording(ocorrencia())
        assertTrue(r is Result.Failure)
        assertEquals("EVID_JA_ABERTA", (r as Result.Failure).error.code)
    }

    @Test
    fun appendDepoisDeFinalizar_falhaComCodigoDeHandle() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        vault.finalize(handle)

        val r = vault.append(handle, ByteArray(16))
        assertTrue(r is Result.Failure)
        assertEquals("EVID_HANDLE", (r as Result.Failure).error.code)
    }

    private companion object {
        /** Taxa fictícia: mantém a janela em 4 kB em vez de 320 kB. */
        const val TAXA_DE_BANCADA = 1_000
        const val JANELA_MS = 2_000

        /** `taxaHz × 2 bytes × janelaMs / 1000` — a mesma conta do cofre. */
        const val BYTES_POR_JANELA = TAXA_DE_BANCADA * 2 * JANELA_MS / 1_000
    }
}
