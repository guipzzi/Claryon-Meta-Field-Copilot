package com.claryon.agent

/**
 * Respostas faladas para cada intenção — **protocolo de laconicidade**: no
 * máximo 7 palavras, sem cortesia. Resultado sensível (ex.: consulta de placa)
 * **não** é falado aqui; sai como earcon codificado (o alto-falante open-ear
 * vaza som para quem está ao lado). Estas frases são só a confirmação da ação.
 */
object OperationalResponses {

    fun para(intent: Intent): String = when (intent) {
        is Intent.PedirApoio -> "Apoio solicitado, guarnição avisada."
        is Intent.IniciarGravacao -> "Gravação iniciada."
        Intent.EncerrarGravacao -> "Gravação encerrada."
        is Intent.ConsultarPlaca ->
            if (intent.placa == null) "Consultando placa pela câmera." else "Consultando placa."
        is Intent.NarrarOcorrencia -> "Ocorrência registrada."
        Intent.Emergencia -> "Emergência acionada."
        Intent.Detalhar -> "Repetindo resultado."
        is Intent.TrocarModo -> when (intent.modo) {
            ModoOperacao.STANDBY -> "Modo standby."
            ModoOperacao.ATIVO -> "Modo ativo."
            ModoOperacao.OCORRENCIA -> "Modo ocorrência."
        }
        is Intent.NaoReconhecida -> "Não entendi, repita."
    }
}
