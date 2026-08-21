package com.claryon.field.ui.telas

/**
 * **Decisões da guarnição-como-grupo — sem uma linha de Compose.**
 *
 * Mesmo motivo de `TrafegoDoCanal.kt`, e o motivo é verificável: o projeto não
 * declara `androidx.compose.ui:ui-test-junit4` (`app/build.gradle.kts`), então
 * decisão que mora dentro de um `@Composable` fica sem teste. Aqui tudo é função
 * pura sobre tipos próprios; o composable só traduz token em pixel.
 *
 * O que este arquivo carrega, e que não é detalhe de desenho: **o vocabulário.**
 * Três rótulos deste produto já foram removidos por afirmarem o que não existe —
 * "na fila" sem fila, "visto por último" sem produtor, "N no canal" sem presença.
 * As frases desta tela nascem aqui, num arquivo que um teste consegue varrer, e
 * é isso que faz `nenhumRotuloAfirmaPresenca` ser um teste e não uma intenção.
 *
 * Ver `specs/guarnicao-como-grupo.spec.md`.
 */

/**
 * Um membro da guarnição — **do cadastro**, não da lista de posições.
 *
 * ### A diferença é o achado desta entrega
 *
 * Até aqui a tela recebia `ParPresente`, derivado de `posicoes_do_grupo`, que faz
 * `join` com `agent_positions`: **quem nunca publicou posição não aparecia — nem
 * como ausente**. A contagem do cabeçalho não era a guarnição; era quem tem
 * posição, e o denominador mentia por omissão.
 *
 * `cadastro_do_grupo` (migração `0013`) devolve a `memberships` inteira, e o
 * cliente **já a busca a cada dez segundos** — `RadioViewModel.carregarCanal` a
 * colapsava num `Map` de autoria e a tela nunca a via. O que faltava não era
 * dado nem rede: era caminho.
 *
 * Então: **o cadastro dá o denominador, a posição dá a idade.**
 *
 * @param idadeDaPosicaoS segundos desde o **upload** da última posição, ou `null`
 *   quando o membro não tem posição publicada. `null` significa "nunca publicou",
 *   nunca "saiu" — ver [idadeLegivel].
 * @param proprio `true` para o portador deste aparelho. Ele é membro da guarnição
 *   como qualquer outro, e omiti-lo faria a contagem discordar do cadastro.
 * @param falando `true` enquanto detém o piso. Vem do rádio em milissegundos, não
 *   da recarga de dez segundos.
 */
data class MembroDaGuarnicao(
    val indicativo: String,
    val idadeDaPosicaoS: Int?,
    val proprio: Boolean = false,
    val falando: Boolean = false,
) {
    val temPosicao: Boolean get() = idadeDaPosicaoS != null && idadeDaPosicaoS >= 0
}

/**
 * O cadastro do grupo como a tela o recebe.
 *
 * [cadastroCarregado] é campo e não `membros.isEmpty()` porque os dois estados
 * pedem frases diferentes: "ninguém no cadastro" é uma afirmação sobre a
 * guarnição; "não carregou" é uma afirmação sobre esta chamada. `CanaisDoAgente`
 * já paga esse mesmo preço com `lexico: List<GrupoFalado>?` — vazio ≠ nulo — e
 * pela mesma razão.
 */
data class Guarnicao(
    val membros: List<MembroDaGuarnicao> = emptyList(),
    val cadastroCarregado: Boolean = false,
)

/**
 * O canal em que estamos, como a tela o recebe.
 *
 * [confirmadoPeloServidor] existe desde a `0011`, em
 * `CanaisDoAgente.canalConfirmadoPeloServidor`, com esta finalidade escrita —
 * *"quem exibe estado precisa poder dizer isso em vez de mostrar um nome de
 * guarnição como se fosse fato confirmado"* — e **zero chamadores**. Este é o
 * chamador.
 */
data class CanalCorrente(
    val id: String,
    val nome: String,
    val confirmadoPeloServidor: Boolean,
)

/**
 * Uma guarnição na lista de entrada.
 *
 * [rotuloFalado] não é enfeite e não é só exibição: é a **chave da troca**. Entrar
 * por toque usa `CanaisDoAgente.trocar(rotuloFalado)`, o mesmo caminho do comando
 * falado, para não existir uma segunda porta até o socket. Ver o critério 22 da
 * spec.
 */
