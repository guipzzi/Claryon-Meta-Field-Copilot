package com.claryon.net

/**
 * **Consulta veicular — a fonte é trocável de propósito.**
 *
 * A base real é dado da corporação, como o gazetteer: ela chega por convênio, num
 * formato que ninguém neste repositório controla, e possivelmente por um serviço
 * que não é o Supabase. O cliente fala com esta interface e nunca com a base, para
 * que a troca seja de implementação e não de código de chamada.
 *
 * O tipo de retorno é onde mora a regra de produto. [ConsultaVeicular] **não tem um
 * booleano `temRestricao`**, e a ausência é a decisão central deste arquivo: um
 * booleano tem `false` como valor de repouso, e `false` seria lido como "limpo"
 * toda vez que a consulta falhasse, não achasse ou não fosse entendida. Aqui "sem
 * restrição" é um valor que só existe dentro de [ConsultaVeicular.Encontrado], e
 * que só chega lá se o servidor tiver dito exatamente isso.
 */
interface BaseVeicular {

    /**
     * @param placa como o agente falou ou como o OCR leu — com hífen, minúscula ou
     *   ruído. A normalização e a validação de formato acontecem **no servidor**,
     *   que é quem tem de decidir entre "não encontrada" e "não era placa".
     *
     * @return [Result.failure] apenas para falha de transporte ou resposta que este
     *   cliente não entende. **Nunca** para "não achei" — isso é
     *   [ConsultaVeicular.NaoEncontrada], que é uma resposta e não um erro.
     */
    suspend fun consultar(placa: String): Result<ConsultaVeicular>
}

/**
 * Origem da informação, presente em **toda** resposta — inclusive na que não achou
 * nada.
 *
 * O caso que obriga isso é a placa ausente da base. "Não encontrada" numa base
 * oficial é informação; "não encontrada" numa semente de seis linhas não é
 * informação nenhuma. As duas frases são idênticas e os significados são opostos, e
 * é justamente no caso sem linha que não há linha para carregar o flag. Por isso a
 * procedência descreve a **base consultada**, não o registro.
 */
enum class Procedencia {
    /** Semente de demonstração, base mista, base vazia, ou qualquer coisa que este
     *  cliente não reconheceu. Nada aqui pode ser falado como fato oficial. */
    DEMONSTRACAO,

    /** Base da corporação, íntegra. Exige prova positiva do servidor. */
    OFICIAL;

    companion object {
        /**
         * **Assimétrica de propósito.** Só o literal `"oficial"` produz [OFICIAL];
         * nulo, desconhecido ou grafia nova degradam para [DEMONSTRACAO].
         *
         * Errar para o lado de "isto é demonstração" estraga a confiança numa
         * resposta boa. Errar para o outro lado apresenta a encenação como verdade
         * oficial — e só esse erro devolve a chave de um carro roubado.
         */
        fun de(codigo: String?): Procedencia = if (codigo == "oficial") OFICIAL else DEMONSTRACAO
    }
}

/**
 * As três classes que o **earcon** distingue, espelhando
 * `com.claryon.agent.Restricao` (`ActionOutcome.kt:150`).
 *
 * São nomes repetidos de propósito, e não uma dependência: `core-*` só depende de
 * `core-common`, então `core-net` não pode enxergar `core-agent`. O espelho existe
 * para que o colapso de seis situações em três sons seja uma **decisão escrita
 * aqui**, e não uma escolha improvisada por quem for ligar o executor.
 */
enum class ClasseDeRestricao { SEM_RESTRICAO, ADMINISTRATIVA, FURTO_ROUBO }

/**
 * Situação do veículo, tal como a base declarou.
 *
 * Espelha o CHECK de `private.veiculos.restricao` na migração `0023`. As duas
 * pontas precisam concordar, e [de] é o lugar onde a discordância aparece.
 *
 * **Chama-se `RestricaoVeicular` e não `Restricao`** porque `core-agent` já tem uma
 * `Restricao` com três valores. Nomes iguais em pacotes diferentes obrigariam o
 * executor a importar com apelido, e um `import as` é onde uma conversão errada
 * passa despercebida numa revisão.
 */
enum class RestricaoVeicular(val codigo: String, val classe: ClasseDeRestricao) {
    /** **Nunca é padrão de nada.** Só existe porque a base escreveu, com fonte e data. */
    SEM_RESTRICAO("sem_restricao", ClasseDeRestricao.SEM_RESTRICAO),

    ROUBO_FURTO("roubo_furto", ClasseDeRestricao.FURTO_ROUBO),

