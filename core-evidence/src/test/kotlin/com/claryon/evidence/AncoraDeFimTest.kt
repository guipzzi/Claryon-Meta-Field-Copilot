package com.claryon.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A âncora de fim roda em JVM porque só a **chave** é do Keystore: a mensagem
 * canônica, o MAC e a decisão são `java.security` puro. É o que permite provar
 * aqui, em segundos, o ataque que antes só aparecia num teste instrumentado.
 *
 * ## O contra-teste, e por que ele é o ponto deste arquivo
 *
 * Cada teste de recusa abaixo **também** afirma que a cadeia de hash sozinha
 * aprovaria a mesma coisa ([cadeiaSobrevivente]). Sem isso o arquivo provaria
 * apenas que um HMAC confere quando confere — e passaria intacto se alguém
 * removesse a âncora amanhã, porque a cadeia truncada continua consistente.
 */
class AncoraDeFimTest {

    /** Chave do "aparelho". Em produção ela vive no Keystore e não existe em bytes. */
    private val doAparelho = assinadorCom("chave-legitima-do-keystore")

    /** Chave de quem tentou forjar. Qualquer coisa que não seja a de cima. */
    private val doAtacante = assinadorCom("chave-que-o-atacante-tem")

    private fun assinadorCom(segredo: String): AncoraDeFim.Assinador {
        val chave = SecretKeySpec(segredo.toByteArray(), AncoraDeFim.ALGORITMO)
        return AncoraDeFim.Assinador { mensagem ->
            Mac.getInstance(AncoraDeFim.ALGORITMO).apply { init(chave) }.doFinal(mensagem)
        }
    }

    private fun declaracao(
        versao: Int = Manifesto.VERSAO_ATUAL,
        sampleRateHz: Int = 16_000,
        motivoDoFim: String? = null,
        purgados: List<Purga> = emptyList(),
    ) = AncoraDeFim.Declaracao(
        handleId = "GTA-3_007_1700000000000",
        versao = versao,
        sampleRateHz = sampleRateHz,
        formato = OccurrenceContext.FORMATO_PCM_S16LE_MONO,
        janelaMs = 10_000,
        fimEpochMillis = 1_700_000_100_000,
        motivoDoFim = motivoDoFim,
        purgados = purgados,
    )

    /** Cadeia real de [n] segmentos, encadeada como o cofre encadeia. */
    private fun cadeiaDe(n: Int): List<ChunkHash> {
        val out = ArrayList<ChunkHash>()
        var prev: String? = null
        for (i in 0 until n) {
            val h = HashChain.sha256Hex(bytesDe(i), prev)
            out.add(ChunkHash(i, h, prev, bytesDe(i).size))
            prev = h
        }
        return out
    }

    private fun bytesDe(i: Int) = ByteArray(64) { (it + i).toByte() }

    /**
     * O contra-teste: [HashChain] sozinha aprova esta cadeia?
     *
     * Truncar no fim deixa exatamente isto — uma cadeia menor e **perfeita**. Se um
     * dia esta função passar a devolver `false` para a cadeia truncada, a âncora
     * deixou de ser o que segura o ataque, e o KDoc de [AncoraDeFim] precisa mudar.
     */
    private fun cadeiaSobrevivente(cadeia: List<ChunkHash>): Boolean =
        HashChain.verificar(cadeia.map { bytesDe(it.sequence) }, cadeia.map { it.sha256Hex }) == -1

    private fun selar(
        declaracao: AncoraDeFim.Declaracao,
        cadeia: List<ChunkHash>,
        assinador: AncoraDeFim.Assinador = doAparelho,
    ) = AncoraDeFim.selar(assinador, declaracao, cadeia.size, cadeia.lastOrNull()?.sha256Hex)!!

    // ── O caminho feliz, que existe para dar sentido às recusas ───────────────

