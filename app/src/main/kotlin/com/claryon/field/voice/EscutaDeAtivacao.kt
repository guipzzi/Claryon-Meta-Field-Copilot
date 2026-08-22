package com.claryon.field.voice

import android.util.Log
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PowerPolicy
import com.claryon.voice.DetectorDeAtivacao
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * O que a escuta precisa de um detector — e nada além disso.
 *
 * Existe porque [DetectorDeAtivacao] é classe concreta com ponteiro nativo: sem esta
 * costura, todo teste da escuta exigiria a `.so` do whisper e viraria instrumentado,
 * e as regras que mais podem estar erradas em silêncio (a mudez, as bordas do anel)
 * ficariam sem teste barato. `core-voice` não é tocado: quem adapta é o serviço.
 */
/**
 * O que a prontidão precisa mostrar. Enum e não `Boolean` porque "desligada" tem
 * causas diferentes, e o agente na rua precisa saber **qual** — saber que algo não
 * funciona sem saber por quê faz ele tentar de novo em vez de trocar de plano.
 */
enum class EstadoDaEscuta { OUVINDO, EM_PAUSA, SEM_ROTA, SEM_MODELO }

interface OuvidoDeAtivacao : Closeable {
    /** `true` quando a palavra foi ouvida nesta janela. */
    fun aceitar(pcm: ShortArray): Boolean

    /** Esquece o anel. Chamado nas bordas da mudez — ver o KDoc de [EscutaDeAtivacao]. */
    fun reiniciar()

    val ultimoEscore: Float
}

/** Adapta o detector real. Uma linha por método, e é só isso que a escuta enxerga. */
fun DetectorDeAtivacao.comoOuvido(): OuvidoDeAtivacao = object : OuvidoDeAtivacao {
    override fun aceitar(pcm: ShortArray) = this@comoOuvido.aceitar(pcm)
    override fun reiniciar() = this@comoOuvido.reiniciar()
    override val ultimoEscore: Float get() = this@comoOuvido.ultimoEscore
    override fun close() = this@comoOuvido.close()
}

/**
 * **A escuta contínua da palavra de ativação.** É o chamador que faltava.
 *
 * O `DetectorDeAtivacao` existia, media 26/26 em fluxo e p50 de 3,5 ms, e **não
 * tinha um único chamador em `src/main`**. Pela régua do §6 do `CLAUDE.md` isso é
 * *escrito*, não construído — o erro que este projeto já cometeu seis vezes. Faltava
 * mais que o chamador, aliás: os três arquivos do modelo viviam em
 * `androidTest/assets`, então `preparar()` chamado de produção devolvia `false` e
 * teria desligado o detector em silêncio.
 *
 * ### Onde ela pode existir, e por que não é escolha minha
 *
 * A escuta só roda onde [PowerPolicy] já declara `hfpAberto`. Não é uma regra nova:
 * é a MESMA que decide se o serviço em primeiro plano sequer pede o tipo
 * `MICROPHONE`. Em Standby o serviço não pede, então abrir microfone ali seria
 * pedir o que a política não autorizou — e a violação apareceria como um crash de
 * `ForegroundServiceTypeException` no bolso do agente, não aqui.
 *
 * Escrever `if (modo != STANDBY)` daria o mesmo resultado hoje e seria uma segunda
 * cópia da regra, livre para divergir da primeira no dia em que alguém mexer no
 * perfil de energia.
 *
 * ### As três bocas que ela precisa fechar
 *
 * 1. **A própria saída.** O earcon e o TTS do copiloto saem pelo mesmo fone que o
 *    microfone escuta. Sem filtrar, o `OUVI_VOCE` que a detecção dispara volta pelo
 *    microfone e alimenta o detector — e a proximidade fone-microfone num headset é
 *    de centímetros. Por isso o [suprimido], que é o **mesmo** `SupressorDeSaidaPropria`
 *    de processo que o rádio usa.
 * 2. **O rádio.** Enquanto o agente segura o PTT, ele está falando com pessoas, não
 *    com o copiloto. Um "Claryon" dito no ar é conversa, não comando.
 * 3. **O ciclo que ela mesma abriu.** Depois de detectar, o copiloto passa segundos
 *    ouvindo e depois falando. O refratário de 1 s do detector cobre o eco imediato;
 *    [silenciarPor] cobre o ciclo inteiro, e quem sabe a duração dele é quem o roda.
 *
 * Em todas as três a escuta chama [DetectorDeAtivacao.reiniciar] ao voltar. O anel de
 * 1 s do detector guarda áudio contínuo; sem reiniciar, ele emendaria o instante
 * anterior à mudez com o posterior e avaliaria uma janela que **nunca existiu no
 * mundo**. Detecção a partir de áudio costurado é falso positivo por construção.
 */
