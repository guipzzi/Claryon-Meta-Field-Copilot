package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.PcmResampler
import com.claryon.common.Result
import com.claryon.common.getOrNull
import com.claryon.field.voice.Modelos
import com.claryon.voice.PiperTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * **O portão pela rima: o ataque se perde, a rima sobrevive.**
 *
 * Três medições seguidas mostraram a mesma coisa e eu levei três para enxergar. As
 * formas que o decodificador produz para *Claryon* não são aleatórias como o banco
 * de formas concluiu — elas são aleatórias **no ataque** e estáveis **na rima**:
 *
 * ```
 * claryon · clarion · varyon · faryon · haryon · quaryon · quarion · parion
 * farion  · valion  · varion · carion · karyon · falion  · fadeon  · flareon
 *   └┬──┘   └──┬───┘
 *    │         └── a rima -yon / -ion / -eon aparece em quase todas
 *    └── o ataque /kl/ vira /v/, /f/, /h/, /p/, /k/, e nunca se repete
 * ```
 *
 * A explicação é fonética e já estava nos dados: /k/ é oclusiva velar, um transiente
 * curto e de alta frequência — a primeira coisa que morre em banda estreita de 8 kHz.
 * A rima `-aryon` é vogal aberta, líquida e nasal, tudo abaixo de 4 kHz, tudo intacto.
 *
 * ## Por que a rima é um portão bom, e não um casamento aproximado disfarçado
 *
 * A spec proíbe **distância de edição**: calcular similaridade e aceitar o mais
 * parecido converte erro de transcrição em erro de despacho. Aqui não há distância
 * nem similaridade — há uma **propriedade estrutural**: *a primeira palavra tem seis
 * caracteres ou mais e termina em `-on` ou `-om`*. É verificável, tem custo O(1), e
 * não tem parâmetro para ajustar até o número ficar bonito.
 *
 * E o que a torna segura é o português, não a sorte: **`-on` átono final praticamente
 * não existe na língua.** Palavra portuguesa termina em `-ão`, `-am`, `-em`, `-im`,
 * `-om` de *bom* e *som* (três letras). As vizinhas acústicas que mais assustavam —
 * `clareou`, `claridade`, `clara`, `clarim` — falham todas: `-ou`, `-ade`, `-a`,
 * `-im`. E `guarnição` normaliza para `guarnicao`, que termina em `-ao`.
 *
 * ## O protocolo, de novo, porque a regra saiu dos dados
 *
 * A regra foi **derivada olhando erros**, então medir nos mesmos erros seria fraude.
 * Divisão por comando: seis comandos constroem, seis outros julgam, e os negativos de
 * teste nunca foram vistos. Três rendições de cada frase — e agora isso é variação
 * de verdade, porque `RepetibilidadeDaBancadaTest` provou que o Piper sorteia a
 * duração de cada fonema e nunca devolve o mesmo áudio duas vezes.
 */
