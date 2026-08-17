package com.claryon.field

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.claryon.agent.ModoOperacao
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.service.CopilotService
import com.claryon.field.radio.RadioViewModel
import com.claryon.field.ui.CascoTatico
import com.claryon.field.ui.Destino
import com.claryon.field.ui.DiagnosticsViewModel
import com.claryon.field.ui.MapaViewModel
import com.claryon.field.ui.telas.Capacidade
import com.claryon.field.ui.telas.TelaDeGuarnicao
import com.claryon.field.ui.telas.TelaDeLogin
import com.claryon.field.ui.telas.TelaDePerfil
import com.claryon.field.ui.telas.TelaDePermissoes
import com.claryon.field.ui.telas.TelaDoMapa
import com.claryon.field.ui.tema.TemaClaryon

/**
 * Ponto de entrada.
 *
 * A abertura é uma sequência de portões, e nenhum é intransponível exceto o
 * primeiro: **permissões → sessão → operação**. Pular custa capacidades, e o app
 * diz quais, em voz alta, quando forem pedidas — nunca um comando que
 * simplesmente não faz nada.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Borda a borda: a moldura de "no ar" precisa alcançar as bordas físicas
        // da tela para ser o aviso que ela promete ser.
        enableEdgeToEdge()

        setContent {
            TemaClaryon {
                val diag: DiagnosticsViewModel = viewModel()
                val radio: RadioViewModel = viewModel()
                // O mapa saiu do `DiagnosticsViewModel` — ver `MapaViewModel`.
                val mapa: MapaViewModel = viewModel()

                // `tudoConcedido`, não `podeOperar`: com `podeOperar` o agente que
                // concedesse o microfone e negasse a localização ia direto à
                // operação da segunda abertura em diante, e mapa, alerta com
                // coordenada e consulta de posição ficavam mortos em silêncio.
                var mostrarPermissoes by remember { mutableStateOf(!tudoConcedido()) }
                var mostrarLogin by remember { mutableStateOf(true) }
                var destino by remember { mutableStateOf(Destino.GUARNICAO) }

                when {
                    mostrarPermissoes ->
                        TelaDePermissoes(aoConcluir = { mostrarPermissoes = false })

                    mostrarLogin && !diag.autenticacao.autenticado() -> TelaDeLogin(
                        auth = diag.autenticacao,
                        configurado = diag.redeConfigurada,
                        aoEntrar = { mostrarLogin = false },
                        aoSeguirSemRede = { mostrarLogin = false },
                    )

                    else -> Operacao(
                        diag = diag,
                        radio = radio,
                        mapa = mapa,
                        destino = destino,
                        aoNavegar = { destino = it },
                        aoEncerrarTurno = {
                            CopilotService.parar(this@MainActivity)
                            radio.fechar()
                            diag.autenticacao.sair()
                            mostrarLogin = true
                        },
                    )
                }
            }
        }
    }

    private fun tudoConcedido(): Boolean = PermissoesEssenciais.avaliar(
        PermissoesEssenciais.catalogo()
            .map { it.permissao }
            .filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet(),
    ).tudoConcedido
}

/**
 * O aplicativo em operação.
 *
 * O rádio abre junto com esta composição e fecha quando ela sai — **não** com a
 * aba da guarnição. Fechar o rádio ao trocar de aba faria o agente perder
 * transmissões enquanto olha o mapa, que é exatamente quando ele mais precisa
 * ouvir a guarnição.
 */
