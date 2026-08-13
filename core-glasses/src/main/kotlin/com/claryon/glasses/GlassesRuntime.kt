package com.claryon.glasses

import android.content.Context
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import com.meta.wearable.dat.core.Wearables

/**
 * Ponto de entrada do runtime do DAT — mantém `Wearables.initialize` dentro de
 * `core-glasses`, para que o módulo `app` (e qualquer outro) **nunca** importe
 * símbolos do SDK. É o corolário da fachada: um único módulo depende da API em
 * preview.
 */
object GlassesRuntime {

    /**
     * Inicializa o DAT uma vez por processo. Deve ser chamado no `onCreate()` da
     * classe `Application`. Assinatura confirmada no AGENTS.md oficial
     * (`Wearables.initialize(context).onFailure { error, _ -> }`).
     */
    fun initialize(context: Context): Result<Unit> {
        var result: Result<Unit> = Result.success(Unit)
        Wearables.initialize(context)
            .onFailure { error, _ ->
                result = Result.failure(
                    ClaryonError.Glasses("glasses.init_failed", error.description),
                )
            }
        return result
    }
}
