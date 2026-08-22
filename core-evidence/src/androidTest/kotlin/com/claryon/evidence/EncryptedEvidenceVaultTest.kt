package com.claryon.evidence

import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── Truncamento: o ataque que a cadeia de hash não pegava ─────────────────

    /**
     * **O defeito, agora fechado no aparelho.**
     *
     * Até a v2 o manifesto não era assinado, e o ataque não precisava de chave
     * nenhuma: apagar os últimos segmentos e as linhas `S` correspondentes deixava
     * uma cadeia **aritmeticamente perfeita** — cada elo ancorado no anterior — e
     * [EncryptedEvidenceVault.verificar] respondia [Integridade.Integra] sobre uma
     * ocorrência da qual o fim tinha sido removido. Quem quer esconder alguma coisa
     * numa gravação de rádio raramente edita o meio: apaga o final.
     *
     * O que segura agora é a âncora de fim ([AncoraDeFim]), selada com chave do
     * Keystore que o diretório não contém. Este teste roda o ataque inteiro e
     * confere que ele **é nomeado**, com os dois números.
     *
     * A asserção do meio é o contra-teste: a cadeia sobrevivente continua
     * consistente consigo mesma. Sem ela, este teste passaria também num mundo em
     * que a cadeia de hash tivesse voltado a ser suficiente — e não estaria
     * provando a âncora.
     */
    @Test
    fun truncarAOcorrencia_ehApontadoPelaAncoraDeFim() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(5) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        val original = (vault.finalize(handle) as Result.Success).value
        assertEquals(5, original.chain.size)
        assertNotNull("finalizar tem de selar a âncora no aparelho", original.ancora)
        assertEquals(Integridade.Integra, vault.verificar(handle))

        podarOFim(handle, segmentosRestantes = 3)

        val lido = Manifesto.ler(dirDe(handle))!!
        assertEquals("o manifesto agora declara uma gravação mais curta", 3, lido.cadeia.size)
        // Contra-teste: sem âncora não haveria o que reprovar aqui.
        assertTrue(
            "a cadeia truncada é internamente consistente",
            HashChain.verificar(
                lido.cadeia.map { segmentoEmClaro(handle, it.sequence) },
                lido.cadeia.map { it.sha256Hex },
            ) == -1,
        )

        assertEquals(
            Integridade.Truncada(seladosNoFim = 5, presentesNoManifesto = 3),
            vault.verificar(handle),
        )
    }

    /**
     * O atacante que percebe a linha `A` e a apaga junto. Não volta a íntegra:
     * volta a "ninguém provou que este é o fim". A regra é fechar por falta — sem
     * âncora válida, [Integridade.Integra] não é alcançável.
     */
    @Test
    fun apagarTambemALinhaDaAncora_naoVoltaAIntegra() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(5) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        vault.finalize(handle)

        podarOFim(handle, segmentosRestantes = 3)
        val arquivo = File(dirDe(handle), Manifesto.NOME)
        arquivo.writeText(
            arquivo.readLines().filterNot { it.startsWith("A\t") }.joinToString("\n", postfix = "\n"),
        )

        assertNull(Manifesto.ler(dirDe(handle))!!.ancora)
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.AUSENTE),
            vault.verificar(handle),
        )
    }

    /**
     * O atacante que reescreve o MAC em vez de apagar a linha. É o caso em que a
     * chave do Keystore é o que decide: sem ela não se produz um MAC que confira.
     */
    @Test
    fun reescreverOMacDaAncora_ehRecusado() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(3) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        vault.finalize(handle)

        val arquivo = File(dirDe(handle), Manifesto.NOME)
        arquivo.writeText(
            arquivo.readLines().joinToString("\n", postfix = "\n") { linha ->
                if (!linha.startsWith("A\t")) linha else {
                    val p = linha.split("\t")
                    "A\t${p[1]}\t${p[2]}\t${"%064x".format(0xDECAFBAD)}"
                }
            },
        )

        // A linha continua bem formada — o que não confere é a assinatura.
        assertNotNull(Manifesto.ler(dirDe(handle))!!.ancora)
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            vault.verificar(handle),
        )
    }

    /**
     * `versao=` é texto, e rebaixá-la seria a saída óbvia da exigência de âncora:
     * um manifesto v2 não tinha `A`, então declarar-se v2 dispensaria a prova.
     * Dispensa — e cai num veredito que também não é integridade.
     */
    @Test
    fun rebaixarAVersaoDoManifesto_naoEscapaDaExigencia() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(3) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        vault.finalize(handle)

        val arquivo = File(dirDe(handle), Manifesto.NOME)
        arquivo.writeText(arquivo.readText().replace("versao=3", "versao=2"))

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR),
            vault.verificar(handle),
        )
    }

    /**
     * Gravação interrompida pelo `kill -9`: manifesto sem `F`, logo sem `A`. Não é
     * adulteração, e também não é integridade — ninguém selou um fim. Sem este
     * estado próprio, uma gravação decapitada por morte de processo e uma decapitada
     * por alguém pareceriam a mesma coisa.
     */
    @Test
    fun gravacaoNaoFinalizada_naoEhIntegra() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(2) { i -> vault.append(handle, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
        // Sem `finalize`: os dois segmentos estão selados, o manifesto não.

        assertEquals(2, segmentosNoDisco(handle).size)
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA),
            vault.verificar(handle),
        )
    }

    /**
     * **O caminho da corregedoria.** Quem confere não é o processo que gravou: é
     * outro objeto, depois, sobre um diretório. Se a chave da âncora não
     * sobrevivesse à instância — se ela fosse gerada por cofre em vez de por app —
     * toda conferência posterior acusaria adulteração. Este teste é o que impede
     * essa regressão de passar despercebida.
     */
    @Test
    fun outraInstanciaDoCofre_confereAAncora() = runBlocking {
        val handle = cofre().let { v ->
            val h = abrir(v)
            repeat(3) { i -> v.append(h, ByteArray(BYTES_POR_JANELA) { (it + i).toByte() }) }
            v.finalize(h)
            h
        }
        assertEquals(Integridade.Integra, cofre().verificar(handle))
    }

    /** O ataque de truncamento, em uma linha: apaga segmentos e linhas `S` do fim. */
    private fun podarOFim(handle: RecordingHandle, segmentosRestantes: Int) {
        val dir = dirDe(handle)
        segmentosNoDisco(handle).drop(segmentosRestantes).forEach { it.delete() }
        val arquivo = File(dir, Manifesto.NOME)
        var restantes = segmentosRestantes
        val podadas = arquivo.readLines().filter { linha ->
            if (linha.startsWith("S\t")) (restantes-- > 0) else true
        }
        arquivo.writeText(podadas.joinToString("\n", postfix = "\n"))
    }

    /** Descriptografa um segmento — é o que a conferência faz para recalcular o hash. */
    private fun segmentoEmClaro(handle: RecordingHandle, seq: Int): ByteArray =
        EncryptedFile.Builder(
            ctx,
            File(dirDe(handle), "seg_%05d.enc".format(seq)),
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build().openFileInput().use { it.readBytes() }

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

    // ── Perícia: o inventário com veredito ────────────────────────────────────

    /**
     * **O caminho de perícia, que até 22/08 não existia.**
     *
     * `verificar()` e `Manifesto.ler()` tinham zero chamadores em `src/main`: o
     * produto **selava** a âncora de fim em produção e **conferia só em teste**, e
     * periciar exigia `adb`/root sobre o diretório privado. [periciar] é a função
     * que a tela chama, e estes testes são a prova de que ela responde sobre o
     * cofre real — com Keystore, com AES-GCM e com o disco.
     */
    @Test
    fun periciar_listaAsGravacoesComVeredito() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(2) { vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x11 }) }
        vault.finalize(handle)

        val registros = vault.periciar()
        assertEquals(1, registros.size)
        val r = registros.single()
        assertEquals(handle, r.handle)
        assertEquals(Manifesto.VERSAO_ATUAL, r.versao)
        assertEquals(2, r.segmentos)
        assertEquals(0, r.purgados)
        assertEquals((BYTES_POR_JANELA * 2).toLong(), r.bytesRetidos)
        assertFalse(r.emAndamento)
        assertNotNull(r.fimEpochMillis)
        assertEquals(Integridade.Integra, r.veredito)
    }

    /**
     * **O veredito da perícia é o MESMO de `verificar`.**
     *
     * Se divergirem, a tela passa a mostrar uma conferência que nenhum teste
     * exercita — que é o defeito de origem com um degrau a mais. Aqui o defeito
     * entra de verdade: um byte trocado no segundo segmento.
     */
    @Test
    fun periciar_acusaAdulteracao_comOMesmoVeredito() = runBlocking {
        val vault = cofre()
        val handle = abrir(vault)
        repeat(3) { vault.append(handle, ByteArray(BYTES_POR_JANELA) { 0x22 }) }
        vault.finalize(handle)

        val alvo = segmentosNoDisco(handle)[1]
        RandomAccessFile(alvo, "rw").use { f ->
            f.seek(f.length() - 1)
            val b = f.read()
            f.seek(f.length() - 1)
            f.write(b xor 0xFF)
        }

        val doInventario = vault.periciar().single().veredito
        assertEquals(Integridade.Quebrada(1), doInventario)
        assertEquals(vault.verificar(handle), doInventario)
    }

    /**
     * **Gravação em curso não é custódia interrompida.**
     *
     * As duas produzem `SemAncoraDeFim(NAO_FINALIZADA)` — não há linha `F` em
     * nenhuma das duas. Sem [RegistroDeCustodia.emAndamento], a tela de perícia
     * acusaria uma custódia rompida sobre a ocorrência que está acontecendo agora.
     */
    @Test
    fun periciar_distingueGravacaoEmCursoDeProcessoMorto() = runBlocking {
        val vault = cofre()
        val aberta = abrir(vault)
        vault.append(aberta, ByteArray(BYTES_POR_JANELA) { 0x33 })

        val r = vault.periciar().single()
        assertTrue("a gravação está aberta neste cofre", r.emAndamento)
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA),
            r.veredito,
        )

        // O MESMO diretório, lido por um cofre que não abriu a sessão: agora é
        // "processo morto antes de fechar", e o veredito é idêntico. A distinção
        // vem do flag, e é ele que a tela usa.
        assertFalse(cofre().periciar().single().emAndamento)
    }

    /** Diretório sem manifesto legível não vira linha "quebrada" — não há custódia ali. */
    @Test
    fun periciar_ignoraDiretorioSemManifesto() = runBlocking {
        File(File(ctx.filesDir, "evidence"), "GTA-9_000_1").mkdirs()
        assertTrue(cofre().periciar().isEmpty())
    }

    private companion object {
        /** Taxa fictícia: mantém a janela em 4 kB em vez de 320 kB. */
        const val TAXA_DE_BANCADA = 1_000
        const val JANELA_MS = 2_000

        /** `taxaHz × 2 bytes × janelaMs / 1000` — a mesma conta do cofre. */
        const val BYTES_POR_JANELA = TAXA_DE_BANCADA * 2 * JANELA_MS / 1_000
    }
}
