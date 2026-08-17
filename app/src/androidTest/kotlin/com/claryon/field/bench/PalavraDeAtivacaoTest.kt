package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * **A palavra de ativação, escolhida por medição — não por análise a priori.**
 *
 * `specs/gatilho-por-voz.spec.md` passou a exigir isto depois que "Claryon" foi
 * medido virando `clarion`, `varyon`, `farion`, `parion` e `marcarion`. A plosiva
 * inicial trocava de modo **e** de lugar (/k/→/v/,/f/,/p/,/m/) no mesmo áudio, o
 * que contradiz frontalmente o critério que a escolheu (`DECISIONS.md` 2026-08-14:
 * *"plosiva + líquida + vogais abertas + nasal, tudo abaixo do corte de 4 kHz"*).
 *
 * Foi a análise fonética a priori que produziu "Claryon". Então o critério passa a
 * ser empírico, e este arquivo é o instrumento.
 *
 * ## Os dois critérios que a análise a priori não capturou
 *
 * **1. Tem de ser palavra REAL do léxico pt-BR.** Medido: "Claryon" falhou **6 de
 * 6** *estando dentro do `initial_prompt`*, enquanto "guarnição" — também no prompt
 * — acertou 7 de 8. A diferença é que "guarnição" tem BPE frequente e "Claryon" não
 * existe. O modelo de linguagem subword é adversário de nome inventado.
 *
 * **2. O traço robusto é a VOGAL, não a consoante.** A banda útil do HFP em CVSD é
 * a de telefonia (300–3400 Hz). /s/ e /f/ têm energia majoritariamente **acima** de
 * 4 kHz; o burst de plosiva é justamente o que se perde. Palavra boa decide a
 * própria identidade por sequência vocálica e sonorantes — não por contraste de
 * fricativa nem por burst inicial.
 *
 * ## O que este teste NÃO decide
 *
 * Mede **grafia sobrevivente em fala sintética de banda cheia**. Faltam, e estão
 * declarados: a taxa de **falso positivo** em fala espontânea (o número que decide
 * de verdade, porque um falso positivo toma o piso da guarnição), e o comportamento
 * no caminho HFP real de 8 kHz. Foi exatamente o Piper a 16 kHz que aprovou
 * "Claryon" em 14/08 — este teste sozinho não pode ser o veredito final, e sim o
 * filtro que elimina as candidatas que nem no caso fácil sobrevivem.
 */
@RunWith(AndroidJUnit4::class)
class PalavraDeAtivacaoTest {

    private val taxa = 16_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * As candidatas, com o motivo de cada uma escrito antes da medição.
     *
     * `Claryon` entra como **controle**: sem ela não se sabe se uma candidata é boa
     * ou se a bancada ficou fácil.
     */
    private val candidatas = listOf(
        "Claryon" to "CONTROLE — a atual. Nome inventado; traço discriminativo na plosiva inicial",
        "Aurora" to "três sílabas INTEIRAMENTE sonorantes (vogais + tepes): nenhuma decisão de lugar de consoante a errar",
        "Andorinha" to "quatro sílabas, zero fricativa obstruinte; nasal palatal e tepe; rara em rádio policial",
        "Bandeirante" to "quatro sílabas com carga nos sonorantes; palavra real de grafia estável",
        "Oriente" to "quatro sílabas, sem sibilante; risco declarado: é também forma verbal",
    )

