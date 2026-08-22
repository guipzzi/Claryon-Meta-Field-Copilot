package com.claryon.field.ui.telas

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.IconeDeLinha
import com.claryon.field.ui.componentes.IconeTatico
import com.claryon.field.ui.componentes.PilulaDeAcao
import com.claryon.field.ui.componentes.TextoCorpo
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.icones.Icones
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import androidx.compose.material3.Text
import com.claryon.field.permissoes.EstadoDePermissoes
import com.claryon.field.permissoes.PermissaoEssencial
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.permissoes.Recuperacao
import com.claryon.glasses.PermissaoDaCameraDoDat
import com.claryon.glasses.RespostaDePermissao

/**
 * Tela de permissões do onboarding.
 *
 * ## As duas decisões que valem mais que o layout
 *
 *  1. **Nenhuma permissão é pedida no `onCreate`.** A primeira versão do app
 *     disparava o diálogo do sistema no instante em que a Activity nascia, antes
 *     de o agente ler uma linha do que quer que fosse. Um diálogo sem contexto é
 *     um diálogo negado — e negado duas vezes é negado para sempre, o que exige
 *     ir aos ajustes do Android para desfazer. O motivo vem primeiro; o diálogo,
 *     depois do toque.
 *
 *  2. **A tela mostra o que está morto, não o que falta.** "Sem câmera, não leio
 *     placas" diz respeito ao trabalho do agente; "CAMERA: negada" diz respeito
 *     ao Android. O segundo formato é o que faz um usuário concluir que o produto
 *     é ruim.
 *
 * ---
 * ## O que foi consertado em 21/08, e por quê
 *
 * ### Esta era a única tela do aplicativo com ZERO tokens do projeto
 *
 * `grep -c "Cores\.\|Espaco\.\|Tipo\."` devolvia **0** para tudo que não fosse
 * `Cores`. Eram `Card` do Material — com sombra e canto —, `TextStyle` montado
 * inline (`fontSize = 22.sp`, `fontWeight = Bold`) e `.dp` literal fora da grade
 * de 4. O sistema deste produto diz o contrário em cada linha: **fio de 1 px, não
 * caixa**; tipografia vem de `Tipo`; espaçamento vem de `Espaco`. Uma tela que não
 * usa o sistema não é uma tela com estilo próprio — é uma tela que não sabe que o
 * sistema existe, e ela é a **primeira** que o agente vê.
 *
 * Agora cada permissão é uma **linha de livro-razão**: ícone → título → valor à
 * direita, separada da seguinte por um fio que não fecha caixa nenhuma. É a mesma
 * gramática do mapa e do perfil.
 *
 * ### Cinco linhas vermelhas numa tela onde nada tinha falhado
 *
 * A tela abria com *"Sem microfone, o Claryon não funciona."*, *"Sem Bluetooth…"*,
 * *"Sem local…"*, *"Sem câmera…"*, *"Sem notificação…"* e *"Sem esta, o app não
 * abre o ciclo de voz."* — **seis** frases em vermelho, todas ao mesmo tempo, e
 * nenhuma delas verdadeira: nenhuma permissão tinha sido negada, porque nenhuma
 * tinha sido pedida. O agente lia "está tudo quebrado" antes de tocar em nada.
 *
 * O orçamento de cor da paleta é explícito e tem dois lados obrigatórios: cor
 * marca o que é **excepcional** *e* **verdadeiro no instante**. Uma consequência
 * que ainda não aconteceu não é excepcional nem verdadeira — é uma explicação, e
 * explicação é texto.
 *
 * O que decide agora é [jaPedidas]: a mesma persistência que já existia para
 * distinguir "nunca perguntamos" de "negada em definitivo".
 *
 * | situação | valor à direita | cor |
 * |---|---|---|
 * | concedida | `LIBERADO` | `TintaMedia` |
 * | ainda não pedida | `PENDENTE` | `TintaFraca` |
 * | pedida **e** ainda faltando | `NEGADA` | `FalhaTexto` |
 *
 * Na primeira abertura, o censo de cor desta tela é **zero** — conferido no
 * emulador com a preferência apagada (`rm shared_prefs/claryon.permissoes.xml`) e
 * as cinco permissões revogadas, que é exatamente o estado da captura do defeito. O
 * vermelho aparece quando alguém de fato negar, e aí ele é a única cor da tela —
 * que é exatamente o peso que ele deveria ter tido desde o começo.
 *
 * A frase `semEla` não sumiu: ela continua em toda linha, em `TintaFraca`, porque
 * a gramática dela já é condicional ("Sem X, …"). O que ela nunca deveria ter tido
 * é a cor de uma falha que não ocorreu. E a sexta linha vermelha — *"Sem esta, o
 * app não abre o ciclo de voz."* — virou a etiqueta `OBRIGATÓRIA` ao lado do
 * valor: mesma informação, sem repetir a frase que a linha acima já dizia mais
 * forte.
 */
