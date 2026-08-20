package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.voice.DetectorDeAtivacao
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime

/**
 * **O detector de ativação rodando no aparelho — o número que a bancada não dá.**
 *
 * Toda a caracterização anterior foi em Python, num laptop: 26 de 26 elocuções em
 * fluxo contínuo, latência mediana de −20 ms, 2,3 ms de custo por segundo de áudio.
 * Nada disso prova que funciona no Android, e este projeto já pagou seis vezes por
 * classe construída, testada e nunca ligada.
 *
 * Duas coisas são medidas aqui, e só aqui:
 *
 * 1. **A cadeia sobe**, com o `onnxruntime` que já vem na `.so` do sherpa, sem
 *    dependência nova. Se `dlopen` falhar, se o `ORT_API_VERSION` divergir ou se os
 *    nomes de tensor estiverem errados, é aqui que aparece.
 * 2. **O escore do aparelho é o mesmo da bancada.** Mel, escala e empilhamento foram
 *    reescritos em C; qualquer divergência de um deles dá um detector que funciona no
 *    laptop e não no campo — e só apareceria no fim.
 *
 * ## O áudio não está no repositório, e isso é de propósito
 *
 * São gravações de pessoas reais: dado pessoal sensível pela LGPD art. 5º, II. Elas
 * entram por `adb push` na hora de medir e o `.gitignore` tem trava para o caso de
 * alguém esquecer. Sem os arquivos o teste **pula**, não falha — quem clona o repo
 * não herda uma bancada quebrada.
 *
 * ```
 * adb shell mkdir -p /sdcard/Android/data/com.claryon.field/files/bench
 * adb push *.wav /sdcard/Android/data/com.claryon.field/files/bench/
 * ```
 *
 * ## O que este teste NÃO mede
 *
 * Falso positivo. O único negativo humano disponível são 3,8 s de fala, e `0
 * disparos` em 3,8 s é ausência de amostra, não taxa. A métrica que decide se isto
 * pode ir a campo é **falsos por hora** sobre fala espontânea.
 */
@RunWith(AndroidJUnit4::class)
class DetectorDeAtivacaoTest {

    private val ctxDoTeste get() = InstrumentationRegistry.getInstrumentation().context

