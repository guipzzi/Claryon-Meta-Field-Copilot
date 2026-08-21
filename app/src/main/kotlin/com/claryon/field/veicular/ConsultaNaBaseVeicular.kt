package com.claryon.field.veicular

import android.util.Log
import com.claryon.agent.Restricao
import com.claryon.field.agent.ClaryonIntentExecutor.ConsultaDePlaca
import com.claryon.net.BaseVeicular
import com.claryon.net.ClasseDeRestricao
import com.claryon.net.ConsultaVeicular
import com.claryon.net.Procedencia

/**
 * **A tradução entre a base veicular e o executor — e é aqui que "não sei" para de
 * poder virar "está limpo".**
 *
 * Existe pelo mesmo motivo que `com.claryon.field.norma.ConsultaDeNorma`: o executor
 * não conhece `core-net`, e `core-net` não conhece intenção. Quem conhece os dois
 * lados é este arquivo, num ponto único que dá para auditar de olho — e o que ele
 * decide é a única coisa deste fluxo que, feita errada, entrega a chave de um carro
 * roubado.
 *
 * ## A régua, e por que ela é assimétrica
 *
 * Os dois erros possíveis não custam a mesma coisa:
 *
 *  - **falso alarme** (dizer "furto/roubo" de um veículo limpo) faz o agente
 *    conferir mais uma vez;
 *  - **falso silêncio** (dizer "sem restrição" sem base que sustente) faz o agente
 *    liberar um veículo roubado.
 *
 * Por isso a regra não é simétrica. **Restrição declarada é falada mesmo vinda da
 * base de demonstração** — errar para o lado do alarme custa um segundo. Já *"sem
 * restrição"* só sai quando [ConsultaVeicular.liberaSemRestricao] autoriza, e essa
 * conta é feita dentro de `core-net`, uma vez, onde a migração `0023` e o cliente
 * concordam sobre o que ela significa.
 *
 * Tudo o mais — base ausente, demonstração sem restrição, placa que o servidor
 * recusou, transporte caído — é [ConsultaDePlaca.NaoRespondeu], e o agente ouve
 * *"Consulta indisponível."*.
 */
class ConsultaNaBaseVeicular(private val base: BaseVeicular) {

    suspend fun consultar(placa: String): ConsultaDePlaca {
        val resposta = base.consultar(placa).getOrElse { erro ->
            // Falha de transporte. Nunca silêncio, e nunca "sem restrição": o
            // agente ouve que a consulta não deu.
            Log.w(TAG, "consulta veicular falhou no transporte: ${erro.message}")
            return ConsultaDePlaca.NaoRespondeu
        }
        return traduzir(resposta)
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}

/**
 * Pura, e separada da classe de propósito: é a régua acima escrita como função, e
 * uma função pura é testável sem rede, sem servidor e sem aparelho.
 */
fun traduzir(resposta: ConsultaVeicular): ConsultaDePlaca = when (resposta) {

    is ConsultaVeicular.Encontrado -> when {
        // Restrição declarada sai SEMPRE, inclusive de base de demonstração. O
        // custo do falso alarme é uma conferência; o do falso silêncio é outro.
        resposta.restricao.impedeLiberacao ->
            ConsultaDePlaca.Respondeu(resposta.placa, resposta.restricao.classe.paraOAgente())

        // "Sem restrição" exige base oficial. `liberaSemRestricao` faz essa conta
        // em `core-net`, junto da definição de `Procedencia` — repeti-la aqui
        // abriria espaço para as duas divergirem.
        resposta.liberaSemRestricao ->
            ConsultaDePlaca.Respondeu(resposta.placa, Restricao.SEM_RESTRICAO)

        // Achou, está limpo, e a base é de demonstração: isso é informação sobre a
        // BASE, não sobre o veículo. Ver `Procedencia` em core-net.
        else -> ConsultaDePlaca.NaoRespondeu
    }

    // A base afirmou não ter o veículo. Só vale como "nada consta" se for oficial —
    // numa semente de seis linhas, "não encontrada" não é informação nenhuma.
    is ConsultaVeicular.NaoEncontrada ->
        if (resposta.procedencia == Procedencia.OFICIAL) {
            ConsultaDePlaca.Respondeu(resposta.placa, Restricao.SEM_RESTRICAO)
        } else {
            ConsultaDePlaca.NaoRespondeu
        }

    // O servidor disse que não era placa. Isso não vira consulta, e muito menos
    // "sem restrição": vira recusa audível.
    is ConsultaVeicular.PlacaInvalida -> ConsultaDePlaca.NaoRespondeu

    // Não há base. Falha de implantação, e tem de soar como falha.
    ConsultaVeicular.BaseIndisponivel -> ConsultaDePlaca.NaoRespondeu
}

/**
 * As três classes do servidor viram os três earcons do agente.
 *
 * O mapeamento é 1:1 e existe só porque `core-net` não pode enxergar `core-agent`
 * (os `core-*` só dependem de `core-common`). `ClasseDeRestricao` já é o espelho
 * declarado de `Restricao`, com o colapso de seis situações em três sons decidido e
 * escrito lá.
 */
private fun ClasseDeRestricao.paraOAgente(): Restricao = when (this) {
    ClasseDeRestricao.SEM_RESTRICAO -> Restricao.SEM_RESTRICAO
    ClasseDeRestricao.ADMINISTRATIVA -> Restricao.ADMINISTRATIVA
    ClasseDeRestricao.FURTO_ROUBO -> Restricao.FURTO_ROUBO
}
