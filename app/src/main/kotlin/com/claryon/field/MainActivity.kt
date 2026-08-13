package com.claryon.field

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.claryon.field.ui.DiagnosticsScreen

/**
 * Ponto de entrada do app.
 *
 * A tela existe apenas para onboarding, diagnóstico e demonstração à banca — a
 * saída rica ao usuário final é sempre áudio. No M0 mostra o painel de
 * diagnóstico com o estado (ainda estático) dos subsistemas; a orquestração
 * real (boot, ciclo de voz, encerramento) é ligada a partir do M2.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    DiagnosticsScreen()
                }
            }
        }
    }
}
