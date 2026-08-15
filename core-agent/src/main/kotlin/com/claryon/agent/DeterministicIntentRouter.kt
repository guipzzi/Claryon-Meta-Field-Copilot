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

            // Comandos explícitos ANTES do léxico de ocorrências. "Modo
            // abordagem" contém "abordagem", que é tipo de ocorrência — sem esta
            // ordem, trocar de modo dispararia um alerta para a guarnição.
            matches(texto, DETALHAR) -> Intent.Detalhar
            matches(texto, MODO_STANDBY) -> Intent.TrocarModo(ModoOperacao.STANDBY)
            matches(texto, MODO_OCORRENCIA) -> Intent.TrocarModo(ModoOperacao.OCORRENCIA)
            matches(texto, MODO_ATIVO) -> Intent.TrocarModo(ModoOperacao.ATIVO)

            // C2: "onde está Alfa Dois?" — o indicativo sai da própria fala.
            matches(texto, CONSULTAR_POSICAO) ->
                extrairIndicativo(texto)?.let { Intent.ConsultarPosicao(it) }
                    ?: Intent.NaoReconhecida(transcricao)

            // NARRAR antes do léxico: "narrar ocorrência: abordagem na rua X" é
            // ditado para o boletim, não alerta. O verbo explícito do agente
            // vence a classificação automática — ele disse o que queria.
            matches(texto, NARRAR) ->
                Intent.NarrarOcorrencia(texto = transcricao.trim())

            // C3 antes de PEDIR_APOIO: "tiroteio na Rui Barbosa, manda apoio" é
            // um alerta de ocorrência, não um pedido genérico. A diferença é
            // operacional — o alerta carrega tipo, prioridade e local, e o
            // fan-out por raio depende deles. Só entra aqui se o léxico
            // determinístico reconhecer o tipo; senão, segue o fluxo antigo.
            LexicoDeOcorrencias.classificar(transcricao) != null ->
                Intent.AlertarOcorrencia(LexicoDeOcorrencias.classificar(transcricao)!!)

            matches(texto, PEDIR_APOIO) ->
                Intent.PedirApoio(prioridade = prioridadeDe(texto), resumo = null)

            matches(texto, CONSULTAR_PLACA_SOLTO) ->
                Intent.ConsultarPlaca(placa = extrairPlaca(texto))

            else -> Intent.NaoReconhecida(transcricao)
        }
    }

    /**
     * Prioridade do pedido de apoio, com a **mesma régua** do léxico de
     * ocorrências.
     *
     * Antes havia duas escalas: "policial baleado" era emergência pelo léxico e
     * prioridade normal pelo pedido de apoio. A mesma frase, dois despachos
     * diferentes, conforme o caminho que o roteador tomasse — o pior tipo de
     * inconsistência, porque é invisível até acontecer em campo.
     */
    private fun prioridadeDe(texto: String): Prioridade =
        LexicoDeOcorrencias.escalarPrioridade(Prioridade.NORMAL, texto)

    /** Extrai placa Mercosul (ABC1D23) ou padrão antigo (ABC1234), se houver. */
    private fun extrairPlaca(texto: String): String? = PlacaValidator.extrair(texto)

    /**
     * Indicativo militar após o gatilho: "onde está **Alfa Dois**".
     *
     * Até três palavras, porque indicativos reais chegam a isso ("Alfa Dois
     * Zero"). Pontuação já saiu na normalização.
     */
    private fun extrairIndicativo(texto: String): String? {
        for (g in CONSULTAR_POSICAO) {
            val i = texto.indexOf(g)
            if (i < 0) continue
            val palavras = texto.substring(i + g.length).trim()
                .split(" ").filter { it.isNotBlank() }.take(3)
            if (palavras.isNotEmpty()) {
                return palavras.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
            }
        }
        return null
    }

    private fun matches(texto: String, padroes: List<String>): Boolean =
        padroes.any { texto.contains(it) }

    private companion object {
        // Verbos-chave já normalizados (sem acento, minúsculo).
        // Só gatilhos de pânico explícito. "tiros" e "homem caído" saíram daqui
        // de propósito: o léxico os classifica com tipo, prioridade E logradouro,
        // que é estritamente mais informativo para quem recebe o alerta — mesma
        // urgência, mais contexto.
        val EMERGENCIA = listOf("emergencia", "codigo vermelho", "socorro")
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

        val CONSULTAR_POSICAO = listOf("onde esta", "onde ta", "posicao de", "localizar", "cade a", "cade o")

        val PRIORIDADE_MAXIMA = listOf("armado", "arma", "refem", "perigo de vida")
        val PRIORIDADE_ALTA = listOf("urgente", "rapido", "agora")
    }
}

/** Normaliza para minúsculas sem acento (para casar "gravação" com "gravacao"). */
private fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
