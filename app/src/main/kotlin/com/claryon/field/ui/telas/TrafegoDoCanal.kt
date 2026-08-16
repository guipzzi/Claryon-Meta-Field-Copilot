package com.claryon.field.ui.telas

/**
 * **Decisões do histórico da guarnição — sem uma linha de Compose.**
 *
 * O arquivo não importa Compose de propósito, e a razão é verificável: o projeto
 * não declara `androidx.compose.ui:ui-test-junit4` (`app/build.gradle.kts`), então
 * qualquer decisão que more dentro de um `@Composable` fica sem teste. Aqui tudo é
 * função pura sobre tipos próprios, e o composable só traduz token em pixel.
 *
 * Os tipos de retorno são enum e não `Color`/`Dp`. `Color` é value class sobre
 * `ULong` e passaria num teste de JVM — mas amarrar o núcleo ao artefato de UI é
 * exatamente como ele volta a exigir aparelho no próximo refactor.
 */

/** Como o registro ocupa a linha. */
enum class FormaDoRegistro {
    /** Fala de um par. Bloco à esquerda. */
    RECEBIDO,

    /** Fala do próprio portador. Bloco à direita. */
    PROPRIO,

    /**
     * Alerta classificado. Linha inteira, sem lado.
     *
     * É o que impede a tela de virar bate-papo: alerta não é mensagem de alguém, é
     * registro do canal — e um terminal de despacho o mostra em largura cheia.
     */
    REGISTRO_DE_CANAL,
}

/** Faixa vertical à borda do bloco. Token, não cor. */
enum class TokenDeCalha { TRACO, TRACO_FORTE, P1, P2, P3 }

/** Peso da tinta do corpo. Token, não cor. */
enum class TokenDeTinta { TINTA, TINTA_MEDIA, TINTA_FRACA }

/** O que se escreve sob a fala própria. Recebida não tem. */
enum class RotuloDeEntrega { ENVIADA, NA_FILA }

/** Um registro pronto para desenhar. */
data class ItemDeTrafego(
    val fala: FalaNoGrupo,
    val forma: FormaDoRegistro,
    val calha: TokenDeCalha,
    val tintaDoTexto: TokenDeTinta,
    /** `"14:00"` quando abre faixa nova; `null` quando continua na mesma. */
    val faixaHoraria: String?,
    val mostraIndicativo: Boolean,
    val rotuloDeEntrega: RotuloDeEntrega?,
) {
    /**
     * O que o leitor de tela anuncia, como **um** nó.
     *
     * A lateralidade é visual e não sobrevive ao áudio: quem ouve a tela não sabe
     * de que lado o bloco está. Por isso a fala própria se anuncia como "Você" —
     * é a única forma de a informação que o alinhamento carrega chegar a quem não
     * vê o alinhamento.
     */
    val leituraEmVoz: String
        get() = buildString {
            fala.prioridade?.let { append(rotuloDePrioridade(it)).append(". ") }
            append(if (fala.propria) "Você" else fala.indicativo)
            if (fala.hora != HORA_DESCONHECIDA) append(", ").append(fala.hora)
            append(". ").append(fala.texto)
            if (rotuloDeEntrega == RotuloDeEntrega.NA_FILA) append(". Ainda na fila")
        }
}

/** `"P1 emergência"`. Escrito, e não só colorido — ver [montarTrafego]. */
fun rotuloDePrioridade(prioridade: Int): String = when (prioridade) {
    1 -> "P1 emergência"
    2 -> "P2 apoio"
    else -> "P3 informativo"
}

/**
 * Traduz a lista crua em registros desenháveis.
 *
 * **A inversão em relação ao WhatsApp é deliberada e é o coração desta tela.** Lá,
 * a fala própria é a realçada; aqui ela é a **rebaixada**. O modelo de uso é outro:
 * quem rola este histórico está procurando *o que perdeu*, não relendo o que disse.
 * O agente já sabe o que falou — ele estava lá. Realçar a própria fala gastaria o
 * recurso mais escasso da tela, que é contraste, no conteúdo de menor valor.
 *
 * O que faz um balão parecer aplicativo social são três coisas separáveis: canto
 * arredondado, rabinho e o "eu" pintado de cor viva. Nenhuma das três é necessária
 * para lateralidade — e nenhuma das três aparece aqui. O lado vem da geometria
 * (alinhamento e margem reservada), não da decoração.
 */
