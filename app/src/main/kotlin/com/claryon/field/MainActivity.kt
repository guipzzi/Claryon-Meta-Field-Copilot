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
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.ui.DiagnosticsScreen
import com.claryon.field.ui.TelaDePermissoes

/**
 * Ponto de entrada do app.
 *
 * A tela existe apenas para onboarding, diagnóstico e demonstração à banca — a
 * saída rica ao usuário final é sempre áudio. O DAT já foi inicializado na
 * [ClaryonApp].
 *
 * **Mudança relevante:** a versão anterior disparava o pedido de permissões no
 * `onCreate`, com `RECORD_AUDIO` e `BLUETOOTH_CONNECT`, e **ignorava o
 * resultado** (o comentário dizia "status refletido no painel"). Três problemas
 * de uma vez: o diálogo aparecia antes de qualquer contexto — que é a receita
 * para ser negado; a lista estava incompleta, sem localização nem câmera; e
 * negar não mudava nada, então o app seguia para o painel e simplesmente não
 * funcionava, sem dizer por quê.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Reavaliado a cada composição inicial: o agente pode ter concedido
            // pelos ajustes do sistema entre uma abertura e outra.
            var mostrarPermissoes by remember { mutableStateOf(!podeSeguir()) }

            MaterialTheme {
                Surface {
                    if (mostrarPermissoes) {
                        TelaDePermissoes(aoConcluir = { mostrarPermissoes = false })
                    } else {
                        DiagnosticsScreen()
                    }
                }
            }
        }
    }

    /**
     * Só a bloqueante impede a entrada. Barrar por câmera negada seria o app se
     * recusando a fazer o que ainda sabe fazer — e o agente já está no pátio.
     */
    private fun podeSeguir(): Boolean = PermissoesEssenciais.avaliar(
        PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet(),
    ).podeOperar
}
