package com.claryon.agent

import java.io.File
import org.junit.Assert.assertTrue

/**
 * **Os 1817 trechos de lei, lidos uma vez e por varredura linear.**
 *
 * O corpus adversarial deste projeto — `corpus/trechos.jsonl` — é material duro para
 * qualquer extrator de placa, porque texto legal é cheio de número ("Art. 306",
 * "§ 1º", "Lei nº 9.503", "R$ 500,00") e número colado a palavra é a matéria-prima de
 * uma corrida. Ele já era lido por `PlacaEmCorpusRealTest`; ganhou casa própria quando
 * `PlacaDitadaNoRoteadorTest` passou a precisar do mesmo texto, porque duas cópias do
 * leitor divergem no primeiro conserto aplicado a uma só delas.
 *
 * **A varredura é linear e sem pilha, de propósito.** `FronteiraDoConhecimentoEmAppTest`
 * registra que a versão com `"((?:[^"\\]|\\.)*)"` morreu de `StackOverflowError`
 * dentro do `java.util.regex.Pattern`: backtracking catastrófico sobre artigo de lei
 * longo. O defeito estava no INSTRUMENTO de medida, que é o pior lugar.
 */
internal object CorpusDeLei {

    /**
     * Os textos do corpus.
     *
     * **Falha em vez de devolver lista vazia.** Um teste que não acha o que precisa
     * conferir tem de falhar: zero trechos lidos daria um "nenhuma norma vira placa"
     * verdíssimo calculado sobre nada.
     */
    fun textos(): List<String> {
        val jsonl = raizDoRepositorio().resolve("corpus/trechos.jsonl")
        assertTrue(
            "corpus/trechos.jsonl não existe. Sem corpus este teste não prova nada, " +
                "então ele FALHA em vez de passar vazio.",
            jsonl.isFile,
        )
        return jsonl.readLines().mapNotNull { campo(it, "texto") }
    }

    private fun raizDoRepositorio(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "Não achei a raiz do repositório a partir de ${File(".").canonicalPath}. " +
                "Teste que não acha o que precisa conferir tem de falhar, não passar.",
        )
    }

    /** Valor de um campo string do JSON, tolerando espaço depois dos dois-pontos. */
    private fun campo(linha: String, nome: String): String? {
        val chave = "\"$nome\""
        var i = linha.indexOf(chave).takeIf { it >= 0 }?.plus(chave.length) ?: return null
        while (i < linha.length && linha[i].isWhitespace()) i++
        if (i >= linha.length || linha[i] != ':') return null
        i++
        while (i < linha.length && linha[i].isWhitespace()) i++
        if (i >= linha.length || linha[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < linha.length) {
            when (val c = linha[i]) {
                '\\' -> {
                    if (i + 1 >= linha.length) return sb.toString()
                    when (val e = linha[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 5 < linha.length) {
                                sb.append(linha.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                }
                '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
