package com.claryon.field.permissoes

import android.Manifest
import com.claryon.common.LaconicityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O catálogo de permissões.
 *
 * O que estes testes protegem é a regra que vale para o produto inteiro: **falha
 * nunca é silêncio**. Permissão negada é a falha mais fácil de esconder num app
 * cuja saída é áudio — o agente fala, nada acontece, e ele conclui que o produto
 * é ruim, não que faltou um toque numa caixa de diálogo.
 */
class PermissoesEssenciaisTest {

    private val todas = PermissoesEssenciais.catalogo().map { it.permissao }.toSet()

    // ── Estado ────────────────────────────────────────────────────────────────

    @Test
    fun tudoConcedido_naoReclamaDeNada() {
        val e = PermissoesEssenciais.avaliar(todas)
        assertTrue(e.tudoConcedido)
        assertTrue(e.podeOperar)
        assertTrue(e.capacidadesPerdidas.isEmpty())
        assertNull("não pode falar nada quando está tudo certo", PermissoesEssenciais.avisoFalado(e))
    }

    @Test
    fun semMicrofone_oAppNaoFingeQueOpera() {
        // É a única bloqueante: sem microfone não há entrada nem saída de voz,
        // que é o produto inteiro.
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.RECORD_AUDIO)
        assertFalse(e.podeOperar)
        assertTrue(Capacidade.OUVIR_COMANDOS in e.capacidadesPerdidas)
        assertTrue(Capacidade.RADIO_PTT in e.capacidadesPerdidas)
        assertTrue(Capacidade.GRAVAR_EVIDENCIA in e.capacidadesPerdidas)
    }

    @Test
    fun semCamera_oCicloDeVozContinuaInteiro() {
        // Degradação parcial não pode virar recusa total: sem câmera o app perde
        // a leitura de placa e mais nada.
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.CAMERA)
        assertTrue("câmera não é bloqueante", e.podeOperar)
        assertEquals(listOf(Capacidade.VER_PLACA), e.capacidadesPerdidas)
    }

    @Test
    fun semLocalizacao_oRadioAindaFala() {
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(e.podeOperar)
        assertTrue(Capacidade.POSICAO_NO_MAPA in e.capacidadesPerdidas)
        assertFalse("PTT não depende de GPS", Capacidade.RADIO_PTT in e.capacidadesPerdidas)
    }

    @Test
    fun semBluetooth_operaComFoneComum() {
        // Decisão de produto: com fone comum o ciclo roda inteiro. Perde-se o
        // beamforming — o que muda quem é gravado, e é dito — mas o app serve.
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(e.podeOperar)
        assertEquals(listOf(Capacidade.CONECTAR_OCULOS), e.capacidadesPerdidas)
    }

    @Test
    fun nadaConcedido_naoQuebra() {
        val e = PermissoesEssenciais.avaliar(emptySet())
        assertFalse(e.podeOperar)
        assertEquals(PermissoesEssenciais.catalogo().size, e.faltando.size)
        assertNotNull(PermissoesEssenciais.avisoFalado(e))
    }

    @Test
    fun permissaoDesconhecidaConcedida_naoConfunde() {
        // O sistema devolve permissões que não pedimos. Não podem ser lidas como
        // se fossem as nossas.
        val e = PermissoesEssenciais.avaliar(todas + "android.permission.NFC")
        assertTrue(e.tudoConcedido)
    }

    // ── Aviso falado ──────────────────────────────────────────────────────────

    @Test
    fun oAvisoRespeitaOProtocoloDeLaconicidade() {
        // A mesma régua de sete palavras do resto do produto. Uma lista falada de
        // negativas num alto-falante open-ear vira ruído que o agente ignora.
        val cenarios = PermissoesEssenciais.catalogo().map { todas - it.permissao } +
            listOf(emptySet(), todas - Manifest.permission.CAMERA - Manifest.permission.ACCESS_FINE_LOCATION)

        for (concedidas in cenarios) {
            val aviso = PermissoesEssenciais.avisoFalado(PermissoesEssenciais.avaliar(concedidas))
            assertNotNull("faltando permissão e o agente não ouve nada", aviso)
            assertTrue(
                "excede ${LaconicityPolicy.MAX_WORDS} palavras: \"$aviso\"",
                LaconicityPolicy.isWithinLimit(aviso!!),
            )
            assertFalse("tem cortesia: \"$aviso\"", LaconicityPolicy.hasCourtesy(aviso))
        }
    }

    @Test
    fun aBloqueanteDominaOAviso() {
        // Faltando microfone e câmera, dizer "sem câmera, não leio placas" seria
        // tecnicamente verdade e operacionalmente uma mentira por omissão.
        val e = PermissoesEssenciais.avaliar(
            todas - Manifest.permission.RECORD_AUDIO - Manifest.permission.CAMERA,
        )
        assertEquals(PermissoesEssenciais.MICROFONE.semEla, PermissoesEssenciais.avisoFalado(e))
    }

    @Test
    fun nenhumAvisoUsaJargaoDeAndroid() {
        // "RECORD_AUDIO negada" não é frase que um agente de rua entenda. O que
        // se procura é o identificador da plataforma vazando para o texto —
        // MAIÚSCULA_COM_SUBLINHADO — e não maiúscula qualquer, que aparece
        // legitimamente em "Claryon" e no início da frase.
        val identificador = Regex("[A-Z]{3,}(_[A-Z]+)*")
        for (p in PermissoesEssenciais.TODAS) {
            assertFalse(p.semEla, p.semEla.contains("permission", ignoreCase = true))
            assertFalse(p.semEla, identificador.containsMatchIn(p.semEla))
            assertFalse(p.porQue, identificador.containsMatchIn(p.porQue))
        }
    }

    // ── Recuperação ───────────────────────────────────────────────────────────

    @Test
    fun aindaDaParaPedir_pedeSemMandarParaOsAjustes() {
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.CAMERA)
        val r = PermissoesEssenciais.recuperacao(e) { true }
        assertTrue(r is Recuperacao.Pedir)
        assertEquals(listOf(Manifest.permission.CAMERA), (r as Recuperacao.Pedir).permissoes)
    }

    @Test
    fun negadaEmDefinitivo_mandaParaOsAjustes() {
        // Sem isto, o botão "permitir" abriria um diálogo que o sistema não mostra
        // mais — e para o agente o sintoma é "apertei e não aconteceu nada".
        val e = PermissoesEssenciais.avaliar(todas - Manifest.permission.RECORD_AUDIO)
        val r = PermissoesEssenciais.recuperacao(e) { false }
        assertTrue(r is Recuperacao.AbrirAjustes)
    }

    @Test
    fun misturaDeNegativas_prefereODialogo() {
        // Uma pedível e uma definitiva: pedir a que dá é menos atrito que mandar
        // o agente para os ajustes do sistema no meio do turno.
        val e = PermissoesEssenciais.avaliar(
            todas - Manifest.permission.CAMERA - Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val r = PermissoesEssenciais.recuperacao(e) { it == Manifest.permission.CAMERA }
        assertTrue(r is Recuperacao.Pedir)
        assertEquals(listOf(Manifest.permission.CAMERA), (r as Recuperacao.Pedir).permissoes)
    }

    @Test
    fun tudoConcedido_naoOfereceRecuperacao() {
        assertEquals(
            Recuperacao.Nada,
            PermissoesEssenciais.recuperacao(PermissoesEssenciais.avaliar(todas)) { true },
        )
    }

    // ── Catálogo ──────────────────────────────────────────────────────────────

    @Test
    fun aBloqueantePrimeiro() {
        // Pedir câmera antes do microfone gasta a paciência do agente na
        // permissão menos importante — e a que ele mais nega é a última.
        assertEquals(PermissoesEssenciais.MICROFONE, PermissoesEssenciais.catalogo().first())
    }

    @Test
    fun oCatalogoDoAparelhoEhSubconjuntoDeTodas() {
        // O filtro por versão só pode remover, nunca acrescentar.
        assertTrue(PermissoesEssenciais.catalogo().all { it in PermissoesEssenciais.TODAS })
    }

    @Test
    fun soUmaEhBloqueante() {
        // Se duas fossem, "podeOperar" viraria um "quase tudo tem de estar certo",
        // e o app recusaria a operar em campo por falta de câmera.
        assertEquals(1, PermissoesEssenciais.TODAS.count { it.bloqueante })
    }

    @Test
    fun naoPedeLocalizacaoEmSegundoPlano() {
        // O FGS com tipo `location` já cobre o turno. A de segundo plano só
        // acrescentaria o diálogo "o tempo todo".
        assertFalse(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION in
                PermissoesEssenciais.TODAS.map { it.permissao },
        )
    }

    @Test
    fun todaPermissaoExplicaOQueMorre() {
        for (p in PermissoesEssenciais.TODAS) {
            assertTrue("${p.permissao} sem porquê", p.porQue.length > 20)
            assertTrue("${p.permissao} sem consequência", p.semEla.length > 10)
            assertTrue("${p.permissao} não apaga capacidade nenhuma", p.capacidades.isNotEmpty())
        }
    }

    @Test
    fun cadaCapacidadeTemDono() {
        // Capacidade sem permissão que a apague é enum morto, e enum morto dá
        // falsa sensação de cobertura no painel.
        // Contra TODAS, não contra `catalogo()`: no Android 12 a permissão de
        // notificação não é pedida em runtime, mas a capacidade existe.
        val cobertas = PermissoesEssenciais.TODAS.flatMap { it.capacidades }.toSet()
        val orfas = Capacidade.entries - cobertas
        assertTrue("capacidades sem permissão associada: $orfas", orfas.isEmpty())
    }
}
