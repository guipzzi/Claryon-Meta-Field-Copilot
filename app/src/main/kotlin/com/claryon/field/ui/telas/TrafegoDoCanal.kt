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
    /**
     * `"27 min sem tráfego"` quando o canal ficou calado o bastante para separar
     * dois momentos; `null` no caso comum, que é a conversa seguindo.
     *
     * Era `faixaHoraria` e valia `"14:00"` — ver [separadorDeLacuna] para a razão
     * de a régua ter mudado de hora cheia para lacuna. O nome mudou junto porque
     * campo que continua chamado `faixaHoraria` guardando outra coisa é a próxima
     * mentira silenciosa.
     */
    val separadorDeTempo: String?,
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
            // **DEPOIS do texto, e não antes.** É o oposto da procedência, que vem
            // antes de propósito — aquela desqualifica o autor, e quem ouve precisa
            // saber de quem é a frase ANTES de recebê-la. Esta qualifica o FIM da
            // frase, e anunciá-la antes faria o leitor de tela dizer "cortada" sobre
            // um texto que ele ainda não leu.
            //
            // O tracejado terminal é sinal visual e não sobrevive ao áudio — mesma
            // razão pela qual a lateralidade virou "Você" aqui.
            if (fala.cortadaPelaRede) append(". ").append(FALA_CORTADA_EM_VOZ)
            if (rotuloDeEntrega == RotuloDeEntrega.NAO_SAIU) append(". Não saiu")
        }
}

/**
 * **A marca escrita no rodapé do registro.**
 *
 * Função pura e fora do `@Composable` pela razão que o KDoc deste arquivo abre: o
 * projeto não declara `ui-test-junit4`, então decisão dentro de composable é decisão
 * sem teste. Era um `when` no corpo de `RodapeDoRegistro`, e ganhou um terceiro caso
 * — que é exatamente quando um `when` escondido para de ser óbvio.
 *
 * ---
 * ### Por que os três casos cabem num campo só
 *
 * "enviada" e "não saiu" falam do **envio deste aparelho**; "cortada" fala da
 * **recepção**. Parecem duas famílias, e são — mas são mutuamente exclusivas por
 * construção, não por sorte: [ItemDeTrafego.rotuloDeEntrega] é `null` em toda fala
 * recebida (*"dizer 'enviada' sob a fala de outro agente afirmaria algo sobre um
 * aparelho que não é este"*), e `FalaNoGrupo.cortadaPelaRede` só é `true` em fala
 * recebida — `EventoRecepcao.Terminou` não existe para transmissão própria, e o
 * truncamento da própria é `Entrega.NAO_SAIU`, outro fenômeno com rótulo próprio.
 *
 * Ou seja: o rodapé da fala recebida tinha um slot **permanentemente vazio**, e o
 * fato que faltava na tela é justamente o que só acontece nela. Não há colisão a
 * arbitrar, e por isso não há precedência escrita aqui — se um dia houver, ela vira
 * decisão explícita em vez de ordem de `when`.
 */
fun marcaDoRodape(item: ItemDeTrafego): String? = when {
    // Minúscula, como as outras duas: o rodapé é metadado e metadado não grita.
    // A caixa-alta desta tela é reservada ao genuinamente excepcional — ver
    // `RodapeDoRegistro`, que perdeu o `ENVIADA` em versal por esse motivo.
    item.fala.cortadaPelaRede -> "cortada"
    item.rotuloDeEntrega == RotuloDeEntrega.ENVIADA -> "enviada"
    item.rotuloDeEntrega == RotuloDeEntrega.NAO_SAIU -> "não saiu"
    else -> null
}

/**
 * O que o leitor de tela ouve no lugar do tracejado terminal.
 *
 * Frase e não palavra solta: "cortada" sozinha, depois da transcrição, poderia ser
 * lida como parte da fala do agente. O sujeito explícito separa o que o rádio diz do
 * que o aparelho diz — a mesma divisão que a tela faz em duas famílias tipográficas
 * e que o áudio não tem como reproduzir.
 */
