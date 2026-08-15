package com.claryon.field.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.FalhaDeLogin
import com.claryon.net.falhaDeLoginDe
import kotlinx.coroutines.launch

/**
 * Entrada do agente com matrícula e senha.
 *
 * É o que faltava para a consulta de posição fechar: `public.consultar_posicao`
 * deriva o solicitante do JWT, então sem sessão o servidor não sabe quem
 * pergunta.
 *
 * Duas coisas que esta tela **não** faz, de propósito:
 *
 *  - **Não guarda a senha.** Só o par de tokens vai para o cofre cifrado. Uma
 *    senha em repouso vaza junto com o aparelho, e o aparelho de campo é
 *    justamente o que se perde.
 *  - **Não colapsa as causas de falha.** "Matrícula ou senha incorreta" e "Sem
 *    rede" pedem coisas diferentes do agente: uma se resolve digitando de novo, a
 *    outra se resolve esperando. Dizer "falha ao entrar" para as duas faz o
 *    agente tentar a senha do jeito errado dez vezes num túnel.
 */
@Composable
fun TelaDeLogin(
    auth: AutenticacaoSupabase,
    configurado: Boolean,
    aoEntrar: () -> Unit,
    aoSeguirSemRede: () -> Unit,
) {
    var matricula by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var entrando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Claryon Field", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Entre com sua matrícula para que a guarnição saiba onde você está " +
                "— e você, onde eles estão.",
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))

        if (!configurado) {
            // Sem endereço de projeto não há como entrar, e o app tem de dizer
            // isso em vez de deixar o agente errando a senha contra o nada.
            Text(
                "Servidor não configurado nesta instalação. O rádio e o mapa " +
                    "ficam indisponíveis; o resto do app funciona.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = aoSeguirSemRede, modifier = Modifier.fillMaxWidth()) {
                Text("Continuar sem rede")
            }
            return@Column
        }

        OutlinedTextField(
            value = matricula,
            onValueChange = { matricula = it; erro = null },
            label = { Text("Matrícula") },
            singleLine = true,
            enabled = !entrando,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = senha,
            onValueChange = { senha = it; erro = null },
            label = { Text("Senha") },
            singleLine = true,
            enabled = !entrando,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        erro?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                entrando = true
                erro = null
                escopo.launch {
                    auth.entrar(matricula, senha)
                        .onSuccess {
                            // A senha some da memória junto com a tela: nada além
                            // dos tokens sobrevive a este ponto.
                            senha = ""
                            aoEntrar()
                        }
                        .onFailure { erro = mensagemDe(falhaDeLoginDe(it)) }
                    entrando = false
                }
            },
            enabled = !entrando && matricula.isNotBlank() && senha.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (entrando) "Entrando…" else "Entrar")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = aoSeguirSemRede, modifier = Modifier.fillMaxWidth()) {
            // Seguir sem entrar é legítimo: a gravação de evidência, o ciclo de
            // voz e os earcons funcionam offline. O que não funciona é o que
            // depende de saber quem é o agente — e isso é dito quando for pedido.
            Text("Seguir sem entrar")
        }
    }
}

private fun mensagemDe(falha: FalhaDeLogin): String = when (falha) {
    FalhaDeLogin.CredencialInvalida -> "Matrícula ou senha incorreta."
    FalhaDeLogin.SemAgenteVinculado ->
        "Sua matrícula não está vinculada a um agente. Procure o administrador."
    FalhaDeLogin.SemRede -> "Sem rede. Tente de novo quando houver sinal."
    is FalhaDeLogin.Servidor -> "Servidor indisponível (${falha.codigo}). Tente de novo."
}
