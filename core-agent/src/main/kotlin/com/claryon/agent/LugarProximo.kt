package com.claryon.agent

import com.claryon.common.LaconicityPolicy

/**
 * Um lugar público, como a resposta precisa dele: **nome e distância**.
 *
 * Repare no que não existe aqui — latitude, longitude, endereço, telefone,
 * horário. É a mesma disciplina de [PosicaoRelativa]: o tipo carrega a grandeza
 * que a fala usa, e nada mais. Um campo a mais seria um campo que alguém acaba
 * lendo em voz alta dentro do teto de sete palavras, ou pondo num registro que
 * deveria ser publicável.
 */
data class LugarProximo(val nome: String, val distanciaM: Int)

/**
 * Resultado de procurar um lugar pela fonte externa.
 *
 * **Quatro casos, e nenhum colapso.** É a mesma lição de [BuscaDePar], onde
 * "não localizado" e "não consegui consultar" já se provaram fatos diferentes:
 *
 *  - [NadaPorPerto] é o sistema **funcionando** — teve rede, a fonte respondeu,
 *    não há hospital no raio. Vestir isso de falha ensinaria o agente a
 *    desconfiar do aparelho quando o aparelho acertou;
 *  - [SemRede] é o caso normal em campo, não a exceção: o relato da PMERJ que
 *    fundamenta este produto descreve região onde nem rádio digital pega;
 *  - [PrazoEstourado] teve rede e não teve resposta a tempo. A recuperação é
 *    diferente — insistir pode dar certo, andar até pegar sinal não muda nada;
 *  - [SemPosicaoPropria] é o caso que só apareceu ao LIGAR a capacidade, e por
 *    isso ele não estava aqui: *"o hospital mais próximo"* é uma pergunta
 *    relativa, e sem correção de GPS ela não tem como ser feita. Colapsá-lo em
 *    [SemRede] mandaria o agente andar atrás de sinal que ele já tem — a mesma
 *    classe de erro que fez o rádio confundir falta de rede com canal ocupado.
 */
sealed interface BuscaDeLugar {
    data class Encontrado(val lugar: LugarProximo) : BuscaDeLugar
    data object NadaPorPerto : BuscaDeLugar
    data object SemRede : BuscaDeLugar
    data object PrazoEstourado : BuscaDeLugar
    data object SemPosicaoPropria : BuscaDeLugar
}

/**
 * **Como um lugar vira fala — e por que ela NÃO se apresenta.**
 *
 * *"Hospital Getúlio Vargas, a 800 metros."* Ponto. Sem *"segundo a internet"*,
 * sem *"encontrei na web"*, sem ressalva.
 *
 * A primeira versão da spec mandava a resposta externa se rebaixar em voz alta.
 * O dono do projeto inverteu isso em 22/08, e o teto de sete palavras torna o
 * argumento concreto: **palavra falada é cara**, e ressalva repetida vira ruído
 * que o agente aprende a ignorar — junto com a informação que vinha depois dela.
 *
 * O sinal de credibilidade **já existe e não custa sílaba**: é a citação. Resposta
 * interna cita norma e número (*"Art. 306, Lei 9.503"*); resposta externa apenas
 * responde. **A ausência da citação é o sinal**, e o agente aprende a distinção
 * pelo uso — o mesmo mecanismo que faz *"nada consta"* valer diferente conforme a
 * `Procedencia` da base veicular.
 *
 * Não falar não é não guardar: a procedência é **registrada** fora da fala —
 * serviço, trecho que fundamentou e carimbo de tempo. Ver a spec §4.
 */
object FalaDeLugar {

    /**
     * Frase de um lugar encontrado, dentro do teto de sete palavras.
     *
     * **Degradação por corte, do detalhe menos essencial para o mais**, igual a
     * [FalaDePosicao.para]. O nome vem de dado aberto (OSM) e ninguém controla o
     * comprimento dele: *"Hospital Municipal Souza Aguiar"* cabe, *"Hospital
     * Estadual Getúlio Vargas do Estado do Rio de Janeiro"* não. Deixar o teto
     * depender de os cadastros do OSM serem curtos é apoiar um invariante do
     * produto na disciplina de um terceiro.
     *
     * A distância é a **última** a cair porque é ela que decide se o agente vai a
     * pé ou de viatura — e [FalaDePosicao.distanciaFalada] já arredonda para a
     * precisão que o GPS realmente tem, em vez de sugerir exatidão inexistente.
     */
    fun para(lugar: LugarProximo): String {
        val distancia = FalaDePosicao.distanciaFalada(lugar.distanciaM)
        val palavras = lugar.nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // O OSM tem nó sem `name`, e isso é dado, não defeito. Dizer "Sem nome" é
        // honesto e ainda entrega o que importa — a distância. Inventar um nome
        // aqui seria a versão geoespacial de inventar artigo de lei.
        if (palavras.isEmpty()) return "Sem nome, $distancia."
        val candidatas = (palavras.size downTo 1).map { n ->
            "${palavras.take(n).joinToString(" ")}, $distancia."
        }
        return candidatas.firstOrNull { LaconicityPolicy.isWithinLimit(it) } ?: candidatas.last()
    }
}
