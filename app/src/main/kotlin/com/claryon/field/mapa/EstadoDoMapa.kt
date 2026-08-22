package com.claryon.field.mapa

import com.claryon.agent.PoliticaDePosicao
import com.claryon.net.MarcadorDePar

/** Como um marcador deve ser desenhado. A forma carrega a confiança no dado. */
enum class Frescor {
    /** Posição recente. Marcador cheio. */
    ATUAL,

    /**
     * Acima de 2 min. Marcador esmaecido — **mostrar posição velha como atual é
     * pior que não mostrar**. É requisito de segurança, não polimento: o agente
     * decidiria a abordagem contando com um apoio que já saiu dali.
     */
    ESMAECIDO,

    /** Acima de 10 min. O marcador para de afirmar posição e passa a dizer idade. */
    ANTIGO,
}

/**
 * Um par pronto para desenhar.
 *
 * O rótulo é o **indicativo**, nunca nome nem matrícula. Um mapa que mostra
 * "SD Silva, mat. 41.882" transforma uma ferramenta de coordenação numa
 * ferramenta de controle sobre o próprio efetivo, e o agente que sabe disso
 * desliga o app.
 */
/**
 * Um ponto do rastro, já em texto falável.
 *
 * Sem número cru e sem coordenada: o tipo carrega o que se DIZ, não o que se
 * mede. A conversão acontece uma vez, no ViewModel, com as mesmas funções que a
 * fala usa — assim a gaveta e o copiloto nunca divergem sobre a mesma distância.
 */
data class TracoDoRastro(
    val distanciaFalada: String,
    val rumoFalado: String,
    val idadeFalada: String,
)

data class ParNoMapa(
    val indicativo: String,
    val distanciaFalada: String,
    val rumoFalado: String,
    val frescor: Frescor,
    /** Preenchido só em [Frescor.ANTIGO]: "posição de 14 minutos". */
    val idadeFalada: String?,
    /**
     * Quando a posição foi atualizada, sempre presente: "agora", "há 40 s",
     * "há 6 min".
     *
     * Aparece em **todas** as linhas, não só nas velhas. O esmaecimento diz
     * "confie menos"; o carimbo diz *quanto* menos — e é a diferença entre o
     * agente decidir por sensação e decidir por número.
     */
    val atualizadoHa: String,
    val emMovimento: Boolean,
    /** Grandezas cruas, para o radar plotar. A lista mostra as versões faladas. */
    val distanciaM: Int,
    val rumoGraus: Double?,
) {
    /**
     * Par praticamente no mesmo ponto — dupla na mesma viatura, que é a
     * configuração mais comum do policiamento.
     *
     * Abaixo deste limiar, distância e rumo deixam de ser informação: "0 metros,
     * oeste" é ruído com cara de dado, e o rumo nem existe (o PostGIS devolve nulo
     * para pontos coincidentes). A mesma regra que a fala já aplica.
     */
    val juntoDeMim: Boolean
        get() = rumoGraus == null || distanciaM < com.claryon.agent.FalaDePosicao.JUNTO_M
}

/**
 * O que a tela mostra num instante.
 *
 * **Três estados que colapsavam em um.** Até 21/08 a lista vazia era uma coisa só,
 * e a gaveta escrevia *"Ninguém publicando · Nenhum par do talk group está
 * enviando posição agora"* em cima de qualquer uma delas. Uma auditoria mostrou
 * que a mais comum era justamente aquela em que a frase mente: `posicoes_do_grupo`
 * faz `cross join minha`, então **quem não publicou recebe zero linhas mesmo com a
 * guarnição inteira publicando** — a causa é do portador e a frase acusava os
 * outros. Os três agora são distinguíveis daqui:
 *
 *  1. **Não publiquei** — [temPosicaoPropria] `false`. A causa está em
 *     [motivoDaMinhaPosicao], derivada do estado real da transmissão.
 *  2. **Ninguém publicou** — [temPosicaoPropria] `true` e [pares] vazia. Decisão
 *     do dono do produto: o mapa fica em branco, **só com a posição do portador**,
 *     sem acusar ninguém.
 *  3. **Não estou recebendo** — [motivoIndisponivel] preenchido. A sondagem falhou
 *     ou não há sessão; nada do que está aqui dentro é afirmação sobre o mundo.
 */
