package com.claryon.field.ui

import android.app.Activity
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claryon.agent.ModoOperacao
import com.claryon.field.service.CopilotService
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import com.claryon.glasses.StreamStatus

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
    aoAbrirMapa: () -> Unit = {},
    vm: DiagnosticoViewModel = viewModel(),
    copiloto: CopilotoViewModel = viewModel(),
) {
    val registration by vm.registration.collectAsState()

    // O anúncio falado do estado degradado é de PRODUÇÃO e mora no
    // `OculosViewModel` — `MainActivity` já o chama na abertura. O painel de
    // diagnóstico não repete: dois avisos para o mesmo fato viram ruído que o
    // agente aprende a ignorar.
    val session by vm.session.collectAsState()
    val stream by vm.streamState.collectAsState()
    val frame by vm.frameInfo.collectAsState()
    val devices by vm.deviceCount.collectAsState()
    val mockStatus by vm.mockStatus.collectAsState()
    val audioStatus by vm.audioStatus.collectAsState()
    val commandStatus by copiloto.commandStatus.collectAsState()
    var command by remember { mutableStateOf("") }

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

        if (registration != RegistrationStatus.REGISTERED) {
            // O caminho de volta do registro perdido. Sem este botão o agente vê
            // "UNAVAILABLE" e não tem o que fazer — e foi medido que parear um
            // aparelho não restaura o registro sozinho.
            // `LocalActivity` e nao cast do `LocalContext`: o cast falha em
            // silencio sob ContextWrapper (o que acontece em tema custom e em
            // preview), e o lint reprovava o build por isso — enquanto o
            // ESTADO.md afirmava build verde.
            val activity = LocalActivity.current
            Button(
                onClick = { activity?.let(vm::registrar) },
                enabled = activity != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Conectar aos óculos") }
        }
        StatusCard("Dispositivos visíveis", devices.toString())
        StatusCard("Sessão", session.name)
        StatusCard("Stream de câmera", stream.name)
        StatusCard(
            "Frames",
            frame?.let { "#${it.count} · ${it.width}×${it.height}" } ?: "—",
        )

        if (vm.mockAvailable) {
            StatusCard("MockDeviceKit", mockStatus)

            Button(onClick = aoAbrirMapa, modifier = Modifier.fillMaxWidth()) {
                Text("Mapa da guarnição")
            }
        }
        StatusCard("Áudio HFP (M3)", audioStatus)

        // Botões gateados por pré-condição — evita toques sem efeito (no-op) e
        // guia a sequência correta: registrar → sessão → câmera.
        val canStartSession = registration == RegistrationStatus.REGISTERED &&
            (session == SessionStatus.IDLE || session == SessionStatus.STOPPED)
        val canStartCamera = session == SessionStatus.STARTED &&
            (stream == StreamStatus.STOPPED || stream == StreamStatus.CLOSED)
        val canStopCamera = stream == StreamStatus.STREAMING ||
            stream == StreamStatus.STARTED || stream == StreamStatus.STARTING

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.mockAvailable) {
                OutlinedButton(onClick = vm::enableMock) { Text("Habilitar mock") }
            }
            Button(onClick = vm::startSession, enabled = canStartSession) { Text("Iniciar sessão") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = vm::startCamera, enabled = canStartCamera) { Text("Iniciar câmera") }
            OutlinedButton(onClick = vm::stopCamera, enabled = canStopCamera) { Text("Parar câmera") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = vm::echo) { Text("Eco 3 s (áudio HFP)") }
        }

        // Ciclo de voz (M4): comando por texto → roteador → resposta falada.
        StatusCard("Comando (roteador → TTS)", commandStatus)
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Comando de voz (texto)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { copiloto.runCommand(command) },
                enabled = command.isNotBlank(),
            ) { Text("Enviar comando") }
            OutlinedButton(onClick = copiloto::falarComando) { Text("Falar comando (STT)") }
        }
        Button(
            onClick = copiloto::cicloDeVoz,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("▶ Ciclo de voz completo (push-to-talk)") }

        // Modos de operação (M8). O serviço de primeiro plano PRECISA ser
        // iniciado daqui — de tela visível; em background é
        // ForegroundServiceStartNotAllowedException.
        val context = LocalContext.current
        val modo by CopilotService.modo.collectAsState()
        // getThermalHeadroom é uma chamada de binder e a API não deve ser
        // consultada mais de uma vez por segundo — acima disso ela devolve NaN,
        // o que tornaria o teto exibido inútil. Chamar direto no corpo do
        // composable a executaria a CADA recomposição (e há muitas: os status de
        // áudio e comando mudam durante os ciclos). `remember(modo)` amarra a
        // leitura à troca de modo.
        val fpsTeto = remember(modo) { CopilotService.fpsPermitidoAgora(context, modo) }
        StatusCard("Modo de operação", "${modo.name} · FPS teto $fpsTeto")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { CopilotService.parar(context) }) { Text("Standby") }
            Button(onClick = { CopilotService.iniciar(context, ModoOperacao.ATIVO) }) { Text("Ativo") }
            Button(onClick = { CopilotService.iniciar(context, ModoOperacao.OCORRENCIA) }) { Text("Ocorrência") }
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
