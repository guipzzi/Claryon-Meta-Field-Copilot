package com.claryon.field.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **A marca de corte sobrevive à morte do processo — e não vira lixo.**
 *
 * O conjunto era `mutableSetOf` de processo, e o serviço é `START_STICKY`: o sistema
 * recria, e a fala cortada voltava a parecer inteira. Estes travam as duas metades do
 * conserto — a que persiste e a que **poda**, porque marca acumulada por turnos vira
 * lixo que ninguém limpa.
 */
class CortesConhecidosTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private fun arquivo() = File(pasta.root, "cortes.tsv")

    private class Relogio(var agora: Long = 1_000_000L)

    private fun comRelogio(r: Relogio, f: File = arquivo()) =
        CortesConhecidos(f, agoraMs = { r.agora })

    @Test
    fun oCorteSobreviveAMorteDoProcesso() {
        // O teste que descreve o defeito inteiro: marca, "morre", nasce de novo.
        val f = arquivo()
        val antes = CortesConhecidos(f)
        antes.marcar("tx-1")
        antes.gravar()

        val depois = CortesConhecidos(f)
        depois.carregar()

        assertTrue("a marca não sobreviveu ao processo novo", "tx-1" in depois)
    }

    @Test
    fun processoNovoSemArquivo_naoQuebra_eNaoInventaMarca() {
        // Primeiro turno do aparelho: não há arquivo. Tem de nascer vazio em vez de
        // lançar — é uma dica de desenho, não pode derrubar o rádio.
        val novo = CortesConhecidos(File(pasta.root, "nunca_existiu.tsv"))
        novo.carregar()
        assertTrue(novo.ids().isEmpty())
        assertFalse("tx-1" in novo)
    }

    @Test
    fun marcarRepetido_devolveFalso_paraNaoGravarAToa() {
        // O retorno existe só para poupar E/S; se alguém trocar por `Unit`, o
        // `RadioViewModel` passa a gravar em disco a cada evento reentregue.
        val c = CortesConhecidos(arquivo())
        assertTrue("a primeira marcação tem de ser nova", c.marcar("tx-1"))
        assertFalse("a segunda marcação não é nova", c.marcar("tx-1"))
    }

    @Test
    fun oQueVenceuAValidade_ehPodadoNaLeitura() {
        // Contra-teste de poda: dois ids, um dentro e um fora da validade. Asserir
        // só que o velho sumiu passaria com uma poda que apagasse tudo.
        val r = Relogio()
        val f = arquivo()
        val escrita = comRelogio(r, f)
        escrita.marcar("velho")
        r.agora += CortesConhecidos.VALIDADE_MS - 1_000  // quase vencendo
        escrita.marcar("novo")
        escrita.gravar()

        // Avança o suficiente para vencer só o primeiro.
        r.agora += 2_000
        val leitura = comRelogio(r, f)
        leitura.carregar()

        assertFalse("o vencido sobreviveu à poda", "velho" in leitura)
        assertTrue("a poda levou junto uma marca válida", "novo" in leitura)
    }

    @Test
    fun oTetoLimitaOCrescimento_eDescartaOMaisAntigo() {
        // A rede de segurança para relógio que anda para trás. Sem ela o arquivo
        // cresce sem limite dentro da validade.
        val c = CortesConhecidos(arquivo())
        repeat(CortesConhecidos.TETO + 50) { c.marcar("tx-$it") }

        assertEquals(CortesConhecidos.TETO, c.ids().size)
        assertFalse("o mais antigo devia ter saído", "tx-0" in c)
        assertTrue(
            "o mais recente devia ter ficado",
            "tx-${CortesConhecidos.TETO + 49}" in c,
        )
    }

    @Test
    fun linhaCorrompida_custaUmId_eNaoOArquivoInteiro() {
        // Escrita interrompida por morte do processo produz linha pela metade. Um
        // parser estrito perderia o turno inteiro de marcas por causa dela.
        val f = arquivo()
        f.writeText("tx-bom\t1000000\nlixo-sem-carimbo\ntx-outro\tnao-numero\ntx-bom2\t1000000")
        val c = comRelogio(Relogio(1_000_000L), f)
        c.carregar()

        assertTrue("a linha boa foi descartada junto", "tx-bom" in c)
        assertTrue("a linha boa depois da corrompida foi perdida", "tx-bom2" in c)
        assertEquals("linha malformada virou id", 2, c.ids().size)
    }

    @Test
    fun oCarimboSobreviveAoIdaEVolta_entaoAValidadeNaoReiniciaSozinha() {
        // Se `gravar` perdesse o carimbo e `carregar` usasse "agora", toda marca
        // rejuvenesceria a cada abertura do app e a poda nunca aconteceria.
        val r = Relogio()
        val f = arquivo()
        comRelogio(r, f).apply { marcar("tx-1"); gravar() }

        r.agora += CortesConhecidos.VALIDADE_MS + 1
        val depois = comRelogio(r, f)
        depois.carregar()

        assertFalse("o carimbo foi reiniciado na leitura", "tx-1" in depois)
    }
}