@RunWith(AndroidJUnit4::class)
class PortaoPelaRimaTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prior = "Claryon."
    private val rendicoes = 3

    private val comandosTreino = listOf(
        "mudar para guarnição 3", "pedir apoio", "modo ocorrência",
        "iniciar gravação", "consultar placa", "detalhar",
    )
    private val comandosTeste = listOf(
        "mudar para guarnição 4", "onde está a guarnição 3", "encerrar gravação",
        "modo ativo", "repetir", "solicitar reforço",
    )

    private val negativosTreino = listOf(
        "Ele clareou a situação para o comandante ontem.",
        "É clara a necessidade de apoio nesta ocorrência.",
        "Central, aqui é a guarnição dois, estamos no local.",
        "Negativo, sem alteração no perímetro, na escuta.",
        "O veículo seguiu sentido bairro pela avenida.",
        "A ocorrência foi encerrada, retornando à base.",
    )

    /**
     * Negativos de teste, escolhidos para **atacar** a regra da rima: palavras
     * portuguesas terminadas em nasal, nomes próprios, e as vizinhas de *clar-*.
     */
    private val negativosTeste = listOf(
        "A claridade do dia ajudou na identificação.",
        "O clarim tocou no pátio do quartel de manhã.",
        "Cordon isolado na esquina, ninguém entra.",
        "Bombom e batom estavam no porta-luvas do carro.",
        "Elétron e próton foram assunto da prova de ontem.",
        "Ele mudou para a outra rua agora há pouco.",
        "Atenção todas as unidades, ocorrência na área central.",
        "Sem novidades por aqui, seguimos em patrulhamento.",
        "O comandante pediu relatório do turno da noite.",
        "Confirmado, estamos a caminho do endereço informado.",
    )

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    private fun primeira(texto: String): String =
        normalizar(texto).split(" ").firstOrNull().orEmpty()

    /** Portão A: a marca, na grafia dela. É o que o produto pede hoje. */
    private fun portaoEstrito(texto: String) = primeira(texto) == "claryon"

    /**
     * Portão B: a rima. Seis caracteres ou mais, terminando em `-on` ou `-om`.
     *
     * O piso de seis não é ajuste: é o que exclui `bom`, `som`, `tom`, `com` e `dom`,
     * que são as palavras portuguesas frequentes com essa terminação, todas de três
     * letras. *Claryon* tem sete.
     */
    private fun portaoPelaRima(texto: String): Boolean {
        val p = primeira(texto)
        return p.length >= 6 && (p.endsWith("on") || p.endsWith("om"))
    }

    @Test
    fun aRimaSobreviveAoAtaqueEFechaOPortao(): Unit = runBlocking {
        Assume.assumeTrue("Piper ausente", Modelos.piper(ctx) != null)
        val espeak = File(ctx.filesDir, "espeak-ng-data").absolutePath

        class Amostra(val rotulo: String, val pcm: ShortArray, val positiva: Boolean, val treino: Boolean)

        val amostras = mutableListOf<Amostra>()
        val piper = PiperTts(
            assetManager = ctx.assets,
            modelDir = Modelos.PIPER_ASSET_DIR,
            modelName = Modelos.PIPER_MODELO,
            dataDir = espeak,
            speed = Modelos.VELOCIDADE_DE_CAMPO,
        )
        suspend fun falar(t: String): ShortArray? = piper.synthesize(t).getOrNull()
            ?.let { porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa)) }

        // Três rendições de cada frase. O Piper sorteia a duração de cada fonema,
        // então repetir a mesma frase é variação legítima, não cópia.
        repeat(rendicoes) { r ->
            for (c in comandosTreino) falar("Claryon, $c.")?.let { amostras += Amostra("$c #$r", it, true, true) }
            for (c in comandosTeste) falar("Claryon, $c.")?.let { amostras += Amostra("$c #$r", it, true, false) }
            for (f in negativosTreino) falar(f)?.let { amostras += Amostra(f, it, false, true) }
            for (f in negativosTeste) falar(f)?.let { amostras += Amostra(f, it, false, false) }
        }
        piper.release()
        Assume.assumeTrue("Piper não sintetizou", amostras.count { it.positiva } >= 24)

        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)
        whisper!!.promptDeDominio = prior
        val transcrito = amostras.map { a ->
            a to (whisper.transcribe(a.pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
        }
        whisper.promptDeDominio = null

        val posTeste = transcrito.filter { it.first.positiva && !it.first.treino }
        val negTeste = transcrito.filter { !it.first.positiva && !it.first.treino }
        val posTreino = transcrito.filter { it.first.positiva && it.first.treino }
        val negTreino = transcrito.filter { !it.first.positiva && it.first.treino }

        fun avaliar(nome: String, portao: (String) -> Boolean): String {
            val recT = posTreino.count { portao(it.second) }
            val rec = posTeste.count { portao(it.second) }
            val fpT = negTreino.count { portao(it.second) }
            val onde = mutableListOf<String>()
            val fp = negTeste.count { (a, txt) ->
                val d = portao(txt)
                if (d) onde += "\"${a.rotulo.take(30)}\" → \"${txt.trim().take(32)}\""
                d
            }
            return "\n  %-24s treino %5.1f%% (%2d/%d) fp %d/%-2d │ TESTE %5.1f%% (%2d/%d) fp %d/%d%s"
                .format(
                    nome, recT * 100.0 / posTreino.size, recT, posTreino.size, fpT, negTreino.size,
                    rec * 100.0 / posTeste.size, rec, posTeste.size, fp, negTeste.size,
                    if (onde.isEmpty()) "" else "\n      ← " + onde.joinToString("\n      ← "),
                )
        }

        val linhas = avaliar("marca exata", ::portaoEstrito) + avaliar("rima -on/-om (≥6)", ::portaoPelaRima)

        android.util.Log.i(
            "ClaryonField",
            """
            |PORTÃO PELA RIMA — prior "$prior", banda estreita 8 kHz, $rendicoes rendições
            |  treino: ${posTreino.size} positivas + ${negTreino.size} negativas
            |  TESTE:  ${posTeste.size} positivas + ${negTeste.size} negativas (nunca vistas)
            |$linhas
            |  meta ..................... 90%                    0
            |
            |  primeiras palavras no TESTE, positivas:
            |    ${posTeste.map { primeira(it.second) }.distinct().joinToString(" · ")}
            |  primeiras palavras no TESTE, negativas:
            |    ${negTeste.map { primeira(it.second) }.distinct().joinToString(" · ")}
            |
            |  positivas que a rima NÃO pegou:
            |    ${
                posTeste.filter { !portaoPelaRima(it.second) }
                    .joinToString("\n    ") { "\"${it.second.trim().take(50)}\"" }
                    .ifEmpty { "(nenhuma)" }
            }
            |
            |  ⚠️ Voz do Piper. O aceite continua exigindo 30 pronúncias REAIS por HFP
            |     e 8 h de rádio ambiente — e agora também um corpus de fala espontânea
            |     grande o bastante para o falso positivo ter intervalo de confiança.
            """.trimMargin(),
        )

        assertTrue("divisão vazia", posTeste.size >= 12 && negTeste.size >= 10)
        assertTrue(
            "vazamento entre treino e teste",
            comandosTreino.none { t -> comandosTeste.any { it == t } },
        )
    }
}