data class EstadoDoMapa(
    val pares: List<ParNoMapa>,
    val assinado: Boolean,
    /**
     * O **servidor** tem a minha posição.
     *
     * Deixou de ser o literal `true` que era: com linhas na resposta, a prova é
     * direta (o `cross join minha` só rende linha se eu tiver publicado); sem
     * linhas, a melhor evidência é local — [EstadoDaTransmissao.viva].
     */
    val temPosicaoPropria: Boolean,
    val motivoIndisponivel: String?,
    /**
     * Há quanto tempo o servidor tem a minha posição, em segundos.
     *
     * Vem de `idade_solicitante_s`, que chega em **toda** linha de
     * `posicoes_do_grupo` desde a migração `0007` — não custa requisição nova. O
     * dado existia no fio, era lido no ViewModel e morria ali, virando um booleano
     * de tudo-ou-nada. `null` quando não há linha nenhuma de onde tirá-lo.
     */
    val minhaIdadeS: Int? = null,
    /**
     * **A minha posição está subindo agora?** O carimbo do portador, que era o
     * único do mapa que ninguém dava.
     */
    val minhaPosicaoSobe: Boolean = false,
    /**
     * Por que sobe, ou por que não — sempre derivada do estado, nunca fixa.
     *
     * Presente também no caminho feliz ("Última posição enviada há 40 s"): dizer
     * "está subindo" sem dizer *quando* subiu é a mesma afirmação sem lastro que
     * o resto deste arquivo existe para eliminar.
     */
    val motivoDaMinhaPosicao: String? = null,
    /**
     * A coordenada do **portador**, e só dela.
     *
     * Existe para ancorar o mapa de ruas: os pares chegam como distância e rumo, e
     * sem uma origem não há onde plotá-los. Nula até a primeira correção de GPS —
     * e enquanto for nula o mapa não desenha, porque um mapa centrado num lugar
     * arbitrário mostraria a guarnição inteira na rua errada.
     */
    val minhaLatitude: Double? = null,
    val minhaLongitude: Double? = null,
    /**
     * Para onde o portador aponta, em graus a partir do norte. `null` parado.
     *
     * Nunca é publicado nem consultado: existe só para girar a seta na tela deste
     * agente. Ver `Coordenada.rumoGraus`.
     */
    val meuRumoGraus: Float? = null,
    /**
     * O rastro de 30 minutos do par em foco, em GRANDEZA — nunca coordenada.
     *
     * Vazio quando ninguém está focado, e **tem de esvaziar ao desfocar**: rastro
     * de um par que já não está em foco é dado de vigilância pendurado na tela.
     */
    val rastro: List<TracoDoRastro> = emptyList(),
    /** De quem é o [rastro]. Existe para a tela não mostrar rastro de um sob o outro. */
    val rastroDe: String? = null,
) {
    companion object {
        /**
         * Estado vazio com a causa **dita**, nunca uma tela em branco.
         *
         * Mapa vazio e mapa indisponível são indistinguíveis para quem olha — e a
         * leitura errada é a perigosa: "ninguém por perto" quando na verdade é
         * "não estou recebendo". A causa aparece escrita.
         */
        fun indisponivel(motivo: String) = EstadoDoMapa(
            pares = emptyList(),
            assinado = false,
            temPosicaoPropria = false,
            motivoIndisponivel = motivo,
        )

        /**
         * O mesmo, carregando a causa da transmissão própria.
         *
         * "Não estou recebendo" e "não estou transmitindo" são falhas
         * independentes e podem coexistir — a rede caiu para os dois lados. A tela
         * precisa das duas para não trocar uma pela outra.
         */
        fun indisponivel(
            motivo: String,
            minhaPosicaoSobe: Boolean,
            motivoDaMinhaPosicao: String?,
        ) = EstadoDoMapa(
            pares = emptyList(),
            assinado = false,
            temPosicaoPropria = false,
            motivoIndisponivel = motivo,
            minhaPosicaoSobe = minhaPosicaoSobe,
            motivoDaMinhaPosicao = motivoDaMinhaPosicao,
        )
    }
}

/**
 * Traduz marcadores crus em algo desenhável.
 *
 * Puro de propósito: a regra que importa — **quando parar de afirmar que a
 * posição é atual** — fica testável sem tela, sem rede e sem GPS.
 */
object MapaDePares {

    /**
     * **Caminho do espelho de coordenadas — hoje sem chamador em `src/main`.**
     *
     * `grep -rn "MapaDePares.montar(" app/src` devolve só `MapaDeParesTest`. Ele é
     * o resto da época em que a recepção era assinatura Realtime de coordenadas de
     * par; desde que virou sondagem de RPC, quem monta a tela é
     * [montarDeGrandezas]. Fica registrado aqui em vez de na cabeça de quem ler:
     * classe testada sem chamador é **escrita**, não construída, e as regras que
     * este bloco ainda protege (frescor, movimento, ordem) estão duplicadas na
     * outra função — é lá que elas valem no aparelho.
     */
    fun montar(marcadores: List<MarcadorDePar>, assinado: Boolean): EstadoDoMapa =
        EstadoDoMapa(
            pares = marcadores.map(::traduzir),
            assinado = assinado,
            temPosicaoPropria = true,
            motivoIndisponivel = null,
        )

