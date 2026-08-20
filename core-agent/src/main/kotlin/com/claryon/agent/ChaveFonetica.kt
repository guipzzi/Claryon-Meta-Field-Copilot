package com.claryon.agent

import java.text.Normalizer
import kotlin.math.min

/**
 * **Chave fonética de pt-BR — para comparar como SOA, não como se escreve.**
 *
 * ## O problema que ela resolve
 *
 * O whisper erra a grafia de palavra rara de forma **sistemática**, não aleatória:
 * "Claryon" volta como "Clareon", "Clarion", "Clarionn"; "guarnição" volta como
 * "Guarney são". Enumerar as grafias uma a uma não escala — são centenas de
 * variantes plausíveis, e cada uma acrescentada à mão é uma linha de código que
 * ninguém revisa.
 *
 * A chave colapsa a variação **gráfica** que o português permite sobre o mesmo som.
 * "claryon", "clarion", "clareon" e "klarion" viram a mesma chave; "clarissa" não.
 *
 * ## O que ela NÃO é
 *
 * Não é transcrição fonética. É um colapso conservador de grafemas — o suficiente
 * para o casamento contra um vocabulário **fechado** (a palavra de ativação e os
 * rótulos das guarnições do agente). Contra vocabulário aberto ela aceitaria demais,
 * e é por isso que o portão continua sendo o léxico, não a chave.
 *
 * ## Por que a distância de edição, e por que ela é medida e não escolhida
 *
 * Nem toda variante colapsa na mesma chave: "guarney sao" tem uma sílaba a mais que
 * "guarnicao". Por isso a comparação admite uma distância pequena — e o valor dela
 * saiu de medir recall E precisão sobre centenas de variantes geradas, positivas e
 * negativas. Ver `GrafiasDaAtivacaoTest`.
 */
object ChaveFonetica {

    /**
     * Reduz a grafia ao som aproximado.
     *
     * As regras são as do português brasileiro e cada uma existe por um par que o
     * whisper de fato confunde:
     *
     * - `y → i` — "claryon"/"clarion"
     * - `ph → f`, `h` mudo cai — grafia importada
     * - `ç`, `ss`, `sc`, `xc`, `c(e|i)` → `s` — "guarnição"/"guarnisão"
     * - `c(a|o|u)`, `qu`, `k` → `k` — "claryon"/"klaryon"
     * - `ão|am|an|on|om|õe` → `ã` (marca nasal única) — "clareon"/"clareom"/"clareão"
     * - `lh → L`, `nh → N` — dígrafos que não se decompõem
     * - `rr → r`, `ss → s`, e toda letra dobrada colapsa
     * - `z` final e `s` final → `s`
     * - `e`/`i` átonos finais colapsam; `o`/`u` átonos finais colapsam
     */
    fun de(texto: String): String {
        var s = Normalizer.normalize(texto.lowercase().trim(), Normalizer.Form.NFD)
            // O til é a ÚNICA marca que sobrevive à remoção de acento: ele não é
            // ornamento, é nasalidade, e "cao" e "cão" são palavras diferentes.
            .replace("̃", "~")
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z~ ]"), "")

        s = s
            .replace("ph", "f")
            .replace("lh", "L").replace("nh", "N")
            .replace("qu", "k").replace("gu", "g")
            .replace("ch", "x")
            .replace("h", "")
            .replace("y", "i")
            .replace("w", "v")
            .replace(Regex("sc|xc|ss|c(?=[ei])|ç"), "s")
            .replace(Regex("c"), "k")
            .replace(Regex("z"), "s")
            // **O colapso de letras dobradas vem ANTES da regra nasal.**
            //
            // Invertido, "claryonn" não casava a nasal final (por causa do `nn`),
            // sobrava "klarion", e "claryon" virava "klari~": chaves diferentes para
            // a mesma palavra. Metade do espaço gráfico caía por essa ordem —
            // medido em `GrafiasDaAtivacaoTest`, 50% de cobertura.
            .replace(Regex("(.)\\1+"), "$1")
            // **A VOGAL antes da nasal fica.** Colapsar "on", "im" e "am" todos em
            // "~" fazia "clarim" e "claryon" terem a MESMA chave — e "clarim" é
            // palavra do português. O que a regra colapsa é só a consoante nasal
            // final (m ≡ n) e o ditongo "ão/ao", que é onde o whisper varia.
            .replace(Regex("(a~o|ao~|a~o|ao)(?=$|\\s)"), "an")
            .replace(Regex("m(?=$|\\s)"), "n")
            // **"e" átono antes de vogal nasal soa "i" em pt-BR.**
            //
            // "clareon" e "clarion" são a mesma palavra dita, e antes desta regra
            // ficavam a distância 1 — o que obrigava a tolerância a ser 1, e com 1
            // "clarim" (chave `klarin`) também entrava. Colapsando o par aqui, as
            // grafias medidas ficam a distância ZERO e a tolerância pode fechar.
            .replace(Regex("e(?=[aeiou]?n($|\\s))"), "i")

        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Distância de edição entre as CHAVES, não entre as grafias.
     *
     * Comparar grafias puniria "k" contra "c" como se fossem sons diferentes; sobre
     * a chave, esse par já colapsou e o que sobra é diferença real de som.
     */
    fun distancia(a: String, b: String): Int {
        val x = de(a)
        val y = de(b)
        if (x == y) return 0
        val anterior = IntArray(y.length + 1) { it }
        val atual = IntArray(y.length + 1)
        for (i in 1..x.length) {
            atual[0] = i
            for (j in 1..y.length) {
                val custo = if (x[i - 1] == y[j - 1]) 0 else 1
                atual[j] = min(min(atual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + custo)
            }
            anterior.indices.forEach { anterior[it] = atual[it] }
        }
        return anterior[y.length]
    }

    /**
     * `true` quando as duas grafias representam plausivelmente a mesma palavra.
     *
     * [tolerancia] é por SOM, não por letra: 1 admite uma sílaba trocada ou uma
     * consoante a mais. Acima de 2 o portão começa a aceitar nome próprio comum —
     * medido, não suposto.
     */
    fun pareceCom(grafia: String, canonica: String, tolerancia: Int = 1): Boolean =
        distancia(grafia, canonica) <= tolerancia
}
