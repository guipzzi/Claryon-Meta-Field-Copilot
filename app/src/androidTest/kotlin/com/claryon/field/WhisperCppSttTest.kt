package com.claryon.field

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
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prova de que o **whisper.cpp nativo** funciona on-device: carrega o modelo que o
 * produto embarca e transcreve — no emulador arm64, sem rede. Valida a cadeia
 * JNI → C++ → texto.
 *
 * ## Esta classe passou meses reportando verde sem rodar
 *
 * O `Assume` exigia `models/ggml-tiny.bin`, e este repositório embarca
 * `models/ggml-small-q5_1.bin`. Assumção sempre falsa é teste sempre pulado, e
 * JUnit conta teste pulado como `OK`. Medido no emulador em 21/08: **`OK (1 test)`
 * em 0,008 s** — nenhuma transcrição, nenhuma diferença visível para um teste que
 * de fato passou.
 *
 * É a pergunta 3 do §6 do `CLAUDE.md` respondida da pior forma: o nome afirma que o
 * whisper transcreve português no aparelho, e o corpo não provava nada.
 *
 * O conserto não foi trocar a string. O nome do modelo saiu daqui e passou a vir de
 * `Modelos.WHISPER_ASSET`, que é de onde o produto o carrega — assim trocar o modelo
 * não deixa mais um teste para trás olhando para um caminho que ninguém mantém.
 *
 * ## A testemunha era da língua errada
 *
 * Este teste transcrevia `jfk.wav`, em inglês, e exigia as palavras "country" ou
 * "ask". Mas `jni.c:190` fixa `params.language = "pt"` — decisão deliberada, o
 * copiloto é de segurança pública brasileira. Alimentar inglês num decodificador
 * fixado em português devolvia *"e então, meu fellow americano… o que você pode
 * fazer para você?"*: o nativo funcionando perfeitamente, e o teste vermelho.
 *
 * O teste só passaria se o produto falasse uma língua que ele não fala. Testemunha
 * assim não certifica nada — e, pior, um dia alguém a faria passar trocando o
 * idioma do produto.
 *
 * A testemunha certa já está no APK: o Piper sintetiza português no próprio
 * aparelho. A ida e volta Piper → Whisper exercita exatamente o caminho de
 * produção, sem rede e sem `.wav` versionado.
 */
@RunWith(AndroidJUnit4::class)
class WhisperCppSttTest {

    // Contexto da INSTRUMENTAÇÃO (APK de teste) — onde vivem os assets de androidTest.
    private val ctx = InstrumentationRegistry.getInstrumentation().context

