package com.claryon.field.radio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.audio.GlassesAudioRoute
import com.claryon.common.Earcon
import com.claryon.common.Result
import com.claryon.field.BuildConfig
import com.claryon.field.ui.componentes.EstadoDoPtt
import com.claryon.field.ui.telas.FalaNoGrupo
import com.claryon.field.ui.telas.ParPresente
import com.claryon.net.ClienteDePisoLocal
import com.claryon.net.CodecDeVoz
import com.claryon.net.ConfigRealtime
import com.claryon.net.MediaCodecOpus
import com.claryon.net.TransporteRealtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * **Liga o rádio tático à interface.**
 *
 * Este arquivo existe para consertar um defeito específico e embaraçoso: o
 * `RadioTatico` — PTT, pré-roll, supressor de eco, gatilho, controle de piso,
 * Opus, transporte — estava **construído, testado e nunca instanciado**. A
 * capacidade inteira estava desligada da tomada, e a suíte verde não dizia nada a
 * respeito, porque testes cobrem o que alguém imaginou testar.
 *
 * Três invariantes que este ViewModel sustenta:
 *
 *  1. **A rota de áudio é pré-condição de tipo.** `entrarEmModoAtivo` exige um
 *     `GlassesAudioRoute`, e a única forma de obter um é rotear de fato. Não há
 *     caminho em que o rádio suba capturando pelo microfone do celular — que é
 *     omnidirecional e, com PTT, difundiria a fala do interlocutor para a
 *     guarnição inteira.
 *
 *  2. **O botão nunca espera a rede.** `aoPressionar` chama o rádio e volta; a
 *     concessão de canal e o estado do socket correm em paralelo. Rede lenta
 *     atrasa a entrega, nunca perde fala.
 *
 *  3. **O estado da barra vem do rádio, não do botão.** Se o piso for negado, a
 *     barra mostra ocupado mesmo com o dedo pressionado — a interface reporta o
 *     que aconteceu, não o que foi pedido. É a mesma regra de `utteranceFor`,
 *     aplicada à saída visual.
 */
