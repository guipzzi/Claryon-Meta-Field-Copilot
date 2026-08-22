package com.claryon.field.radio

import com.claryon.agent.FalhaOperacional
import com.claryon.field.agent.ClaryonIntentExecutor.TrocaDeGrupo

/**
 * **A decisão de trocar de grupo, separada de quem detém o estado.**
 *
 * Esta classe existe por um motivo de testabilidade que é também de desenho: a
 * lógica com mais consequência desta feature é a **ordem das guardas**, e ela
 * estava dentro de [CanaisDoAgente] — um `object` com `Context` e `SessaoDoAgente`,
 * que nenhum teste de JVM alcança. Regra com consequência operacional morando em
 * código intestável é como não ter a regra: ela funciona até o dia em que alguém
 * reordena duas linhas.
 *
 * [CanaisDoAgente] ficou sendo o dono do estado; aqui mora a política.
 *
 * ## A ordem das guardas não é arbitrária
 *
 * 1. **Transmissão em curso** vem primeiro, e não é conveniência. Recusar por estar
 *    no ar não depende de o grupo existir — e checar isso *depois* de resolver o
 *    rótulo faria a recusa vazar a informação de que o grupo existe: quem está
 *    transmitindo ouviria "não conheço essa guarnição" para grupo alheio e "fale
 *    depois de encerrar" para grupo próprio. Dois textos diferentes são um oráculo.
 * 2. **Sem léxico** antes de resolver, porque lista ausente e rótulo ausente pedem
 *    recuperações diferentes do agente: uma é entrar de novo, a outra é repetir.
 * 3. **Rádio no ar** antes de "grupo repetido", e esta ordem é a correção de 22/08.
 *    Estava invertida: o atalho do grupo corrente devolvia `Trocado` sem olhar para
 *    o rádio, porque "não tocar no socket" parecia dispensar o socket. Não dispensa
 *    — o executor usa o MESMO `Trocado` para seguir e chamar `abrirTransmissao`, e
 *    lá o abridor devolvia `false` cru, que vira *"Canal ocupado."* na fala. Num
 *    aparelho sem rota de áudio o canal não está ocupado: o rádio é que não subiu.
 *    Confirmar "já estamos lá" com o rádio fora do ar é a mesma mentira que
 *    [trocador] `== null` sempre recusou, entrando pela porta do atalho.
 * 4. **Grupo repetido** antes de tocar no rádio: `RadioTatico.trocarDeGrupo` derruba
 *    receptor, transporte e buffer de jitter. Pagar isso por um comando redundante
 *    deixaria o agente surdo por alguns quadros sem motivo nenhum.
 */
class PoliticaDeTrocaDeGrupo(
    private val resolvedor: ResolvedorDeGrupo,
    /** Lido no instante da decisão: a transmissão começa e termina entre comandos. */
    private val transmitindo: () -> Boolean,
    private val grupoCorrenteId: () -> String,
    /**
     * Quem sabe mexer no socket, ou `null` se ninguém atou o fio.
     *
     * `null` **não** vira sucesso silencioso: dizer "Agora na guarnição três" com o
     * rádio fechado faria o agente passar a falar acreditando estar em outro canal.
     */
    private val trocador: () -> (suspend (String) -> Boolean)?,
    /**
     * **O rádio pode pôr o agente no ar agora?**
     *
     * Separado de [trocador] porque as duas perguntas deixaram de ser a mesma. O fio
     * passou a ser atado **antes** da rota de áudio — senão nenhum aparelho sem HFP
     * teria fio nenhum —, então "há trocador" virou verdade permanente e parou de
     * significar "há rádio". Quem responde isto é `CanaisDoAgente`, lendo o
     * `RadioTatico` no instante da decisão.
     *
     * O padrão preserva o comportamento de quem não conhece o parâmetro: sem fio
     * atado, não há rádio no ar.
     */
    private val noAr: () -> Boolean = { trocador() != null },
) {

    /**
     * O resultado da troca, e o efeito colateral que o chamador precisa aplicar.
     *
     * Devolver o novo grupo em vez de escrevê-lo mantém esta classe **sem estado** —
     * o que a torna testável sem `Context` e impede que duas verdades apareçam.
     */
    data class Decisao(
        val resultado: TrocaDeGrupo,
        /** Quando não-nulo, o dono do estado deve adotar este grupo. */
        val novoGrupo: com.claryon.net.GrupoFalado? = null,
    )

    suspend fun decidir(rotuloFalado: String): Decisao {
        if (transmitindo()) {
            return Decisao(TrocaDeGrupo.Falhou(FalhaOperacional.TRANSMISSAO_EM_CURSO))
        }

        return when (val r = resolvedor.resolver(rotuloFalado)) {
            ResolvedorDeGrupo.Resultado.SemLexico ->
                Decisao(TrocaDeGrupo.Falhou(FalhaOperacional.SEM_LEXICO_DE_CANAIS))

            ResolvedorDeGrupo.Resultado.NaoReconhecido ->
                Decisao(TrocaDeGrupo.NaoReconhecido)

            is ResolvedorDeGrupo.Resultado.Encontrado -> {
                val alvo = r.grupo
                // **Guarda 3, e ela vem antes do atalho.** `Trocado` é a licença que
                // o executor usa para chamar `abrirTransmissao` em seguida; emiti-la
                // com o rádio fora do ar entrega o comando a um abridor que só sabe
                // dizer `false`, e `false` cru é falado como "Canal ocupado.".
                if (!noAr()) {
                    return Decisao(TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO))
                }
                // Já estamos lá: confirma sem tocar no rádio. Ver guarda 4.
                if (alvo.id == grupoCorrenteId()) {
                    return Decisao(TrocaDeGrupo.Trocado(alvo.nome))
                }
                val trocar = trocador()
                    ?: return Decisao(TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO))

                if (!trocar(alvo.id)) {
                    // O rádio recusou. Não adota o grupo novo: adotar aqui faria o
                    // estado e o socket discordarem, e o próximo comando recusaria
                    // "já estamos lá" para um grupo em que não estamos.
                    return Decisao(TrocaDeGrupo.Falhou(FalhaOperacional.RADIO_FECHADO))
                }
                Decisao(TrocaDeGrupo.Trocado(alvo.nome), novoGrupo = alvo)
            }
        }
    }
}
