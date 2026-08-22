package com.claryon.field.radio

import java.io.File

/**
 * **Quais transmissões este aparelho ouviu terminarem no meio — e por que isso mora aqui.**
 *
 * Guarda os `transmissaoId` cujo `FimDaFala` foi `CORTADA_NO_MEIO`, para que a marca
 * do balão sobreviva ao poll de 10 s **e à morte do processo**.
 *
 * ---
 * ### A decisão: disco local, e não o servidor. O motivo é epistemológico, não técnico.
 *
 * Três caminhos foram considerados em 22/08.
 *
 * **Coluna no servidor — recusada, e não por custo.** O corte é uma **conclusão do
 * receptor**: o emissor sumiu sem anunciar nada, e é o `core-net/Receptor.kt` *deste*
 * aparelho que decide isso depois de esperar a janela de jitter inteira. Dois
 * receptores da mesma transmissão podem discordar com toda a razão — o que está num
 * túnel conclui `CORTADA_NO_MEIO`, o que está no descampado recebeu a fala inteira.
 * Escrever no servidor transformaria a condição de rede de **um** aparelho num fato
 * **global** sobre a transmissão, e o colega que ouviu tudo veria "cortada" numa fala
 * que, para ele, não foi. A pergunta *"quem escreve na divergência?"* não tem
 * resposta boa: último a escrever vence faz a marca piscar entre aparelhos, e
 * primeiro vence dá o registro ao pior enlace da guarnição. Um fato local não vira
 * verdade compartilhada só porque é conveniente persistir.
 *
 * **Aceitar a perda e declarar — recusada, e é a mais tentadora.** Já existe uma
 * perda declarada e legítima no KDoc de `FalaNoGrupo.cortadaPelaRede`: fala truncada
 * que este aparelho **não ouviu ao vivo** aparece inteira, porque afirmar corte sem o
 * evento seria inventar. Esta perda é de outra natureza e é pior: o aparelho **tinha**
 * o fato, com evidência de primeira mão, e o esqueceu porque o sistema recriou um
 * serviço `START_STICKY`. Não é uma pergunta que ele não sabe responder; é uma
 * resposta que ele jogou fora.
 *
 * **Arquivo local — escolhida.** O fato é local, então o armazenamento é local. É a
 * única das três em que o alcance do dado casa com o alcance da evidência.
 *
 * ---
 * ### Não é evidência, e por isso não usa `EncryptedFile`
 *
 * A regra dura do projeto — *"evidência fora de `EncryptedFile` + Keystore"* — vale
 * para o que é capturado do mundo: áudio, transcrição, frame, cadeia de custódia.
 * Isto é uma **dica de desenho**: um conjunto de identificadores opacos que decide se
 * um balão sai com uma régua tracejada. Não contém fala, nem texto, nem posição, nem
 * identidade — perdê-lo degrada a interface e não a prova. Guardá-lo no cofre custaria
 * uma abertura de Keystore no caminho de um poll de 10 s para proteger dados que não
 * dizem nada sobre ninguém.
 *
 * ---
 * ### A poda, que é a parte que se esquece
 *
 * Marca acumulada por turnos vira lixo que ninguém limpa. Duas réguas, e as duas
 * precisam existir:
 *
 *  - **[VALIDADE_MS] — 12 h**, um turno. Passado isso a transmissão saiu de qualquer
 *    histórico que a tela carregue, e o id vira peso morto. É a régua **principal**,
 *    porque é a que corresponde ao uso.
 *  - **[TETO] — 200 ids.** A rede de segurança para o caso que a validade não cobre:
 *    um turno anormalmente ruidoso, ou um relógio que andou para trás. Sem ela, um
 *    defeito de relógio faria o arquivo crescer sem limite.
 *
 * Poda na **leitura** e na **escrita**. Só na escrita deixaria um arquivo velho
 * intacto num aparelho que ficou dias desligado; só na leitura deixaria o arquivo
 * crescer dentro de uma sessão longa.
 *
 * ---
 * ### Formato
 *
 * Uma linha por corte, `id<TAB>carimbo`. Texto e não JSON: são dois campos e nenhum
 * deles é aninhado, e uma linha corrompida por escrita interrompida custa **um** id,
 * não o arquivo inteiro — [carregar] descarta linha malformada em silêncio, que é o
 * comportamento certo para um cache de dica.
 */
internal class CortesConhecidos(
    private val arquivo: File,
    private val agoraMs: () -> Long = System::currentTimeMillis,
) {

    private val cortes = LinkedHashMap<String, Long>()

    /** Os ids vivos. Cópia: o chamador não mexe no estado interno. */
    fun ids(): Set<String> = cortes.keys.toSet()

    operator fun contains(transmissaoId: String): Boolean = transmissaoId in cortes

    /**
     * Registra um corte. Devolve `false` quando o id já era conhecido.
     *
     * O retorno existe para o chamador **não gravar em disco à toa**: `Terminou`
     * chega uma vez por transmissão, mas nada impede uma reentrega, e uma escrita por
     * evento repetido é E/S no caminho de uma tela que rola.
     */
    fun marcar(transmissaoId: String): Boolean {
        if (transmissaoId in cortes) return false
        cortes[transmissaoId] = agoraMs()
        podar()
        return true
    }

    /**
     * Lê o arquivo, podando o que venceu.
     *
     * Nunca lança: arquivo ausente é o caso normal do primeiro turno, e arquivo
     * ilegível não pode derrubar o rádio por causa de uma dica de desenho.
     */
    fun carregar() {
        cortes.clear()
        val linhas = runCatching { if (arquivo.isFile) arquivo.readLines() else emptyList() }
            .getOrDefault(emptyList())
        linhas.forEach { linha ->
            val partes = linha.split('\t')
            if (partes.size != 2) return@forEach
            val carimbo = partes[1].toLongOrNull() ?: return@forEach
            if (partes[0].isNotBlank()) cortes[partes[0]] = carimbo
        }
        podar()
    }

    /** Grava o estado podado. Nunca lança — ver [carregar]. */
    fun gravar() {
        runCatching {
            arquivo.parentFile?.mkdirs()
            arquivo.writeText(cortes.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    /**
     * Validade primeiro, teto depois — e a ordem importa.
     *
     * Invertida, um lote recente e grande empurraria para fora ids ainda válidos
     * antes de a validade ter chance de remover os vencidos, e o aparelho perderia
     * marca boa guardando marca velha.
     */
    private fun podar() {
        val limite = agoraMs() - VALIDADE_MS
        cortes.entries.removeAll { it.value < limite }
        while (cortes.size > TETO) {
            // `LinkedHashMap` preserva ordem de inserção, e o mais antigo é o que sai.
            cortes.remove(cortes.keys.first())
        }
    }

    companion object {
        /** Um turno. Ver a nota sobre a poda. */
        const val VALIDADE_MS = 12L * 60 * 60 * 1000

        /** Rede de segurança para relógio que anda para trás. */
        const val TETO = 200

        /** Nome do arquivo em `filesDir`. */
        const val NOME = "cortes_da_rede.tsv"
    }
}