    /** Lê um WAV PCM 16-bit mono em ShortArray (cabeçalho RIFF de 44 bytes). */
    private fun readWavPcm(input: InputStream): ShortArray {
        val bytes = input.use { it.readBytes() }
        val data = bytes.copyOfRange(44, bytes.size)
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    /**
     * Ida e volta em português: o Piper fala, o Whisper ouve.
     *
     * O `ggml-tiny` é o menor modelo da família e erra acentuação e palavra rara —
     * por isso o aceite é sobre **palavras de conteúdo**, não sobre a frase
     * inteira. Exigir transcrição literal de um `tiny` seria um teste que quebra
     * por ruído, não por regressão.
     */
    /**
     * As palavras de conteúdo da frase de teste, e o mínimo para aceitar.
     *
     * Separado do corpo do teste **de propósito**: um critério de aceite que só
     * existe dentro do teste não pode ser conferido contra texto errado, e aí não
     * se sabe se ele discrimina alguma coisa. Ver [oCriterioDeAceiteRecusaLixo].
     */
    private fun aceita(texto: String): Boolean =
        listOf("central", "guarni", "caminho", "ocorr").count { texto.contains(it) } >= 2

    @Test
    fun whisperTranscrevePortuguesOnDevice() = runBlocking {
        // **O modelo é o que o produto embarca, e vem por `Modelos`.**
        //
        // Antes daqui saía `models/ggml-tiny.bin`, que este repositório NUNCA
        // embarcou: o asset é `models/ggml-small-q5_1.bin`. O `Assume` era portanto
        // sempre falso, e a classe devolvia `OK (1 test)` em 0,008 s — verde
        // indistinguível de teste que passou. Medido no emulador em 21/08.
        //
        // Ir por `Modelos.whisper` em vez de nomear o asset aqui é o conserto que
        // impede a recaída: o nome do arquivo passa a existir num lugar só
        // (`Modelos.WHISPER_ASSET`), e trocar o modelo do produto não deixa mais um
        // teste para trás olhando para um caminho que ninguém mantém. É o mesmo
        // caminho que `ConfiancaDoSttTest` e `VerificadorDoGatilhoTest` já usam — e
        // esses dois rodavam de verdade enquanto este dormia.
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente em ${Modelos.WHISPER_ASSET}", whisper != null)

        val alvo = InstrumentationRegistry.getInstrumentation().targetContext
        val piper = com.claryon.field.voice.Modelos.piper(alvo)
        Assume.assumeTrue("Piper ausente: sem ele não há fala em português para ouvir", piper != null)
        val dito = piper!!.synthesize("Central, a guarnição está a caminho da ocorrência.").getOrNull()
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", dito != null)

        // O Piper gera na taxa da voz; o whisper.cpp exige 16 kHz.
        val pcm = reamostrar(dito!!.samples, dito.sampleRateHz, 16_000)
        val resultado = whisper!!.transcribe(pcm, 16_000)
        val texto = (resultado as? Result.Success)?.value?.text.orEmpty().trim().lowercase()
        android.util.Log.i("ClaryonField", "WHISPER PT: \"$texto\"")

        assertTrue("o STT não devolveu texto: $resultado", texto.isNotBlank())
        assertTrue(
            "esperava ao menos 2 palavras de conteúdo da frase falada; veio: \"$texto\"",
            aceita(texto),
        )
    }

    /**
     * **O contra-teste: o critério de aceite RECUSA transcrição errada.**
     *
     * Sem isto, `whisperTranscrevePortuguesOnDevice` passaria também se
     * [aceita] fosse largo demais — e "o teste passa" não distinguiria whisper
     * funcionando de critério frouxo. É a pergunta 3 do §6: *se o teste passaria
     * com o defeito de volta, ele não testa o defeito.*
     *
     * Os negativos não são aleatórios. Cada um é um jeito real de o STT errar:
     * silêncio transcrito como nada, alucinação de modelo pequeno, e — o mais
     * perigoso — uma frase do MESMO domínio operacional, que compartilha registro e
     * vocabulário com a esperada sem ser ela.
     */
    @Test
    fun oCriterioDeAceiteRecusaLixo() {
        val deveAceitar = listOf(
            "central, a guarnição está a caminho da ocorrência.",
            "central a guarnicao esta a caminho da ocorrencia",
            // Um erro de acentuação e um de palavra rara ainda passam: o aceite é
            // sobre palavras de conteúdo, não sobre a frase literal.
            "central, a guarniçao esta a caminho da ocorrencia",
        )
        val deveRecusar = listOf(
            "",
            "                    ",
            "obrigado por assistir",                       // alucinação clássica
            "you",                                          // idem, em modelo pequeno
            "solicito apoio imediato na avenida brasil",    // MESMO domínio, outra frase
            "a viatura seguiu pela rodovia",                // idem
        )
        deveAceitar.forEach {
            assertTrue("o critério recusou transcrição BOA: \"$it\"", aceita(it))
        }
        deveRecusar.forEach {
            assertTrue(
                "o critério ACEITOU transcrição errada: \"$it\" — enquanto isto for " +
                    "verdade, o teste de transcrição passa por frouxidão e não por " +
                    "o whisper ter funcionado",
                !aceita(it),
            )
        }
    }

    /** Linear: o teste prova transcrição, não fidelidade de reamostragem. */
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

    @org.junit.Ignore(
        "A testemunha é inglesa e o decodificador é fixado em pt (jni.c:190). " +
            "Mantido como registro do porquê; o teste vivo é whisperTranscrevePortuguesOnDevice.",
    )
    @Test
    fun whisperTranscreveJfkOnDevice() = runBlocking {
        // O asset aqui também apontava para `ggml-tiny`, que não existe. Corrigido
        // junto, embora este teste esteja `@Ignore`: um dia alguém tira o `@Ignore`
        // por um bom motivo, e encontrar o caminho errado esperando por ele é
        // reinstalar a mesma armadilha com data marcada. `@Ignore` é honesto — ele
        // aparece como pulado com o motivo escrito —, mas isso não é licença para o
        // corpo apodrecer.
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente em ${Modelos.WHISPER_ASSET}", whisper != null)

        val pcm = readWavPcm(ctx.assets.open("jfk.wav"))
        val texto = (whisper!!.transcribe(pcm, 16_000) as? Result.Success)
            ?.value?.text.orEmpty().trim().lowercase()
        android.util.Log.i("WhisperCppSttTest", "Transcrição: $texto")
        assertTrue("texto vazio", texto.isNotBlank())
        // JFK: "...ask not what your country can do for you..."
        assertTrue(
            "esperava conteúdo do discurso; veio: $texto",
            texto.contains("country") || texto.contains("ask"),
        )
    }
}
