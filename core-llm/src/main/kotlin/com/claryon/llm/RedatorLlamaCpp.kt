package com.claryon.llm

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A ponte para o `libclaryonllm.so`. **Interna de propósito**: nada fora deste
 * módulo tem motivo para falar JNI, e um ponteiro nativo exposto vira uso
 * indevido em runtime, não erro de compilação.
 *
 * Os quatro nomes casam com `src/main/cpp/redator_jni.cpp` — mudar um lado sem o
 * outro dá `UnsatisfiedLinkError` só quando o método é chamado, nunca no build.
 */
internal object NativoDoRedator {
    external fun nativoCarregar(caminho: String, nThreads: Int, nCtx: Int): Long
    external fun nativoRedigir(
        handle: Long,
        sistema: String,
        usuario: String,
        maxTokens: Int,
        prazoMs: Int,
    ): String?

    external fun nativoLiberar(handle: Long)
    external fun nativoTamanhoDoModelo(handle: Long): Long
}

/**
 * **A Etapa B rodando de verdade: llama.cpp, 100% no aparelho.**
 *
 * ## O modelo vem de `filesDir`, e isso não é preferência
 *
 * [arquivoDoModelo] é caminho no sistema de arquivos porque
 * `llama_model_load_from_file` faz `fopen`/`mmap`. **Asset dentro do APK não tem
 * caminho** — comprimido ou não, `AssetManager` entrega um descritor com
 * deslocamento, não um arquivo. O `ROADMAP.md` já registrava a armadilha, e este
 * projeto já a pagou uma vez com o `espeak-ng-data` do Piper.
 *
 * A consequência prática é boa: o GGUF não infla o APK, e trocar de modelo é um
 * `adb push` — não uma reinstalação.
 *
 * ## O que este objeto NÃO faz
 *
 * Não decide se deve existir (isso é [PoliticaDeRedacao]), não decide se a
 * saída pode ser dita (isso é [GuardaDaRedacao]) e não fala (isso é a camada de
 * voz). Ele carrega, gera e devolve — com prazo.
 *
 * @param nThreads threads de inferência. `4` é o número de núcleos grandes de um
 *   celular de campo típico; passar do número de núcleos *piora*, porque as
 *   threads passam a disputar o mesmo cache.
 * @param nCtx janela de contexto. `1024` cabe um trecho de norma longo mais a
 *   pergunta e a resposta, e o KV cache cresce linearmente com isto — é o
 *   parâmetro que mais custa RAM depois dos pesos.
 */
