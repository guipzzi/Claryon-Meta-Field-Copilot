package com.claryon.field.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Painel de diagnóstico (M0).
 *
 * Ainda estático: lista os subsistemas e o marco em que cada um ganha
 * implementação. Nos marcos seguintes, cada linha passa a refletir estado real
 * (registro, sessão, roteamento HFP, pipeline de voz), observando os StateFlow
 * das fachadas.
 */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Claryon Field",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Copiloto de voz para segurança pública · Ray-Ban Meta (sem display)",
            fontSize = 13.sp,
        )

        SUBSYSTEMS.forEach { (nome, marco, detalhe) ->
            Card(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = nome, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(text = marco, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(text = detalhe, fontSize = 12.sp)
                }
            }
        }
    }
}

private val SUBSYSTEMS = listOf(
    Triple("core-glasses", "M1–M2", "Registro, sessão e câmera via DAT (fachada única)."),
    Triple("core-audio", "M3", "Roteamento HFP/SCO, AudioRecord/AudioTrack."),
    Triple("core-voice", "M4", "Wake word · VAD · STT · TTS, 100% on-device."),
    Triple("core-agent", "M5", "Roteador de intenções determinístico."),
    Triple("core-sound", "M5", "Earcons, fila de prioridade, laconicidade ≤7 palavras."),
    Triple("core-evidence", "M6", "Cofre cifrado + cadeia de custódia SHA-256."),
    Triple("core-sync", "M7", "Supabase, WhatsApp, fila offline."),
)

@Preview(showBackground = true)
@Composable
private fun DiagnosticsScreenPreview() {
    MaterialTheme3Preview { DiagnosticsScreen() }
}

// Wrapper mínimo para o preview não depender do tema do app.
@Composable
private fun MaterialTheme3Preview(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme { content() }
}
