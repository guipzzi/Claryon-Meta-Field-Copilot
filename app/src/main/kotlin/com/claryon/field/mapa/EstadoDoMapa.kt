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
)

/** O que a tela mostra num instante. */
data class EstadoDoMapa(
    val pares: List<ParNoMapa>,
    val assinado: Boolean,
    val temPosicaoPropria: Boolean,
    val motivoIndisponivel: String?,
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
    }
}

/**
 * Traduz marcadores crus em algo desenhável.
 *
 * Puro de propósito: a regra que importa — **quando parar de afirmar que a
 * posição é atual** — fica testável sem tela, sem rede e sem GPS.
 */
object MapaDePares {

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
     */
    fun montarDeGrandezas(
        posicoes: List<com.claryon.net.RespostaDePosicao>,
        assinado: Boolean,
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
            )
        },
        assinado = assinado,
        temPosicaoPropria = true,
        motivoIndisponivel = null,
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
