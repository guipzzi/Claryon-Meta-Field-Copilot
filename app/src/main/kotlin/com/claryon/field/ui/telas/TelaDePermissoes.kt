package com.claryon.field.ui.telas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claryon.field.ui.tema.Cores
import com.claryon.field.permissoes.EstadoDePermissoes
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.permissoes.Recuperacao
import com.claryon.glasses.PermissaoDaCameraDoDat
import com.claryon.glasses.RespostaDePermissao

/**
 * Tela de permissões do onboarding.
 *
 * Duas decisões de desenho que valem mais que o layout:
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

    // Conceder pelos ajustes do Android **não reinicia o processo** (só revogar
    // reinicia). Sem observar o retorno, a tela continuaria mostrando tudo negado
    // depois de o agente ter liberado tudo.
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                estado = avaliarAgora(context)
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
    val podePedir = remember(estado) { podePedirDeNovo(context) }

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
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        // **A cor é explícita, e sem ela o título era PRETO.**
        //
        // Estes dois `Text` estão fora de qualquer `Card`, e portanto fora de
        // qualquer `Surface`: `LocalContentColor` cai no padrão do Material, que é
        // `Color.Black`. Sobre o fundo da aplicação isso dá **1,1:1** — abaixo de
        // qualquer piso, e invisível na prática. Os textos dentro dos cartões
        // escapavam por acidente, porque `Card` fornece `onSurfaceVariant`.
        //
        // O defeito convivia com o da barra de status e cada um escondia o outro:
        // o título ilegível não denunciava a sobreposição, e a sobreposição
        // explicava o título sumido. Consertar só a posição entregaria um título
        // bem colocado que continua sem dar para ler.
        Text(
            "Antes de começar",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Cores.Tinta,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "O Claryon trabalha com a tela apagada. Estas permissões são o que " +
                "ele usa — e o que deixa de funcionar sem cada uma.",
            fontSize = 14.sp,
            color = Cores.TintaMedia,
        )
        Spacer(Modifier.height(16.dp))

        for (p in PermissoesEssenciais.catalogo()) {
            val concedida = p !in estado.faltando
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        // Rótulo pela capacidade, não pelo nome da permissão.
                        p.capacidades.joinToString(", ") { it.rotulo }
                            .replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(p.porQue, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (concedida) "Liberado" else p.semEla,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (concedida) Cores.Tinta else Cores.FalhaTexto,
                    )
                    if (!concedida && p.bloqueante) {
                        Spacer(Modifier.height(2.dp))
                        // `FalhaTexto` e não `error`: `Cores.Falha` rende 4,20:1 e
                        // é token de MARCA, não de palavra. Aqui o estado precisa
                        // mesmo ser lido, e este é o token que existe para isso —
                        // 6,76:1.
                        Text(
                            "Sem esta, o app não abre o ciclo de voz.",
                            fontSize = 12.sp,
                            color = Cores.FalhaTexto,
                        )
                    }
                }
            }
        }

        // **Câmera dos óculos — permissão do DAT, não do Android.**
        //
        // Fica depois das do sistema de propósito: é a única que sai do app, e
        // pedir a que abre outro aplicativo antes das locais gasta a paciência do
        // agente na mais cara.
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Câmera dos óculos", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "É por ela que o app enxerga pela câmera dos óculos. " +
                        "Quem libera é o app Meta AI, não o Android.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                val (texto, boa) = when (val o = oculos) {
                    null -> "Verificando…" to true
                    is RespostaDePermissao.Concedida -> "Liberado" to true
                    is RespostaDePermissao.Negada -> "Sem ela, não enxergo pelos óculos." to false
                    // A causa, não um "negada" genérico: "óculos desconectados"
                    // pede que o agente ligue os óculos; "negada" pediria que ele
                    // mudasse de ideia. Mandar consertar a coisa errada é o modo
                    // de falha que esta tela existe para evitar.
                    is RespostaDePermissao.Indisponivel -> o.causa.frase to false
                }
                Text(
                    texto,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (boa) Cores.Tinta else Cores.FalhaTexto,
                )
                if (oculos !is RespostaDePermissao.Concedida) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        // **O chamador que faltava.** Sem este toque, nada no
                        // aplicativo jamais pede a permissão de câmera do DAT.
                        onClick = {
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
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Liberar no Meta AI") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (val r = PermissoesEssenciais.recuperacao(estado, podePedir)) {
            is Recuperacao.Pedir -> Button(
                onClick = {
                    // Registrar ANTES de lançar. É este registro que faz
                    // `shouldShowRequestPermissionRationale == false` significar
                    // "negada em definitivo" em vez de "nunca perguntamos" — o
                    // Android devolve `false` nos dois casos e não distingue.
                    // Sem isso, o botão "Permitir" continuaria aparecendo para
                    // sempre, abrindo um diálogo que o sistema não mostra mais.
                    context.registrarPedido(r.permissoes)
                    pedido.launch(r.permissoes.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Permitir") }

            is Recuperacao.AbrirAjustes -> Column {
                // Sem esta explicação, o botão "Permitir" abriria um diálogo que
                // o sistema não mostra mais — e o sintoma, para o agente, seria
                // "apertei e não aconteceu nada".
                Text(
                    "O Android não pergunta mais. É preciso liberar nos ajustes " +
                        "do aplicativo.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { context.abrirAjustesDoApp() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir ajustes") }
            }

            Recuperacao.Nada -> Button(
                onClick = aoConcluir,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continuar") }
        }

        if (!estado.tudoConcedido) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = aoConcluir,
                enabled = estado.podeOperar,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Seguir sem tudo é permitido — menos sem microfone. Bloquear por
                // câmera negada seria o app se recusando a fazer o que ainda sabe
                // fazer.
                Text(
                    if (estado.podeOperar) {
                        "Seguir assim mesmo"
                    } else {
                        "O microfone é obrigatório"
                    },
                )
            }
        }
    }
}

private fun avaliarAgora(context: Context): EstadoDePermissoes =
    PermissoesEssenciais.avaliar(
        PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet(),
    )

/**
 * `shouldShowRequestPermissionRationale` responde "o sistema ainda mostra o
 * diálogo?" — e responde `false` em dois casos opostos: nunca pedimos, ou o
 * usuário negou em definitivo. A diferença é registrada localmente, porque o
 * Android não a expõe.
 */
private fun podePedirDeNovo(context: Context): (String) -> Boolean {
    val activity = context as? Activity ?: return { true }
    val jaPedidas = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(JA_PEDIDAS, emptySet())
        .orEmpty()
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