@Composable
private fun Operacao(
    diag: DiagnosticsViewModel,
    radio: RadioViewModel,
    mapa: MapaViewModel,
    destino: Destino,
    aoNavegar: (Destino) -> Unit,
    aoEncerrarTurno: () -> Unit,
) {
    val estadoPtt by radio.estado.collectAsState()
    val falas by radio.falas.collectAsState()
    val pares by radio.pares.collectAsState()
    val noAr by radio.noAr.collectAsState()
    val copilotoOcupado by diag.copilotoOcupado.collectAsState()
    val estadoMapa by mapa.estado.collectAsState()
    val registro by diag.registration.collectAsState()

    LaunchedEffect(Unit) { diag.anunciarEstadoDegradado() }

    val contexto = LocalContext.current
    DisposableEffect(Unit) {
        // O rádio lê o histórico com o token do agente; quem o guarda é o cofre
        // cifrado, que vive no ViewModel de diagnóstico.
        radio.tokenDeSessao = { diag.autenticacao.tokenValido() }

        // **Coleta de posição em segundo plano.**
        //
        // Sobe daqui, de tela visível: iniciar um serviço em primeiro plano a
        // partir do background é `ForegroundServiceStartNotAllowedException`.
        // E o publicador é injetado antes do `iniciar`, senão o serviço nasce
        // coletando e descartando — o pior desperdício, porque o GPS acorda e o
        // dado morre no caminho.
        CopilotService.publicador = mapa.publicadorDePosicao
        CopilotService.iniciar(contexto, ModoOperacao.ATIVO)
        radio.abrir(
            canal = CANAL_DEMO,
            nomeDoCanal = NOME_DO_CANAL,
            agenteId = AGENTE_DEMO,
            indicativo = INDICATIVO_DEMO,
        )
        onDispose {
            radio.fechar()
            // O serviço **não** para aqui: é justamente ele que mantém a posição
            // subindo com o app fechado. Só o "Encerrar turno" o derruba, que é a
            // única ação em que o agente declara que parou de trabalhar.
        }
    }

    CascoTatico(destino = destino, aoNavegar = aoNavegar, noAr = noAr) { modifier ->
        when (destino) {
            Destino.GUARNICAO -> TelaDeGuarnicao(
                canal = NOME_DO_CANAL,
                pares = pares,
                falas = falas,
                estadoDoPtt = estadoPtt,
                aoPressionarPtt = radio::aoPressionar,
                aoSoltarPtt = radio::aoSoltar,
                // O ciclo de voz ganha porta de entrada. Estava pronto, testado e
                // inalcançável desde o commit d888970: o único chamador vivia numa
                // tela que não é composta.
                aoAbrirCopiloto = diag::cicloDeVoz,
                copilotoOcupado = copilotoOcupado,
                modifier = modifier,
            )

            Destino.MAPA -> TelaDoMapa(
                estado = estadoMapa,
                aoAbrir = mapa::abrirMapa,
                aoFechar = mapa::fecharMapa,
                modifier = modifier,
            )

            Destino.PERFIL -> TelaDePerfil(
                indicativo = INDICATIVO_DEMO,
                matricula = AGENTE_DEMO,
                unidade = "GTA-3",
                canal = NOME_DO_CANAL,
                capacidades = capacidadesDe(estadoPtt, registro.name, estadoMapa.assinado),
                aoSair = aoEncerrarTurno,
                modifier = modifier,
            )
        }
    }
}

/**
 * Traduz o estado real dos subsistemas em prontidão legível.
 *
 * Cada capacidade morta traz **a causa** junto. Saber que algo não funciona sem
 * saber por quê é pior que não saber: o agente tenta de novo, no meio da rua, em
 * vez de trocar de plano.
 */
private fun capacidadesDe(
    ptt: com.claryon.field.ui.componentes.EstadoDoPtt,
    registro: String,
    mapaAssinado: Boolean,
): List<Capacidade> {
    val pttVivo = ptt !is com.claryon.field.ui.componentes.EstadoDoPtt.Indisponivel
    return listOf(
        Capacidade(
            nome = "Rádio tático",
            viva = pttVivo,
            motivo = (ptt as? com.claryon.field.ui.componentes.EstadoDoPtt.Indisponivel)?.motivo,
        ),
        Capacidade(
            nome = "Óculos conectados",
            viva = registro == "REGISTERED",
            // Sem identificador de plataforma no texto. "Registro em UNAVAILABLE"
            // é linguagem do SDK; o agente precisa saber o que fazer, e o que
            // fazer depende de qual dos estados é.
            motivo = when (registro) {
                "UNAVAILABLE" -> "Os óculos não estão pareados. Conecte pelo app Meta AI."
                "UNKNOWN" -> "Ainda verificando os óculos."
                else -> "Os óculos não responderam. Verifique se estão ligados."
            },
        ),
        Capacidade(
            nome = "Mapa da guarnição",
            viva = mapaAssinado,
            // Motivo derivado do estado, não string fixa. A anterior dizia
            // "recepção ainda não disponível no transporte" e era falsa desde que
            // o mapa passou a funcionar — o relatório de prontidão dava falso
            // negativo em 100% das aberturas, sobre a capacidade que funciona.
            motivo = if (mapaAssinado) {
                "Recebendo posições da guarnição."
            } else {
                "Abra o mapa para começar a receber posições."
            },
        ),
    )
}

/**
 * Identidade de demonstração, casada com `servidor/seed_piloto.sql`.
 *
 * No produto isto vem do cadastro junto da sessão — o servidor já impõe o
 * vínculo por RLS, e o `agent_id` das RPCs sai do JWT. Aqui é constante porque a
 * tela de seleção de talk group ainda não existe.
 *
 * **O identificador e o nome são coisas separadas.** A consulta usa o UUID; a
 * tela mostra "GTA-3 Alfa". Mostrar o UUID seria vazar chave primária para o
 * agente, e usar o nome na consulta quebraria no dia em que dois grupos se
 * chamassem igual.
 */
private const val CANAL_DEMO = "22222222-0000-0000-0000-000000000001"
private const val NOME_DO_CANAL = "GTA-3 Alfa"
private const val AGENTE_DEMO = "41882"
private const val INDICATIVO_DEMO = "Alfa Um"
