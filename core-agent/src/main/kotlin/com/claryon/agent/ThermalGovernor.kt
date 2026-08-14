package com.claryon.agent

/**
 * Teto de taxa em função do *thermal headroom* do aparelho.
 *
 * Armadilha que este objeto existe para evitar (docs/PADROES_DE_ENGENHARIA.md): **`NaN` não é 0**.
 * `getThermalHeadroom()` devolve `NaN` quando o sistema não tem informação
 * (chamadas muito próximas, aparelho sem sensor). Tratar `NaN` como 0 faria o
 * app se achar frio e acelerar exatamente quando não sabe nada — por isso, sem
 * informação, mantemos o teto de taxa vigente e **não** liberamos rajada.
 *
 * Semântica do valor: `0.0` = frio; `1.0` = no limite de throttling; `>1.0` = já
 * passou do limite.
 */
object ThermalGovernor {

    /** Teto de FPS da câmera dado o headroom (ou o [padrao] se não houver informação). */
    fun fpsPermitido(headroom: Float, padrao: Int = PowerPolicy.FPS_PADRAO): Int = when {
        headroom.isNaN() -> padrao      // sem informação → mantém o teto, não acelera
        headroom >= 1.0f -> 0           // no limite ou além: câmera desce
        headroom >= 0.85f -> 2          // quase lá: taxa mínima válida do DAT
        else -> padrao
    }

    /**
     * Se é seguro iniciar uma **rajada** (stream de câmera, transcrição longa).
     * Sem informação (`NaN`) a resposta é `false`: rajada é opcional, e na dúvida
     * o custo de não fazer é menor que o de superaquecer no meio de uma ocorrência.
     */
    fun podeIniciarRajada(headroom: Float): Boolean =
        !headroom.isNaN() && headroom < 0.85f
}
