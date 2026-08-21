package com.claryon.glasses

import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A Regra Zero virando teste, em vez de comentário.**
 *
 * Toda assinatura do DAT usada aqui foi confirmada por `javap` no artefato antes
 * de virar código — e um comentário dizendo isso envelhece em silêncio na
 * próxima versão do SDK. Estes testes leem os enums **do próprio artefato** e
 * exigem que a tradução os cubra: subir o mwdat e ganhar um valor novo passa a
 * quebrar o build, que é o único lugar onde a divergência não passa despercebida.
 *
 * É o contra-teste do §6.3: com o defeito de volta — um valor do SDK sem
 * tradução, caindo no fallback — estes testes falham. Um teste que só afirmasse
 * "o enum tem 9 valores" passaria com o defeito.
 */
class TraducaoDoDatTest {

    @Test
    fun todoStreamErrorDoSdk_temTraducaoPropria() {
        // Se o SDK ganhar um valor novo, ele cai em DESCONHECIDO e o agente ouve
        // "Câmera falhou." em vez da causa. Não é crash — é perda de informação
        // exatamente no item que existe para não perder informação.
        for (doSdk in StreamError.entries) {
            val nosso = runCatching { enumValueOf<ErroDeStream>(doSdk.name) }
                .getOrDefault(ErroDeStream.DESCONHECIDO)
            assertNotEquals(
                "StreamError.${doSdk.name} não tem tradução em ErroDeStream — " +
                    "o mwdat mudou e a causa vira genérica",
                ErroDeStream.DESCONHECIDO,
                nosso,
            )
        }
    }

    @Test
    fun todoPermissionErrorDoSdk_temTraducaoPropria() {
        for (doSdk in PermissionError.entries) {
            val nosso = runCatching { enumValueOf<CausaSemPermissao>(doSdk.name) }
                .getOrDefault(CausaSemPermissao.DESCONHECIDA)
            assertNotEquals(
                "PermissionError.${doSdk.name} não tem tradução em CausaSemPermissao",
                CausaSemPermissao.DESCONHECIDA,
                nosso,
            )
        }
    }

    @Test
    fun todoStreamStateDoSdk_temTraducaoEmStreamStatus() {
        // `StreamStatus` é mapeado por nome com fallback STOPPED — e o fallback é
        // silencioso: um estado novo viraria "parado" sem ninguém notar, e agora
        // "parado" dispara `Camera.stop()`. O custo do descuido subiu.
        for (doSdk in StreamState.entries) {
            val nosso = runCatching { enumValueOf<StreamStatus>(doSdk.name) }.getOrNull()
            assertNotEquals(
                "StreamState.${doSdk.name} cairia no fallback STOPPED e pararia a câmera",
                null,
                nosso,
            )
        }
    }

    @Test
    fun oSdkAindaTemUmaSoPermissao_eEhCamera() {
        // O contrato de pedido recebe `Unit` porque não há o que escolher. Se o
        // DAT publicar MICROPHONE (a doc já menciona microfone e invocação por
        // voz como permissões futuras), a entrada `Unit` passa a esconder uma
        // escolha — e a de microfone toca na regra dura do produto.
        assertEquals(
            "o DAT ganhou permissões novas: revisar PermissaoDaCameraDoDat.Contrato",
            listOf("CAMERA"),
            Permission.entries.map { it.name },
        )
    }

    @Test
    fun oEstadoInicialDeUmStreamDoSdk_ehStopped() {
        // Não é curiosidade: é a razão de `jaSubiu` existir em `observeStream`.
        // `StreamImpl` nasce em STOPPED e `state` é StateFlow, então o coletor
        // recebe STOPPED ao assinar — antes de `start()`. Sem o guarda, tratar
        // STOPPED como terminal mataria a câmera recém-criada.
        //
        // O que se prova aqui é o pressuposto que torna o guarda necessário: que
        // STOPPED é um valor **alcançável antes** de qualquer transição, e não um
        // estado exclusivamente final.
        assertTrue(
            "STOPPED deixou de ser um valor de StreamState — revisar observeStream",
            StreamState.entries.any { it.name == "STOPPED" },
        )
        assertEquals(StreamState.STOPPED, StreamState.valueOf("STOPPED"))
    }

    @Test
    fun todaFraseFalada_cabeEmSetePalavras() {
        // Invariante do produto (CLAUDE.md §4). Estas frases saem pelo
        // alto-falante open-ear, no meio da rua; a oitava palavra é a que o
        // agente não ouve.
        for (e in ErroDeStream.entries) {
            assertTrue(
                "ErroDeStream.${e.name} passou de 7 palavras: \"${e.frase}\"",
                e.frase.split(" ").size <= 7,
            )
        }
        for (c in CausaSemPermissao.entries) {
            assertTrue(
                "CausaSemPermissao.${c.name} passou de 7 palavras: \"${c.frase}\"",
                c.frase.split(" ").size <= 7,
            )
        }
    }

    @Test
    fun causasDistintas_naoCompartilhamFrase() {
        // Duas causas com a mesma frase reintroduzem o defeito que o item
        // conserta: o agente ouve o mesmo aviso para "hastes dobradas" e para
        // "permissão negada" e não sabe o que fazer. Só o fallback pode repetir.
        val faladas = ErroDeStream.entries
            .filter { it != ErroDeStream.DESCONHECIDO }
            .map { it.frase }
        assertEquals("frases repetidas em ErroDeStream", faladas.size, faladas.toSet().size)
    }
}
