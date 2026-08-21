package com.claryon.field.norma

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.claryon.common.FeatureFlag
import com.claryon.llm.PedidoDeRedacao
import com.claryon.llm.PoliticaDeRedacao
import com.claryon.llm.Redator
import com.claryon.llm.RedatorLlamaCpp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **O dono de processo da Etapa B, e o lugar onde a degradação acontece de fato.**
 *
 * Uma instância por processo, pela mesma razão que [com.claryon.field.voice.EscutaDoAgente]
 * é: o GGUF pesa centenas de MB e não pode existir duas vezes nem morrer com uma
 * tela.
 *
 * ## O que este objeto decide, e quando
 *
 * A decisão é tomada **uma vez, no boot** — literalmente em
 * `ClaryonApp.onCreate` —, com três entradas que só existem no aparelho:
 *
 *  1. o GGUF está em `filesDir`? (não vai no APK; ver [ARQUIVO_DO_MODELO])
 *  2. a chave humana está ligada? ([FeatureFlag.REDACAO_POR_LLM])
 *  3. a RAM do aparelho comporta? (`ActivityManager.MemoryInfo`)
 *
 * A regra em si é pura e mora em [PoliticaDeRedacao], separada de propósito:
 * ler `MemoryInfo` prova que o número foi lido, **não** que o aparelho fraco cai
 * na Etapa A. Só um contra-teste sobre a função pura prova isso, e ele existe.
 *
 * ## Por que carregar o modelo no boot, e não na primeira pergunta
 *
 * Pelo mesmo motivo que o whisper ficou quente entre invocações: carregar
 * centenas de MB pelo JNI **dentro** de um aceite de 4 s é como transformar a
 * primeira pergunta do turno na única que falha. A carga vai para o escopo do
 * processo, fora da Main, e o log diz quanto custou.
 *
 * O contrapeso é [liberar], chamada por `onTrimMemory` — se o sistema apertar,
 * o redator é a **primeira** coisa que devolve memória, porque é a capacidade
 * mais cara e a única cuja ausência não quebra o produto: sem ela, a Etapa A lê
 * o trecho verbatim, que é o comportamento padrão.
 */
object RedacaoDoCopiloto {

    private const val TAG = "ClaryonField"

    /**
     * **Nome fixo em `filesDir`, e nunca em `assets/`.**
     *
     * `llama_model_load_from_file` faz `fopen`/`mmap`: asset dentro do APK não
     * tem caminho no sistema de arquivos, comprimido ou não. Além de ser
     * requisito técnico, é o que mantém o APK com o tamanho que tem — o GGUF
     * chega por `adb push` ou por download fora do caminho crítico:
     *
     * ```
     * adb push Llama-3.2-1B-Instruct-Q4_K_M.gguf /data/local/tmp/redator.gguf
     * adb shell run-as com.claryon.field cp /data/local/tmp/redator.gguf files/
     * ```
     *
     * Nome genérico e não o do modelo: trocar de família de modelo passa a ser
     * trocar um arquivo, sem recompilar nem mexer em constante.
     */
    const val ARQUIVO_DO_MODELO: String = "redator.gguf"

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var decisaoDoBoot: PoliticaDeRedacao.Decisao? = null

    @Volatile
    private var redator: Redator = Redator.Inerte

    /** A decisão tomada no boot, ou `null` se o boot ainda não aconteceu. */
    val decisao: PoliticaDeRedacao.Decisao? get() = decisaoDoBoot

    /** `true` quando a Etapa B está de pé neste aparelho, nesta execução. */
    val redigindo: Boolean get() = decisaoDoBoot is PoliticaDeRedacao.Decisao.Redigir

    fun arquivoDoModelo(context: Context): File = File(context.filesDir, ARQUIVO_DO_MODELO)

