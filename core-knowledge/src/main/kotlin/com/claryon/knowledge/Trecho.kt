package com.claryon.knowledge

/**
 * Um pedaço de norma **já escrito**, do jeito que está publicado.
 *
 * É a unidade inteira da Etapa A: o copiloto não redige nada: ele recupera um
 * [Trecho] e o Piper lê [texto] palavra por palavra, anunciando [citacao] e
 * [norma]. Zero alucinação não é uma meta de qualidade aqui — é consequência de
 * não existir passo que produza texto novo.
 *
 * **Por que os três campos são obrigatórios e o construtor recusa vazio.** Um
 * trecho sem procedência é pior que nenhum trecho: o agente ouve uma regra dita
 * com autoridade e não tem como conferir de onde ela veio, no meio de uma
 * abordagem. Tornando a citação parte da *existência* do tipo, "esqueci de citar"
 * deixa de ser um caminho possível — não há como construir o objeto sem ela.
 *
 * **O construtor lança de propósito.** É violação de programação, não falha
 * operacional: um corpus malformado não deve chegar até aqui. O carregador do
 * corpus (item 3 da Etapa A) valida e descarta a entrada ruim ANTES de construir
 * — o caminho crítico continua sem exceção, como manda o contrato do projeto.
 *
 * @property texto o recorte da norma, byte a byte como publicado. Ninguém
 *   reescreve, resume ou "melhora" — quem lê é o TTS, e o que ele lê é isto.
 * @property citacao onde o trecho está dentro da norma: `"Art. 28"`,
 *   `"Art. 244, § 1º"`. É o que o agente precisa ouvir para conferir depois.
 * @property norma o documento de origem, com número e ano:
 *   `"Lei 11.343/2006"`, `"Decreto-Lei 3.689/1941 — Código de Processo Penal"`.
 *
 * **Armadilha de nome, para quem for escrever o carregador.** O corpus em
 * `corpus/trechos.jsonl` usa quatro campos — `texto`, `artigo`, `documento` e
 * `norma` —, e o `norma` de lá é a **sigla** (`"CTB"`), não o documento. O mapa
 * correto é `artigo → citacao` e `documento → norma`; casar campo por nome igual
 * faria o Piper anunciar `"CTB"` no lugar de `"Lei 9.503/1997"`, que é justamente
 * a informação com que o agente conferiria a fonte depois.
 */
data class Trecho(
    val texto: String,
    val citacao: String,
    val norma: String,
) {
    init {
        require(texto.isNotBlank()) { "Trecho sem texto não é trecho." }
        require(citacao.isNotBlank()) {
            "Trecho sem citação não pode existir: o agente precisa saber de onde veio."
        }
        require(norma.isNotBlank()) {
            "Trecho sem norma não pode existir: o agente precisa saber qual documento é."
        }
    }
}

/**
 * Um [Trecho] com quanto ele se parece com a pergunta, do jeito que um índice
 * vetorial devolve. É a entrada de [PortaDoConhecimento].
 *
 * @property similaridade **cosseno entre vetores normalizados**, portanto em
 *   `-1.0..1.0`. A faixa é exigida no construtor porque o limiar só significa
 *   alguma coisa se todas as implementações medirem na mesma régua: um produto
 *   interno não normalizado passaria `12,7` por "muito parecido" e faria a porta
 *   aceitar qualquer coisa. Métrica diferente converte antes de chegar aqui.
 */
data class Candidato(
    val trecho: Trecho,
    val similaridade: Double,
) {
    init {
        require(similaridade.isFinite() && similaridade in -1.0..1.0) {
            "Similaridade fora da régua do cosseno (-1..1): $similaridade"
        }
    }
}
