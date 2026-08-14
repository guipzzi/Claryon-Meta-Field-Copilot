package com.claryon.evidence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class EncryptedEvidenceVaultTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var vault: EncryptedEvidenceVault

    @Before
    fun limpar() {
        File(ctx.filesDir, "evidence").deleteRecursively()
        vault = EncryptedEvidenceVault(ctx, clockMillis = { 1_700_000_000_000L })
    }

    private fun ctxOcorrencia() =
        OccurrenceContext(agentId = "007", unitId = "GTA-3", startedAtEpochMillis = 42L)

    @Test
    fun gravaCifrado_finaliza_eCadeiaIntegra() = runBlocking {
        val handle = (vault.beginRecording(ctxOcorrencia()) as Result.Success).value
        // ~30 s de áudio simulado = vários segmentos.
        repeat(30) { i ->
            val chunk = ByteArray(1024) { (it + i).toByte() }
            assertTrue(vault.append(handle, chunk) is Result.Success)
        }
        val manifesto = (vault.finalize(handle) as Result.Success).value

        assertEquals(30, manifesto.chain.size)
        // Segmento no disco está cifrado (não contém o padrão em claro).
        val seg0 = File(ctx.filesDir, "evidence/${handle.id}/seg_00000.enc").readBytes()
        assertTrue("segmento não deveria estar em claro", seg0.size != 1024 || !seg0.all { it == seg0[0] })
        // Cadeia íntegra → -1.
        assertEquals(-1, vault.verificar(handle, manifesto))
    }

    @Test
    fun adulterarUmByte_verificacaoFalha_eApontaOSegmento() = runBlocking {
        val handle = (vault.beginRecording(ctxOcorrencia()) as Result.Success).value
        repeat(5) { i -> vault.append(handle, ByteArray(512) { (it + i).toByte() }) }
        val manifesto = (vault.finalize(handle) as Result.Success).value
        assertEquals(-1, vault.verificar(handle, manifesto))

        // Vira 1 byte do segmento 2 no disco.
        val alvo = File(ctx.filesDir, "evidence/${handle.id}/seg_00002.enc")
        RandomAccessFile(alvo, "rw").use { raf ->
            val pos = raf.length() / 2
            raf.seek(pos)
            val b = raf.readByte()
            raf.seek(pos)
            raf.writeByte(b.toInt() xor 0x01)
        }

        assertEquals("deve apontar o segmento 2", 2, vault.verificar(handle, manifesto))
    }
}
