package com.claryon.evidence

import java.io.File
import java.io.FileOutputStream

/**
 * Manifesto de custódia: **append-only e versionado**.
 *
 * ## O defeito que este arquivo corrige
 *
 * A versão 1 reescrevia o manifesto **inteiro** a cada segmento
 * (`escreverManifesto(...)` chamado de dentro de `append`). Com um segmento por
 * quadro de 20 ms, um minuto de fala fazia 3.000 reescritas de um arquivo que
 * crescia ~135 B por linha:
 *
 * ```
 * Σ(k=1..3000) 135·k  ≈  135 · 3000·3001/2  ≈  605 MB
 * ```
 *
 * 605 MB gravados em flash para produzir um arquivo final de **404 kB**. É
 * quadrático: dobrar a duração da gravação quadruplica o custo. Append-only troca
 * isso por 135 B por segmento — de 605 MB/min para 404 kB/min sem janela nenhuma,
 * e para menos de 1 kB/min com janela de 10 s.
 *
 * ## Por que continua em texto claro
 *
 * Hashes não são segredo, e o manifesto em claro é o que permite a um terceiro
 * conferir a custódia **sem** a chave do Keystore. Ele continua **reescrevível** por
 * quem tenha acesso de escrita ao diretório — o que a v3 acrescenta não é
 * confidencialidade, é uma linha que esse alguém **não consegue reproduzir**.
 *
 * ## A linha `A`, e o que ela mudou na v3
 *
 * Até a v2 o manifesto não era assinado de forma nenhuma, e o custo disso não era o
 * genérico "poderia ser reescrito": era um ataque concreto e barato. Apagar os
 * últimos segmentos e as linhas `S` correspondentes deixava uma cadeia
 * **aritmeticamente perfeita**, e a conferência respondia íntegra sobre uma
 * gravação da qual o fim tinha sido removido. A v3 acrescenta a linha `A` —
 * HMAC-SHA256 com chave do Keystore sobre "terminou com N segmentos, último hash
 * H". Ver [AncoraDeFim], inclusive o que ela **não** fecha.
 *
 * ## Formato
 *
 * ```
 * versao=3
 * handle=<id>
 * formato=pcm_s16le_mono
 * taxaHz=16000
 * janelaMs=10000
 * inicio=<epochMillis>
 * S<TAB><seq><TAB><prev|-><TAB><sha256><TAB><bytes>
 * P<TAB><seq><TAB><epochMillis><TAB><motivo>
 * F<TAB><epochMillis><TAB><motivo|->
 * A<TAB><segmentos><TAB><ultimoHash|-><TAB><hmacSha256>
 * ```
 *
 * A letra inicial classifica a linha. Ela também é o que torna a migração
 * trivial: **as linhas da versão 1 começam com dígito**, então distinguir os dois
 * formatos não depende de heurística. E é o que deixou a v3 caber na v2 sem
 * quebrar nada: `A` é a **última** linha escrita, então um leitor v2 lê a gravação
 * inteira e apenas para nela.
 *
 * ## Linha truncada é linha descartada
 *
 * Um arquivo append-only só pode ser rasgado no **fim** (processo morto no meio de
 * um `write`). O leitor descarta a primeira linha que não parseia **e tudo depois
 * dela**. Um manifesto que perde a última linha é um manifesto correto de uma
 * gravação um segmento mais curta — não um manifesto corrompido.
 */
object Manifesto {

    /** 3 desde a âncora de fim. Ver [AncoraDeFim.VERSAO_COM_ANCORA]. */
    const val VERSAO_ATUAL = 3
    const val NOME = "manifest.txt"

    private const val SEP = '\t'
    private const val NULO = "-"

    // ── Escrita ───────────────────────────────────────────────────────────────

    /**
     * Escritor com o descritor aberto durante toda a gravação.
     *
     * `fd.sync()` a cada linha: 6 sincronizações por minuto com janela de 10 s é
     * barato, e é o que garante que a cadeia sobreviva a um corte de energia. A
     * versão anterior fazia 50 reescritas completas por segundo e mesmo assim não
     * sincronizava nenhuma.
     */
    class Escritor(private val arquivo: File) : AutoCloseable {

        private val saida = FileOutputStream(arquivo, /* append = */ true)

