package com.claryon.field.ui.telas

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claryon.field.ui.componentes.BarraDePtt
import com.claryon.field.ui.componentes.BotaoDeIrParaOFim
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.BlocoDeProcedencia
import com.claryon.field.ui.componentes.FimInterrompido
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.SetaParaDireita
import com.claryon.field.ui.componentes.TextoCorpo
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.componentes.calha
import com.claryon.field.ui.componentes.contornoTracejado
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Regua
import com.claryon.field.ui.tema.Tipo
import com.claryon.field.ui.tema.duracaoAcessivel
import kotlinx.coroutines.launch

/**
 * Uma fala no histórico do grupo.
 *
 * **Sobre transcrever:** a proibição do projeto é transcrever, classificar ou
 * indexar a fala de **terceiros** — o abordado, o transeunte, quem passa perto do
 * microfone. Isto aqui é outra coisa: é o tráfego de rádio da guarnição, falado
 * por agentes que apertaram um botão para transmitir a colegas, no exercício da
 * função. O histórico escrito é o que o rádio analógico nunca deu, e é o que
 * permite ao agente que estava com as mãos ocupadas ler depois o que perdeu.
 *
 * A distinção é de consentimento e de papel, não de tecnologia — e por isso
 * precisa estar escrita onde o dado é definido.
 *
 * **`indicativo` vazio não é "sem nome".** Vem do `join` de autoria em
 * `HistoricoDoCanal.falas`, e vazio significa que o vínculo entre a transmissão e
 * um agente do cadastro **não fechou**. Quem traduz isso para a tela é
 * [Procedencia]; ver `montarTrafego`.
 */
data class FalaNoGrupo(
    val id: String,
    val indicativo: String,
    val hora: String,
    val texto: String,
    val propria: Boolean,
    /** 1, 2 ou 3 quando a fala carrega alerta classificado; `null` quando é conversa. */
    val prioridade: Int?,
    val entrega: Entrega,
    /**
     * **A rede cortou esta fala no meio, e o final não chegou.**
     *
     * `true` só quando o receptor concluiu `FimDaFala.CORTADA_NO_MEIO`: o emissor
     * sumiu — túnel, bateria, app morto — sem que nada anunciasse o fim, e o
     * `core-net/Receptor.kt` desistiu depois de esperar a janela inteira de jitter.
     *
     * **Não é derivável de nenhum outro campo desta classe**, e é por isso que ela
     * existe. `texto` incompleto é indistinguível de texto completo curto; `entrega`
     * fala do envio deste aparelho e não da recepção; e `quadros perdidos` chega
     * **zero** no caso truncado, porque o receptor não sabe contar quadros que
     * nunca existiram (ver `Receptor.kt` e `CaosDeRecepcaoComNParesTest`). Quem
     * sabe é o motivo do fim, e ele só existe num evento que passava direto.
     *
     * Padrão `false`: fala do servidor chega pelo histórico, que não guarda o
     * motivo do fim, e afirmar corte sem o evento seria inventar. Uma fala truncada
     * que este aparelho não ouviu ao vivo aparece como inteira — é uma perda
     * conhecida, e a alternativa (marcar tudo por suspeita) é pior.
     */
    val cortadaPelaRede: Boolean = false,
) {
    /**
     * Entregue ≠ perdido. O agente precisa saber a diferença — e a diferença
     * precisa ser verdadeira.
     *
     * `NAO_SAIU` chamava-se `ENFILEIRADA` e a tela escrevia "na fila". **Não havia
     * fila.** `ArquivoDeFalasDiferidas` existe, tem 187 linhas e onze testes, e
     * **zero chamadores** — a fala transmitida sem rede simplesmente se perde. Um
     * rótulo dizendo "na fila" fazia o agente acreditar que a mensagem sairia
     * depois, e ele seguiria a ocorrência contando com um apoio que nunca foi
     * pedido. Renomear é o conserto honesto enquanto a fila não existe; quando ela
     * existir, o estado volta a se chamar enfileirada porque aí será verdade.
     *
     * Pelo mesmo motivo não existe "visto" aqui, e não vai existir enquanto não
     * houver quem produza o dado: `transmission_acks` é tabela, não capacidade
     * ligada. Duas marcas de leitura seriam a versão bonita da mesma mentira.
     */
    enum class Entrega { ENVIADA, NAO_SAIU, RECEBIDA }
}

/**
 * As três páginas que vivem sob o destino `GUARNICAO`.
 *
 * É a navegação de um mensageiro, e ela é **local a esta tela** de propósito: a
 * pilha some quando o agente troca de aba, porque nenhuma das três representa um
 * lugar em que ele "estava". `Destino` continua com três valores, e a barra
 * inferior continua sendo a única navegação de primeiro nível.
 *
 * **Sem transição.** `Movimento` diz que o padrão do sistema é `IMEDIATO` e que
 * não há transição de tela; trocar de página aqui é troca de conteúdo, não
 * movimento com informação. O que se move nesta tela é a linha viva do cabeçalho,
 * e ela se move porque o conteúdo dela muda sozinho.
 */
private enum class PaginaDaGuarnicao { CONVERSA, GRUPO, GUARNICOES }

/**
 * **Tela da guarnição — a conversa do canal.**
 *
 * A estrutura é a de um aplicativo de mensagens, e a escolha é sobre **custo de
 * aprendizado**: o agente já sabe ler uma conversa, e não deveria gastar atenção
 * decodificando um layout novo no meio de uma ocorrência. Três faixas, de cima
 * para baixo — quem é e quem está aí, o que foi dito, e por onde se fala.
 *
 * O que muda em relação a um mensageiro é o que este produto tem e ele não:
 *
 *  - **A transcrição nasce na origem** (P1). O texto viaja junto do áudio e é
 *    idêntico em todos os receptores. Não há "digitando", porque não se digita.
 *  - **A autoria pode não fechar.** O ataque deste produto é personificação, e
 *    fala não conferida não pode parecer fala conferida — ver [Procedencia].
 *  - **Prioridade interrompe.** Um P1 não é mais uma mensagem: sai da conversa e
 *    vira registro de canal, em largura inteira, como num terminal de despacho.
 *  - **Entrega é honesta e binária.** Saiu, ou não saiu. Sem visto, sem entregue.
 *
 * E o que **não** entra, apesar de a gramática do mensageiro pedir: citação de
 * resposta. Não existe relação de réplica no tráfego de rádio nem coluna que a
 * guarde; a fatia acima da fala carrega procedência, que é real.
 */
