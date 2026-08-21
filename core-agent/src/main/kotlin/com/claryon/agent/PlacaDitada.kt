package com.claryon.agent

import java.text.Normalizer

/**
 * **Placa ditada por voz → sete caracteres, ou uma recusa nomeada.**
 *
 * *"tango bravo unido três delta sete zero"* → `TBU3D70`.
 *
 * ## Por que isto não é trabalho de LLM
 *
 * A `specs/consulta-de-placa-por-camera.spec.md` propôs que o modelo normalizasse a
 * placa ditada, por ser "preenchimento de campo, não escolha de ação" — legítimo
 * pela regra dura do `CLAUDE.md` §2. Legítimo, mas desnecessário: o alfabeto
 * fonético é **tabela fixa de 26 entradas**. Um parser determinístico é exato onde o
 * modelo é provável, custa microssegundos onde o modelo custa segundos (medido em
 * 21/08: mediana de 4 680 ms para a redação), e não depende de decisão pendente
 * sobre qual modelo embarcar. Onde a tabela basta, a tabela ganha.
 *
 * ## As quatro regras que seguram o falso positivo, e o que cada uma provou
 *
 * O erro que mata este fluxo não é deixar de ler uma placa — é **inventar uma**. Um
 * agente que ouve "sem restrição" sobre um veículo que nunca foi consultado libera
 * um carro roubado. Por isso, do mais fraco ao mais forte:
 *
 * 1. **Âncora** — só se lê depois da palavra "placa". **Sem prova própria hoje:**
 *    ligada e desligada dá o mesmo número de falsos positivos, nas 84 elocuções e
 *    nos 1817 trechos de lei. Fica como defesa em profundidade, e o registro
 *    honesto disso está em `PlacaEmCorpusRealTest.aAncoraReduzASuperficieExaminada`.
 * 2. **Corrida contígua** — palavra que não é letra nem dígito **quebra** a corrida.
 *    "a placa do carro tá suja" não forma corrida nenhuma.
 * 3. **Sete exatos, e sem fatiar** — corrida de 6 ou 8 caracteres é descartada
 *    **inteira**. Não se procura uma placa boa dentro dela: é exatamente o defeito
 *    que o KDoc de [PlacaValidator.extrair] registra ter custado — "código
 *    ABC12345" produzindo `ABC1234`, e o app consultando placa que ninguém falou.
 * 4. **A gramática** ([PlacaValidator]) — e ela é quem trabalha. Sobre os 1817
 *    trechos de lei, o montador forma **93 corridas de exatamente sete caracteres**
 *    (`D120120`, `77AA77E`, `IIIVIIE`…) e a gramática reprova **as 93**. Sem ela
 *    seriam 93 consultas a veículos que ninguém pediu.
 *
 * Sete caracteres na ordem errada são **erro de leitura, não consulta** — e viram
 * [Motivo.FORA_DA_GRAMATICA], nunca uma consulta com dado fabricado.
 *
 * ## O que devolve, e por que não é `String?`
 *
 * `null` não distingue "não falou placa" de "falou e eu li errado". A primeira abre
 * o subfluxo da câmera; a segunda pede que o agente repita. Ver [Leitura].
 */
object PlacaDitada {

    /** Resultado da leitura. Nunca uma placa sem forma válida. */
    sealed interface Leitura {

        /** Sete caracteres com forma de placa brasileira. */
        data class Reconhecida(
            val placa: String,
            val formato: PlacaValidator.Formato,
        ) : Leitura

        /**
         * Não há placa a consultar, e o [motivo] diz o que o chamador deve fazer.
         *
         * [bruto] só existe quando alguma coisa foi montada e reprovada — serve para
         * o log e para pedir repetição citando o que se ouviu, nunca para consultar.
         */
        data class Recusada(val motivo: Motivo, val bruto: String? = null) : Leitura
    }

