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
 * **O par de ativação, medido em BANDA ESTREITA — inclusive o "Hey Claryon" pedido.**
 *
 * A decisão humana de 17/08 foi abandonar a palavra única pelo **par improvável em
 * coocorrência**, e veio com uma pergunta direta: *"algo como Hey, Claryon — será
 * que funciona melhor?"*. Isto mede, em vez de opinar.
 *
 * ## Por que palavra única morreu, e o que o par tem de resolver
 *
 * Três propriedades são necessárias e nenhuma palavra as teve juntas:
 *
 * | | `Claryon` | `Aurora` |
 * |---|---|---|
 * | tokeniza como unidade | ✘ (não existe no vocabulário) | ✔ (id 40663) |
 * | sobrevive em banda estreita | 0/3 | **50%** — colapsa em "agora" |
 * | rara na fala espontânea | ✔ | **1/8** — "a aurora boreal" |
 *
 * O par muda o eixo: cada palavra só precisa **tokenizar e sobreviver**; a **raridade
 * passa a ser do par**, não de cada uma. "Hey" é comum, "escuta" é comuníssima em
 * rádio — mas o par com a segunda palavra certa pode ser raro.
 *
 * ## Tudo em banda estreita, e isso não é detalhe
 *
 * Em banda cheia `Aurora` deu 3/3; em 8 kHz, 50%. **A ordem das candidatas muda com
 * a banda**, e foi por medir em banda cheia que a análise de 14/08 aprovou "Claryon"
 * e errou. Toda candidata aqui passa por `16 → 8 → 16 kHz` com o FIR do projeto.
 *
 * ## Os dois números, sempre juntos
 *
 * **Recall** (o par sobrevive quando dito) e **falso positivo** (o par aparece quando
 * não foi dito). Uma candidata que ganhe num e perca no outro está reprovada — foi
 * exatamente o que aconteceu com `Aurora`, aprovada por 3/3 de sobrevivência e
 * derrubada por 1/8 de falso positivo.
 */
@RunWith(AndroidJUnit4::class)
class ParDeAtivacaoTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * As candidatas. `Hey Claryon` entra porque foi pedida **e** porque é a frase
     * original do `ROADMAP` — se ela funcionar, a marca sobrevive.
     */
    private val pares = listOf(
        "Hey Claryon" to "pedida na revisão, e é a frase original do ROADMAP — a marca sobrevive se passar",
        "Escuta Claryon" to "verbo de rádio + marca; 'escuta' tokeniza bem e o par com a marca é raro",
        "Atenção Aurora" to "'Aurora' sozinha morre em 'agora'; precedida de 'atenção' o par fica raro",
        "Alerta Aurora" to "mesma ideia, com palavra mais curta e mais distinta no ataque",
        "Copiloto escuta" to "duas palavras reais e frequentes; a raridade é toda do PAR",
    )

    /** Fala operacional que NÃO é comando — onde o par não pode aparecer. */
    private val ambiente = listOf(
        "Central, aqui é a guarnição dois, estamos no local.",
        "A aurora boreal apareceu no noticiário ontem.",
        "Negativo, sem alteração no perímetro, na escuta.",
        "Atenção todas as unidades, ocorrência na área central.",
        "O copiloto do veículo desceu e correu.",
        "Alerta de tempestade para a região hoje à tarde.",
        "Ele mudou para a outra rua agora há pouco.",
        "Escuta, o apoio já está a caminho daí.",
    )

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Banda estreita do HFP: corta acima de 4 kHz e devolve a taxa do whisper. */
    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /**
     * O par aparece **em sequência** no texto?
     *
     * Sequência e não "as duas palavras em qualquer lugar": é isso que faz a
     * raridade ser do par. "Alerta de tempestade… região" contém 'alerta' e não
     * contém 'alerta aurora'.
     */
    private fun contemPar(texto: String, par: String): Boolean {
        val p = normalizar(par).split(" ")
        val t = normalizar(texto).split(" ")
        if (p.size != 2) return false
        for (i in 0 until (t.size - 1)) if (t[i] == p[0] && t[i + 1] == p[1]) return true
        return false
    }

    @Test
    fun qualParSobreviveESeCala(): Unit = runBlocking {
        val complementos = listOf(
            "mudar para guarnição 3", "mudar para guarnição 4", "onde está a guarnição 3",
            "modo ocorrência", "pedir apoio", "iniciar gravação",
        )

        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)

        // Fase 1: sintetizar TUDO (os dois modelos juntos estouram o LMK).
        val doPar = LinkedHashMap<String, MutableList<ShortArray>>()
        for ((par, _) in pares) {
            for (c in complementos) {
                piper!!.synthesize("$par, $c.").getOrNull()?.let {
                    doPar.getOrPut(par) { mutableListOf() } +=
                        porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
                }
            }
        }
        val doAmbiente = mutableListOf<Pair<String, ShortArray>>()
        for (f in ambiente) {
            piper!!.synthesize(f).getOrNull()?.let {
                doAmbiente += f to porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa))
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", doPar.isNotEmpty())

        // Fase 2: transcrever uma vez cada áudio, e reusar para todas as candidatas.
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val txtAmbiente = doAmbiente.map { (frase, pcm) ->
            frase to (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
        }

        val linhas = StringBuilder()
        val placar = LinkedHashMap<String, Triple<Int, Int, Int>>() // recall, total, falsos
        for ((par, motivo) in pares) {
            val audios = doPar[par].orEmpty()
            var acertos = 0
            val erros = mutableListOf<String>()
            for (pcm in audios) {
                val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
                if (contemPar(txt, par)) acertos++ else erros += txt.trim().take(40)
            }
            val falsos = txtAmbiente.count { contemPar(it.second, par) }
            placar[par] = Triple(acertos, audios.size, falsos)

            linhas.append(
                "\n  %-16s recall %2d/%-2d (%5.1f%%)   falso positivo %d/%d\n      porquê: %s"
                    .format(par, acertos, audios.size,
                        if (audios.isEmpty()) 0.0 else acertos * 100.0 / audios.size,
                        falsos, txtAmbiente.size, motivo.take(72)),
            )
            if (erros.isNotEmpty()) linhas.append("\n      falhou: ${erros.take(3).joinToString(" · ")}")
        }

        // O vencedor tem de ganhar nos DOIS eixos: zero falso positivo primeiro,
        // e só entre esses o maior recall. Aurora provou que ordenar por recall
        // isolado aprova candidata que abre canal sozinha.
        val vencedor = placar.entries
            .filter { it.value.third == 0 }
            .maxByOrNull { it.value.first.toDouble() / it.value.second.coerceAtLeast(1) }

        android.util.Log.i(
            "ClaryonField",
            """
            |PAR DE ATIVAÇÃO — BANDA ESTREITA 8 kHz, ${Modelos.WHISPER_ASSET}
            |  critério: o par tem de aparecer EM SEQUÊNCIA na transcrição
            |$linhas
            |
            |  melhor com ZERO falso positivo: ${vencedor?.key ?: "NENHUM"} ${
                vencedor?.let { "(recall ${it.value.first}/${it.value.second})" } ?: ""
            }
            |
            |  ⚠️ Fala do Piper. Sem sotaque, sem hesitação, sem AGC de uplink. O aceite
            |     continua exigindo 30 pronúncias REAIS e 8 h de rádio ambiente.
            """.trimMargin(),
        )

        assertTrue("nenhuma candidata foi medida", placar.size == pares.size)
        assertTrue("o ambiente não foi medido", txtAmbiente.isNotEmpty())
    }
}
