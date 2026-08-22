package com.claryon.field

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.ActionOutcome
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.audio.SaidaUnica
import com.claryon.field.voice.Modelos
import com.claryon.field.voice.SileroVoiceActivityDetector
import com.claryon.field.voice.TelemetriaDoCicloDeVoz
import com.claryon.field.voice.VoiceCycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **As duas metas que mais importam deixam de ser adjetivo.**
 *
 * `fim da fala → earcon` (≤500 ms) e `fim da fala → resposta falada` (≤2000 ms)
 * apareciam como *"sem amostras"* em todos os relatórios do projeto. A causa não
 * era o emulador: era **fiação**.
 *
 * Os dois marcos (`EARCON_PLAYED` e `RESPONSE_FIRST_AUDIO`) são emitidos por
 * `SaidaUnica.marcarReproducao` no instante em que o PCM entra no `AudioTrack` —
 * de propósito, para não medir enfileiramento. Mas todo teste até aqui criava uma
 * `TelemetriaDoCicloDeVoz` própria e um `emitir` que apenas acumulava numa lista.
 * Os marcos nunca aconteciam, o relatório dizia "sem amostras", e "sem amostras"
 * era lido como limitação de bancada.
 *
 * Este teste usa a **saída de produção**: `SaidaUnica.de(app)` como `emitir`,
 * `SaidaUnica.telemetriaDoCiclo` como telemetria, e `abrirCiclo` com o **mesmo**
 * `cicloId` que o `VoiceCycle` vai marcar. É exatamente a fiação de
 * `CopilotoViewModel:472-499`, e é o que faz som sair de verdade.
 *
 * ## O que cada meta mede, e por que uma pode passar hoje
 *
 * **`fim da fala → earcon` NÃO depende do STT.** O `VoiceCycle` emite `CANAL_FECHADO`
 * *antes* de chamar o whisper. Essa meta mede: hangover do VAD (600 ms de silêncio
 * que o Silero exige para fechar a janela) + síntese do earcon + subida da rota
 * SCO + primeiro `write` no `AudioTrack`. Só o hangover já come 600 dos 500 ms —
 * então o número interessa mesmo quando reprova, porque diz **qual parcela** estoura.
 *
 * **`fim da fala → resposta falada` inclui o STT**, que hoje custa segundos. Vai
 * reprovar por ordem de grandeza. O valor de medir não é o veredito: é a
 * **decomposição**. Com os cinco marcos é possível derivar
 * `RESPONSE_FIRST_AUDIO − ACTION_DONE` = síntese do Piper + subida de rota + fila,
 * que ninguém neste projeto jamais mediu e que está inteira no caminho crítico.
 *
 * ## Contaminação entre testes, declarada
 *
 * `SaidaUnica` é dona de processo e não tem `limpar()`. Este teste **suja** o
 * singleton: a fila, o supressor e a telemetria seguem vivos depois dele. Como a
 * telemetria é acumulativa por ciclo e cada rodada abre um `cicloId` novo, isso não
 * falseia a medição — mas um teste futuro que dependa de fila vazia vai quebrar
 * aqui e é bom saber por quê.
 */
@RunWith(AndroidJUnit4::class)
class CicloDeVozNoAparelhoTest {

    private val taxa = 16_000
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private suspend fun falar(frase: String): ShortArray? {
        val piper = Modelos.piper(app) ?: return null
        val audio = piper.synthesize(frase).getOrNull()
        piper.release()
        return audio?.let { reamostrar(it.samples, it.sampleRateHz, taxa) }
    }

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

    private fun quadrosCom(fala: ShortArray): Flow<ShortArray> {
        val tudo = ShortArray(taxa / 4) + fala + ShortArray(taxa)
        return tudo.toList().chunked(320) { it.toShortArray() }.asFlow()
    }

    /**
     * Executor que devolve uma resposta **falada** e curta.
     *
     * Tem de falar: `RESPONSE_FIRST_AUDIO` só é marcado para `Sound.Speech`
     * (`SaidaUnica:186`). Um outcome que só emite earcon — `GravacaoIniciada`, por
     * exemplo — deixaria a meta de 2000 ms sem amostra e daria a impressão de que a
     * fiação continua quebrada.
     */
    private fun executorQueFala() = object : IntentExecutor {
        override suspend fun execute(intent: Intent): ActionOutcome =
            ActionOutcome.GrupoTrocado("GTA-4 Bravo")
    }

    @Test
    fun asDuasMetasDoCicloDeVoz_ganhamAmostra(): Unit = runBlocking {
        val fala = falar("Claryon, mudar para guarnição 4.")
        Assume.assumeTrue("Piper ausente: sem fala não há ciclo", fala != null)

        val whisper = Modelos.whisper(app)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)

