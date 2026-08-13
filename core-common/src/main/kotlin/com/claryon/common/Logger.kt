package com.claryon.common

/**
 * Abstração de log independente de plataforma.
 *
 * Mantida em Kotlin puro (sem `android.util.Log`) para que a lógica de domínio
 * — roteador de intenções, protocolo de laconicidade, cadeia de custódia —
 * seja testável em JUnit local. A implementação Android (Logcat, tag
 * "ClaryonField") é injetada pelo módulo `app` na orquestração.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, cause: Throwable? = null)
    fun e(tag: String, message: String, cause: Throwable? = null)

    /** Logger nulo para testes e para o caminho em que log não deve ter custo. */
    object NoOp : Logger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, cause: Throwable?) {}
        override fun e(tag: String, message: String, cause: Throwable?) {}
    }
}
