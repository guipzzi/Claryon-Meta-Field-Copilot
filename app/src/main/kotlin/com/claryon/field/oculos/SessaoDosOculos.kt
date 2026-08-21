package com.claryon.field.oculos

import android.util.Log
import com.claryon.common.Result
import com.claryon.glasses.DatGlassesFacade
import com.claryon.glasses.RegistrationStatus
import com.claryon.glasses.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * **Dona de processo da sessão dos óculos — não do ViewModel.**
 *
 * ## O defeito que isto conserta
 *
 * `DatGlassesFacade` recebe o escopo no construtor e roda **tudo** dentro dele: o
 * `stateIn(Eagerly)` do registro, o coletor de `session.state`, o de
 * `session.errors`, o de `stream.state`, o de `stream.errorStream` e o vigia de
 * primeiro frame de `withCamera`. Ela era construída com `viewModelScope` em
 * `OculosViewModel` — logo **a fachada inteira morria no `onCleared()`**, e levava
 * junto sessão, stream e a causa tipada do erro.
 *
 * Um fluxo de câmera que dura 5 s e precisa acontecer com o celular no bolso não
 * existia. É o mesmo defeito de classe que o ciclo de voz tinha antes de
 * [com.claryon.field.voice.CerebroDoCopiloto], e a forma da correção é a mesma —
 * junto de [com.claryon.field.audio.AudioDoAgente],
 * [com.claryon.field.audio.SaidaUnica] e [com.claryon.field.voice.EscutaDoAgente],
 * que já são donos de processo pelo mesmo motivo.
 *
 * ## Por que objeto de processo, e não injeção
 *
 * Mesmo argumento de `AudioDoAgente`: o recurso disputado é **global do aparelho**,
 * não do grafo de objetos. Existe uma conexão DAT por processo, e o próprio KDoc de
 * `DatGlassesFacade.stopSession` adverte que "um `createSession` seguinte encontra a
 * anterior ativa". Dois donos é precisamente o defeito que existia: `OculosViewModel`
 * e `DiagnosticoViewModel` construíam **uma fachada cada**.
 *
 * ## Por que existe um vigia, e não uma chamada solta
 *
 * `grep -rn "startSession()" app/src/main` devolvia **zero** antes deste arquivo: a
 * sessão nunca era aberta em produção, e o `stopSession()` do `onCleared()` era uma
 * chamada sobre `activeSession == null`. Mudar só o dono deixaria a capacidade tão
 * morta quanto antes — o padrão que o §6 do `CLAUDE.md` conta seis vezes.
 *
 * E não pode ser sob demanda: `startSession()` tem teto de 12 s, enquanto o aceite
 * de `specs/consulta-de-placa-por-camera.spec.md` dá **5 s** ao OCR inteiro. A
 * sessão precisa estar de pé **antes** do pedido.
 *
 * O vigia ([abrir]) espera a condição de subida, tenta **uma vez**, e respeita um
 * intervalo mínimo. Sem óculos ele fica suspenso e não toca o SDK; com a sessão de
 * pé ele fica suspenso e não sonda nada.
 *
 * Ver `specs/dono-de-processo-para-a-facade-do-dat.spec.md` — inclusive para o que
 * se PERDE: a sessão agora sobrevive à tela, então quem nunca encerra o turno fica
 * com uma `DeviceSession` aberta enquanto o processo viver.
 */
object SessaoDosOculos {

    /**
     * O escopo do processo. **Nunca cancelado** — cancelá-lo mataria o
     * `stateIn(Eagerly)` do registro, e o app passaria a exibir `UNKNOWN` para
     * sempre sem nenhum erro aparecendo.
     */
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var instancia: DatGlassesFacade? = null

    @Volatile
    private var vigia: Job? = null

    private val _motivo = MutableStateFlow<String?>(null)

    /**
     * A última causa **tipada** da sessão ou do stream, ou `null` se nada falhou.
     *
     * Existe porque estado sozinho não é causa: `STOPPED` é a mesma palavra para
     * hastes dobradas, permissão negada, bateria no fim e superaquecimento — quatro
     * problemas com quatro ações diferentes. Os coletores rodam no escopo **do
     * processo**, então a causa continua sendo capturada com a tela apagada; era
     * essa a metade que morria junto com a fachada.
     */
    val motivo: StateFlow<String?> = _motivo.asStateFlow()

    /**
     * A fachada única do processo. Criada na primeira chamada.
     *
     * Preguiçosa de propósito: construir `DatGlassesFacade` toca
     * `Wearables.registrationState`, e o SDK exige `Wearables.initialize` antes
     * (feito em `ClaryonApp.onCreate`). Criar no carregamento da classe amarraria
     * a ordem de inicialização a quem importa este arquivo primeiro.
     */
    fun facade(): DatGlassesFacade =
        instancia ?: synchronized(this) {
            instancia ?: DatGlassesFacade(escopo).also { nova ->
                instancia = nova
                observarCausas(nova)
            }
        }

    /** Estado de registro dos óculos, do dono de processo. */
    val registro: StateFlow<RegistrationStatus> get() = facade().registration

    /** Estado da sessão do DAT, do dono de processo. */
    val estado: StateFlow<SessionStatus> get() = facade().session

    /**
     * Abre o turno dos óculos: instala o vigia que mantém a sessão de pé.
     *
     * Idempotente — chamar duas vezes não cria dois vigias. Dois vigias sobre a
     * mesma fachada disputariam `startSession()`, e em hardware um derrubaria a
     * sessão do outro.
     *
     * Não suspende: quem chama é uma composição, e prender a tela por 12 s de
     * Bluetooth seria trocar um defeito por outro. O trabalho todo vai para o
     * escopo do processo.
     */
    fun abrir() {
        synchronized(this) {
            if (vigia?.isActive == true) return
            val f = facade()
            vigia = escopo.launch { manterDePe(f) }
            Log.i(TAG, "turno dos óculos aberto — vigia de sessão instalado")
        }
    }

