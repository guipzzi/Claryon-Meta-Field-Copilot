package com.claryon.agent

import java.text.Normalizer

/**
 * Roteador de intenções **determinístico** — o cérebro do copiloto.
 *
 * Correspondência por padrão + verbos-chave sobre a transcrição normalizada
 * (minúsculas, sem acento). **Sem LLM**: latência previsível, sem rede, auditável
 * — requisitos inegociáveis em decisão operacional de segurança pública. O que
 * não casa vira [Intent.NaoReconhecida] (earcon de falha + pedido de repetição);
 * o sistema nunca age por adivinhação.
 *
 * A ordem de avaliação vai do mais específico (emergência) ao mais genérico.
 */
class DeterministicIntentRouter : IntentRouter {

    override fun route(transcricao: String): Intent {
        val texto = normalizar(transcricao)
        if (texto.isBlank()) return Intent.NaoReconhecida(transcricao)

        return when {
            matches(texto, EMERGENCIA) -> Intent.Emergencia

            matches(texto, ENCERRAR_GRAVACAO) -> Intent.EncerrarGravacao
            matches(texto, INICIAR_GRAVACAO) -> Intent.IniciarGravacao(motivo = null)

            // Verbos explícitos primeiro. Só depois o termo solto "placa" —
            // senão "narrar ocorrência: veículo de placa ABC1234" viraria
            // consulta e a narração do agente seria perdida.
            matches(texto, CONSULTAR_PLACA_EXPLICITO) ->
                Intent.ConsultarPlaca(placa = extrairPlaca(texto))

            matches(texto, PEDIR_APOIO) ->
                Intent.PedirApoio(prioridade = prioridadeDe(texto), resumo = null)

            matches(texto, NARRAR) ->
                Intent.NarrarOcorrencia(texto = transcricao.trim())

            matches(texto, CONSULTAR_PLACA_SOLTO) ->
                Intent.ConsultarPlaca(placa = extrairPlaca(texto))

            matches(texto, DETALHAR) -> Intent.Detalhar

            matches(texto, MODO_STANDBY) -> Intent.TrocarModo(ModoOperacao.STANDBY)
            matches(texto, MODO_OCORRENCIA) -> Intent.TrocarModo(ModoOperacao.OCORRENCIA)
            matches(texto, MODO_ATIVO) -> Intent.TrocarModo(ModoOperacao.ATIVO)

            else -> Intent.NaoReconhecida(transcricao)
        }
    }

    private fun prioridadeDe(texto: String): Prioridade = when {
        matches(texto, PRIORIDADE_MAXIMA) -> Prioridade.EMERGENCIA
        matches(texto, PRIORIDADE_ALTA) -> Prioridade.ALTA
        else -> Prioridade.NORMAL
    }

    /** Extrai placa Mercosul (ABC1D23) ou padrão antigo (ABC1234), se houver. */
    private fun extrairPlaca(texto: String): String? = PlacaValidator.extrair(texto)

    private fun matches(texto: String, padroes: List<String>): Boolean =
        padroes.any { texto.contains(it) }

    private companion object {
        // Verbos-chave já normalizados (sem acento, minúsculo).
        val EMERGENCIA = listOf("emergencia", "codigo vermelho", "socorro", "homem caido", "tiros")
        val PEDIR_APOIO = listOf("apoio", "reforco", "reforcar", "solicitar apoio", "preciso de apoio")
        val INICIAR_GRAVACAO = listOf("gravar", "iniciar gravacao", "comecar gravacao", "registrar video")
        val ENCERRAR_GRAVACAO = listOf("encerrar gravacao", "parar gravacao", "parar de gravar", "finalizar gravacao")
        // Verbo + objeto: intenção inequívoca de consulta.
        val CONSULTAR_PLACA_EXPLICITO =
            listOf("consultar placa", "verificar placa", "checar placa", "rodar placa")
        // Termo solto: só vale se nada mais específico casou antes.
        val CONSULTAR_PLACA_SOLTO = listOf("placa")
        val NARRAR = listOf("narrar", "ditar", "registrar ocorrencia", "anotar ocorrencia", "boletim")
        val DETALHAR = listOf("detalhar", "repetir", "repita", "de novo")
        val MODO_STANDBY = listOf("modo standby", "modo espera", "modo descanso")
        val MODO_OCORRENCIA = listOf("modo ocorrencia", "modo abordagem")
        val MODO_ATIVO = listOf("modo ativo", "modo patrulha")

        val PRIORIDADE_MAXIMA = listOf("armado", "arma", "refem", "perigo de vida")
        val PRIORIDADE_ALTA = listOf("urgente", "rapido", "agora")
    }
}

/** Normaliza para minúsculas sem acento (para casar "gravação" com "gravacao"). */
private fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