    @Test
    fun ancoraDoProprioAparelho_deixaPassar() {
        val cadeia = cadeiaDe(5)
        val ancora = selar(declaracao(), cadeia)
        assertNull(
            "âncora legítima sobre a cadeia que ela selou é o único caso que passa",
            AncoraDeFim.conferir(doAparelho, declaracao(), cadeia, finalizada = true, ancora),
        )
    }

    // ── O ataque ──────────────────────────────────────────────────────────────

    /**
     * **O defeito que motivou este arquivo.** Apagar os dois últimos segmentos e as
     * duas últimas linhas `S` deixa uma cadeia que confere consigo mesma — e antes
     * da âncora isso respondia [Integridade.Integra].
     */
    @Test
    fun truncarOFim_ehApontadoComOsDoisNumeros() {
        val completa = cadeiaDe(5)
        val ancora = selar(declaracao(), completa)

        val truncada = completa.dropLast(2)
        // Sem a âncora não haveria o que reprovar: a cadeia curta é perfeita.
        assertTrue("a cadeia truncada é internamente consistente", cadeiaSobrevivente(truncada))

        assertEquals(
            Integridade.Truncada(seladosNoFim = 5, presentesNoManifesto = 3),
            AncoraDeFim.conferir(doAparelho, declaracao(), truncada, finalizada = true, ancora),
        )
    }

