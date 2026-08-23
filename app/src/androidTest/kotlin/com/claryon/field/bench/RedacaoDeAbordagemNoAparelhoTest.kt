package com.claryon.field.bench

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.field.norma.RedacaoDoCopiloto
import com.claryon.llm.GuardaDaRedacao
import com.claryon.llm.PedidoDeRedacao
import com.claryon.llm.RedatorLlamaCpp
import java.io.File
import java.text.Normalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

/**
 * **A Etapa B medida sobre as perguntas de abordagem: o que o modelo escreve
 * quando a Etapa A entrega o artigo que ela de fato recupera.**
 *
 * ## O que este arquivo mede, e o que ele não mede
 *
 * Mede **três** coisas por geração, e as três são números, não impressões:
 *
 *  1. **Cifra sem lastro** — dígito na saída que não existe na fonte. É o
 *     indicador de dano: um `"acima de 10 gramas"` sobre um artigo que não fixa
 *     gramatura é um critério inventado que pode fundamentar um flagrante.
 *  2. **O guarda aprovaria?** — [GuardaDaRedacao] com o limiar de produção. A
 *     geração roda com o guarda DESLIGADO para o texto cru chegar ao log; o
 *     julgamento acontece aqui, explícito.
 *  3. **Viraria ação?** — a saída passa pelo [DeterministicIntentRouter]. Em
 *     21/08 mediu-se 2 de 3; este arquivo repete a medida em escala.
 *
 * **Não mede** se o texto é fiel ao artigo. Isso não é automatizável com honestidade
 * — um casamento lexical chamaria de fiel a frase *"não há nada que aconteça com
 * quem dirige embriagado"*, que passou pelo guarda em 21/08 justamente por usar o
 * léxico da fonte. A classificação de fidelidade é humana, feita lendo o log.
 *
 * ## Como rodar
 *
 * ```
 * ./gradlew :core-knowledge:test --tests '&#42;DespejoDeAbordagemTest&#42;' -i \
 *   | grep TRIPLA | sed -e 's|^ &#42;||' > /tmp/abordagem.tsv
 * adb push /tmp/abordagem.tsv /data/local/tmp/abordagem.tsv
 * adb push Qwen2.5-1.5B-Instruct-Q4_K_M.gguf /data/local/tmp/redator.gguf
 * ```
 *
 * Sem os dois arquivos os testes **pulam**, não passam — a distinção é o achado
 * de 21/08 sobre o `WhisperCppSttTest`.
 *
 * ## Por que o arquivo vem de `/data/local/tmp` e não do classpath
 *
 * `app` **não tem `core-knowledge` no classpath**, e não vai ter só para medir:
 * a lista de dependências do módulo é fronteira normativa. O TSV é gerado pelo
 * índice real, em JVM, e empurrado — o mesmo caminho que o GGUF já usa.
 */
class RedacaoDeAbordagemNoAparelhoTest {

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Tripla(
        val pergunta: String,
        val citacao: String,
        val norma: String,
        val confianca: Double,
        val texto: String,
    )

