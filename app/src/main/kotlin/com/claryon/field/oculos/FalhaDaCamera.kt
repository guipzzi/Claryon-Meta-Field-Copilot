package com.claryon.field.oculos

import com.claryon.agent.FalhaOperacional
import com.claryon.glasses.ErroDeStream

/**
 * **A tradução do erro do SDK para o que o agente OUVE.**
 *
 * ## O defeito que isto conserta
 *
 * `ErroDeStream` distingue oito causas, cada uma com frase própria já dentro do teto
 * de sete palavras. Até 21/08 as oito chegavam ao agente como *"Consulta
 * indisponível."*: a causa tipada morria num `Log.w` um passo antes do alto-falante,
 * porque `FalhaOperacional` — enum fechado em `core-agent` — não tinha valor nenhum
 * para câmera.
 *
 * Isso colidia com a regra dura **falha nunca é silêncio**. Uma falha que soa igual a
 * outra é a versão sonora do silêncio: informa que deu errado e esconde **o quê**,
 * que é a única parte acionável. E o `ErroDeStream` foi escrito exatamente com essa
 * intenção — o KDoc dele diz que `HINGE_CLOSED` vira "óculos dobrados" *porque foi
 * isso que aconteceu no mundo*.
 *
 * ## O critério é a RECUPERAÇÃO, não a causa
 *
 * Oito causas não precisam de oito falas. Precisam de tantas quantas forem as ações
 * distintas que o agente pode tomar:
 *
 * ```
 * hastes fechadas   → abre as hastes         dois segundos, resolve sozinho
 * permissão negada  → mexe no Meta AI        para e olha o celular
 * óculos quentes    → espera esfriar         INSISTIR PIORA
 * energia no fim    → põe no estojo          o turno tem problema maior
 * resto             → tenta de novo          é o que há
 * ```
 *
 * Duas causas com o mesmo gesto **podem** compartilhar fala — é o caso de
 * `BATTERY_LOW` e `PEAK_POWER_LIMIT`, cujas frases do SDK diferem mas cujo gesto é o
 * mesmo. Duas com gestos diferentes **não podem**, e era precisamente isso que
 * acontecia.
 *
 * ## Por que este arquivo vive em `app`
 *
 * `core-agent` não conhece `core-glasses`, e não deve: a fronteira é o que impede o
 * vocabulário de ação de crescer com detalhe de hardware. `app` conhece os dois, e é
 * o único lugar onde a tradução pode existir sem furar isso.
 *
 * ## A totalidade é o que impede o próximo silêncio
 *
 * O `when` abaixo é sobre um `enum` e **não tem `else`**: um valor novo no SDK quebra
 * a compilação, em vez de cair num balde genérico sem ninguém notar. É a mesma razão
 * de `utteranceFor` não ter `else`. Ver `FalhaDaCameraTest`, que também prova que
 * recuperações distintas produzem falas distintas.
 */
internal object FalhaDaCamera {

    fun de(erro: ErroDeStream): FalhaOperacional = when (erro) {
        ErroDeStream.HINGE_CLOSED -> FalhaOperacional.CAMERA_OCULOS_DOBRADOS
        ErroDeStream.PERMISSIONS_DENIED -> FalhaOperacional.CAMERA_SEM_PERMISSAO
        ErroDeStream.THERMAL_HOT -> FalhaOperacional.CAMERA_QUENTE

        // Gesto idêntico — pôr no estojo —, então fala idêntica. Colapso deliberado,
        // registrado em `specs/falha-de-camera-falada.spec.md`.
        ErroDeStream.BATTERY_LOW,
        ErroDeStream.PEAK_POWER_LIMIT -> FalhaOperacional.CAMERA_SEM_BATERIA

        // Sem recuperação específica: tentar de novo é o que há. Note que
        // `CRITICAL_STREAM_ERROR` cai aqui e não em algo mais alarmante — a diferença
        // entre "falhou" e "falhou feio" não muda o que o agente faz em campo, e fala
        // que assusta sem dar ação é ruído com custo.
        ErroDeStream.STREAM_ERROR,
        ErroDeStream.CRITICAL_STREAM_ERROR,
        ErroDeStream.TIMEOUT,
        ErroDeStream.DESCONHECIDO -> FalhaOperacional.CAMERA_INDISPONIVEL
    }

    /**
     * A partir do código textual (`glasses.stream_error.HINGE_CLOSED`), para o caminho
     * em que só a string atravessou.
     *
     * Devolve [FalhaOperacional.CAMERA_INDISPONIVEL] quando o código não é de stream
     * — é o caso de `glasses.no_session` e `glasses.camera_threw`, que nascem deste
     * lado e não têm recuperação própria além de tentar de novo.
     */
    fun deCodigo(codigo: String): FalhaOperacional {
        val nome = codigo.substringAfterLast('.')
        val erro = ErroDeStream.entries.firstOrNull { it.name == nome }
        return erro?.let { de(it) } ?: FalhaOperacional.CAMERA_INDISPONIVEL
    }
}