@Composable
fun TelaDeGuarnicao(
    /**
     * O canal **do servidor**, não uma constante.
     *
     * Era `canal: String`, e `MainActivity` passava `CanalDoPiloto.NOME` — enquanto
     * o `RadioViewModel` reconciliava o canal com o cadastro trinta linhas abaixo.
     * Um agente de outra lotação lia "GTA-3 Alfa" no topo estando noutra
     * guarnição. [CanalCorrente.confirmadoPeloServidor] é o que permite à página do
     * grupo dizer que o nome ainda é provisório em vez de exibi-lo como fato.
     */
    canal: CanalCorrente,
    /**
     * A guarnição: **cadastro completo**, com a idade de quem publicou posição.
     *
     * Era `pares: List<ParPresente>`, derivado de `posicoes_do_grupo` — que faz
     * `join` com `agent_positions` e por isso **omitia quem nunca publicou**. A
     * contagem do cabeçalho não era a guarnição; era quem tem posição. Ver
     * `RadioViewModel.guarnicao`.
     */
    guarnicao: Guarnicao,
    falas: List<FalaNoGrupo>,
    estadoDoPtt: EstadoDoPtt,
    /**
     * A **intenção** do agente sobre a escuta, e nada além.
     *
     * Nunca vira "está no canal": quem afirma isso é [estadoDoPtt]. Ela existe só
     * para separar dois desfechos do mesmo fato — *você saiu* e *o canal recusou* —
     * que pedem cores e gestos opostos. Ver [escutaDoCanal].
     */
    pediuEscuta: Boolean,
    aoPressionarPtt: () -> Unit,
    aoSoltarPtt: () -> Unit,
    /** Reabre o rádio. Não afirma nada: quem reporta é [estadoDoPtt]. */
    aoEntrarNaEscuta: () -> Unit,
    /** Fecha o rádio por vontade do agente. */
    aoSairDaEscuta: () -> Unit,
    /**
     * As guarnições em que o agente pode entrar. `null` = o léxico não carregou.
     *
     * Vazio e nulo são coisas diferentes, e a tela precisa das duas: lista vazia é
     * afirmação sobre a lotação, lista ausente é afirmação sobre esta chamada.
     */
    guarnicoes: List<GuarnicaoNaLista>?,
    /** Entra noutra guarnição. **Suspende até o rádio responder** — sem otimismo. */
    aoEntrarEm: suspend (String) -> ResultadoDaTroca,
    /**
     * Quem detém o piso **agora**, vindo do rádio.
     *
     * Existe separado de [guarnicao] porque há um valor que a lista de membros não
     * consegue representar: `AUTOR_NAO_CONFIRMADO`. A lista casa por indicativo, e
     * "Origem não confirmada" não casa com ninguém — então uma transmissão de
     * autoria duvidosa passava pelo alto-falante sem **nada** na tela, que é o pior
     * desfecho possível para o ataque que este produto de fato tem.
     *
     * `null` quando o canal está calado.
     */
    quemEstaNoAr: String? = null,
    modifier: Modifier = Modifier,
) {
    // A página é estado LOCAL desta tela, de propósito: nenhuma das três muda nada
    // no rádio, nenhuma sobrevive à troca de destino, e nenhuma precisa de algo que
    // o `MainActivity` já não passe. Estado que sobe sem motivo vira parâmetro que
    // alguém esquece de ligar — e capacidade sem chamador já aconteceu seis vezes
    // aqui.
    var pagina by remember { mutableStateOf(PaginaDaGuarnicao.CONVERSA) }
    BackHandler(enabled = pagina != PaginaDaGuarnicao.CONVERSA) {
        pagina = PaginaDaGuarnicao.CONVERSA
    }

    val escuta = escutaDoCanal(
        pediuEscuta = pediuEscuta,
        canalDisponivel = estadoDoPtt !is EstadoDoPtt.Indisponivel,
        motivo = (estadoDoPtt as? EstadoDoPtt.Indisponivel)?.motivo,
    )

    Box(modifier.fillMaxSize()) {
        // `background` própria e não herdada do casco: uma tela que depende do
        // hospedeiro para ter fundo mostra o cinza do tema do sistema no primeiro
        // lugar em que for composta fora dele — foi o que a vitrine de captura
        // revelou, e num painel escuro isso é a tela inteira queimando visão
        // noturna.
        Column(Modifier.fillMaxSize().background(Cores.Vazio)) {
            CabecalhoDaConversa(
                canal = canal,
                guarnicao = guarnicao,
                escuta = escuta,
                // Cai para a lista quando o rádio não informa: sem o parâmetro
                // ligado, a tela continua sabendo o que sabia antes, e não inventa
                // nada.
                noAr = quemEstaNoAr ?: guarnicao.membros.firstOrNull { it.falando }?.indicativo,
                aoVoltar = { pagina = PaginaDaGuarnicao.GUARNICOES },
                aoAbrirGrupo = { pagina = PaginaDaGuarnicao.GRUPO },
                aoAlternarEscuta = { if (escuta.token == TokenDeEscuta.NA_ESCUTA) aoSairDaEscuta() else aoEntrarNaEscuta() },
            )
            Fio()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (falas.isEmpty()) {
                    Vazio(
                        etiqueta = "Canal silencioso",
                        explicacao = "Nada foi transmitido neste turno. Segure o botão para falar.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    HistoricoDeFalas(falas)
                }
            }

            BarraDeComposicao(
                estadoDoPtt = estadoDoPtt,
                aoPressionarPtt = aoPressionarPtt,
                aoSoltarPtt = aoSoltarPtt,
            )
        }

        // **A passagem entre páginas, ligada em 22/08 a pedido do dono do produto.**
        //
        // As três páginas são uma PILHA, não um seletor, e sem movimento abrir e
        // fechar produziam o mesmo quadro: a página nova simplesmente substituía a
        // conversa. A direção é a informação — o grupo entra pela direita porque se
        // vai "para dentro" dele, a lista de guarnições entra pela esquerda porque
        // o gesto que a chama é o de **voltar**, e o polegar aprende o caminho de
        // volta sem ler nenhum rótulo.
        //
        // A barra inferior de destinos continua sem transição, e isso não é
        // esquecimento: ver o KDoc de `Movimento.PassagemDePagina`.
        AnimatedVisibility(
            visible = pagina == PaginaDaGuarnicao.GRUPO,
            enter = deslizarEntrando(DA_DIREITA),
            exit = deslizarSaindo(DA_DIREITA),
        ) {
            TelaDoGrupo(
                canal = canal,
                guarnicao = guarnicao,
                escuta = escuta,
                aoEntrarNaEscuta = aoEntrarNaEscuta,
                aoSairDaEscuta = aoSairDaEscuta,
                aoFechar = { pagina = PaginaDaGuarnicao.CONVERSA },
            )
        }

        AnimatedVisibility(
            visible = pagina == PaginaDaGuarnicao.GUARNICOES,
            enter = deslizarEntrando(DA_ESQUERDA),
            exit = deslizarSaindo(DA_ESQUERDA),
        ) {
            TelaDeGuarnicoes(
                guarnicoes = guarnicoes,
                aoEntrarEm = aoEntrarEm,
                aoFechar = { pagina = PaginaDaGuarnicao.CONVERSA },
            )
        }
    }
}

/** De onde a página entra. `+1` é a borda direita, `-1` a esquerda. */
private const val DA_DIREITA = 1

private const val DA_ESQUERDA = -1

/**
 * A página entra deslizando da própria borda, opaca desde o primeiro quadro.
 *
 * **Sem `fadeIn`**, e é decisão: estas páginas são superfícies opacas que cobrem a
 * conversa, e uma superfície translúcida em trânsito deixa o histórico do canal
 * legível por baixo dela por 240 ms. Num painel que o agente lê de relance, dois
 * textos sobrepostos por um quarto de segundo é pior que nenhum.
 */