    /**
     * Monta a partir das grandezas que o servidor calculou — o caminho do mapa
     * desde que a recepção passou a ser sondagem de RPC em vez de assinatura de
     * coordenadas.
     *
     * **`temPosicaoPropria` deixou de ser o literal `true`.** Ele era escrito
     * mesmo sobre a lista VAZIA, que é exatamente o que `posicoes_do_grupo`
     * devolve quando quem pergunta nunca publicou — o `cross join minha` zera o
     * resultado inteiro. A tela lia esse `true` e concluía "ninguém do grupo está
     * publicando" a partir de uma resposta que não diz nada sobre o grupo.
     *
     * A regra agora tem duas metades e nenhuma inventa:
     *
     *  - **Com linhas**, o próprio `cross join` é a prova: o servidor não teria
     *    calculado distância nenhuma sem a minha posição.
     *  - **Sem linhas**, a resposta é muda, e a única evidência disponível é local
     *    — [minhaPosicaoSobe], que vem de `TransmissaoDePosicao`.
     */
    fun montarDeGrandezas(
        posicoes: List<com.claryon.net.RespostaDePosicao>,
        assinado: Boolean,
        minhaLatitude: Double? = null,
        minhaLongitude: Double? = null,
        meuRumoGraus: Float? = null,
        minhaPosicaoSobe: Boolean = false,
        motivoDaMinhaPosicao: String? = null,
    ): EstadoDoMapa = EstadoDoMapa(
        pares = posicoes.map { p ->
            val frescor = frescorDe(p.idadeS)
            ParNoMapa(
                indicativo = p.indicativo,
                distanciaFalada = com.claryon.agent.FalaDePosicao.distanciaFalada(p.distanciaM),
                rumoFalado = p.azimuteGraus
                    ?.let(com.claryon.agent.Rumo::deGraus)?.falado.orEmpty(),
                frescor = frescor,
                idadeFalada = if (frescor == Frescor.ANTIGO) idade(p.idadeS) else null,
                atualizadoHa = carimbo(p.idadeS),
                emMovimento = (p.velocidadeMs ?: 0f) > 1f && frescor == Frescor.ATUAL,
                distanciaM = p.distanciaM,
                rumoGraus = p.azimuteGraus,
            )
        },
        assinado = assinado,
        temPosicaoPropria = posicoes.isNotEmpty() || minhaPosicaoSobe,
        motivoIndisponivel = null,
        // A idade da MINHA posição, a mesma que mede toda distância desta tela.
        // `minOf` e não `first`: as linhas trazem o mesmo valor, e o mínimo é o
        // único agregado que não passa a mentir se um dia deixarem de trazer.
        minhaIdadeS = posicoes.minOfOrNull { it.idadeDoSolicitanteS },
        minhaPosicaoSobe = minhaPosicaoSobe,
        motivoDaMinhaPosicao = motivoDaMinhaPosicao,
        minhaLatitude = minhaLatitude,
        minhaLongitude = minhaLongitude,
        meuRumoGraus = meuRumoGraus,
    )

    private fun frescorDe(idadeS: Int): Frescor = when {
        PoliticaDePosicao.marcadorMuitoVelho(idadeS) -> Frescor.ANTIGO
        PoliticaDePosicao.marcadorObsoleto(idadeS) -> Frescor.ESMAECIDO
        else -> Frescor.ATUAL
    }

    /**
     * Carimbo curto de atualização.
     *
     * "agora" abaixo de 15 s, e não "há 0 s": zero segundos sugere precisão que o
     * GPS e a rede não têm, e o agente lê "0" como instantâneo.
     */
    private fun carimbo(idadeS: Int): String = when {
        idadeS < 15 -> "agora"
        idadeS < 60 -> "há ${idadeS}s"
        idadeS < 3600 -> "há ${idadeS / 60}min"
        else -> "há ${idadeS / 3600}h"
    }

    private fun traduzir(m: MarcadorDePar): ParNoMapa {
        val frescor = when {
            PoliticaDePosicao.marcadorMuitoVelho(m.idadeS) -> Frescor.ANTIGO
            PoliticaDePosicao.marcadorObsoleto(m.idadeS) -> Frescor.ESMAECIDO
            else -> Frescor.ATUAL
        }
        return ParNoMapa(
            indicativo = m.posicao.indicativo,
            distanciaFalada = com.claryon.agent.FalaDePosicao.distanciaFalada(m.distanciaM),
            rumoFalado = com.claryon.agent.Rumo.deGraus(m.rumoGraus)?.falado.orEmpty(),
            frescor = frescor,
            // Só em ANTIGO. Abaixo disso, a idade na tela seria ruído: o
            // esmaecimento já comunica "não confie tanto".
            idadeFalada = if (frescor == Frescor.ANTIGO) idade(m.idadeS) else null,
            atualizadoHa = carimbo(m.idadeS),
            distanciaM = m.distanciaM,
            rumoGraus = m.rumoGraus,
            // Movimento afirmado só enquanto a posição é atual. "Deslocando" a
            // partir de um dado de dez minutos é uma afirmação sobre o presente
            // feita com informação do passado.
            emMovimento = m.emMovimento && frescor == Frescor.ATUAL,
        )
    }

    private fun idade(segundos: Int): String {
        val m = segundos / 60
        return when {
            m < 60 -> "posição de $m minutos"
            m < 120 -> "posição de mais de uma hora"
            else -> "posição de ${m / 60} horas"
        }
    }
}