@Composable
fun TelaDePermissoes(aoConcluir: () -> Unit) {
    val context = LocalContext.current

    // `neverEqualPolicy`: o Compose compara `EstadoDePermissoes` por igualdade
    // estrutural, e negar uma permissão produz um estado **idêntico** ao anterior
    // — mesmas listas, mesmos valores. Sem isto não havia recomposição, e o
    // caminho de recuperação nunca era reavaliado: o agente negava duas vezes
    // (definitivo, no Android 11+), o botão continuava "Permitir", e tocá-lo
    // disparava um pedido que o sistema recusa na hora. O sintoma era literalmente
    // "apertei e não aconteceu nada" — o que esta tela existe para evitar.
    var estado by remember {
        mutableStateOf(avaliarAgora(context), policy = neverEqualPolicy())
    }

    // **A permissão de câmera do DAT — a que nunca era pedida.**
    //
    // `null` enquanto a consulta não respondeu. Ela é distinta de
    // `Manifest.permission.CAMERA`: aquela é do celular e sai do diálogo do
    // Android; esta é concedida no app Meta AI, por aparelho. O `catalogo()`
    // acima cobre a primeira e não tem como cobrir a segunda.
    var oculos by remember { mutableStateOf<RespostaDePermissao?>(null) }

    // O agente já respondeu ao diálogo do Meta AI nesta abertura? Negada + já
    // perguntado libera o portão: insistir seria prender o onboarding em cima de
    // uma escolha que o agente acabou de fazer.
    var jaPediuOculos by remember { mutableStateOf(false) }

    // Incrementado a cada volta para a tela; é a chave que refaz a consulta ao
    // DAT depois da ida ao Meta AI.
    var voltas by remember { mutableStateOf(0) }

    // **O conjunto que separa "negada" de "ainda não pedida".**
    //
    // Estado de verdade, e **não** `remember(estado) { context.jaPedidas() }` — que
    // foi a primeira tentativa e não funcionou no aparelho: `EstadoDePermissoes` é
    // `data class`, e depois de uma negativa o novo estado é **estruturalmente
    // igual** ao anterior. É a mesma armadilha que obrigou `neverEqualPolicy` logo
    // acima, e ela morde de novo aqui, num lugar em que a política de estado não
    // ajuda: `remember(chave)` compara a chave com `==`, então a chave nunca muda e
    // o bloco nunca reexecuta. Medido: as cinco permissões negadas no emulador,
    // `shared_prefs/claryon.permissoes.xml` com os cinco nomes gravados, e a tela
    // continuava dizendo `PENDENTE` nas cinco linhas.
    //
    // O mesmo defeito estava, silencioso, em `podePedir` — ver o comentário lá.
    var pedidas by remember { mutableStateOf(context.jaPedidas()) }

    // Conceder pelos ajustes do Android **não reinicia o processo** (só revogar
    // reinicia). Sem observar o retorno, a tela continuaria mostrando tudo negado
    // depois de o agente ter liberado tudo.
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                estado = avaliarAgora(context)
                pedidas = context.jaPedidas()
                voltas++
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose { dono.lifecycle.removeObserver(observador) }
    }

    // A consulta é suspensa (a resposta vem dos óculos por Bluetooth) e tem teto
    // próprio dentro de `PermissaoDaCameraDoDat`, para não prender a abertura.
    LaunchedEffect(voltas) {
        oculos = PermissaoDaCameraDoDat.status()
    }

    val pedidoDosOculos = rememberLauncherForActivityResult(PermissaoDaCameraDoDat.Contrato()) {
        oculos = it
        jaPediuOculos = true
    }

    val pedido = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        // Reavalia pelo PackageManager, não pelo mapa que o launcher devolve: o
        // usuário pode ter concedido pelos ajustes numa ida e volta anterior, e
        // o resultado do diálogo não sabe disso.
        estado = avaliarAgora(context)
        pedidas = context.jaPedidas()
    }

    // **Um só lugar decide que o onboarding acabou.**
    //
    // Antes eram três (`ON_RESUME`, o retorno do diálogo e os botões), e
    // acrescentar a condição dos óculos aos três significaria esquecê-la num
    // deles. O portão dos óculos NÃO bloqueia: sem óculos por perto a resposta é
    // `Indisponivel` e a tela segue igual a antes. Ele só segura a abertura no
    // caso em que segurar é a coisa certa — óculos presentes, permissão negada,
    // e o agente ainda não respondeu ao diálogo do Meta AI. Este é o instante
    // que o roadmap chama de "a única janela".
    val podeConcluir = estado.tudoConcedido &&
        (oculos is RespostaDePermissao.Concedida ||
            oculos is RespostaDePermissao.Indisponivel ||
            jaPediuOculos)

    LaunchedEffect(podeConcluir) {
        if (podeConcluir) aoConcluir()
    }

    // Lido uma vez por composição, não a cada recomposição: `SharedPreferences`
    // na main thread dentro do corpo do composable rodava a cada quadro.
    //
    // **A chave era `estado`, e `estado` nunca muda de valor** — `data class` com
    // conteúdo idêntico depois de uma negativa. O fecho devolvido por
    // `podePedirDeNovo` fotografa `jaPedidas` na hora em que é criado, então ele
    // congelava no conjunto vazio da primeira composição e `Recuperacao` nunca
    // chegava a `AbrirAjustes`: o botão continuaria dizendo "Permitir" para sempre,
    // abrindo um diálogo que o Android não mostra mais — que é **exatamente** o
    // sintoma que o comentário do `registrarPedido`, trinta linhas abaixo, diz que
    // este código existe para evitar. Defeito anterior a esta sessão, encontrado
    // porque a decisão de cor passou a depender do mesmo dado.
    //
    // [pedidas] é a chave certa: ela muda de conteúdo quando um pedido é feito, e
    // [voltas] cobre a volta dos ajustes do Android.
    val podePedir = remember(pedidas, voltas) { podePedirDeNovo(context) }

    // **As barras do sistema, e por que a conta é desta tela.**
    //
    // `MainActivity.onCreate` chama `enableEdgeToEdge`, então a janela vai até as
    // bordas físicas — é o que a moldura de "no ar" exige. Quem devolve o espaço
    // das barras é `CascoTatico`, e o casco só embrulha a OPERAÇÃO: esta tela é
    // portão de abertura e é composta fora dele. O título saía por baixo do
    // relógio e dos ícones de privacidade, e o último botão por baixo da barra de
    // gestos.
    //
    // Ficou visível agora porque a barra passou a pedir ícones CLAROS
    // (`SystemBarStyle.dark`, necessário porque o app não segue o modo do
    // sistema). Antes eram ícones escuros sobre fundo escuro: o defeito era o
    // mesmo, e nada era legível o bastante para denunciá-lo.
    //
    // Os dois lados são necessários e nenhum é duplicado: nada aqui passa pelo
    // casco. Quem mexer nesta tela e a mover para dentro do casco tem de tirar o
    // `statusBarsPadding` daqui, senão o título desce duas vezes.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // **Fundo próprio, e não o do hospedeiro.** Mesma correção que
            // `TelaDeGuarnicao` já traz por escrito: tela que depende de quem a
            // compõe para ter fundo mostra o cinza do tema do sistema no primeiro
            // lugar em que for composta fora dele. Aqui não havia hospedeiro
            // nenhum — o preto vinha do `windowBackground`, por acidente.
            .background(Cores.Vazio)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecalho()

        for (p in PermissoesEssenciais.catalogo()) {
            Fio()
            LinhaDePermissao(
                icone = iconeDe(p),
                titulo = p.capacidades.joinToString(", ") { it.rotulo }
                    .replaceFirstChar { it.uppercase() },
                porQue = p.porQue,
                semEla = p.semEla,
                obrigatoria = p.bloqueante,
                estado = when {
                    p !in estado.faltando -> EstadoDaLinha.LIBERADA
                    p.permissao in pedidas -> EstadoDaLinha.NEGADA
                    else -> EstadoDaLinha.PENDENTE
                },
            )
        }

        // **Câmera dos óculos — permissão do DAT, não do Android.**
        //
        // Fica depois das do sistema de propósito: é a única que sai do app, e
        // pedir a que abre outro aplicativo antes das locais gasta a paciência do
        // agente na mais cara.
        Fio()
        LinhaDePermissao(
            icone = Icones.Oculos,
            titulo = "Câmera dos óculos",
            porQue = "É por ela que o app enxerga pela câmera dos óculos. " +
                "Quem libera é o app Meta AI, não o Android.",
            // A causa, não um "negada" genérico: "óculos desconectados" pede que o
            // agente ligue os óculos; "negada" pediria que ele mudasse de ideia.
            // Mandar consertar a coisa errada é o modo de falha que esta tela
            // existe para evitar.
            semEla = when (val o = oculos) {
                null, is RespostaDePermissao.Concedida -> null
                is RespostaDePermissao.Negada -> "Sem ela, não enxergo pelos óculos."
                is RespostaDePermissao.Indisponivel -> o.causa.frase
            },
            obrigatoria = false,
            estado = when (oculos) {
                null -> EstadoDaLinha.CONSULTANDO
                is RespostaDePermissao.Concedida -> EstadoDaLinha.LIBERADA
                // `Negada` é o único caso em que alguém de fato disse não. Óculos
                // ausente ou desconectado é ausência de resposta, não recusa — e
                // pintá-la de vermelho seria a mesma mentira que esta sessão
                // desfez nas cinco linhas de cima.
                is RespostaDePermissao.Negada -> EstadoDaLinha.NEGADA
                else -> EstadoDaLinha.PENDENTE
            },
            acao = if (oculos !is RespostaDePermissao.Concedida) {
                {
                    PilulaDeAcao(
                        rotulo = "Liberar no Meta AI",
                        icone = Icones.Externo,
                        // **O chamador que faltava.** Sem este toque, nada no
                        // aplicativo jamais pede a permissão de câmera do DAT.
                        aoTocar = {
                            // `pedir` devolve resposta quando nem deu para pedir
                            // (Meta AI ausente ⇒ `ActivityNotFoundException` no
                            // deeplink). Sem isso, o toque não faria nada e o
                            // sintoma seria "apertei e não aconteceu nada" — o
                            // mesmo que o resto desta tela evita.
                            PermissaoDaCameraDoDat.pedir(pedidoDosOculos)?.let {
                                oculos = it
                                jaPediuOculos = true
                            }
                        },
                    )
                }
            } else {
                null
            },
        )

        Fio()
        Box(Modifier.height(Espaco.Secao))

        Column(Modifier.padding(horizontal = Espaco.Largo)) {
            when (val r = PermissoesEssenciais.recuperacao(estado, podePedir)) {
                is Recuperacao.Pedir -> BotaoTatico(
                    rotulo = "Permitir",
                    icone = Icones.Destravar,
                    aoTocar = {
                        // Registrar ANTES de lançar. É este registro que faz
                        // `shouldShowRequestPermissionRationale == false` significar
                        // "negada em definitivo" em vez de "nunca perguntamos" — o
                        // Android devolve `false` nos dois casos e não distingue.
                        // Sem isso, o botão "Permitir" continuaria aparecendo para
                        // sempre, abrindo um diálogo que o sistema não mostra mais.
                        //
                        // É o mesmo registro que decide o vermelho das linhas: uma
                        // permissão só pode aparecer como NEGADA depois de passar
                        // por aqui.
                        context.registrarPedido(r.permissoes)
                        pedidas = context.jaPedidas()
                        pedido.launch(r.permissoes.toTypedArray())
                    },
                )

                is Recuperacao.AbrirAjustes -> Column {
                    // Sem esta explicação, o botão "Permitir" abriria um diálogo que
                    // o sistema não mostra mais — e o sintoma, para o agente, seria
                    // "apertei e não aconteceu nada".
                    TextoCorpoMenor(
                        "O Android não pergunta mais. É preciso liberar nos ajustes " +
                            "do aplicativo.",
                        cor = Cores.TintaMedia,
                    )
                    Box(Modifier.height(Espaco.Padrao))
                    BotaoTatico(
                        rotulo = "Abrir ajustes",
                        icone = Icones.Ajustes,
                        aoTocar = { context.abrirAjustesDoApp() },
                    )
                }

                Recuperacao.Nada -> BotaoTatico(
                    rotulo = "Continuar",
                    icone = Icones.Entrar,
                    aoTocar = aoConcluir,
                )
            }

            if (!estado.tudoConcedido) {
                Box(Modifier.height(Espaco.Medio))
                // Seguir sem tudo é permitido — menos sem microfone. Bloquear por
                // câmera negada seria o app se recusando a fazer o que ainda sabe
                // fazer.
                //
                // Pílula e não botão: seguir com capacidade morta é a ação de menor
                // peso desta tela, e o peso agora é dito pela forma. Quando o
                // microfone falta ela some — não fica desabilitada —, porque um alvo
                // que nunca vai funcionar é ruído; no lugar dela entra a frase que
                // diz o que ainda impede.
                //
                // **E essa frase obedece à mesma regra das linhas acima.** Na
                // primeira versão desta correção ela saía sempre em `FalhaTexto`, e
                // isso reintroduzia, em escala menor, exatamente o defeito que a
                // sessão inteira estava desfazendo: microfone `PENDENTE` é uma
                // pergunta que ninguém fez ainda, não uma recusa. Vermelho só depois
                // de o agente ter dito não — aí é excepcional **e** verdadeiro.
                if (estado.podeOperar) {
                    PilulaDeAcao(
                        rotulo = "Seguir assim mesmo",
                        icone = Icones.Adiante,
                        aoTocar = aoConcluir,
                    )
                } else {
                    val recusado = PermissoesEssenciais.MICROFONE.permissao in pedidas
                    val cor = if (recusado) Cores.Tinta else Cores.TintaMedia
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Espaco.Medio),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconeTatico(Icones.Microfone, cor = cor)
                        Box(Modifier.width(Espaco.Medio))
                        TextoCorpoMenor(
                            "O microfone é obrigatório para abrir o turno.",
                            cor = cor,
                        )
                    }
                }
            }
        }

        Box(Modifier.height(Espaco.Bloco))
    }
}

