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

/**
 * **Marca o balão cuja fala a rede cortou no meio.**
 *
 * Irmã de [comTexto], e de propósito: as duas resolvem o mesmo problema — um evento
 * do rádio que precisa achar um balão já existente pela chave — e resolvê-lo em dois
 * lugares diferentes é como os dois divergem no primeiro ajuste.
 *
 * Casa **só** por `transmissaoId`, sem o ramo de fala própria que [comTexto] tem.
 * Não há caso próprio a tratar: `EventoRecepcao.Terminou` só existe para fala
 * **recebida**, e o truncamento da própria transmissão deste aparelho é outro
 * fenômeno inteiro (`Entrega.NAO_SAIU`), com rótulo próprio.
 *
 * ---
 * ### Balão ausente: descarta, e a perda é conhecida
 *
 * Como em [comTexto], índice negativo devolve a lista intacta. Isto **acontece na
 * prática** e não é hipótese: `RadioViewModel` não insere balão de fala recebida —
 * ele nasce no `carregarCanal`, que faz poll do servidor a cada 10 s, e o
 * `Terminou` chega cerca de 2 s depois do corte. Na janela entre o corte e o poll
 * seguinte não há balão para marcar.
 *
 * A alternativa seria criar um balão aqui, e ela é pior: sairia sem hora, sem
 * indicativo e sem texto — a interface inventando um registro de canal a partir de
 * um evento de erro. Quando o poll trouxer a fala, ela aparece sem a marca; o
 * earcon já avisou ao vivo, e o histórico prefere calar a mentir. **O que conserta
 * isto de verdade** é a inserção local da fala recebida no instante do
 * `Chegando` — que é mudança de comportamento do rádio e começa por diff de spec.
 */
internal fun comCorteDaRede(
    falas: List<FalaNoGrupo>,
    transmissaoId: String,
): List<FalaNoGrupo> {
    val alvo = falas.indexOfFirst { it.id == transmissaoId }
    if (alvo < 0) return falas
    // Idempotente: `Terminou` chega uma vez por transmissão, mas marcar duas vezes
    // não pode produzir lista nova — recomposição à toa numa lista que rola custa
    // quadro, e o `key` do `LazyColumn` é o id, não a identidade do objeto.
    if (falas[alvo].cortadaPelaRede) return falas
    return falas.toMutableList().also {
        it[alvo] = it[alvo].copy(cortadaPelaRede = true)
    }
}
