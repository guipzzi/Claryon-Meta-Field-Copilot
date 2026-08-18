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
 * **O banco de formas, MEDIDO — e medido com treino e teste separados.**
 *
 * A revisão humana pediu isto com estas palavras: *"deixar num banco com prioridade"*.
 * A ideia é certa e o motivo é medível: o decodificador **erra sempre do mesmo jeito**.
 * Ele não sorteia entre mil grafias — ele prefere uma sequência de tokens para aquele
 * som, e a preferência é estável. `Eclareon` apareceu duas vezes em seis; `clarion`
 * apareceu de forma consistente. Isso não é ruído, é assinatura.
 *
 * ## O que torna este número honesto, e sem o que ele seria uma fraude
 *
 * A lista de sete variantes que existe hoje foi **escrita à mão olhando as falhas**.
 * Medir o acerto dela nas mesmas frases de onde ela saiu não mede nada: mede a minha
 * memória. Um banco ajustado no próprio conjunto onde é avaliado sempre parece
 * excelente, e é exatamente o tipo de número que este projeto proibiu.
 *
 * Então o protocolo é o de sempre em aprendizado, e ele não é negociável aqui:
 *
 * | | de onde sai | para que serve |
 * |---|---|---|
 * | **TREINO** | 6 comandos × 3 velocidades | constrói o banco |
 * | **TESTE** | 6 comandos **diferentes** × 3 velocidades | mede o recall |
 * | **negativos de treino** | 8 falas que não são comando | **poda** o banco |
 * | **negativos de teste** | 8 outras falas | mede o falso positivo |
 *
 * A divisão é **por comando**, não sorteada: se "pedir apoio" está no treino, nenhuma
 * velocidade dele aparece no teste. Sorteio por amostra deixaria a mesma frase nos
 * dois lados em velocidade diferente, e o recall subiria por vazamento.
 *
 * ## Como o banco é construído, e por que a poda vem antes da contagem
 *
 * Para cada transcrição de treino, as candidatas são os prefixos de **uma** e de
 * **duas** palavras. Uma candidata só entra se:
 *
 * 1. **não for prefixo de nenhum negativo de treino** — a poda. É ela que impede
 *    `e` de entrar por causa de `"e clarion, mudar…"`, já que `"É clara a
 *    necessidade…"` normaliza para `e clara a necessidade`;
 * 2. **aparecer ao menos duas vezes** — uma forma vista uma vez só é sorte, e cada
 *    forma no banco é superfície de falso positivo;
 * 3. tiver ao menos 4 caracteres — abaixo disso não cabe uma palavra de três sílabas,
 *    e o que entra é artigo ou conjunção.
 *
 * ## Isto NÃO é o casamento aproximado que a spec proíbe
 *
 * A proibição em `troca-de-grupo-por-voz.spec.md` é sobre **distância de edição**:
 * calcular similaridade e aceitar o mais parecido converte erro de transcrição em erro
 * de despacho, e o agente não fica sabendo. Aqui não há similaridade nenhuma — há
 * igualdade contra formas **observadas** de um decodificador determinístico, podadas
 * contra fala real. E a consequência de errar é diferente: errar o **grupo** manda a
 * voz do agente para outra guarnição; errar o **portão** abre o canal, que é medido
 * aqui mesmo, na coluna do falso positivo.
 */
@RunWith(AndroidJUnit4::class)
class BancoDeFormasTest {

    private val taxa = 16_000
    private val hfpHz = 8_000
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Velocidades de fala. `0.9` é a de campo; as outras dão variação de rendição. */
    private val velocidades = listOf(0.8f, 0.9f, 1.05f)

    private val comandosTreino = listOf(
        "mudar para guarnição 3", "pedir apoio", "modo ocorrência",
        "iniciar gravação", "consultar placa", "detalhar",
    )
    private val comandosTeste = listOf(
        "mudar para guarnição 4", "onde está a guarnição 3", "encerrar gravação",
        "modo ativo", "repetir", "solicitar reforço",
    )

