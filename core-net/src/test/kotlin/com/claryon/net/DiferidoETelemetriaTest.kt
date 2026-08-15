package com.claryon.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Modo diferido: o que acontece quando a rede não está lá.
 *
 * A rede morre exatamente onde o rádio portátil já falha. A resposta é
 * *store-and-forward* com carimbo de origem — e a distinção que **não pode se
 * perder** é entre isto e o pré-roll: aqui grava-se fala que o agente decidiu
 * transmitir; o pré-roll vive em RAM e some se o botão não for apertado.
 */
class ArquivoDeFalasDiferidasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun arquivo() = ArquivoDeFalasDiferidas(tmp.newFolder())
    private fun quadros(n: Int, marca: Byte = 7) = List(n) { ByteArray(30) { marca } }

    @Test
    fun gravaERecupera_comQuadrosIntactos() {
        val a = arquivo()
        val original = quadros(50)

        val fala = a.gravar("tx-1", origemMs = 1_000, PrioridadeTransmissao.P2_APOIO, original)
        assertTrue("não gravou", fala != null)
        assertEquals(50, fala!!.quadros)

        val lidos = a.quadrosDe(fala)
        assertEquals(50, lidos.size)
        assertTrue("os bytes do áudio não sobreviveram", lidos.all { it.contentEquals(original[0]) })
    }

    @Test
    fun oCarimboDeOrigemSobrevive() {
        // Sem ele, uma fala de meia hora atrás chegaria soando como se fosse
        // agora — e agir sobre informação velha achando que é nova é pior que
        // não receber.
        val a = arquivo()
        a.gravar("tx-1", origemMs = 1_691_000_000_000, PrioridadeTransmissao.P1_EMERGENCIA, quadros(5))

        val pendente = a.pendentes().single()
        assertEquals(1_691_000_000_000, pendente.origemMs)
        assertEquals(PrioridadeTransmissao.P1_EMERGENCIA, pendente.prioridade)
    }

    @Test
    fun sobreviveAMorteDoProcesso() {
        // O ponto inteiro do modo diferido: outra instância, mesmo diretório.
        val dir = tmp.newFolder()
        ArquivoDeFalasDiferidas(dir).gravar("tx-1", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(10))

        val depois = ArquivoDeFalasDiferidas(dir).pendentes()
        assertEquals(1, depois.size)
        assertEquals(10, depois.single().quadros)
    }

    @Test
    fun ordemEhCronologica_naMaisAntigaPrimeiro() {
        // Ordem de fala é ordem de sentido: "estou indo" antes de "cheguei".
        val a = arquivo()
        a.gravar("tx-c", 3_000, PrioridadeTransmissao.P2_APOIO, quadros(3))
        a.gravar("tx-a", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(3))
        a.gravar("tx-b", 2_000, PrioridadeTransmissao.P2_APOIO, quadros(3))

        assertEquals(listOf("tx-a", "tx-b", "tx-c"), a.pendentes().map { it.transmissaoId })
    }

    @Test
    fun falaVazia_naoEhGravada() {
        // PTT apertado e solto sem falar não vira arquivo.
        assertNull(arquivo().gravar("tx-1", 1_000, PrioridadeTransmissao.P2_APOIO, emptyList()))
    }

    @Test
    fun removerTiraDaFila() {
        val a = arquivo()
        val fala = a.gravar("tx-1", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(5))!!
        a.remover(fala)
        assertEquals(0, a.tamanho())
    }

    // ── Cenários adversos ─────────────────────────────────────────────────────

    @Test
    fun arquivoCorrompido_ehIgnoradoEPodado() {
        // Sem isto, o drenador tentaria enviá-lo para sempre, gastando bateria em
        // retentativas que nunca teriam o que enviar.
        val dir = tmp.newFolder()
        val a = ArquivoDeFalasDiferidas(dir)
        a.gravar("tx-bom", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(5))
        java.io.File(dir, "lixo.fala").writeText("isto não é uma fala")

        assertEquals("o corrompido não pode aparecer como pendente", 1, a.tamanho())
        assertTrue("o corrompido deveria ser podado", a.podar(agoraMs = 2_000) >= 1)
    }

    @Test
    fun arquivoTruncado_naoDerrubaALeitura() {
        // Processo morto no meio da escrita. A escrita é atômica, mas o disco
        // pode encher — e ler um arquivo pela metade não pode explodir.
        val dir = tmp.newFolder()
        val a = ArquivoDeFalasDiferidas(dir)
        val fala = a.gravar("tx-1", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(50))!!

        val bytes = fala.arquivo.readBytes()
        fala.arquivo.writeBytes(bytes.copyOf(bytes.size / 3))

        val lidos = a.quadrosDe(fala)
        assertTrue("leitura de arquivo truncado deveria devolver o que deu", lidos.size < 50)
    }

    @Test
    fun falaVelhaDemais_ehPodada() {
        // Fala de ontem chegando hoje não é informação operacional, é ruído — e
        // guardar voz indefinidamente contraria a política de retenção.
        val a = arquivo()
        val seisHoras = 6 * 60 * 60 * 1000L
        val agora = 100_000_000L
        // "antiga" tem 7 h de idade; "recente" tem 1 h.
        a.gravar("antiga", agora - 7 * 60 * 60 * 1000L, PrioridadeTransmissao.P2_APOIO, quadros(3))
        a.gravar("recente", agora - 1 * 60 * 60 * 1000L, PrioridadeTransmissao.P2_APOIO, quadros(3))

        a.podar(agoraMs = agora, validadeMs = seisHoras)

        assertEquals(listOf("recente"), a.pendentes().map { it.transmissaoId })
    }

    @Test
    fun muitasFalasAcumuladas_todasRecuperaveis() {
        // Turno inteiro sem cobertura: 60 falas de 30 s.
        val a = arquivo()
        repeat(60) { a.gravar("tx-$it", it * 1_000L, PrioridadeTransmissao.P2_APOIO, quadros(1500)) }

        assertEquals(60, a.tamanho())
        assertEquals(1500, a.quadrosDe(a.pendentes().first()).size)
    }

    @Test
    fun diretorioInexistente_ehCriado() {
        val dir = java.io.File(tmp.newFolder(), "ainda/nao/existe")
        val a = ArquivoDeFalasDiferidas(dir)
        assertTrue(a.gravar("tx-1", 1_000, PrioridadeTransmissao.P2_APOIO, quadros(3)) != null)
    }
}

