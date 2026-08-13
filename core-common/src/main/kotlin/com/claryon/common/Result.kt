package com.claryon.common

/**
 * Tipo de resultado explícito do projeto.
 *
 * Preferimos este selado a `kotlin.Result` porque:
 *  - o erro é um [ClaryonError] tipado e auditável (código estável para
 *    telemetria e para o mapeamento erro → earcon), não um `Throwable` solto;
 *  - deixa o caminho de falha visível na assinatura de toda operação de risco
 *    (DAT, áudio, STT/TTS, evidência, rede), coerente com a política de que
 *    "falha nunca é silêncio".
 *
 * Convenção de uso: nunca lançar exceção no caminho crítico; retornar
 * [Failure] e deixar o chamador decidir o feedback sonoro.
 */
sealed interface Result<out T> {

    data class Success<out T>(val value: T) : Result<T>

    data class Failure(
        val error: ClaryonError,
        val cause: Throwable? = null,
    ) : Result<Nothing>

    companion object {
        fun <T> success(value: T): Result<T> = Success(value)
        fun failure(error: ClaryonError, cause: Throwable? = null): Result<Nothing> =
            Failure(error, cause)
    }
}

/** `true` se e somente se for [Result.Success]. */
val Result<*>.isSuccess: Boolean get() = this is Result.Success

/** Valor em caso de sucesso, ou `null`. Não lança. */
fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.value

/** Aplica [transform] ao valor de sucesso, propagando a falha inalterada. */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Failure -> this
}
