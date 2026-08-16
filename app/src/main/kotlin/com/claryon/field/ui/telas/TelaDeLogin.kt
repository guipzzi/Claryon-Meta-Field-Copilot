package com.claryon.field.ui.telas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.FalhaDeLogin
import com.claryon.net.falhaDeLoginDe
import kotlinx.coroutines.launch

/**
 * **Entrada de turno.**
 *
 * A tela abre com uma varredura horizontal lenta atrás do nome — um único
 * movimento ambiente, do vocabulário de equipamento de rádio que varre uma faixa
 * de frequência. É a única animação decorativa do aplicativo inteiro, e existe
 * aqui porque este é o momento em que o agente **espera** por algo: enquanto o
 * resto do produto tem de sumir, a tela de entrada pode ter presença.
 *
 * Duas coisas que esta tela não faz, de propósito:
 *
 *  - **Não guarda a senha.** Só o par de tokens vai para o cofre cifrado. Senha
 *    em repouso vaza junto com o aparelho, e o aparelho de campo é o que se perde.
 *  - **Não colapsa causas de falha.** "Matrícula ou senha incorreta" e "Sem rede"
 *    pedem coisas diferentes: uma se resolve digitando, a outra esperando. Dizer
 *    "falha ao entrar" para as duas faz o agente tentar a senha dez vezes num túnel.
 */
@Composable
fun TelaDeLogin(
    auth: AutenticacaoSupabase,
    configurado: Boolean,
    aoEntrar: () -> Unit,
    aoSeguirSemRede: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var matricula by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var entrando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    Column(
        modifier
            .fillMaxSize()
            .background(Cores.Vazio)
            .imePadding()
            .padding(horizontal = Espaco.Largo),
        verticalArrangement = Arrangement.Center,
    ) {
        Marca()
        Box(Modifier.height(Espaco.Micro))
        TextoCorpoMenor(
            "Copiloto de voz para operação de rua.",
            cor = Cores.TintaMedia,
        )

        Box(Modifier.height(Espaco.Secao))

        if (!configurado) {
            Etiqueta("Servidor não configurado", cor = Cores.P2)
            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(
                "Esta instalação não tem endereço de projeto. O rádio e o mapa ficam " +
                    "indisponíveis; o ciclo de voz e a gravação funcionam.",
            )
            Box(Modifier.height(Espaco.Largo))
            BotaoTatico("Continuar sem rede", aoSeguirSemRede)
            return@Column
        }

        CampoTatico(
            valor = matricula,
            aoMudar = { matricula = it; erro = null },
            rotulo = "Matrícula",
            teclado = KeyboardType.Number,
            habilitado = !entrando,
        )
        Box(Modifier.height(Espaco.Medio))
        CampoTatico(
            valor = senha,
            aoMudar = { senha = it; erro = null },
            rotulo = "Senha",
            teclado = KeyboardType.Password,
            senha = true,
            habilitado = !entrando,
        )

        erro?.let {
            Box(Modifier.height(Espaco.Medio))
            TextoCorpoMenor(it, cor = Cores.Falha)
        }

        Box(Modifier.height(Espaco.Largo))
        BotaoTatico(
            rotulo = if (entrando) "Entrando" else "Iniciar turno",
            aoTocar = {
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
            habilitado = !entrando && matricula.isNotBlank() && senha.isNotBlank(),
        )

        Box(Modifier.height(Espaco.Padrao))
        // Seguir sem entrar é legítimo: ciclo de voz, earcons e gravação de
        // evidência funcionam offline. O que não funciona é o que depende de
        // saber quem é o agente — e isso é dito quando for pedido.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Etiqueta(
                "Seguir sem entrar",
                cor = Cores.TintaFraca,
                modifier = Modifier
                    .tocavel(aoTocar = aoSeguirSemRede)
                    .padding(Espaco.Curto),
            )
        }
    }
}

/**
 * A marca, com uma varredura horizontal lenta por baixo.
 *
 * Movimento contínuo e muito lento (4 s por passada, opacidade baixa): lê como
 * equipamento ligado, não como carregamento. Uma barra de progresso aqui mentiria
 * — não há nada carregando.
 */
@Composable
private fun Marca() {
    val transicao = rememberInfiniteTransition(label = "varredura")
    val posicao by transicao.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "posicao-varredura",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBehind {
                val y = size.height - 6f
                drawLine(
                    color = Cores.Traco,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                val x = size.width * posicao
                drawLine(
                    color = Cores.TracoForte,
                    start = Offset(x - 40f, y),
                    end = Offset(x, y),
                    strokeWidth = 2f,
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text("CLARYON", style = Tipo.Titulo, color = Cores.Tinta)
    }
}

@Composable
private fun CampoTatico(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    teclado: KeyboardType,
    habilitado: Boolean,
    senha: Boolean = false,
) {
    Column {
        Etiqueta(rotulo)
        Box(Modifier.height(Espaco.Curto))
        OutlinedTextField(
            value = valor,
            onValueChange = aoMudar,
            enabled = habilitado,
            singleLine = true,
            textStyle = Tipo.Indicativo,
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = teclado, imeAction = ImeAction.Next),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cores.Tinta,
                unfocusedBorderColor = Cores.Traco,
                focusedTextColor = Cores.Tinta,
                unfocusedTextColor = Cores.Tinta,
                cursorColor = Cores.Tinta,
                focusedContainerColor = Cores.Painel,
                unfocusedContainerColor = Cores.Painel,
                disabledContainerColor = Cores.Painel,
            ),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun mensagemDe(falha: FalhaDeLogin): String = when (falha) {
    FalhaDeLogin.CredencialInvalida -> "Matrícula ou senha incorreta."
    FalhaDeLogin.SemAgenteVinculado ->
        "Sua matrícula não está vinculada a um agente. Procure o administrador."
    FalhaDeLogin.SemRede -> "Sem rede. Tente de novo quando houver sinal."
    is FalhaDeLogin.Servidor -> "Servidor indisponível (${falha.codigo}). Tente de novo."
}