    /**
     * Encerra o turno: desliga o vigia e para a câmera e a sessão, **nessa ordem**.
     *
     * A ordem é a do contrato do DAT (câmera/stream primeiro, sessão depois) e já
     * está dentro de `stopSession()`. O vigia cai **antes** do `stop()`: invertido,
     * ele veria a sessão sair de `STARTED` e a levantaria de novo no mesmo instante
     * em que o agente pediu para encerrar.
     *
     * A referência da fachada é resolvida **agora**, de forma síncrona, e não dentro
     * da corrotina: se alguém trocasse a instância entre a chamada e o despacho, o
     * `stop()` cairia sobre a fachada errada — ou sobre nenhuma, deixando a sessão
     * do agente aberta em silêncio.
     */
    fun encerrar() {
        val f = synchronized(this) {
            vigia?.cancel()
            vigia = null
            instancia
        } ?: return
        escopo.launch {
            f.stopCameraStream()
            f.stopSession()
            _motivo.value = null
            Log.i(TAG, "turno dos óculos encerrado — sessão parada")
        }
    }

    /**
     * Substitui a fachada — **só para teste instrumentado**.
     *
     * `null` devolve o objeto ao estado virgem. O `@After` precisa chamar com
     * `null` pelo mesmo motivo de `AudioDoAgente.instalar`: instância vazada de um
     * teste para o seguinte carrega a sessão do anterior, e o segundo passa ou
     * falha por motivo que não é o dele.
     */
    fun instalar(substituta: DatGlassesFacade?) {
        synchronized(this) {
            vigia?.cancel()
            vigia = null
            instancia = substituta
            _motivo.value = null
        }
    }

    /**
     * O laço do vigia.
     *
     * `first { }` e não `while + delay`: com a sessão de pé, ou sem óculos, isto
     * fica **suspenso**, sem custo nenhum. Sondar seria pagar bateria pelo caso
     * normal, que é o caso em que nada precisa acontecer.
     *
     * **O laço não pode morrer por exceção.** `startSession()` devolve `Result`,
     * mas `Wearables.createSession` é do SDK e pode lançar (`WearablesException` se
     * o `initialize` não rodou, por exemplo). Sob `SupervisorJob`, uma exceção aqui
     * mataria só esta corrotina — e o turno seguiria com um vigia morto, sem
     * sessão, sem log e sem ninguém para reclamar. É o mesmo cuidado que
     * `RadioTatico.semDerrubarOProcesso` já toma no laço do rádio.
     */
    private suspend fun manterDePe(f: DatGlassesFacade) {
        while (coroutineContext.isActive) {
            combine(f.registration, f.session) { reg, ses -> reg to ses }
                .first { (reg, ses) -> reg == RegistrationStatus.REGISTERED && ses !in DE_PE }

            try {
                when (val r = f.startSession()) {
                    is Result.Success -> {
                        _motivo.value = null
                        Log.i(TAG, "sessão do DAT de pé")
                    }
                    is Result.Failure -> {
                        // Falha nunca é silêncio: a causa tipada fica publicada para
                        // a tela de prontidão, em vez de virar um estado sem porquê.
                        _motivo.value = r.error.message
                        Log.w(TAG, "sessão não subiu: ${r.error.code} — ${r.error.message}")
                    }
                }
            } catch (e: CancellationException) {
                // Encerramento de turno. Engoli-la faria o corpo seguir depois do
                // escopo morto — o mesmo defeito que `cicloDeVoz` já corrigiu.
                throw e
            } catch (e: Exception) {
                _motivo.value = "Falha ao abrir a sessão dos óculos."
                Log.e(TAG, "startSession lançou — o vigia continua", e)
            }

            // **O piso entre tentativas.** Sem ele, um par de óculos registrado que
            // recusa a sessão (sem permissão, hastes fechadas, longe demais) viraria
            // `createSession` em laço sobre o Bluetooth do agente pelo turno inteiro.
            delay(INTERVALO_ENTRE_TENTATIVAS_MS)
        }
    }

    /**
     * Coleta as causas no escopo **do processo**.
     *
     * `sessionErrors` e `streamErrors` são `MutableSharedFlow(replay = 1)` dentro da
     * fachada, mas quem os alimenta são coletores que rodam no escopo dela — o
     * mesmo escopo. Com `viewModelScope`, sair da tela cancelava a captura da causa
     * junto com tudo o mais.
     */
    private fun observarCausas(f: DatGlassesFacade) {
        escopo.launch { f.sessionErrors.collect { e -> _motivo.value = e.message } }
        escopo.launch { f.streamErrors.collect { e -> _motivo.value = e.message } }
    }

    /** Estados em que a sessão está de pé ou a caminho — não se tenta subir outra. */
    private val DE_PE = setOf(SessionStatus.STARTING, SessionStatus.STARTED)

    /**
     * Piso entre duas tentativas de subir a sessão.
     *
     * 30 s: longo o bastante para não martelar o rádio Bluetooth durante um turno
     * inteiro, curto o bastante para o agente que acabou de abrir as hastes não
     * ficar esperando algo que ele não sabe que precisa esperar.
     */
    private const val INTERVALO_ENTRE_TENTATIVAS_MS = 30_000L

    private const val TAG = "ClaryonField"
}
