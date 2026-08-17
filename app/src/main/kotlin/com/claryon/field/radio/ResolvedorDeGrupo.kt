package com.claryon.field.radio

import com.claryon.net.GrupoFalado
import com.claryon.net.RotulosFalados

/**
 * **Resolve um rótulo falado em talk group — por igualdade, e só por igualdade.**
 *
 * ## O que está em jogo
 *
 * Trocar de talk group redireciona a voz do agente para outra guarnição. Se a
 * resolução erra, ele pede apoio para a unidade errada **acreditando ter pedido
 * para a certa** — e não tem como descobrir, porque a confirmação falada diria o
 * nome do grupo que o sistema escolheu, não o que ele quis.
 *
 * Por isso não há casamento aproximado aqui. A tentação é óbvia e vai reaparecer:
 * o STT devolveu "agora nisso são três", "guarnicao tres" está na lista, e uma
 * distância de edição de 8 resolveria. **Está proibido pela spec**, e o motivo é
 * operacional: recusar custa uma repetição, adivinhar errado custa apoio que não
 * vem. Distância de edição aqui converteria erro de transcrição em erro de
 * despacho — e, pior, esconderia o defeito do STT em vez de expô-lo.
 *
 * Se a transcrição está ruim demais para casar exato, o conserto é no STT.
 *
 * ## Uma recusa, dois motivos
 *
 * [Resultado.NaoReconhecido] cobre "grupo não existe" e "existe e você não é
 * membro" com a mesma resposta. Distinguir vazaria a estrutura da corporação pelo
 * texto da recusa — a mesma classe de erro que o servidor evita ao devolver
 * grandezas em vez de coordenada de terceiro.
 *
 * ## Esta classe não autoriza nada
 *
 * A lista vem de `public.meus_rotulos_falados()`, que já filtra por membership com
 * o solicitante saindo do JWT. Mas a **fronteira de segurança** é `pedir_canal`
 * (`servidor/migracoes/0005_controle_de_piso.sql:78-82`), que recusa não-membro no
 * servidor. Um cliente adulterado que peça grupo fora da lista continua sendo
 * recusado onde importa. Esta classe é conveniência de UX e economia de rede.
 */
class ResolvedorDeGrupo(
    /**
     * O léxico carregado no login. `null` enquanto não carregou — e essa distinção
     * é o que separa "não conheço esse grupo" de "não tenho lista nenhuma", que
     * exigem recuperações diferentes do agente.
     */
    private val lexico: () -> List<GrupoFalado>?,
) {

    sealed interface Resultado {
        /** Casou. [grupo] tem o id para `trocarDeGrupo` e o nome para a fala. */
        data class Encontrado(val grupo: GrupoFalado) : Resultado

        /** Não está na lista deste agente. Não diz se existe. */
        data object NaoReconhecido : Resultado

        /** A lista não carregou. Nunca cair para `CanalDoPiloto` em silêncio. */
        data object SemLexico : Resultado
    }

    /**
     * Compara pela **mesma** função de normalização usada no lado do servidor.
     *
     * `RotulosFalados.normalizar` e não uma cópia local: normalizar de formas
     * diferentes nos dois lados é como não normalizar — a comparação falha em
     * silêncio e a conclusão vira "o servidor está errado". O projeto já foi
     * mordido por isso.
     */
    fun resolver(rotuloFalado: String): Resultado {
        val lista = lexico() ?: return Resultado.SemLexico
        if (lista.isEmpty()) return Resultado.SemLexico

        val pedido = RotulosFalados.normalizar(rotuloFalado)
        if (pedido.isEmpty()) return Resultado.NaoReconhecido

        val achado = lista.firstOrNull { RotulosFalados.normalizar(it.rotuloFalado) == pedido }
        return achado?.let { Resultado.Encontrado(it) } ?: Resultado.NaoReconhecido
    }
}