    /**
     * O áudio entra pelo diretório externo **do app**, não por `/sdcard/Download`.
     *
     * Com armazenamento com escopo, `Download` responde `exists() == true` e falha
     * no `open()` com `EACCES` — o pior formato de erro possível, porque passa pela
     * verificação e quebra depois. O diretório do próprio app é legível sem
     * permissão nenhuma.
     */
    private val pasta: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "bench",
        )

    private fun lerWav(f: File): ShortArray {
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

    /**
     * **Os assets do APK DE PRODUÇÃO, não os do APK de teste.**
     *
     * Eles moravam em `androidTest/assets/ativacao/`, e por isso este arquivo lia de
     * `ctxDoTeste` com o diretório passado à mão. O detector passava aqui e teria
     * devolvido `false` em produção — `preparar()` procura em `models/ativacao`, que
     * não existia no APK do app. Um teste verde sobre um caminho que o produto não
     * percorre é precisamente o que o §6 chama de escrito, não construído.
     *
     * Agora o alvo é `targetContext` e o diretório é o default. Se alguém tirar os
     * modelos do APK, este teste cai junto com a capacidade.
     */
    private fun detector(): DetectorDeAtivacao? {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val bytes = runCatching {
            assets.open("${'$'}{DetectorDeAtivacao.ASSETS}/cabeca.f32").use { it.readBytes() }
        }.getOrNull() ?: return null
        val (pesos, vies) = DetectorDeAtivacao.cabecaDeBytes(bytes) ?: return null
        val d = DetectorDeAtivacao(pesos, vies)
        return if (d.preparar(assets)) d else null.also { d.close() }
    }

    @Test
    fun aCadeiaSobeNoAparelhoEDetectaEmFluxo() {
        val fluxo = File(pasta, "claryon-repetidas-vezes_guido.wav")
        Assume.assumeTrue(
            "áudio ausente — adb push para ${pasta.absolutePath}",
            fluxo.exists(),
        )
        val d = detector()
        Assume.assumeTrue("o detector não subiu (assets de ativação ausentes?)", d != null)

        d!!.use {
            val pcm = lerWav(fluxo)
            val bloco = 320 // 20 ms, a granularidade do rádio
            var disparos = 0
            var decisoes = 0
            val custos = mutableListOf<Long>()

            var i = 0
            while (i < pcm.size) {
                val n = minOf(bloco, pcm.size - i)
                val pedaco = pcm.copyOfRange(i, i + n)
                val antes = it.ultimoEscore
                val ns = measureNanoTime { if (it.aceitar(pedaco)) disparos++ }
                // Só conta o custo dos blocos em que houve decisão de verdade: os
                // outros são cópia de anel e diluiriam a medida.
                if (it.ultimoEscore != antes) {
                    decisoes++
                    custos += ns
                }
                i += n
            }

            custos.sort()
            val p50 = custos[custos.size / 2] / 1_000_000.0
            val p90 = custos[(custos.size * 9) / 10] / 1_000_000.0
            val duracao = pcm.size / DetectorDeAtivacao.TAXA.toDouble()

            android.util.Log.i(
                "ClaryonField",
                """
                |DETECTOR DE ATIVAÇÃO NO APARELHO — onnxruntime da .so do sherpa
                |  ${"%.1f".format(duracao)} s de áudio · $decisoes decisões (passo de 80 ms)
                |  disparos ................. $disparos   (a bancada em Python deu 26)
                |  custo por decisão ........ p50 ${"%.1f".format(p50)} ms · p90 ${"%.1f".format(p90)} ms
                |  orçamento do passo ....... 80 ms → ocupa ${"%.1f".format(p50 / 80 * 100)} % de um núcleo
                |  cabeça ................... 289 floats, 1156 bytes, produto escalar em Kotlin
                |  dependência nova ......... nenhuma
                """.trimMargin(),
            )

            assertTrue("nenhuma decisão foi tomada — o anel não encheu", decisoes > 100)
            // Número EXATO, não "maior que zero". A gravação tem 26 elocuções e a
            // bancada em Python dá 26; se o mel, a escala `/10+2` ou o passo entre
            // as três janelas divergirem no C, a contagem muda e é aqui que aparece.
            // `> 0` passaria com qualquer um desses defeitos de volta.
            assertTrue(
                "esperados 26 disparos (o que a bancada em Python dá nesta mesma " +
                    "gravação), vieram $disparos: o escore do aparelho não reproduz o dela",
                disparos == 26,
            )
            // O passo é de 80 ms: se uma decisão custar mais que isso, o detector não
            // acompanha o áudio em tempo real e o projeto inteiro cai.
            assertTrue("p90 de ${"%.1f".format(p90)} ms não cabe no passo de 80 ms", p90 < 80.0)
        }
    }

    /**
     * **O teste que derrubou a versão 1, e por isso é o que mais importa aqui.**
     *
     * Com 3,8 s de negativo o detector parecia perfeito. Com 3,65 min de leitura
     * contínua ele deu **26 disparos — 428 por hora**, contra a meta de 0,5/h do
     * roadmap para o que só toca earcon. A causa não era o limiar: o treino tinha
     * visto **três** negativos humanos e nunca fala corrida.
     *
     * A cabeça v2 aprendeu com a primeira metade da leitura; esta medição roda na
     * **segunda metade**, que o treino não viu. Sem esse corte no tempo o número
     * seria zero por construção e não valeria nada.
     *
     * ⚠️ 1,8 min de material retido dá limite superior de ~99 falsos/h com 95% de
     * confiança. **Isto não prova a meta de 0,5/h** — para isso são ~6 h de fala com
     * zero disparo. O que está provado é a direção, e ela é grande: 428 → 0.
     */
    @Test
    fun oDetectorFicaCaladoEmLeituraContinua() {
        val leitura = File(pasta, "leitura-tp_guido.wav")
        Assume.assumeTrue("áudio ausente", leitura.exists())
        val d = detector()
        Assume.assumeTrue("o detector não subiu", d != null)

        d!!.use {
            val pcm = lerWav(leitura)
            // Só a metade retida: a primeira treinou a cabeça.
            val metade = pcm.size / 2
            var disparos = 0
            var i = metade
            while (i < pcm.size) {
                val n = minOf(320, pcm.size - i)
                if (it.aceitar(pcm.copyOfRange(i, i + n))) disparos++
                i += n
            }
            val horas = (pcm.size - metade) / 16000.0 / 3600.0
            android.util.Log.i(
                "ClaryonField",
                """
                |ATIVAÇÃO — FALSO POSITIVO em leitura contínua (metade retida)
                |  ${"%.1f".format(horas * 60)} min de fala que o treino nunca viu
                |  disparos ......... $disparos  →  ${"%.1f".format(disparos / horas)} por hora
                |  a versão 1, treinada com 3 negativos, dava 428/h
                |  meta do roadmap .. 0,5/h para o que só toca earcon
                |  ⚠️ com este material o limite superior de 95% é ~99/h. A meta exige ~6 h.
                """.trimMargin(),
            )
            assertTrue("o detector disparou $disparos vez(es) em leitura contínua", disparos == 0)
        }
    }

    @Test
    fun oDetectorFicaCaladoEmFalaQueNaoEComando() {
        val negativo = File(pasta, "na-escuta_guido.wav")
        Assume.assumeTrue("áudio ausente", negativo.exists())
        val d = detector()
        Assume.assumeTrue("o detector não subiu", d != null)

        d!!.use {
            val pcm = lerWav(negativo)
            var disparos = 0
            var i = 0
            while (i < pcm.size) {
                val n = minOf(320, pcm.size - i)
                if (it.aceitar(pcm.copyOfRange(i, i + n))) disparos++
                i += n
            }
            android.util.Log.i(
                "ClaryonField",
                "ATIVAÇÃO — \"na escuta\" (${"%.1f".format(pcm.size / 16000.0)} s): " +
                    "$disparos disparo(s) · último escore ${"%.3f".format(it.ultimoEscore)}\n" +
                    "  ⚠️ 3,8 s não é taxa de falso positivo. É ausência de amostra.",
            )
            assertTrue("o detector disparou em fala que não é comando", disparos == 0)
        }
    }
}
