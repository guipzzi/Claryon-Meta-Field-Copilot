package com.claryon.field.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.tocavel
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.marca.MarcaClaryon
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import androidx.compose.material3.Text
import com.claryon.net.AutenticacaoSupabase
import com.claryon.net.FalhaDeLogin
import com.claryon.net.falhaDeLoginDe
import kotlinx.coroutines.launch

/**
 * **Entrada de turno.**
 *
 * ## O que mudou aqui, e por quê
 *
 * A versão anterior desta tela abria com uma **varredura horizontal infinita** por
 * baixo do nome, e o KDoc dela defendia a escolha assim: *"é a única animação
 * decorativa do aplicativo inteiro, e existe aqui porque este é o momento em que o
 * agente espera por algo"*. A decisão tinha precedência sobre gosto, então ela é
 * refutada por escrito, com dois argumentos:
 *
 *  1. **O agente não espera nada aqui.** Ele espera na abertura, enquanto o cofre
 *     cifrado é lido — e essa espera agora tem tela própria
 *     ([com.claryon.field.ui.marca.AberturaDoTurno]). Nesta tela quem trabalha é o
 *     polegar dele: não há carga, não há rede em voo, não há nada acontecendo. Um
 *     movimento contínuo sobre uma tela ociosa afirma atividade que não existe, que
 *     é a mesma classe de defeito que este projeto já corrigiu no indicador de
 *     quadros e no relatório de prontidão.
 *  2. **A varredura tem dono no vocabulário do produto.** Uma barra correndo sobre
 *     uma linha é, num produto de rádio, *varredura de frequência*. O aplicativo
 *     não varre frequência nenhuma. Movimento que empresta o gesto de uma função
 *     inexistente é pior que movimento gratuito — ele descreve errado.
 *
 * No lugar dela entra a marca, parada. A marca não precisa se mexer para ter
 * presença; ela se move uma vez, na abertura, e ali o movimento tem trabalho:
 * apresentar o produto. Aqui ela identifica, e identificar é um trabalho de forma.
 *
 * ## O que esta tela continua não fazendo, de propósito
 *
 *  - **Não guarda a senha.** Só o par de tokens vai para o cofre cifrado. Senha em
 *    repouso vaza junto com o aparelho, e o aparelho de campo é o que se perde.
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

    val podeEntrar = !entrando && matricula.isNotBlank() && senha.isNotBlank()
    val entrar: () -> Unit = {
        if (podeEntrar) {
            entrando = true
            erro = null
            escopo.launch {
                auth.entrar(matricula, senha)
                    .onSuccess {
                        // A senha some da memória junto com a tela: nada além dos
                        // tokens sobrevive a este ponto.
                        senha = ""
                        aoEntrar()
                    }
                    .onFailure { erro = mensagemDe(falhaDeLoginDe(it)) }
                entrando = false
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Cores.Vazio)
            .imePadding()
            .padding(horizontal = Espaco.Largo),
        verticalArrangement = Arrangement.Center,
    ) {
        BlocoDaMarca()

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
            acaoDoTeclado = ImeAction.Next,
            habilitado = !entrando,
        )
        Box(Modifier.height(Espaco.Padrao))
        CampoTatico(
            valor = senha,
            aoMudar = { senha = it; erro = null },
            rotulo = "Senha",
            teclado = KeyboardType.Password,
            // `Done` e não `Next`: a senha é o último campo, e o teclado que
            // oferece "avançar" no último campo manda o agente procurar um campo
            // que não existe. Confirmar daqui entra no turno.
            acaoDoTeclado = ImeAction.Done,
            aoConfirmar = entrar,
            senha = true,
            habilitado = !entrando,
        )

        erro?.let {
            Box(Modifier.height(Espaco.Padrao))
            TextoCorpoMenor(it, cor = Cores.Falha)
        }

        Box(Modifier.height(Espaco.Largo))
        BotaoTatico(
            rotulo = if (entrando) "Entrando" else "Iniciar turno",
            aoTocar = entrar,
            habilitado = podeEntrar,
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
 * Marca, nome e o que o produto é — centralizados, com ar em volta.
 *
 * O bloco é centralizado e o formulário abaixo é alinhado à esquerda, e a mistura
 * é intencional: identidade é simétrica, dado é tabular. É a mesma divisão que o
 * resto do aplicativo faz entre a sans (linguagem) e a mono (leitura precisa).
 */
@Composable
private fun BlocoDaMarca() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarcaClaryon(largura = 84.dp)

        Box(Modifier.height(Espaco.Largo))

        // Entreletra de 0,16 em: o mesmo valor de `Tipo.Etiqueta`, e não um número
        // escolhido a olho. Caixa-alta com entreletra larga é o gesto de título do
        // sistema; aplicá-lo com a métrica da etiqueta é o que faz o nome e os
        // rótulos da tela pertencerem à mesma família.
        Text(
            "CLARYON",
            style = Tipo.Titulo.copy(letterSpacing = 0.16.em),
            color = Cores.Tinta,
        )
        Box(Modifier.height(Espaco.Curto))
        Etiqueta("Copiloto de campo", cor = Cores.TintaFraca)
    }
}

/**
 * Campo de entrada: rótulo, texto e **um fio**.
 *
 * Era um `OutlinedTextField` — uma caixa com borda nos quatro lados e fundo
 * `Painel`. A regra do sistema é explícita e a paleta a repete: *"a estrutura é
 * feita de fios, não de caixas"*. Duas caixas com borda inteira no meio de uma tela
 * que não tem nenhuma outra denunciavam componente de biblioteca — e custavam
 * altura, que é o recurso escasso quando o teclado está aberto.
 *
 * O fio é `Traco` em repouso e `Tinta` com foco. Não há cor cromática aqui de
 * propósito: âmbar significa "no ar" e verde significa "presente"; um campo de
 * texto focado não é nem uma coisa nem outra.
 */
@Composable
private fun CampoTatico(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    teclado: KeyboardType,
    acaoDoTeclado: ImeAction,
    habilitado: Boolean,
    aoConfirmar: () -> Unit = {},
    senha: Boolean = false,
) {
    var focado by remember { mutableStateOf(false) }

    Column {
        Etiqueta(rotulo, cor = if (focado) Cores.TintaMedia else Cores.TintaFraca)
        Box(Modifier.height(Espaco.Curto))
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            enabled = habilitado,
            singleLine = true,
            textStyle = Tipo.Indicativo.copy(
                color = if (habilitado) Cores.Tinta else Cores.TintaFraca,
            ),
            cursorBrush = SolidColor(Cores.Tinta),
            visualTransformation =
                if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = teclado, imeAction = acaoDoTeclado),
            keyboardActions = KeyboardActions(onDone = { aoConfirmar() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Espaco.Curto)
                .onFocusChanged { focado = it.isFocused },
        )
        Fio(cor = if (focado) Cores.Tinta else Cores.Traco)
    }
}

private fun mensagemDe(falha: FalhaDeLogin): String = when (falha) {
    FalhaDeLogin.CredencialInvalida -> "Matrícula ou senha incorreta."
    FalhaDeLogin.SemAgenteVinculado ->
        "Sua matrícula não está vinculada a um agente. Procure o administrador."
    FalhaDeLogin.SemRede -> "Sem rede. Tente de novo quando houver sinal."
    is FalhaDeLogin.Servidor -> "Servidor indisponível (${falha.codigo}). Tente de novo."
}
