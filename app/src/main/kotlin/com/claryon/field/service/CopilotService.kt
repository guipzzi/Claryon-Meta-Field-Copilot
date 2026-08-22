package com.claryon.field.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import com.claryon.agent.ThermalGovernor
import com.claryon.agent.TipoServico
import com.claryon.field.audio.AudioDoAgente
import com.claryon.field.audio.SaidaUnica
import com.claryon.field.local.ColetorDePosicao
import com.claryon.field.local.FonteDoSistema
import com.claryon.field.local.TransmissaoDePosicao
import com.claryon.field.voice.CopilotoDoAgente
import com.claryon.field.voice.EscutaDeAtivacao
import com.claryon.field.voice.EstadoDaEscuta
import com.claryon.field.voice.comoOuvido
import com.claryon.voice.DetectorDeAtivacao
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.common.Telemetry
import com.claryon.common.Priority
import com.claryon.agent.Utterance
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.net.PoliticaDeReconexao
import com.claryon.net.PublicadorDePosicao
import com.claryon.net.PublicadorDePosicaoSupabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.claryon.field.MainActivity
import com.claryon.field.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Serviço de primeiro plano do pipeline contínuo (sessão com os óculos, captura
 * HFP e — em ocorrência — câmera).
 *
 * Armadilhas endereçadas (docs/PADROES_DE_ENGENHARIA.md):
 *  - `foregroundServiceType` declarado **no manifest e em `startForeground()`**
 *    (Android 14+ derruba o serviço se divergirem);
 *  - o tipo é **derivado do modo** por [PowerPolicy.tiposDeServico] — em Standby
 *    o serviço não pede microfone nem câmera;
 *  - **iniciar sempre de tela visível** ([iniciar] é chamado da UI): iniciar FGS
 *    em background lança `ForegroundServiceStartNotAllowedException`;
 *  - trocar de modo **atualiza** o serviço em vez de recriá-lo.
 */
