package com.claryon.agent

/** Prioridade de um pedido de apoio. Nível 1 (emergência) interrompe tudo. */
enum class Prioridade { EMERGENCIA, ALTA, NORMAL }

/** Modos de operação — trocáveis por voz ou toque. Governam energia e câmera. */
enum class ModoOperacao { STANDBY, ATIVO, OCORRENCIA }

/**
 * Modelo fechado de intenções operacionais.
 *
 * Toda saída do reconhecimento de voz cai em UMA destas variantes — não há
 * "talvez". Intenção não reconhecida vira [NaoReconhecida] e responde com
 * earcon de falha + pedido curto de repetição; o sistema NUNCA age por
 * adivinhação (contexto de segurança pública).
 */
sealed interface Intent {

    /** Pedir apoio à guarnição. `resumo` é opcional (≤120 caracteres no template). */
    data class PedirApoio(val prioridade: Prioridade, val resumo: String?) : Intent

    /** Iniciar gravação de evidência. `motivo` opcional. */
    data class IniciarGravacao(val motivo: String?) : Intent

    /** Encerrar a gravação em curso. */
    data object EncerrarGravacao : Intent

    /** Consultar placa. `placa == null` ⇒ ler pela câmera (OCR). */
    data class ConsultarPlaca(val placa: String?) : Intent

    /** Narrar/ditar a ocorrência para o boletim preliminar. */
    data class NarrarOcorrencia(val texto: String) : Intent

    /** Emergência: prioridade máxima, interrompe qualquer coisa. */
    data object Emergencia : Intent

    /** Repetir o último resultado por voz (usado quando o agente já se afastou). */
    data object Detalhar : Intent

    /** Trocar o modo de operação. */
    data class TrocarModo(val modo: ModoOperacao) : Intent

    /**
     * Consultar a posição de um par pelo indicativo (C2).
     * A resposta sai como distância, rumo e estado — nunca coordenadas.
     */
    data class ConsultarPosicao(val indicativo: String) : Intent

    /**
     * Alertar uma ocorrência (C3). A [Ocorrencia] já vem classificada pelo
     * léxico determinístico — o executor não reclassifica, só despacha.
     */
    data class AlertarOcorrencia(val ocorrencia: Ocorrencia) : Intent

    /** Nada reconhecido — carrega a transcrição bruta para diagnóstico. */
    data class NaoReconhecida(val transcricao: String) : Intent
}
