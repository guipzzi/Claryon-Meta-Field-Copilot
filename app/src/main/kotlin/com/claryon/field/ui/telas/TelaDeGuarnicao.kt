package com.claryon.field.ui.telas

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.BarraDePtt
import com.claryon.field.ui.componentes.BotaoDeIrParaOFim
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.LinhaDeProcedencia
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

/** Um par no grupo, para a régua de presença do topo. */
data class ParPresente(val indicativo: String, val online: Boolean, val falando: Boolean)

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
    canal: String,
    pares: List<ParPresente>,
    falas: List<FalaNoGrupo>,
    estadoDoPtt: EstadoDoPtt,
    aoPressionarPtt: () -> Unit,
    aoSoltarPtt: () -> Unit,
    /**
     * Abre o ciclo de voz do copiloto: captura → VAD → Whisper → roteador →
     * executor → resposta falada.
     *
     * **Este parâmetro é a correção do defeito mais caro do projeto.** O ciclo
     * inteiro estava pronto, testado e **inalcançável**: o único chamador de
     * `cicloDeVoz` vivia em `DiagnosticsScreen`, que não é composta em lugar
     * nenhum. C2, C3 e C4 eram indemonstráveis por voz — num produto cuja
     * premissa é operação por voz.
     *
     * O botão não é o desenho final; o final é "Hey Claryon". É o caminho
     * alcançável que prova que a capacidade existe, e ele vem antes porque
     * "construir, testar e não ligar" já aconteceu seis vezes aqui.
     */
    aoAbrirCopiloto: () -> Unit,
    /** `true` enquanto o ciclo está ouvindo ou pensando. Trava o botão. */
    copilotoOcupado: Boolean = false,
    /**
     * Quem detém o piso **agora**, vindo do rádio.
     *
     * Existe separado de [pares] porque há um valor que a régua de presença não
     * consegue representar: `AUTOR_NAO_CONFIRMADO`. A régua casa por indicativo
     * (`it.indicativo == quem`), e "Origem não confirmada" não casa com ninguém —
     * então uma transmissão de autoria duvidosa passava pelo alto-falante sem
     * **nada** na tela, que é o pior desfecho possível para o ataque que este
     * produto de fato tem.
     *
     * `null` quando o canal está calado.
     */
    quemEstaNoAr: String? = null,
    modifier: Modifier = Modifier,
) {
    // O painel de detalhes é estado LOCAL desta tela, de propósito: ele não muda
    // nada no rádio, não sobrevive à troca de destino e não precisa de nada que o
    // `MainActivity` já não passe. Estado que sobe sem motivo vira parâmetro que
    // alguém esquece de ligar — e capacidade sem chamador já aconteceu seis vezes
    // aqui.
    var detalhes by remember { mutableStateOf(false) }
    BackHandler(enabled = detalhes) { detalhes = false }

    Box(modifier.fillMaxSize()) {
        // `background` própria e não herdada do casco: uma tela que depende do
        // hospedeiro para ter fundo mostra o cinza do tema do sistema no primeiro
        // lugar em que for composta fora dele — foi o que a vitrine de captura
        // revelou, e num painel escuro isso é a tela inteira queimando visão
        // noturna.
        Column(Modifier.fillMaxSize().background(Cores.Vazio)) {
            CabecalhoDaConversa(
                canal = canal,
                pares = pares,
                // Cai para a régua quando o rádio não informa: sem o parâmetro
                // ligado, a tela continua sabendo o que sabia antes, e não inventa
                // nada.
                noAr = quemEstaNoAr ?: pares.firstOrNull { it.falando }?.indicativo,
                aoAbrirDetalhes = { detalhes = true },
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
                aoAbrirCopiloto = aoAbrirCopiloto,
                copilotoOcupado = copilotoOcupado,
            )
        }

        if (detalhes) {
            DetalhesDaGuarnicao(
                canal = canal,
                pares = pares,
                estadoDoPtt = estadoDoPtt,
                aoFechar = { detalhes = false },
            )
        }
    }
}

// ── Cabeçalho ────────────────────────────────────────────────────────────────

