package com.claryon.field.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.CabecalhoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import androidx.compose.material3.Text

/** Uma capacidade do produto e se ela está de pé agora. */
data class Capacidade(
    val nome: String,
    val viva: Boolean,
    /** Preenchido só quando está morta: **por quê**, no vocabulário do agente. */
    val motivo: String? = null,
)

/**
 * **Perfil e estado do equipamento.**
 *
 * Não é uma tela de "configurações" com interruptores. É um **relatório de
 * prontidão**: o que funciona agora, o que não funciona e por quê. É a tela que o
 * agente abre antes de sair da viatura, e a pergunta que ela responde é uma só —
 * *dá para confiar nisto no turno de hoje?*
 *
 * Quase não há o que configurar de propósito. Cada interruptor é uma forma de o
 * produto se comportar de um jeito que o agente não previu; num equipamento
 * operacional, comportamento previsível vale mais que flexibilidade.
 */
@Composable
fun TelaDePerfil(
    indicativo: String,
    matricula: String,
    unidade: String,
    canal: String,
    capacidades: List<Capacidade>,
    aoSair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CabecalhoTatico(etiqueta = "Identidade operacional", titulo = indicativo)

        Column(Modifier.padding(Espaco.Padrao)) {
            LinhaDeDado("Matrícula", matricula)
            LinhaDeDado("Unidade", unidade)
            LinhaDeDado("Talk group", canal)
        }

        Fio()
        Box(Modifier.height(Espaco.Largo))

        Column(Modifier.padding(horizontal = Espaco.Padrao)) {
            Etiqueta("Prontidão")
            Box(Modifier.height(Espaco.Medio))
        }

        for (c in capacidades) {
            LinhaDeCapacidade(c)
            Fio()
        }

        Box(Modifier.height(Espaco.Secao))

        Column(Modifier.padding(horizontal = Espaco.Padrao)) {
            // "Encerrar turno" e não "sair": nomeia o que acontece no mundo do
            // agente, não a operação técnica. E o que acontece é sério — a
            // guarnição deixa de ver a posição dele.
            BotaoTatico("Encerrar turno", aoSair, destrutivo = true)
            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(
                "Ao encerrar, sua posição sai do mapa da guarnição e o rádio fecha.",
                cor = Cores.TintaFraca,
            )
        }

        Box(Modifier.height(Espaco.Secao))
    }
}

@Composable
private fun LinhaDeDado(rotulo: String, valor: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Espaco.Curto),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Etiqueta(rotulo)
        TextoDado(valor)
    }
}

@Composable
private fun LinhaDeCapacidade(c: Capacidade) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cores.Vazio)
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PontoDeEstado(if (c.viva) Cores.Vivo else Cores.Falha)
        Box(Modifier.width(Espaco.Medio))
        Column(Modifier.weight(1f)) {
            Text(c.nome, style = Tipo.Corpo, color = if (c.viva) Cores.Tinta else Cores.TintaMedia)
            // A causa aparece junto da capacidade morta, não numa tela de ajuda.
            // Saber que algo não funciona sem saber por quê é pior que não saber.
            c.motivo?.takeIf { !c.viva }?.let {
                Box(Modifier.height(Espaco.Micro))
                TextoCorpoMenor(it, cor = Cores.TintaFraca)
            }
        }
        Etiqueta(if (c.viva) "ok" else "parado", cor = if (c.viva) Cores.Vivo else Cores.Falha)
    }
}
