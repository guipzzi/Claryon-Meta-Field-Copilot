package com.claryon.field.agent

import android.util.Log
import com.claryon.agent.RegistroDeAuditoria
import com.claryon.agent.RegistroDeUso
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * **Os DOIS destinos da consulta externa — e o que os separa é que são dois.**
 *
 * `specs/consulta-externa.spec.md` pede §4 (procedência com serviço, trecho e
 * carimbo preciso) e §7.5 (granularidade de dia, sem agente, sem posição). Um
 * registro só que atendesse aos dois teria de guardar a precisão do primeiro — e
 * aí o segundo deixaria de ser publicável, que é a propriedade inteira dele.
 *
 * Aqui isso é **dois fluxos**, não dois campos do mesmo:
 *
 *  - [auditoria] tem serviço, consulta emitida, trecho e milissegundo. **Fica no
 *    aparelho.**
 *  - [uso] tem consulta, categoria, se respondeu, quem respondeu, e o **dia**.
 *    Pode sair.
 *
 * ## O que está construído e o que não está — dito aqui para não virar mentira
 *
 * Os dois vivem em **RAM**, com teto, e vão para o `logcat` sob a tag
 * `ClaryonField`. É o que existe hoje, e é honesto: o pré-roll do PTT também vive
 * em RAM por regra dura, então "em memória" não é sinônimo de provisório neste
 * projeto.
 *
 * O que **não** existe: persistência do [uso] em arquivo, e tela lendo a
 * [auditoria]. O §4 diz que a auditoria *"fica disponível na tela"* — o fluxo está
 * publicado aqui para que a tela o observe, e **nenhuma tela o observa ainda**.
 * Escrever o contrário seria a sétima vez que este projeto declara construído o
 * que só está escrito.
 */
class DiarioDaConsultaExterna(private val teto: Int = TETO) {

    private val _auditoria = MutableStateFlow<List<RegistroDeAuditoria>>(emptyList())

    /** Auditoria da resposta, do mais recente para o mais antigo. Não sai daqui. */
    val auditoria: StateFlow<List<RegistroDeAuditoria>> = _auditoria.asStateFlow()

    private val _uso = MutableStateFlow<List<RegistroDeUso>>(emptyList())

    /** Estatística de uso — o andaime da decisão 5. Publicável por construção. */
    val uso: StateFlow<List<RegistroDeUso>> = _uso.asStateFlow()

    /**
     * Registra a procedência de uma resposta de fonte externa.
     *
     * Chamado em **toda** resposta — inclusive na que não achou nada, que é onde
     * não há linha de resultado para pendurar o flag e por isso é a que se esquece.
     * Ver `Procedencia` em `core-net`.
     */
    fun auditar(registro: RegistroDeAuditoria) {
        _auditoria.update { (listOf(registro) + it).take(teto) }
        Log.i(
            TAG,
            "externa: ${registro.categoria} → ${registro.servico} " +
                "em ${registro.duracaoMs} ms; consulta=${registro.consultaEmitida}",
        )
    }

    /**
     * Contabiliza a pergunta, respondida ou não.
     *
     * **A não respondida é a que mais importa** — é ela que diz para onde o corpus
     * precisa crescer. A curva pretendida pela decisão 5 é essa contagem migrando
     * de `EXTERNA_ESTRUTURADA` para `LOCAL` com o tempo.
     */
    fun contabilizar(registro: RegistroDeUso) {
        _uso.update { (listOf(registro) + it).take(teto) }
        Log.i(TAG, "uso: ${registro.paraLinha()}")
    }

    companion object {
        private const val TAG = "ClaryonField"

        /**
         * Teto das duas listas.
         *
         * Sem teto, um turno inteiro de consultas cresce sem limite num processo em
         * primeiro plano — e a memória que o whisper de 75 MB já disputa não sobra
         * para um histórico. Cinquenta cobre a conferência que o §4 descreve ("o
         * agente pode conferir depois"), que é sobre a consulta recente, não sobre
         * o turno inteiro.
         */
        const val TETO = 50

        /**
         * O diário do processo. Um só, pela mesma razão de `AudioDoAgente`: dois
         * diários seriam duas metades do histórico, e a tela leria a metade errada.
         */
        val DO_PROCESSO = DiarioDaConsultaExterna()
    }
}
