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
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.service.CopilotService
import com.claryon.field.radio.CanalDoPiloto
import com.claryon.field.radio.RadioViewModel
import com.claryon.field.ui.CascoTatico
import com.claryon.field.ui.Destino
import com.claryon.field.ui.CopilotoViewModel
import com.claryon.field.ui.OculosViewModel
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
                val oculos: OculosViewModel = viewModel()
                val radio: RadioViewModel = viewModel()
                // O mapa saiu do `DiagnosticsViewModel` — ver `MapaViewModel`.
                val mapa: MapaViewModel = viewModel()
                // Ciclo de voz + evidência saíram do `DiagnosticsViewModel`:
                // eles compartilham o executor, que é a máquina de estado da
                // gravação. Ver `CopilotoViewModel`.
                val copiloto: CopilotoViewModel = viewModel()

                // `tudoConcedido`, não `podeOperar`: com `podeOperar` o agente que
                // concedesse o microfone e negasse a localização ia direto à
                // operação da segunda abertura em diante, e mapa, alerta com
                // coordenada e consulta de posição ficavam mortos em silêncio.
                var mostrarPermissoes by remember { mutableStateOf(!tudoConcedido()) }
                var mostrarLogin by remember { mutableStateOf(true) }
                var destino by remember { mutableStateOf(Destino.GUARNICAO) }

                // O estado da sessão é OBSERVADO, não perguntado: `autenticado()`
                // lê o cofre cifrado e a primeira leitura custa ~468 ms de
                // Keystore. Ver `SessaoDoAgente`.
                val sessao by SessaoDoAgente.estado.collectAsState()

                when {
                    mostrarPermissoes ->
                        TelaDePermissoes(aoConcluir = { mostrarPermissoes = false })

                    // Ainda lendo o cofre: NÃO mostrar login, senão a tela pisca
                    // na cara de quem já tem sessão.
                    sessao is SessaoDoAgente.EstadoDaSessao.Verificando -> Unit

                    mostrarLogin && sessao is SessaoDoAgente.EstadoDaSessao.Ausente -> TelaDeLogin(
                        auth = oculos.autenticacao,
                        configurado = oculos.redeConfigurada,
                        aoEntrar = {
                            SessaoDoAgente.anunciar(true)
                            mostrarLogin = false
                        },
                        aoSeguirSemRede = { mostrarLogin = false },
                    )

                    else -> Operacao(
                        oculos = oculos,
                        radio = radio,
                        mapa = mapa,
                        copiloto = copiloto,
                        destino = destino,
                        aoNavegar = { destino = it },
                        aoEncerrarTurno = {
                            CopilotService.parar(this@MainActivity)
                            radio.fechar()
                            oculos.autenticacao.sair()
                            SessaoDoAgente.anunciar(false)
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
    oculos: OculosViewModel,
    radio: RadioViewModel,
    mapa: MapaViewModel,
    copiloto: CopilotoViewModel,
    destino: Destino,
    aoNavegar: (Destino) -> Unit,
    aoEncerrarTurno: () -> Unit,
) {
    val estadoPtt by radio.estado.collectAsState()
    val falas by radio.falas.collectAsState()
    val pares by radio.pares.collectAsState()
    val noAr by radio.noAr.collectAsState()
    val copilotoOcupado by copiloto.copilotoOcupado.collectAsState()
    val estadoMapa by mapa.estado.collectAsState()
    val registro by oculos.registration.collectAsState()

    LaunchedEffect(Unit) {
        oculos.anunciarEstadoDegradado()
        // As permissões passaram a ser anunciadas por quem tem a fila de som.
        copiloto.anunciarCapacidadesPerdidas()
    }

    val contexto = LocalContext.current
    DisposableEffect(Unit) {
        // O rádio lê o histórico com o token do agente; quem o guarda é o cofre
        // cifrado, que vive no ViewModel de diagnóstico.
        radio.tokenDeSessao = { oculos.autenticacao.tokenValido() }

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
            canal = CanalDoPiloto.ID,
            nomeDoCanal = CanalDoPiloto.NOME,
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
                canal = CanalDoPiloto.NOME,
                pares = pares,
                falas = falas,
                estadoDoPtt = estadoPtt,
                aoPressionarPtt = radio::aoPressionar,
                aoSoltarPtt = radio::aoSoltar,
                // O ciclo de voz ganha porta de entrada. Estava pronto, testado e
                // inalcançável desde o commit d888970: o único chamador vivia numa
                // tela que não é composta.
                aoAbrirCopiloto = copiloto::cicloDeVoz,
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
                canal = CanalDoPiloto.NOME,
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
 * Identidade de demonstração do agente. O canal saiu daqui: ver [CanalDoPiloto],
 * que é a fonte única — o mesmo UUID chegou a estar escrito em três arquivos sem
 * import cruzado, e mapa e rádio apontavam para o mesmo grupo por digitação.
 *
 * No produto isto vem do cadastro junto da sessão.
 */
private const val AGENTE_DEMO = "41882"
private const val INDICATIVO_DEMO = "Alfa Um"
