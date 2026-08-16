package com.claryon.field.ui.telas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.BarraDePtt
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.FaixaDePrioridade
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.TextoCorpo
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import androidx.compose.material3.Text

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
     */
    enum class Entrega { ENVIADA, NAO_SAIU, RECEBIDA }
}

/** Um par no grupo, para a régua de presença do topo. */
data class ParPresente(val indicativo: String, val online: Boolean, val falando: Boolean)

/**
 * **Tela da guarnição — o grupo, o histórico e o botão.**
 *
 * Estrutura de cima para baixo, na ordem em que a informação é urgente: quem está
 * no canal, o que foi dito, e o botão para falar. O botão fica embaixo porque é
 * onde o polegar chega sem reposicionar a mão — e porque uma tela que começa com
 * o controle e termina com o conteúdo faz o agente rolar para encontrar o que
 * perdeu.
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
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ReguaDePresenca(canal, pares)
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

        BotaoDoCopiloto(aoAbrirCopiloto, copilotoOcupado)
        BarraDePtt(estadoDoPtt, aoPressionarPtt, aoSoltarPtt)
    }
}

/**
 * Régua de presença: quem está no canal, em uma linha.
 *
 * Rola horizontalmente em vez de quebrar em grade. Uma guarnição tem 4 a 8
 * pessoas; a lista horizontal mantém a altura fixa e previsível, e o que importa
 * — **quem está falando agora** — vai sempre para o começo.
 */