data class GuarnicaoNaLista(
    val id: String,
    val nome: String,
    val rotuloFalado: String,
    val corrente: Boolean,
)

/** Por que a troca não aconteceu. Cada causa pede um gesto diferente do agente. */
enum class RecusaDaTroca {
    /** Piso próprio no ar. `PoliticaDeTrocaDeGrupo` recusa antes de resolver o rótulo. */
    TRANSMITINDO,

    /** Não há `RadioTatico` registrado — o rádio está fechado. */
    RADIO_FECHADO,

    /** O léxico não carregou. Lista ausente ≠ lista vazia. */
    SEM_LEXICO,

    /** O rótulo não casou. Não deveria acontecer por toque; acontece por voz. */
    NAO_RECONHECIDO,
}

/** O desfecho de uma troca de guarnição. Nunca otimista: só existe depois do rádio. */
sealed interface ResultadoDaTroca {
    data class Entrou(val nome: String) : ResultadoDaTroca
    data class Recusada(val causa: RecusaDaTroca) : ResultadoDaTroca
}

/**
 * **O estado do controle de escuta.** Três valores, e o do meio é o que não pode
 * faltar.
 *
 * `FORA_POR_VONTADE` e `RECUSADO` são o **mesmo fato** — não estamos no canal — e
 * pedem leituras opostas. Sair é decisão do agente e não é erro; ser recusado é
 * o canal privado da `0012` respondendo `Unauthorized`, e exige ação. Um controle
 * que pintasse os dois de vermelho ensinaria o agente a ignorar o vermelho.
 *
 * **Não existe um quarto valor de "entrando".** É a mesma ausência que
 * `Movimento` tem por escrito: não há `PisoPendente` porque este projeto já
 * publicou 168 quadros para um canal em que não tinha entrado, com o indicador
 * aceso. Entre o toque e a resposta do rádio, o estado continua sendo o que o
 * rádio reporta — e o que ele reporta é que ainda não estamos no canal.
 */
enum class TokenDeEscuta { NA_ESCUTA, FORA_POR_VONTADE, RECUSADO }

/**
 * O que o controle de escuta escreve.
 *
 * [detalhe] é `null` quando não há nada a acrescentar, e é o **motivo literal do
 * servidor** quando há — inclusive o `Unauthorized` inteiro. Encurtar a causa da
 * recusa foi o que fez este projeto perder um dia procurando rede quando o
 * problema era credencial.
 */
data class Escuta(val token: TokenDeEscuta, val rotulo: String, val detalhe: String?)

/**
 * **O rótulo do controle vem do RÁDIO; só o `FORA_POR_VONTADE` vem da intenção.**
 *
 * A ordem das guardas é a regra, não a implementação:
 *
 * 1. **Canal disponível ⇒ na escuta.** É fato reportado, e ganha de qualquer
 *    intenção — inclusive de uma intenção de sair que o `fechar()` ainda não
 *    aplicou.
 * 2. **Não pediu escuta ⇒ saiu por vontade.** Cinza, sem cor de falha, com a
 *    consequência escrita: sem canal não há PTT e o histórico para de recarregar.
 * 3. **Pediu e não tem ⇒ recusa**, com o motivo palavra por palavra.
 *
 * Invertida, a guarda 2 antes da 1, um toque em "sair" apagaria o rótulo antes de
 * o rádio fechar — a interface reportando o que foi pedido, que é exatamente a
 * regra que o `RadioViewModel` sustenta do outro lado.
 *
 * @param pediuEscuta intenção do agente, e nada além. Nunca vira "está no canal".
 * @param canalDisponivel `EstadoDoPtt` **não** é `Indisponivel`. Fato do rádio.
 * @param motivo o texto de `EstadoDoPtt.Indisponivel.motivo`, quando houver.
 */
fun escutaDoCanal(pediuEscuta: Boolean, canalDisponivel: Boolean, motivo: String?): Escuta = when {
    canalDisponivel -> Escuta(TokenDeEscuta.NA_ESCUTA, "Na escuta", null)

    !pediuEscuta -> Escuta(
        TokenDeEscuta.FORA_POR_VONTADE,
        "Fora",
        "Você saiu do canal. Sem push-to-talk, e o histórico para de recarregar.",
    )

    else -> Escuta(
        TokenDeEscuta.RECUSADO,
        "Sem canal",
        motivo ?: "O rádio não reportou motivo.",
    )
}