/**
 * **Cabeçalho da conversa: com quem se está falando, e quem está falando.**
 *
 * A primeira linha é identidade — o canal, e quantos dos pares estão publicando
 * posição. A segunda é a que muda o tempo todo, e por isso tem **altura fixa**:
 * ela troca entre a régua de presença (canal calado) e quem está no ar. Um
 * cabeçalho que cresce quando alguém fala empurraria o histórico para baixo no
 * exato instante em que o agente está lendo — deslocamento de layout como efeito
 * colateral de uma informação que cabia no mesmo espaço.
 *
 * A troca é `Crossfade` e não corte seco: o conteúdo continua existindo dos dois
 * lados da transição, e é isso que a curva de morfose diz. Corte seco a essa
 * frequência lê como falha de renderização.
 */
@Composable
private fun CabecalhoDaConversa(
    canal: String,
    pares: List<ParPresente>,
    noAr: String?,
    aoAbrirDetalhes: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Cores.Vazio)
            .padding(
                start = Espaco.Padrao,
                end = Espaco.Padrao,
                top = Espaco.Padrao,
                bottom = Espaco.Medio,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                Modifier
                    // `forma` e não `clip`: o realce de pressão herda o raio dos
                    // balões sem que o arco passe por cima do "T" de TALK GROUP,
                    // que nasce no canto do nó. Ver o KDoc de `tocavel`.
                    .tocavel(forma = RoundedCornerShape(CANTO_DO_BLOCO), aoTocar = aoAbrirDetalhes)
                    .padding(end = Espaco.Curto)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Talk group $canal. Abrir detalhes da guarnição"
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Etiqueta("Talk group")
                    Box(Modifier.height(Espaco.Micro))
                    // O canal em corpo grande é a âncora da tela: é a única coisa
                    // que o agente confere de relance para saber com quem está
                    // falando. Agora é também a porta dos detalhes — o mesmo lugar
                    // em que qualquer mensageiro põe essa porta, o que é o
                    // argumento inteiro: custo de aprendizado zero.
                    Text(canal.uppercase(), style = Tipo.IndicativoGrande, color = Cores.Tinta)
                }
                Box(Modifier.width(Espaco.Curto))
                // Desenhada, não glifo — o mesmo motivo de `SetaParaBaixo`. E é o
                // que impede a porta de ser invisível: um bloco tocável sem
                // affordance é um segredo, e o agente não descobre segredo no meio
                // de uma ocorrência.
                SetaParaDireita(modifier = Modifier.padding(bottom = Espaco.Curto))
            }
            Column(horizontalAlignment = Alignment.End) {
                // **"Com posição", e não "no canal".** O dado por trás é
                // `ParPresente.online`, que o `RadioViewModel` deriva da IDADE DA
                // POSIÇÃO — publicou coordenada há pouco, e nada além disso. Ler
                // "2/3 no canal" e entender "um saiu do ar" leva a decisão errada
                // quando a verdade é "um está numa garagem sem GPS". É a mesma
                // razão pela qual não existe "visto" no rodapé do balão: rótulo
                // que afirma mais do que o dado sustenta é mentira com desenho
                // melhor. O detalhe explica de onde vem — ver
                // [DetalhesDaGuarnicao].
                Etiqueta("Com posição")
                Box(Modifier.height(Espaco.Micro))
                Text(
                    "${pares.count { it.online }}/${pares.size}",
                    style = Tipo.IndicativoGrande,
                    color = if (pares.any { it.online }) Cores.Tinta else Cores.TintaFraca,
                )
            }
        }
        Box(Modifier.height(Espaco.Medio))

        Box(Modifier.fillMaxWidth().height(ALTURA_DA_LINHA_VIVA)) {
            Crossfade(
                targetState = noAr,
                animationSpec = tween(Movimento.MEDIO, easing = Movimento.Morfose),
                label = "linha-viva",
            ) { quem ->
                if (quem != null) LinhaDeQuemFala(quem) else ReguaDePresenca(pares)
            }
        }
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
            PontoDeEstado(cor = Cores.Vivo, pulsando = true)
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