private fun deslizarEntrando(lado: Int) = slideInHorizontally(
    animationSpec = Movimento.PassagemDePagina(),
    initialOffsetX = { largura -> lado * largura },
)

private fun deslizarSaindo(lado: Int) = slideOutHorizontally(
    animationSpec = Movimento.PassagemDePagina(),
    targetOffsetX = { largura -> lado * largura },
)

// ── Cabeçalho ────────────────────────────────────────────────────────────────

/**
 * **Cabeçalho da conversa — a forma do cabeçalho de grupo, com o dado deste
 * produto.**
 *
 * ```
 *  ‹    (GA)   GTA-3 Alfa                          [ Na escuta ]
 *              4 na guarnição · 2 com posição
 *  ①     ②      ③                                        ④
 * ```
 *
 * ① volta para a lista de guarnições · ② a marca do grupo · ③ nome e subtítulo,
 * que é a porta da página do grupo · ④ entrar e sair da escuta.
 *
 * ### O que este cabeçalho recusou desenhar
 *
 * **A foto do grupo.** O pedido a incluía. Ela **não existe**: `talk_groups` é
 * `id, unit_id, nome, tipo` (`0001:36-41`) mais `rotulo_falado` (`0011`), e um
 * `grep` de `foto|avatar|imagem|photo|descricao` nas 23 migrações devolve zero —
 * não há bucket de Storage para grupo. O que está no lugar dela é [MarcaDoGrupo]:
 * o **nome desenhado**, determinístico, que resolve o mesmo problema visual sem
 * afirmar dado que não temos.
 *
 * **A régua de indicativos.** Os nomes dos pares em rolagem horizontal, com dois
 * pontos verdes, ocupavam a segunda linha inteira — e a segunda linha agora divide
 * espaço com o controle de escuta. Eles descem para a página do grupo, a um toque,
 * onde cabem com a idade de cada um ao lado. É perda de relance, declarada.
 *
 * ### Três nós, três ações
 *
 * O cabeçalho era **um** nó tocável. Não pode mais ser: voltar, abrir o grupo e
 * alternar a escuta são três coisas com consequências diferentes, e a última muda
 * o estado do rádio. Um alvo único que fizesse a terceira por engano tiraria o
 * agente do canal no meio de uma ocorrência.
 *
 * ### A segunda linha
 *
 * Altura **fixa**, e é o ponto: ela troca de conteúdo sozinha. Um cabeçalho que
 * cresce quando alguém fala empurraria o histórico para baixo no exato instante em
 * que o agente está lendo.
 *
 * A precedência é: **recusa → quem está no ar → contagem**. A recusa vem primeiro
 * porque, sem canal, "quem está no ar" é afirmação sobre um canal que não estamos
 * ouvindo. `Crossfade` e não corte seco: o conteúdo existe dos dois lados da
 * transição, que é o que a curva de morfose diz — é remanejo do que já está na
 * tela, `MEDIO` com `Morfose`.
 */