class TelemetriaDoRadioTest {

    @Test
    fun percentisRefletemAsAmostras() {
        val t = TelemetriaDoRadio()
        (1..100).forEach { t.registrar(TelemetriaDoRadio.Metrica.CODIFICACAO, it.toLong()) }

        val m = t.medicao(TelemetriaDoRadio.Metrica.CODIFICACAO)!!
        assertEquals(100, m.amostras)
        assertTrue("p50 fora do esperado: ${m.p50}", m.p50 in 45..55)
        assertTrue("p95 fora do esperado: ${m.p95}", m.p95 in 90..100)
        assertEquals(100, m.pior)
    }

    @Test
    fun semAmostras_naoInventaNumero() {
        // Devolver zero seria pior que nada: pareceria uma medição excelente.
        assertNull(TelemetriaDoRadio().medicao(TelemetriaDoRadio.Metrica.CONCESSAO_DE_CANAL))
    }

    @Test
    fun janelaDeslizante_naoCresceSemLimite() {
        // Um turno inteiro de medições não cabe na memória, e percentil de janela
        // recente é mais útil: a rede de agora importa mais que a de 3 h atrás.
        val t = TelemetriaDoRadio(capacidade = 50)
        (1..5_000).forEach { t.registrar(TelemetriaDoRadio.Metrica.CODIFICACAO, it.toLong()) }

        val m = t.medicao(TelemetriaDoRadio.Metrica.CODIFICACAO)!!
        assertEquals(50, m.amostras)
        assertTrue("deveria refletir as amostras recentes", m.p50 > 4_900)
    }

    @Test
    fun contadoresSomam() {
        val t = TelemetriaDoRadio()
        t.contar(TelemetriaDoRadio.QUADROS_ENVIADOS, 250)
        t.contar(TelemetriaDoRadio.QUADROS_ENVIADOS, 100)
        t.contar(TelemetriaDoRadio.CANAL_NEGADO)

        assertEquals(350, t.contador(TelemetriaDoRadio.QUADROS_ENVIADOS))
        assertEquals(1, t.contador(TelemetriaDoRadio.CANAL_NEGADO))
        assertEquals(0, t.contador("nunca_aconteceu"))
    }

    @Test
    fun relatorioTrazAPerdaMedida_eDeclaraOQueNaoMede() {
        val t = TelemetriaDoRadio()
        t.contar(TelemetriaDoRadio.QUADROS_RECEBIDOS, 95)
        t.contar(TelemetriaDoRadio.QUADROS_PERDIDOS, 5)
        t.registrar(TelemetriaDoRadio.Metrica.TOQUE_ATE_PRIMEIRO_QUADRO, 80)

        val r = t.relatorio()
        // O separador decimal segue o locale (em pt-BR sai "5,0%"), e isso esta
        // certo para um relatorio lido pela equipe — o teste e que nao pode
        // presumir ponto.
        assertTrue("faltou a perda medida em: " + r, r.contains("5,0%") || r.contains("5.0%"))
        // A ressalva não é decoração: publicar medição interna como se fosse
        // boca a ouvido seria um número otimista num documento técnico.
        assertTrue("o relatório precisa declarar o que NÃO mede", r.contains("não incluem Bluetooth"))
    }

    @Test
    fun zerar_limpaTudo() {
        val t = TelemetriaDoRadio()
        t.registrar(TelemetriaDoRadio.Metrica.CODIFICACAO, 10)
        t.contar(TelemetriaDoRadio.QUADROS_ENVIADOS)
        t.zerar()

        assertNull(t.medicao(TelemetriaDoRadio.Metrica.CODIFICACAO))
        assertEquals(0, t.contador(TelemetriaDoRadio.QUADROS_ENVIADOS))
    }
}