/**
 * Cabeçalho da tela.
 *
 * `Tipo.TituloDeTela` e não um `fontSize` inline: caixa-alta com entreletra larga é
 * o gesto de título deste sistema, e o KDoc dele explica por quê — em painel, quem
 * já esteve na tela não lê o título nenhuma vez depois da primeira, reconhece a
 * forma do bloco. Alinhado à esquerda, junto com a prosa: o estilo é centralizado
 * por padrão e centralizar aqui deixaria o título fora do eixo de tudo o que vem
 * abaixo.
 */
@Composable
private fun Cabecalho() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Espaco.Largo,
                end = Espaco.Largo,
                top = Espaco.Secao,
                bottom = Espaco.Largo,
            ),
    ) {
        Etiqueta("Antes de começar", cor = Cores.TintaMedia)
        Box(Modifier.height(Espaco.Medio))
        TextoCorpo(
            "O Claryon trabalha com a tela apagada. Estas permissões são o que " +
                "ele usa — e o que deixa de funcionar sem cada uma.",
            cor = Cores.TintaMedia,
        )
    }
}

/**
 * O estado de uma linha, e **só ele decide se há cor**.
 *
 * [PENDENTE] existe separado de [NEGADA] porque a diferença é a coisa toda: uma é
 * um pedido que ainda não foi feito, a outra é uma recusa. Colapsá-las foi o
 * defeito que pintou cinco linhas de vermelho numa tela em que nada tinha falhado.
 *
 * `internal` e não `private` por um motivo só: `OrcamentoDeCorTest` varre estas
 * quatro entradas e exige que **apenas** [NEGADA] tenha croma. É o contra-teste do
 * defeito — se alguém repintar [PENDENTE] de vermelho, o build quebra em vez de a
 * tela voltar a mentir em silêncio.
 */