    /**
     * O atacante que percebe a linha `A` e a apaga junto. Não volta a íntegra:
     * volta a "ninguém provou que este é o fim", que é um veredito diferente e
     * visível.
     */
    @Test
    fun apagarALinhaDaAncora_naoRetornaAIntegridade() {
        val truncada = cadeiaDe(5).dropLast(2)
        assertTrue("a cadeia truncada é internamente consistente", cadeiaSobrevivente(truncada))

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.AUSENTE),
            AncoraDeFim.conferir(doAparelho, declaracao(), truncada, finalizada = true, ancora = null),
        )
    }

    /** O atacante que sela a própria âncora sobre a cadeia curta, com a chave que tem. */
    @Test
    fun forjarAAncoraComOutraChave_ehRecusado() {
        val truncada = cadeiaDe(5).dropLast(2)
        val forjada = selar(declaracao(), truncada, assinador = doAtacante)

        // A âncora forjada é internamente coerente: declara 3 e a cadeia tem 3.
        assertEquals(3, forjada.segmentos)
        assertEquals(truncada.last().sha256Hex, forjada.ultimoHashHex)

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            AncoraDeFim.conferir(doAparelho, declaracao(), truncada, finalizada = true, forjada),
        )
    }

    /**
     * `versao=` é texto no manifesto, e rebaixá-lo seria a saída óbvia da exigência
     * de âncora. Ela leva a um veredito não-íntegro, não a [Integridade.Integra] —
     * que é o que "fechar por falta" significa.
     */
    @Test
    fun rebaixarAVersao_naoEscapaDaExigencia() {
        val cadeia = cadeiaDe(5)
        val ancora = selar(declaracao(), cadeia)

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR),
            AncoraDeFim.conferir(doAparelho, declaracao(versao = 2), cadeia, finalizada = true, ancora),
        )
    }

    /**
     * Forjar uma linha `P` explicaria sozinha um segmento apagado — o cofre reporta
     * purga como [Integridade.ExpurgadaPorPolitica], que não é fraude. A purga entra
     * no MAC exatamente por isso.
     */
    @Test
    fun purgaForjada_quebraOMac() {
        val cadeia = cadeiaDe(5)
        val ancora = selar(declaracao(), cadeia)

        val comPurgaInventada = declaracao(
            purgados = listOf(Purga(4, 1_700_000_099_000, "RETENCAO_CONFIGURADA")),
        )
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            AncoraDeFim.conferir(doAparelho, comPurgaInventada, cadeia, finalizada = true, ancora),
        )
    }

    /**
     * Adulteração sem apagar nada: `taxaHz=16000` vira `8000` e os mesmos bytes
     * reproduzem outra gravação — mais lenta, mais grave, e com o dobro da duração
     * declarada.
     */
    @Test
    fun reescreverATaxaDeAmostragem_quebraOMac() {
        val cadeia = cadeiaDe(3)
        val ancora = selar(declaracao(sampleRateHz = 16_000), cadeia)

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            AncoraDeFim.conferir(
                doAparelho, declaracao(sampleRateHz = 8_000), cadeia, finalizada = true, ancora,
            ),
        )
    }

    /** Reescrever o hash da última linha `S` sem poder recalcular o segmento cifrado. */
    @Test
    fun ultimoHashDivergente_apontaOFimDaCadeia() {
        val cadeia = cadeiaDe(4)
        val ancora = selar(declaracao(), cadeia)
        val mexida = cadeia.dropLast(1) + cadeia.last().copy(sha256Hex = "%064x".format(99))

        assertEquals(
            Integridade.Quebrada(3),
            AncoraDeFim.conferir(doAparelho, declaracao(), mexida, finalizada = true, ancora),
        )
    }

    // ── Estados que não são ataque, e ainda assim não são integridade ─────────

    @Test
    fun gravacaoNaoFinalizada_naoEhIntegra() {
        val cadeia = cadeiaDe(2)
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA),
            AncoraDeFim.conferir(doAparelho, declaracao(), cadeia, finalizada = false, ancora = null),
        )
    }

    /**
     * Keystore mudo diz que **esta conferência** não pôde ser feita — não que a
     * gravação foi adulterada. Confundir os dois faria um perito ler fraude onde
     * houve aparelho errado.
     */
    @Test
    fun chaveIndisponivel_temVereditoProprio() {
        val cadeia = cadeiaDe(2)
        val ancora = selar(declaracao(), cadeia)
        val mudo = AncoraDeFim.Assinador { null }

        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL),
            AncoraDeFim.conferir(mudo, declaracao(), cadeia, finalizada = true, ancora),
        )
        assertNull("e nem sela", AncoraDeFim.selar(mudo, declaracao(), 2, cadeia.last().sha256Hex))
    }

    // ── Canonicalização ───────────────────────────────────────────────────────

    /**
     * O defeito que reprovou a primeira versão de [AncoraDeFim.mensagem]: purgas
     * unidas por `;` e campos por `,` deixavam **uma** purga de motivo `A;3,4,B`
     * produzir a mesma cadeia de caracteres que **duas** purgas. Uma âncora selada
     * sobre uma valeria para a outra — e a segunda "explica" um segmento a mais.
     */
    @Test
    fun motivoDePurgaComSeparador_naoImpersonaDuasPurgas() {
        val uma = declaracao(purgados = listOf(Purga(1, 2, "A;3,4,B")))
        val duas = declaracao(purgados = listOf(Purga(1, 2, "A"), Purga(3, 4, "B")))

        assertNotEquals(
            String(AncoraDeFim.mensagem(uma, 5, null)),
            String(AncoraDeFim.mensagem(duas, 5, null)),
        )
        val ancora = selar(uma, cadeiaDe(5))
        assertEquals(
            Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.INVALIDA),
            AncoraDeFim.conferir(doAparelho, duas, cadeiaDe(5), finalizada = true, ancora),
        )
    }

    /** Duas gravações diferentes não podem ter a mesma mensagem assinada. */
    @Test
    fun mensagem_distingueOQueEhDiferente() {
        val base = AncoraDeFim.mensagem(declaracao(), 5, "abc")
        val variacoes = listOf(
            AncoraDeFim.mensagem(declaracao(), 6, "abc"),
            AncoraDeFim.mensagem(declaracao(), 5, "abd"),
            AncoraDeFim.mensagem(declaracao(motivoDoFim = "EVID_SEM_ESPACO"), 5, "abc"),
            AncoraDeFim.mensagem(declaracao(sampleRateHz = 8_000), 5, "abc"),
            AncoraDeFim.mensagem(declaracao(versao = 4), 5, "abc"),
        )
        for (v in variacoes) assertNotEquals(String(base), String(v))
    }
}