    enum class Motivo {
        /** Nada com cara de placa foi dito. É o caso que abre o subfluxo da câmera. */
        NADA_DITADO,

        /** Montou-se algo, e a forma não é de placa brasileira. Erro de leitura. */
        FORA_DA_GRAMATICA,

        /**
         * Duas corridas distintas produziram placas válidas.
         *
         * Escolher uma seria adivinhar qual veículo consultar. Recusar e pedir
         * repetição é a única saída que não inventa.
         */
        AMBIGUA,
    }

    /** Toda placa brasileira em uso tem sete caracteres — ver [PlacaValidator]. */
    private const val TAMANHO = 7

    private val ANCORA = setOf("placa", "placas")

    /**
     * Lê a placa ditada em [transcricao].
     *
     * Os dois parâmetros são **hipóteses ligáveis**, e não conveniência: cada um
     * existe para que o contra-teste possa desligá-lo e exigir que o resultado piore.
     * Hipótese que não se consegue desligar não se consegue medir.
     *
     * - [aproximar] — casamento por som ([ChaveFonetica]) para o que o whisper
     *   corrompeu ("tangu", "bravu"). Medido em `PlacaDitadaTest`.
     * - [ancorar] — só ler depois da palavra "placa". Medido no mesmo lugar.
     *
     * O padrão de ambos é o que produz o melhor par recall/precisão sobre o banco de
     * elocuções (`BancoDePlacasDitadasTest`). Mudar o padrão exige refazer a medida.
     */
    fun ler(transcricao: String, aproximar: Boolean = true, ancorar: Boolean = true): Leitura {
        val corridas = corridasDe(transcricao, aproximar, ancorar)
        if (corridas.isEmpty()) return Leitura.Recusada(Motivo.NADA_DITADO)

        // Sete exatos. Corrida de outro tamanho é descartada INTEIRA — ver KDoc.
        val seteCaracteres = corridas.filter { it.length == TAMANHO }
        if (seteCaracteres.isEmpty()) {
            return Leitura.Recusada(Motivo.NADA_DITADO, corridas.maxByOrNull { it.length })
        }

        val validas = seteCaracteres.mapNotNull { c -> PlacaValidator.formato(c)?.let { c to it } }
        return when (validas.map { it.first }.distinct().size) {
            0 -> Leitura.Recusada(Motivo.FORA_DA_GRAMATICA, seteCaracteres.first())
            1 -> Leitura.Reconhecida(validas.first().first, validas.first().second)
            else -> Leitura.Recusada(Motivo.AMBIGUA, validas.joinToString("/") { it.first })
        }
    }

    /**
     * As corridas **maximais** que a fala produziu, antes de qualquer validação.
     *
     * Existe visível ao teste por um motivo só: o contra-teste da gramática precisa
     * mostrar que a placa inventada **chegou** até a validação — que contiguidade,
     * âncora e contagem a aceitaram — e que foi a gramática, e nada mais, que a
     * barrou. Sem isto, um teste que só olha a recusa não distingue "a gramática
     * pegou" de "a corrida nem se formou".
     */
    internal fun corridasDe(
        transcricao: String,
        aproximar: Boolean = true,
        ancorar: Boolean = true,
    ): List<String> {
        val brutos = transcricao.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val normais = brutos.map(::normalizar)

        // Âncora: depois da ÚLTIMA palavra "placa". "consultar placa da placa X"
        // é fala improvável, mas se acontecer o que vale é a última menção.
        val inicio = if (ancorar) normais.indexOfLast { it in ANCORA } + 1 else 0
        return corridas(
            brutos.subList(inicio, brutos.size),
            normais.subList(inicio, normais.size),
            aproximar,
        )
    }