internal enum class EstadoDaLinha(val rotulo: String, val cor: Color) {
    LIBERADA("Liberado", Cores.TintaMedia),
    PENDENTE("Pendente", Cores.TintaFraca),
    CONSULTANDO("Verificando", Cores.TintaFraca),
    NEGADA("Negada", Cores.FalhaTexto),
}

/**
 * **Uma permissão, na gramática de livro-razão do produto.**
 *
 * Ícone → título → valor à direita, e um fio separando da seguinte. Não é cartão:
 * não há sombra, não há canto, não há caixa fechada. A paleta escreve a regra —
 * *"a estrutura é feita de fios, não de caixas"* — e esta tela era a única que a
 * contrariava.
 *
 * O ícone carrega a **identidade do aparelho** (microfone, rádio, pino, câmera,
 * sino) e o título carrega a **capacidade** que se ganha com ele. Os dois juntos
 * resolvem uma tensão que o texto sozinho tinha: o produto decidiu, com razão, não
 * rotular a linha com o nome da permissão do Android — mas aí "Entender comandos
 * de voz, falar no rádio tático, gravar evidência" não diz de onde vem. O
 * pictograma diz, sem gastar uma palavra.
 *
 * O valor à direita é `Tipo.Valor` — mono, tabular, alinhado à direita. É a mesma
 * borda direita comum do resto do aplicativo, e é ela que permite varrer seis
 * estados de cima a baixo sem ler seis vezes.
 */