@Composable
private fun CabecalhoDaConversa(
    canal: CanalCorrente,
    guarnicao: Guarnicao,
    escuta: Escuta,
    noAr: String?,
    aoVoltar: () -> Unit,
    aoAbrirGrupo: () -> Unit,
    aoAlternarEscuta: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Cores.Vazio)
            .padding(
                start = Espaco.Curto,
                end = Espaco.Curto,
                top = Espaco.Curto,
                bottom = Espaco.Medio,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotaoDeVoltar(aoVoltar)

            // A marca e o bloco de texto são UM alvo: são a mesma porta, e separar
            // criaria dois alvos de 48 dp encostados fazendo a mesma coisa.
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(CANTO_DO_BLOCO))
                    .tocavel(forma = RoundedCornerShape(CANTO_DO_BLOCO), aoTocar = aoAbrirGrupo)
                    .padding(horizontal = Espaco.Curto, vertical = Espaco.Micro)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Guarnição ${canal.nome}. " +
                            resumoDaGuarnicao(guarnicao.membros, guarnicao.cadastroCarregado) +
                            ". Abrir a página do grupo"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarcaDoGrupo(canal.nome, tamanho = MARCA_NO_CABECALHO)
                Box(Modifier.width(Espaco.Medio))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // **Sans e semibold, não mono.** O KDoc anterior defendia a
                        // mono dizendo que `GTA-3 Alfa` é dado. Ele não é: é o
                        // rótulo desta tela, lido uma vez por turno, e não é
                        // comparado caractere a caractere com nada. Indicativo
                        // continua em mono — esse sim se compara em coluna.
                        //
                        // **Isto quer ser `Tipo.NomeDoGrupo`**, e não é porque
                        // `ui/tema/` estava fora do território desta sessão. É o
                        // mesmo padrão de `CANTO_DO_BLOCO` e `PILULA`: o token
                        // derivado no ponto de uso, com o porquê ao lado.
                        Text(
                            canal.nome,
                            style = Tipo.Corpo.copy(fontWeight = FontWeight.SemiBold),
                            color = Cores.Tinta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Box(Modifier.width(Espaco.Curto))
                        // Desenhada, não glifo — o mesmo motivo de `SetaParaBaixo`.
                        // E é o que impede a porta de ser invisível: um bloco
                        // tocável sem affordance é um segredo, e o agente não
                        // descobre segredo no meio de uma ocorrência.
                        SetaParaDireita()
                    }
                    Box(Modifier.height(RESPIRO))
                    Box(Modifier.fillMaxWidth().height(ALTURA_DA_LINHA_VIVA)) {
                        Crossfade(
                            targetState = LinhaViva.de(escuta, noAr),
                            animationSpec = tween(
                                duracaoAcessivel(Movimento.MEDIO),
                                easing = Movimento.Morfose,
                            ),
                            label = "linha-viva",
                        ) { viva ->
                            when (viva) {
                                is LinhaViva.Aviso -> TextoCorpoMenor(
                                    viva.motivo,
                                    // Sair não é erro, e não pode sair na cor de
                                    // falha: um agente que vê vermelho por decisão
                                    // própria aprende a ignorar o vermelho.
                                    cor = if (viva.grave) Cores.Tinta else Cores.TintaFraca,
                                    maxLinhas = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                is LinhaViva.NoAr -> LinhaDeQuemFala(viva.quem)

                                LinhaViva.Contagem -> TextoCorpoMenor(
                                    resumoDaGuarnicao(guarnicao.membros, guarnicao.cadastroCarregado),
                                    cor = Cores.TintaFraca,
                                    maxLinhas = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.width(Espaco.Curto))
            ControleDeEscuta(escuta, aoAlternarEscuta)
        }
    }
}

/**
 * O que a segunda linha do cabeçalho mostra **agora**.
 *
 * Existe como tipo, e não como três `if` dentro do `Crossfade`, porque o
 * `Crossfade` precisa de um `targetState` que mude exatamente quando o conteúdo
 * muda. Com `noAr` sozinho como alvo, uma recusa que chegasse durante uma
 * transmissão trocaria o texto **sem** transição — e pior, a transição dispararia
 * depois, quando o piso caísse, animando uma mudança que já tinha acontecido.
 */
private sealed interface LinhaViva {
    /** [grave] separa a recusa do servidor da saída por vontade do agente. */
    data class Aviso(val motivo: String, val grave: Boolean) : LinhaViva
    data class NoAr(val quem: String) : LinhaViva
    data object Contagem : LinhaViva

    companion object {
        /**
         * A precedência: **fora do canal → no ar → contagem**.
         *
         * Estar fora ganha porque, sem canal, "quem está no ar" é afirmação sobre
         * um canal que não estamos ouvindo — e um piso concedido que ficou
         * pendurado na última carga continuaria desenhando "no ar" sobre um rádio
         * mudo.
         */
        fun de(escuta: Escuta, noAr: String?): LinhaViva {
            val detalhe = escuta.detalhe
            return when {
                escuta.token == TokenDeEscuta.RECUSADO && detalhe != null ->
                    Aviso(detalhe, grave = true)
                escuta.token == TokenDeEscuta.FORA_POR_VONTADE && detalhe != null ->
                    Aviso(detalhe, grave = false)
                noAr != null -> NoAr(noAr)
                else -> Contagem
            }
        }
    }
}

/**
 * **Voltar — para a lista de guarnições.**
 *
 * O mesmo chevron de [SetaParaDireita], girado meia volta. `Modifier.rotate` e não
 * um desenho novo: dois `Canvas` com a mesma geometria digitada duas vezes é como
 * uma seta muda de espessura sozinha no próximo refactor — e a peça original vive
 * em `ui/componentes/`, território de outra sessão.
 *
 * [Regua.Toque] de alvo mínimo. Um chevron tem 8 dp; o alvo tem 48. A diferença é
 * a mão com luva, no banco de uma viatura em movimento.
 */
@Composable
internal fun BotaoDeVoltar(aoTocar: () -> Unit) {
    Box(
        Modifier
            .size(Regua.Toque)
            .clip(RoundedCornerShape(CANTO_DO_BLOCO))
            .tocavel(forma = RoundedCornerShape(CANTO_DO_BLOCO), aoTocar = aoTocar)
            .semantics(mergeDescendants = true) {
                contentDescription = "Voltar para as guarnições"
            },
        contentAlignment = Alignment.Center,
    ) {
        SetaParaDireita(modifier = Modifier.rotate(180f), cor = Cores.TintaMedia)
    }
}

/**
 * **A marca do grupo — o nome desenhado, e não uma foto.**
 *
 * A recusa da foto está no KDoc de [CabecalhoDaConversa] e em
 * `specs/guarnicao-como-grupo.spec.md`: não há coluna, não há bucket. O que esta
 * peça faz é o trabalho *visual* que a foto faria — uma âncora que o olho pega
 * antes de ler o nome — dizendo a verdade sobre de onde ela veio.
 *
 * **Sem cor por grupo.** A tentação óbvia é derivar um matiz do nome, e ela é
 * exatamente o que a paleta proíbe: cor neste painel significa prioridade e
 * transmissão, e uma quinta gramática cromática faria as outras perderem sentido.
 * O que distingue dois grupos aqui são as letras, que é o canal que sobrevive a
 * daltonismo e a sol.
 *
 * O anel de 1 px em vez de disco cheio segue a doutrina da paleta — a estrutura
 * deste produto é feita de fios, não de caixas — e mantém as iniciais sobre
 * [Cores.Elevado], onde `TintaMedia` rende 7,01:1.
 */
@Composable
internal fun MarcaDoGrupo(nome: String, tamanho: Dp) {
    Box(
        Modifier
            .size(tamanho)
            .clip(CircleShape)
            .background(Cores.Elevado)
            .border(Regua.Fio, Cores.TracoForte, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            iniciaisDe(nome),
            // Mono aqui, e é o oposto da decisão do nome ao lado: duas ou três
            // letras num disco não são prosa, são um monograma, e a largura fixa é
            // o que as mantém centradas quando as letras mudam de grupo para grupo.
            style = Tipo.Dado.copy(fontSize = (tamanho.value * 0.34f).sp),
            color = Cores.TintaMedia,
        )
    }
}

/**
 * **Entrar e sair da escuta — o controle que este projeto tinha recusado.**
 *
 * A recusa anterior está escrita no KDoc de `EntradaNoCanal`, e o motivo era bom:
 * *"um botão precisaria de uma função nova no ViewModel e de um parâmetro novo
 * ligado no `MainActivity`; sem os dois, seria mais uma capacidade construída,
 * testada e sem chamador"*. Os dois existem agora — `entrarNaEscuta` e
 * `sairDaEscuta` —, e a mudança de comportamento passou por spec antes do diff.
 *
 * ### O rótulo vem do RÁDIO, não do dedo
 *
 * `escutaDoCanal` deriva as três palavras de `EstadoDoPtt`. Tocar o controle
 * **não** muda o rótulo: muda a intenção, o `RadioViewModel` age, e o rótulo segue
 * o que o rádio reportar. Entre o toque e a resposta, o texto continua dizendo que
 * não estamos no canal — porque não estamos.
 *
 * **Não há "conectando", e não há animação de entrada.** É a mesma ausência que
 * `Movimento` sustenta por escrito: não existe `PisoPendente` porque este projeto
 * já publicou **168 quadros para um canal em que não tinha entrado**, com o
 * indicador aceso o tempo todo.
 *
 * ### Sair não é a mesma coisa que ser recusado
 *
 * Os dois são o mesmo fato — fora do canal — e pedem gestos opostos. Sair é decisão
 * do agente e sai em [Cores.TintaFraca]; recusa é o canal privado da `0012`
 * respondendo `Unauthorized`, e sai em [Cores.FalhaTexto] (6,76:1, o token que
 * existe para quando o estado precisa ser **lido**). Pintar os dois de vermelho
 * ensinaria o agente a ignorar o vermelho.
 *
 * A cor transita em [Movimento.MICRO] com [Movimento.Sutil] — é mudança sutil de
 * estado, a única categoria de `motion-design` que pede o `ease` do CSS. Um salto
 * seco aqui leria como defeito de renderização; qualquer coisa mais longa leria
 * como progresso, que é o que não há.
 */
@Composable
private fun ControleDeEscuta(escuta: Escuta, aoTocar: () -> Unit) {
    val alvo = when (escuta.token) {
        TokenDeEscuta.NA_ESCUTA -> Cores.Tinta
        TokenDeEscuta.FORA_POR_VONTADE -> Cores.TintaFraca
        TokenDeEscuta.RECUSADO -> Cores.FalhaTexto
    }
    val cor by animateColorAsState(
        targetValue = alvo,
        animationSpec = tween(duracaoAcessivel(Movimento.MICRO), easing = Movimento.Sutil),
        label = "cor-da-escuta",
    )
    Row(
        Modifier
            .defaultMinSize(minHeight = Regua.Toque)
            .clip(PILULA)
            .background(Cores.Elevado)
            .tocavel(forma = PILULA, aoTocar = aoTocar)
            .padding(horizontal = Espaco.Medio)
            .semantics(mergeDescendants = true) {
                // O leitor de tela anuncia o ESTADO DO RÁDIO, nunca a intenção —
                // pela mesma razão que o rótulo visível.
                contentDescription = when (escuta.token) {
                    TokenDeEscuta.NA_ESCUTA -> "Você está no canal. Tocar sai da escuta"
                    TokenDeEscuta.FORA_POR_VONTADE -> "Você está fora do canal. Tocar entra na escuta"
                    TokenDeEscuta.RECUSADO ->
                        "Você não está no canal. ${escuta.detalhe.orEmpty()} Tocar tenta de novo"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextoCorpoMenor(escuta.rotulo, cor = cor, maxLinhas = 1)
    }
}

/**
 * **Quem está no ar, agora.**
 *
 * O pulso é o único movimento contínuo desta tela, e ele é honesto: só existe
 * enquanto o piso está concedido a alguém, e o dado vem do rádio em
 * milissegundos, não da recarga de dez segundos. Movimento que sugere atividade
 * sem atividade é mentira visual — aqui há atividade, e é literalmente o som
 * saindo do alto-falante.
 *
 * `Cores.Vivo` e não `Cores.NoAr`: âmbar significa "**você** está no ar", e só
 * isso. Um par transmitindo é presença ativa, não você comprometendo o canal — e
 * se as duas coisas usarem a mesma cor, a que importa deixa de saltar.
 *
 * Origem não confirmada não ganha o ponto verde. Presença viva é afirmação sobre
 * um colega; sobre este emissor não há afirmação a fazer.
 */
@Composable
private fun LinhaDeQuemFala(quem: String) {
    val confirmado = quem != AUTOR_NAO_CONFIRMADO
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "$quem está transmitindo" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (confirmado) {
            PontoDeEstado(cor = Cores.TintaMedia, pulsando = true)
        } else {
            // Traço tracejado curto, o mesmo sinal do bloco de fala não conferida.
            Box(Modifier.width(10.dp).height(2.dp).calha(Cores.TintaMedia, 10.dp, tracejada = true))
        }
        Box(Modifier.width(Espaco.Curto))
        Etiqueta(quem, cor = if (confirmado) Cores.Tinta else Cores.TintaMedia)
        Box(Modifier.width(Espaco.Curto))
        Etiqueta("no ar", cor = Cores.TintaFraca)
    }
}


// ── Histórico ────────────────────────────────────────────────────────────────

@Composable
private fun HistoricoDeFalas(falas: List<FalaNoGrupo>) {
    val estadoLista = rememberLazyListState()
    val escopo = rememberCoroutineScope()

    // `remember` sobre a lista: a recarga de 10 s devolve, na maior parte das
    // voltas, exatamente a mesma coisa. Sem isto o thread inteiro seria remontado
    // seis vezes por minuto sem nada ter mudado.
    val itens = remember(falas) { montarTrafego(falas) }

    // Rolar sempre arranca a leitura da mão de quem subiu para procurar o que
    // perdeu — e é exatamente isso que se faz no histórico de um rádio. Rolar
    // nunca esconde o que acabou de chegar. A decisão está em `deveRolarParaOFim`,
    // que é pura e tem teste; aqui só se pergunta a ela.
    LaunchedEffect(itens.size) {
        val ultimoVisivel = estadoLista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (deveRolarParaOFim(ultimoVisivel, itens.lastIndex)) {
            estadoLista.animateScrollToItem(itens.lastIndex.coerceAtLeast(0))
        }
    }

    // `derivedStateOf` e não leitura direta: `layoutInfo` muda a cada pixel de
    // rolagem, e ler no corpo do composable recomporia a tela dezenas de vezes por
    // segundo. Aqui só recompõe quando a CONTAGEM muda.
    val abaixoDaLeitura by remember(itens) {
        derivedStateOf {
            val ultimoVisivel = estadoLista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            registrosAbaixoDaLeitura(ultimoVisivel, itens.lastIndex)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = estadoLista,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Espaco.Curto),
        ) {
            items(itens, key = { it.fala.id }) { item ->
                item.separadorDeTempo?.let { SeparadorDeSilencio(it) }
                RegistroDeTrafego(item)
            }
        }

        BotaoDeIrParaOFim(
            registrosAbaixo = abaixoDaLeitura,
            aoTocar = { escopo.launch { estadoLista.animateScrollToItem(itens.lastIndex.coerceAtLeast(0)) } },
            canto = CANTO_DO_BLOCO,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Espaco.Medio),
        )
    }
}

