package com.claryon.field.radio

import com.claryon.field.ui.telas.FalaNoGrupo

/**
 * **Onde o texto transcrito pousa no fio da conversa.**
 *
 * Função pura, e separada do `ViewModel` por um motivo: era a única decisão da
 * transcrição na origem que eu tinha **justificado sem testar**. A verificação ponta
 * a ponta de 20/08 provou o acumulador, o quarto evento e o texto igual dos dois
 * lados — e passou ao largo desta, porque o caminho medido não passa pela tela.
 *
 * Os dois casos casam por critérios diferentes, e a diferença é deliberada.
 */
internal fun comTexto(
    falas: List<FalaNoGrupo>,
    transmissaoId: String,
    texto: String,
    propria: Boolean,
): List<FalaNoGrupo> {
    val alvo = if (propria) {
        // A própria fala não tem `transmissaoId` no balão: ele nasce em `aoSoltar()`,
        // no instante da soltura, e o id só existe dentro da `SessaoPtt`. O candidato
        // é o balão próprio mais recente **ainda sem texto** — o que acabou de ser
        // criado. Fala própria é uma por vez, então não há ambiguidade a resolver.
        falas.indexOfLast { it.propria && it.texto.isBlank() }
    } else {
        // Recebida casa pela CHAVE, sempre. Entre a fala do colega e o texto dela
        // pode ter começado outra transmissão; escrever "no último balão" poria a
        // frase de um agente embaixo do nome de outro — num rádio, o erro que não
        // se perdoa.
        falas.indexOfFirst { it.id == transmissaoId }
    }
    // Texto sem balão é descartado: criar um do zero produziria uma fala sem hora
    // nem indicativo, e a interface estaria inventando conteúdo.
    if (alvo < 0) return falas
    return falas.toMutableList().also { it[alvo] = it[alvo].copy(texto = texto) }
}
