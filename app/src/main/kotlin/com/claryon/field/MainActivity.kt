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
import com.claryon.agent.LexicoDeOcorrencias
import com.claryon.agent.ModoOperacao
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.oculos.SessaoDosOculos
import com.claryon.field.permissoes.PermissoesEssenciais
import com.claryon.field.service.CopilotService
import com.claryon.field.voice.EstadoDaEscuta
import com.claryon.field.radio.CanaisDoAgente
import com.claryon.field.radio.CanalDoPiloto
import com.claryon.field.radio.RadioViewModel
import com.claryon.field.ui.CascoTatico
import com.claryon.field.ui.Destino
import com.claryon.field.ui.marca.AberturaDoTurno
import com.claryon.field.ui.CopilotoViewModel
import com.claryon.field.ui.OculosViewModel
import com.claryon.field.ui.MapaViewModel
import com.claryon.field.ui.telas.Capacidade
import com.claryon.field.ui.telas.TelaDeGuarnicao
import com.claryon.field.ui.telas.TelaDeLogin
import com.claryon.field.ui.QuemMeConsultouViewModel
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
                // A abertura vem antes de tudo, inclusive das permissões: é a
                // apresentação do produto, e ela cobre a leitura do cofre que
                // acontece em paralelo. Ver `AberturaDoTurno`.
                var abrindo by remember { mutableStateOf(true) }

                // O estado da sessão é OBSERVADO, não perguntado: `autenticado()`
                // lê o cofre cifrado e a primeira leitura custa ~468 ms de
                // Keystore. Ver `SessaoDoAgente`.
                val sessao by SessaoDoAgente.estado.collectAsState()

                when {
                    abrindo -> AberturaDoTurno(
                        // A abertura só sai quando o cofre respondeu. Enquanto
                        // `Verificando`, a condição logo abaixo desenhava PRETO —
                        // a espera existia e não tinha rosto.
                        sessaoResolvida =
                            sessao !is SessaoDoAgente.EstadoDaSessao.Verificando,
                        aoConcluir = { abrindo = false },
                    )

                    mostrarPermissoes ->
                        TelaDePermissoes(aoConcluir = { mostrarPermissoes = false })

                    // Ainda lendo o cofre: NÃO mostrar login, senão a tela pisca
                    // na cara de quem já tem sessão. Depois da abertura isto só
                    // acontece se a sessão voltar a `Verificando`, o que hoje não
                    // ocorre — a condição fica como rede de segurança, não como
                    // caminho esperado.
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
                            // **O `stopSession()` mora AQUI agora**, e não no
                            // `onCleared()` do `OculosViewModel`. Lá ele matava a
                            // sessão toda vez que a tela morria — e um fluxo de
                            // câmera de 5 s com o celular no bolso não existia.
                            // "Encerrar turno" é a única ação em que o agente
                            // declara que parou de trabalhar; é o recorte certo,
                            // o mesmo do `CopilotService` na linha acima. Ver
                            // `specs/dono-de-processo-para-a-facade-do-dat.spec.md`.
                            SessaoDosOculos.encerrar()
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
    val quemFala by radio.quemFala.collectAsState()
    val noAr by radio.noAr.collectAsState()
    val copilotoOcupado by copiloto.copilotoOcupado.collectAsState()
    val estadoMapa by mapa.estado.collectAsState()
    val escuta by CopilotService.estadoDaEscuta.collectAsState()
    val quemMeConsultou: QuemMeConsultouViewModel = viewModel()
    val consultas by quemMeConsultou.estado.collectAsState()
    // Lê ao ENTRAR no perfil, não em laço: a tela não fica aberta, e sondar
    // transformaria a própria consulta de transparência numa fonte de tráfego.
    LaunchedEffect(destino) {
        if (destino == Destino.PERFIL) quemMeConsultou.carregar()
    }
    val registro by oculos.registration.collectAsState()
    // A causa tipada que o `errorStream` entrega. Ela era coletada desde 21/08 e
    // **não tinha consumidor nenhum em produção** — causa capturada e não exibida é
    // causa descartada com passos extras.
    val motivoDosOculos by oculos.motivoDosOculos.collectAsState()

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
        // O publicador **não** é injetado daqui: o serviço constrói o próprio,
        // onde a escrita acontece. Injetar daqui fazia a coleta depender de alguém
        // ter passado por esta linha — e o sistema recria o serviço por
        // `START_STICKY` sem tela nenhuma ter rodado.
        CopilotService.iniciar(contexto, ModoOperacao.ATIVO)

        // **A sessão dos óculos abre com o turno — e este era o chamador que não
        // existia.** `grep -rn "startSession()" app/src/main` devolvia zero: a
        // sessão do DAT nunca era aberta em produção, só pelo painel de
        // diagnóstico e pelos testes. Sem esta linha, dar dono de processo à
        // fachada trocaria uma capacidade morta por outra.
        //
        // Não suspende e não bloqueia: instala um vigia no escopo do processo, que
        // só toca o SDK quando os óculos estiverem REGISTERED. Abrir sob demanda
        // não serviria — `startSession()` tem teto de 12 s e a consulta de placa
        // tem 5 s para o OCR inteiro.
        SessaoDosOculos.abrir()
        // O canal provisório é o ponto de partida; `RadioViewModel` reconcilia com
        // o cadastro assim que o léxico chega. Ver `CanaisDoAgente.grupoCorrenteId`.
        radio.abrir(
            canal = CanaisDoAgente.grupoCorrenteId,
            nomeDoCanal = CanaisDoAgente.grupoCorrenteNome,
            agenteId = AGENTE_DEMO,
            indicativo = INDICATIVO_DEMO,
        )
        onDispose {
            radio.fechar()
            // O serviço **não** para aqui: é justamente ele que mantém a posição
            // subindo com o app fechado. Só o "Encerrar turno" o derruba, que é a
            // única ação em que o agente declara que parou de trabalhar.
            //
            // **E a sessão dos óculos também não.** Fechá-la aqui seria o
            // `onCleared()` do `OculosViewModel` com outro nome — o defeito que
            // este bloco veio consertar.
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
                // Sem esta linha, `AUTOR_NAO_CONFIRMADO` continuaria sem caminho
                // até a tela: a régua de presença casa por indicativo e ele não
                // casa com par nenhum. Ver o KDoc de `RadioViewModel.quemFala`.
                quemEstaNoAr = quemFala,
                modifier = modifier,
            )

            Destino.MAPA -> TelaDoMapa(
                estado = estadoMapa,
                aoAbrir = mapa::abrirMapa,
                aoFechar = mapa::fecharMapa,
                aoFocar = mapa::focarPar,
                modifier = modifier,
            )

            Destino.PERFIL -> TelaDePerfil(
                indicativo = INDICATIVO_DEMO,
                matricula = AGENTE_DEMO,
                unidade = "GTA-3",
                canal = CanalDoPiloto.NOME,
                capacidades = capacidadesDe(
                    estadoPtt,
                    registro.name,
                    estadoMapa.assinado,
                    escuta,
                    LexicoDeOcorrencias.gazetteer.size,
                    motivoDosOculos,
                ),
                consultas = consultas,
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
/**
 * Abaixo disto a lista é semente, não gazetteer.
 *
 * Não é um número medido — é uma ordem de grandeza: nenhuma cidade tem 50
 * logradouros, então qualquer lista menor que isso é claramente de teste. O papel
 * da constante é impedir que a semente passe por capacidade viva.
 */
private const val LISTA_UTIL = 50

private fun capacidadesDe(
    ptt: com.claryon.field.ui.componentes.EstadoDoPtt,
    registro: String,
    mapaAssinado: Boolean,
    escuta: EstadoDaEscuta,
    logradouros: Int,
    motivoDosOculos: String?,
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
            //
            // **A causa tipada tem precedência sobre o texto do registro.** Óculos
            // REGISTERED cuja sessão não sobe (hastes dobradas, permissão do Meta
            // AI negada, bateria no fim) caíam no `else` e ouviam "Verifique se
            // estão ligados" — a única frase que descreve o que NÃO aconteceu.
            motivo = motivoDosOculos ?: when (registro) {
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
        // **A capacidade que passou uma fase inteira parecendo existir.** O
        // detector media 26/26 em bancada e não tinha chamador nenhum em
        // produção; os modelos estavam no APK de teste. Ninguém tinha como
        // perceber, porque nada no aplicativo dizia se ele estava ouvindo.
        //
        // Motivo derivado do estado, nunca string fixa — a linha do mapa, logo
        // acima, registra o que acontece quando é fixa: ela mente.
        // **O cabeçalho do asset AFIRMAVA que esta linha existia**, e ela não
        // existia — a promessa foi escrita no mesmo commit que ligou o gazetteer.
        // Sem ela, o único sinal era um `Log.i`, e uma lista vazia por asset
        // faltando no APK falharia exatamente em silêncio.
        Capacidade(
            nome = "Logradouros conhecidos",
            // **`>= LISTA_UTIL`, e não `> 0`.** Com `> 0` a semente de duas entradas
            // acendia VERDE — e o painel só mostra o motivo nas capacidades mortas,
            // então o aviso "isto é semente" nunca apareceria. Um instrumento que
            // diz OK sobre uma lista que não reconhece rua nenhuma é pior que
            // instrumento nenhum: ele encerra a pergunta.
            viva = logradouros >= LISTA_UTIL,
            motivo = when {
                logradouros == 0 ->
                    "Nenhum carregado: o endereço da ocorrência não será reconhecido."
                logradouros < LISTA_UTIL ->
                    "$logradouros carregados — é semente de teste. A lista da " +
                        "corporação ainda não entrou, e sem ela o endereço falado " +
                        "vira nome de pessoa."
                else -> "$logradouros logradouros."
            },
        ),
        Capacidade(
            nome = "Palavra de ativação",
            viva = escuta == EstadoDaEscuta.OUVINDO,
            motivo = when (escuta) {
                EstadoDaEscuta.OUVINDO -> "Escutando \"Hey Claryon\"."
                EstadoDaEscuta.EM_PAUSA -> "Em pausa o microfone fica fechado. Volte para o serviço."
                EstadoDaEscuta.SEM_ROTA -> "Sem rota de áudio. Conecte os óculos ou o fone."
                EstadoDaEscuta.SEM_MODELO -> "Modelo de ativação ausente no aplicativo."
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
