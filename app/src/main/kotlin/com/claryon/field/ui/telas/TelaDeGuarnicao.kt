package com.claryon.field.ui.telas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
    /** Entregue ≠ enfileirado. O agente precisa saber a diferença. */
    enum class Entrega { ENVIADA, ENFILEIRADA, RECEBIDA }
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
                            p.falando -> Cores.NoAr
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

    // Rola para o fim quando chega fala nova. `falas.size` como chave, e não a
    // lista inteira: mudar o estado de entrega de uma fala já visível não deve
    // arrastar a tela debaixo do dedo de quem está lendo o histórico.
    LaunchedEffect(falas.size) {
        if (falas.isNotEmpty()) estadoLista.animateScrollToItem(falas.lastIndex)
    }

    LazyColumn(
        state = estadoLista,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Espaco.Curto),
    ) {
        items(falas, key = { it.id }) { fala -> LinhaDeFala(fala) }
    }
}

/**
 * Uma linha do histórico.
 *
 * Não há bolha de conversa, e isso é deliberado: bolha alinhada à direita para o
 * "eu" é gramática de aplicativo social. Aqui todas as falas são do mesmo tipo —
 * tráfego de rádio — e a distinção que importa não é quem falou, é **o que foi
 * dito e quando**. A própria fala se identifica por um fio à esquerda, discreto.
 */
@Composable
private fun LinhaDeFala(fala: FalaNoGrupo) {
    var visivel by remember { mutableStateOf(false) }
    LaunchedEffect(fala.id) { visivel = true }

    AnimatedVisibility(
        visible = visivel,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = Espaco.Curto)) {
            // Faixa de prioridade quando é alerta; fio neutro quando é a própria
            // fala; nada quando é conversa recebida.
            when {
                fala.prioridade != null -> FaixaDePrioridade(
                    fala.prioridade,
                    Modifier.fillMaxHeightDaLinha(),
                )
                fala.propria -> Box(
                    Modifier.width(2.dp).fillMaxHeightDaLinha().background(Cores.TracoForte),
                )
                else -> Box(Modifier.width(2.dp))
            }

            Column(Modifier.padding(start = Espaco.Medio, end = Espaco.Padrao)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextoIndicativo(
                        fala.indicativo,
                        cor = if (fala.propria) Cores.TintaMedia else Cores.Tinta,
                    )
                    Box(Modifier.width(Espaco.Curto))
                    TextoDado(fala.hora, cor = Cores.TintaFraca)
                    if (fala.entrega == FalaNoGrupo.Entrega.ENFILEIRADA) {
                        Box(Modifier.width(Espaco.Curto))
                        // Enfileirada não é enviada. Dizer só "enviada" faria o
                        // agente contar com uma transmissão que ninguém ouviu.
                        Etiqueta("na fila", cor = Cores.P2)
                    }
                }
                Box(Modifier.height(Espaco.Micro))
                TextoCorpo(
                    fala.texto,
                    cor = if (fala.propria) Cores.TintaMedia else Cores.Tinta,
                )
            }
        }
    }
}

/**
 * Altura da linha para as faixas laterais.
 *
 * `IntrinsicSize` seria o caminho idiomático, mas custa uma medida extra por
 * item numa lista que rola — e a faixa só precisa acompanhar o texto. Altura
 * mínima fixa resolve com uma fração do custo.
 */
private fun Modifier.fillMaxHeightDaLinha() = this.height(44.dp)