/**
 * **Separador de silêncio.** Um fio com a lacuna escrita no meio.
 *
 * A peça é a mesma de antes; o que ela diz mudou. Era a hora cheia (`14:00`), e a
 * hora cheia compartimenta a conversa por um critério que não é do canal: 14:59 e
 * 15:01 são a mesma coisa e ganhavam um corte, quarenta minutos de silêncio dentro
 * da mesma hora não ganhavam nada. A regra agora é lacuna — ver
 * [separadorDeLacuna], que é onde o limiar mora com o porquê.
 *
 * **O rótulo perdeu a caixa-alta**, e o motivo já tem precedente nesta tela: a
 * `Etiqueta` a 0,16 em de entreletra é o vocabulário do que é excepcional, e um
 * separador é estrutura — a mesma troca que "áudio sem transcrição" fez quando
 * passou de `Etiqueta` para [TextoDado]. A hora completa continua em toda linha,
 * no rodapé de cada bloco; o que só existe aqui é a **duração**, e é ela que fica
 * escrita.
 */
@Composable
private fun SeparadorDeSilencio(rotulo: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Padrao)
            // Um nó só para o leitor de tela: "quarenta minutos, fio, sem tráfego"
            // não é uma frase.
            .semantics(mergeDescendants = true) { contentDescription = rotulo },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(Regua.Fio).background(Cores.Traco))
        Box(Modifier.width(Espaco.Medio))
        TextoDado(rotulo, cor = Cores.TintaFraca)
        Box(Modifier.width(Espaco.Medio))
        Box(Modifier.weight(1f).height(Regua.Fio).background(Cores.Traco))
    }
}

/**
 * Um registro do canal.
 *
 * **Lateralidade sem virar aplicativo social.** O que faz um balão parecer social
 * são três coisas separáveis: canto arredondado, rabinho e o "eu" pintado de cor
 * viva. Duas continuam fora — não há rabinho, e a fala própria é a **rebaixada**,
 * nunca a colorida. O canto entrou em 21/08, e é a única das três que não carrega
 * informação nenhuma: ele não diz de quem é a fala, diz que o bloco tem um fim.
 * Retângulo cortado a raio zero encostado na margem lê como painel de terminal
 * inacabado, e a tela toda pagava por isso.
 *
 * O lado continua vindo da geometria: alinhamento mais uma margem vazia de
 * [MARGEM_DO_LADO_OPOSTO] do lado oposto — que subiu de 16% para 22%, porque a
 * assimetria precisa ser legível **na mensagem curta**, e a mensagem curta é a
 * regra no rádio.
 *
 * Alerta classificado não recebe lado nenhum: ocupa a linha inteira, como num
 * terminal de despacho. É essa segunda forma que impede a tela de virar bate-papo.
 *
 * **A pilha interna é a de um balão de mensagem, com o topo trocado.** Onde um
 * mensageiro põe a citação da resposta, aqui vai a procedência: a classificação do
 * alerta, e o aviso de autoria não conferida. Depois vêm autor, texto e um rodapé
 * com hora e entrega — a mesma ordem, com conteúdo que existe.
 *
 * As duas fatias do topo eram **faixas de largura total**, com fundo próprio, e
 * viraram linha + fio: fundo cheio dentro de fundo cheio é caixa dentro de caixa,
 * e a doutrina desta paleta é fio de 1 px. O que elas dizem não mudou uma palavra.
 */