        fun cabecalho(ctx: OccurrenceContext, handle: RecordingHandle, janelaMs: Int) {
            escrever(
                buildString {
                    appendLine("versao=$VERSAO_ATUAL")
                    appendLine("handle=${handle.id}")
                    appendLine("formato=${ctx.formato}")
                    appendLine("taxaHz=${ctx.sampleRateHz}")
                    appendLine("janelaMs=$janelaMs")
                    appendLine("inicio=${ctx.startedAtEpochMillis}")
                },
            )
        }

        fun segmento(c: ChunkHash) = escrever(
            "S$SEP${c.sequence}$SEP${c.previousSha256Hex ?: NULO}$SEP${c.sha256Hex}$SEP${c.bytes}\n",
        )

        fun purga(p: Purga) = escrever("P$SEP${p.sequence}$SEP${p.epochMillis}$SEP${p.motivo}\n")

        fun fim(epochMillis: Long, motivo: String?) =
            escrever("F$SEP$epochMillis$SEP${motivo ?: NULO}\n")

        /**
         * Última linha do arquivo, escrita **depois** de [fim] porque o horário e o
         * motivo do fim entram no que ela assina.
         */
        fun ancora(a: AncoraDeFim.Ancora) = escrever(
            "A$SEP${a.segmentos}$SEP${a.ultimoHashHex ?: NULO}$SEP${a.macHex}\n",
        )

        private fun escrever(texto: String) {
            saida.write(texto.toByteArray(Charsets.UTF_8))
            saida.flush()
            runCatching { saida.fd.sync() }
        }

        override fun close() {
            runCatching { saida.close() }
        }
    }

    // ── Leitura ───────────────────────────────────────────────────────────────

    /** Manifesto lido do disco, em qualquer das versões suportadas. */
    data class Lido(
        val versao: Int,
        val handle: RecordingHandle,
        val cadeia: List<ChunkHash>,
        val purgados: List<Purga>,
        val formato: String,
        val sampleRateHz: Int,
        val janelaMs: Int,
        val inicioEpochMillis: Long,
        val fimEpochMillis: Long?,
        val motivoDoFim: String?,
        val ancora: AncoraDeFim.Ancora? = null,
    ) {
        val finalizado: Boolean get() = fimEpochMillis != null

        fun paraCustodia(): CustodyManifest = CustodyManifest(
            handle = handle,
            chain = cadeia,
            finalizedAtEpochMillis = fimEpochMillis ?: 0L,
            versao = versao,
            sampleRateHz = sampleRateHz,
            formato = formato,
            janelaMs = janelaMs,
            purgados = purgados,
            motivoDoFim = motivoDoFim,
            ancora = ancora,
        )
    }

    /**
     * Lê o manifesto de [dir]. Aceita **v1 e v2**.
     *
     * Ler v1 não é cortesia com o passado: os segmentos gravados na bancada usam
     * o nome do arquivo como *associated data* do AES-GCM (verificado no bytecode
     * de `EncryptedFile.openFileInput`/`openFileOutput` — ambos passam
     * `mFile.getName()` para `newDecryptingStream`/`newEncryptingStream`). Trocar
     * o esquema de nomes tornaria toda gravação anterior **indescriptografável**.
     * Por isso a v2 mantém `seg_%05d.enc` e muda só o que cabe dentro.
     */
    fun ler(dir: File): Lido? {
        val arquivo = File(dir, NOME)
        if (!arquivo.isFile) return null
        val linhas = arquivo.readLines()
        if (linhas.isEmpty()) return null
        return if (linhas[0].startsWith("versao=")) lerV2(linhas) else lerV1(linhas)
    }

