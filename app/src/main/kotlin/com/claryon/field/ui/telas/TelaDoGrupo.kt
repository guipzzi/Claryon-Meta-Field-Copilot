package com.claryon.field.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.TextoIndicativo
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Regua
import com.claryon.field.ui.tema.Tipo

/**
 * **A página do grupo.** Abre pelo nome da guarnição, no cabeçalho da conversa.
 *
 * É onde o mensageiro põe foto, descrição e a lista de participantes. Aqui há a
 * lista, e ela é a parte que este produto pode sustentar; as outras duas foram
 * recusadas por ausência de fonte, e a recusa está escrita abaixo porque um
 * "não existe" que não é registrado volta como pedido daqui a dois meses.
 *
 * ---
 * ### O que esta página NÃO mostra, e por quê
 *
 * **Foto do grupo.** `talk_groups` é `id, unit_id, nome, tipo` (`0001:36-41`) mais
 * `rotulo_falado` (`0011`). Um `grep` de `foto|avatar|imagem|photo` nas 23
 * migrações devolve zero, e não há bucket de Storage para grupo. No lugar dela vai
 * `MarcaDoGrupo` — o **nome desenhado**, determinístico —, que faz o trabalho
 * visual da foto sem fingir que existe uma.
 *
 * **Descrição do grupo.** Mesma tabela, mesma ausência: `grep` de `descricao`
 * devolve zero nas 23 migrações. Um campo de descrição aqui seria um retângulo
 * vazio pedindo para ser preenchido com invenção.
 *
 * **Foto de integrante.** Por dois motivos independentes: não há coluna em
 * `agents`, e base biométrica é proibição dura deste produto — sem versão e sem
 * flag. O indicativo é a identidade; é assim que o agente existe no rádio.
 *
 * **"Visto por último".** Não há quem produza o dado. O que existe é a idade da
 * **posição**, e é ela que a página escreve — com a diferença dita por extenso,
 * porque as duas coisas levam a decisões diferentes.
 *
 * ---
 * ### O que mudou: o denominador
 *
 * A versão anterior desta página listava `posicoes_do_grupo`, que faz `join` com
 * `agent_positions` — **quem nunca publicou posição não aparecia, nem como
 * ausente**. O próprio KDoc admitia: *"Ela é menor que a guarnição, e não sabe
 * dizer quanto menor"*.
 *
 * Agora a lista vem de `cadastro_do_grupo` (`0013`), que devolve a `memberships`
 * inteira e **já era buscada a cada dez segundos** por este aplicativo. A posição
 * virou o que ela sempre foi: a **idade** de cada linha, não a existência dela.
 *
 * ---
 * ### E o botão que esta página tinha recusado
 *
 * O KDoc de `EntradaNoCanal` dizia: *"O botão «entrar no canal» NÃO está aqui, e a
 * recusa é deliberada … acrescentar reentrada ao rádio é mudança de comportamento,
 * que começa por diff de spec e não por diff de código"*. A spec existe
 * (`specs/guarnicao-como-grupo.spec.md`), as duas funções existem
 * (`entrarNaEscuta`, `sairDaEscuta`), e o parâmetro está ligado no `MainActivity`.
 * A recusa cumpriu o papel dela: segurou a feature até ela ter tomada.
 */
@Composable
fun TelaDoGrupo(
    canal: CanalCorrente,
    guarnicao: Guarnicao,
    escuta: Escuta,
    aoEntrarNaEscuta: () -> Unit,
    aoSairDaEscuta: () -> Unit,
    aoFechar: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Cores.Vazio)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Espaco.Curto, vertical = Espaco.Curto),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotaoDeVoltar(aoFechar)
            Box(Modifier.width(Espaco.Curto))
            Etiqueta("Guarnição")
        }
        Fio()

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            IdentidadeDoGrupo(canal)
            Fio()
            SuaEntrada(escuta, aoEntrarNaEscuta, aoSairDaEscuta)
            Fio()
            QuemEDaGuarnicao(guarnicao)
            Fio()
            OQueEstaListaE()
            // O respiro do fim da rolagem. `Bloco` é o teto da escala, e é o único
            // lugar em que ele cabe: separa a última seção da borda do aparelho.
            Box(Modifier.height(Espaco.Bloco))
        }
    }
}