@Composable
private fun RegistroDeTrafego(item: ItemDeTrafego) {
    val ehRegistro = item.forma == FormaDoRegistro.REGISTRO_DE_CANAL
    val aDireita = item.forma == FormaDoRegistro.PROPRIO
    val naoConfirmada = item.procedencia == Procedencia.NAO_CONFIRMADA
    val forma = RoundedCornerShape(CANTO_DO_BLOCO)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Espaco.Medio,
                end = Espaco.Medio,
                // Proximidade é o agrupador: turno novo respira, continuação
                // adensa. Sem caixa e sem fio entre itens, é o único disponível.
                top = if (item.abreSequencia) Espaco.Medio else Espaco.Micro,
            )
            .semantics(mergeDescendants = true) { contentDescription = item.leituraEmVoz },
    ) {
        // A margem reservada É o sinal de lado. Quem "limpar" este Box achando que
        // é resíduo mata a lateralidade sem produzir erro de compilação.
        if (aDireita) Box(Modifier.weight(MARGEM_DO_LADO_OPOSTO))

        Column(
            Modifier
                .weight(if (ehRegistro) 1f else 1f - MARGEM_DO_LADO_OPOSTO)
                // `clip` ANTES do fundo e da calha, e a ordem é o desenho: a calha
                // é uma barra reta desenhada da borda, e é o recorte que a faz
                // afinar nos cantos em vez de furar o balão. Trocar a ordem não
                // quebra build nenhum — só devolve os quatro bicos.
                .clip(forma)
                .background(if (aDireita) Cores.Painel else Cores.Elevado)
                .calha(
                    cor = corDaCalha(item.calha, naoConfirmada),
                    largura = larguraDaCalha(item.calha, naoConfirmada),
                    tracejada = naoConfirmada,
                )
                .then(
                    // O perímetro que não fecha. Ver `contornoTracejado`.
                    if (naoConfirmada) {
                        Modifier.contornoTracejado(Cores.TintaFraca, CANTO_DO_BLOCO)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = Espaco.Medio,
                    end = Espaco.Medio,
                    top = Espaco.Medio,
                    bottom = Espaco.Curto,
                ),
        ) {
            if (ehRegistro) {
                LinhaDeClassificacao(item.fala.prioridade ?: 3)
            }
            // A procedência vem DEPOIS da classificação e ANTES do nome, porque é
            // nesta ordem que a dúvida precisa chegar: "isto é um P1" → "e não sei
            // de quem" → o nome. Invertido, o agente lê o nome primeiro e o
            // crédito já foi dado.
            if (naoConfirmada) {
                // **A pergunta "quem falou?" tem resposta, e a resposta é
                // "ninguém que este servidor consiga nomear".**
                //
                // O bloco mostrava só o aviso, e ficava ambíguo entre "a tela
                // escondeu o nome" e "não há nome". A fonte é única e é literal:
                // `HistoricoDoCanal` lê `agents.indicativo` pelo `join` de
                // autoria (`transmissions_author_agent_id_fkey`), e quando o
                // vínculo não fecha o campo chega VAZIO. Não há nome reivindicado
                // guardado em `transmissions` — a coluna que existe é a de
                // autoria, e é ela que não resolveu.
                //
                // O nome que o EMISSOR escreve no anúncio ao vivo é outra coisa e
                // não chega até aqui: `RadioTatico` o descarta na chegada, por
                // decisão escrita — *"exibir o rótulo que o próprio forjador
                // digitou é pior que não exibir nada, porque dá autoridade à
                // mentira"*. Trazê-lo para a tela é mudança de comportamento numa
                // regra de segurança, e começa por diff de spec.
                BlocoDeProcedencia(
                    rotulo = AUTOR_NAO_CONFIRMADO,
                    explicacao = "Nenhum agente do cadastro do grupo assinou esta " +
                        "transmissão. Não há indicativo a mostrar.",
                )
                Box(Modifier.height(Espaco.Curto))
            }

            if (item.mostraIndicativo) {
                // Tinta média e não cheia: o nome de quem falou é o rótulo do
                // bloco, não o conteúdo dele. Em tinta cheia ele competia de igual
                // para igual com a transcrição — e a transcrição é o que o agente
                // subiu no histórico para ler.
                TextoIndicativo(item.autorExibido, cor = Cores.TintaMedia)
                Box(Modifier.height(Espaco.Micro))
            }
            // Fala sem transcrição não é fala vazia — é áudio que ninguém
            // transcreveu ainda. Um balão em branco faria o agente procurar o
            // texto que sumiu; dizer o que houve custa uma linha e não mente.
            //
            // Em monoespaçada, e é aí que a divisão das duas famílias começa a
            // pagar: sans é fala humana, mono é o aparelho falando de si. Antes
            // era `Etiqueta`, e a caixa-alta espaçada gritava mais que a própria
            // transcrição ao lado.
            if (item.fala.texto.isBlank()) {
                TextoDado("áudio sem transcrição", cor = Cores.TintaMedia)
            } else {
                TextoCorpo(item.fala.texto, cor = tinta(item.tintaDoTexto))
            }
            // O fio que não termina, colado no texto que não terminou. Vale também
            // quando NÃO há transcrição: "áudio sem transcrição" cortado no meio
            // continua sendo áudio que acabou antes da hora, e o rodapé sozinho não
            // diz isso perto o bastante do lugar onde faltou.
            if (item.fala.cortadaPelaRede) {
                Box(Modifier.height(Espaco.Micro))
                FimInterrompido()
            }
            Box(Modifier.height(Espaco.Micro))
            RodapeDoRegistro(item)
        }

        if (!aDireita && !ehRegistro) Box(Modifier.weight(MARGEM_DO_LADO_OPOSTO))
    }
}

