package com.claryon.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.field.voice.Modelos
import com.claryon.voice.ModelSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Caminho de produção dos modelos on-device.**
 *
 * Os testes de whisper e Piper que já existiam liam dos assets do *pacote de
 * testes* — ou seja, provavam que os modelos funcionam, não que o **aplicativo
 * instalado** os tem. No aparelho que a organização entrega no dia, era essa a
 * diferença entre ter e não ter IA local.
 *
 * Aqui exercitamos exatamente o que o app faz: `assets/models/` do APK de
 * produção, resolvidos por [Modelos].
 *
 * Também é a rede de proteção da **poda do espeak-ng-data**: de 113 dicionários
 * de idioma sobraram `pt_dict` e `en_dict` (18 MB → 1,8 MB). Se a poda tiver
 * levado junto algo que o espeak precisa, a síntese falha aqui.
 */
@RunWith(AndroidJUnit4::class)
class ModelosProducaoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun limparCopiaAnterior() {
        // Sem isto, uma cópia completa de uma execução anterior mascararia a
        // poda: o teste passaria lendo dados que o APK não empacota mais.
        File(context.filesDir, "espeak-ng-data").deleteRecursively()
    }

    @Test
    fun whisper_vemDoAssetDoApk_naoDoFilesDir() {
        val fonte = Modelos.fonteDoWhisper(context)
        assertNotNull("modelo whisper ausente do APK de produção", fonte)
        assertTrue(
            "o whisper de produção tem de vir do asset empacotado, e veio de: $fonte",
            fonte is ModelSource.Asset,
        )
        assertTrue(fonte!!.existe())
    }

    @Test
    fun piper_sintetizaPtBr_comEspeakPodado() = runBlocking {
        val piper = Modelos.piper(context)
        assertNotNull("Piper não resolveu a partir dos assets de produção", piper)

        val r = piper!!.synthesize("Apoio solicitado, guarnição avisada.")
        assertTrue(
            "Piper falhou com o espeak podado — causa: ${piper.ultimaFalha}",
            r is Result.Success,
        )
        val audio = (r as Result.Success).value
        assertTrue("áudio vazio", audio.samples.isNotEmpty())
        assertTrue(
            "áudio silencioso — o espeak pode ter perdido o dicionário pt",
            audio.samples.any { kotlin.math.abs(it.toInt()) > 500 },
        )
        piper.release()
    }

    @Test
    fun espeakCopiado_temApenasOsDicionariosQueUsamos() {
        // A cópia para o filesDir acontece dentro de Modelos.piper(); dispara e
        // confere o que foi parar no disco do aparelho.
        runBlocking { Modelos.piper(context)?.release() }

        val dir = File(context.filesDir, "espeak-ng-data")
        assertTrue("espeak-ng-data não foi copiado", dir.isDirectory)

        val dicts = dir.listFiles { f -> f.name.endsWith("_dict") }?.map { it.name }?.sorted()
        assertEquals(listOf("en_dict", "pt_dict"), dicts)

        // Os arquivos de fonética são o núcleo do espeak: sem eles não há síntese
        // em idioma nenhum. A poda não pode tê-los tocado.
        for (nucleo in listOf("phondata", "phonindex", "phontab", "intonations")) {
            assertTrue("arquivo de núcleo ausente: $nucleo", File(dir, nucleo).exists())
        }
        assertTrue("lang/roa/pt-BR ausente", File(dir, "lang/roa/pt-BR").exists())
    }
}