fun montarTrafego(falas: List<FalaNoGrupo>): List<ItemDeTrafego> {
    var faixaAnterior: String? = null
    var indicativoAnterior: String? = null
    var formaAnterior: FormaDoRegistro? = null

    return falas.map { fala ->
        val forma = when {
            fala.prioridade != null -> FormaDoRegistro.REGISTRO_DE_CANAL
            fala.propria -> FormaDoRegistro.PROPRIO
            else -> FormaDoRegistro.RECEBIDO
        }

        val faixa = faixaDe(fala.hora)
        val abreFaixa = faixa != null && faixa != faixaAnterior
        if (faixa != null) faixaAnterior = faixa

        // Indicativo some em sequência do mesmo autor — mas só entre falas
        // recebidas. Num REGISTRO_DE_CANAL ele fica sempre, porque a largura
        // inteira apaga a lateralidade justamente no registro mais importante:
        // sem o indicativo, um P1 próprio fica indistinguível de um P1 recebido.
        val mostra = forma == FormaDoRegistro.REGISTRO_DE_CANAL ||
            !(forma == FormaDoRegistro.RECEBIDO &&
                formaAnterior == FormaDoRegistro.RECEBIDO &&
                indicativoAnterior == fala.indicativo)

        indicativoAnterior = fala.indicativo
        formaAnterior = forma

        ItemDeTrafego(
            fala = fala,
            forma = forma,
            calha = when {
                fala.prioridade == 1 -> TokenDeCalha.P1
                fala.prioridade == 2 -> TokenDeCalha.P2
                fala.prioridade == 3 -> TokenDeCalha.P3
                forma == FormaDoRegistro.PROPRIO -> TokenDeCalha.TRACO
                else -> TokenDeCalha.TRACO_FORTE
            },
            // Própria em tinta média; recebida em tinta cheia. É a inversão do
            // parágrafo acima, aplicada onde ela se paga.
            tintaDoTexto = if (forma == FormaDoRegistro.PROPRIO) {
                TokenDeTinta.TINTA_MEDIA
            } else {
                TokenDeTinta.TINTA
            },
            faixaHoraria = faixa.takeIf { abreFaixa },
            mostraIndicativo = mostra,
            rotuloDeEntrega = when {
                // Recebida nunca tem rótulo: dizer "enviada" sob a fala de outro
                // agente afirmaria algo sobre um aparelho que não é este.
                !fala.propria -> null
                fala.entrega == FalaNoGrupo.Entrega.ENVIADA -> RotuloDeEntrega.ENVIADA
                fala.entrega == FalaNoGrupo.Entrega.ENFILEIRADA -> RotuloDeEntrega.NA_FILA
                else -> null
            },
        )
    }
}

/**
 * Faixa horária de um carimbo `HH:mm:ss`.
 *
 * `null` quando a hora é desconhecida. O `RadioViewModel` usa `"--:--:--"` como
 * fallback, e inventar faixa a partir dele seria a interface afirmando o que o dado
 * não sustenta — o mesmo erro que o esmaecimento do mapa existe para evitar.
 */
private fun faixaDe(hora: String): String? {
    if (hora == HORA_DESCONHECIDA || hora.length < 2) return null
    val h = hora.take(2)
    if (h.any { !it.isDigit() }) return null
    return "$h:00"
}

/**
 * Rolar para o fim, ou não.
 *
 * Rolar sempre arranca a leitura da mão de quem subiu para procurar algo — e quem
 * subiu no histórico do rádio está procurando exatamente o que perdeu. Rolar nunca
 * esconde o que acabou de chegar. A folga resolve os dois: perto do fim o agente
 * está acompanhando; longe dele, está lendo.
 */
fun deveRolarParaOFim(ultimoVisivel: Int, ultimoIndice: Int, folga: Int = FOLGA_DE_ROLAGEM): Boolean =
    ultimoIndice >= 0 && ultimoVisivel >= ultimoIndice - folga

const val HORA_DESCONHECIDA = "--:--:--"

/** Três posições. Menos que isso e um item alto sozinho já cancela o acompanhamento. */
const val FOLGA_DE_ROLAGEM = 3
