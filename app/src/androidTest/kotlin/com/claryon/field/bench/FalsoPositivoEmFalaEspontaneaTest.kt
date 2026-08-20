package com.claryon.field.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.PalavraDeAtivacaoNaFala
import com.claryon.common.Result
import com.claryon.field.voice.EscutaDoAgente
import com.claryon.voice.DetectorDeAtivacao
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **O falso positivo, com horas de fala espontânea de verdade.**
 *
 * Esta é a tarefa mais antiga em aberto do projeto. O `ESTADO.md` a carrega assim:
 * *"`0` em 1,8 min retidos dá limite superior de ~99/h com 95%; a meta é 0,5/h, e
 * isso exige da ordem de 6 h"*. E os 1,8 min eram de leitura em voz alta de um
 * texto, por um locutor só.
 *
 * ## Por que silêncio e leitura não valiam
 *
 * O `ESTADO` já dizia: *"os 60 s de silêncio do emulador não contam — silêncio é o
 * negativo mais fácil que existe"*. Leitura contínua é melhor, e ainda é fácil: um
 * locutor, dicção cuidada, sem sobreposição, sem risada, sem ruído de fundo.
 *
 * Fala espontânea de podcast tem o que falta: múltiplos locutores, sobreposição,
 * interjeição, hesitação, música de abertura, variação de nível. É o negativo que
 * se parece com o mundo.
 *
 * ## O que este teste NÃO faz
 *
 * Não transcreve. O whisper sobre 54 min levaria horas e a pergunta não é sobre
 * transcrição: é sobre o **estágio 1**, o detector acústico que roda o turno
 * inteiro. Ele é quem dispara o earcon, e é o falso positivo DELE que o aceite
 * limita em 0,5/h.
 *
 * ## Sobre o material
 *
 * O áudio é um podcast em pt-BR indicado por quem conduz o projeto, usado **só como
 * conjunto negativo local**: nada dele é transcrito, redistribuído ou versionado —
 * o que entra no repositório é o número. Fica fora do `git` pela mesma regra dos
 * áudios de treino.
 *
 * ```
 * yt-dlp -x --audio-format wav --postprocessor-args "-ar 16000 -ac 1"
 * ffmpeg -i negativo.wav -f segment -segment_time 300 -c copy fatia_%02d.wav
 * adb push fatia_*.wav .../files/bench/negativo/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FalsoPositivoEmFalaEspontaneaTest {

    private val ctxDoTeste get() = InstrumentationRegistry.getInstrumentation().context

    private val pasta: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "bench/negativo",
        )

    private fun detector(): DetectorDeAtivacao? {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val bytes = runCatching {
            assets.open("${DetectorDeAtivacao.ASSETS}/cabeca.f32").use { it.readBytes() }
        }.getOrNull() ?: return null
        val (pesos, vies) = DetectorDeAtivacao.cabecaDeBytes(bytes) ?: return null
        val d = DetectorDeAtivacao(pesos, vies)
        return if (d.preparar(assets)) d else null.also { d.close() }
    }

    private fun amostrasDoWav(f: File): ShortArray {
        val b = f.readBytes()
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val tam = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(tam, b.size - i - 8) / 2
                return ShortArray(n) { k ->
                    val p = i + 8 + k * 2
                    (((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)).toShort()
                }
            }
            i += 8 + tam + (tam and 1)
        }
        return ShortArray(0)
    }

    @Test
    fun oDetectorFicaCalado_emHorasDeFalaEspontanea() {
        Assume.assumeTrue("sem o negativo em ${pasta.path}", pasta.isDirectory)
        val fatias = pasta.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }
        Assume.assumeTrue("sem fatias de áudio", !fatias.isNullOrEmpty())

        val d = detector()
        Assume.assumeTrue("detector não subiu (assets ausentes?)", d != null)

        var disparos = 0
        var amostras = 0L
        var maiorEscore = 0f
        val quandoDisparou = mutableListOf<String>()

        try {
            for (fatia in fatias!!) {
                val pcm = amostrasDoWav(fatia)
                // Blocos de 20 ms: a mesma granularidade com que o rádio entrega, e
                // a mesma que a `EscutaDeAtivacao` usa em produção. Medir com bloco
                // diferente mediria outro detector.
                var i = 0
                while (i + 320 <= pcm.size) {
                    val bloco = pcm.copyOfRange(i, i + 320)
                    if (d!!.aceitar(bloco)) {
                        disparos++
                        quandoDisparou += "${fatia.name}@${"%.1f".format((amostras + i) / 16000.0)}s"
                    }
                    if (d.ultimoEscore > maiorEscore) maiorEscore = d.ultimoEscore
                    i += 320
                }
                amostras += pcm.size
                // Anel limpo entre fatias: emendar o fim de uma no começo da outra
                // avaliaria uma janela que não existe no áudio — o mesmo defeito que
                // a `EscutaDeAtivacao` evita nas bordas da mudez.
                d!!.reiniciar()
            }
        } finally {
            d?.close()
        }

        val horas = amostras / 16000.0 / 3600.0
        val porHora = if (horas > 0) disparos / horas else 0.0
        // Regra de três de Rutherford: com zero eventos em n janelas, o limite
        // superior de 95% é 3/n. É o número honesto quando não houve disparo.
        val tetoPorHora = if (disparos == 0 && horas > 0) 3.0 / horas else porHora

        Log.i("ClaryonField", "FALSO POSITIVO — fala espontânea, múltiplos locutores")
        Log.i(
            "ClaryonField",
            "  material ................. ${fatias.size} fatias, ${"%.2f".format(horas)} h " +
                "(${"%.1f".format(horas * 60)} min)\n" +
                "  disparos ................. $disparos\n" +
                "  taxa ..................... ${"%.2f".format(porHora)}/h" +
                (if (disparos == 0) "  (teto de 95%: ${"%.2f".format(tetoPorHora)}/h)" else "") + "\n" +
                "  maior escore ............. ${"%.3f".format(maiorEscore)} (limiar 0,500)\n" +
                "  meta do aceite ........... 0,5/h\n" +
                (if (quandoDisparou.isNotEmpty()) "  onde: ${quandoDisparou.take(10)}\n" else "") +
                "  ⚠️ É o estágio 1 (detector acústico) — o que dispara o earcon. " +
                "Falso aceite que ABRE CANAL é outro número, e depende da transcrição.",
        )

        org.junit.Assert.assertTrue(
            "o detector disparou $disparos vezes em ${"%.2f".format(horas)} h de fala " +
                "espontânea = ${"%.2f".format(porHora)}/h, contra a meta de 0,5/h. " +
                "Onde: ${quandoDisparou.take(10)}",
            porHora <= 0.5,
        )
    }

    /**
     * **O falso aceite que ABRE CANAL — a conjunção dos dois estágios.**
     *
     * O aceite da Fase 2 tem dois limites e eles medem coisas diferentes: earcon
     * falso ≤ 0,5/h (o teste acima) e **canal aberto por engano ≤ 1 por 8 h**. O
     * segundo é o grave: earcon falso irrita, canal aberto por engano difunde
     * ruído de ambiente para a guarnição inteira.
     *
     * Medir isto é barato e o motivo é a arquitetura: só as janelas em que o
     * estágio 1 disparou chegam ao estágio 2. Transcrever 54 min levaria horas;
     * transcrever os poucos segundos ao redor de cada disparo leva minutos.
     *
     * A janela é de 4 s a partir de 1 s ANTES do disparo — é o que a
     * `EscutaDeAtivacao` entregaria ao ciclo: o gatilho mais o comando que viria
     * depois. Transcrever só o instante do disparo mediria outra coisa.
     */
    @Test
    fun nenhumDisparoFalso_chegaAAbrirCanal(): Unit = runBlocking {
        Assume.assumeTrue("sem o negativo em ${pasta.path}", pasta.isDirectory)
        val fatias = pasta.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }
        Assume.assumeTrue("sem fatias", !fatias.isNullOrEmpty())
        val d = detector()
        Assume.assumeTrue("detector não subiu", d != null)
        val whisper = EscutaDoAgente.de(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        Assume.assumeTrue("whisper indisponível", whisper != null)

        val router = DeterministicIntentRouter()
        var disparos = 0
        var confirmaramGatilho = 0
        var abriramCanal = 0
        var amostras = 0L
        val exemplos = mutableListOf<String>()

        try {
            for (fatia in fatias!!) {
                val pcm = amostrasDoWav(fatia)
                var i = 0
                while (i + 320 <= pcm.size) {
                    if (d!!.aceitar(pcm.copyOfRange(i, i + 320))) {
                        disparos++
                        val ini = maxOf(0, i - 16_000)
                        val fim = minOf(pcm.size, ini + 4 * 16_000)
                        val texto = (whisper!!.transcribe(pcm.copyOfRange(ini, fim), 16_000)
                            as? Result.Success)?.value?.text.orEmpty()
                        if (PalavraDeAtivacaoNaFala.conferir(texto).confirmada) confirmaramGatilho++
                        if (router.route(texto) is Intent.AbrirTransmissao) {
                            abriramCanal++
                            exemplos += "${fatia.name}@${"%.1f".format((amostras + i) / 16000.0)}s \"${texto.take(50)}\""
                        }
                    }
                    i += 320
                }
                amostras += pcm.size
                d!!.reiniciar()
            }
        } finally {
            d?.close()
        }

        val horas = amostras / 16000.0 / 3600.0
        val porOitoHoras = if (horas > 0) abriramCanal * 8.0 / horas else 0.0
        Log.i("ClaryonField", "FALSO ACEITE QUE ABRE CANAL — conjunção dos dois estágios")
        Log.i(
            "ClaryonField",
            "  material ................. ${"%.2f".format(horas)} h\n" +
                "  estágio 1 disparou ....... $disparos\n" +
                "  estágio 2 confirmou ...... $confirmaramGatilho\n" +
                "  ABRIU CANAL .............. $abriramCanal  (${"%.2f".format(porOitoHoras)} por 8 h)\n" +
                "  meta do aceite ........... ≤ 1 por 8 h\n" +
                (if (exemplos.isNotEmpty()) "  onde: ${exemplos.take(5)}\n" else ""),
        )

        org.junit.Assert.assertTrue(
            "ruído de ambiente abriu canal $abriramCanal vez(es) em " +
                "${"%.2f".format(horas)} h = ${"%.2f".format(porOitoHoras)} por 8 h. " +
                "Cada uma difunde som ambiente para a guarnição inteira. ${exemplos.take(3)}",
            porOitoHoras <= 1.0,
        )
    }
}