/**
 * Rodapé do bloco: hora e estado de entrega, alinhados à direita.
 *
 * É a fatia que um mensageiro usa para carimbo e marcas de leitura, e aqui ela
 * carrega as duas únicas coisas verdadeiras do gênero. **Não há "visto".** Existe
 * tabela de confirmação no servidor e não existe quem a preencha — duas marcas de
 * leitura seriam a mesma mentira que "na fila" era, com desenho melhor.
 *
 * **Uma linha só, uma família só, um tamanho só.** Eram dois `Text` com gramáticas
 * diferentes coladas — `ENVIADA` em caixa-alta a 0,16 em de entreletra ao lado de
 * `15:01:02` — e o par gritava mais que a transcrição que ele carimba. Metadado que
 * grita rouba a leitura de quem está em deslocamento.
 *
 * **O que grita agora é só o que é excepcional.** "enviada" é o caso nominal e sai
 * em [Cores.TintaFraca]; "não saiu" sobe para [Cores.TintaMedia] — 7,01:1 contra
 * 4,70:1 — porque é o rótulo pelo qual este projeto brigou. Antes os dois tinham
 * exatamente o mesmo peso, o que é a versão tipográfica de dizer que dá no mesmo.
 * Continua sem cor: cor já significa prioridade e transmissão neste painel.
 *
 * A hora fica em TODAS as linhas, inclusive em continuação de sequência: é ela que
 * faz o histórico servir de log, e log sem hora é conversa. **Com o segundo** — ver
 * a nota abaixo.
 *
 * ---
 * ### O segundo saiu, e voltou pela captura
 *
 * O carimbo foi cortado para `HH:mm` num primeiro passe, pela razão certa: metadado
 * tem de ser quieto. A vitrine mostrou o custo na primeira tela — as duas falas
 * seguidas de `BRAVO UM`, a 14:58:12 e 14:58:41, viraram **dois `14:58` idênticos**
 * um debaixo do outro. Num histórico de conversa isso não incomoda; num log de
 * rádio, o segundo é o que casa a fala com o registro de despacho e com o carimbo
 * da evidência.
 *
 * O que deixava o carimbo pesado não era o segundo, era a companhia: `ENVIADA` em
 * caixa-alta a 0,16 em de entreletra, noutra família, coladinho nele. Removida a
 * companhia, `15:03:38` em 12 sp monoespaçado e [Cores.TintaFraca] já é quieto.
 * **Cortar dado para resolver um problema de tipografia era consertar a coisa
 * errada.**
 */
@Composable
private fun RodapeDoRegistro(item: ItemDeTrafego) {
    // A escolha da palavra saiu daqui para `marcaDoRodape`, e o motivo é o do KDoc
    // de `TrafegoDoCanal.kt`: sem `ui-test-junit4`, decisão dentro de composable é
    // decisão sem teste. O que sobrou aqui é token virando pixel.
    val marca = marcaDoRodape(item)
    val hora = item.fala.hora
    // Os dois estados excepcionais sobem para 7,01:1; o nominal ("enviada", ou a
    // hora sozinha) fica em 4,70:1. É a mesma régua de antes, com o caso novo do
    // lado certo dela — fala cortada é exatamente o gênero de fato pelo qual este
    // rodapé brigou para não ter o mesmo peso do caso comum.
    val excepcional = item.fala.cortadaPelaRede ||
        item.rotuloDeEntrega == RotuloDeEntrega.NAO_SAIU
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextoDado(
            if (marca == null) hora else "$marca · $hora",
            cor = if (excepcional) Cores.TintaMedia else Cores.TintaFraca,
        )
    }
}

/**
 * Classificação do alerta — linha, não banda.
 *
 * Três canais em paralelo — cor, largura e **rótulo escrito**. P2 e P3 diferem por
 * 1 px de calha, ou seja diferem só por cor: um agente daltônico, ou qualquer um
 * sob sol forte, não distingue. O rótulo é o canal que não depende de visão de cor
 * nem de contraste.
 *
 * Era uma banda de largura total com o fundo tingido na cor da prioridade. Saiu por
 * duas razões, e a segunda é medida:
 *
 *  1. Fundo cheio dentro de fundo cheio é caixa dentro de caixa. O sistema desta
 *     paleta é fio de 1 px, e o fio abaixo do rótulo faz o mesmo trabalho de
 *     separar cabeçalho de conteúdo por 1/12 da tinta.
 *  2. **Tingir o balão não era opção.** Sobrepor `P1` a 10% sobre `Elevado` dá
 *     `#322221`, e sobre ele [Cores.TintaFraca] cai de **4,70:1 para 4,27:1** —
 *     abaixo de AA. A 5% dá exatamente 4,50, ou seja, no fio da navalha. O rodapé
 *     inteiro do balão é `TintaFraca`; tingir o fundo do P1 rebaixaria em silêncio
 *     a correção de contraste feita hoje, e justamente no bloco de emergência.
 *
 * O que sustenta o P1 é o que sempre sustentou: calha de 4 px em vermelho, largura
 * inteira sem lado, o fio na cor da prioridade, e o rótulo em caixa-alta.
 *
 * O texto sai em `Cores.Tinta` e não na cor da prioridade: P3 sobre `Elevado` rende
 * 4,28:1, abaixo do mínimo de 4,5:1 para texto pequeno. A cor fica no fio e na
 * calha, que são elemento não-textual e respondem a 3:1.
 *
 * **P3 não é excepcional, e por isso não grita.** Caixa-alta espaçada fica com P1 e
 * P2; prioridade normal é o caso comum, e a paleta já tinha tirado a cor dele pela
 * mesma razão — o que é comum não pode gastar o recurso do que é raro.
 */
@Composable
private fun LinhaDeClassificacao(prioridade: Int) {
    if (prioridade <= 2) {
        Etiqueta(rotuloDePrioridade(prioridade), cor = Cores.Tinta)
    } else {
        TextoDado(rotuloDePrioridade(prioridade), cor = Cores.TintaFraca)
    }
    Box(Modifier.height(Espaco.Curto))
    Fio(cor = if (prioridade <= 2) corDaPrioridade(prioridade) else Cores.Traco)
    Box(Modifier.height(Espaco.Curto))
}

// ── Barra de composição ──────────────────────────────────────────────────────

/**
 * **Por onde se fala — e agora só isso.**
 *
 * A faixa carregava duas ações: a consulta ao copiloto, numa pílula, e o
 * push-to-talk. **"Perguntar ao copiloto" saiu** — decisão humana desta sessão.
 *
 * ### A pergunta que essa remoção obriga a responder
 *
 * Este parágrafo existe porque `CLAUDE.md` §6 pergunta *"quem chama isto em
 * `src/main`?"*, e apagar um chamador é o mesmo risco que nunca ter criado um.
 * A pílula era, desde o commit `d888970`, a única porta de toque até `cicloDeVoz`
 * — o ciclo inteiro tinha ficado pronto, testado e inalcançável, e ela foi o
 * conserto.
 *
 * Ela pode sair agora porque a porta **de produto** ficou pronta no intervalo:
 * `CopilotService:107` chama `cicloDeVoz(ultimaAtivacao)` a partir da palavra de
 * ativação, medida no aparelho em 1500 quadros / 30,0 s. `grep -rn cicloDeVoz
 * app/src/main` devolve esse chamador, e ele não depende de tela nenhuma estar
 * composta. O botão sempre foi o andaime; "Claryon" é o desenho.
 *
 * O que sobra aqui é a ação de maior consequência da tela, sozinha, e ela ganha a
 * faixa inteira: falar com a guarnição precisa ser encontrado sem olhar.
 */
@Composable
private fun BarraDeComposicao(
    estadoDoPtt: EstadoDoPtt,
    aoPressionarPtt: () -> Unit,
    aoSoltarPtt: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Cores.Painel)) {
        Fio()
        Box(Modifier.height(Espaco.Curto))
        // O raio desce daqui e não é digitado lá: é o mesmo dos balões, e o
        // caminho é o que `BotaoDeIrParaOFim` já usa. Dois raios diferentes na
        // mesma tela é o tipo de discordância que ninguém nomeia e todo mundo vê.
        BarraDePtt(estadoDoPtt, aoPressionarPtt, aoSoltarPtt, canto = CANTO_DO_BLOCO)
    }
}

