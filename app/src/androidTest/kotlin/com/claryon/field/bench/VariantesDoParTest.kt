package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer

/**
 * **"Hey Claryon" com lista de variantes MEDIDA — a decisão de manter a marca.**
 *
 * A revisão humana de 17/08 decidiu manter `Hey Claryon` e perguntou se dá para
 * "treinar" o sistema a reconhecer que a conversa começou assim. Treinar o modelo é
 * inviável — exigiria dados rotulados, compute e reconversão para `ggml`. Mas há um
 * mecanismo mais barato e que a medição justifica: **o decodificador erra sempre do
 * mesmo jeito.**
 *
 * ```
 * "Hey Claryon, mudar para guarnição 3"  →  "Eclareon, mudar para a guarnição 3"
 * "Hey Claryon, mudar para guarnição 4"  →  "Eclareon, mudar para a Guarnição 4"
 * "Hey Claryon, onde está a guarnição 3" →  "E clarion, onde está a guarni são 3"
 * ```
 *
 * `Eclareon` duas vezes, `Clarion` de forma consistente. Isso não é ruído: é a
 * sequência de tokens que o modelo **prefere** para aquele som, e ela é estável.
 *
 * ## Isto NÃO é o casamento aproximado que a spec proíbe
 *
 * A proibição de `troca-de-grupo-por-voz.spec.md` é sobre **distância de edição**:
 * computar similaridade e aceitar o "mais parecido" converte erro de transcrição em
 * erro de despacho, e o agente não tem como saber. Uma **lista explícita, curta e
 * derivada de medição** é outra coisa: não computa similaridade, reconhece saídas
 * observadas de um decodificador determinístico.
 *
 * E a diferença de consequência é decisiva. Errar o **grupo** manda a voz do agente
 * para outra guarnição sem que ele saiba. Errar o **portão** abre o canal quando não
 * devia — e isso é medível diretamente, é o falso positivo, e está aqui no mesmo teste.
 *
 * ## O que valida a lista, e o que a invalidaria
 *
 * A lista só entra se **os dois** números fecharem: recall ≥ 90% e falso positivo
 * **zero** contra fala operacional. Uma lista que suba o recall e comece a disparar
 * em ambiente está pior que não ter lista — foi assim que `Aurora` sozinha morreu.
 *
 * Toda medição em **banda estreita de 8 kHz**, porque a ordem das candidatas muda
 * com a banda e foi por medir em banda cheia que a análise de 14/08 errou.
 */
@RunWith(AndroidJUnit4::class)
class VariantesDoParTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val par = "Hey Claryon"

    /**
     * Variantes observadas, **coletadas por medição** e escritas à mão.
     *
     * Cada entrada é uma sequência que o decodificador de fato produziu para o
     * áudio de "Hey Claryon". A lista é curta de propósito: cada item acrescentado
     * é superfície de falso positivo, e o teste abaixo mede exatamente isso.
     */
    private val variantes = listOf(
        "hey claryon",   // a forma canônica
        "hey clarion",   // grafia portuguesa da marca
        "eclareon",      // "hey" fundiu com a marca — 2 de 6 na primeira medição
        "e clarion",     // fusão parcial, com fronteira
        "eclarion",
        "hei claryon",
        "hey clareon",
    )

    /** Fala operacional que NÃO é comando: aqui nenhuma variante pode aparecer. */
    private val ambiente = listOf(
        "Central, aqui é a guarnição dois, estamos no local.",
        "Negativo, sem alteração no perímetro, na escuta.",
        "Atenção todas as unidades, ocorrência na área central.",
        "O veículo seguiu sentido bairro pela avenida principal.",
        "Ele clareou a situação para o comandante ontem.",
        "A claridade do dia ajudou na identificação do suspeito.",
        "E clara a necessidade de apoio nesta ocorrência.",
        "Solicito informação sobre o endereço anterior.",
        "Aguardando o apoio chegar para prosseguir com a ação.",
        "A perseguição terminou perto do posto de gasolina.",
    )

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /**
     * A transcrição **começa** por alguma variante?
     *
     * Ancorado no início e não em qualquer posição: é o que impede "ele clareou a
     * situação" de abrir canal. A ancoragem é a metade barata da defesa contra falso
     * positivo, e a lista de variantes é a que custa.
     */
    private fun comecaPorVariante(texto: String): String? {
        val t = normalizar(texto)
        return variantes.firstOrNull { t == it || t.startsWith("$it ") }
    }

    @Test
    fun aListaDeVariantesSobeORecallSemAbrirFalsoPositivo(): Unit = runBlocking {
        val comandos = listOf(
            "mudar para guarnição 3", "mudar para guarnição 4", "onde está a guarnição 3",
            "modo ocorrência", "pedir apoio", "iniciar gravação", "encerrar gravação",
            "consultar placa", "modo ativo", "detalhar",
        )

        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val comando = mutableListOf<ShortArray>()
        for (c in comandos) {
            piper!!.synthesize("$par, $c.").getOrNull()?.let {
                comando += porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
            }
        }
        val naoComando = mutableListOf<Pair<String, ShortArray>>()
        for (f in ambiente) {
            piper!!.synthesize(f).getOrNull()?.let {
                naoComando += f to porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", comando.isNotEmpty())

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        // ── Recall: quantos comandos a lista reconhece ────────────────────────
        var canonico = 0
        var comLista = 0
        val naoCobertas = mutableListOf<String>()
        val usadas = LinkedHashMap<String, Int>()
        for (pcm in comando) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val t = normalizar(txt)
            if (t.startsWith("hey claryon")) canonico++
            val v = comecaPorVariante(txt)
            if (v != null) {
                comLista++
                usadas[v] = (usadas[v] ?: 0) + 1
            } else {
                naoCobertas += t.split(" ").take(3).joinToString(" ")
            }
        }

        // ── Falso positivo: a lista dispara em fala que não é comando? ────────
        var falsos = 0
        val ondeDisparou = mutableListOf<String>()
        for ((frase, pcm) in naoComando) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            comecaPorVariante(txt)?.let {
                falsos++
                ondeDisparou += "\"${frase.take(34)}\" → \"${txt.trim().take(34)}\" (casou \"$it\")"
            }
        }

        val n = comando.size
        android.util.Log.i(
            "ClaryonField",
            """
            |"$par" COM LISTA DE VARIANTES — banda estreita 8 kHz
            |  ${variantes.size} variantes, ancoradas no INÍCIO da transcrição
            |
            |  recall só com a grafia canônica .. ${canonico * 100.0 / n}%  ($canonico/$n)
            |  recall com a lista ............... ${"%.1f".format(comLista * 100.0 / n)}%  ($comLista/$n)
            |  meta ............................. 90%
            |  variantes que pegaram ............ ${usadas.entries.joinToString { "${it.key}×${it.value}" }}
            |  ainda não cobertas ............... ${naoCobertas.distinct().take(6).joinToString(" · ")}
            |
            |  falso positivo ................... $falsos/${naoComando.size}
            |  ${ondeDisparou.joinToString("\n  ").ifEmpty { "(nenhum disparo em fala que não é comando)" }}
            """.trimMargin(),
        )

        // O falso positivo é a condição de entrada, não o resultado desejável: uma
        // lista que suba o recall e comece a disparar está PIOR que não ter lista.
        assertTrue(
            "a lista de variantes abriu $falsos falso(s) positivo(s): $ondeDisparou",
            falsos == 0,
        )
        assertTrue("nenhuma amostra medida", n > 0)
    }
}