/**
 * **Idade da posição em linguagem de duração.**
 *
 * Três recusas, e as três são o mesmo princípio — não afirmar o que o dado não
 * sustenta:
 *
 *  - **`null` não vira `"há 0 s"`.** Membro sem posição é membro que nunca
 *    publicou, e "agora mesmo" seria a pior leitura possível: o agente concluiria
 *    que o colega acabou de reportar.
 *  - **Idade negativa também não vira número.** `0020` garante que o servidor não
 *    produz futuro (`now() - greatest(0, idade)`), mas a garantia é de lá; aqui um
 *    negativo é dado quebrado, e dado quebrado não vira frase confiante.
 *  - **Trunca para baixo.** A idade já é otimista pelo tempo de ida (0,4% de 120 s,
 *    medido em `0020`); arredondar para cima somaria erro na mesma direção — a que
 *    faz uma posição velha parecer nova.
 *
 * A palavra é **posição**, sempre. Nunca "visto", nunca "ativo": este produto não
 * guarda visto, e o que ele sabe é que um aparelho publicou coordenada.
 */
fun idadeLegivel(idadeS: Int?): String {
    if (idadeS == null || idadeS < 0) return "sem posição"
    if (idadeS < 60) return "há $idadeS s"
    val minutos = idadeS / 60
    if (minutos < 60) return "há $minutos min"
    val horas = minutos / 60
    val resto = minutos % 60
    return if (resto == 0) "há $horas h" else "há $horas h $resto min"
}

/**
 * **A ordem em que a informação é útil**, e ela é estável.
 *
 * Quem fala primeiro, depois quem tem posição (da mais nova para a mais velha),
 * depois quem não tem. O desempate por indicativo não é capricho: a lista
 * recarrega a cada dez segundos, e duas linhas com a mesma idade trocariam de
 * lugar sozinhas — um agente lendo a lista veria movimento sem que nada tivesse
 * mudado, que é a definição de mentira visual em `Movimento`.
 *
 * O próprio portador **não** sobe para o topo. Ele já se reconhece pelo marcador
 * na linha, e reordenar por "eu" reordenaria pela pessoa errada: quem lê esta
 * lista está procurando os outros.
 */
fun ordenarMembros(membros: List<MembroDaGuarnicao>): List<MembroDaGuarnicao> =
    membros.sortedWith(
        compareByDescending<MembroDaGuarnicao> { it.falando }
            .thenByDescending { it.temPosicao }
            .thenBy { it.idadeDaPosicaoS ?: Int.MAX_VALUE }
            .thenBy { it.indicativo },
    )

/**
 * **A marca do grupo — o nome desenhado, e não uma foto.**
 *
 * O pedido incluía foto do grupo. **Ela não existe, e a recusa é por ausência de
 * fonte, não por gosto:** `talk_groups` é `id, unit_id, nome, tipo`
 * (`0001:36-41`) mais `rotulo_falado` (`0011`), e um `grep` de
 * `foto|avatar|imagem|photo|descricao` nas 23 migrações devolve zero. Não há
 * bucket de Storage para grupo. Um espaço reservado para foto seria um retângulo
 * vazio pedindo para ser preenchido com invenção — e a invenção só apareceria em
 * produção.
 *
 * Iniciais resolvem o mesmo problema visual (uma âncora redonda que o olho pega
 * antes de ler o nome) dizendo a verdade: **é o nome, desenhado.** E é
 * determinística — o mesmo grupo tem sempre a mesma marca, o que é a metade do
 * trabalho que uma foto faria.
 *
 * Duas palavras dão uma inicial cada (`"GTA-3 Alfa"` → `"GA"`); uma palavra dá as
 * duas primeiras letras (`"Guarnicao"` → `"GU"`). Nome vazio dá `"?"`, que é o que
 * se sabe: nada.
 */
fun iniciaisDe(nome: String): String {
    val palavras = nome.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (palavras.isEmpty()) return "?"
    if (palavras.size == 1) {
        val p = palavras[0]
        return (if (p.length >= 2) p.take(2) else p).uppercase()
    }
    return (palavras[0].take(1) + palavras[1].take(1)).uppercase()
}

