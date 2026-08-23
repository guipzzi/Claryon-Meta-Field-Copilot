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
 *  1. o GGUF está no aparelho? (não vai no APK; ver [arquivoDoModelo], que
 *     procura no diretório privado **e** no externo do app)
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
 * mais cara e a única cuja ausência não quebra o produto: sem ela, a Etapa A
 * responde com a **citação**, que é o comportamento padrão.
 *
 * ## Duas coisas que este KDoc afirmava e não eram verdade (corrigido em 22/08)
 *
 *  1. **"a Etapa A lê o trecho verbatim"** — não lê. `utteranceFor` fala
 *     `"Art. 306, Lei 9.503"`; o texto do artigo não chega a `app`, porque
 *     [ConsultaDeNorma.consultar] devolve `Pair<citacao, norma>`. Leitura
 *     verbatim esbarra no teto de 7 palavras do `CLAUDE.md` §4 e é **proposta**
 *     em `specs/leitura-de-norma.spec.md`, esperando decisão humana.
 *  2. **[redigir] tem ZERO chamadores em `src/main`** — [decidirNoBoot] e
 *     [liberar] são chamados por `ClaryonApp`, mas a redação em si nunca é
 *     pedida por ninguém. Pelo critério do `CLAUDE.md` §6, a Etapa B está
 *     **escrita**, não construída: falta o caminho alcançável pelo agente.
 *
 * Ligar [redigir] à fala **não é diff de código**: a fala derivada de norma é
 * governada pelo teto de 7 palavras, que é regra dura. Pelo §7, sobrepor regra
 * dura entra como spec e espera. A medição que fundamenta essa decisão está em
 * `OrcamentoDaEtapaBNoAparelhoTest`.
 */
object RedacaoDoCopiloto {

    private const val TAG = "ClaryonField"

    /**
     * **Nome fixo em disco, e nunca em `assets/`.**
     *
     * `llama_model_load_from_file` faz `fopen`/`mmap`: asset dentro do APK não
     * tem caminho no sistema de arquivos, comprimido ou não. Além de ser
     * requisito técnico, é o que mantém o APK com o tamanho que tem — o GGUF
     * chega por `adb push` ou por download fora do caminho crítico.
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

    /**
     * **Onde o GGUF pode estar, na ordem em que se procura.**
     *
     * ## Por que dois lugares, e por que o externo existe
     *
     * O caminho histórico é `filesDir`, e ele obriga o onboarding a **duplicar**
     * 770 MiB: `adb push` não escreve no diretório privado do app, então o
     * arquivo entra em `/data/local/tmp` e é copiado depois — duas vezes o
     * espaço enquanto a cópia acontece, e uma cópia que fica lá para sempre.
     *
     * `getExternalFilesDir` é o diretório do app **dentro** do armazenamento
     * compartilhado: `adb push` escreve nele direto, sem `run-as`, portanto
     * também em build de release; o app lê sem permissão nenhuma desde a API 19;
     * e a desinstalação leva o arquivo junto.
     *
     * **A dúvida real era se o llama.cpp carrega de lá**, porque armazenamento
     * "externo" é FUSE em Android moderno e `mmap` sobre FUSE não é garantido.
     * Medido em 22/08 no emulador arm64 API 35
     * (`OrcamentoDaEtapaBNoAparelhoTest.osDoisCaminhosDeEmbarqueSaoMedidos`):
     *
     * ```
     * cópia /data/local/tmp → filesDir ....... 908 ms (770,28 MiB)
     * preparar() direto do externo ........... 2 168 ms, devolveu true
     * ```
     *
     * Carrega. Então o onboarding do dia do evento é uma linha e nenhuma cópia:
     *
     * ```
     * adb push Qwen2.5-1.5B-Instruct-Q4_K_M.gguf \
     *   /sdcard/Android/data/com.claryon.field/files/redator.gguf
     * ```
     *
     * O nome no aparelho é `redator.gguf` e **não** o nome do modelo, o que fez o
     * troca de Llama 3.2 1B para Qwen2.5 1.5B em 22/08 não tocar uma linha de
     * código deste arquivo. Os 770,28 MiB medidos acima são do GGUF antigo; o
     * novo tem 940,36 MiB e a cópia de (b) custa proporcionalmente mais.
     *
     * ## A ordem: privado primeiro
     *
     * `filesDir` vence quando os dois existem. Não é preferência técnica — é que
     * o armazenamento compartilhado é gravável por quem tem acesso ao aparelho, e
     * um GGUF trocado é um copiloto trocado. Quem quiser a garantia forte usa o
     * diretório privado, que continua funcionando exatamente como antes; quem
     * quiser o onboarding barato usa o externo. **Nenhum dos dois é caminho novo
     * no código**: é a mesma `File` chegando ao mesmo lugar.
     *
     * Devolve o caminho **privado** quando não há arquivo em lugar nenhum, para
     * que o log do boot e o motivo `SEM_MODELO` continuem apontando para onde o
     * arquivo deveria estar.
     */
    fun arquivoDoModelo(context: Context): File {
        val privado = File(context.filesDir, ARQUIVO_DO_MODELO)
        if (privado.isFile) return privado
        // `getExternalFilesDir` devolve `null` com o armazenamento desmontado —
        // estado normal, não erro, e aqui vira "não achei", como qualquer outra
        // ausência de arquivo.
        val externo = context.getExternalFilesDir(null)?.let { File(it, ARQUIVO_DO_MODELO) }
        return if (externo != null && externo.isFile) externo else privado
    }

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
                    Log.w(TAG, "redação: motor não carregou — voltando à citação da Etapa A")
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
     * Quem chama cai na Etapa A — que hoje é a **citação** (`"Art. 306, Lei
     * 9.503"`), não a leitura do artigo — e continua sendo o comportamento
     * correto do produto.
     *
     * **Medido em 22/08, com a configuração de produção, sobre as 20 perguntas
     * do banco de abordagem que passam o limiar de 0,30**
     * (`OrcamentoDaEtapaBNoAparelhoTest`, emulador arm64 API 35, 2,5 GB): `null`
     * em **9 de 20** por prazo estourado, mais 4 reprovadas pelo guarda — **13 de
     * 20 caem**. O ramo de queda não é excepcional: ele é a **maioria** do
     * comportamento desta função neste aparelho.
     *
     * **E o número da esquerda é do APARELHO, não do produto.** No mesmo dia, a
     * mesma configuração rendeu **14 de 20 com texto** numa execução e **4 de 20**
     * em outra, com a máquina que hospeda o emulador mais carregada. A causa está
     * no log nativo: o prefill de ~500 tokens custa **1,6 a 2,5 s** e o prazo é
     * 2 500 ms, então `llama_decode` devolve `2` (abortado) **antes de o prompt
     * entrar**. Quem for prometer esta capacidade num palco precisa saber que ela
     * fica muda quando o celular estiver ocupado — e no dia do evento ele estará.
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
