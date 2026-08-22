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
 * @param formulacao o par instrução/mensagem do usuário. Existe como parâmetro
 *   para que a bancada rode várias sobre o mesmo banco de perguntas sem editar
 *   produção — ver [FormulacaoDoPrompt]. O padrão é
 *   [FormulacaoDoPrompt.PRODUCAO], e trocá-lo é mudança de comportamento que só
 *   acontece com a medição junto.
 */
class RedatorLlamaCpp(
    private val arquivoDoModelo: File,
    private val nThreads: Int = 4,
    private val nCtx: Int = 1024,
    private val maxTokens: Int = MAX_TOKENS_PADRAO,
    private val prazoMs: Int = PRAZO_PADRAO_MS,
    private val guarda: GuardaDaRedacao = GuardaDaRedacao(),
    private val despachante: CoroutineDispatcher = Dispatchers.Default,
    private val formulacao: FormulacaoDoPrompt = FormulacaoDoPrompt.PRODUCAO,
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
                formulacao.instrucao,
                formulacao.usuario(pedido),
                maxTokens,
                prazoMs,
            )
        }.onFailure { Log.e(TAG, "redator: geração falhou", it) }.getOrNull()
            ?: return@withContext null

        // **A fonte do guarda é o TRECHO, e a pergunta ficou de fora.**
        //
        // Ela entrava aqui até 22/08, por um raciocínio que soava certo: uma redação
        // que responde à pergunta reusa naturalmente as palavras dela, e penalizá-la
        // por isso reprovaria redação honesta.
        //
        // **A medição derrubou o raciocínio.** Com a pergunta na fonte, um modelo que
        // apenas a ECOA obtém lastro **1,00 sem tocar no artigo** — e ecoar a pergunta
        // é comportamento registrado deste modelo. Medido por diferença sobre as 20
        // perguntas do banco de abordagem, com o prompt de 22/08 em vigor: das **9**
        // aprovações que a composição antiga daria no prazo de produção, **2 caem**; no
        // prazo de medição, **2 de 12**. Com o prompt de hoje (`FormulacaoDoPrompt.SEM_ROTULO`,
        // que devolve o foco à pergunta) o eco fica mais frequente e a régua pega mais:
        // **5 de 13**. Quanto mais o modelo ecoa, mais esta linha trabalha.
        //
        // As quatro recusas lidas uma a uma são todas eco ou meta-comentário — *"Vendeu
        // simulacro de pistola na loja de brinquedo."*, *"Comprei o carro com chassi
        // adulterado, respondo ao ag"*, *"O TRECHO DA NORMA trata sobre o crime de
        // adulterar placa…"*, *"A adaptação da norma à pergunta feita pelo agente é
        // simples: o agente responde que não sabe…"*. **Nenhuma redação honesta foi
        // perdida**, que era o risco que justificava a composição antiga.
        //
        // O medo de reprovar redação honesta não se concretiza: palavra que a pergunta
        // e o artigo compartilham **já está no trecho**, e continua contando. O que
        // deixa de contar é a palavra que existe SÓ na pergunta — que é precisamente a
        // que não tem lastro na norma.
        //
        // A `procedencia` fica, porque é curta e é a citação que a resposta deve poder
        // repetir. A saída anterior continua fora: uma alucinação aprovada uma vez
        // viraria lastro para a seguinte, e o filtro degradaria sozinho ao longo do
        // turno.
        val fonte = "${pedido.trecho}\n${pedido.procedencia}"
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
                "redator: RECUSADO (lastro %.2f) — cai na citação da Etapa A · cru=\"%s\""
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

    companion object {
        private const val TAG = "ClaryonField"

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
