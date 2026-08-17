package com.claryon.field

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.claryon.common.Result
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.voice.EscutaDoAgente
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.claryon.glasses.GlassesRuntime
import org.maplibre.android.MapLibre

/**
 * Classe Application do Claryon Field.
 *
 * Inicializa o DAT **uma vez por processo** via [GlassesRuntime.initialize] —
 * que encapsula `Wearables.initialize` dentro de `core-glasses`, para o `app`
 * nunca importar o SDK. É a pré-condição obrigatória do DAT (APIs antes disso
 * lançam `WearablesException`).
 *
 * Fica na `Application` (não em Activity) porque o `ForegroundService` e o
 * `WorkManager` recriam/retomam o processo — o SDK precisa estar pronto em todos
 * os pontos de entrada.
 *
 * O MapLibre entra pelo mesmo motivo e com a mesma pré-condição: `MapView` lança
 * `MapLibreConfigurationException` no construtor se `getInstance` não tiver
 * rodado antes. A primeira versão chamava isso de dentro de um `DisposableEffect`
 * no Composable — que roda **depois** da composição, enquanto o `remember` que
 * constrói a `MapView` roda **durante**. O mapa quebrava na primeira abertura.
 * É a mesma classe de erro que a sequência de boot deste projeto existe para
 * evitar, e a correção é a mesma: inicializar no ponto de entrada do processo.
 */
class ClaryonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instalarStrictModeDeDesenvolvimento()
        // Lê o cofre cifrado FORA da Main. Sem isto, a primeira composição
        // chamava `autenticado()` na thread principal e pagava 468 ms de
        // Keystore no portão de login. Ver `SessaoDoAgente.aquecer`.
        SessaoDoAgente.aquecer(this)
        MapLibre.getInstance(this)

        val result = GlassesRuntime.initialize(this)
        if (result is Result.Failure) {
            // Falha nunca é silêncio: registra para diagnóstico. Em Developer
            // Mode com MockDeviceKit, o enable() reinicializa se necessário.
            Log.e(TAG, "Falha ao inicializar o DAT: ${result.error}")
        }
    }

    /**
     * Parte do instrumento do critério de aceite da Fase 1: "StrictMode não
     * acusa violação na Main durante 30 s de transmissão contínua"
     * (`ROADMAP.md`).
     *
     * **O que isto pega de verdade, hoje:** disco e rede na Main —
     * `detectDiskReads`/`detectDiskWrites`/`detectNetwork` são automáticos, sem
     * precisar instrumentar chamador nenhum. A auditoria de threading já achou
     * candidatos reais (leitura do cofre cifrado da sessão, `ConsultaDePosicao`)
     * que estes três detectores acusariam sozinhos.
     *
     * **O que isto NÃO pega:** o bloqueio do `MediaCodec` (`dequeueInputBuffer`/
     * `dequeueOutputBuffer`) não é E/S de disco nem de rede aos olhos do
     * StrictMode — é chamada bloqueante comum, e só `detectCustomSlowCalls` +
     * `StrictMode.noteSlowCall` no próprio ponto de bloqueio pegariam isso, o
     * que este ciclo não instrumentou. A garantia de que o Opus saiu da Main é
     * `MediaCodecOpus.dispatcher` — verificável por construção e por teste com
     * dispatcher injetado, não por este detector.
     *
     * `penaltyLog` e não `penaltyDeath`: em produto real (não CI), matar o
     * processo por uma violação transformaria um sinal de diagnóstico em
     * incidente de campo.
     *
     * Só em `BuildConfig.DEBUG`: rodar isto em release custaria overhead de
     * detecção sem ninguém para ler o log.
     */
    private fun instalarStrictModeDeDesenvolvimento() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectCustomSlowCalls()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * **A válvula que faltava.**
     *
     * O app não tinha `onTrimMemory` nenhum (`grep` devolvia zero), e a partir
     * daqui ele segura o contexto do Whisper quente — ~78 MB nativos — somados ao
     * Piper, ao runtime do sherpa-onnx e ao MapLibre, num processo que roda sob
     * *foreground service* com microfone.
     *
     * Sem devolver isso sob pressão, quem decide o que morre é o *Low Memory
     * Killer*, e o que ele mata é o serviço de rádio: a capacidade que não pode
     * cair. Recarregar o modelo custa um ciclo de voz lento; perder o rádio custa
     * a ocorrência.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!EscutaDoAgente.deveLiberar(level)) return
        Log.i(TAG, "onTrimMemory($level): devolvendo o contexto do whisper")
        escopoDeManutencao.launch { EscutaDoAgente.liberar() }
    }

    /**
     * Escopo de manutenção do processo — trabalho que não pertence a nenhuma
     * tela. `onTrimMemory` não é suspensa e a liberação do contexto nativo pega
     * um mutex; fazê-la na Main travaria justamente quando o sistema já está sem
     * memória.
     */
    private val escopoDeManutencao = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ClaryonField"
    }
}
