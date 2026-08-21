package com.claryon.field.ui.tema

import androidx.compose.ui.graphics.Color

/**
 * **Paleta do Claryon Field.**
 *
 * O aparelho é um painel de instrumento, não um aplicativo de mensagens com tema
 * escuro. Quatro decisões carregam a identidade, e **três delas são verificáveis
 * por medição**, não por gosto.
 *
 * ---
 * ### 1. A base é cinza de verdade. Chroma zero, sem exceção.
 *
 * Fundo, painel, fio, tipografia e ícone são preto/cinza/branco. Nenhum token de
 * estrutura tem matiz — `max(R,G,B) - min(R,G,B) == 0` em todos eles.
 *
 * A versão anterior tinha viés azul-ardósia (216°, chroma de 5 a 16 unidades RGB).
 * Medido, esse viés era **quase imperceptível**: 5 unidades a 4,9% de luminosidade
 * não se lê como cor. O ganho de removê-lo não é ótico, é **de regra**: chroma
 * zero é uma propriedade que um teste consegue afirmar, e um limite que se pode
 * conferir com `grep` vale mais que um limite que se pode ver. Enquanto a base
 * tinha 5 unidades de matiz, "a base não tem cor" era uma intenção; agora é um
 * invariante.
 *
 * A luminosidade de cada degrau foi **preservada** na conversão, então a
 * hierarquia de superfície não mudou de lugar — só perdeu o matiz.
 *
 * ---
 * ### 2. Cor é rara, e por isso significa. Quatro cromáticas, e um orçamento.
 *
 * Vermelho, amarelo, verde e âmbar continuam existindo — como **complemento**,
 * nunca como base. O critério de uso é duplo e os dois lados são obrigatórios:
 * cada aparição de cor tem de corresponder a um estado **excepcional** e
 * **verdadeiro no instante**.
 *
 * A consequência prática é a regra que mais dói: **cor em elemento permanente é
 * cor que deixou de significar.** Um ponto verde que fica aceso o turno inteiro
 * porque o par está online não informa nada — ele é o fundo. O que informa é o
 * par que *some*. Estado nominal é cinza; a cor entra quando o nominal quebra.
 *
 * ---
 * ### 3. O âmbar tem um significado só: você está no ar.
 *
 * Não é cor de destaque, não marca botão primário, não pinta ícone selecionado.
 * Uma transmissão acidental difunde para a guarnição inteira, então o sinal que a
 * anuncia não pode aparecer em nenhum outro contexto.
 *
 * **A regra não se sustenta por matiz, e isso foi medido.** [NoAr] (16°) e [P1]
 * (4°) estão a 11,8° um do outro; [P2] estava a 20,5°. Separar matizes o bastante
 * para tornar quatro sinais inconfundíveis exigiria espalhá-los pela roda inteira,
 * e aí vermelho deixaria de ser emergência. O que sustenta a regra é a **forma**:
 *
 *  - a **moldura de tela cheia**, o rótulo "NO AR" e a forma de onda ao vivo são
 *    exclusivos do [NoAr] — nenhum outro estado pode tomar essas formas;
 *  - nenhuma outra cor aparece em elemento que sangre até a borda da tela.
 *
 * O que a paleta faz é tirar os concorrentes de perto: [P2] saiu do âmbar
 * (36,6° → 41,5°, e deixou de ser laranja para ser amarelo) e [P3] **perdeu a cor
 * inteira**, porque prioridade normal não é estado excepcional.
 *
 * ---
 * ### 4. A estrutura é feita de fios, não de caixas.
 *
 * [Traco] a 1 px separa; sombra e canto arredondado não existem. Cada linha de
 * dado é ícone → rótulo → **valor à direita**, e o que a separa da seguinte é um
 * fio que não fecha caixa nenhuma. É livro-razão, não cartão.
 *
 * ---
 * ### Contraste medido (WCAG 2.1), sobre o pior fundo — [Elevado]
 *
 * | token | antes | agora | piso |
 * |---|---|---|---|
 * | [Tinta] | 14,51 | 14,50 | 4,5 |
 * | [TintaMedia] | 5,39 | **7,01** | 4,5 |
 * | [TintaFraca] | **2,51 ✗** | **4,70 ✓** | 4,5 |
 * | [P3] | 4,28 ✗ | **4,89 ✓** | 4,5 |
 * | [NoAr] | 5,88 | 5,88 | 3,0 |
 * | [Vivo] | 6,40 | 6,78 | 3,0 |
 * | [P1] / [Falha] | 4,20 | 4,20 | 3,0 |
 * | [P2] | 7,37 | 7,26 | 3,0 |
 *
 * A correção de [TintaFraca] é a mais importante da paleta e não era visível a
 * olho: ela é a **cor padrão de `Etiqueta`**, o elemento mais usado do sistema, e
 * a 2,51:1 renderizava quase invisíveis exatamente os rótulos que este projeto
 * lutou para tornar honestos — *"não saiu"*, *"sem sinal"*, *"áudio sem
 * transcrição"*. Reprovava AA (4,5:1) e reprovava até o piso não-textual (3:1).
 *
 * Os dois pisos são diferentes porque os papéis são diferentes, e a doutrina já
 * estava escrita em `TelaDeGuarnicao.kt`: **cor de prioridade é marca, não
 * texto.** Calha, banda e ponto respondem a 3:1; o texto por cima deles sai em
 * [Tinta]. Onde um estado precisa mesmo virar palavra, existe [FalhaTexto].
 */
