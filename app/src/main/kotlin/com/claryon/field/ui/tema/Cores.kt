package com.claryon.field.ui.tema

import androidx.compose.ui.graphics.Color

/**
 * **Paleta do Claryon Field.**
 *
 * O aparelho é um painel de instrumento, não um aplicativo de mensagens com tema
 * escuro. Três decisões carregam a identidade:
 *
 *  1. **O fundo não é preto puro.** `#0A0C0F` tem viés azul-ardósia. Preto
 *     absoluto num OLED à noite produz contraste agressivo contra texto branco —
 *     e o agente olha esta tela no escuro da viatura, com a pupila dilatada. O
 *     azul mínimo também muda a leitura: preto puro é ausência, ardósia é
 *     equipamento ligado.
 *
 *  2. **O âmbar tem um significado só: você está no ar.** Não é cor de destaque,
 *     não marca botão primário, não pinta ícone selecionado. Uma transmissão
 *     acidental difunde para a guarnição inteira, então o sinal que a anuncia não
 *     pode aparecer em nenhum outro contexto — no instante em que âmbar significa
 *     duas coisas, deixa de significar qualquer uma.
 *
 *  3. **A estrutura é feita de fios, não de caixas.** `Traco` a 1 px separa;
 *     sombra e cantos arredondados quase não existem. Densidade com hierarquia:
 *     tudo é dado, e a interface recua para o dado ler.
 *
 * A cor de prioridade é semântica e **separada** do âmbar de transmissão. As duas
 * famílias nunca se encontram no mesmo elemento.
 */
object Cores {

    // ── Fundos ────────────────────────────────────────────────────────────────

    /** Fundo da aplicação. Ardósia quase preta. */
    val Vazio = Color(0xFF0A0C0F)

    /** Superfície de painel — barras, listas. */
    val Painel = Color(0xFF12151A)

    /** Cartão elevado. A única "elevação" do sistema; não há sombra. */
    val Elevado = Color(0xFF1A1E25)

    /** Estado pressionado / seleção. */
    val Pressionado = Color(0xFF232830)

    // ── Estrutura ─────────────────────────────────────────────────────────────

    /** Fio de 1 px. É ele que constrói o layout, não a caixa. */
    val Traco = Color(0xFF252A32)

    /** Fio de ênfase — separa seções, não itens. */
    val TracoForte = Color(0xFF343A44)

    // ── Texto ─────────────────────────────────────────────────────────────────

    val Tinta = Color(0xFFEDEFF2)
    val TintaMedia = Color(0xFF8C939C)
    val TintaFraca = Color(0xFF565D66)

    // ── Sinal de transmissão — uso único ─────────────────────────────────────

    /**
     * **No ar.** Reservado à transmissão em curso, e a nada mais.
     *
     * Âmbar e não verde: é a cor do LED de transmissão de um rádio e da luz de
     * emergência brasileira. Verde diria "tudo certo", que é o oposto do que
     * transmitir significa — transmitir é comprometer o canal da guarnição.
     */
    val NoAr = Color(0xFFFF6B35)

    /** Âmbar rebaixado, para trilhas e rastros da forma de onda. */
    val NoArFraco = Color(0x33FF6B35)

    // ── Semântica ─────────────────────────────────────────────────────────────

    /** Presença: par publicando posição, sessão viva, rede conectada. */
    val Vivo = Color(0xFF4FB286)

    /** Prioridade 1 — emergência. */
    val P1 = Color(0xFFE4483C)

    /** Prioridade 2 — alta. */
    val P2 = Color(0xFFE0A03C)

    /** Prioridade 3 — normal. */
    val P3 = Color(0xFF3E8C93)

    /** Falha, permissão negada, capacidade morta. */
    val Falha = Color(0xFFE4483C)
}
