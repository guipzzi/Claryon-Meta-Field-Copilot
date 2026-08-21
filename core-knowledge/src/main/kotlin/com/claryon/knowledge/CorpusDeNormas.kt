package com.claryon.knowledge

import java.io.InputStream

/**
 * **Lê `trechos.jsonl` e devolve [Trecho]s — ou uma lista vazia, nunca uma
 * exceção.**
 *
 * O corpus é dado de arquivo, e arquivo falha de maneiras que o caminho crítico
 * não pode encarar: some do APK, chega truncado, ganha uma linha malformada num
 * commit. Cada uma dessas coisas vira **descarte da linha**, e no pior caso uma
 * lista vazia — que [BaseDeConhecimentoLexical] converte em recusa. Uma exceção
 * subindo daqui, num aparelho sem display, é indistinguível do aplicativo ter
 * morrido.
 *
 * ## A armadilha do nome, e por que ela é escrita aqui em vez de comentada
 *
 * O JSONL tem **sete** campos, e dois deles colidem por nome com os de [Trecho]
 * significando coisas diferentes:
 *
 * | no JSONL | exemplo | vai para |
 * |---|---|---|
 * | `artigo` | `"Art. 306"` | [Trecho.citacao] |
 * | `documento` | `"Lei 9.503/1997"` | [Trecho.norma] |
 * | `norma` | `"CTB"` — **a sigla** | só o índice, como termo de busca |
 * | `citacao` | `"Art. 306 do CTB"` | não usado: repete os dois de cima |
 *
 * Casar `norma` com `norma` compila, passa em qualquer teste que só confira que
 * o campo não está vazio, e faz o Piper anunciar **"CTB"** onde deveria dizer
 * **"Lei 9.503/1997"** — perdendo justamente a informação com que o agente
 * conferiria a fonte depois. `CorpusDeNormasTest` troca os dois de propósito e
 * exige que o teste grite.
 *
 * ## Revogado não entra
 *
 * 73 dos 1817 trechos têm `revogado: true`, e o texto deles é literalmente
 * `"Art. 262. (Revogado pela Lei nº 13.281, de 2016)"`. Ler isso em voz alta
 * numa abordagem não é uma resposta pior — é uma resposta que ocupa o lugar da
 * certa. Eles ficam de fora do índice.
 */
internal object CorpusDeNormas {

    /** Recurso embarcado no jar do módulo — e, por tabela, no APK de quem depender dele. */
    const val RECURSO: String = "/corpus/trechos.jsonl"

    /**
     * O corpus embarcado, ou lista vazia se o recurso não estiver no artefato.
     *
     * Recurso ausente é o modo de falha realista: basta a cópia do build não
     * rodar. Ele **não** pode virar exceção, e `IndiceEmPeTest` prova que o
     * recurso está lá — senão a lista vazia passaria despercebida, com toda
     * pergunta virando recusa e o sistema parecendo "só conservador".
     */
    fun embarcado(): List<TrechoIndexado> =
        try {
            CorpusDeNormas::class.java.getResourceAsStream(RECURSO)?.use { ler(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    /** Lê de um fluxo qualquer — é por aqui que um corpus de fora do APK entraria. */
    fun ler(fluxo: InputStream): List<TrechoIndexado> =
        try {
            fluxo.bufferedReader(Charsets.UTF_8).useLines { linhas ->
                linhas.mapNotNull(::trechoDe).toList()
            }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Uma linha do JSONL vira [Trecho] + a sigla, ou `null`.
     *
     * `null` cobre tudo que não deve chegar ao índice: linha em branco, JSON
     * quebrado, campo faltando, campo vazio e trecho revogado.
     */
    fun trechoDe(linha: String): TrechoIndexado? {
        val campos = objetoRaso(linha) ?: return null
        if (campos["revogado"] == "true") return null

        val texto = campos["texto"].orEmpty()
        val citacao = campos["artigo"].orEmpty()       // artigo -> citacao
        val norma = campos["documento"].orEmpty()      // documento -> norma
        if (texto.isBlank() || citacao.isBlank() || norma.isBlank()) return null

        return try {
            TrechoIndexado(
                trecho = Trecho(texto = texto, citacao = citacao, norma = norma),
                sigla = campos["norma"].orEmpty(),     // "CTB" — termo de busca, não citação
                titulo = campos["titulo"].orEmpty(),
            )
        } catch (_: IllegalArgumentException) {
            // O construtor de Trecho lança de propósito em violação de programação.
            // Aqui isso é dado ruim de arquivo, não bug — descarta a linha e segue.
            null
        }
    }

    /**
     * Parser de objeto JSON **raso**: só pares `"chave": "valor"` e
     * `"chave": true|false|null`, que é exatamente a forma de cada linha do
     * corpus. Objeto aninhado ou array devolve `null`.
     *
     * **Por que não uma biblioteca.** `core-knowledge` declara uma dependência
     * só (`:core-common`), e essa lista é a fronteira que impede texto
     * recuperado de virar ação — `FronteiraDoConhecimentoTest` derruba o build
     * se ela crescer. Trocar essa garantia por um parser de 40 linhas que lê um
     * formato de 7 campos conhecidos seria mau negócio.
     */
    private fun objetoRaso(linha: String): Map<String, String>? {
        val s = linha.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null

        val fora = HashMap<String, String>()
        var i = 1
        val fim = s.length - 1
        while (i < fim) {
            while (i < fim && (s[i] == ' ' || s[i] == ',')) i++
            if (i >= fim) break

            if (s[i] != '"') return null
            val (chave, aposChave) = texto(s, i) ?: return null
            i = aposChave

            while (i < fim && s[i] == ' ') i++
            if (i >= fim || s[i] != ':') return null
            i++
            while (i < fim && s[i] == ' ') i++
            if (i >= fim) return null

            if (s[i] == '"') {
                val (valor, aposValor) = texto(s, i) ?: return null
                fora[chave] = valor
                i = aposValor
            } else {
                val inicio = i
                while (i < fim && s[i] != ',') i++
                val cru = s.substring(inicio, i).trim()
                // Só literais são aceitos: `{` ou `[` aqui significa objeto
                // aninhado, que este parser não lê — e ler pela metade é pior
                // que recusar.
                if (cru !in LITERAIS) return null
                fora[chave] = cru
            }
        }
        return fora
    }

    private val LITERAIS = setOf("true", "false", "null")

    /**
     * Lê a string JSON que começa em [inicio] (a aspa). Devolve o conteúdo já
     * sem escapes e o índice logo depois da aspa de fechamento, ou `null` se a
     * string não fecha.
     */
    private fun texto(s: String, inicio: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = inicio + 1
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= s.length) return null
                    when (val e = s[i + 1]) {
                        '"', '\\', '/' -> { sb.append(e); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'u' -> {
                            if (i + 5 >= s.length) return null
                            val hex = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            sb.append(hex.toChar()); i += 6
                        }
                        else -> return null
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}

/**
 * O [Trecho] mais o que serve para **achá-lo** e não para dizê-lo.
 *
 * A sigla (`"CTB"`) e o título (`"Desacato"`) entram no índice porque o agente
 * usa essas palavras — mas nunca são lidos em voz alta no lugar de
 * [Trecho.norma] e [Trecho.citacao]. Separar os dois papéis num tipo é o que
 * impede a confusão de voltar por outro caminho.
 */
internal data class TrechoIndexado(
    val trecho: Trecho,
    val sigla: String,
    val titulo: String,
)
