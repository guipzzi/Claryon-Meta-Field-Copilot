package com.claryon.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Uma fala que não pôde sair ao vivo e espera a rede voltar.
 *
 * @param origemMs quando o agente **falou**, não quando a mensagem sairá. É o que
 *   permite ao receptor anunciar *"Alfa Dois, há quatro minutos"* antes do áudio.
 *   Sem isso, uma fala de meia hora atrás chegaria soando como se fosse agora — e
 *   agir sobre informação velha achando que é nova é pior que não receber.
 */
data class FalaDiferida(
    val transmissaoId: String,
    val origemMs: Long,
    val prioridade: PrioridadeTransmissao,
    val quadros: Int,
    val arquivo: File,
)

/**
 * **Modo diferido: o que fazer quando a rede não está lá.**
 *
 * A rede morre exatamente onde o rádio portátil já falha — em área sem cobertura
 * de dados. Esconder isso custa mais caro que admitir, e a resposta do produto é
 * *store-and-forward*: a fala vai para o disco e sai quando houver rede, com o
 * carimbo de origem preservado.
 *
 * ## A distinção que não pode se perder
 *
 * O **pré-roll nunca é persistido** — ele vive em RAM e some se o PTT não for
 * apertado. O que este arquivo grava é outra coisa: fala que o agente
 * **decidiu transmitir**, apertando o botão, e que a rede não aceitou. A
 * diferença entre as duas é a diferença entre escuta ambiente e rádio.
 *
 * Guardamos **Opus já codificado**, não PCM: um minuto de fala ocupa ~90 KB em
 * vez de ~1 MB, e é o mesmo formato que vai para a rede — sem recodificar, sem
 * perder qualidade de novo.
 *
 * Formato do arquivo, deliberadamente simples (sem dependência de serialização):
 * ```
 * [int] versão
 * [long] origemMs · [int] prioridade · [UTF] transmissaoId
 * repetido: [int] tamanho do quadro · [bytes] quadro Opus
 * ```
 */
class ArquivoDeFalasDiferidas(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /**
     * Grava uma fala inteira. Escrita **atômica** (`.tmp` + rename): sem isso, o
     * processo morrendo no meio deixaria um arquivo pela metade que o drenador
     * tentaria enviar para sempre.
     */
    fun gravar(
        transmissaoId: String,
        origemMs: Long,
        prioridade: PrioridadeTransmissao,
        quadros: List<ByteArray>,
    ): FalaDiferida? {
        if (quadros.isEmpty()) return null

        val destino = File(dir, "$origemMs-$transmissaoId$EXT")
        val tmp = File(dir, "${destino.name}.tmp")

        return runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSAO)
                out.writeLong(origemMs)
                out.writeInt(prioridade.nivel)
                out.writeUTF(transmissaoId)
                for (q in quadros) {
                    out.writeInt(q.size)
                    out.write(q)
                }
            }
            if (!tmp.renameTo(destino)) {
                tmp.copyTo(destino, overwrite = true)
                tmp.delete()
            }
            FalaDiferida(transmissaoId, origemMs, prioridade, quadros.size, destino)
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    /** Falas pendentes, **da mais antiga para a mais nova**. */
    fun pendentes(): List<FalaDiferida> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .mapNotNull { ler(it) }
            .sortedBy { it.origemMs }

    /** Lê os quadros de uma fala. Vazio se o arquivo estiver corrompido. */
    fun quadrosDe(fala: FalaDiferida): List<ByteArray> = runCatching {
        DataInputStream(fala.arquivo.inputStream().buffered()).use { input ->
            input.readInt()   // versão
            input.readLong()  // origem
            input.readInt()   // prioridade
            input.readUTF()   // id
            val quadros = ArrayList<ByteArray>()
            while (true) {
                val tamanho = try {
                    input.readInt()
                } catch (_: Exception) {
                    break
                }
                if (tamanho <= 0 || tamanho > TAMANHO_MAXIMO_QUADRO) break
                val bytes = ByteArray(tamanho)
                input.readFully(bytes)
                quadros.add(bytes)
            }
            quadros
        }
    }.getOrDefault(emptyList())

    fun remover(fala: FalaDiferida) {
        fala.arquivo.delete()
    }

    /**
     * Remove arquivos ilegíveis e os que passaram de [validadeMs].
     *
     * As duas remoções têm motivos diferentes e ambos importam. Ilegível: sem
     * isso o drenador tentaria enviar para sempre e gastaria bateria em
     * retentativas que nunca teriam o que enviar. Velho demais: uma fala de
     * ontem chegando hoje não é informação operacional, é ruído — e guardar voz
     * indefinidamente contraria a política de retenção.
     */
    fun podar(agoraMs: Long, validadeMs: Long = VALIDADE_MS): Int {
        var removidos = 0
        for (f in dir.listFiles { f -> f.isFile } ?: emptyArray()) {
            val fala = ler(f)
            if (fala == null || agoraMs - fala.origemMs > validadeMs) {
                if (f.delete()) removidos++
            }
        }
        return removidos
    }

    fun tamanho(): Int = pendentes().size

    private fun ler(arquivo: File): FalaDiferida? = runCatching {
        DataInputStream(arquivo.inputStream().buffered()).use { input ->
            val versao = input.readInt()
            if (versao != VERSAO) return null
            val origem = input.readLong()
            val nivel = input.readInt()
            val id = input.readUTF()
            var quadros = 0
            while (true) {
                val tamanho = try {
                    input.readInt()
                } catch (_: Exception) {
                    break
                }
                if (tamanho <= 0 || tamanho > TAMANHO_MAXIMO_QUADRO) break
                input.skipBytes(tamanho)
                quadros++
            }
            if (quadros == 0) return null
            FalaDiferida(id, origem, prioridadeDe(nivel), quadros, arquivo)
        }
    }.getOrNull()

    private fun prioridadeDe(nivel: Int) = when (nivel) {
        1 -> PrioridadeTransmissao.P1_EMERGENCIA
        3 -> PrioridadeTransmissao.P3_INFORMATIVO
        else -> PrioridadeTransmissao.P2_APOIO
    }

    private companion object {
        const val VERSAO = 1
        const val EXT = ".fala"

        /** Quadro Opus a 12 kbps tem ~30 B; 4 KB é folga com margem de sanidade. */
        const val TAMANHO_MAXIMO_QUADRO = 4096

        /** 6 h. Além disso a fala deixou de ser operacional. */
        const val VALIDADE_MS = 6 * 60 * 60 * 1000L
    }
}
