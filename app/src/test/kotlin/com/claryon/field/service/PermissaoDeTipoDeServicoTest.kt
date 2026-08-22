package com.claryon.field.service

import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import com.claryon.agent.TipoServico
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O portão entre "o modo usa este tipo" e "o Android deixa subir com ele".
 *
 * Estes testes existem porque `CopilotService` isentava `CONNECTED_DEVICE` com
 * `return true // não exige runtime`, e isso **matava o app** na primeira
 * operação com Bluetooth negado — `SecurityException` em `startForeground`,
 * reproduzida em emulador API 35. Cada teste aqui é contra-teste: escrito para
 * falhar se a isenção voltar.
 */
class PermissaoDeTipoDeServicoTest {

    /**
     * Das onze permissões que satisfazem `connectedDevice`, este manifest
     * declara uma só. A lista é a que o próprio sistema enuncia na
     * `SecurityException` (`ActiveServices.validateForegroundServiceType`),
     * copiada da exceção real, não de memória.
     */
    private val qualificadorasDeConnectedDevice = setOf(
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.NFC",
        "android.permission.TRANSMIT_IR",
        "android.permission.UWB_RANGING",
    )

    @Test
    fun nenhumTipoDeServicoEhIsentoDePermissaoDeRuntime() {
        // O defeito era exatamente um `null` aqui, para CONNECTED_DEVICE.
        for (tipo in TipoServico.entries) {
            assertNotNull(
                "$tipo sem permissão de runtime: no Android 14+ isso é " +
                    "SecurityException em startForeground, não isenção",
                permissaoDeRuntimeDe(tipo),
            )
        }
    }

    @Test
    fun connectedDevice_exigeBluetoothConnect() {
        assertEquals(
            "connectedDevice só sobe se o app tiver uma das qualificadoras; " +
                "a única que este manifest declara é BLUETOOTH_CONNECT",
            android.Manifest.permission.BLUETOOTH_CONNECT,
            permissaoDeRuntimeDe(TipoServico.CONNECTED_DEVICE),
        )
    }

    /**
     * O teste que pega o erro mais fácil de cometer: trocar a permissão do
     * portão por uma que o manifest não pede. `checkSelfPermission` de uma
     * permissão não declarada devolve `DENIED` **para sempre** — o tipo sumiria
     * da máscara em todo aparelho, e o sintoma seria "o serviço não sobe" sem
     * nada apontando para cá.
     */
    @Test
    fun todaPermissaoDoPortaoEstaDeclaradaNoManifest() {
        val declaradas = permissoesDoManifest()
        assertTrue("não achei o AndroidManifest.xml para conferir", declaradas.isNotEmpty())
        for (tipo in TipoServico.entries) {
            val exigida = permissaoDeRuntimeDe(tipo) ?: continue
            assertTrue(
                "$tipo é barrado por $exigida, que o manifest não declara — " +
                    "checkSelfPermission devolveria DENIED para sempre",
                exigida in declaradas,
            )
        }
    }

    @Test
    fun aQualificadoraDeConnectedDevice_continuaSendoAUnicaDeclarada() {
        val declaradas = permissoesDoManifest()
        val qualificadoras = declaradas intersect qualificadorasDeConnectedDevice
        // Se alguém acrescentar CHANGE_NETWORK_STATE (normal, sem diálogo) o
        // crash some — e com ele some a honestidade do tipo: o app passaria a
        // declarar "sessão com aparelho conectado" com o Bluetooth negado.
        // Mudança dessas é decisão de produto; o teste obriga a passar por aqui.
        assertEquals(
            "mudou o conjunto de qualificadoras de connectedDevice no manifest",
            setOf(android.Manifest.permission.BLUETOOTH_CONNECT),
            qualificadoras,
        )
    }

    /**
     * O modo em que a máscara pode ficar **vazia** — e vazia é ilegal no
     * Android 14+ (`MissingForegroundServiceTypeException`).
     *
     * Standby não pede microfone nem câmera. Negados Bluetooth e localização,
     * não sobra tipo nenhum. Este teste fixa o fato que obriga
     * `entrarEmPrimeiroPlano` a tratar `tipos == 0` em vez de confiar que
     * sempre sobra alguma coisa.
     */
    @Test
    fun standbySemBluetoothESemLocal_naoSobraNenhumTipo() {
        val negadas = setOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val sobrando = PowerPolicy.tiposDeServico(ModoOperacao.STANDBY)
            .filter { permissaoDeRuntimeDe(it) !in negadas }
        assertEquals(
            "Standby com Bluetooth e local negados fica sem tipo — a máscara " +
                "vazia precisa ser tratada, não descoberta em produção",
            emptyList<TipoServico>(),
            sobrando,
        )
    }

    /** `ATIVO` com microfone concedido sempre tem tipo, mesmo sem Bluetooth. */
    @Test
    fun ativoComMicrofone_sobeMesmoComBluetoothNegado() {
        val sobrando = PowerPolicy.tiposDeServico(ModoOperacao.ATIVO)
            .filter { permissaoDeRuntimeDe(it) != android.Manifest.permission.BLUETOOTH_CONNECT }
        assertTrue(
            "o caminho que o agente percorre ao negar Bluetooth e seguir assim " +
                "mesmo precisa continuar subindo o serviço",
            TipoServico.MICROPHONE in sobrando,
        )
    }

    private fun permissoesDoManifest(): Set<String> {
        val manifesto = acharManifest() ?: return emptySet()
        return PADRAO_USES_PERMISSION.findAll(manifesto.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * O diretório de trabalho do Gradle para teste JVM é o do módulo, mas isso
     * não é contrato — subir até achar o arquivo mantém o teste válido se
     * alguém rodar de outro lugar.
     */
    private fun acharManifest(): File? {
        var dir: File? = File("").absoluteFile
        repeat(PROFUNDIDADE_DE_BUSCA) {
            val d = dir ?: return null
            val candidato = File(d, "app/src/main/AndroidManifest.xml")
            if (candidato.isFile) return candidato
            val local = File(d, "src/main/AndroidManifest.xml")
            if (local.isFile) return local
            dir = d.parentFile
        }
        return null
    }

    private companion object {
        val PADRAO_USES_PERMISSION =
            Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
        const val PROFUNDIDADE_DE_BUSCA = 6
    }
}