/**
 * Régua de presença: quem está no canal, em uma linha.
 *
 * Rola horizontalmente em vez de quebrar em grade. Uma guarnição tem 4 a 8
 * pessoas; a lista horizontal mantém a altura fixa e previsível, e o que importa
 * — quem está online — vai sempre para o começo.
 */
@Composable
private fun ReguaDePresenca(pares: List<ParPresente>) {
    if (pares.isEmpty()) {
        Etiqueta("Ninguém no canal", cor = Cores.TintaFraca)
        return
    }

    // Online primeiro, ausentes por último — a ordem em que a informação é útil,
    // não a ordem alfabética do cadastro. Quem fala saiu daqui: quando alguém
    // detém o piso, esta régua nem está na tela.
    val ordenados = pares.sortedByDescending { it.online }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Espaco.Padrao),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (p in ordenados) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PontoDeEstado(cor = if (p.online) Cores.Vivo else Cores.TintaFraca)
                Box(Modifier.width(Espaco.Curto))
                TextoDado(p.indicativo, cor = if (p.online) Cores.Tinta else Cores.TintaFraca)
            }
        }
    }
}

// ── Detalhes da guarnição ────────────────────────────────────────────────────

/**
 * **Detalhes do talk group.** Abre pelo nome da guarnição, no cabeçalho.
 *
 * ---
 * ### O que esta tela NÃO mostra, e por quê
 *
 * O pedido incluía **descrição do grupo**. Ela não existe: `talk_groups` tem
 * `id`, `unit_id`, `nome`, `tipo` e `rotulo_falado`, e mais nada — conferido
 * migração por migração em `servidor/migracoes` (`0001` cria a tabela, `0011`
 * acrescenta `rotulo_falado`; um `grep` de "descricao" nos 23 arquivos devolve
 * zero). Um campo de descrição aqui seria um retângulo vazio pedindo para ser
 * preenchido com invenção, e a invenção só apareceria em produção.
 *
 * **Foto de integrante** também não, por dois motivos independentes: não há coluna
 * em `agents`, e base biométrica é proibida neste produto sem versão e sem flag. O
 * indicativo é a identidade — é assim que o agente existe no rádio.
 *
 * **"Visto por último"** foi recusado antes, no rodapé do balão, e a recusa vale
 * aqui pelo mesmo motivo: não há quem produza o dado.
 *
 * ---
 * ### O que "com posição" é — e a razão de a palavra ter mudado
 *
 * `ParPresente.online` é **derivado da idade da última posição publicada**:
 * `RadioViewModel` marca `true` quando `idadeDaPosicaoS <= LIMIAR_DE_PRESENCA_S`,
 * que são **120 s**, recarregando a cada 10 s. Não é presença no canal, não é "o
 * app está aberto", não é Realtime Presence — a política de presença do servidor
 * está **deliberadamente negada** em `0012`, onde só `broadcast` tem política.
 *
 * Ler "ALFA DOIS offline" e entender "saiu do ar" leva a decisão errada quando a
 * verdade é "está numa garagem sem GPS". Por isso a tela escreve **posição**, e por
 * isso esta nota existe dentro do painel e não só no código.
 *
 * **E a lista é menor que a guarnição.** `posicoes_do_grupo` faz `join` com
 * `agent_positions` (`0021`), então quem **nunca** publicou posição não entra como
 * ausente: some. A contagem honesta é "de quem publica posição", nunca "de quantos
 * são" — o cadastro completo existe em `cadastro_do_grupo` (`0013`), mas o cliente
 * o colapsa num `Map` de autoria e a tela não o recebe.
 */