object Cores {

    // ── Fundos — cinza puro, luminosidade preservada da paleta anterior ───────

    /** Fundo da aplicação. */
    val Vazio = Color(0xFF0C0C0C)

    /** Superfície de painel — barras, listas. */
    val Painel = Color(0xFF151515)

    /** Cartão elevado. A única "elevação" do sistema; não há sombra. */
    val Elevado = Color(0xFF1E1E1E)

    /** Estado pressionado / seleção. */
    val Pressionado = Color(0xFF282828)

    // ── Estrutura ─────────────────────────────────────────────────────────────

    /**
     * Fio de 1 px. É ele que constrói o layout, não a caixa.
     *
     * 1,16:1 sobre [Elevado] — **abaixo** do piso não-textual de 3:1, e de
     * propósito. Um fio a 3:1 desenha uma grade de caixas, que é exatamente o
     * oposto do que ele existe para fazer. O fio não carrega informação: ele
     * separa informação que já está legível por conta própria. Exceção
     * consciente, registrada aqui para não ser "consertada" por engano.
     */
    val Traco = Color(0xFF2A2A2A)

    /** Fio de ênfase — separa seções, não itens. 1,47:1; mesma exceção. */
    val TracoForte = Color(0xFF3A3A3A)

    // ── Texto — três degraus, todos acima de AA no pior fundo ─────────────────

    /** Primário. 14,50:1 sobre [Elevado]. */
    val Tinta = Color(0xFFEFEFEF)

    /** Secundário: rótulo, unidade, dado de apoio. 7,01:1. */
    val TintaMedia = Color(0xFFA8A8A8)

    /**
     * Terciário. 4,70:1 — no piso de AA, e **nunca abaixo**.
     *
     * É a cor padrão de `Etiqueta`. Se alguém a rebaixar de novo, a interface
     * inteira perde legibilidade de uma vez, sem que nenhum teste reclame.
     */
    val TintaFraca = Color(0xFF888888)

    // ── Sinal de transmissão — uso único ─────────────────────────────────────

    /**
     * **No ar.** Reservado à transmissão em curso, e a nada mais.
     *
     * Âmbar e não verde: é a cor do LED de transmissão de um rádio e da luz de
     * emergência brasileira. Verde diria "tudo certo", que é o oposto do que
     * transmitir significa — transmitir é comprometer o canal da guarnição.
     *
     * O que protege este significado é a **forma exclusiva** (moldura de tela
     * cheia, rótulo "NO AR", forma de onda ao vivo), não a distância de matiz.
     * Ver a decisão 3 no KDoc do objeto.
     */
    val NoAr = Color(0xFFFF6B35)

    /** Âmbar rebaixado, para trilhas e rastros da forma de onda. */
    val NoArFraco = Color(0x33FF6B35)

    // ── Semântica — cada uma custa caro, e só entra em estado excepcional ─────

    /**
     * **Presença excepcional**, não presença.
     *
     * Não pinte o que está normal. Par online o turno inteiro é o fundo do
     * problema, não o sinal — quem informa é o par que sumiu. Use em transição
     * verdadeira (o copiloto ouvindo agora, o par que acabou de voltar), nunca
     * em indicador permanente.
     */
    val Vivo = Color(0xFF46B985)

    /** Prioridade 1 — emergência. Marca, não texto (piso 3:1). */
    val P1 = Color(0xFFE4483C)

    /** Prioridade 2 — alta. Amarelo, e não âmbar: saiu da faixa do [NoAr]. */
    val P2 = Color(0xFFD9A227)

    /**
     * Prioridade 3 — normal. **Cinza, e isso é a decisão.**
     *
     * Era um teal a 185°, a 31,6° do [Vivo] — confundível, e pior: prioridade
     * normal é o caso comum. Cor no caso comum é cor gasta. O que distingue P3
     * continua existindo em dois canais que não dependem de visão de cor: a
     * largura da calha (2 px contra 4 px do P1) e o rótulo escrito.
     */
    val P3 = Color(0xFF8B8B8B)

    /** Falha, permissão negada, capacidade morta. Marca (piso 3:1). */
    val Falha = Color(0xFFE4483C)

    /**
     * Falha **como palavra**. 6,76:1 sobre [Elevado].
     *
     * [Falha] a 4,20:1 é marca e reprova como texto pequeno. Existem lugares em
     * que o estado precisa mesmo ser lido — a causa do erro de login, a idade de
     * uma posição vencida. Este é o token para eles, e o único motivo de ele
     * existir separado é que a marca deve continuar saturada.
     */
    val FalhaTexto = Color(0xFFE88C84)
}