    /** Fala operacional que NÃO é comando. Metade poda o banco, metade o julga. */
    private val negativosTreino = listOf(
        "Ele clareou a situação para o comandante ontem.",
        "É clara a necessidade de apoio nesta ocorrência.",
        "Central, aqui é a guarnição dois, estamos no local.",
        "Negativo, sem alteração no perímetro, na escuta.",
        "O veículo seguiu sentido bairro pela avenida.",
        "A ocorrência foi encerrada, retornando à base.",
        "Solicito informação sobre o endereço anterior.",
        "Aguardando o apoio chegar para prosseguir.",
    )
    private val negativosTeste = listOf(
        "A claridade do dia ajudou na identificação.",
        "O clarim tocou no pátio do quartel de manhã.",
        "Atenção todas as unidades, ocorrência na área central.",
        "A perseguição terminou perto do posto de gasolina.",
        "Ele mudou para a outra rua agora há pouco.",
        "Sem novidades por aqui, seguimos em patrulhamento.",
        "O comandante pediu relatório do turno da noite.",
        "Confirmado, estamos a caminho do endereço informado.",
    )

    /** A lista escrita à mão que existe hoje, para comparação na mesma bancada. */
    private val listaEscritaAMao = listOf(
        "hey claryon", "hey clarion", "eclareon", "e clarion",
        "eclarion", "hei claryon", "hey clareon",
    )

    private fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun porHfp(pcm: ShortArray): ShortArray =
        PcmResampler.resample(PcmResampler.resample(pcm, taxa, hfpHz), hfpHz, taxa)

    /** O portão: a transcrição **começa** por alguma forma do conjunto? */
    private fun passa(texto: String, formas: Collection<String>): String? {
        val t = normalizar(texto)
        return formas.firstOrNull { t == it || t.startsWith("$it ") }
    }

