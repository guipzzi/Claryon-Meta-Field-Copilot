package com.claryon.field

import android.app.Application
import android.util.Log
import com.claryon.common.Result
import com.claryon.glasses.GlassesRuntime

/**
 * Classe Application do Claryon Field.
 *
 * Inicializa o DAT **uma vez por processo** via [GlassesRuntime.initialize] —
 * que encapsula `Wearables.initialize` dentro de `core-glasses`, para o `app`
 * nunca importar o SDK. É a pré-condição obrigatória do DAT (APIs antes disso
 * dão `NOT_INITIALIZED`).
 *
 * Fica na `Application` (não em Activity) porque o `ForegroundService` e o
 * `WorkManager` recriam/retomam o processo — o SDK precisa estar pronto em todos
 * os pontos de entrada.
 */
class ClaryonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val result = GlassesRuntime.initialize(this)
        if (result is Result.Failure) {
            // Falha nunca é silêncio: registra para diagnóstico. Em Developer
            // Mode com MockDeviceKit, o enable() reinicializa se necessário.
            Log.e(TAG, "Falha ao inicializar o DAT: ${result.error}")
        }
    }

    companion object {
        private const val TAG = "ClaryonField"
    }
}