class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val audio = GlassesAudioManagerImpl(app, allowFallbackToDefault = BuildConfig.DEBUG)

    private val _estado = MutableStateFlow<EstadoDoPtt>(
        EstadoDoPtt.Indisponivel("Rádio fechado."),
    )
    val estado: StateFlow<EstadoDoPtt> = _estado.asStateFlow()

    private val _falas = MutableStateFlow<List<FalaNoGrupo>>(emptyList())
    val falas: StateFlow<List<FalaNoGrupo>> = _falas.asStateFlow()

    private val _pares = MutableStateFlow<List<ParPresente>>(emptyList())
    val pares: StateFlow<List<ParPresente>> = _pares.asStateFlow()

    /** `true` enquanto transmitimos. É o que acende a moldura da tela inteira. */
    val noAr: StateFlow<Boolean> get() = _noAr
    private val _noAr = MutableStateFlow(false)

    private var radio: RadioTatico? = null
    private var rota: GlassesAudioRoute? = null
    private var cronometro: Job? = null
    private var inicioDaFalaMs = 0L

    private val redeConfigurada =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    // ── Ciclo de vida do rádio ────────────────────────────────────────────────

    /**
     * Abre o rádio. Chamado quando a tela da guarnição fica visível.
     *
     * A ordem é a do contrato de boot e não pode ser invertida: **rota de áudio
     * antes da sessão**. HFP totalmente configurado antes de qualquer streaming;
     * o inverso produz captura de voz intermitente.
     */
    fun abrir(canal: String, agenteId: String, indicativo: String) {
        if (radio != null) return
        if (!redeConfigurada) {
            _estado.value = EstadoDoPtt.Indisponivel("Servidor não configurado.")
            return
        }

        viewModelScope.launch {
            val r = when (val res = audio.iniciar()) {
                is Result.Success -> res.value
                is Result.Failure -> {
                    // Falha nunca é silêncio, e aqui o canal de aviso é a própria
                    // barra: sem rota não há rádio, e o agente precisa saber antes
                    // de apertar o botão contando com ele.
                    _estado.value = EstadoDoPtt.Indisponivel("Sem rota de áudio. Conecte os óculos.")
                    return@launch
                }
            }
            rota = r

            val transporte = TransporteRealtime(
                config = ConfigRealtime(
                    projetoUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                    apiKey = BuildConfig.SUPABASE_ANON_KEY,
                ),
                escopo = viewModelScope,
            )

            val novo = RadioTatico(
                escopo = viewModelScope,
                talkGroupId = canal,
                agenteId = agenteId,
                indicativo = indicativo,
                transporte = transporte,
                codec = codec(),
                piso = ClienteDePisoLocal(),
                pcmDoMicrofone = { rotaValida -> audio.microfonePcm(rotaValida) },
                reproduzir = { pcm, taxa -> audio.reproduzir(pcm, taxa) },
                emitir = { /* o ciclo de voz já toca; aqui só a barra reage */ },
                duracaoDoEarconMs = { e -> duracaoDoEarcon(e) },
            )
            novo.entrarEmModoAtivo(r)
            radio = novo
            _estado.value = EstadoDoPtt.Pronto(canal)
        }
    }

    /** Fecha o rádio e devolve a rota. Chamado ao sair da tela ou encerrar o turno. */
    fun fechar() {
        cronometro?.cancel()
        cronometro = null
        radio?.sairDeModoAtivo()
        radio = null
        rota = null
        _noAr.value = false
        _estado.value = EstadoDoPtt.Indisponivel("Rádio fechado.")
        audio.liberar()
    }

    // ── Push-to-talk ──────────────────────────────────────────────────────────

    /**
     * Dedo desceu.
     *
     * Não suspende, não espera rede, não valida nada que dependa de resposta
     * remota. A captura começa no instante do toque — a concessão de canal corre
     * atrás, e o pré-roll cobre o intervalo.
     */
    fun aoPressionar() {
        val r = rota ?: return
        val radioAtivo = radio ?: return
        inicioDaFalaMs = System.currentTimeMillis()

        radioAtivo.aoPressionar(r)
        _noAr.value = true
        _estado.value = EstadoDoPtt.NoAr(0L, 0f)

        cronometro?.cancel()
        cronometro = viewModelScope.launch {
            while (true) {
                val atual = _estado.value
                if (atual !is EstadoDoPtt.NoAr) break
                _estado.value = EstadoDoPtt.NoAr(
                    decorridoMs = System.currentTimeMillis() - inicioDaFalaMs,
                    amplitude = radioAtivo.amplitudeAtual,
                )
                // 60 ms: acompanha a voz sem recompor a barra a cada quadro de
                // áudio. A forma de onda é informação, não animação — mais rápido
                // que isso só gastaria bateria.
                delay(60)
            }
        }
    }

    /** Dedo subiu — por qualquer motivo, inclusive escorregar ou a tela apagar. */
    fun aoSoltar() {
        cronometro?.cancel()
        cronometro = null
        _noAr.value = false
        radio?.aoSoltar()
        _estado.value = _estado.value.let { atual ->
            if (atual is EstadoDoPtt.Indisponivel) atual else EstadoDoPtt.Pronto(canalAtual())
        }
    }

    private fun canalAtual(): String =
        (_estado.value as? EstadoDoPtt.Pronto)?.canal ?: TALK_GROUP_PADRAO

    // ── Peças auxiliares ──────────────────────────────────────────────────────

    private fun codec(): CodecDeVoz = MediaCodecOpus()

    private fun duracaoDoEarcon(earcon: Earcon): Long = when (earcon) {
        Earcon.GRAVANDO -> 2_000L
        else -> 320L
    }

    override fun onCleared() {
        fechar()
        super.onCleared()
    }

    private companion object {
        const val TALK_GROUP_PADRAO = "demo"
        val HORA: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
    }
}
