package com.claryon.field.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Painel de diagnóstico (M2) — a tela existe só para config/diagnóstico/demo; a
 * saída rica ao usuário final é sempre áudio.
 *
 * Reflete ao vivo o registro, a sessão e o stream de câmera reais do DAT.
 * Em DEBUG, o botão "Habilitar mock" pareia um Ray-Ban simulado (câmera do
 * celular como fonte) e permite ver a sessão transitar STARTING → STARTED e o
 * stream STARTING → STARTED → STREAMING, com os frames chegando — sem óculos.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val registration by vm.registration.collectAsState()
    val session by vm.session.collectAsState()
    val stream by vm.streamState.collectAsState()
    val frame by vm.frameInfo.collectAsState()
    val devices by vm.deviceCount.collectAsState()
    val mockStatus by vm.mockStatus.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Claryon Field", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Diagnóstico do DAT · M2 (Mock Device Kit)", fontSize = 13.sp)

        StatusCard("Registro", registration.name)
        StatusCard("Dispositivos visíveis", devices.toString())
        StatusCard("Sessão", session.name)
        StatusCard("Stream de câmera", stream.name)
        StatusCard(
            "Frames",
            frame?.let { "#${it.count} · ${it.width}×${it.height}" } ?: "—",
        )

        if (vm.mockAvailable) {
            StatusCard("MockDeviceKit", mockStatus)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.mockAvailable) {
                OutlinedButton(onClick = vm::enableMock) { Text("Habilitar mock") }
            }
            Button(onClick = vm::startSession) { Text("Iniciar sessão") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = vm::startCamera) { Text("Iniciar câmera") }
            OutlinedButton(onClick = vm::stopCamera) { Text("Parar câmera") }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardPreview() {
    StatusCard("Sessão", "STARTED")
}