/**
 * Pílula — canto totalmente arredondado.
 *
 * `percent` e não `Dp`: a pílula tem de acompanhar a altura, e um raio fixo
 * deixaria as pontas retas assim que a linha crescesse com a escala de fonte.
 *
 * Vive aqui pelo mesmo padrão de [MARGEM_DO_LADO_OPOSTO] e [CANTO_DO_BLOCO] — um
 * símbolo, com o porquê ao lado — porque `ui/tema/` estava fora do território
 * desta sessão. **Isto quer ser `Regua.Pilula`.**
 */
internal val PILULA = RoundedCornerShape(percent = 50)

// ── Tradução de token em pixel ───────────────────────────────────────────────

private fun corDaPrioridade(p: Int) = when (p) {
    1 -> Cores.P1
    2 -> Cores.TintaMedia
    else -> Cores.TintaFraca
}

private fun corDaCalha(token: TokenDeCalha, naoConfirmada: Boolean): Color = when {
    // Prioridade ganha da procedência na COR, e não perde nada com isso: o
    // tracejado continua dizendo que a autoria não fechou. Um P1 não conferido
    // precisa ser vermelho — pode ser um pedido de apoio real.
    token == TokenDeCalha.P1 -> Cores.P1
    token == TokenDeCalha.P2 -> Cores.TintaMedia
    token == TokenDeCalha.P3 -> Cores.TintaFraca
    naoConfirmada -> Cores.TracoForte
    token == TokenDeCalha.TRACO -> Cores.Traco
    else -> Cores.TracoForte
}

/**
 * **Os três valores vêm de [Regua], e o motivo está escrito lá.**
 *
 * `Regua.MarcaP1/P2/P3` nasceram hoje justamente para matar os `4.dp/3.dp/2.dp`
 * digitados duas vezes — aqui e em `Comuns.FaixaDePrioridade` — e nasceram **sem
 * um único chamador**: um `grep` de `Regua.` em `app` e nos `core` devolvia zero.
 * Um canal de acessibilidade que depende de dois literais concordarem não é um
 * canal, é uma coincidência, e a coincidência continuava de pé porque ninguém tinha
 * ligado o token na tomada.
 */
private fun larguraDaCalha(token: TokenDeCalha, naoConfirmada: Boolean): Dp {
    val base = when (token) {
        TokenDeCalha.P1 -> Regua.MarcaP1
        TokenDeCalha.P2 -> Regua.MarcaP2
        else -> Regua.MarcaP3
    }
    // Tracejado a 2 dp lê como linha contínua. Abaixo de 3 dp o vão some.
    return if (naoConfirmada && base < Regua.MarcaP2) Regua.MarcaP2 else base
}

@Composable
private fun tinta(token: TokenDeTinta) = when (token) {
    TokenDeTinta.TINTA -> Cores.Tinta
    TokenDeTinta.TINTA_MEDIA -> Cores.TintaMedia
    TokenDeTinta.TINTA_FRACA -> Cores.TintaFraca
}

/**
 * 22% de margem vazia do lado oposto — o balão vai a **78%** da largura útil.
 *
 * Eram 16%, e 16% não se lê. A assimetria só existe quando o bloco chega perto do
 * limite: numa fala longa a linha quebra e a borda direita encosta na margem, mas
 * a fala curta — que é a regra no rádio — para muito antes dela, e aí os 16% de
 * reserva não aparecem em lugar nenhum. Com 78% o teto fica visível o suficiente
 * para o olho reconstruir o lado mesmo em três palavras.
 */
private const val MARGEM_DO_LADO_OPOSTO = 0.22f

/**
 * **O raio do balão. É o item que mais muda a tela por menos código.**
 *
 * Raio zero num bloco que vai até a margem lê como retângulo cortado, e retângulo
 * cortado lê como painel inacabado — era o diagnóstico inteiro do "bruto".
 *
 * **Isto quer ser `Regua.Canto`**, e não é porque `ui/tema/` estava fora do
 * território desta sessão. Enquanto não for, é o token de espaçamento mais próximo
 * do alvo (10 dp) — [Espaco.Curto], a 2 dp dele — e vive aqui pelo mesmo padrão que
 * [MARGEM_DO_LADO_OPOSTO] e [ALTURA_DA_LINHA_VIVA] já usavam neste arquivo: um
 * símbolo, com o porquê ao lado. Quem mover isto para `Regua` troca uma linha.
 */
internal val CANTO_DO_BLOCO = Espaco.Medio

/**
 * Altura da linha viva do cabeçalho.
 *
 * Fixa, e é o ponto: a linha troca de conteúdo o tempo todo — a lista de
 * integrantes quando o canal está calado, quem está no ar quando não está. Se ela
 * crescesse, o histórico inteiro pularia toda vez que alguém apertasse o botão.
 *
 * Subiu de 22 para 24 dp quando a contagem entrou nesta linha em sans de 13 sp:
 * `Tipo.CorpoMenor` tem 18 sp de altura de linha, e a 1,3× de escala de fonte —
 * que é ajuste comum de acessibilidade, não caso extremo — 18 sp viram 23,4 dp e a
 * frase era cortada pela metade. Altura fixa protege o layout; ela não pode custar
 * a legibilidade de quem aumentou a fonte.
 */
private val ALTURA_DA_LINHA_VIVA = 24.dp

/**
 * Diâmetro da marca do grupo no cabeçalho.
 *
 * 40 dp e não [Regua.Toque]: a marca **não é o alvo** — quem é alvo é a linha
 * inteira do nome, e ela já tem 48 dp de altura. Uma marca do tamanho do alvo
 * ficaria maior que o nome ao lado e viraria o elemento dominante de um cabeçalho
 * que o agente lê uma vez por turno.
 *
 * Não vem de [Espaco] de propósito: espaçamento é ritmo entre coisas, e isto é o
 * tamanho de uma coisa. É a mesma separação que [Regua] faz — e **isto quer ser
 * `Regua.MarcaDeGrupo`**, quando `ui/tema/` sair do território de outra sessão.
 */
internal val MARCA_NO_CABECALHO = 40.dp

/** A marca na página do grupo, onde ela é o elemento de abertura e pode dominar. */
internal val MARCA_NA_PAGINA = 64.dp

/**
 * 2 dp — **abaixo** do piso de [Espaco], e é de propósito.
 *
 * `Espaco.Micro` (4 dp) é o menor degrau da escala, e a escala mede ritmo **entre
 * blocos**. Isto é outra coisa: o vão óptico entre duas linhas de texto que já são
 * um bloco só — nome e subtítulo, nome e rótulo falado. A 4 dp elas deixam de ler
 * como par e passam a ler como dois itens.
 *
 * Existe nomeado, e não como `2.dp` solto em três lugares, para que o desvio esteja
 * declarado — e para que quem o encontrar leia o porquê antes de "consertar" para
 * `Espaco.Micro`.
 */
internal val RESPIRO = 2.dp