        val telemetria = SaidaUnica.telemetriaDoCiclo
        val saida = SaidaUnica.de(app)
        // O MESMO aquecimento que produção faz ao abrir o ciclo. Sem ele, a
        // primeira resposta falada paga a construção do motor neural DENTRO da
        // meta de 2000 ms — foi o que a primeira rodada deste teste mediu: 1218 ms
        // de "síntese + rota + fila" contra 1021 ms do whisper inteiro.
        SaidaUnica.aquecer(app)
        val cicloId = "bancada-ciclo-1"

        // A ordem importa e é a de produção (`CopilotoViewModel:472`): abrir o ciclo
        // ANTES de qualquer emissão. `marcarNoCicloCorrente` escreve no ciclo
        // corrente, e sem `abrirCiclo` não há corrente — os marcos cairiam no vazio.
        telemetria.abrirCiclo(cicloId)

        val ciclo = VoiceCycle(
            pcmInput = { quadrosCom(fala!!) },
            vad = SileroVoiceActivityDetector(assets = app.assets, sampleRateHz = taxa),
            sttFn = { pcm, sr ->
                (whisper!!.transcribe(pcm, sr) as? Result.Success)?.value?.text.orEmpty()
            },
            router = DeterministicIntentRouter(),
            executor = executorQueFala(),
            // **A saída de produção.** É esta linha que faz os dois marcos existirem.
            emitir = { u -> saida.emitir(u) },
            sampleRateHz = taxa,
            telemetria = telemetria,
            novoCicloId = { cicloId },
        )

        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(120_000) { ciclo.runOnce() }
        assertNotNull("o ciclo não fechou em 120 s", r)

        // A fala é enfileirada, não instantânea: `runOnce` retorna quando a
        // `Utterance` entra na fila, e `RESPONSE_FIRST_AUDIO` só é marcado quando o
        // PCM chega ao `AudioTrack`. Sem esta espera, a meta de 2000 ms sairia "sem
        // amostras" por corrida — o modo de falhar mais enganoso possível, porque
        // pareceria o defeito de fiação que este teste veio consertar.
        val prazo = System.currentTimeMillis() + 30_000
        while (
            telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_RESPOSTA) == null &&
            System.currentTimeMillis() < prazo
        ) {
            kotlinx.coroutines.delay(250)
        }

        val earcon = telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_EARCON)
        val resposta = telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_RESPOSTA)
        val stt = telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.STT)
        val acao = telemetria.medicao(TelemetriaDoCicloDeVoz.Transicao.ACAO)

        // A decomposição é o produto deste teste. O veredito de cada meta é
        // secundário: o que muda a próxima decisão é saber QUAL parcela estoura.
        val sintesePlusRota = if (resposta != null && stt != null && acao != null) {
            "${resposta.p50 - stt.p50 - acao.p50} ms (derivado: resposta − STT − ação)"
        } else {
            "indisponível"
        }

        android.util.Log.i(
            "ClaryonField",
            """
            |CICLO DE VOZ NO APARELHO — as duas metas com amostra
            |  transcrito ............... "${r!!.transcricao}"
            |  outcome .................. ${r.outcome}
            |  parede total ............. ${System.currentTimeMillis() - t0} ms
            |
            |  fim da fala → earcon ..... ${earcon ?: "SEM AMOSTRA"}   (meta ≤ 500 ms)
            |     └─ inclui 600 ms de hangover do Silero, que já estoura a meta sozinho
            |  fim da fala → resposta ... ${resposta ?: "SEM AMOSTRA"}   (meta ≤ 2000 ms)
            |  transcrição (whisper) .... ${stt ?: "sem amostra"}
            |  execução da ação ......... ${acao ?: "sem amostra"}
            |  síntese TTS + rota + fila  $sintesePlusRota
            |
            |${telemetria.relatorio().prependIndent("  ")}
            """.trimMargin(),
        )

        // **A asserção é sobre o INSTRUMENTO, não sobre a meta.** As metas reprovam
        // hoje e reprovam por causa do STT — isso está registrado no `ESTADO.md`.
        // O que este teste garante é que elas passaram a ter número: uma meta sem
        // amostra é indistinguível de uma meta atingida para quem lê o relatório, e
        // era assim que este projeto vinha lendo as duas mais importantes.
        assertNotNull(
            "`fim da fala → earcon` continua SEM AMOSTRA — a fiação da telemetria " +
                "não está ligada à saída de produção",
            earcon,
        )
        assertNotNull(
            "`fim da fala → resposta falada` continua SEM AMOSTRA — verifique se o " +
                "outcome usado produz Sound.Speech (SaidaUnica:186 só marca fala)",
            resposta,
        )
        assertTrue("earcon com duração absurda: $earcon", earcon!!.p50 in 1..60_000)
        assertTrue(
            "a resposta tem de vir DEPOIS do earcon: earcon=$earcon resposta=$resposta",
            resposta!!.p50 >= earcon.p50,
        )
    }
}
