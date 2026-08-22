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
import com.claryon.field.ui.QuemMeConsultouViewModel
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
    /** Quem consultou a posição deste agente. Ver `QuemMeConsultouViewModel`. */
    consultas: QuemMeConsultouViewModel.Estado,
    aoSair: () -> Unit,
    /** Abre a perícia da cadeia de custódia. Ver [TelaDePericia]. */
    aoAbrirPericia: () -> Unit,
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

        QuemMeConsultou(consultas)

        Box(Modifier.height(Espaco.Secao))

        // **A porta da perícia.** Sem esta seção, `verificar()` e `Manifesto.ler()`
        // continuariam com zero chamadores em `src/main`: o produto selaria a
        // âncora de fim em produção e conferiria só em teste, e periciar exigiria
        // `adb` sobre o diretório privado — o mesmo acesso que o modelo de ameaça
        // trata como atacante. Fica no perfil, junto do relatório de prontidão e do
        // log de acesso, porque as três respondem à mesma família de pergunta: o
        // que este aparelho está fazendo com o que é meu.
        Column(Modifier.padding(horizontal = Espaco.Padrao)) {
            Etiqueta("Cadeia de custódia")
            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(
                "Confere as gravações seladas neste aparelho contra o manifesto e a " +
                    "âncora de fim.",
                cor = Cores.TintaFraca,
            )
            Box(Modifier.height(Espaco.Medio))
            BotaoTatico("Periciar a custódia", aoAbrirPericia)
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

/**
 * **Quem perguntou onde eu estou.**
 *
 * A outra metade do log de acesso da `0017`. Aquela migração registra o ATO de
 * consultar — nunca a resposta — e o argumento dela é que um sistema que concede o
 * poder de saber onde um colega está sem deixar rastro não distingue apoio a
 * caminho de vigilância. Esta lista devolve esse rastro ao titular.
 *
 * **Nunca mostra o que foi respondido**, porque o servidor não guarda: distância,
 * azimute e velocidade não entram na tabela. Um log que guardasse a resposta seria
 * um histórico de posição com outro nome — exatamente o que a `0016` gastou uma
 * migração para impedir.
 *
 * Lista vazia é afirmação, não espaço em branco: "ninguém consultou" é informação,
 * e o silêncio seria ambíguo entre isso e uma falha de rede.
 */
@Composable
private fun QuemMeConsultou(estado: QuemMeConsultouViewModel.Estado) {
    Column(Modifier.padding(horizontal = Espaco.Padrao)) {
        Etiqueta("Quem consultou sua posição")
        Box(Modifier.height(Espaco.Curto))
        when (estado) {
            is QuemMeConsultouViewModel.Estado.Carregando ->
                TextoCorpoMenor("Lendo o registro…", cor = Cores.TintaFraca)

            is QuemMeConsultouViewModel.Estado.Indisponivel ->
                TextoCorpoMenor(estado.motivo, cor = Cores.TintaMedia)

            is QuemMeConsultouViewModel.Estado.Lista -> if (estado.consultas.isEmpty()) {
                TextoCorpoMenor(
                    "Ninguém consultou sua posição.",
                    cor = Cores.TintaMedia,
                )
            } else {
                estado.consultas.take(20).forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Espaco.Micro),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextoCorpoMenor(c.indicativo, cor = Cores.Tinta)
                        TextoCorpoMenor(c.quando, cor = Cores.TintaFraca)
                    }
                }
                // **Truncar em silêncio numa tela de auditoria esconde consulta.**
                // O servidor devolve até 100 (`0017`); mostrar 20 sem dizer faria o
                // agente concluir que ninguém mais o consultou.
                if (estado.consultas.size > 20) {
                    TextoCorpoMenor(
                        "e mais ${estado.consultas.size - 20} consultas",
                        cor = Cores.TintaFraca,
                    )
                }
            }
        }
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(
            "O registro guarda quem perguntou — nunca o que foi respondido.",
            cor = Cores.TintaFraca,
        )
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
        PontoDeEstado(if (c.viva) Cores.TintaFraca else Cores.Tinta)
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
        Etiqueta(if (c.viva) "ok" else "parado", cor = if (c.viva) Cores.TintaFraca else Cores.Tinta)
    }
}