    /**
     * Decide, registra e — se for o caso — aquece o modelo. **Não suspensa e não
     * bloqueante**: o trabalho vai para [escopo], porque `onCreate` roda na Main
     * e aqui há leitura de disco (o `StrictMode` deste projeto acusaria, com
     * razão).
     *
     * Idempotente: a segunda chamada no mesmo processo não faz nada.
     */
    fun decidirNoBoot(context: Context) {
        if (decisaoDoBoot != null) return
        val app = context.applicationContext
        escopo.launch {
            val arquivo = arquivoDoModelo(app)
            val memoria = ActivityManager.MemoryInfo().also { info ->
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getMemoryInfo(info)
            }
            val flag = FlagsDoAparelho(app).isEnabled(FeatureFlag.REDACAO_POR_LLM)

            val d = PoliticaDeRedacao.decidir(
                flagLigada = flag,
                tamanhoDoModeloBytes = if (arquivo.isFile) arquivo.length() else 0L,
                ramTotalBytes = memoria.totalMem,
                ramDisponivelBytes = memoria.availMem,
                sistemaSobPressao = memoria.lowMemory,
            )
            decisaoDoBoot = d

            // Os NÚMEROS, não um "ok". Um aparelho que recusa por 40 MB e outro
            // que recusa por 3 GB dariam a mesma linha, e são situações
            // diferentes — foi assim que o log do gazetteer virou útil.
            Log.i(
                TAG,
                "redação: $d · flag=$flag · modelo=${if (arquivo.isFile) arquivo.length() else 0} B " +
                    "· RAM total=${memoria.totalMem} disp=${memoria.availMem} " +
                    "low=${memoria.lowMemory}",
            )

            if (d is PoliticaDeRedacao.Decisao.Redigir) {
                val motor = RedatorLlamaCpp(arquivo)
                redator = if (motor.preparar()) {
                    motor
                } else {
                    // Decidiu redigir e o motor não subiu: isso NÃO pode ficar
                    // como "vai tentar de novo na hora da pergunta", porque a
                    // hora da pergunta é dentro do aceite de 4 s.
                    Log.w(TAG, "redação: motor não carregou — voltando à leitura verbatim")
                    decisaoDoBoot = PoliticaDeRedacao.Decisao.LerVerbatim(
                        PoliticaDeRedacao.Motivo.SEM_MODELO,
                    )
                    Redator.Inerte
                }
            }
        }
    }

    /**
     * O texto a ser falado sobre [trecho], ou `null` quando não houve redação
     * confiável.
     *
     * `null` é resposta esperada e frequente: Etapa B desligada, aparelho fraco,
     * modelo ausente, prazo estourado ou texto reprovado pelo guarda de lastro.
     * Quem chama **lê o trecho verbatim** — que é a Etapa A e continua sendo o
     * comportamento correto do produto.
     *
     * **Esta função não anuncia a procedência.** O número do artigo e o nome da
     * lei saem do trecho recuperado, byte a byte, pela boca de quem chama; nunca
     * pela do modelo. Artigo reescrito é a falha mais cara que existe aqui.
     */
    suspend fun redigir(pergunta: String, trecho: String, procedencia: String): String? {
        if (trecho.isBlank()) return null
        return redator.redigir(
            PedidoDeRedacao(pergunta = pergunta, trecho = trecho, procedencia = procedencia),
        )
    }

    /**
     * Devolve a memória do modelo. Chamada por `onTrimMemory`.
     *
     * Depois disto o produto continua respondendo — pela Etapa A. É a diferença
     * entre esta capacidade e o rádio: perder o redator custa uma resposta menos
     * fluente; perder o serviço de rádio custa a ocorrência.
     */
    fun liberar() {
        redator.liberar()
        redator = Redator.Inerte
        decisaoDoBoot = PoliticaDeRedacao.Decisao.LerVerbatim(
            PoliticaDeRedacao.Motivo.SISTEMA_SOB_PRESSAO,
        )
    }

    /** Só para teste instrumentado: troca o motor sem passar pelo boot. */
    fun instalar(substituto: Redator, decisao: PoliticaDeRedacao.Decisao) {
        redator = substituto
        decisaoDoBoot = decisao
    }
}