@Composable
private fun LinhaDePermissao(
    icone: ImageVector,
    titulo: String,
    porQue: String,
    semEla: String?,
    obrigatoria: Boolean,
    estado: EstadoDaLinha,
    acao: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Espaco.Largo, vertical = Espaco.Padrao),
    ) {
        // Alinhado ao topo e não ao centro: o bloco de texto tem três parágrafos de
        // altura variável, e um ícone centrado nele flutua no meio do nada. No topo
        // ele fica na linha do título, que é o que ele nomeia.
        IconeTatico(
            icone,
            cor = if (estado == EstadoDaLinha.NEGADA) Cores.Tinta else Cores.TintaMedia,
            tamanho = IconeDeLinha,
        )
        Box(Modifier.width(Espaco.Padrao))

        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                TextoCorpo(titulo, modifier = Modifier.weight(1f))
                Box(Modifier.width(Espaco.Medio))
                Text(
                    estado.rotulo.uppercase(),
                    style = Tipo.Valor,
                    color = estado.cor,
                )
            }

            if (obrigatoria) {
                Box(Modifier.height(Espaco.Curto))
                // Substitui a segunda frase vermelha que existia aqui. A informação
                // — "sem esta não há produto" — é a mesma; o peso é o de um rótulo,
                // que é o que ela é.
                Etiqueta("Obrigatória", cor = Cores.TintaMedia)
            }

            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(porQue, cor = Cores.TintaMedia)

            if (semEla != null) {
                Box(Modifier.height(Espaco.Micro))
                // **A frase de consequência, e a cor dela.** Em `TintaFraca`
                // (5,52:1 sobre `Vazio`) enquanto ninguém negou nada: a gramática
                // dela já é condicional ("Sem X, …"), então ela se explica sozinha
                // sem gastar o orçamento de cor. Só vira `FalhaTexto` quando a
                // recusa aconteceu de verdade.
                TextoCorpoMenor(
                    semEla,
                    cor = if (estado == EstadoDaLinha.NEGADA) {
                        Cores.Tinta
                    } else {
                        Cores.TintaFraca
                    },
                )
            }

            if (acao != null) {
                Box(Modifier.height(Espaco.Padrao))
                acao()
            }
        }
    }
}