@Composable
private fun ReguaDePresenca(canal: String, pares: List<ParPresente>) {
    Column(Modifier.fillMaxWidth().background(Cores.Vazio).padding(Espaco.Padrao)) {
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
        Box(Modifier.height(Espaco.Padrao))

        if (pares.isEmpty()) {
            Etiqueta("Ninguém no canal", cor = Cores.TintaFraca)
            return@Column
        }

        // Falando primeiro, online depois, ausentes por último — a ordem em que a
        // informação é útil, não a ordem alfabética do cadastro.
        val ordenados = pares.sortedWith(
            compareByDescending<ParPresente> { it.falando }.thenByDescending { it.online },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Espaco.Padrao),
        ) {
            for (p in ordenados) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PontoDeEstado(
                        cor = when {
                            // `Vivo` e nao `NoAr`: ambar significa "VOCE esta no
                            // ar". Um par transmitindo e presenca ativa, nao voce
                            // comprometendo o canal — e se as duas coisas usarem a
                            // mesma cor, a que importa deixa de saltar.
                            p.falando -> Cores.Vivo
                            p.online -> Cores.Vivo
                            else -> Cores.TintaFraca
                        },
                        pulsando = p.falando,
                    )
                    Box(Modifier.width(Espaco.Curto))
                    TextoDado(
                        p.indicativo,
                        cor = if (p.online) Cores.Tinta else Cores.TintaFraca,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoricoDeFalas(falas: List<FalaNoGrupo>) {
    val estadoLista = rememberLazyListState()

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
}

/**
 * O botão do copiloto.
 *
 * Fica **acima** da barra de PTT e visivelmente menor, e a hierarquia é
 * deliberada: falar com a guarnição é a ação de maior consequência da tela, e o
 * polegar tem de encontrar o PTT sem olhar. O copiloto é consulta — importante,
 * mas nunca urgente do mesmo jeito.
 *
 * Sem cor própria. Cor já significa prioridade e transmissão neste painel, e uma
 * terceira gramática cromática faria as três perderem sentido. O que distingue o
 * botão é a superfície, como no bloco de fala.
 */
@Composable
private fun BotaoDoCopiloto(aoTocar: () -> Unit, ocupado: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cores.Painel)
            .clickable(
                enabled = !ocupado,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = aoTocar,
            )
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
        Modifier.fillMaxWidth().padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
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
 */
@Composable
private fun RegistroDeTrafego(item: ItemDeTrafego) {
    val ehRegistro = item.forma == FormaDoRegistro.REGISTRO_DE_CANAL
    val aDireita = item.forma == FormaDoRegistro.PROPRIO

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Espaco.Medio, vertical = Espaco.Micro)
            .semantics(mergeDescendants = true) { contentDescription = item.leituraEmVoz },
    ) {
        // A margem reservada É o sinal de lado. Quem "limpar" este Box achando que
        // é resíduo mata a lateralidade sem produzir erro de compilação.
        if (aDireita) Box(Modifier.weight(MARGEM_DO_LADO_OPOSTO))

        Column(
            Modifier
                .weight(if (ehRegistro) 1f else 1f - MARGEM_DO_LADO_OPOSTO)
                .background(if (aDireita) Cores.Painel else Cores.Elevado),
        ) {
            if (ehRegistro) {
                BandaDeClassificacao(item.fala.prioridade ?: 3)
            }
            Row(Modifier.padding(horizontal = Espaco.Medio, vertical = Espaco.Curto)) {
                CalhaDoRegistro(item.calha)
                Box(Modifier.width(Espaco.Medio))
                Column(Modifier.weight(1f)) {
                    CabecalhoDoRegistro(item)
                    Box(Modifier.height(Espaco.Micro))
                    // Fala sem transcrição não é fala vazia — é áudio que ninguém
                    // transcreveu ainda (o STT do PTT é fase seguinte). Um balão
                    // em branco faria o agente procurar o texto que sumiu; dizer
                    // o que houve custa uma linha e não mente.
                    if (item.fala.texto.isBlank()) {
                        Etiqueta("áudio sem transcrição", cor = Cores.TintaFraca)
                    } else {
                        TextoCorpo(item.fala.texto, cor = tinta(item.tintaDoTexto))
                    }
                    item.rotuloDeEntrega?.let {
                        Box(Modifier.height(Espaco.Micro))
                        // `Cores.TintaFraca` e não `P2`: cor já significa
                        // prioridade neste painel, e estado de entrega não é
                        // prioridade. Uma terceira gramática cromática faria as
                        // três perderem sentido.
                        Etiqueta(
                            // "não saiu" e não "na fila": não há fila. Ver o
                            // KDoc de `FalaNoGrupo.Entrega`.
                            if (it == RotuloDeEntrega.ENVIADA) "enviada" else "não saiu",
                            cor = Cores.TintaFraca,
                        )
                    }
                }
            }
        }

        if (!aDireita && !ehRegistro) Box(Modifier.weight(MARGEM_DO_LADO_OPOSTO))
    }
}

/** Indicativo e hora. O indicativo some em sequência do mesmo par; a hora, nunca. */
@Composable
private fun CabecalhoDoRegistro(item: ItemDeTrafego) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.mostraIndicativo) {
            TextoIndicativo(
                item.fala.indicativo,
                cor = if (item.forma == FormaDoRegistro.PROPRIO) Cores.TintaMedia else Cores.Tinta,
            )
            Box(Modifier.width(Espaco.Curto))
        }
        // A hora fica em TODAS as linhas, inclusive em continuação: é ela que faz
        // o histórico servir de log, e log sem hora é conversa.
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

/** Calha vertical. Reforço do lado, não o sinal — ver o KDoc de `RegistroDeTrafego`. */
@Composable
private fun CalhaDoRegistro(token: TokenDeCalha) {
    val largura = when (token) {
        TokenDeCalha.P1 -> 4.dp
        TokenDeCalha.P2 -> 3.dp
        TokenDeCalha.P3 -> 2.dp
        else -> 2.dp
    }
    val cor = when (token) {
        TokenDeCalha.P1 -> Cores.P1
        TokenDeCalha.P2 -> Cores.P2
        TokenDeCalha.P3 -> Cores.P3
        TokenDeCalha.TRACO -> Cores.Traco
        TokenDeCalha.TRACO_FORTE -> Cores.TracoForte
    }
    Box(Modifier.width(largura).fillMaxHeightDaLinha().background(cor))
}

private fun corDaPrioridade(p: Int) = when (p) {
    1 -> Cores.P1
    2 -> Cores.P2
    else -> Cores.P3
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
 * Altura da linha para as faixas laterais.
 *
 * `IntrinsicSize` seria o caminho idiomático, mas custa uma medida extra por
 * item numa lista que rola — e a faixa só precisa acompanhar o texto. Altura
 * mínima fixa resolve com uma fração do custo.
 */
private fun Modifier.fillMaxHeightDaLinha() = this.height(44.dp)
