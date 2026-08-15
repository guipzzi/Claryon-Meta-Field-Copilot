package com.claryon.field.ui.telas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.claryon.field.permissoes.EstadoDePermissoes
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.permissoes.Recuperacao

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

    // Conceder pelos ajustes do Android **não reinicia o processo** (só revogar
    // reinicia). Sem observar o retorno, a tela continuaria mostrando tudo negado
    // depois de o agente ter liberado tudo.
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                estado = avaliarAgora(context)
                if (estado.tudoConcedido) aoConcluir()
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose { dono.lifecycle.removeObserver(observador) }
    }

    val pedido = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        // Reavalia pelo PackageManager, não pelo mapa que o launcher devolve: o
        // usuário pode ter concedido pelos ajustes numa ida e volta anterior, e
        // o resultado do diálogo não sabe disso.
        estado = avaliarAgora(context)
        if (estado.tudoConcedido) aoConcluir()
    }

    // Lido uma vez por composição, não a cada recomposição: `SharedPreferences`
    // na main thread dentro do corpo do composable rodava a cada quadro.
    val podePedir = remember(estado) { podePedirDeNovo(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Antes de começar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "O Claryon trabalha com a tela apagada. Estas permissões são o que " +
                "ele usa — e o que deixa de funcionar sem cada uma.",
            fontSize = 14.sp,
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
                        color = if (concedida) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (!concedida && p.bloqueante) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Sem esta, o app não abre o ciclo de voz.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
