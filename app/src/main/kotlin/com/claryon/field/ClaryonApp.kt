package com.claryon.field

import android.app.Application
import android.util.Log
import com.claryon.common.Result
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
        MapLibre.getInstance(this)

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
