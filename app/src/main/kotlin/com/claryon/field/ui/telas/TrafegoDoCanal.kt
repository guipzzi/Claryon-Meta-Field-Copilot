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
enum class RotuloDeEntrega { ENVIADA, NAO_SAIU }

/**
 * **De quem a fala é, e se o servidor sustenta isso.**
 *
 * Ortogonal à prioridade de propósito: um P1 também pode chegar sem autoria
 * conferida, e é justamente esse o caso que mais importa. Por isso é campo
 * próprio e não mais um valor de [TokenDeCalha] — os dois se somam no mesmo
 * registro.
 *
 * O ataque real deste produto é **personificação**, não escuta. Quem forja um
 * anúncio escreve o indicativo que quiser; o que o servidor confirma é o vínculo
 * entre a transmissão e um agente do cadastro do grupo. Quando o vínculo não
 * fecha, a tela precisa dizer — exibir o rótulo que o próprio forjador digitou é
 * pior que não exibir nada, porque dá autoridade à mentira.
 */
enum class Procedencia { CONFIRMADA, NAO_CONFIRMADA }

/**
 * O que a tela escreve no lugar do indicativo quando a autoria não resolve.
 *
 * Frase e não vazio: espaço em branco no lugar do nome pareceria defeito de
 * renderização, e o agente precisa saber que a **origem** é que é duvidosa — não
 * a tela.
 *
 * Mora aqui, e não no `RadioTatico`, porque é decisão de exibição: quem escolhe
 * como um dado ausente aparece é a interface. O rádio importa esta constante em
 * vez de manter a gêmea que tinha — dois literais iguais em arquivos diferentes é
 * como o texto de um deles muda sozinho no próximo refactor.
 */