    private fun lerV2(linhas: List<String>): Lido? {
        var versao = 0
        var handle: String? = null
        var formato = OccurrenceContext.FORMATO_PCM_S16LE_MONO
        var taxa = 16_000
        var janelaMs = 0
        var inicio = 0L
        var fim: Long? = null
        var motivoDoFim: String? = null
        var ancora: AncoraDeFim.Ancora? = null
        val cadeia = ArrayList<ChunkHash>()
        val purgados = ArrayList<Purga>()

        for (linha in linhas) {
            if (linha.isEmpty()) continue
            val ok = when {
                linha.startsWith("versao=") -> linha.removePrefix("versao=").toIntOrNull()
                    ?.also { versao = it } != null
                linha.startsWith("handle=") -> { handle = linha.removePrefix("handle="); true }
                linha.startsWith("formato=") -> { formato = linha.removePrefix("formato="); true }
                linha.startsWith("taxaHz=") -> linha.removePrefix("taxaHz=").toIntOrNull()
                    ?.also { taxa = it } != null
                linha.startsWith("janelaMs=") -> linha.removePrefix("janelaMs=").toIntOrNull()
                    ?.also { janelaMs = it } != null
                linha.startsWith("inicio=") -> linha.removePrefix("inicio=").toLongOrNull()
                    ?.also { inicio = it } != null
                linha[0] == 'S' -> parseSegmento(linha)?.also { cadeia.add(it) } != null
                linha[0] == 'P' -> parsePurga(linha)?.also { purgados.add(it) } != null
                linha[0] == 'F' -> parseFim(linha)?.also { (t, m) -> fim = t; motivoDoFim = m } != null
                linha[0] == 'A' -> parseAncora(linha)?.also { ancora = it } != null
                else -> false
            }
            // Rasgo só acontece no fim de um arquivo append-only: a primeira linha
            // ilegível encerra a leitura, e o que veio antes continua válido.
            if (!ok) break
        }
        val id = handle ?: return null
        return Lido(
            versao, RecordingHandle(id), cadeia, purgados,
            formato, taxa, janelaMs, inicio, fim, motivoDoFim, ancora,
        )
    }

    private fun parseAncora(linha: String): AncoraDeFim.Ancora? {
        val p = linha.split(SEP)
        if (p.size < 4) return null
        val n = p[1].toIntOrNull() ?: return null
        // MAC de tamanho errado é linha rasgada, não âncora — e âncora rasgada tem
        // de sumir, não virar uma que "não confere". A distinção importa: AUSENTE
        // é o que a queda produz, INVALIDA é o que a adulteração produz.
        if (p[3].length != 64) return null
        return AncoraDeFim.Ancora(n, p[2].takeIf { it != NULO }, p[3])
    }

    private fun parseSegmento(linha: String): ChunkHash? {
        val p = linha.split(SEP)
        if (p.size < 5) return null
        val seq = p[1].toIntOrNull() ?: return null
        val bytes = p[4].toIntOrNull() ?: return null
        if (p[3].length != 64) return null
        return ChunkHash(seq, p[3], p[2].takeIf { it != NULO }, bytes)
    }

    private fun parsePurga(linha: String): Purga? {
        val p = linha.split(SEP)
        if (p.size < 4) return null
        return Purga(p[1].toIntOrNull() ?: return null, p[2].toLongOrNull() ?: return null, p[3])
    }

    private fun parseFim(linha: String): Pair<Long, String?>? {
        val p = linha.split(SEP)
        if (p.size < 3) return null
        return (p[1].toLongOrNull() ?: return null) to p[2].takeIf { it != NULO }
    }

    /**
     * Leitor da versão 1 — `handle=`, `finalizado=`, e linhas `seq\tprev\thash`
     * sem tamanho. O `bytes = 0` é honesto: aquele formato não registrava o
     * tamanho do segmento, e inventá-lo aqui seria pior que admitir a lacuna.
     */
    private fun lerV1(linhas: List<String>): Lido? {
        var handle: String? = null
        var finalizado = false
        var fim: Long? = null
        val cadeia = ArrayList<ChunkHash>()
        for (linha in linhas) {
            when {
                linha.isEmpty() -> Unit
                linha.startsWith("handle=") -> handle = linha.removePrefix("handle=")
                linha.startsWith("finalizado=") ->
                    finalizado = linha.removePrefix("finalizado=").toBoolean()
                linha.startsWith("finalizedAt=") -> fim = linha.removePrefix("finalizedAt=").toLongOrNull()
                else -> {
                    val p = linha.split(SEP)
                    if (p.size >= 3 && p[2].length == 64) {
                        val seq = p[0].toIntOrNull() ?: break
                        cadeia.add(ChunkHash(seq, p[2], p[1].takeIf { it != NULO }, bytes = 0))
                    } else {
                        break
                    }
                }
            }
        }
        val id = handle ?: return null
        return Lido(
            versao = 1,
            handle = RecordingHandle(id),
            cadeia = cadeia,
            purgados = emptyList(),
            formato = OccurrenceContext.FORMATO_PCM_S16LE_MONO,
            sampleRateHz = 16_000,
            // v1 selava um arquivo por quadro de 20 ms — a "janela" era o quadro.
            janelaMs = 20,
            inicioEpochMillis = 0L,
            fimEpochMillis = if (finalizado) (fim ?: 0L) else null,
            motivoDoFim = null,
        )
    }
}
