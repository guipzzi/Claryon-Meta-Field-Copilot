package com.claryon.field.ui.telas

/**
 * **A idade da posição própria, partida em número e unidade — sem uma linha de Compose.**
 *
 * Mesmo motivo de `TrafegoDoCanal.kt`: o projeto não declara `ui-test-junit4`, então
 * decisão que mora dentro de `@Composable` é decisão sem teste. Aqui é função pura
 * sobre `Int?`, e o composable só traduz em pixel.
 *
 * ---
 * ### Por que partido em dois
 *
 * O número de destaque do sistema é `Tipo.Heroi` (44 sp, **Light**) e a unidade é
 * `Tipo.HeroiUnidade` (15 sp). Os dois estilos existiam desde que a escala foi
 * escrita e **não tinham um único chamador** — o oitavo caso do padrão que o
 * `CLAUDE.md` §6 nomeia. Compor `"há 12 s"` numa string só devolveria o "12" e o
 * "s" no mesmo corpo, que é precisamente o que a régua da referência proíbe: o
 * algarismo é grande e leve, a unidade é pequena e some.
 *
 * ---
 * ### Por que ESTE número, e não outro
 *
 * Decisão do dono, 22/08: **a idade da posição é a única quantidade real que este
 * produto mostra.** O resto são estados — no ar, na escuta, recusado, sem sinal —, e
 * herói sobre estado é decoração. A referência tem `289 KM` e `73%` porque mede
 * coisas; forçar um herói onde não há grandeza inventaria uma.
 *
 * É também, literalmente, o dado que a auditoria de GPS apontou como ausente:
 * `EstadoDoMapa.minhaIdadeS` vinha de `idade_solicitante_s` em toda linha de
 * `posicoes_do_grupo` desde a migração `0007`, era lido no `MapaViewModel` e
 * **morria ali** — o mapa carimbava a idade de cada par e não a do portador.
 */
data class IdadeHeroica(
    /** O algarismo. `"—"` quando não há posição no servidor. */
    val numero: String,
    /** `"s"`, `"min"`, `"h"` — ou vazio quando não há número. */
    val unidade: String,
    /**
     * `true` quando o servidor não tem posição nenhuma deste agente.
     *
     * Separado de `numero == "—"` de propósito: quem desenha precisa trocar a
     * **frase**, não só o glifo, e comparar contra um travessão é o tipo de teste de
     * string que quebra na primeira mudança de tipografia.
     */
    val ausente: Boolean,
)

/**
 * **Trunca para baixo, sempre.**
 *
 * A mesma direção de erro que `duracaoLegivel` e a migração `0020` escolheram:
 * errar para o lado que **não inventa**. Uma posição de 119 s vira `"1 min"` e não
 * `"2 min"` — afirmar mais idade do que houve faria o agente descartar um dado
 * ainda bom; afirmar menos faria confiar num dado velho, que é o erro perigoso.
 * Truncar para baixo erra na direção segura porque o número exibido é sempre
 * **menor ou igual** à idade real, e a régua de frescor (`Frescor.ESMAECIDO` a
 * 2 min) age sobre o segundo cru, não sobre este texto.
 *
 * Negativo é tratado como zero: relógio de servidor adiantado em relação ao do
 * aparelho produz idade negativa, e `"-3 s"` num painel lê como defeito.
 */
fun idadeHeroica(segundos: Int?): IdadeHeroica {
    if (segundos == null) return IdadeHeroica(numero = "—", unidade = "", ausente = true)
    val s = segundos.coerceAtLeast(0)
    return when {
        s < 60 -> IdadeHeroica("$s", "s", ausente = false)
        s < 3600 -> IdadeHeroica("${s / 60}", "min", ausente = false)
        else -> IdadeHeroica("${s / 3600}", "h", ausente = false)
    }
}

/**
 * A frase ao lado do número. Curta, e ela muda de **sujeito** quando não há dado.
 *
 * *"no servidor"* afirma sobre o servidor, que é quem tem ou não a posição — não
 * sobre o GPS deste aparelho, que pode estar funcionando perfeitamente enquanto o
 * `POST` falha. A distinção é a mesma que o cabeçalho já faz entre RECEBER e
 * TRANSMITIR, e foi a auditoria de GPS que a exigiu.
 */
fun rotuloDoFrescor(idade: IdadeHeroica): String =
    if (idade.ausente) "sem posição no servidor" else "no servidor"
