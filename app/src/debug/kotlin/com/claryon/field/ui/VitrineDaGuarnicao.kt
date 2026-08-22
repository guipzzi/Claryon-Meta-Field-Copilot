package com.claryon.field.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.CanalCorrente
import com.claryon.field.ui.telas.Guarnicao
import com.claryon.field.ui.telas.MembroDaGuarnicao
import com.claryon.field.ui.telas.GuarnicaoNaLista
import com.claryon.field.ui.telas.ResultadoDaTroca
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
 *
 * **O roteiro base é fixo de propósito.** Ele é a régua das capturas antes/depois:
 * mexer nele invalida a comparação, que é a única forma de um acabamento visual ser
 * julgado por evidência em vez de por memória. Caso novo entra por trás de uma
 * chave — ver [DUROS] —, nunca acrescentado ao meio.
 */
class VitrineDaGuarnicao : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quemNoAr = intent.getStringExtra("no_ar")
        // `-e volume N` repete o roteiro para exercitar a rolagem longa: com sete
        // registros o caminho de volta ao fim NUNCA aparece, e é correto — a
        // folga de três posições ainda cobre a lista inteira.
        val voltas = intent.getStringExtra("volume")?.toIntOrNull() ?: 1
        // `-e duros 1` acrescenta os cruzamentos, no fim, onde a lista já para.
        // `-e duros 1` acrescenta os cruzamentos; `-e cortadas 1`, as falas que a
        // rede truncou. Somam-se de forma independente, e sempre NO FIM — o roteiro
        // base tem de sair idêntico às capturas anteriores, ou a comparação some.
        val roteiro = FALAS +
            (if (intent.getStringExtra("duros") != null) DUROS else emptyList()) +
            (if (intent.getStringExtra("cortadas") != null) CORTADAS else emptyList())
        // `-e ptt negado|ocupado|noar` — o estado do rádio.
        //
        // Existe por causa do painel de detalhes: a parte dele que mais importa é
        // a **recusa** do canal privado, e o caminho feliz nunca a desenha. Sem
        // isto, "Você NÃO está no canal" seria código que ninguém nunca viu — que
        // é como um estado de erro chega quebrado ao campo.
        val estadoDoPtt = when (intent.getStringExtra("ptt")) {
            "negado" -> EstadoDoPtt.Indisponivel(
                "Canal negado. Unauthorized: You do not have permissions to read " +
                    "from this Channel topic",
            )
            "ocupado" -> EstadoDoPtt.Ocupado("BRAVO UM")
            "noar" -> EstadoDoPtt.NoAr(decorridoMs = 4_200, amplitude = 0.62f)
            else -> EstadoDoPtt.Pronto("GTA-3 Alfa")
        }
        val falas = (0 until voltas).flatMap { v ->
            roteiro.map { it.copy(id = "${it.id}-$v") }
        }
        setContent {
            TemaClaryon {
                // Reproduz o que `CascoTatico` aplica em produção; sem isto a
                // captura sai com a barra de status por cima do cabeçalho e parece
                // defeito da tela.
                //
                // `navigationBarsPadding` entrou junto em 21/08: em produção quem
                // consome a barra de baixo é a `BarraDeNavegacao` do casco, que a
                // vitrine não tem. Sem ele o bloco de fala encostava na barra de
                // gestos **só na captura**, e a comparação antes/depois julgava um
                // defeito que o produto não tem.
                TelaDeGuarnicao(
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    // **Trocado em 21/08 junto com a fonte de dados da tela.**
                    //
                    // Era `pares: List<ParPresente>`, derivado de `posicoes_do_grupo` —
                    // que faz `join` com `agent_positions` e portanto SOME com quem nunca
                    // publicou posição. A contagem não era a guarnição; era quem tinha
                    // posição. `Guarnicao` vem do cadastro e sabe quem existe;
                    // `idadeDaPosicaoS` diz há quanto tempo cada um foi visto, e `null`
                    // é "nunca publicou" — que é informação, não ausência dela.
                    canal = CanalCorrente(
                        id = "gta-3-alfa",
                        nome = "GTA-3 Alfa",
                        confirmadoPeloServidor = true,
                    ),
                    guarnicao = Guarnicao(
                        membros = listOf(
                            MembroDaGuarnicao("BRAVO UM", idadeDaPosicaoS = 12),
                            MembroDaGuarnicao("ALFA DOIS", idadeDaPosicaoS = 47),
                            // Passou dos 120 s: existe no cadastro, sem posição recente.
                            MembroDaGuarnicao("CHARLIE 4", idadeDaPosicaoS = 900),
                            // `null` = NUNCA publicou. É quem o `join` antigo apagava.
                            MembroDaGuarnicao("DELTA CINCO", idadeDaPosicaoS = null),
                        ),
                        cadastroCarregado = true,
                    ),
                    falas = falas,
                    estadoDoPtt = estadoDoPtt,
                    aoPressionarPtt = {},
                    aoSoltarPtt = {},
                    // `aoAbrirCopiloto` saiu: o atalho do copiloto foi retirado da barra
                    // desta tela por decisão do usuário em 21/08. A capacidade continua
                    // alcançável por VOZ, pelo gatilho — o que saiu foi o botão.
                    pediuEscuta = true,
                    aoEntrarNaEscuta = {},
                    aoSairDaEscuta = {},
                    // A lista de guarnições em que este agente PODE entrar — o botão
                    // de voltar do topo. `null` seria "ainda não sei"; aqui é a
                    // demonstração, então vem preenchida.
                    guarnicoes = listOf(
                        GuarnicaoNaLista("gta-3-alfa", "GTA-3 Alfa", "guarnicao 3 alfa", corrente = true),
                        GuarnicaoNaLista("gta-3-bravo", "GTA-3 Bravo", "guarnicao 3 bravo", corrente = false),
                    ),
                    aoEntrarEm = { ResultadoDaTroca.Entrou("GTA-3 Bravo") },
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
    // **O silêncio.** 41 minutos depois da última fala — a ocorrência fechou e o
    // canal voltou noutro momento.
    //
    // Entra no roteiro BASE, e não atrás da chave `-e duros`, contra a regra que o
    // KDoc desta classe estabelece. A razão é que a régua de tempo mudou de
    // critério em 21/08 — de hora cheia para lacuna — e sem um par de falas
    // separadas por mais de 15 min o roteiro **não desenha separador nenhum**: as
    // sete falas acima cabem em 6 minutos. Um roteiro que não exercita a régua não
    // é régua de captura para ela, e a comparação antes/depois ficaria cega
    // exatamente no item que mudou.
    //
    // Isto muda a régua de comparação com as capturas anteriores a 21/08, e a
    // mudança está escrita aqui para não ser descoberta como divergência.
    FalaNoGrupo(
        "10", "BRAVO UM", "15:45:22",
        "Ocorrência encerrada, guarnição liberada. Retomando patrulhamento.",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
)

/**
 * **Os cruzamentos.** `-e duros 1`.
 *
 * Prioridade e procedência são campos ortogonais, e o roteiro base exercita um de
 * cada vez — o que deixa sem captura exatamente o caso que este produto existe para
 * cobrir: **o P1 forjado.** Quem anuncia emergência é quem mais tem motivo para
 * escrever o indicativo que quiser, e é o único registro em que os dois sinais
 * visuais precisam conviver sem um apagar o outro: a calha vermelha de 4 px porque
 * pode ser um pedido de apoio real, e o perímetro tracejado porque o vínculo com o
 * cadastro não fechou.
 *
 * O P2 vem junto por um motivo mais prosaico: ele é o único grau de prioridade que
 * o roteiro base nunca desenha, e uma cor que ninguém olha é uma cor que ninguém
 * confere.
 */
private val DUROS = listOf(
    FalaNoGrupo(
        "8", "BRAVO UM", "15:05:04",
        "Solicito apoio de mais uma viatura no perímetro.",
        propria = false, prioridade = 2, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
    FalaNoGrupo(
        "9", "", "15:05:47",
        "Todas as unidades, converjam para a praça agora.",
        propria = false, prioridade = 1, entrega = FalaNoGrupo.Entrega.RECEBIDA,
    ),
)

/**
 * **A fala que a rede cortou no meio.** `-e cortadas 1`.
 *
 * Atrás de chave, e não no roteiro base, pela regra que o KDoc desta classe fixa: o
 * roteiro base é a régua das capturas antes/depois, e acrescentar caso ao meio dele
 * invalida toda comparação anterior a hoje.
 *
 * **O primeiro par é o teste de verdade, e é por isso que os dois textos são
 * idênticos.** `11` e `12` dizem exatamente a mesma frase; só uma foi cortada. É o
 * enunciado visual da razão pela qual `cortadaPelaRede` existe como campo em vez de
 * ser derivada — *texto incompleto é indistinguível de texto completo curto*. Se a
 * marca terminal falhar, os dois balões ficam gêmeos na captura, e o defeito se vê
 * sem ler o código.
 *
 * `13` cobre o cruzamento que importa: **cortada E sem autoria confirmada.** Os dois
 * sinais usam o mesmo morfema — tracejado —, e a captura é o que prova que eles não
 * se apagam: um é perímetro, o outro é terminal. Se alguém unificar os dois desenhos,
 * este registro deixa de ter duas leituras e passa a ter uma.
 *
 * `14` é a cortada **sem transcrição nenhuma**: o caso em que o rodapé sozinho não
 * bastaria, porque não há frase que termine abruptamente para o olho perceber.
 */
private val CORTADAS = listOf(
    FalaNoGrupo(
        "11", "ALFA DOIS", "15:06:10",
        "Suspeito seguiu a pé pela travessa, sentido",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
        cortadaPelaRede = true,
    ),
    FalaNoGrupo(
        "12", "ALFA DOIS", "15:06:31",
        "Suspeito seguiu a pé pela travessa, sentido",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
        cortadaPelaRede = false,
    ),
    FalaNoGrupo(
        "13", "", "15:07:02",
        "Tem mais um saindo pelos fundos, ele está",
        propria = false, prioridade = 1, entrega = FalaNoGrupo.Entrega.RECEBIDA,
        cortadaPelaRede = true,
    ),
    FalaNoGrupo(
        "14", "CHARLIE 4", "15:07:40", "",
        propria = false, prioridade = null, entrega = FalaNoGrupo.Entrega.RECEBIDA,
        cortadaPelaRede = true,
    ),
)
