package com.claryon.agent

import com.claryon.common.LaconicityPolicy

/**
 * Posição de um par, **como grandezas** — nunca coordenadas.
 *
 * O servidor resolve e devolve distância, rumo e estado; o aparelho de um agente
 * jamais recebe a posição bruta de outro. Isso é requisito de privacidade antes
 * de ser de arquitetura: mesmo um cliente comprometido não tem o que vazar.
 *
 * @param idadeS há quantos segundos a posição foi atualizada. **Posição velha
 *   apresentada como atual é pior que nenhuma posição** — o agente decidiria a
 *   abordagem contando com apoio que já saiu dali.
 */
data class PosicaoRelativa(
    val indicativo: String,
    val distanciaM: Int,
    /** `null` quando não há rumo definido — os dois pontos coincidem. */
    val rumo: Rumo?,
    val emMovimento: Boolean,
    val idadeS: Int,
)

/** Rumo cardinal. Falado, e não em graus: ninguém corre orientado por azimute. */
enum class Rumo(val falado: String) {
    NORTE("norte"), NORDESTE("nordeste"), LESTE("leste"), SUDESTE("sudeste"),
    SUL("sul"), SUDOESTE("sudoeste"), OESTE("oeste"), NOROESTE("noroeste");

    companion object {
        /**
         * Converte azimute em graus para o cardinal mais próximo. `null` se o
         * azimute não existe.
         *
         * `NaN` devolvia **norte** — `Math.round(NaN)` é 0 — e o agente ouvia um
         * rumo afirmado com total confiança a partir de um número inválido. E
         * `NaN` é justamente o que o PostGIS produz quando os dois pontos
         * coincidem: dupla na mesma viatura, a configuração mais comum do
         * policiamento.
         */
        fun deGraus(graus: Double): Rumo? {
            if (graus.isNaN() || graus.isInfinite()) return null
            val normalizado = ((graus % 360) + 360) % 360
            return entries[(Math.round(normalizado / 45.0).toInt()) % 8]
        }
    }
}

/**
 * **Como a posição vira fala — a "engenharia de resposta" do C2.**
 *
 * O que faz a resposta parecer inteligente não é o modelo, é o conteúdo:
 *
 * > *"Alfa Dois a mil e duzentos, nordeste, em deslocamento."*
 *
 * Três decisões de produto embutidas nessa frase:
 *
 *  1. **Distância arredondada para a precisão real do GPS.** Dizer "1.237 metros"
 *     sugere uma exatidão que o sinal não tem, e exatidão falsa em informação
 *     operacional é pior que aproximação honesta.
 *  2. **Rumo cardinal, não graus.** Ninguém se orienta por azimute correndo.
 *  3. **Estado de movimento**, derivado da velocidade: saber que o apoio está
 *     *vindo* muda a decisão tanto quanto saber onde ele está.
 *
 * E uma regra que o resto do produto já impõe: **≤ 7 palavras**, sem cortesia.
 */
object FalaDePosicao {

    /**
     * Frase para uma posição encontrada. Respeita a laconicidade.
     *
     * Acima de [IDADE_MAXIMA_S] a posição deixa de ser afirmada como atual — o
     * agente ouve há quanto tempo ela é, e decide. Silenciar a idade seria
     * apresentar dado velho como fresco.
     */
    fun para(p: PosicaoRelativa): String {
        if (p.idadeS > IDADE_MAXIMA_S) {
            return "${p.indicativo}, posição de ${minutos(p.idadeS)}."
        }

        // Par praticamente no mesmo ponto — dupla na mesma viatura. Falar
        // "a 0 metros, norte" seria ruído; e o rumo nem existe (o PostGIS devolve
        // nulo para pontos coincidentes).
        if (p.rumo == null || p.distanciaM < JUNTO_M) {
            return "${p.indicativo}, com você."
        }

        // Degradação por corte, do detalhe menos essencial para o mais.
        //
        // A frase completa cabe em 7 palavras com "Alfa Dois" e estoura com
        // "Alfa Dois Zero" — indicativo de três palavras que existe de verdade, e
        // que o roteador produz. Testar só com o indicativo curto fazia a regra
        // dura passar verde e quebrar em campo.
        val distancia = distanciaFalada(p.distanciaM)
        val candidatas = listOfNotNull(
            if (p.emMovimento) "${p.indicativo}, $distancia, ${p.rumo.falado}, deslocando." else null,
            "${p.indicativo}, $distancia, ${p.rumo.falado}.",
            // O último recurso guarda o rumo e larga a distância: saber para onde
            // correr orienta mais que saber quantos metros são.
            "${p.indicativo}, ${p.rumo.falado}.",
        )
        return candidatas.firstOrNull { LaconicityPolicy.isWithinLimit(it) } ?: candidatas.last()
    }