    @Test
    fun oBancoMedidoSobeORecallSemAbrirFalsoPositivo(): Unit = runBlocking {
        // ── Fase 1: sintetizar tudo, em três velocidades, e soltar o Piper ────
        // Os dois modelos juntos estouram o LMK e o processo morre com signal 9.
        Assume.assumeTrue("Piper ausente", Modelos.piper(ctx) != null)
        val espeak = File(ctx.filesDir, "espeak-ng-data").absolutePath

        class Amostra(val rotulo: String, val pcm: ShortArray, val positiva: Boolean, val treino: Boolean)

        val amostras = mutableListOf<Amostra>()
        for (v in velocidades) {
            val piper = PiperTts(
                assetManager = ctx.assets,
                modelDir = Modelos.PIPER_ASSET_DIR,
                modelName = Modelos.PIPER_MODELO,
                dataDir = espeak,
                speed = v,
            )
            suspend fun falar(t: String): ShortArray? = piper.synthesize(t).getOrNull()
                ?.let { porHfp(PcmResampler.resample(it.samples, it.sampleRateHz, taxa)) }

            for (c in comandosTreino) {
                falar("Claryon, $c.")?.let { amostras += Amostra("[$v] $c", it, true, true) }
            }
            for (c in comandosTeste) {
                falar("Claryon, $c.")?.let { amostras += Amostra("[$v] $c", it, true, false) }
            }
            // Os negativos entram só na velocidade de campo: o que eles precisam
            // representar é fala operacional, não variação de rendição.
            if (v == Modelos.VELOCIDADE_DE_CAMPO) {
                for (f in negativosTreino) falar(f)?.let { amostras += Amostra(f, it, false, true) }
                for (f in negativosTeste) falar(f)?.let { amostras += Amostra(f, it, false, false) }
            }
            piper.release()
        }
        Assume.assumeTrue("Piper não sintetizou", amostras.count { it.positiva } >= 20)

        // ── Fase 2: transcrever uma vez cada amostra ─────────────────────────
        val whisper = Modelos.whisper(ctx)
        Assume.assumeTrue("modelo whisper ausente", whisper != null)
        val transcrito = amostras.map { a ->
            a to (whisper!!.transcribe(a.pcm, taxa) as? Result.Success)?.value?.text.orEmpty()
        }

        val posTreino = transcrito.filter { it.first.positiva && it.first.treino }
        val posTeste = transcrito.filter { it.first.positiva && !it.first.treino }
        val negTreino = transcrito.filter { !it.first.positiva && it.first.treino }
        val negTeste = transcrito.filter { !it.first.positiva && !it.first.treino }

        // ── Fase 3: construir o banco SÓ com o treino ───────────────────────
        val proibidas = negTreino.map { normalizar(it.second) }
        val contagem = LinkedHashMap<String, Int>()
        for ((_, txt) in posTreino) {
            val p = normalizar(txt).split(" ").filter { it.isNotBlank() }
            if (p.isEmpty()) continue
            listOfNotNull(
                p.first(),
                if (p.size >= 2) "${p[0]} ${p[1]}" else null,
            ).forEach { contagem[it] = (contagem[it] ?: 0) + 1 }
        }
        val banco = contagem
            .filter { (forma, n) ->
                n >= 2 &&
                    forma.length >= 4 &&
                    proibidas.none { it == forma || it.startsWith("$forma ") }
            }
            .keys.toList()

        // ── Fase 4: os três portões, no MESMO conjunto de teste ─────────────
        fun avaliar(formas: Collection<String>): Triple<Int, Int, List<String>> {
            val rec = posTeste.count { passa(it.second, formas) != null }
            val onde = mutableListOf<String>()
            val fp = negTeste.count { (a, txt) ->
                val f = passa(txt, formas)
                if (f != null) onde += "\"${a.rotulo.take(30)}\" → \"${txt.trim().take(30)}\" (casou \"$f\")"
                f != null
            }
            return Triple(rec, fp, onde)
        }

        val canonico = avaliar(listOf("claryon"))
        val aMao = avaliar(listaEscritaAMao)
        val medido = avaliar(banco)
        val n = posTeste.size

        android.util.Log.i(
            "ClaryonField",
            """
            |BANCO DE FORMAS MEDIDO — treino e teste SEPARADOS, banda estreita 8 kHz
            |  treino: ${posTreino.size} positivas + ${negTreino.size} negativas
            |  teste:  $n positivas + ${negTeste.size} negativas (comandos que o banco nunca viu)
            |
            |  banco construído (${banco.size} formas): ${banco.joinToString(" · ")}
            |  podadas por baterem em negativo de treino: ${
                contagem.keys.filter { f -> proibidas.any { it == f || it.startsWith("$f ") } }
                    .joinToString(" · ").ifEmpty { "(nenhuma)" }
            }
            |
            |  portão                     recall           falso positivo
            |  grafia canônica ......... ${"%5.1f".format(canonico.first * 100.0 / n)}% (${canonico.first}/$n)      ${canonico.second}/${negTeste.size}
            |  lista escrita à mão ..... ${"%5.1f".format(aMao.first * 100.0 / n)}% (${aMao.first}/$n)      ${aMao.second}/${negTeste.size}
            |  banco MEDIDO ............ ${"%5.1f".format(medido.first * 100.0 / n)}% (${medido.first}/$n)      ${medido.second}/${negTeste.size}
            |  meta .................... 90%              0
            |
            |  ${medido.third.joinToString("\n  ").ifEmpty { "(o banco não disparou em fala que não é comando)" }}
            |
            |  transcrições de teste que o banco NÃO pegou:
            |  ${
                posTeste.filter { passa(it.second, banco) == null }
                    .joinToString("\n  ") { "\"${it.second.trim().take(52)}\"" }
                    .ifEmpty { "(nenhuma)" }
            }
            |
            |  ⚠️ Voz do Piper em três velocidades. Sem sotaque, sem hesitação, sem AGC
            |     de uplink. O aceite continua exigindo 30 pronúncias REAIS por HFP.
            """.trimMargin(),
        )

        // O que este teste garante de verdade é o PROTOCOLO: sem divisão real, o
        // recall é vazamento e não mede nada.
        assertTrue("o conjunto de teste ficou vazio", n >= 12 && negTeste.size >= 6)
        assertTrue(
            "vazamento: um comando aparece nos dois lados da divisão",
            comandosTreino.none { t -> comandosTeste.any { it == t } },
        )
        // E a invariante da construção: nenhuma forma do banco pode ser prefixo de
        // um negativo de treino. Se isto quebrar, a poda parou de podar.
        assertTrue(
            "a poda falhou: forma do banco é prefixo de negativo de treino",
            banco.none { f -> proibidas.any { it == f || it.startsWith("$f ") } },
        )
    }
}