/**
 * **A contagem, com a palavra junto do número — e o denominador certo.**
 *
 * `"4 na guarnição · 2 com posição"`, nunca um `2/4` cru: fração crua o olho lê
 * como "dois dos quatro estão aí", e o que a segunda metade diz é outra coisa —
 * dois publicaram coordenada nos últimos dois minutos. Os outros dois podem estar
 * numa garagem sem GPS, ao lado do primeiro.
 *
 * **Quando o cadastro não chegou, a frase muda de forma.** `cadastro_do_grupo`
 * devolve conjunto vazio para chamada sem sessão e para grupo de que o chamador
 * não é membro; nesses casos só resta a lista de posições, que é **menor que a
 * guarnição e não sabe dizer quanto menor**. Apresentá-la como denominador seria
 * repetir, com dado melhor disponível, exatamente o defeito que esta entrega veio
 * consertar.
 */
fun resumoDaGuarnicao(membros: List<MembroDaGuarnicao>, cadastroCarregado: Boolean): String {
    val comPosicao = membros.count { it.temPosicao }
    if (!cadastroCarregado) {
        return if (membros.isEmpty()) {
            "Cadastro do grupo não carregou."
        } else {
            "$comPosicao com posição · cadastro não carregou"
        }
    }
    if (membros.isEmpty()) return "Ninguém no cadastro deste grupo."
    return "${membros.size} na guarnição · $comPosicao com posição"
}

/**
 * **Cada recusa pede um gesto diferente, então cada recusa tem frase própria.**
 *
 * Duas causas com a mesma frase mandam o agente fazer a coisa errada em metade dos
 * casos — é o mesmo raciocínio que separou as oito causas de câmera por
 * recuperação, e não por código do SDK. Aqui: soltar o botão, entrar no canal,
 * entrar de novo no aplicativo. Três gestos, três frases, e um teste que falha se
 * duas colidirem.
 *
 * As frases derivam de `FalhaOperacional.causaCurta`, que é o que o agente **ouve**
 * quando pede a mesma coisa por voz. Ler na tela algo diferente do que o produto
 * fala pela mesma causa é como duas verdades nascem.
 */
fun rotuloDaRecusa(causa: RecusaDaTroca): String = when (causa) {
    RecusaDaTroca.TRANSMITINDO -> "Você está no ar. Solte o botão e tente de novo."
    RecusaDaTroca.RADIO_FECHADO -> "O rádio está fechado. Entre na escuta primeiro."
    RecusaDaTroca.SEM_LEXICO -> "A lista de guarnições não carregou. Entre de novo."
    RecusaDaTroca.NAO_RECONHECIDO -> "Esta guarnição não está na sua lista."
}

/**
 * O que a lista de guarnições declara sobre si mesma.
 *
 * `meus_rotulos_falados()` (`0011`) filtra `rotulo_falado is not null`, e nulo ali
 * significa "não endereçável por voz" — **não** "não existe". Uma guarnição do
 * agente sem rótulo falado existe, funciona por toque no cadastro, e não aparece
 * nesta lista.
 *
 * Uma lista que se apresenta como completa sem ser é o mesmo defeito da contagem
 * de presença, noutro lugar da tela. Por isso a frase é fixa e mora aqui, onde um
 * teste a alcança.
 */
const val RECORTE_DA_LISTA: String =
    "Só aparecem as guarnições com rótulo falado no cadastro. Uma guarnição sua " +
        "sem rótulo existe e não está nesta lista."

/**
 * O que a página do grupo declara sobre a idade que ela mostra.
 *
 * Duas afirmações, as duas medidas: a idade é do **upload** (`0020` — cinco
 * funções liam `updated_at` como idade e quem reconectava após 4 min entrava como
 * `idade_s = 0`), e o erro é sempre para o lado otimista, pelo tempo de ida.
 */
const val O_QUE_A_IDADE_E: String =
    "A idade é a da última posição que o aparelho do par publicou, contada do " +
        "envio — então ela é sempre um pouco otimista. Não é «visto por último»: " +
        "este produto não guarda isso."

/**
 * O que a página do grupo declara sobre quem não tem idade.
 *
 * A frase existe porque a leitura errada é a perigosa: "sem posição" lido como
 * "sumiu" leva a decisão de deslocamento sobre um colega que está a dois
 * quarteirões, numa garagem.
 */
const val O_QUE_E_SEM_POSICAO: String =
    "«Sem posição» é membro do cadastro que nunca publicou coordenada, ou cuja " +
        "última publicação já venceu a retenção. Não é agente que saiu."