@Composable
private fun DetalhesDaGuarnicao(
    canal: String,
    pares: List<ParPresente>,
    estadoDoPtt: EstadoDoPtt,
    aoFechar: () -> Unit,
) {
    val comPosicao = pares.count { it.online }
    Column(Modifier.fillMaxSize().background(Cores.Vazio)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = Espaco.Padrao,
                    end = Espaco.Padrao,
                    top = Espaco.Padrao,
                    bottom = Espaco.Medio,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Etiqueta("Talk group")
                Box(Modifier.height(Espaco.Micro))
                Text(canal.uppercase(), style = Tipo.IndicativoGrande, color = Cores.Tinta)
            }
            Row(
                Modifier
                    .defaultMinSize(minHeight = Regua.Toque)
                    .tocavel(aoTocar = aoFechar)
                    .padding(horizontal = Espaco.Medio),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fechar", style = Tipo.Acao, color = Cores.TintaMedia)
            }
        }
        Fio()

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            EntradaNoCanal(estadoDoPtt)
            Fio()

            Column(Modifier.padding(Espaco.Padrao)) {
                Etiqueta("Quem publica posição")
                Box(Modifier.height(Espaco.Curto))
                // Nunca "N integrantes": esta lista não é o cadastro. Ver o KDoc.
                TextoCorpoMenor(
                    "$comPosicao de ${pares.size} com posição dos últimos 2 minutos.",
                    cor = Cores.TintaMedia,
                )
            }

            if (pares.isEmpty()) {
                Vazio(
                    etiqueta = "Ninguém publicando",
                    explicacao = "Nenhum par do grupo publicou posição. Pode ser o " +
                        "grupo calado, ou este aparelho sem rede.",
                )
            } else {
                // Com posição primeiro. É a ordem em que a informação é útil, não a
                // do cadastro — e é a mesma da régua do cabeçalho.
                for (p in pares.sortedByDescending { it.online }) {
                    LinhaDeIntegrante(p)
                }
            }

            Fio()
            Column(Modifier.padding(Espaco.Padrao)) {
                Etiqueta("O que esta lista é")
                Box(Modifier.height(Espaco.Curto))
                TextoCorpoMenor(
                    "«Com posição» quer dizer que o aparelho do par publicou " +
                        "coordenada há menos de 2 minutos. Não é presença no canal, " +
                        "e não é «visto por último» — este produto não guarda isso.",
                    cor = Cores.TintaFraca,
                )
                Box(Modifier.height(Espaco.Curto))
                TextoCorpoMenor(
                    "Quem nunca publicou posição não aparece nesta lista. Ela é " +
                        "menor que a guarnição, e não sabe dizer quanto menor.",
                    cor = Cores.TintaFraca,
                )
            }
            Box(Modifier.height(Espaco.Bloco))
        }
    }
}

/**
 * **Você está no canal, ou não está — e a causa vem junto.**
 *
 * É a parte de "ficar online" que **tem fonte**. O estado sai inteiro de
 * [EstadoDoPtt], que o `RadioViewModel` já publica: `Pronto` só existe com rota de
 * áudio e transporte conectado, e `Indisponivel` carrega o motivo — inclusive o
 * `Unauthorized` do canal privado, que `ProtocoloRealtime` traduz em
 * `CanalRecusado` e o `RadioViewModel` reescreve como *"Canal negado. …"*.
 *
 * Duas regras do produto, as duas obedecidas por construção:
 *
 *  1. **Nada de "entrou" antes do servidor confirmar.** Este bloco não tem estado
 *     próprio e não anima nada: ele **lê** o estado do rádio. Não existe
 *     "conectando" porque não existe dado de "conectando" — este projeto já mandou
 *     168 quadros para um canal em que não tinha entrado, com o indicador aceso, e
 *     a lição virou a ausência de `PisoPendente` em `Movimento`.
 *  2. **A recusa é visível.** `Indisponivel` sai em [Cores.FalhaTexto] — 6,76:1, o
 *     token que existe exatamente para quando o estado precisa ser **lido** e não
 *     só marcado — com o motivo do servidor abaixo, palavra por palavra.
 *
 * **O botão "entrar no canal" NÃO está aqui, e a recusa é deliberada.** Não há o
 * que chamar: `RadioViewModel` expõe quatro funções públicas — `abrir`, `fechar`,
 * `aoPressionar`, `aoSoltar` — e a entrada acontece uma vez, no `DisposableEffect`
 * do `MainActivity`. Um botão precisaria de uma função nova no ViewModel e de um
 * parâmetro novo ligado no `MainActivity`; sem os dois, seria mais uma capacidade
 * construída, testada e sem chamador — o defeito que este projeto já cometeu seis
 * vezes. E acrescentar reentrada ao rádio é mudança de comportamento, que começa
 * por diff de spec e não por diff de código.
 */