/**
 * Permissão → pictograma.
 *
 * O `when` é sobre a constante do Android e não sobre a posição no catálogo: o
 * catálogo muda de tamanho por versão de sistema (`POST_NOTIFICATIONS` só existe no
 * 13+), e um mapeamento por índice trocaria todos os ícones de lugar num aparelho
 * mais antigo.
 */
private fun iconeDe(p: PermissaoEssencial): ImageVector = when (p.permissao) {
    Manifest.permission.RECORD_AUDIO -> Icones.Microfone
    Manifest.permission.BLUETOOTH_CONNECT -> Icones.Bluetooth
    Manifest.permission.ACCESS_FINE_LOCATION -> Icones.Local
    Manifest.permission.CAMERA -> Icones.Camera
    Manifest.permission.POST_NOTIFICATIONS -> Icones.Sino
    // Permissão nova sem pictograma próprio cai no cadeado — genérico, mas nunca
    // ausente. Linha sem ícone quebraria o alinhamento de todas as outras, e o
    // buraco leria como defeito de renderização em vez de "ícone faltando".
    else -> Icones.Destravar
}

private fun avaliarAgora(context: Context): EstadoDePermissoes =
    PermissoesEssenciais.avaliar(
        PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet(),
    )

/**
 * As permissões cujo diálogo do sistema **já foi mostrado alguma vez**.
 *
 * O Android não guarda isto e não expõe nada equivalente; quem guarda é
 * [registrarPedido]. O conjunto tinha um leitor só ([podePedirDeNovo]) e ganhou o
 * segundo: é ele que separa "ainda não pedimos" de "o agente negou", e portanto é
 * ele que decide se há vermelho na tela.
 */
private fun Context.jaPedidas(): Set<String> =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(JA_PEDIDAS, emptySet())
        .orEmpty()

/**
 * `shouldShowRequestPermissionRationale` responde "o sistema ainda mostra o
 * diálogo?" — e responde `false` em dois casos opostos: nunca pedimos, ou o
 * usuário negou em definitivo. A diferença é registrada localmente, porque o
 * Android não a expõe.
 */
private fun podePedirDeNovo(context: Context): (String) -> Boolean {
    val activity = context as? Activity ?: return { true }
    val jaPedidas = context.jaPedidas()
    return { permissao ->
        activity.shouldShowRequestPermissionRationale(permissao) || permissao !in jaPedidas
    }
}

/** Registra que o diálogo já foi mostrado — chamado quando o pedido dispara. */
fun Context.registrarPedido(permissoes: Collection<String>) {
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val atual = prefs.getStringSet(JA_PEDIDAS, emptySet()).orEmpty()
    prefs.edit().putStringSet(JA_PEDIDAS, atual + permissoes).apply()
}

private fun Context.abrirAjustesDoApp() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val PREFS = "claryon.permissoes"
private const val JA_PEDIDAS = "ja_pedidas"