/**
 * A abertura: marca, nome e o rótulo pelo qual o canal se abre por voz.
 *
 * **O rótulo falado é dado, e sai em mono.** É a string que o agente diz em voz
 * alta e que o léxico casa caractere a caractere depois de normalizar — a única
 * coisa desta página que se compara. O nome, logo acima, é o rótulo da tela e sai
 * em sans; a divisão é a que `Tipo` define, aplicada a duas strings que estão a
 * quatro pixels uma da outra.
 *
 * **Nome não confirmado é marcado.** `CanaisDoAgente.canalConfirmadoPeloServidor`
 * existe desde a `0011` com esta finalidade escrita — *"quem exibe estado precisa
 * poder dizer isso em vez de mostrar um nome de guarnição como se fosse fato
 * confirmado"* — e não tinha um único chamador. Enquanto o léxico não responde, o
 * nome na tela é o da constante `CanalDoPiloto`, e um agente de outra lotação
 * leria "GTA-3 Alfa" como se fosse a guarnição dele.
 */
@Composable
private fun IdentidadeDoGrupo(canal: CanalCorrente) {
    Column(
        Modifier.fillMaxWidth().padding(Espaco.Padrao),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarcaDoGrupo(canal.nome, tamanho = MARCA_NA_PAGINA)
        Box(Modifier.height(Espaco.Medio))
        Text(
            canal.nome,
            style = Tipo.Titulo.copy(fontWeight = FontWeight.SemiBold),
            color = Cores.Tinta,
        )
        Box(Modifier.height(Espaco.Micro))
        if (!canal.confirmadoPeloServidor) {
            TextoCorpoMenor(
                "Nome provisório: o cadastro do servidor ainda não respondeu.",
                cor = Cores.TintaMedia,
            )
        }
    }
}

/**
 * **Você está no canal, ou não está — e a causa vem junto, com o botão.**
 *
 * O estado sai inteiro de `EstadoDoPtt`, traduzido por [escutaDoCanal]: `Pronto` só
 * existe com rota de áudio e transporte conectado, e `Indisponivel` carrega o
 * motivo — inclusive o `Unauthorized` do canal privado (`0012`), que
 * `ProtocoloRealtime` traduz em `CanalRecusado` e o `RadioViewModel` reescreve como
 * *"Canal negado. …"*.
 *
 * Três regras do produto, obedecidas por construção:
 *
 *  1. **Nada de "entrou" antes do servidor confirmar.** Este bloco não tem estado
 *     próprio e não anima nada: ele **lê**. Não existe "conectando" porque não
 *     existe dado de "conectando" — este projeto já mandou 168 quadros para um
 *     canal em que não tinha entrado, com o indicador aceso, e a lição virou a
 *     ausência de `PisoPendente` em `Movimento`.
 *  2. **A recusa é visível**, em [Cores.FalhaTexto] (6,76:1 — o token que existe
 *     exatamente para quando o estado precisa ser **lido** e não só marcado), com
 *     o motivo do servidor palavra por palavra.
 *  3. **Sair não é erro.** Sai em [Cores.TintaMedia], com a consequência escrita —
 *     ver [Escuta]. Um agente que vê vermelho por decisão própria aprende a
 *     ignorar o vermelho.
 *
 * O rótulo do botão é a **ação**, e o texto acima é o **fato**. Um botão que dissesse
 * o estado seria um interruptor sem direção; um fato que dissesse a ação seria a
 * tela reportando o pedido em vez do que aconteceu.
 */
@Composable
private fun SuaEntrada(
    escuta: Escuta,
    aoEntrarNaEscuta: () -> Unit,
    aoSairDaEscuta: () -> Unit,
) {
    val naEscuta = escuta.token == TokenDeEscuta.NA_ESCUTA
    val titulo = if (naEscuta) "Você está no canal" else "Você NÃO está no canal"
    val cor = when (escuta.token) {
        TokenDeEscuta.NA_ESCUTA -> Cores.Tinta
        TokenDeEscuta.FORA_POR_VONTADE -> Cores.TintaMedia
        TokenDeEscuta.RECUSADO -> Cores.FalhaTexto
    }
    Column(Modifier.fillMaxWidth().padding(Espaco.Padrao)) {
        Etiqueta("Sua entrada")
        Box(Modifier.height(Espaco.Curto))
        Text(titulo, style = Tipo.Indicativo, color = cor)
        escuta.detalhe?.let {
            Box(Modifier.height(Espaco.Micro))
            TextoCorpoMenor(it, cor = Cores.TintaMedia)
        }
        if (naEscuta) {
            Box(Modifier.height(Espaco.Micro))
            TextoCorpoMenor(
                "Rota de áudio aberta. Segure o botão para falar.",
                cor = Cores.TintaMedia,
            )
        }
        Box(Modifier.height(Espaco.Padrao))
        BotaoTatico(
            rotulo = if (naEscuta) "Sair da escuta" else "Entrar na escuta",
            aoTocar = if (naEscuta) aoSairDaEscuta else aoEntrarNaEscuta,
            // Sair do canal da guarnição no meio de um turno é ação de
            // consequência; entrar não é. `destrutivo` é o que dá ao rótulo a cor
            // de falha e tira o preenchimento — o vocabulário que "Encerrar turno"
            // já usa nesta interface.
            destrutivo = naEscuta,
        )
    }
}

