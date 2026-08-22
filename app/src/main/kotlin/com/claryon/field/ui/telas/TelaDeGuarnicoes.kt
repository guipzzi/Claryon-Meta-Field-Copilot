package com.claryon.field.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Regua
import com.claryon.field.ui.tema.Tipo
import kotlinx.coroutines.launch

/**
 * **As guarnições em que este agente pode entrar.**
 *
 * É a lista de conversas de um mensageiro, e ela era **fora de escopo** por escrito
 * em `specs/chat.spec.md`: *"O app tem um talk group só … Uma lista de um item é
 * cerimônia"*. Deixou de ser: `meus_rotulos_falados()` (`0011`) devolve os grupos
 * do agente desde a Fase 2, `CanaisDoAgente.grupos` os expõe com o comentário
 * *"Exposto para a tela"* — e tinha **zero chamadores**, `grep` devolvendo só a
 * própria definição. Esta tela é o chamador.
 *
 * ---
 * ### O recorte que a lista declara sobre si mesma
 *
 * `meus_rotulos_falados()` filtra `rotulo_falado is not null`, e nulo ali significa
 * *"não endereçável por voz"* — **não** *"não existe"*. Uma guarnição do agente sem
 * rótulo falado existe, funciona, e não aparece aqui.
 *
 * Uma lista que se apresentasse como completa sem ser é o mesmo defeito que a
 * contagem de presença tinha noutro canto desta tela. Por isso [RECORTE_DA_LISTA]
 * é texto fixo, mora no arquivo puro, e um teste o alcança.
 *
 * ---
 * ### Entrar usa o caminho da voz, e isso não é economia
 *
 * `CanaisDoAgente.trocar(rotuloFalado)` e não `RadioTatico.trocarDeGrupo(id)`.
 * Uma segunda porta até o socket seriam duas verdades sobre em que grupo estamos —
 * exatamente o que `CanaisDoAgente` existe para impedir. De quebra, o toque herda
 * `PoliticaDeTrocaDeGrupo` inteira, inclusive a guarda que **recusa trocar durante
 * uma transmissão**: sem ela, tocar aqui com o dedo no PTT derrubaria receptor,
 * transporte e buffer de jitter no meio da própria fala.
 *
 * ---
 * ### Nada de otimismo, e nada de "conectando"
 *
 * A troca **suspende até o rádio responder**. Enquanto ela corre, a linha tocada
 * fica desabilitada e nenhuma linha muda de estado: a guarnição alvo só é desenhada
 * como corrente quando `Entrou` volta. É a mesma regra dos 168 quadros, aplicada à
 * navegação — e é por isso que não existe um estado visual de "entrando" aqui.
 *
 * A recusa aparece **na linha tocada**, não num aviso global: é ali que o agente
 * está olhando, e é dali que ele decide se tenta outra guarnição ou se solta o
 * botão primeiro.
 */