class RedatorLlamaCpp(
    private val arquivoDoModelo: File,
    private val nThreads: Int = 4,
    private val nCtx: Int = 1024,
    private val maxTokens: Int = MAX_TOKENS_PADRAO,
    private val prazoMs: Int = PRAZO_PADRAO_MS,
    private val guarda: GuardaDaRedacao = GuardaDaRedacao(),
    private val despachante: CoroutineDispatcher = Dispatchers.Default,
) : Redator {

    @Volatile
    private var handle: Long = 0L

    /** `true` se o modelo está carregado em memória agora. */
    val carregado: Boolean get() = handle != 0L

    /**
     * Carrega o GGUF. Devolve `false` em vez de lançar — arquivo ausente ou
     * corrompido é estado operacional, e quem chama já sabe cair na Etapa A.
     *
     * Idempotente: chamar duas vezes não carrega duas vezes 800 MB.
     */
    suspend fun preparar(): Boolean = withContext(despachante) {
        if (handle != 0L) return@withContext true
        if (!biblioteca()) return@withContext false
        if (!arquivoDoModelo.isFile) {
            Log.w(TAG, "redator: sem modelo em ${arquivoDoModelo.path}")
            return@withContext false
        }
        val t0 = System.currentTimeMillis()
        val h = runCatching {
            NativoDoRedator.nativoCarregar(arquivoDoModelo.path, nThreads, nCtx)
        }.onFailure { Log.e(TAG, "redator: carga nativa falhou", it) }.getOrDefault(0L)
        handle = h
        if (h == 0L) return@withContext false
        Log.i(
            TAG,
            "redator: carregado em ${System.currentTimeMillis() - t0} ms " +
                "(${arquivoDoModelo.length()} B em disco, " +
                "${NativoDoRedator.nativoTamanhoDoModelo(h)} B de pesos)",
        )
        true
    }

    override suspend fun redigir(pedido: PedidoDeRedacao): String? = withContext(despachante) {
        if (handle == 0L && !preparar()) return@withContext null

        val gerado = runCatching {
            NativoDoRedator.nativoRedigir(
                handle,
                INSTRUCAO,
                usuario(pedido),
                maxTokens,
                prazoMs,
            )
        }.onFailure { Log.e(TAG, "redator: geração falhou", it) }.getOrNull()
            ?: return@withContext null

        // **A fonte do guarda é o pedido, e só ele.** Se a saída anterior
        // entrasse aqui, uma alucinação aprovada uma vez viraria lastro para a
        // seguinte, e o filtro degradaria sozinho ao longo do turno.
        val fonte = "${pedido.trecho}\n${pedido.procedencia}\n${pedido.pergunta}"
        val aprovado = guarda.aprovar(gerado, fonte)
        if (aprovado == null) {
            // **O texto RECUSADO vai para o log, não só a nota.** Sem ele, três
            // execuções desta sessão disseram "lastro 0.00" e ninguém sabia se o
            // modelo tinha alucinado, gaguejado ou respondido "NÃO SEI" — e a
            // diferença entre esses três decide se o defeito é do guarda, do
            // prompt ou do modelo. É texto de norma reescrito, não fala de
            // terceiro: nada aqui esbarra na proibição de indexar interlocutor.
            Log.w(
                TAG,
                "redator: RECUSADO (lastro %.2f) — cai na leitura verbatim · cru=\"%s\""
                    .format(guarda.lastro(gerado, fonte), gerado.take(240).replace('\n', ' ')),
            )
        }
        aprovado
    }

    override fun liberar() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { NativoDoRedator.nativoLiberar(h) }
    }

    private fun usuario(pedido: PedidoDeRedacao): String = buildString {
        append("PERGUNTA DO AGENTE: ").append(pedido.pergunta).append('\n')
        append("TRECHO DA NORMA:\n").append(pedido.trecho).append('\n')
    }

    companion object {
        private const val TAG = "ClaryonField"

        /**
         * **A instrução é fixa e não recebe nada de fora.** Prompt montado com
         * texto de terceiro é o caminho pelo qual conteúdo recuperado passa a
         * mandar no modelo; aqui o único material variável viaja no papel de
         * `user`, nunca no de `system`.
         *
         * Ela também não oferece nenhuma ação: não há ferramenta, não há campo
         * de comando, não há verbo de aparelho. O `CLAUDE.md` §2 proíbe LLM
         * escolhendo ação, e a garantia forte disso é de classpath e de teste em
         * `app` — esta instrução é só a primeira camada.
         *
         * **Curta porque foi MEDIDO que a longa não funciona.** A primeira versão
         * tinha seis linhas e uma cláusula de escape (*"se o trecho não responder
         * à pergunta, responda NÃO SEI"*). No emulador, com Llama 3.2 1B Q4_K_M,
         * ela produziu `"NÃO SEI."` em **5 de 6** gerações sobre um artigo do CTB
         * que responde à pergunta diretamente. Trocada por estas três linhas, o
         * mesmo modelo, o mesmo trecho e a mesma semente passaram a produzir
         * resumo utilizável. A cláusula de recusa saiu porque ela é da Etapa A —
         * quem decide que não sabe é o limiar de `PortaDoConhecimento`, não o
         * modelo — e aqui ela só dava ao modelo uma saída fácil.
         */
        private val INSTRUCAO = """
            Resuma o TRECHO DA NORMA em duas frases curtas, em português do Brasil.
            Use somente o que está escrito no trecho. Não cite número de artigo.
            Comece direto pelo resumo, sem preâmbulo e sem aspas.
        """.trimIndent()

        /**
         * Três frases faladas cabem folgadamente em 96 tokens. O teto existe
         * para o caso em que o modelo entra em laço: sem ele, o prazo seria a
         * única barreira e o agente esperaria o prazo inteiro por lixo.
         */
        const val MAX_TOKENS_PADRAO: Int = 96

        /**
         * **O prazo é o aceite, menos o resto do caminho.** O `ROADMAP.md` pede
         * resposta falada em ≤ 4 s do fim da fala; STT, recuperação e síntese já
         * comem parte disso. Estourado, o nativo devolve o que gerou e o produto
         * segue — nunca fica mudo esperando.
         */
        const val PRAZO_PADRAO_MS: Int = 2_500

        @Volatile
        private var bibliotecaCarregada: Boolean? = null

        /**
         * `System.loadLibrary` uma vez por processo, com a falha capturada.
         *
         * Falhar aqui é normal em JVM de teste local (não há `.so` para o host)
         * e tem de degradar, não derrubar: quem chama cai na Etapa A, que é o
         * comportamento correto de um aparelho sem o motor.
         */
        private fun biblioteca(): Boolean = bibliotecaCarregada ?: synchronized(this) {
            bibliotecaCarregada ?: runCatching {
                System.loadLibrary("claryonllm")
                true
            }.onFailure {
                Log.e(TAG, "redator: libclaryonllm.so não carregou", it)
            }.getOrDefault(false).also { bibliotecaCarregada = it }
        }
    }
}