/**
 * **Quem é da guarnição.** O cadastro completo, com a idade de quem publicou.
 *
 * A contagem escreve a palavra junto do número — `"4 na guarnição · 2 com
 * posição"` — e nunca um `2/4` cru: fração crua o olho lê como *"dois dos quatro
 * estão aí"*, e o que a segunda metade diz é outra coisa. Ver [resumoDaGuarnicao].
 */
@Composable
private fun QuemEDaGuarnicao(guarnicao: Guarnicao) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Espaco.Padrao)) {
            Etiqueta("Quem é da guarnição")
            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(
                resumoDaGuarnicao(guarnicao.membros, guarnicao.cadastroCarregado),
                cor = Cores.TintaMedia,
            )
        }

        if (guarnicao.membros.isEmpty()) {
            Vazio(
                etiqueta = if (guarnicao.cadastroCarregado) "Guarnição vazia" else "Sem cadastro",
                explicacao = if (guarnicao.cadastroCarregado) {
                    "O cadastro deste grupo não tem membros."
                } else {
                    "O cadastro do grupo não respondeu. Pode ser este aparelho sem " +
                        "rede, ou a sessão vencida."
                },
            )
        } else {
            for (m in ordenarMembros(guarnicao.membros)) {
                LinhaDeMembro(m)
            }
        }
    }
}

/**
 * Uma linha do livro-razão: indicativo à esquerda, idade da posição à direita.
 *
 * **Sem cor, e é a decisão.** A paleta diz que cor em elemento permanente é cor
 * que deixou de significar, e um par com posição o turno inteiro é permanente.
 * O que separa os estados aqui é a tinta e a palavra escrita, que sobrevivem a
 * daltonismo e a sol — e esta página é onde o agente vem **ler**, não relancear.
 *
 * **"no ar" é a exceção, e é a única.** É estado excepcional e verdadeiro no
 * instante — os dois lados do critério da paleta —, e some sozinho quando o piso
 * cai. Ainda assim sai em tinta, não em cor: o âmbar significa *"você* está no ar",
 * e um par transmitindo não é você comprometendo o canal.
 *
 * [Regua.LinhaDensa] e não um número: é a altura de linha que este sistema tem para
 * lista longa, e ela ainda cumpre alvo tocável com folga.
 */
@Composable
private fun LinhaDeMembro(membro: MembroDaGuarnicao) {
    val estado = if (membro.falando) "no ar" else idadeLegivel(membro.idadeDaPosicaoS)
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Regua.LinhaDensa)
            .padding(horizontal = Espaco.Padrao)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(membro.indicativo)
                    if (membro.proprio) append(", você")
                    append(". ")
                    append(if (membro.falando) "Transmitindo agora" else "Posição $estado")
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TextoIndicativo(
                membro.indicativo,
                cor = if (membro.temPosicao || membro.falando) Cores.Tinta else Cores.TintaFraca,
            )
            if (membro.proprio) {
                Box(Modifier.width(Espaco.Curto))
                // Em prosa e não em etiqueta: `VOCÊ` a 0,16 em de entreletra
                // gritaria mais que o indicativo que ele qualifica, e num
                // mensageiro esta marca é a coisa mais discreta da lista.
                TextoCorpoMenor("você", cor = Cores.TintaFraca)
            }
        }
        Box(Modifier.width(Espaco.Curto))
        TextoDado(
            estado,
            cor = if (membro.temPosicao || membro.falando) Cores.TintaMedia else Cores.TintaFraca,
        )
    }
}

/**
 * O parágrafo que impede a lista de ser lida como outra coisa.
 *
 * Duas leituras erradas são plausíveis e as duas levam a decisão de deslocamento:
 * *"sem posição"* lido como *"sumiu"*, e a idade lida como *"visto por último"*. As
 * frases moram em `CadastroDaGuarnicao.kt`, onde um teste as alcança — rótulo que
 * afirma dado inexistente já foi removido três vezes deste produto, e todas as três
 * vezes ele estava dentro de um `@Composable`, fora do alcance de qualquer teste.
 */
@Composable
private fun OQueEstaListaE() {
    Column(Modifier.fillMaxWidth().padding(Espaco.Padrao)) {
        Etiqueta("O que esta lista é")
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(O_QUE_A_IDADE_E, cor = Cores.TintaFraca)
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(O_QUE_E_SEM_POSICAO, cor = Cores.TintaFraca)
    }
}
