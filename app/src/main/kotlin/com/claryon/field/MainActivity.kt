package com.claryon.field

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.claryon.field.ui.DiagnosticsScreen

/**
 * Ponto de entrada do app.
 *
 * A tela existe apenas para onboarding, diagnóstico e demonstração à banca — a
 * saída rica ao usuário final é sempre áudio. Solicita em runtime as permissões
 * necessárias ao pipeline (áudio HFP e Bluetooth); o DAT já foi inicializado na
 * [ClaryonApp].
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { /* status refletido no painel */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT),
        )
        setContent {
            MaterialTheme {
                Surface {
                    DiagnosticsScreen()
                }
            }
        }
    }
}
