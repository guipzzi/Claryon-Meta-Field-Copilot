package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **"guarnição N na escuta" — a frase que abre canal sem tocar em nada.**
 *
 * O roadmap fixa duas propriedades como invariantes, não preferências, e as duas
 * existem pelo mesmo motivo: "na escuta" é vocabulário CORRENTE de rádio policial,
 * dito o tempo todo. Um casamento frouxo faria o produto abrir transmissão ouvindo
 * o próprio tráfego da guarnição — e difundir para todo mundo.
 *
 * 1. O **número** torna a frase específica e a tira do vocabulário corrente.
 * 2. O casamento é **INTEGRAL**: a frase inteira e nada mais.
 *
 * A metade "recusa" destes testes é a que importa. Um roteador que só acertasse os
 * positivos e aceitasse tudo que CONTÉM a frase passaria em metade deles.
 */
class AbrirTransmissaoPorVozTest {

    private val router = DeterministicIntentRouter()

    private fun rotulo(fala: String): String? =
        (router.route(fala) as? Intent.AbrirTransmissao)?.rotuloFalado

    // ── o que abre ────────────────────────────────────────────────────────────

    @Test
    fun aFraseExata_abreTransmissao() {
        assertEquals("guarnicao 3", rotulo("Guarnição 3 na escuta"))
    }

    @Test
    fun oAcentoEAPontuacao_naoAtrapalham() {
        assertEquals("guarnicao 3", rotulo("guarnição 3 na escuta."))
        assertEquals("guarnicao 3", rotulo("GUARNIÇÃO 3 NA ESCUTA"))
    }

    /** O rótulo carrega a palavra "guarnição": é assim que ele está no cadastro. */
    @Test
    fun oRotuloIncluiAPalavraGuarnicao_comoNoCadastro() {
        assertTrue(
            "sem o prefixo, a comparação contra `rotulo_falado` falha em toda linha",
            rotulo("guarnição 3 na escuta")!!.startsWith("guarnicao"),
        )
    }

    @Test
    fun rotuloPorExtenso_tambemAbre() {
        assertEquals("guarnicao tres", rotulo("guarnição três na escuta"))
    }

    // ── o que NÃO abre, que é a metade que protege ────────────────────────────

    /**
     * **A invariante nomeada no roadmap.** Sem o número a frase é rádio comum.
     */
    @Test
    fun naEscuta_SOZINHO_naoAbreNada() {
        assertTrue(
            "\"na escuta\" sozinho abriu canal — é o que a guarnição diz o dia todo",
            router.route("na escuta") !is Intent.AbrirTransmissao,
        )
    }

    /**
     * **Casamento integral.** Estas são falas que CONTÊM o comando e não são o
     * comando. Um `contains` — que é como o resto do roteador casa verbos — aceita
     * as três.
     */
    @Test
    fun falaQueCONTEMOComando_naoEOComando() {
        val conversas = listOf(
            "diz pro pessoal que a guarnição 3 na escuta",
            "guarnição 3 na escuta câmbio",
            "eu falei guarnição 3 na escuta ontem",
            "avisa a guarnição 3 na escuta agora",
        )
        val aceitas = conversas.filter { router.route(it) is Intent.AbrirTransmissao }
        assertTrue(
            "abriu canal com fala que só CONTÉM a frase: $aceitas. Com o casamento " +
                "frouxo, conversa de rádio vira transmissão para a guarnição inteira",
            aceitas.isEmpty(),
        )
    }

    @Test
    fun semAFraseFinal_naoAbre() {
        assertTrue(router.route("guarnição 3") !is Intent.AbrirTransmissao)
        assertTrue(router.route("guarnição 3 escuta") !is Intent.AbrirTransmissao)
    }

    @Test
    fun trocarDeGrupo_continuaSendoTrocarDeGrupo() {
        assertTrue(
            "a frase de abertura roubou o comando de troca",
            router.route("claryon mudar para guarnição 5") is Intent.TrocarDeGrupo,
        )
    }

    /**
     * O detector acústico **nunca** abre canal: quem abre é esta intenção, vinda da
     * transcrição. Este teste fixa o corolário — o roteador não tem caminho que
     * produza `AbrirTransmissao` sem a frase inteira ter sido transcrita.
     */
    @Test
    fun aPalavraDeAtivacaoSOZINHA_naoAbreCanal() {
        for (fala in listOf("claryon", "hey claryon", "claryon na escuta")) {
            assertTrue(
                "\"$fala\" abriu canal — o KWS não pode decidir transmissão",
                router.route(fala) !is Intent.AbrirTransmissao,
            )
        }
    }
}