const val AUTOR_NAO_CONFIRMADO = "Origem não confirmada"

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
    val procedencia: Procedencia,
    /**
     * `true` quando este registro **começa** uma sequência — outro autor, outra
     * forma, ou abertura de faixa horária.
     *
     * É o que dá o respiro entre falas de pessoas diferentes e a densidade dentro
     * da fala de uma só. Proximidade é o único agrupador disponível numa lista
     * sem caixa: itens próximos leem como um turno de fala, itens afastados como
     * dois.
     */
    val abreSequencia: Boolean,
) {
    /**
     * O nome que vai no cabeçalho do bloco.
     *
     * Vazio nunca chega à tela: ou é o indicativo conferido, ou é a frase que diz
     * que ele não foi conferido.
     */
    val autorExibido: String
        get() = if (procedencia == Procedencia.NAO_CONFIRMADA) AUTOR_NAO_CONFIRMADO else fala.indicativo

    /**
     * O que o leitor de tela anuncia, como **um** nó.
     *
     * A lateralidade é visual e não sobrevive ao áudio: quem ouve a tela não sabe
     * de que lado o bloco está. Por isso a fala própria se anuncia como "Você" —
     * é a única forma de a informação que o alinhamento carrega chegar a quem não
     * vê o alinhamento.
     *
     * A procedência entra pelo mesmo motivo, e **antes do texto**: o tracejado da
     * calha e a faixa acima da fala são sinais visuais, e quem ouve a tela receberia
     * a frase de um desconhecido com a mesma autoridade da de um colega.
     */
    val leituraEmVoz: String
        get() = buildString {
            fala.prioridade?.let { append(rotuloDePrioridade(it)).append(". ") }
            append(if (fala.propria) "Você" else autorExibido)
            if (fala.hora != HORA_DESCONHECIDA) append(", ").append(fala.hora)
            append(". ").append(fala.texto)
            if (rotuloDeEntrega == RotuloDeEntrega.NAO_SAIU) append(". Não saiu")
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

        // **Autoria conferida, ou não.** O servidor devolve o indicativo por um
        // `join` com `agents` pela chave de autoria (`HistoricoDoCanal.falas`);
        // quando o vínculo não fecha — porque o autor não está no cadastro que o
        // RLS deixa este agente ver — o campo chega VAZIO. Vazio não é "sem nome",
        // é "não consegui atribuir", e a tela até aqui desenhava um cabeçalho em
        // branco: exatamente o "defeito de renderização" que o rádio já tinha
        // escrito querer evitar.
        //
        // Própria nunca é não confirmada: o balão local nasce neste aparelho, com
        // o indicativo desta sessão.
        val procedencia = if (!fala.propria && fala.indicativo.isBlank()) {
            Procedencia.NAO_CONFIRMADA
        } else {
            Procedencia.CONFIRMADA
        }

        // Indicativo some em sequência do mesmo autor — mas só entre falas
        // recebidas. Num REGISTRO_DE_CANAL ele fica sempre, porque a largura
        // inteira apaga a lateralidade justamente no registro mais importante:
        // sem o indicativo, um P1 próprio fica indistinguível de um P1 recebido.
        //
        // Na fala PRÓPRIA ele não aparece nunca, e a mudança é deliberada: quem
        // rola este histórico sabe o que disse, o lado já diz de quem é, e um
        // "VOCÊ" repetido em cada bloco gasta a linha do cabeçalho sem informar.
        // O leitor de tela continua ouvindo "Você" — ver `leituraEmVoz`, que é
        // onde a lateralidade sobrevive ao áudio.
        //
        // Origem não confirmada também não mostra — mas por outro motivo: **não
        // há indicativo para mostrar.** Quem diz de onde a fala veio é a faixa de
        // procedência, acima do texto, e escrever a mesma frase duas vezes no
        // mesmo bloco gasta duas linhas para informar uma. O que ela NÃO perde é
        // o agrupamento: ver `abreSequencia` logo abaixo.
        val mostra = when {
            forma == FormaDoRegistro.REGISTRO_DE_CANAL -> procedencia == Procedencia.CONFIRMADA
            forma == FormaDoRegistro.PROPRIO -> false
            procedencia == Procedencia.NAO_CONFIRMADA -> false
            else -> !(formaAnterior == FormaDoRegistro.RECEBIDO &&
                indicativoAnterior == fala.indicativo)
        }

        // Começa turno de fala novo quando muda o autor, muda a forma, ou abre
        // faixa horária. É o que separa dois turnos e adensa um só.
        val abre = abreFaixa ||
            formaAnterior == null ||
            formaAnterior != forma ||
            indicativoAnterior != fala.indicativo ||
            procedencia == Procedencia.NAO_CONFIRMADA

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
            procedencia = procedencia,
            abreSequencia = abre,
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
                fala.entrega == FalaNoGrupo.Entrega.NAO_SAIU -> RotuloDeEntrega.NAO_SAIU
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

/**
 * Quantos registros existem **abaixo** da leitura, agora.
 *
 * Medida, não estimativa: é a distância entre o último item visível e o fim da
 * lista. Não é contador de não-lidas, e a diferença importa — este aparelho não
 * sabe o que o agente já leu, e afirmar "3 novas" seria a interface inventando um
 * dado que não existe em lugar nenhum.
 *
 * Só é oferecido quando [deveRolarParaOFim] **recusa** acompanhar: enquanto a
 * lista se rola sozinha, um botão para ir ao fim seria um controle que não faz
 * nada. A recusa é o que cria a necessidade, e por isso as duas decisões moram
 * juntas.
 */
fun registrosAbaixoDaLeitura(
    ultimoVisivel: Int,
    ultimoIndice: Int,
    folga: Int = FOLGA_DE_ROLAGEM,
): Int {
    if (deveRolarParaOFim(ultimoVisivel, ultimoIndice, folga)) return 0
    return (ultimoIndice - ultimoVisivel).coerceAtLeast(0)
}

const val HORA_DESCONHECIDA = "--:--:--"

/** Três posições. Menos que isso e um item alto sozinho já cancela o acompanhamento. */
const val FOLGA_DE_ROLAGEM = 3
