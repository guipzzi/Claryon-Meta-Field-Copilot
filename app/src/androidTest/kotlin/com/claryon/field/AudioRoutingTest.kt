package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3 — verifica o **tratamento gracioso** do roteamento de áudio (a exigência do
 * edital/PADROES_DE_ENGENHARIA.md: `setCommunicationDevice==false` e lista vazia nunca são falha
 * silenciosa). Roda no emulador, que não tem dispositivo Bluetooth SCO.
 *
 * O ciclo de eco completo (falar → gravar → reproduzir) exige um **fone
 * Bluetooth HFP real** — o MDK não simula áudio e o emulador não tem SCO.
 */
@RunWith(AndroidJUnit4::class)
class AudioRoutingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun iniciar_semSCO_retornaFalhaClara_naoSilenciosa() = runBlocking {
        val manager = GlassesAudioManagerImpl(context, allowFallbackToDefault = false)
        val result = manager.iniciar()

        assertTrue("sem SCO deve falhar explicitamente", result is Result.Failure)
        assertEquals("audio.no_sco", (result as Result.Failure).error.code)

        manager.liberar() // restaura o modo/rota; não deve lançar
    }

    @Test
    fun liberar_semIniciar_ehSeguro() {
        val manager = GlassesAudioManagerImpl(context)
        manager.liberar() // idempotente/seguro mesmo sem iniciar()
    }
}