class EscutaDeAtivacao(
    private val escopo: CoroutineScope,
    /** Sobe a rota e devolve o PCM. `null` quando não há rota — sem óculos, sem escuta. */
    private val abrirMicrofone: suspend () -> Flow<ShortArray>?,
    /** Libera o usuário da rota. Sempre chamado quando [abrirMicrofone] devolveu fluxo. */
    private val fecharMicrofone: () -> Unit,
    private val criarDetector: () -> OuvidoDeAtivacao?,
    /** O que fazer ao ouvir a palavra. Roda no escopo da escuta, fora da Main. */
    private val aoDetectar: suspend (escore: Float) -> Unit,
    private val suprimido: (agoraMs: Long) -> Boolean = { false },
    private val ocupadoNoRadio: () -> Boolean = { false },
    private val agoraMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private var laco: Job? = null
    private var modoAtual: ModoOperacao? = null

    private val _estado = MutableStateFlow(EstadoDaEscuta.EM_PAUSA)

    /**
     * O estado real da escuta — não a intenção de escutar.
     *
     * Só vira [EstadoDaEscuta.OUVINDO] depois de o modelo carregar E a rota subir.
     * Reportar "ouvindo" no `launch` seria o mesmo erro que este arquivo conserta:
     * dizer que existe capacidade porque o código foi chamado.
     */
    val estado: StateFlow<EstadoDaEscuta> = _estado.asStateFlow()

    @Volatile
    private var mudoAte = 0L

    /** `true` enquanto o laço de escuta está de pé. Alimenta a prontidão no perfil. */
    val escutando: Boolean get() = laco?.isActive == true

    /**
     * Cala a escuta até [instanteMs]. Quem roda o ciclo de voz chama isto: é ele que
     * sabe quando o copiloto para de falar, e ninguém mais sabe.
     */
    fun silenciarPor(duracaoMs: Long) {
        mudoAte = maxOf(mudoAte, agoraMs() + duracaoMs)
    }

    fun ajustarPara(modo: ModoOperacao) {
        if (!PowerPolicy.perfil(modo).hfpAberto) {
            if (escutando) Log.i(TAG, "escuta de ativação para: $modo não abre HFP")
            parar()
            modoAtual = modo
            _estado.value = EstadoDaEscuta.EM_PAUSA
            return
        }
        // Já de pé no mesmo modo: reconfigurar derrubaria a rota e o anel do detector
        // por nada, e o intervalo de surdez seria justamente o de uma troca de modo —
        // que é quando algo acabou de acontecer.
        if (modo == modoAtual && escutando) return

        parar()
        modoAtual = modo
        laco = escopo.launch { escutar() }
    }

    fun parar() {
        laco?.cancel()
        laco = null
        modoAtual = null
    }

    /** Para e **espera** o laço morrer — o teste precisa disso para não medir corrida. */
    suspend fun pararEEsperar() {
        laco?.cancelAndJoin()
        laco = null
        modoAtual = null
    }

    private suspend fun escutar() {
        val detector = criarDetector() ?: run {
            // Sem modelo não há escuta, e isso **não** pode ser silêncio: foi
            // exatamente assim que o detector passou uma fase inteira parecendo
            // ligado. Se este aviso aparecer, os assets não entraram no APK.
            Log.w(TAG, "detector de ativação indisponível — palavra de ativação DESLIGADA")
            _estado.value = EstadoDaEscuta.SEM_MODELO
            return
        }
        val fluxo = abrirMicrofone() ?: run {
            Log.w(TAG, "sem rota de áudio — palavra de ativação desligada até o próximo modo")
            _estado.value = EstadoDaEscuta.SEM_ROTA
            detector.close()
            return
        }

        Log.i(TAG, "escuta de ativação de pé")
        _estado.value = EstadoDaEscuta.OUVINDO
        var calado = false
        // **Batimento da escuta**, e ele existe pela mesma razão que o item que este
        // arquivo fecha: "de pé" não é "consumindo". Um `collect` que nunca recebe
        // quadro loga a subida e depois silêncio — indistinguível de estar
        // funcionando. Uma linha a cada 30 s custa nada e responde, no campo, a
        // pergunta que este projeto passou uma fase inteira sem conseguir responder.
        var quadros = 0L
        var calados = 0L
        var maiorEscore = 0f
        var proximoRelato = agoraMs() + RELATO_MS
        try {
            fluxo.collect { quadro ->
                if (!escopo.isActive) return@collect
                val agora = agoraMs()
                if (agora >= proximoRelato) {
                    Log.i(
                        TAG,
                        "escuta viva: %d quadros (%.1f s), %d calados, maior escore %.3f"
                            .format(quadros, quadros * 0.02, calados, maiorEscore),
                    )
                    proximoRelato = agora + RELATO_MS
                }
                val deveCalar = agora < mudoAte || suprimido(agora) || ocupadoNoRadio()

                if (deveCalar) {
                    calados++
                    // Reinicia UMA vez, na borda. Reiniciar a cada quadro calado
                    // gastaria trabalho à toa e daria no mesmo — o que importa é
                    // que o anel não emende os dois lados da mudez.
                    if (!calado) {
                        detector.reiniciar()
                        calado = true
                    }
                    return@collect
                }
                if (calado) {
                    detector.reiniciar()
                    calado = false
                }

                quadros++
                val aceitou = detector.aceitar(quadro)
                if (detector.ultimoEscore > maiorEscore) maiorEscore = detector.ultimoEscore
                if (aceitou) {
                    val escore = detector.ultimoEscore
                    Log.i(TAG, "palavra de ativação: escore %.3f".format(escore))
                    // Reinicia ANTES de chamar: o consumidor vai falar, e o anel
                    // não pode carregar o rastro da própria palavra para dentro da
                    // próxima janela.
                    detector.reiniciar()
                    aoDetectar(escore)
                }
            }
        } finally {
            if (_estado.value == EstadoDaEscuta.OUVINDO) _estado.value = EstadoDaEscuta.EM_PAUSA
            detector.close()
            fecharMicrofone()
            Log.i(TAG, "escuta de ativação encerrada")
        }
    }

    private companion object {
        const val TAG = "ClaryonField"

        /** 30 s. Baixo o bastante para não virar ruído, alto o bastante para provar vida. */
        const val RELATO_MS = 30_000L
    }
}