const val FALA_CORTADA_EM_VOZ = "Fala cortada pela rede"

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
    var horaAnterior: String? = null
    var indicativoAnterior: String? = null
    var formaAnterior: FormaDoRegistro? = null

    return falas.map { fala ->
        val forma = when {
            fala.prioridade != null -> FormaDoRegistro.REGISTRO_DE_CANAL
            fala.propria -> FormaDoRegistro.PROPRIO
            else -> FormaDoRegistro.RECEBIDO
        }

        val separador = separadorDeLacuna(horaAnterior, fala.hora)
        // Guarda a hora **crua**, e só quando ela é legível: com `"--:--:--"` no
        // meio, medir a lacuna contra ele produziria um número inventado. Manter a
        // última hora conhecida faz a próxima medida atravessar o buraco, que é o
        // comportamento certo — a lacuna continua sendo tempo real de silêncio.
        if (fala.hora != HORA_DESCONHECIDA) horaAnterior = fala.hora

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

        // Começa turno de fala novo quando muda o autor, muda a forma, ou o canal
        // ficou calado. É o que separa dois turnos e adensa um só.
        val abre = separador != null ||
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
            separadorDeTempo = separador,
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
 * **O separador mede LACUNA, não hora cheia.**
 *
 * A régua anterior abria faixa a cada virada de hora (`14:00`, `15:00`), e a hora
 * cheia não significa nada num canal de guarnição: duas falas a 14:59 e 15:01 são
 * a mesma conversa e ganhavam um corte no meio, enquanto quarenta minutos de
 * silêncio dentro da mesma hora passavam sem marca nenhuma. O separador ficava
 * exatamente onde a informação não estava.
 *
 * O WhatsApp separa por **dia** — é o que o produto dele precisa, porque uma
 * conversa pessoal atravessa semanas. Aqui o dia é grosso demais: um turno inteiro
 * cabe num só, e um histórico de oito horas sairia sem uma única marca. O que a
 * régua do rádio tem de responder é a pergunta que o carimbo por linha não
 * responde — *quanto tempo se passou* —, e a resposta é a lacuna.
 *
 * ---
 * ### O limiar: 15 minutos, e o que sustenta o número
 *
 * **Não é medido, e isso está escrito de propósito.** Não existe tráfego real de
 * guarnição neste projeto para medir; o que existe é o roteiro sintético da
 * vitrine. O número é escolhido por dois limites do uso, e é falseável:
 *
 *  - **Piso — o deslocamento.** Uma guarnição despachada leva minutos até o local
 *    e reporta na chegada. "Em deslocamento" e "no local" são a MESMA ocorrência
 *    separadas por vários minutos de estrada; um limiar de 5 min cortaria a
 *    ocorrência ao meio, que é o defeito que este conserto veio remover.
 *  - **Teto — o turno.** A 15 min, um turno de 6 h tem no máximo 24 separadores,
 *    e na prática muito menos. A 60 min teria no máximo 6, e um turno com seis
 *    marcas volta a ser o problema da hora cheia: régua que não acompanha o
 *    conteúdo.
 *
 * **A medição que resolve isto** é uma só, e é uma linha de SQL: a distribuição
 * dos intervalos entre transmissões consecutivas de um mesmo `talk_group_id` em
 * `transmissions.criada_em`, por um turno real. Espera-se duas modas — dentro da
 * ocorrência e entre ocorrências —; o limiar é o vale entre elas. Enquanto não
 * houver canal real gravado, [LACUNA_QUE_SEPARA_S] é uma hipótese com o porquê ao
 * lado, e não um número achado.
 *
 * ---
 * ### O que a função recusa fazer
 *
 *  - **Hora desconhecida não vira lacuna.** `"--:--:--"` é o fallback do
 *    `RadioViewModel`; medir contra ele daria um número inventado.
 *  - **A virada da meia-noite é somada, não negada.** Turno da madrugada existe, e
 *    23:58 → 00:03 são 5 minutos, não menos 23 horas.
 *  - **Acima de [LACUNA_ABSURDA_S] devolve `null`.** `RadioViewModel` acrescenta
 *    as falas locais pendentes **no fim** da lista (`+ locaisPendentes`), então um
 *    balão local antigo pode cair depois de um do servidor mais novo. Com a soma
 *    da meia-noite, essa inversão viraria *"23 h sem tráfego"* — a interface
 *    afirmando um silêncio que não houve. Dentro de um único carregamento de
 *    histórico, lacuna dessa ordem é desordem, não silêncio, e o honesto é não
 *    desenhar marca nenhuma.
 *
 * @param anterior carimbo `HH:mm:ss` da fala de cima, ou `null` no primeiro item.
 * @return o rótulo pronto, ou `null` quando não há lacuna a declarar.
 */
fun separadorDeLacuna(anterior: String?, atual: String): String? {
    // Primeiro item da lista NUNCA abre separador, e a recusa é a mesma dos
    // outros: antes dele não há intervalo medido. A lista é uma janela sobre o
    // canal, não o começo dele — marcar o topo afirmaria que o histórico começa
    // ali.
    val de = segundosDoDia(anterior ?: return null) ?: return null
    val para = segundosDoDia(atual) ?: return null

    val bruta = para - de
    // Meia-noite: o turno da madrugada atravessa o zero, e o carimbo não carrega
    // data. Somar o dia é a única leitura possível — e é a certa enquanto a
    // lista couber em menos de 24 h, que é o caso de um histórico de 50 falas.
    val lacuna = if (bruta < 0) bruta + DIA_EM_SEGUNDOS else bruta

    if (lacuna < LACUNA_QUE_SEPARA_S || lacuna >= LACUNA_ABSURDA_S) return null
    return "${duracaoLegivel(lacuna)} sem tráfego"
}

/** `HH:mm:ss` em segundos desde a meia-noite. `null` quando não é hora. */
private fun segundosDoDia(hora: String): Int? {
    if (hora == HORA_DESCONHECIDA) return null
    val partes = hora.split(':')
    if (partes.size < 2) return null
    val h = partes[0].toIntOrNull() ?: return null
    val m = partes[1].toIntOrNull() ?: return null
    val s = partes.getOrNull(2)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

/**
 * `"27 min"`, `"1 h"`, `"2 h 40 min"`.
 *
 * Trunca para baixo em minutos: o separador afirma o silêncio que **houve**, e
 * arredondar para cima o faria afirmar silêncio que não houve. É a mesma direção
 * de erro que a idade da posição escolheu em `0020` — errar para o lado que não
 * inventa.
 */
private fun duracaoLegivel(segundos: Int): String {
    val minutos = segundos / 60
    if (minutos < 60) return "$minutos min"
    val horas = minutos / 60
    val resto = minutos % 60
    return if (resto == 0) "$horas h" else "$horas h $resto min"
}

/**
 * **15 minutos.** O silêncio que, num canal de guarnição, significa outro momento.
 *
 * O porquê — e a medição que o substituiria por um número achado — está no KDoc de
 * [separadorDeLacuna]. Público porque é o teste que trava o valor: mudá-lo sem
 * mexer no teste não compila o critério embutido lá.
 */
const val LACUNA_QUE_SEPARA_S = 15 * 60

/**
 * Acima disto a lacuna é tratada como desordem da lista, não como silêncio.
 *
 * 12 h: metade do dia. Um histórico carregado de uma vez não atravessa isso; o que
 * atravessa é a soma da meia-noite aplicada a duas falas fora de ordem.
 */
const val LACUNA_ABSURDA_S = 12 * 3600

private const val DIA_EM_SEGUNDOS = 24 * 3600

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