    /**
     * **Classificada como furto/roubo, e a escolha é de segurança do agente.** Placa
     * clonada significa que a identidade de um veículo legítimo está sendo usada por
     * outro, e na prática acompanha veículo de origem criminosa. O earcon
     * administrativo é o som calmo; dá-lo aqui informaria ao agente que o caso é
     * burocrático no momento em que ele mais precisa saber que não é.
     */
    CLONAGEM_SUSPEITA("clonagem_suspeita", ClasseDeRestricao.FURTO_ROUBO),

    BLOQUEIO_JUDICIAL("bloqueio_judicial", ClasseDeRestricao.ADMINISTRATIVA),

    /** Judicial, não indício de risco ao agente — daí a classe administrativa. */
    APREENSAO("apreensao", ClasseDeRestricao.ADMINISTRATIVA),

    LICENCIAMENTO_VENCIDO("licenciamento_vencido", ClasseDeRestricao.ADMINISTRATIVA);

    /** Se o veículo deve travar a liberação. Derivado, nunca armazenado. */
    val impedeLiberacao: Boolean get() = this != SEM_RESTRICAO

    companion object {
        /**
         * @return `null` quando o código é desconhecido — e o chamador é obrigado a
         *   tratar isso como **falha**, jamais como [SEM_RESTRICAO].
         *
         * É a regra de compatibilidade para a frente deste arquivo. No dia em que o
         * servidor ganhar um código novo (`'recuperado'`, `'furto_em_apuracao'`), um
         * cliente antigo tem duas saídas: dizer que não entendeu, ou dizer que está
         * limpo. A segunda é a falha que este projeto inteiro existe para evitar, e
         * seria a que um `valueOf(...) ?: SEM_RESTRICAO` escolheria sozinho.
         */
        fun de(codigo: String?): RestricaoVeicular? = entries.firstOrNull { it.codigo == codigo }
    }
}

/**
 * Resposta da consulta veicular.
 *
 * Os quatro estados são exaustivos e **distintos de propósito**: `when` sobre esta
 * hierarquia não compila sem tratar todos, o que impede o colapso silencioso de
 * "não sei" em "está limpo". O servidor (`public.consultar_placa`) devolve sempre
 * exatamente uma linha com `situacao` explícita, justamente para que nenhum destes
 * estados precise ser inferido da ausência de dados.
 */
sealed interface ConsultaVeicular {

    /** A base tem o veículo e declarou a situação dele. */
    data class Encontrado(
        val placa: String,
        val procedencia: Procedencia,
        val restricao: RestricaoVeicular,
        /** Qual base, por extenso — a primeira pergunta da corregedoria. */
        val fonte: String?,
        val marcaModelo: String?,
        val cor: String?,
        val ano: Int?,
    ) : ConsultaVeicular

    /**
     * A base foi consultada e **afirmou** não ter o veículo.
     *
     * Só vale como "nada consta" se [procedencia] for [Procedencia.OFICIAL]. Numa
     * base de demonstração isto não é informação sobre o veículo — é informação
     * sobre a base.
     */
    data class NaoEncontrada(
        val placa: String,
        val procedencia: Procedencia,
        val fonte: String?,
    ) : ConsultaVeicular

    /** Não era placa. Ruído do reconhecedor, ou o agente falou outra coisa. */
    data class PlacaInvalida(val textoConsultado: String?) : ConsultaVeicular

    /** Não há base para consultar. Falha de implantação, e tem de soar como falha. */
    data object BaseIndisponivel : ConsultaVeicular

    /**
     * Se a resposta autoriza dizer ao agente que **nada consta**.
     *
     * Existe para que essa conta seja feita **uma vez, aqui**, e não em cada
     * chamador. Ela é `true` num único caso: base oficial que afirmou não ter o
     * veículo, ou base oficial que o tem e o declarou sem restrição. Qualquer outro
     * estado — demonstração, base ausente, placa inválida — é `false`.
     */
    val liberaSemRestricao: Boolean
        get() = when (this) {
            is Encontrado -> procedencia == Procedencia.OFICIAL && !restricao.impedeLiberacao
            is NaoEncontrada -> procedencia == Procedencia.OFICIAL
            is PlacaInvalida, BaseIndisponivel -> false
        }
}

/**
 * Implementação honesta para quando não há base configurada.
 *
 * É o padrão deliberado: um `BaseVeicular` ausente faria o chamador precisar de um
 * caminho nulo, e caminho nulo é onde "não sei" vira "está limpo". Este objeto
 * responde sempre [ConsultaVeicular.BaseIndisponivel], que é verdade.
 */
object BaseVeicularIndisponivel : BaseVeicular {
    override suspend fun consultar(placa: String): Result<ConsultaVeicular> =
        Result.success(ConsultaVeicular.BaseIndisponivel)
}