@Composable
fun TelaDeGuarnicoes(
    /** `null` = o léxico não carregou. Vazio ≠ nulo — ver o corpo. */
    guarnicoes: List<GuarnicaoNaLista>?,
    aoEntrarEm: suspend (String) -> ResultadoDaTroca,
    aoFechar: () -> Unit,
) {
    val escopo = rememberCoroutineScope()
    // O rótulo que está sendo trocado, e a recusa da última tentativa. Estado
    // local: nada disto sobrevive à tela, e nada disto o rádio precisa saber.
    var trocando by remember { mutableStateOf<String?>(null) }
    var recusa by remember { mutableStateOf<Pair<String, RecusaDaTroca>?>(null) }

    Column(Modifier.fillMaxSize().background(Cores.Vazio)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Espaco.Curto, vertical = Espaco.Curto),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotaoDeVoltar(aoFechar)
            Box(Modifier.width(Espaco.Curto))
            Etiqueta("Guarnições")
        }
        Fio()

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                // **Nulo e vazio pedem recuperações diferentes**, e é por isso que
                // são dois ramos. Lista ausente manda entrar de novo; lista vazia
                // manda procurar o cadastro da corporação. Colapsar os dois faria o
                // agente tentar a coisa errada em metade dos casos — e é a mesma
                // distinção que `CanaisDoAgente.carregar` já faz do lado da rede.
                guarnicoes == null -> Vazio(
                    etiqueta = "Lista não carregada",
                    explicacao = "As guarnições vêm do cadastro e ainda não chegaram. " +
                        "Pode ser este aparelho sem rede, ou a sessão vencida — entre de novo.",
                )

                guarnicoes.isEmpty() -> Vazio(
                    etiqueta = "Sem guarnição",
                    explicacao = "O cadastro não põe você em nenhuma guarnição " +
                        "endereçável. Isso é lotação, e se resolve na corporação.",
                )

                else -> {
                    for (g in guarnicoes) {
                        LinhaDeGuarnicao(
                            guarnicao = g,
                            // Toda a lista trava durante uma troca: `trocarDeGrupo`
                            // derruba socket e buffer, e dois pedidos sobrepostos
                            // deixariam o rádio num grupo e o estado noutro.
                            habilitada = trocando == null,
                            trocandoEsta = trocando == g.rotuloFalado,
                            recusa = recusa?.takeIf { it.first == g.rotuloFalado }?.second,
                            aoTocar = {
                                trocando = g.rotuloFalado
                                recusa = null
                                escopo.launch {
                                    when (val r = aoEntrarEm(g.rotuloFalado)) {
                                        is ResultadoDaTroca.Entrou -> {
                                            trocando = null
                                            aoFechar()
                                        }

                                        is ResultadoDaTroca.Recusada -> {
                                            trocando = null
                                            recusa = g.rotuloFalado to r.causa
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Fio()
            Column(Modifier.padding(Espaco.Padrao)) {
                Etiqueta("O que esta lista é")
                Box(Modifier.height(Espaco.Curto))
                TextoCorpoMenor(RECORTE_DA_LISTA, cor = Cores.TintaFraca)
            }
            Box(Modifier.height(Espaco.Bloco))
        }
    }
}

/**
 * Uma guarnição da lista.
 *
 * Três informações, em três pesos: o **nome** em sans semibold, o **rótulo falado**
 * em mono — é a string que o agente diz em voz alta, e a única desta linha que se
 * compara caractere a caractere —, e a marca de corrente em prosa fraca.
 *
 * **"aqui" e não um ponto colorido.** Um marcador de cor permanente na guarnição
 * corrente seria cor gasta pela definição da paleta, e a palavra escrita é o canal
 * que não depende de visão de cor. A linha corrente também não é tocável: entrar no
 * grupo em que já se está derrubaria receptor, transporte e buffer de jitter por um
 * comando redundante — é a guarda 3 de `PoliticaDeTrocaDeGrupo`, feita visível.
 */
@Composable
private fun LinhaDeGuarnicao(
    guarnicao: GuarnicaoNaLista,
    habilitada: Boolean,
    trocandoEsta: Boolean,
    recusa: RecusaDaTroca?,
    aoTocar: () -> Unit,
) {
    val tocavel = habilitada && !guarnicao.corrente
    val forma = RoundedCornerShape(CANTO_DO_BLOCO)
    // Tinta fraca enquanto a troca corre. É afirmação sobre o CONTROLE — não
    // aceita toque agora —, nunca sobre o canal: não existe estado de "entrando"
    // nesta tela, e desenhar um seria a versão navegável dos 168 quadros.
    val apagada = !habilitada && !trocandoEsta

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Espaco.Curto, vertical = RESPIRO)
            .clip(forma)
            .then(if (tocavel) Modifier.tocavel(forma = forma, aoTocar = aoTocar) else Modifier)
            .padding(horizontal = Espaco.Medio, vertical = Espaco.Medio)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Guarnição ${guarnicao.nome}")
                    if (guarnicao.corrente) append(". Você já está aqui")
                    recusa?.let { append(". ").append(rotuloDaRecusa(it)) }
                    if (tocavel) append(". Tocar entra nesta guarnição")
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = Regua.LinhaDensa),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarcaDoGrupo(guarnicao.nome, tamanho = MARCA_NO_CABECALHO)
            Box(Modifier.width(Espaco.Medio))
            Column(Modifier.weight(1f)) {
                Text(
                    guarnicao.nome,
                    style = Tipo.Corpo.copy(fontWeight = FontWeight.SemiBold),
                    color = if (apagada) Cores.TintaFraca else Cores.Tinta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.height(RESPIRO))
                TextoDado("«${guarnicao.rotuloFalado}»", cor = Cores.TintaFraca)
            }
            if (guarnicao.corrente) {
                Box(Modifier.width(Espaco.Curto))
                TextoCorpoMenor("aqui", cor = Cores.TintaMedia, maxLinhas = 1)
            }
        }
        recusa?.let {
            Box(Modifier.height(Espaco.Curto))
            // A recusa mora na linha tocada, e não num aviso global: é ali que o
            // agente está olhando, e é dali que sai o gesto seguinte.
            TextoCorpoMenor(rotuloDaRecusa(it), cor = Cores.Tinta)
        }
    }
}