@Composable
private fun EntradaNoCanal(estado: EstadoDoPtt) {
    val (titulo, detalhe, cor) = when (estado) {
        is EstadoDoPtt.Pronto ->
            Triple("Você está no canal", "Rota de áudio aberta. Segure o botão para falar.", Cores.Tinta)
        is EstadoDoPtt.NoAr ->
            Triple("Você está transmitindo", "Solte o botão para devolver o canal.", Cores.Tinta)
        is EstadoDoPtt.Ocupado ->
            Triple("Você está no canal", "${estado.porQuem} detém o piso agora. Meio-duplex: não dá para falar por cima.", Cores.Tinta)
        is EstadoDoPtt.Indisponivel ->
            Triple("Você NÃO está no canal", estado.motivo, Cores.FalhaTexto)
    }
    Column(Modifier.fillMaxWidth().padding(Espaco.Padrao)) {
        Etiqueta("Sua entrada")
        Box(Modifier.height(Espaco.Curto))
        Text(titulo, style = Tipo.Indicativo, color = cor)
        Box(Modifier.height(Espaco.Micro))
        TextoCorpoMenor(detalhe, cor = Cores.TintaMedia)
    }
}

/**
 * Uma linha do livro-razão: indicativo à esquerda, estado à direita.
 *
 * **Sem cor, e é a decisão.** A régua do cabeçalho pinta o ponto de verde porque lá
 * ela é um relance; aqui o agente veio ler, e cor em estado nominal é cor gasta — o
 * censo de hoje levou a tela do mapa de oito pontos de cor a zero no nominal
 * justamente por isso. O que separa os dois estados é a tinta e a palavra escrita,
 * que sobrevivem a daltonismo e a sol.
 *
 * [Regua.LinhaDensa] e não um número: é a altura de linha que este sistema tem para
 * lista longa, e ela ainda cumpre alvo tocável com folga.
 */
@Composable
private fun LinhaDeIntegrante(par: ParPresente) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Regua.LinhaDensa)
            .padding(horizontal = Espaco.Padrao),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextoIndicativo(par.indicativo, cor = if (par.online) Cores.Tinta else Cores.TintaFraca)
        TextoDado(
            when {
                par.falando -> "no ar"
                par.online -> "posição recente"
                // "sem posição recente", e não "offline": o vocabulário do painel
                // é um só, e "recente" está definido em minutos logo abaixo.
                else -> "sem posição recente"
            },
            cor = if (par.online) Cores.TintaMedia else Cores.TintaFraca,
        )
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
                item.faixaHoraria?.let { SeparadorDeHora(it) }
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
 * Separador de faixa horária.
 *
 * Um fio com a hora no meio — a mesma peça que qualquer log usa. Existe porque o
 * carimbo por linha responde "quando foi esta fala" mas não responde "quanto tempo
 * se passou", e num turno de oito horas essa é a pergunta.
 */
