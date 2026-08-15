package com.claryon.field

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.field.permissoes.Capacidade
import com.claryon.field.permissoes.PermissoesEssenciais
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O catálogo de permissões **contra o manifest de verdade**, no aparelho.
 *
 * Isto existe porque há uma classe de defeito que nenhum teste de JVM pega e que
 * não aparece no build: **permissão pedida em runtime sem estar declarada no
 * manifest é negada em silêncio, para sempre**. Não há erro de compilação, não
 * há exceção, não há log de aviso. O diálogo simplesmente não abre e o callback
 * volta `DENIED` — indistinguível, do lado do código, de um agente que apertou
 * "não permitir".
 *
 * Em campo isso apareceria como a pior versão possível do sintoma: um copiloto
 * que não ouve, e uma tela de permissões que insiste em pedir algo que o sistema
 * nunca vai conceder.
 */
class PermissoesNoAparelhoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val declaradas: Set<String> by lazy {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    }

    @Test
    fun todaPermissaoDoCatalogoEstaDeclaradaNoManifest() {
        val ausentes = PermissoesEssenciais.TODAS
            .map { it.permissao }
            .filterNot { it in declaradas }

        assertTrue(
            "pedidas em runtime mas ausentes do manifest (seriam negadas em " +
                "silêncio para sempre): $ausentes",
            ausentes.isEmpty(),
        )
    }

    @Test
    fun naoPedimosLocalizacaoEmSegundoPlano() {
        // Verificado no manifest de verdade, não só no catálogo: bastaria uma
        // biblioteca fundir a permissão dela no manifest final para o app passar
        // a exibir o diálogo "o tempo todo" sem ninguém ter escrito uma linha.
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION entrou no manifest final — provavelmente " +
                "por merge de biblioteca. Verificar com :app:processDebugManifest.",
            "android.permission.ACCESS_BACKGROUND_LOCATION" in declaradas,
        )
    }

    @Test
    fun oServicoDeclaraOsTiposQueUsa() {
        // O FGS de tipo `location` exige a permissão homônima no Android 14+.
        // Sem ela, `startForeground` lança SecurityException — em produção, na
        // primeira vez que o agente liga o mapa.
        for (p in listOf(
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
        )) {
            assertTrue("$p não declarada", p in declaradas)
        }
    }

    @Test
    fun aPrimeiraInstalacaoComecaSemPoderOperar() {
        // Estado real de primeira instalação: **nada concedido**. Descoberto
        // aqui, e não por suposição — a expectativa era que o runner de
        // instrumentação concedesse tudo o que está no manifest. Ele não
        // concede, e é bom que não conceda: este é exatamente o estado em que o
        // agente abre o app pela primeira vez, no pátio, antes do turno.
        val estadoInicial = PermissoesEssenciais.avaliar(concedidasDeVerdade())

        if (!estadoInicial.tudoConcedido) {
            assertFalse(
                "sem microfone o app não pode dizer que opera",
                estadoInicial.podeOperar,
            )
            val aviso = PermissoesEssenciais.avisoFalado(estadoInicial)
            assertTrue("o agente precisa ouvir alguma coisa", !aviso.isNullOrBlank())
        }

        // Concede de verdade, pelo caminho do sistema, e o catálogo tem de
        // enxergar a mudança. Fecha o circuito manifest → PackageManager →
        // catálogo → tela.
        conceder(PermissoesEssenciais.TODAS.map { it.permissao })

        val depois = PermissoesEssenciais.avaliar(concedidasDeVerdade())
        assertEquals(
            "ainda faltando: ${depois.faltando.map { it.permissao }}",
            emptyList<Capacidade>(),
            depois.capacidadesPerdidas,
        )
        assertTrue(depois.podeOperar)
        assertEquals(null, PermissoesEssenciais.avisoFalado(depois))
    }

    /**
     * Só concede, nunca revoga.
     *
     * `pm revoke` no pacote sob teste **mata o processo do app** — a instrumentação
     * morre junto e o teste vira "processo encerrado inesperadamente", que não é
     * uma falha legível. As combinações de negativa estão cobertas em
     * `PermissoesEssenciaisTest`, na JVM, onde varrer todas custa milissegundos.
     */
    private fun conceder(permissoes: List<String>) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in permissoes) {
            runCatching { automation.grantRuntimePermission(context.packageName, p) }
        }
    }

    private fun concedidasDeVerdade(): Set<String> = PermissoesEssenciais.TODAS
        .map { it.permissao }
        .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        .toSet()
}
