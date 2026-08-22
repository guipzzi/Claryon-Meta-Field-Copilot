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
 * ### 2. Acromático é a BASE. Cor é reservada para SINAL.
 *
 * **Diff de spec — 22/08, decisão do dono do produto** (§7: sobrepor regra dura é
 * decisão humana, e ela entra por escrito).
 *
 * A regra anterior dizia *"cor é rara, e cada aparição corresponde a um estado
 * excepcional e verdadeiro no instante"*. Ela era certa e **não bastava**, porque
 * "excepcional" é adjetivo e todo estado parece excepcional para quem o está
 * desenhando. Medido na auditoria de 22/08: 45 usos cromáticos em 10 arquivos, e a
 * tela do mapa sozinha gastava **três cromáticas por linha de par**, vezes trinta
 * linhas na gaveta. A régua da referência de produto é acromática — hierarquia sai
 * de opacidade, peso e posição —, e a nossa não estava.
 *
 * A regra nova troca o adjetivo por uma **pergunta**, e a pergunta tem resposta
 * verificável:
 *
 * > **Isto é um sinal, ou é estrutura/estado sendo decorado?**
 *
 *  - **Sinal** → pode ter cor. Aviso que bloqueia operação, emergência P1, "no ar",
 *    falha que exige ação do agente.
 *  - **Estrutura ou estado** → acromático, **sempre**. Ponto de presença, item de
 *    lista, linha de prontidão, frescor, separador, fundo, borda, rótulo.
 *
 * E o orçamento que a acompanha: **um elemento cromático por sinal, não um por
 * pedaço do sinal.** Onde três elementos da mesma linha viravam vermelho juntos —
 * ícone, valor e explicação em `TelaDePermissoes` —, sobra **um**: o valor. Os
 * outros dois carregam o mesmo fato em nível de tinta, que é o canal que a
 * referência usa e que sobrevive a daltonismo.
 *
 * A consequência prática continua sendo a regra que mais dói, e agora ela é
 * corolário em vez de axioma: **cor em elemento permanente é cor que deixou de
 * significar.** Um ponto verde aceso o turno inteiro porque o par está online é
 * estado, não sinal — ele é o fundo. O que informa é o par que *some*.
 *
 * **O que isso aposentou.** `Vivo` e o antigo `P3` saíram do arquivo, e a razão é a
 * pergunta acima: os dois nomeavam o **caso nominal** — "o par está vivo", "a
 * prioridade é normal" —, e caso nominal nunca é sinal. Não havia um só uso
 * legítimo sobrando depois da varredura; mantê-los seria guardar cor à espera de
 * quem a gastasse. Prioridade normal continua se distinguindo por **largura de
 * calha e rótulo escrito**, que é como ela já se distinguia para quem não vê cor.
 *
 * O que **ficou** cromático, e cada um é um sinal: [NoAr] (você está no ar), [P1]
 * (emergência), [P2] (aviso que bloqueia operação — servidor não configurado, sem
 * posição própria), [Falha] e [FalhaTexto] (falha que exige ação — erro de login,
 * fora do mapa, escuta recusada, permissão negada).
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
 * (36,6° → 41,5°, e deixou de ser laranja para ser amarelo) e o antigo `P3` **perdeu
 * a cor inteira**, porque prioridade normal não é estado excepcional — e em 22/08
 * perdeu também o token, por não ter sobrado chamador nenhum.
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
 * | [NoAr] | 5,88 | 5,88 | 3,0 |
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

    // **`Vivo` e `P3` foram removidos em 22/08.** Ver a decisão 2: os dois
    // nomeavam o caso NOMINAL — "o par está vivo", "a prioridade é normal" — e o
    // caso nominal nunca é sinal. Depois da varredura não sobrou um uso legítimo
    // de nenhum dos dois, e token sem chamador é escrito, não construído (§6).
    //
    // Quem procurar o verde: presença virou nível de tinta ([TintaFraca] para o
    // nominal, [TintaMedia] para a linha viva). Quem procurar o cinza do P3: ele
    // era `#8B8B8B`, a dois pontos de [TintaFraca] (`#888888`) — duas entradas
    // para a mesma cor, que é a duplicata que diverge no primeiro ajuste. A
    // prioridade normal continua distinguível por largura de calha e rótulo.

    /**
     * Prioridade 1 — emergência. Marca, não texto (piso 3:1).
     *
     * **A única prioridade que continua colorida**, e o critério é o da decisão 2:
     * emergência é sinal, e cor de urgência tem valor funcional numa ocorrência —
     * o agente a reconhece antes de ler a palavra. As outras duas prioridades são
     * classificação, e classificação é estado.
     */
    val P1 = Color(0xFFE4483C)

    /**
     * **Aviso que bloqueia operação.** Amarelo, e não âmbar: saiu da faixa do [NoAr].
     *
     * Deixou de ser "prioridade 2" em 22/08 — prioridade é classificação, e
     * classificação é estado. O que sobrou para ele é o banner de aviso, que é
     * sinal por definição: *"servidor não configurado"*, *"sem posição própria"*.
     * Nos dois casos o agente não consegue trabalhar até resolver, e nos dois ele é
     * **um** elemento na tela.
     */
    val P2 = Color(0xFFD9A227)

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
