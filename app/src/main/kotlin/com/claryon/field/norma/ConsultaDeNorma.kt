package com.claryon.field.norma

import android.util.Log
import com.claryon.knowledge.BaseDeConhecimentoLexical

/**
 * **A costura da Etapa A: o único lugar de `app` que conhece o conhecimento.**
 *
 * ## Por que este arquivo existe em vez de duas linhas no executor
 *
 * O caminho óbvio era construir o `BaseDeConhecimentoLexical` onde o
 * `ClaryonIntentExecutor` é montado e passar o lambda ali. Isso funcionaria, e
 * quebraria uma garantia: `FronteiraDoConhecimentoEmAppTest` exige que **nenhum
 * arquivo de produção de `app` mencione `com.claryon.knowledge` e o executor ao
 * mesmo tempo**.
 *
 * A garantia não é burocracia. O risco medido em 21/08 é concreto dos dois lados:
 * **252 dos 1817 artigos de lei**, entregues ao `DeterministicIntentRouter`, viram
 * ação — e **2 de 3 respostas geradas** pelo LLM também. O que impede isso de
 * acontecer não é cuidado: é não haver, em lugar nenhum, um arquivo onde o texto
 * recuperado e o construtor de intenção se encontrem.
 *
 * Então a fronteira vira **dois arquivos que não se conhecem**:
 *
 *  - **este**, que sabe o que é um `Trecho` e não sabe o que é `Intent`,
 *    `ActionOutcome` ou `IntentExecutor` — procure: nenhum deles aparece abaixo;
 *  - **o executor**, que recebe `suspend (String) -> Pair<String, String>?` e não
 *    tem vocabulário para falar de conhecimento.
 *
 * Nenhum dos dois consegue, sozinho, transformar norma em ação. E o teste continua
 * passando **sem exceção escrita à mão**, que era a outra saída e a pior: lista de
 * exceção é onde a próxima entra sem ninguém notar.
 *
 * ## O que atravessa, e por que só isso
 *
 * Sai `(citacao, norma)` — `"Art. 28"` e `"Lei 11.343/2006"` —, nunca o texto do
 * artigo. Não é economia: é o teto de **7 palavras** por fala operacional, que tem
 * teste varrendo todos os ramos de `utteranceFor`. Ler o artigo inteiro está
 * proposto em `specs/leitura-de-norma.spec.md`, esperando decisão humana (§7).
 *
 * ## O índice é preguiçoso, e isso importa no boot
 *
 * `BaseDeConhecimentoLexical` monta o índice na primeira busca: **112 ms** para
 * 1744 trechos, depois **913 µs** por consulta. Construir aqui como `by lazy`
 * significa que o agente que nunca pergunta nada não paga nada — e quem pergunta
 * paga uma vez só.
 */
object ConsultaDeNorma {

    private const val TAG = "ClaryonField"

    private val base by lazy { BaseDeConhecimentoLexical() }

    /**
     * Responde onde está a norma, ou `null` quando nada ficou perto o bastante.
     *
     * **`null` é resposta, não falha.** O limiar de 0,30 foi medido: abaixo dele o
     * índice devolveria o vizinho mais próximo, e vizinho mais próximo de uma
     * pergunta que a lei não responde é invenção com cara de citação. O aceite do
     * `ROADMAP.md` é literal sobre isso — *"uma pergunta fora do corpus produz 'não
     * encontrei procedimento para isso' e não uma invenção"*.
     *
     * Nunca lança: corpus ausente, recurso corrompido ou índice vazio viram `null`,
     * porque num produto sem display uma exceção que sobe é indistinguível de o
     * aplicativo ter morrido.
     */
    suspend fun consultar(pergunta: String): Pair<String, String>? {
        val trecho = base.buscar(pergunta)
        Log.i(
            TAG,
            "NORMA | pergunta=\"${pergunta.take(48)}\" → " +
                (trecho?.let { "${it.citacao} (${it.norma})" } ?: "recusada (abaixo do limiar)"),
        )
        return trecho?.let { it.citacao to it.norma }
    }

    /**
     * Quantos trechos o índice tem de pé. `0` significa que o corpus não chegou ao
     * APK — e nesse caso toda pergunta é recusada, o que é honesto e silencioso
     * demais para passar despercebido. Por isso existe este contador: ele é o que
     * o painel de diagnóstico e o teste de fumaça conseguem olhar.
     */
    val trechosIndexados: Int get() = base.trechosIndexados
}