    /**
     * Maximal, e não toda subsequência: procurar a melhor placa dentro de uma
     * corrida longa é fabricar dado. Se o agente disse oito caracteres, ele errou a
     * ditada e a resposta certa é pedir de novo.
     */
    private fun corridas(brutos: List<String>, normais: List<String>, aproximar: Boolean): List<String> {
        val saida = mutableListOf<String>()
        val atual = StringBuilder()
        var multiplicador = 1
        var i = 0
        while (i < normais.size) {
            // **Repetidores, antes de tudo.** "duplo zero" são dois caracteres e
            // "quarto dobrado" também — ver [NumeroFalado]. Vêm antes do casamento
            // por som justamente para que "triplo" não tenha chance de virar letra.
            val antes = NumeroFalado.repetidorAntes(normais[i])
            if (antes != null) {
                multiplicador = antes
                i++
                continue
            }
            if (NumeroFalado.repeteAnterior(normais[i]) && atual.isNotEmpty()) {
                atual.append(atual.last())
                i++
                continue
            }

            // **Quantidade por extenso PRIMEIRO**, e a ordem tem motivo: sem ela o
            // casamento por som poderia levar "vinte" para alguma palavra do
            // alfabeto e comer o número. `grupo` só abre em palavra que não pode
            // ser dígito, então dígito solto nunca cai aqui.
            val grupo = NumeroFalado.grupo(normais, i)
            if (grupo != null) {
                atual.append(grupo.digitos)
                multiplicador = 1
                i = grupo.proximo
                continue
            }
            val simbolo = simbolo(brutos[i], normais[i], aproximar)
            if (simbolo != null) {
                repeat(if (simbolo.length == 1) multiplicador else 1) { atual.append(simbolo) }
                multiplicador = 1
                i++
                continue
            }
            if (atual.isNotEmpty()) saida += atual.toString()
            atual.clear()
            multiplicador = 1
            i++
        }
        if (atual.isNotEmpty()) saida += atual.toString()
        return saida
    }

    /**
     * A palavra vira caractere(s), ou `null` se ela quebra a corrida.
     *
     * A ordem importa. **Dígito solto antes de quantidade**: "sete zero" são dois
     * caracteres, nunca setenta — ver [NumeroFalado].
     */
    private fun simbolo(bruto: String, normal: String, aproximar: Boolean): String? {
        NumeroFalado.digitoSolto(normal)?.let { return it.toString() }

        // Já veio em dígitos do whisper: "3", "70".
        if (normal.isNotEmpty() && normal.all { it.isDigit() }) return normal

        AlfabetoFonetico.letra(normal)?.let { return it.toString() }

        // **A CAIXA ALTA é evidência, e é a única que separa "ABC" de "do".**
        //
        // O whisper escreve placa em caixa alta ("placa ABC 1D23") e escreve
        // português corrente em caixa baixa. Sem esta regra, aceitar qualquer
        // palavra como sequência de letras faria "a placa do carro" montar
        // `DOCARRO`; com ela, "do" não é candidato e "ABC" é.
        //
        // **E caixa alta sozinha não basta — o formato tem de caber numa placa.**
        // A varredura dos 1817 trechos de lei achou `AII1212` em *"para a categoria
        // A; II - 12 (doze) anos"*: o algarismo romano `II` da enumeração entrou
        // como duas letras. Sigla de duas letras não é fragmento de placa em
        // nenhuma das duas gramáticas — o grupo de letras tem exatamente três, e o
        // do meio, no Mercosul, tem uma. Então token só-letras vale se tiver **três**;
        // token misto (`1D23`) vale porque letra colada a algarismo já é placa.
        if (bruto == bruto.uppercase() && bruto.none { it.code > 127 } &&
            bruto.all { it.isLetterOrDigit() } && bruto.any { it.isLetter() }
        ) {
            val misto = bruto.any { it.isDigit() }
            if ((misto && bruto.length >= 2) || (!misto && bruto.length == 3)) return bruto
        }

        if (aproximar) AlfabetoFonetico.letraAproximada(normal)?.let { return it.toString() }
        return null
    }

    /** Minúsculas, sem acento — a mesma régua do resto do roteador. */
    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