    /** A frase de comando real do produto, para medir a palavra em contexto. */
    private fun frase(palavra: String) = "$palavra, mudar para guarnição 4."

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * **`PcmResampler.resample` e NÃO uma interpolação linear escrita à mão.**
     *
     * Esta função era um reamostrador linear meu, repetido em seis benches — e
     * linear é um filtro anti-aliasing péssimo. O Piper sintetiza a 22 050 Hz e o
     * barramento é 16 000: descer sem filtro **dobra 8–11 kHz para dentro da banda
     * de voz**, exatamente onde vivem as fricativas que distinguem consoantes.
     *
     * O projeto já tinha resolvido isso em `801df29` ("Anti-aliasing na voz"), com
     * um FIR de 63 tapes e janela de Hamming antes do decimador — e o KDoc do
     * próprio `PcmResampler` avisa que `resampleLinear` não filtra. Eu reintroduzi
     * o defeito na bancada e passei a medir o meu aliasing em vez do ASR.
     */
    private fun reamostrar(entrada: ShortArray, de: Int, para: Int): ShortArray =
        com.claryon.common.PcmResampler.resample(entrada, de, para)

    @Test
    fun qualPalavraDeAtivacaoSobrevive(): Unit = runBlocking {
        val repeticoes = 3

        // Fase 1: sintetizar tudo e soltar o Piper. Os dois modelos no mesmo
        // processo estouram o LMK — lição da primeira versão do bench de STT.
        val piper = Modelos.piper(ctx)
        Assume.assumeTrue("Piper ausente", piper != null)
        val amostras = mutableListOf<Triple<String, String, ShortArray>>()
        for ((palavra, _) in candidatas) {
            repeat(repeticoes) {
                piper!!.synthesize(frase(palavra)).getOrNull()?.let {
                    amostras += Triple(palavra, frase(palavra), reamostrar(it.samples, it.sampleRateHz, taxa))
                }
            }
        }
        piper!!.release()
        Assume.assumeTrue("Piper não sintetizou", amostras.isNotEmpty())

        // Fase 2: transcrever.
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        // palavra -> lista de (primeira palavra transcrita, transcrição inteira)
        val saidas = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((palavra, _, pcm) in amostras) {
            val txt = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
            val primeira = normalizar(txt).split(" ").firstOrNull().orEmpty()
            saidas.getOrPut(palavra) { mutableListOf() } += primeira to txt.trim()
        }

        val linhas = StringBuilder()
        val placar = LinkedHashMap<String, Int>()
        for ((palavra, motivo) in candidatas) {
            val obs = saidas[palavra].orEmpty()
            val alvo = normalizar(palavra)
            val acertos = obs.count { it.first == alvo }
            placar[palavra] = acertos
            val grafias = obs.map { it.first }.distinct()
            linhas.append(
                "\n  %-12s %d/%d  grafias: %s\n      motivo: %s\n      saídas: %s"
                    .format(
                        palavra, acertos, obs.size,
                        grafias.joinToString(" · "),
                        motivo.take(90),
                        obs.joinToString(" | ") { "\"${it.second}\"" }.take(260),
                    ),
            )
        }

        val vencedora = placar.filterKeys { it != "Claryon" }.maxByOrNull { it.value }
        android.util.Log.i(
            "ClaryonField",
            """
            |PALAVRA DE ATIVAÇÃO — $repeticoes sínteses por candidata, ${Modelos.WHISPER_ASSET}
            |  critério: a primeira palavra transcrita tem de ser a candidata, normalizada
            |$linhas
            |
            |  controle (Claryon) .. ${placar["Claryon"]}/$repeticoes
            |  melhor candidata .... ${vencedora?.key} ${vencedora?.value}/$repeticoes
            |
            |  ⚠️ Isto mede grafia em fala sintética de BANDA CHEIA. Falta a taxa de
            |     falso positivo em fala espontânea — que é o número que decide, porque
            |     falso positivo toma o piso da guarnição — e o caminho HFP de 8 kHz.
            """.trimMargin(),
        )

        // Asserção sobre o INSTRUMENTO: toda candidata tem de ter sido medida.
        // O veredito de qual escolher é humano e vive na spec.
        assertTrue("nenhuma candidata foi medida", placar.size == candidatas.size)
        assertTrue(
            "o controle deveria ter sido medido junto — sem ele não se sabe se a " +
                "bancada ficou fácil",
            placar.containsKey("Claryon"),
        )
    }
}
