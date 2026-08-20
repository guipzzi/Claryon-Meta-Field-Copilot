package com.claryon.field.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.PalavraDeAtivacaoNaFala
import com.claryon.common.Result
import com.claryon.field.voice.EscutaDoAgente
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Quatro locutores reais: o que eles gravaram, e o que isso mede de verdade.**
 *
 * ## O que eu supus, e o que a medição disse
 *
 * As gravações de Bruna, Carla, Guido e Pedro existem desde 17/08 e nunca tinham
 * passado pelo roteador. Escrevi este teste esperando medir **recall** do comando
 * com quatro vozes. Deu 0/4 — e a leitura óbvia ("a cadeia não funciona para outras
 * vozes") estava errada.
 *
 * O que o whisper devolveu:
 *
 * ```
 * Bruna  "Na escuta, cambia de eslível."
 * Carla  "Na escota, Cambiu desleveu."
 * Guido  "Na escuta, Cambio, Prisligo."
 * Pedro  "Na escota, câmbio, desligo."
 * ```
 *
 * Eles disseram *"na escuta, câmbio, desligo"*. São gravações de um desenho
 * ANTERIOR — de 17/08, quando a spec vigente era `gatilho-por-voz.spec.md` e a
 * frase de abertura era outra. **O áudio não contém o comando atual**, então 0/4
 * não mede recall de coisa nenhuma.
 *
 * ## O que ele mede, e é mais valioso do que eu procurava
 *
 * `Na escuta - *` é fala humana real dizendo "na escuta" **sem intenção de
 * comando** — o negativo mais próximo do mundo que este projeto tem. E o roteador
 * recusou os quatro.
 *
 * Isso testa a invariante que o roadmap chama de não-negociável: *"guarnição N na
 * escuta" ≠ "na escuta"*. Até aqui ela só tinha sido testada com strings que eu
 * mesmo escrevi. Agora tem quatro vozes humanas.
 *
 * `Claryon - *` são os arquivos do gatilho, e esses sim medem a conferência de
 * segundo estágio com voz que não é a de quem treinou o detector.
 *
 * Isto não substitui o aceite da Fase 2, e a diferença precisa ficar dita:
 *
 * - **O aceite pede 30 pronúncias por fone HFP.** Aqui são quatro locutores pelo
 *   microfone de celular. O canal é outro — HFP tem banda estreita e comprime — e
 *   o número de pronúncias é menor.
 * - **O que ISTO fecha** é a pergunta que estava sem resposta nenhuma: a cadeia
 *   whisper → conferência do gatilho → roteador funciona com voz que não é a de
 *   quem treinou o detector?
 *
 * ### Por que não sintetizar voz para completar a amostra
 *
 * Este projeto já refutou esse caminho por medição: `RepetibilidadeDaBancadaTest`
 * provou que o Piper **não é determinístico** (48 640 / 45 355 / 44 820 amostras
 * para a mesma frase), e a via de treinar com voz sintética foi descartada. Voz de
 * TTS medida e apresentada como recall de fala real seria número inventado — a
 * classe de mentira que este projeto passou a sessão inteira caçando.
 *
 * ```
 * adb push "Na escuta - *.wav" .../files/bench/vozes/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RecallDeQuatroLocutoresTest {

    private val pasta: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "bench/vozes",
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
     * **"na escuta" dito por quatro pessoas NÃO pode abrir canal.**
     *
     * A invariante do roadmap, agora com voz humana em vez de string minha.
     */
    @Test
    fun naEscutaDeQuatroVozesHUMANAS_naoAbreCanal(): Unit = runBlocking {
        Assume.assumeTrue("sem áudio dos locutores em ${pasta.path}", pasta.isDirectory)
        val arquivos = pasta.listFiles { f -> f.name.startsWith("Na_escuta") }?.sortedBy { it.name }
        Assume.assumeTrue("sem os WAVs de 'Na escuta'", !arquivos.isNullOrEmpty())

        val whisper = EscutaDoAgente.de(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        Assume.assumeTrue("whisper indisponível", whisper != null)

        val router = DeterministicIntentRouter()
        val linhas = mutableListOf<String>()
        var abriram = 0
        var confirmaramOGatilho = 0

        for (f in arquivos!!) {
            val pcm = lerWav(f)
            val texto = (whisper!!.transcribe(pcm, 16_000) as? Result.Success)?.value?.text.orEmpty()
            val conferencia = PalavraDeAtivacaoNaFala.conferir(texto)
            val intent = router.route(texto)
            val abriu = intent is Intent.AbrirTransmissao
            if (abriu) abriram++
            if (conferencia.confirmada) confirmaramOGatilho++
            linhas += "  %-28s gatilho=%-5s abre=%-5s  \"%s\"".format(
                f.name.removePrefix("Na_escuta_-_").removeSuffix(".wav"),
                conferencia.confirmada, abriu, texto.trim().take(70),
            )
        }

        val total = arquivos.size
        Log.i("ClaryonField", "NEGATIVO HUMANO — $total locutores dizendo \"na escuta, câmbio, desligo\"")
        linhas.forEach { Log.i("ClaryonField", it) }
        Log.i(
            "ClaryonField",
            "  gatilho confirmado ....... $confirmaramOGatilho/$total (esperado 0)\n" +
                "  abriu transmissão ........ $abriram/$total (esperado 0)\n" +
                "  Estes arquivos são de 17/08, de um desenho anterior: o áudio NÃO " +
                "contém o comando atual. Servem como NEGATIVO humano.",
        )

        org.junit.Assert.assertTrue(
            "fala humana dizendo apenas \"na escuta\" abriu canal em $abriram de " +
                "$total casos. É a invariante do roadmap quebrando com voz de " +
                "verdade — e \"na escuta\" é dito o dia inteiro no rádio",
            abriram == 0,
        )
    }

    /**
     * **A conferência do gatilho com quatro vozes.**
     *
     * Aqui sim há recall a medir: `Claryon - *` são as gravações da palavra de
     * ativação. O detector acústico foi treinado com a voz do Guido, e a pergunta é
     * se a segunda etapa — a transcrição — reconhece as outras três.
     */
    @Test
    fun oGatilhoEReconhecido_emVozesQueNaoTreinaramODetector(): Unit = runBlocking {
        Assume.assumeTrue("sem áudio", pasta.isDirectory)
        val arquivos = pasta.listFiles { f ->
            f.name.startsWith("Claryon_-_") && !f.name.contains("pergunta")
        }?.sortedBy { it.name }
        Assume.assumeTrue("sem os WAVs de 'Claryon'", !arquivos.isNullOrEmpty())

        val whisper = EscutaDoAgente.de(InstrumentationRegistry.getInstrumentation().targetContext)
        Assume.assumeTrue("whisper indisponível", whisper != null)

        var confirmados = 0
        val linhas = mutableListOf<String>()
        for (f in arquivos!!) {
            val texto = (whisper!!.transcribe(lerWav(f), 16_000) as? Result.Success)
                ?.value?.text.orEmpty()
            val ok = PalavraDeAtivacaoNaFala.conferir(texto).confirmada
            if (ok) confirmados++
            linhas += "  %-10s gatilho=%-5s  \"%s\"".format(
                f.name.removePrefix("Claryon_-_").removeSuffix(".wav"), ok, texto.trim().take(72),
            )
        }
        Log.i("ClaryonField", "GATILHO — ${arquivos.size} locutores, fala humana")
        linhas.forEach { Log.i("ClaryonField", it) }
        Log.i("ClaryonField", "  confirmados .............. $confirmados/${arquivos.size}")
        Log.i(
            "ClaryonField",
            "  ⚠️ NÃO é o aceite: microfone de celular, não fone HFP, e são " +
                "${arquivos.size} locutores contra as 30 pronúncias que o aceite pede.",
        )

        org.junit.Assert.assertTrue(
            "a conferência do gatilho não reconheceu NENHUMA das ${arquivos.size} " +
                "vozes — detalhe por locutor no logcat",
            confirmados >= 1,
        )
    }
}