    /** Quando o par não foi encontrado: honestidade, nunca posição plausível. */
    fun naoEncontrado(indicativo: String): String = "$indicativo não localizado."

    /**
     * Arredonda para a precisão real do GPS: 50 m abaixo de 1 km, 100 m acima.
     * "Mil e duzentos" é o que um agente processa correndo; "1.237" não é.
     */
    fun distanciaFalada(metros: Int): String = when {
        metros < 100 -> "a ${arredondar(metros, 10)} metros"
        metros < 1_000 -> "a ${arredondar(metros, 50)} metros"
        else -> {
            val km = arredondar(metros, 100) / 1000.0
            if (km == Math.floor(km)) "a ${km.toInt()} quilômetros"
            else "a ${"%.1f".format(km).replace('.', ',')} quilômetros"
        }
    }

    private fun arredondar(valor: Int, passo: Int): Int =
        ((valor + passo / 2) / passo) * passo

    private fun minutos(segundos: Int): String {
        val m = segundos / 60
        return when {
            m < 1 -> "menos de um minuto"
            m == 1 -> "um minuto"
            m < 60 -> "$m minutos"
            else -> "mais de uma hora"
        }
    }

    /**
     * Acima disso, a posição é apresentada com a idade em vez de como atual.
     * Dois minutos é o mesmo limiar do esmaecimento do marcador no mapa — a
     * regra é uma só, dita de duas formas.
     */
    const val IDADE_MAXIMA_S = 120

    /**
     * Abaixo disso, "distância e rumo" não é informação — é ruído. É o erro
     * típico de um GPS de celular, e a dupla está na mesma viatura.
     */
    const val JUNTO_M = 25
}

/**
 * Resultado de procurar um par.
 *
 * Os três casos existem separados porque **"não localizado" e "não consegui
 * consultar" são fatos diferentes**, e colapsar os dois num `null` faz o app
 * dizer que o companheiro sumiu quando na verdade quem está fora do ar é a
 * consulta. Num pedido de apoio, a diferença entre "ele não aparece" e "não sei
 * dizer" muda o que o agente faz em seguida.
 */
sealed interface BuscaDePar {

    data class Encontrado(val posicao: PosicaoRelativa) : BuscaDePar

    /**
     * O sistema respondeu: o par não está localizável — fora do talk group, sem
     * posição recente, ou indicativo inexistente. As três causas colapsam de
     * propósito; distinguir "existe mas você não pode ver" de "não existe"
     * entregaria pela mensagem a informação que a política nega pelo dado.
     */
    data object NaoLocalizado : BuscaDePar

    /** A consulta não pôde ser feita — sem sessão, sem rede, servidor fora. */
    data object Indisponivel : BuscaDePar

    /**
     * O par foi encontrado, mas a **minha** posição publicada está velha demais
     * para a distância significar alguma coisa.
     *
     * O servidor mede a partir da última posição que eu publiquei. Sem rede há
     * meia hora, ela é de onde eu estava — e a resposta sairia afirmada como
     * atual, com erro de quilômetros e nada no payload denunciando. Este caso
     * existe para que a resposta seja "não sei de onde medir" em vez de um número
     * com cara de precisão.
     */
    data object PosicaoPropriaVelha : BuscaDePar
}
