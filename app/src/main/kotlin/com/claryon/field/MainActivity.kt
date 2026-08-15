package com.claryon.field

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.ui.DiagnosticsScreen
import com.claryon.field.ui.DiagnosticsViewModel
import com.claryon.field.ui.TelaDeLogin
import com.claryon.field.ui.TelaDePermissoes

/**
 * Ponto de entrada do app.
 *
 * A tela existe apenas para onboarding, diagnóstico e demonstração à banca — a
 * saída rica ao usuário final é sempre áudio. O DAT já foi inicializado na
 * [ClaryonApp].
 *
 * A abertura é uma sequência de portões, e nenhum deles é intransponível exceto
 * o primeiro: **permissões → sessão → painel**. Cada portão pode ser pulado, e
 * pular custa capacidades que o app diz em voz alta quando forem pedidas —
 * jamais um comando que simplesmente não faz nada.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: DiagnosticsViewModel = viewModel()

            // `tudoConcedido`, não `podeOperar`.
            //
            // A versão anterior usava `podeOperar` — que só olha a bloqueante — e
            // criava um beco sem saída: o agente concedia o microfone, negava a
            // localização, tocava "seguir assim mesmo", e **da segunda abertura em
            // diante o app ia direto ao painel**. Localização, câmera e Bluetooth
            // nunca mais eram pedidos, e C2, C3 e o mapa ficavam mortos em
            // silêncio para sempre.
            //
            // Agora a tela reaparece a cada abertura enquanto faltar alguma, e
            // "seguir assim mesmo" vale só para esta sessão. Um toque a mais por
            // turno é barato; uma capacidade morta em definitivo não é.
            var mostrarPermissoes by remember { mutableStateOf(!tudoConcedido()) }
            var mostrarLogin by remember { mutableStateOf(true) }

            MaterialTheme {
                Surface {
                    when {
                        mostrarPermissoes ->
                            TelaDePermissoes(aoConcluir = { mostrarPermissoes = false })

                        // Já autenticado num turno anterior: a sessão está no cofre
                        // cifrado e o login não reaparece.
                        mostrarLogin && !vm.autenticacao.autenticado() -> TelaDeLogin(
                            auth = vm.autenticacao,
                            configurado = vm.redeConfigurada,
                            aoEntrar = { mostrarLogin = false },
                            aoSeguirSemRede = { mostrarLogin = false },
                        )

                        else -> DiagnosticsScreen()
                    }
                }
            }
        }
    }

    private fun tudoConcedido(): Boolean = PermissoesEssenciais.avaliar(
        PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet(),
    ).tudoConcedido
}
