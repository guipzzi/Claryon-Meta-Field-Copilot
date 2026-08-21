package com.claryon.field.ui.telas

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.BarraDePtt
import com.claryon.field.ui.componentes.BotaoDeIrParaOFim
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.FaixaDeProcedencia
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.TextoCorpo
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.componentes.calha
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.Espaco
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
    // `background` própria e não herdada do casco: uma tela que depende do
    // hospedeiro para ter fundo mostra o cinza do tema do sistema no primeiro
    // lugar em que for composta fora dele — foi o que a vitrine de captura
    // revelou, e num painel escuro isso é a tela inteira queimando visão noturna.
    Column(modifier.fillMaxSize().background(Cores.Vazio)) {
        CabecalhoDaConversa(
            canal = canal,
            pares = pares,
            // Cai para a régua quando o rádio não informa: sem o parâmetro ligado,
            // a tela continua sabendo o que sabia antes, e não inventa nada.
            noAr = quemEstaNoAr ?: pares.firstOrNull { it.falando }?.indicativo,
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
private fun CabecalhoDaConversa(canal: String, pares: List<ParPresente>, noAr: String?) {
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
            Column {
                Etiqueta("Talk group")
                Box(Modifier.height(Espaco.Micro))
                // O canal em corpo grande é a âncora da tela: é a única coisa que
                // o agente confere de relance para saber com quem está falando.
                Text(canal.uppercase(), style = Tipo.IndicativoGrande, color = Cores.Tinta)
            }
            Column(horizontalAlignment = Alignment.End) {
                Etiqueta("No canal")
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
 * viva. Nenhuma é necessária para o lado, e nenhuma está aqui — o bloco é
 * retângulo, raio zero, elevação zero. O lado vem da geometria: alinhamento mais
 * uma margem vazia de 16% do lado oposto.
 *
 * Alerta classificado não recebe lado nenhum: ocupa a linha inteira, como num
 * terminal de despacho. É essa segunda forma que impede a tela de virar bate-papo.
 *
 * **A pilha interna é a de um balão de mensagem, com o topo trocado.** Onde um
 * mensageiro põe a citação da resposta, aqui vai a procedência: a classificação do
 * alerta, e o aviso de autoria não conferida. Depois vêm autor, texto e um rodapé
 * com hora e entrega — a mesma ordem, com conteúdo que existe.
 */
@Composable
private fun RegistroDeTrafego(item: ItemDeTrafego) {
    val ehRegistro = item.forma == FormaDoRegistro.REGISTRO_DE_CANAL
    val aDireita = item.forma == FormaDoRegistro.PROPRIO
    val naoConfirmada = item.procedencia == Procedencia.NAO_CONFIRMADA

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
                .background(if (aDireita) Cores.Painel else Cores.Elevado)
                .calha(
                    cor = corDaCalha(item.calha, naoConfirmada),
                    largura = larguraDaCalha(item.calha, naoConfirmada),
                    tracejada = naoConfirmada,
                ),
        ) {
            if (ehRegistro) {
                BandaDeClassificacao(item.fala.prioridade ?: 3)
            }
            // A faixa vem DEPOIS da classificação e ANTES do nome, porque é nesta
            // ordem que a dúvida precisa chegar: "isto é um P1" → "e não sei de
            // quem" → o nome. Invertido, o agente lê o nome primeiro e o crédito
            // já foi dado.
            if (naoConfirmada) {
                FaixaDeProcedencia(AUTOR_NAO_CONFIRMADO)
            }

            Column(
                Modifier.padding(
                    start = Espaco.Medio,
                    end = Espaco.Medio,
                    top = Espaco.Curto,
                    bottom = Espaco.Curto,
                ),
            ) {
                if (item.mostraIndicativo) {
                    TextoIndicativo(
                        item.autorExibido,
                        cor = if (naoConfirmada) Cores.TintaMedia else Cores.Tinta,
                    )
                    Box(Modifier.height(Espaco.Micro))
                }
                // Fala sem transcrição não é fala vazia — é áudio que ninguém
                // transcreveu ainda. Um balão em branco faria o agente procurar o
                // texto que sumiu; dizer o que houve custa uma linha e não mente.
                if (item.fala.texto.isBlank()) {
                    Etiqueta("áudio sem transcrição", cor = Cores.TintaFraca)
                } else {
                    TextoCorpo(item.fala.texto, cor = tinta(item.tintaDoTexto))
                }
                Box(Modifier.height(Espaco.Curto))
                RodapeDoRegistro(item)
            }
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
 * A hora fica em TODAS as linhas, inclusive em continuação de sequência: é ela que
 * faz o histórico servir de log, e log sem hora é conversa. À direita, os carimbos
 * caem numa coluna — monoespaçada, largura fixa, o olho compara sem reler.
 */
@Composable
private fun RodapeDoRegistro(item: ItemDeTrafego) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.rotuloDeEntrega?.let {
            // `Cores.TintaFraca` e não `P2`: cor já significa prioridade neste
            // painel, e estado de entrega não é prioridade. Uma terceira gramática
            // cromática faria as três perderem sentido.
            Etiqueta(
                // "não saiu" e não "na fila": não há fila. Ver o KDoc de
                // `FalaNoGrupo.Entrega`.
                if (it == RotuloDeEntrega.ENVIADA) "enviada" else "não saiu",
                cor = Cores.TintaFraca,
            )
            Box(Modifier.width(Espaco.Curto))
        }
        TextoDado(item.fala.hora, cor = Cores.TintaFraca)
    }
}

/**
 * Banda de classificação do alerta.
 *
 * Três canais em paralelo — cor, largura e **rótulo escrito**. Hoje P2 e P3 diferem
 * por 1 px de calha, ou seja diferem só por cor: um agente daltônico, ou qualquer
 * um sob sol forte, não distingue. O rótulo é o canal que não depende de visão de
 * cor nem de contraste.
 *
 * O texto sai em `Cores.Tinta` e não na cor da prioridade: P3 sobre `Elevado` rende
 * 4,28:1, abaixo do mínimo de 4,5:1 para texto pequeno. A cor fica na banda e na
 * calha, que são elemento não-textual e respondem a 3:1.
 */
@Composable
private fun BandaDeClassificacao(prioridade: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(corDaPrioridade(prioridade).copy(alpha = 0.14f))
            .padding(horizontal = Espaco.Medio, vertical = Espaco.Micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(10.dp).background(corDaPrioridade(prioridade)))
        Box(Modifier.width(Espaco.Curto))
        Etiqueta(rotuloDePrioridade(prioridade), cor = Cores.Tinta)
    }
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
        Etiqueta(
            if (ocupado) "OUVINDO…" else "PERGUNTAR AO COPILOTO",
            cor = if (ocupado) Cores.Vivo else Cores.TintaMedia,
        )
        // O estado vem do ciclo, não do toque: um rótulo que muda no toque diria
        // "ouvindo" mesmo quando a captura falhou ao abrir.
        Etiqueta(if (ocupado) "" else "voz", cor = Cores.TintaFraca)
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

private fun larguraDaCalha(token: TokenDeCalha, naoConfirmada: Boolean): Dp {
    val base = when (token) {
        TokenDeCalha.P1 -> 4.dp
        TokenDeCalha.P2 -> 3.dp
        else -> 2.dp
    }
    // Tracejado a 2 dp lê como linha contínua. Abaixo de 3 dp o vão some.
    return if (naoConfirmada && base < 3.dp) 3.dp else base
}

@Composable
private fun tinta(token: TokenDeTinta) = when (token) {
    TokenDeTinta.TINTA -> Cores.Tinta
    TokenDeTinta.TINTA_MEDIA -> Cores.TintaMedia
    TokenDeTinta.TINTA_FRACA -> Cores.TintaFraca
}

/** 16% de margem vazia do lado oposto. É o que carrega o lado. */
private const val MARGEM_DO_LADO_OPOSTO = 0.16f

/**
 * Altura da linha viva do cabeçalho.
 *
 * Fixa, e é o ponto: a linha troca de conteúdo o tempo todo — régua de presença
 * quando o canal está calado, quem está no ar quando não está. Se ela crescesse, o
 * histórico inteiro pularia toda vez que alguém apertasse o botão.
 */
private val ALTURA_DA_LINHA_VIVA = 22.dp