class CopilotService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Escopo do serviço, não do ViewModel.
     *
     * A coleta de posição tem de sobreviver à tela: o agente fecha o app, guarda
     * o celular no bolso, e a guarnição continua vendo onde ele está. Um escopo
     * de ViewModel morre com a composição, que é exatamente o oposto do
     * comportamento de compartilhamento contínuo.
     */
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var coletor: ColetorDePosicao? = null
    private var escuta: EscutaDeAtivacao? = null

    /** Id do ciclo aberto pela última detecção, para o ciclo de voz reaproveitar. */
    @Volatile
    private var ultimaAtivacao: String? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        coletor = ColetorDePosicao(
            escopo = escopo,
            publicar = { lat, lon, precisao, velocidade, nanos ->
                val escritor = escritorDePosicao()
                escritor.publicar(lat, lon, precisao, velocidade, nanos)
                // **Aqui está o consumidor que `publicando()` não tinha.** A
                // interface `PublicadorDePosicao.publicar` devolve `Unit` — a falha
                // vive num flag interno do publicador, e enquanto ninguém lia esse
                // flag o coletor tratava fracasso e sucesso como a mesma coisa. Uma
                // linha, e o `onFailure` que era código morto vira um `Boolean`
                // que a tela do mapa lê.
                escritor.publicando()
            },
            fonte = FonteDoSistema(this),
        )
        escuta = construirEscuta().also { viva ->
            escopo.launch { viva.estado.collect { _estadoDaEscuta.value = it } }
        }
        // **O ciclo roda AQUI, e num coletor separado.** Chamá-lo de dentro do
        // `aoDetectar` pareceria mais simples e travaria o `collect` do microfone
        // pelos 8 s do ciclo — o fluxo é o mesmo que alimenta o detector, e a
        // escuta ficaria surda por baixo, com quadros descartados por contrapressão.
        //
        // E roda no serviço, não num ViewModel: era essa a metade que faltava. A
        // escuta e o earcon já sobreviviam à tela; o comando, não.
        escopo.launch {
            ativacoes.collect {
                // O MESMO id que a detecção abriu: é o que liga `WAKE_DETECTED` ao
                // `EARCON_PLAYED` e faz a meta de 500 ms existir como número.
                CopilotoDoAgente.de(applicationContext).cicloDeVoz(ultimaAtivacao)
            }
        }
        // Antes de o coletor começar: sem turno, toda publicação é recusada.
        abrirTurno()
    }

    /**
     * A escuta da palavra de ativação, no serviço e não num ViewModel.
     *
     * Pela mesma razão do coletor de posição: o agente guarda o celular no bolso e
     * a tela apaga. Escuta presa a `viewModelScope` morreria com a composição, que
     * é o oposto de "mãos livres" — e mãos livres é a justificativa operacional do
     * produto inteiro, registrada em D1: com uma mão na pistola e outra no volante
     * não há mão para tocar nos óculos.
     */
    private fun construirEscuta(): EscutaDeAtivacao {
        val audio = AudioDoAgente.de(this)
        lateinit var propria: EscutaDeAtivacao
        propria = EscutaDeAtivacao(
            escopo = escopo,
            abrirMicrofone = {
                when (val r = audio.iniciar()) {
                    is Result.Success -> audio.microfonePcm(r.value)
                    is Result.Failure -> {
                        // **Não chamar `liberar()`.** `iniciar()` falhou e não contou
                        // usuário nenhum; decrementar aqui deixaria um déficit que o
                        // próximo `liberar()` do rádio cobraria, derrubando a rota SCO
                        // por baixo de quem ainda captura.
                        Log.w(TAG, "escuta sem rota HFP: ${r.error.message}")
                        null
                    }
                }
            },
            fecharMicrofone = { audio.liberar() },
            criarDetector = {
                val bytes = runCatching {
                    assets.open("${DetectorDeAtivacao.ASSETS}/cabeca.f32").use { it.readBytes() }
                }.getOrNull()
                val cabeca = bytes?.let { DetectorDeAtivacao.cabecaDeBytes(it) }
                cabeca?.let { (pesos, vies) ->
                    val d = DetectorDeAtivacao(pesos, vies)
                    if (d.preparar(assets)) d.comoOuvido() else { d.close(); null }
                }
            },
            aoDetectar = { escore ->
                // **Abrir o ciclo ANTES do earcon.** `SaidaUnica` marca
                // `EARCON_PLAYED` no ciclo CORRENTE, no instante em que o som entra
                // no `AudioTrack`. Sem um ciclo aberto aqui, o marco cairia no
                // ciclo anterior — ou em nenhum — e a meta de 500 ms nunca fecharia.
                val cicloId = "ativacao-${System.currentTimeMillis()}"
                SaidaUnica.telemetriaDoCiclo.abrirCiclo(cicloId)
                SaidaUnica.telemetriaDoCiclo.mark(
                    cicloId,
                    Telemetry.Stage.WAKE_DETECTED,
                    System.currentTimeMillis(),
                )
                ultimaAtivacao = cicloId
                // **BOMMM — o primeiro tempo da gramática, e é aqui que a marca
                // sonora do produto existe.**
                //
                // Até 22/08 este earcon e o do fechamento do VAD eram os DOIS
                // `OUVI_VOCE`, e este comentário afirmava, com estas palavras, que
                // "eles dizem coisas diferentes". Diziam — e soavam igual. Num
                // produto sem display, dois significados com um som só é o agente
                // sem saber se pode falar ou se já foi ouvido.
                //
                // Agora é `DESPERTAR`: golpe de sino inarmônico, o único earcon com
                // ataque de golpe, som que não é de nenhum outro aparelho da
                // viatura. É dele que sai a meta `fim de "Claryon" → earcon
                // ≤ 500 ms`, que só existe se quem confirma a escuta for quem ouviu
                // a palavra. O `VoiceCycle` emite os outros dois tempos: `bipbip`
                // quando o microfone de fato abre e `trimtrim` quando o agente para
                // de falar.
                SaidaUnica.de(applicationContext)
                    .emitir(Utterance.Sinalizar(Earcon.DESPERTAR, Priority.RESPOSTA))
                // Cala pela duração de um ciclo. O `VoiceCycle` tem teto de 8 s; os
                // 10 s cobrem a resposta falada depois dele. Sem isto o copiloto
                // ouviria a própria fala e o supressor sozinho não basta — ele cobre
                // o que SAI por este processo, e o ciclo ainda vai ouvir o agente.
                propria.silenciarPor(JANELA_DO_CICLO_MS)
                _ativacoes.tryEmit(escore)
            },
            suprimido = { agora -> SaidaUnica.supressor.suprimido(agora) },
            ocupadoNoRadio = { radioNoAr() },
        )
        return propria
    }

    /**
     * O escritor de posição, construído **aqui**, onde a escrita acontece.
     *
     * Preguiçoso e memoizado: o serviço pode ser recriado pelo sistema
     * (`START_STICKY`) sem que nenhuma tela tenha rodado, e nesse caminho não há
     * quem injete nada. Antes disso ele nascia coletando o GPS e descartando,
     * porque `publicador` era nulo — o pior desperdício possível, já que o rádio
     * acorda e o dado morre no caminho.
     */
    /**
     * Abre o turno antes de a primeira posição subir — **e insiste até conseguir**.
     *
     * Sem turno aberto o servidor recusa `publicar_posicao` com `42501` (`0019`),
     * e o coletor acordaria o GPS para nada. É idempotente do lado do servidor, o
     * que torna seguro chamar a cada subida do serviço.
     *
     * **Uma tentativa só era uma armadilha de turno inteiro.** O serviço sobe junto
     * com a operação, que é exatamente o momento em que a rede tem mais chance de
     * ainda não estar de pé: o token pode não ter saído do cofre, o Wi-Fi da
     * delegacia pode estar trocando para dados, a viatura pode estar na garagem.
     * Uma falha ali e a posição não subia até o agente encerrar e reabrir o turno —
     * o que ninguém faz, porque nada avisava. O rádio, no arquivo ao lado, já
     * reconecta com backoff; a posição não tinha razão nenhuma para ser diferente.
     *
     * O teto é de 64 s, e não os 5 min de [PoliticaDeReconexao.TETO_MS]: o rádio
     * pode esperar cinco minutos porque o agente percebe um rádio mudo na hora. Um
     * turno fechado é invisível — o único sinal é o companheiro não aparecer no
     * mapa de outra pessoa.
     */
    private fun abrirTurno() {
        escopo.launch {
            val backoff = PoliticaDeReconexao(tetoMs = TETO_DO_TURNO_MS)
            while (isActive) {
                val ok = runCatching { escritorDePosicao().iniciarTurno() }.getOrDefault(false)
                TransmissaoDePosicao.turno(ok)
                if (ok) {
                    if (backoff.tentativasSeguidas > 0) {
                        Log.i(TAG, "turno aberto na tentativa ${backoff.tentativasSeguidas + 1}")
                    }
                    return@launch
                }
                val espera = backoff.proximoAtrasoMs()
                Log.w(
                    TAG,
                    "turno NÃO abriu (tentativa ${backoff.tentativasSeguidas}) — " +
                        "a posição não sobe até abrir; nova tentativa em ${espera}ms",
                )
                delay(espera)
            }
        }
    }

    private fun escritorDePosicao(): PublicadorDePosicao =
        publicador ?: escritorProprio ?: PublicadorDePosicaoSupabase(
            config = SessaoDoAgente.config,
            tokenDeSessao = { SessaoDoAgente.tokenValido(applicationContext) },
        ).also { escritorProprio = it }

    @Volatile
    private var escritorProprio: PublicadorDePosicao? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // `intent == null` significa **recriação pelo sistema** (START_STICKY após
        // morte por memória), não pedido do usuário. Voltar em ATIVO aqui
        // reabriria o microfone sem que ninguém tivesse pedido — regressão de
        // privacidade e de energia, e ainda com o app em background (risco de
        // ForegroundServiceStartNotAllowedException). O certo é encerrar e deixar
        // o operador decidir; o modo é uma escolha explícita, não um padrão.
        if (intent == null) {
            Log.w(TAG, "Serviço recriado pelo sistema sem intent — encerrando (não reabre o microfone)")
            // `startForeground` ANTES de `stopSelf`, e não é cerimônia: quando o
            // sistema entrega um `onStartCommand` originado de
            // `startForegroundService`, ele exige a notificação dentro de ~5 s ou
            // mata o processo com `ForegroundServiceDidNotStartInTimeException`.
            // Sair sem promover é um caminho de encerramento que derruba o app —
            // e derruba justamente na recriação, quando ninguém está olhando.
            entrarEmPrimeiroPlano(ModoOperacao.STANDBY)
            stopSelf()
            return START_NOT_STICKY
        }

        val modo = intent.getStringExtra(EXTRA_MODO)
            ?.let { runCatching { ModoOperacao.valueOf(it) }.getOrNull() }
            ?: ModoOperacao.ATIVO

        if (modo == ModoOperacao.STANDBY && intent.action == ACAO_PARAR) {
            // Mesma razão do bloco acima: promover e só então encerrar.
            entrarEmPrimeiroPlano(ModoOperacao.STANDBY)
            stopSelf()
            return START_NOT_STICKY
        }

        _modo.value = modo
        entrarEmPrimeiroPlano(modo)

        // A coleta acompanha o modo: cadência e provedor mudam, a existência não.
        // Standby também publica — sumir do mapa em pausa criaria a expectativa
        // errada, porque companheiro que desaparece parece em perigo.
        coletor?.ajustarPara(modo, mapaVisivel = false)
        // A escuta acompanha o modo pela MESMA regra que decidiu o tipo do serviço
        // logo acima: `hfpAberto`. Em Standby o serviço não pediu `MICROPHONE`, e
        // abrir microfone sem o tipo declarado derruba o processo no Android 14+.
        escuta?.ajustarPara(modo)
        // START_STICKY: se o sistema matar por memória, é recriado — e o ramo
        // acima decide (com segurança) o que fazer nessa recriação.
        return START_STICKY
    }

    override fun onDestroy() {
        _modo.value = ModoOperacao.STANDBY
        // **Fecha o turno onde a coleta termina.**
        //
        // O servidor também encerra por inatividade (`0019`), como rede — mas
        // depender só disso daria 30 min de turno aberto depois de o agente ter
        // largado o aparelho, e turno é o recorte que autoriza saber onde ele
        // esteve. Fora do `escopo`, que morre na linha seguinte.
        val escritor = escritorProprio ?: publicador
        if (escritor != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { escritor.encerrarTurno() }
                    .onFailure { Log.w(TAG, "turno não encerrado", it) }
            }
        }
        // O turno fechou **deste lado**, e é o que a interface precisa saber: com
        // o serviço morto nada mais publica, independentemente de o `encerrar_turno`
        // ter chegado ao servidor. Deixar `turnoAberto = true` pendurado faria a
        // tela do mapa procurar a causa na rede.
        TransmissaoDePosicao.turno(false)
        // Soltar o GPS antes de morrer. Um listener sobrevivente mantém o rádio
        // de posição acordado sem ninguém consumindo — o pior custo possível,
        // porque não aparece em lugar nenhum da interface.
        coletor?.parar()
        coletor = null
        escuta?.parar()
        escuta = null
        _estadoDaEscuta.value = EstadoDaEscuta.EM_PAUSA
        escopo.cancel()
        super.onDestroy()
    }

    /**
     * Sobe (ou atualiza) o serviço com os tipos exatos que o [modo] usa —
     * **interseccionados com as permissões de runtime concedidas**.
     *
     * Aprendizado de runtime (Android 14+): declarar `camera`/`microphone` no
     * manifest não basta; subir o FGS com esses tipos exige a permissão de
     * runtime correspondente **concedida**, senão é `SecurityException` e o
     * processo morre. Degradar (subir só com o que pode) é o comportamento
     * correto: o pipeline continua e a falta de sensor vira falha audível na
     * feature, não crash do app.
     *
     * ## Por que existe um `try/catch` aqui, e não só o filtro
     *
     * O filtro é a defesa; o `catch` é o que impede que uma falha dele mate o
     * app. Três coisas podem escapar ao filtro, e nenhuma é hipotética:
     *
     *  1. **Corrida.** O agente revoga a permissão nos ajustes entre o
     *     `checkSelfPermission` e o `startForeground`. O Android mata o processo
     *     do app quando uma permissão é revogada, mas a ordem não é garantida.
     *  2. **Máscara vazia.** Sem nenhum tipo concedido, `tipos` é `0`, e para
     *     `targetSdk >= 34` subir FGS sem tipo é `MissingForegroundServiceTypeException`.
     *     Alcançável hoje: Standby com Bluetooth **e** localização negados —
     *     Standby não pede microfone nem câmera, e as duas outras somem.
     *  3. **Regra nova do sistema** numa versão futura do Android.
     *
     * Cair aqui não pode virar tela preta. `stopSelf()` desarma o prazo de ~5 s
     * do `startForegroundService` (que mataria o processo com
     * `ForegroundServiceDidNotStartInTimeException`) e o app volta para a
     * interface, sem copiloto em segundo plano — que é a capacidade perdida, e
     * ela fica no log em vez de virar crash na frente do agente.
     */
    private fun entrarEmPrimeiroPlano(modo: ModoOperacao) {
        val concedidos = PowerPolicy.tiposDeServico(modo).filter { temPermissaoPara(it) }
        val tipos = concedidos.fold(0) { acc, t -> acc or t.androidFlag() }
        val notificacao = construirNotificacao(modo)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                startForeground(ID_NOTIFICACAO, notificacao)
            } else {
                // Máscara vazia é ilegal no Android 14+; não adianta tentar.
                if (tipos == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    throw IllegalStateException(
                        "nenhum tipo de FGS concedido para o modo $modo — " +
                            "faltam as permissões de runtime de todos eles",
                    )
                }
                startForeground(ID_NOTIFICACAO, notificacao, tipos)
            }
        } catch (e: Exception) {
            // `Exception` e não só `SecurityException`: `MissingForegroundServiceTypeException`
            // e `ForegroundServiceStartNotAllowedException` são irmãs dela por
            // `IllegalStateException`, e derrubam o app do mesmo jeito.
            Log.e(
                TAG,
                "não foi possível promover o serviço a primeiro plano no modo $modo " +
                    "(tipos concedidos: $concedidos) — o copiloto não roda em segundo plano",
                e,
            )
            stopSelf()
        }
    }

    /**
     * Permissão de runtime que cada tipo de FGS exige (além da do manifest).
     *
     * `true` quando o tipo não exige nenhuma — hoje **nenhum** tipo que usamos
     * está nesse caso. Ver [permissaoDeRuntimeDe].
     */
    private fun temPermissaoPara(tipo: TipoServico): Boolean {
        val permissao = permissaoDeRuntimeDe(tipo) ?: return true
        return checkSelfPermission(permissao) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun construirNotificacao(modo: ModoOperacao): Notification {
        val abrir = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val texto = when (modo) {
            ModoOperacao.STANDBY -> "Standby — microfone fechado"
            ModoOperacao.ATIVO -> "Ativo — ouvindo comandos"
            ModoOperacao.OCORRENCIA -> "Ocorrência — gravando"
        }
        return NotificationCompat.Builder(this, CANAL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        val canal = NotificationChannel(CANAL, "Copiloto em campo", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Pipeline de voz e sessão com os óculos" }
        nm.createNotificationChannel(canal)
    }

    companion object {
        private const val TAG = "ClaryonField"
        private const val CANAL = "claryon_copiloto"
        private const val ID_NOTIFICACAO = 1
        private const val EXTRA_MODO = "modo"
        private const val ACAO_PARAR = "com.claryon.field.PARAR"

        /**
         * Quem publica a posição no servidor.
         *
         * Injetado de fora porque o serviço não pode conhecer `core-net` nem a
         * sessão do agente — o token vive no cofre cifrado do `app`. Nulo até o
         * login: sem sessão não há a quem publicar, e o coletor descarta em
         * silêncio em vez de acumular.
         */
        /**
         * Injeção **para teste**. Em produção fica nulo e o serviço constrói o
         * próprio escritor.
         *
         * Era por aqui que o publicador chegava, vindo do `MapaViewModel` pela
         * `MainActivity` — propriedade invertida: a tela do mapa era dona do
         * escritor de posição, e desde que o mapa deixou de publicar (`0016`) ela
         * não tinha mais motivo nenhum para sê-lo. Pior, o serviço dependia de
         * alguém ter passado por aquela linha antes de ele coletar.
         */
        @Volatile
        var publicador: PublicadorDePosicao? = null

        /**
         * Cada palavra de ativação ouvida, com o escore. De processo, e não do
         * serviço: quem consome é o ciclo de voz, que vive noutro objeto.
         *
         * `DROP_OLDEST` com buffer 1 porque duas ativações em sequência não são duas
         * intenções — a segunda é eco ou insistência, e enfileirar rodaria dois
         * ciclos, o segundo sobre a resposta falada do primeiro.
         */
        private val _ativacoes = MutableSharedFlow<Float>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** Palavras de ativação ouvidas. O escore vai junto para o log honesto. */
        val ativacoes: SharedFlow<Float> = _ativacoes

        /**
         * "O agente está com o PTT pressionado?" — registrado pelo `RadioViewModel`,
         * que é quem tem o `RadioTatico`. Default `false`: sem rádio de pé, a escuta
         * não pode ficar calada esperando por uma resposta que ninguém vai dar.
         */
        @Volatile
        var radioNoAr: () -> Boolean = { false }

        private val _estadoDaEscuta = MutableStateFlow(EstadoDaEscuta.EM_PAUSA)

        /** A prontidão da palavra de ativação, para o perfil mostrar com a CAUSA. */
        val estadoDaEscuta: StateFlow<EstadoDaEscuta> = _estadoDaEscuta

        /** Teto do `VoiceCycle` (8 s) mais a resposta falada. */
        const val JANELA_DO_CICLO_MS = 10_000L

        /**
         * Teto do backoff de [abrirTurno]. 64 s é o mesmo teto que o rádio tático
         * pratica na reconexão, e o motivo de não usar os 5 min do padrão está no
         * KDoc daquela função: turno fechado é invisível para quem está com o
         * aparelho.
         */
        private const val TETO_DO_TURNO_MS = 64_000L

        private val _modo = MutableStateFlow(ModoOperacao.STANDBY)

        /** Modo corrente do pipeline — a UI observa para refletir o estado real. */
        val modo: StateFlow<ModoOperacao> = _modo

        /**
         * Sobe/atualiza o serviço no [modo]. **Chamar de tela visível** — iniciar
         * FGS em background é `ForegroundServiceStartNotAllowedException`.
         */
        fun iniciar(context: Context, modo: ModoOperacao) {
            val i = Intent(context, CopilotService::class.java).putExtra(EXTRA_MODO, modo.name)
            context.startForegroundService(i)
        }

        fun parar(context: Context) {
            val i = Intent(context, CopilotService::class.java)
                .setAction(ACAO_PARAR)
                .putExtra(EXTRA_MODO, ModoOperacao.STANDBY.name)
            // `startForegroundService` e não `startService`: a partir do Android 8
            // um serviço em primeiro plano só pode ser iniciado por este caminho
            // quando o app está em background, e `parar()` é chamado exatamente
            // dali. `startService` lançaria `IllegalStateException` — o pedido de
            // PARAR derrubando o app.
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Teto de FPS agora, cruzando a política de modo com o estado térmico.
         * `getThermalHeadroom` pode devolver `NaN` — [ThermalGovernor] trata.
         */
        fun fpsPermitidoAgora(context: Context, modo: ModoOperacao): Int {
            val padrao = PowerPolicy.perfil(modo).fpsMaximo
            if (padrao == 0) return 0
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    context.getSystemService(PowerManager::class.java)
                        .getThermalHeadroom(SEGUNDOS_PREVISAO)
                }.getOrDefault(Float.NaN)
            } else {
                Float.NaN
            }
            return ThermalGovernor.fpsPermitido(headroom, padrao)
        }

        private const val SEGUNDOS_PREVISAO = 10
    }
}

/** Tradução do espelho puro [TipoServico] para as constantes do Android. */
private fun TipoServico.androidFlag(): Int = when (this) {
    TipoServico.CONNECTED_DEVICE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    TipoServico.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    TipoServico.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    TipoServico.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
}

/**
 * Permissão de runtime que o Android exige para **subir** o FGS com cada tipo —
 * `null` se o tipo não exige nenhuma.
 *
 * ## O que `connectedDevice` custa, e por que a resposta antiga estava errada
 *
 * Aqui havia `CONNECTED_DEVICE -> return true // não exige runtime`. Falso a
 * partir do Android 14, e o preço era o app morrer na primeira operação com
 * Bluetooth negado. Reproduzido em emulador API 35, `targetSdk = 35`, com todas
 * as permissões concedidas **menos** `BLUETOOTH_CONNECT` — e o próprio sistema
 * enuncia a regra na exceção:
 *
 * ```
 * java.lang.SecurityException: Starting FGS with type connectedDevice
 *   targetSDK=35 requires permissions:
 *   all of  [android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE]
 *   any of  [BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN,
 *            CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE,
 *            NFC, TRANSMIT_IR, UWB_RANGING, USB Device, USB Accessory]
 * ```
 *
 * Ou seja: é um **"qualquer uma de"**, e o que decide não é o tipo, é o que
 * ESTE manifest declara. Das onze da lista, `AndroidManifest.xml` declara
 * exatamente **uma**: `BLUETOOTH_CONNECT` (linha 9). `android.permission.BLUETOOTH`
 * (linha 8) é a legada e **não** está na lista. Com `minSdk = 31`,
 * `BLUETOOTH_CONNECT` é sempre permissão de runtime — não há ramo de versão a
 * fazer, e negá-la deixa o app sem nenhuma qualificadora.
 *
 * Acrescentar `CHANGE_NETWORK_STATE` ao manifest satisfaria a regra sem diálogo
 * nenhum (é `normal`, concedida na instalação) e faria o crash sumir. **Não é o
 * que este projeto quer:** o tipo `connectedDevice` existe para declarar ao
 * sistema e ao agente que há uma sessão com os óculos aberta. Declará-lo
 * enquanto o Bluetooth está negado — quando não pode haver sessão nenhuma — é
 * pedir ao sistema um selo que a realidade não sustenta. Degradar é honesto;
 * contornar é mentir para o Android.
 *
 * @see CopilotService.entrarEmPrimeiroPlano
 */
internal fun permissaoDeRuntimeDe(tipo: TipoServico): String? = when (tipo) {
    TipoServico.CONNECTED_DEVICE -> android.Manifest.permission.BLUETOOTH_CONNECT
    TipoServico.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
    TipoServico.CAMERA -> android.Manifest.permission.CAMERA
    TipoServico.LOCATION -> android.Manifest.permission.ACCESS_FINE_LOCATION
}