@Composable
private fun SeparadorDeHora(rotulo: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Espaco.Padrao, vertical = Espaco.Padrao),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Cores.Traco))
        Box(Modifier.width(Espaco.Medio))
        Etiqueta(rotulo, cor = Cores.TintaFraca)
        Box(Modifier.width(Espaco.Medio))
        Box(Modifier.weight(1f).height(1.dp).background(Cores.Traco))
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
                LinhaDeProcedencia(AUTOR_NAO_CONFIRMADO)
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
    val marca = when (item.rotuloDeEntrega) {
        // "não saiu" e não "na fila": não há fila. Ver o KDoc de
        // `FalaNoGrupo.Entrega`.
        RotuloDeEntrega.ENVIADA -> "enviada"
        RotuloDeEntrega.NAO_SAIU -> "não saiu"
        null -> null
    }
    val hora = item.fala.hora
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextoDado(
            if (marca == null) hora else "$marca · $hora",
            cor = if (item.rotuloDeEntrega == RotuloDeEntrega.NAO_SAIU) {
                Cores.TintaMedia
            } else {
                Cores.TintaFraca
            },
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
 * **Por onde se fala.**
 *
 * A faixa de baixo de um mensageiro é onde ficam as ações de entrada, e é o que
 * ela é aqui: a consulta ao copiloto e o push-to-talk, na mesma superfície, com um
 * fio separando. Ficam juntas porque são a mesma pergunta do agente — "quero
 * falar" — e porque é onde o polegar chega sem reposicionar a mão.
 *
 * As duas não têm o mesmo peso, e a diferença é deliberada: falar com a guarnição
 * é a ação de maior consequência da tela, e o PTT precisa ser encontrado sem
 * olhar. O copiloto é consulta — importante, nunca urgente do mesmo jeito —, fica
 * acima e é visivelmente menor.
 */
@Composable
private fun BarraDeComposicao(
    estadoDoPtt: EstadoDoPtt,
    aoPressionarPtt: () -> Unit,
    aoSoltarPtt: () -> Unit,
    aoAbrirCopiloto: () -> Unit,
    copilotoOcupado: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(Cores.Painel)) {
        Fio()
        AcaoDoCopiloto(aoAbrirCopiloto, copilotoOcupado)
        BarraDePtt(estadoDoPtt, aoPressionarPtt, aoSoltarPtt)
    }
}

/**
 * A ação do copiloto.
 *
 * Sem cor própria. Cor já significa prioridade e transmissão neste painel, e uma
 * terceira gramática cromática faria as três perderem sentido. O que distingue a
 * linha é a superfície, como no bloco de fala.
 *
 * 48 dp de altura mínima: o agente que toca isto está de óculos, dirigindo, ou em
 * pé numa abordagem. Alvo pequeno aqui vira toque perdido, e toque perdido num
 * copiloto de voz vira o agente olhando para a tela.
 */
@Composable
private fun AcaoDoCopiloto(aoTocar: () -> Unit, ocupado: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .tocavel(habilitado = !ocupado, aoTocar = aoTocar)
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Caixa e não caixa-alta. `PERGUNTAR AO COPILOTO` a 0,16 em de entreletra
        // era o rótulo mais largo da tela — mais largo que qualquer transcrição —
        // para uma ação que é consulta, não urgência. Quando tudo grita, nada
        // grita: a caixa-alta desta tela fica com o cabeçalho, com "P1/P2" e com
        // "ORIGEM NÃO CONFIRMADA".
        Text(
            if (ocupado) "Ouvindo…" else "Perguntar ao copiloto",
            style = Tipo.Acao,
            color = if (ocupado) Cores.Vivo else Cores.TintaMedia,
        )
        // O estado vem do ciclo, não do toque: um rótulo que muda no toque diria
        // "ouvindo" mesmo quando a captura falhou ao abrir.
        TextoDado(if (ocupado) "" else "voz", cor = Cores.TintaFraca)
    }
}

// ── Tradução de token em pixel ───────────────────────────────────────────────

private fun corDaPrioridade(p: Int) = when (p) {
    1 -> Cores.P1
    2 -> Cores.P2
    else -> Cores.P3
}

private fun corDaCalha(token: TokenDeCalha, naoConfirmada: Boolean): Color = when {
    // Prioridade ganha da procedência na COR, e não perde nada com isso: o
    // tracejado continua dizendo que a autoria não fechou. Um P1 não conferido
    // precisa ser vermelho — pode ser um pedido de apoio real.
    token == TokenDeCalha.P1 -> Cores.P1
    token == TokenDeCalha.P2 -> Cores.P2
    token == TokenDeCalha.P3 -> Cores.P3
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
private val CANTO_DO_BLOCO = Espaco.Medio

/**
 * Altura da linha viva do cabeçalho.
 *
 * Fixa, e é o ponto: a linha troca de conteúdo o tempo todo — régua de presença
 * quando o canal está calado, quem está no ar quando não está. Se ela crescesse, o
 * histórico inteiro pularia toda vez que alguém apertasse o botão.
 */
private val ALTURA_DA_LINHA_VIVA = 22.dp
