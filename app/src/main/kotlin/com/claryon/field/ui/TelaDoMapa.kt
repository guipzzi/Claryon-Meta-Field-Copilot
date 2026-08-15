package com.claryon.field.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.Frescor
import com.claryon.field.mapa.ParNoMapa

/**
 * **Tela do mapa (C5).**
 *
 * O ciclo de vida desta tela **é** a regra de bateria. `ON_START` abre a
 * assinatura do canal de posições; `ON_STOP` fecha e descarta o espelho. Numa
 * guarnição de oito, manter a assinatura aberta seria 8 × 8 de tráfego
 * permanente para uma tela que fica fechada 95% do turno.
 *
 * `ON_START`/`ON_STOP` e não `ON_RESUME`/`ON_PAUSE`: uma notificação por cima da
 * tela dispara `ON_PAUSE` sem que o mapa deixe de estar visível, e fechar a
 * assinatura ali faria os marcadores sumirem toda vez que uma mensagem chegasse.
 *
 * A lista é ordenada por distância e mostra **indicativo**, nunca nome nem
 * matrícula — um mapa que identifica pessoas deixa de ser coordenação e vira
 * controle sobre o próprio efetivo.
 */
@Composable
fun TelaDoMapa(
    estado: EstadoDoMapa,
    aoAbrir: () -> Unit,
    aoFechar: () -> Unit,
) {
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> aoAbrir()
                Lifecycle.Event.ON_STOP -> aoFechar()
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose {
            dono.lifecycle.removeObserver(observador)
            // Fechar também aqui: a tela pode sair da composição sem passar por
            // ON_STOP (navegação dentro da mesma Activity). Sem isto, a assinatura
            // sobreviveria à tela — o vazamento é de bateria e de privacidade ao
            // mesmo tempo, porque continuaríamos recebendo posição de todos.
            aoFechar()
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Guarnição", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (estado.assinado) "recebendo" else "sem sinal",
                fontSize = 13.sp,
                color = if (estado.assinado) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        estado.motivoIndisponivel?.let { motivo ->
            // Mapa vazio e mapa indisponível são indistinguíveis para quem olha, e
            // a leitura errada é a perigosa: "ninguém por perto" quando a verdade
            // é "não estou recebendo".
            Text(motivo, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            return@Column
        }

        if (estado.pares.isEmpty()) {
            Text(
                "Nenhum par publicando posição agora.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(estado.pares, key = { it.indicativo }) { par -> CartaoDePar(par) }
        }
    }
}

@Composable
private fun CartaoDePar(par: ParNoMapa) {
    // O esmaecimento carrega informação, não estética: é a diferença entre "está
    // ali" e "estava ali". Alpha e não uma cor diferente, porque cor já está
    // ocupada por prioridade em todo o resto do app.
    val alfa = when (par.frescor) {
        Frescor.ATUAL -> 1f
        Frescor.ESMAECIDO -> 0.45f
        Frescor.ANTIGO -> 0.3f
    }
    val cor = MaterialTheme.colorScheme.onSurface.copy(alpha = alfa)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(par.indicativo, fontWeight = FontWeight.SemiBold, color = cor)
            Spacer(Modifier.height(2.dp))
            Text(
                when (par.frescor) {
                    // Acima de dez minutos o marcador para de afirmar posição.
                    // Dizer "a 400 metros, nordeste" a partir de um dado de meia
                    // hora atrás é uma afirmação sobre o presente feita com
                    // informação do passado.
                    Frescor.ANTIGO -> par.idadeFalada.orEmpty()
                    else -> listOfNotNull(
                        par.distanciaFalada,
                        par.rumoFalado.takeIf { it.isNotBlank() },
                        "deslocando".takeIf { par.emMovimento },
                    ).joinToString(", ")
                },
                fontSize = 13.sp,
                color = cor,
            )
        }
    }
}
