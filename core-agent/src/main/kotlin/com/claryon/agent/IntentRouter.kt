package com.claryon.agent

/**
 * Roteador determinístico de intenções.
 *
 * Contrato fixado no M0; a implementação (correspondência por padrão +
 * verbos-chave + validação de esquema) chega no M5, coberta por teste unitário
 * de 20 frases operacionais reais.
 *
 * Regra de projeto: **nada de LLM no caminho crítico**. Latência imprevisível,
 * dependência de rede e comportamento não auditável são inaceitáveis em decisão
 * operacional de segurança pública. Toda transcrição não mapeável retorna
 * [Intent.NaoReconhecida] — o sistema informa que não entendeu, não inventa.
 */
interface IntentRouter {
    fun route(transcricao: String): Intent
}
