package com.claryon.field.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.ParPresente
import com.claryon.field.ui.telas.TelaDeGuarnicao
import com.claryon.field.ui.tema.TemaClaryon

/**
 * **Vitrine da tela de guarnição — só no APK de debug.**
 *
 * Existe para capturar a tela com tráfego sintético sem depender de sessão, rede
 * e canal ao vivo. Não é caminho de produto e não tem entrada no launcher: abre
 * por `adb shell am start -n com.claryon.field/.ui.VitrineDaGuarnicao`.
 *
 * Os casos são os que a tela promete e que o caminho feliz não exercita: alerta
 * classificado, autoria não conferida, fala sem transcrição, e transmissão
 * própria que não saiu.
 */
class VitrineDaGuarnicao : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quemNoAr = intent.getStringExtra("no_ar")
        // `-e volume N` repete o roteiro para exercitar a rolagem longa: com sete
        // registros o caminho de volta ao fim NUNCA aparece, e é correto — a
        // folga de três posições ainda cobre a lista inteira.
        val voltas = intent.getStringExtra("volume")?.toIntOrNull() ?: 1
        val falas = (0 until voltas).flatMap { v ->
            FALAS.map { it.copy(id = "${it.id}-$v") }
        }
        setContent {
            TemaClaryon {
                // `statusBarsPadding` reproduz o que `CascoTatico` aplica em
                // produção; sem isto a captura sai com a barra de status por cima
                // do cabeçalho e parece defeito da tela.
                TelaDeGuarnicao(
                    modifier = Modifier.statusBarsPadding(),
                    canal = "GTA-3 Alfa",
                    pares = listOf(
                        ParPresente("BRAVO UM", online = true, falando = false),
                        ParPresente("ALFA DOIS", online = true, falando = false),
                        ParPresente("CHARLIE 4", online = false, falando = false),
                    ),
                    falas = falas,
                    estadoDoPtt = EstadoDoPtt.Pronto("GTA-3 Alfa"),
                    aoPressionarPtt = {},
                    aoSoltarPtt = {},
                    aoAbrirCopiloto = {},
                    quemEstaNoAr = quemNoAr,
                )
            }
        }
    }
}

private val FALAS = listOf(
    FalaNoGrupo(
        "1", "BRAVO UM", "14:58:12",
        "Guarnição, em deslocamento para a Rua Marechal Deodoro, altura do número 400.",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
    FalaNoGrupo(
        "2", "BRAVO UM", "14:58:41",
        "Trânsito parado no viaduto, vou pela marginal.",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
    FalaNoGrupo(
        "3", "GTA-3", "15:01:02",
        "Entendido, sigo para o mesmo endereço pela Rua Sete.",
        propria = true, prioridade = null, entrega = FalaNoGrupo.Entrega.ENVIADA,
    ),
    // Autoria que não fechou no cadastro do grupo.
    FalaNoGrupo(
        "4", "", "15:02:20",
        "Apoio imediato na praça, tem gente armada.",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
    FalaNoGrupo(
        "5", "ALFA DOIS", "15:03:00",
        "Confirmo visual do veículo, placa parcial ABC.",
        propria = false, prioridade = 1, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
    // Áudio que ainda não foi transcrito, e transmissão que não saiu.
    FalaNoGrupo(
        "6", "GTA-3", "15:03:38", "",
        propria = true, prioridade = null, entrega = FalaNoGrupo.Entrega.NAO_SAIU,
    ),
    FalaNoGrupo(
        "7", "CHARLIE 4", "15:04:10",
        "Estou a dois quarteirões, chegando pelo lado oposto.",
        propria = false, prioridade = 3, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
)