    @Test
    fun asGeracoesSaoMedidasUmaAUma() = runBlocking {
        val modelo = RedacaoDoCopiloto.arquivoDoModelo(contexto).let { destino ->
            if (!destino.isFile || destino.length() == 0L) {
                val origem = File(GGUF_NO_TMP)
                if (origem.canRead()) {
                    origem.inputStream().use { e -> destino.outputStream().use { e.copyTo(it, 1 shl 20) } }
                }
            }
            destino
        }
        val tsv = File(TSV_NO_TMP)
        assumeTrue("sem $GGUF_NO_TMP no aparelho — PULADO é a resposta honesta", modelo.isFile)
        assumeTrue("sem $TSV_NO_TMP no aparelho — rode o DespejoDeAbordagemTest e faça o push", tsv.canRead())

        val triplas = tsv.readLines().mapNotNull { linha ->
            val c = linha.split('\t')
            if (c.size < 6 || c[0] != "TRIPLA" || c[5].isBlank()) null
            else Tripla(c[1], c[2], c[3], c[4].replace(',', '.').toDoubleOrNull() ?: 0.0, c[5])
        }
        assertTrue("o TSV não trouxe tripla nenhuma", triplas.isNotEmpty())
        Log.i(TAG, "abordagem: ${triplas.size} perguntas · $GERACOES gerações cada")

        // Guarda desligado no motor para o texto CRU chegar ao log; o guarda de
        // produção julga aqui, explícito, e a diferença entre os dois é o dado.
        val redator = RedatorLlamaCpp(
            modelo,
            prazoMs = PRAZO_DE_MEDICAO_MS,
            guarda = GuardaDaRedacao(lastroMinimo = 0.0),
        )
        assertTrue("preparar() devolveu false — ver o logcat do llama", redator.preparar())
        val guarda = GuardaDaRedacao()
        val roteador = DeterministicIntentRouter()

        // Controle positivo do roteador, na MESMA execução: sem ele, "nenhuma
        // saída virou ação" passaria por vacuidade se o roteador tivesse morrido.
        assertTrue(
            "o roteador não reconhece comando legítimo — enquanto isso for verdade nada abaixo prova nada",
            roteador.route("preciso de apoio") !is Intent.NaoReconhecida,
        )

        var geradas = 0
        var vazias = 0
        var comCifraInventada = 0
        var aprovadasPeloGuarda = 0
        var viramAcao = 0
        val tempos = ArrayList<Long>()

        for ((i, t) in triplas.withIndex()) {
            val procedencia = "${t.citacao} — ${t.norma}"
            val fonte = "${t.texto}\n$procedencia\n${t.pergunta}"
            val cifrasDaFonte = cifras(fonte)
            repeat(GERACOES) { g ->
                val t0 = System.currentTimeMillis()
                val cru = redator.redigir(
                    PedidoDeRedacao(pergunta = t.pergunta, trecho = t.texto, procedencia = procedencia),
                )
                val dt = System.currentTimeMillis() - t0
                tempos += dt
                if (cru.isNullOrBlank()) {
                    vazias++
                    Log.i(TAG, "abordagem: [${i + 1}/${triplas.size}#${g + 1}] VAZIA ${dt}ms · \"${t.pergunta}\"")
                    return@repeat
                }
                geradas++
                val inventadas = cifras(cru) - cifrasDaFonte
                if (inventadas.isNotEmpty()) comCifraInventada++
                val aprovado = guarda.aprovar(cru, fonte) != null
                if (aprovado) aprovadasPeloGuarda++
                val intent = roteador.route(cru)
                val acao = intent !is Intent.NaoReconhecida
                if (acao) viramAcao++

                // Uma linha por geração, com TUDO o que a classificação humana
                // precisa. O texto cru vai inteiro: sem ele, "lastro 0,42" não
                // diz se o modelo alucinou, gaguejou ou negou o artigo.
                Log.i(
                    TAG,
                    "abordagem: [${i + 1}/${triplas.size}#${g + 1}] ${dt}ms " +
                        "conf=%.3f".format(t.confianca) +
                        " porta=${if (t.confianca >= LIMIAR_DE_PRODUCAO) "ABRE" else "fecha"}" +
                        " cifras=${if (inventadas.isEmpty()) "ok" else inventadas.joinToString(",")}" +
                        " guarda=${if (aprovado) "APROVA" else "recusa"}" +
                        " lastro=%.2f".format(guarda.lastro(cru, fonte)) +
                        " acao=${if (acao) intent::class.simpleName else "-"}" +
                        " | P=\"${t.pergunta}\" | A=${t.citacao}/${t.norma}" +
                        " | G=\"${cru.replace('\n', ' ')}\"",
                )
            }
        }
        redator.liberar()

        val n = geradas + vazias
        Log.i(
            TAG,
            "abordagem: TOTAL $n gerações ($geradas com texto, $vazias vazias) · " +
                "cifra inventada=$comCifraInventada · guarda aprova=$aprovadasPeloGuarda · " +
                "viram ação=$viramAcao · tempo mediana=${tempos.sorted()[tempos.size / 2]}ms " +
                "min=${tempos.min()} max=${tempos.max()}",
        )

        // Controle positivo: sem texto nenhum, todo número acima é zero e o
        // relatório mentiria por vacuidade. Foi assim que a primeira execução
        // do `RedatorNoAparelhoTest` passou com três `null`.
        assertTrue(
            "O modelo não gerou texto em nenhuma das $n tentativas. Enquanto isso " +
                "for verdade este teste passa por vacuidade.",
            geradas > 0,
        )
    }

    /** Dígitos como o [GuardaDaRedacao] os vê: sobre o texto sem acento e em minúsculas. */
    private fun cifras(texto: String): Set<String> =
        CIFRA.findAll(semAcento(texto.lowercase())).map { it.value }.toSet()

    private fun semAcento(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    companion object {
        private const val TAG = "ClaryonField"
        private const val GGUF_NO_TMP = "/data/local/tmp/redator.gguf"
        private const val TSV_NO_TMP = "/data/local/tmp/abordagem.tsv"

        /**
         * **Três, e o motivo é a regra dos três.** Uma geração por pergunta não
         * distingue "o modelo erra" de "o modelo errou uma vez": com temperatura
         * 0,3 e `dist` com semente fixa, a cadeia de amostragem avança de estado
         * entre chamadas e as três saídas de fato diferem. O relatório mostra a
         * variação, não só a média.
         */
        private const val GERACOES = 3

        /**
         * Prazo só para MEDIR, bem acima do de produção
         * (`RedatorLlamaCpp.PRAZO_PADRAO_MS` = 2 500 ms). Medir através do prazo
         * curto mediria o prazo, não o modelo.
         */
        private const val PRAZO_DE_MEDICAO_MS = 30_000

        /**
         * O limiar de `BaseDeConhecimentoLexical.LIMIAR_MEDIDO`, repetido aqui
         * porque `core-knowledge` não está no classpath deste módulo. Serve só
         * para ROTULAR a linha do log — nenhuma decisão depende dele aqui.
         */
        private const val LIMIAR_DE_PRODUCAO = 0.30

        private val CIFRA = Regex("""\d+""")
    }
}
