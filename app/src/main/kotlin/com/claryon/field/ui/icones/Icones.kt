package com.claryon.field.ui.icones

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * **Os ícones do Claryon Field, desenhados como geometria.**
 *
 * Até hoje o aplicativo não tinha **um único ícone** — nem na barra de abas, nem
 * na frente de linha de dado nenhuma. A referência visual do produto (painel de
 * automóvel, escuro) é feita de contorno fino, e a ausência disso era a diferença
 * mais visível entre a referência e a tela.
 *
 * O precedente do projeto é [com.claryon.field.ui.marca.GeometriaDaMarca]: a marca
 * é reconstruída por geometria, e não colada como bitmap, porque bitmap não escala,
 * não anima e não responde ao tema. **Ícone tem exatamente o mesmo problema**, e
 * emoji tem um pior: emoji é desenhado pela fonte do sistema, com cor própria e
 * traço próprio, e num painel de croma zero ele entra como a única mancha colorida
 * da tela — cor que não significa nada, que é o que a paleta deste produto proíbe.
 *
 * ---
 * ### A gramática, e ela é a mesma em todos os quatorze
 *
 *  - **Quadro de 24 × 24**, conteúdo entre 2,5 e 21,5. A margem existe para o
 *    ícone não encostar no texto ao lado nem no alvo de toque em volta.
 *  - **Traço de [TRACO] = 1,6, e nada de preenchimento.** Nenhum `fill` em nenhum
 *    caminho — o que dá forma é a linha, como no resto do painel, que é feito de
 *    fios. Um ícone sólido no meio de uma tela de fios lê como componente de
 *    biblioteca, exatamente como o `ripple` e o canto arredondado que este projeto
 *    já removeu.
 *  - **Ponta reta ([StrokeCap.Butt]) e junta em esquadro ([StrokeJoin.Miter]).**
 *    É a decisão que a marca já tinha tomado e que está escrita lá: ponta
 *    arredondada faz a linha parecer rabisco à mão; reta faz dela **seção de
 *    instrumento**. O conjunto é a exceção que confirma: nenhum ícone tem canto
 *    redondo que não seja um raio de verdade da forma (a lente, a cabeça, o pino).
 *  - **Escala uniforme.** O traço é declarado no quadro de 24, então ele encolhe
 *    junto com o ícone: a 22 dp são 1,47 dp de traço; a 20 dp, 1,33 dp. Nenhum
 *    ícone tem traço próprio — se um deles parecer mais gordo que o vizinho, é
 *    defeito, não ajuste.
 *  - **Sem cor.** Igual à marca: quem pinta é quem desenha. Todos saem em branco e
 *    recebem `tint` no local de uso, então o mesmo ícone serve `Tinta`,
 *    `TintaMedia` e `TintaFraca` sem um segundo arquivo.
 *  - **`contentDescription = null` em todo uso.** Os quatorze aparecem **ao lado
 *    de texto que já diz a mesma coisa** — aba com rótulo, linha de permissão com
 *    título, botão com verbo. Ícone com descrição própria nesse arranjo faz o
 *    leitor de tela dizer tudo duas vezes. Se um dia um destes aparecer sozinho,
 *    quem o puser lá é que deve a descrição.
 *
 * ---
 * ### Por que cada forma, onde a escolha não era óbvia
 *
 * **As três da barra de abas têm de ser inconfundíveis entre si, de relance e no
 * escuro.** Esse é o critério que decidiu [Radio]. O caminho natural para
 * "guarnição" seria o pictograma de duas pessoas — e ele é justamente o pior aqui,
 * porque a terceira aba é [Perfil], que é **uma** pessoa: duas silhuetas humanas
 * lado a lado, a 22 dp, viram a mesma mancha. O rádio portátil não se parece com
 * pessoa nem com mapa em nenhum tamanho, e é honesto com o que a aba mostra — o
 * tráfego do canal da guarnição.
 *
 * [Mapa] é o mapa **dobrado**, e não o pino: o pino já é [Local], que marca a linha
 * de permissão de localização, e a aba não é "onde eu estou", é a carta inteira.
 *
 * [Oculos] tem lente retangular, não redonda. Óculos de lente redonda é o
 * pictograma de "leitura"; o aparelho deste produto é um Ray-Ban de armação reta, e
 * a forma reta ainda combina com a gramática de esquadro do conjunto.
 *
 * [Destravar] — cadeado de arco aberto — é o ícone do botão "Permitir", e a escolha
 * é semântica, não decorativa: um "certo" ali diria que a permissão **já** foi
 * concedida, que é precisamente a mentira que a tela de permissões existe para não
 * contar.
 *
 * [Externo] marca a única ação da tela que **sai do aplicativo** (o diálogo do Meta
 * AI). É o único ícone com essa função e ele não deve aparecer em ação local.
 */
object Icones {

    /** Lado do quadro. Também o tamanho padrão, quando ninguém dimensiona. */
    private val QUADRO = 24.dp

    /** Coordenadas do desenho. Tudo é fração deste quadro. */
    private const val VIEWPORT = 24f

    /**
     * Espessura do traço, no quadro de 24.
     *
     * 1,6 e não 2,0: a referência pede contorno **fino**, e 2,0 no quadro de 24 é o
     * traço do Material, que a esta distância lê como negrito. Também não 1,0 — a
     * 20 dp isso daria 0,83 dp, e abaixo de 1 dp o antialiasing come metade da
     * linha e o ícone fica cinza em vez de desenhado. É o mesmo raciocínio de
     * sobrevivência à redução que a marca já registra.
     */
    private const val TRACO = 1.6f

    // ── Barra de abas ─────────────────────────────────────────────────────────

    /**
     * **Guarnição.** Rádio portátil: corpo, antena e duas linhas de alto-falante.
     *
     * A antena sai do canto e quebra o retângulo — é ela que impede o corpo de ler
     * como "documento" ou "celular". As duas linhas do alto-falante ficam no terço
     * de cima, onde ficam de fato num HT, e não no meio: centradas elas leem como
     * visor.
     */
    val Radio: ImageVector = icone("radio") {
        moveTo(7f, 6f); horizontalLineTo(17f); verticalLineTo(21.5f)
        horizontalLineTo(7f); close()
        moveTo(14.5f, 6f); lineTo(17.5f, 2.5f)
        moveTo(9.5f, 10f); horizontalLineTo(14.5f)
        moveTo(9.5f, 13f); horizontalLineTo(14.5f)
    }

    /**
     * **Mapa.** Carta dobrada em três painéis, com as duas dobras marcadas.
     *
     * As dobras são o que distingue mapa de "polígono qualquer": sem elas o
     * contorno em ziguezague vira uma bandeira.
     */
    val Mapa: ImageVector = icone("mapa") {
        moveTo(2.5f, 7f); lineTo(9f, 4f); lineTo(15f, 7f); lineTo(21.5f, 4f)
        verticalLineTo(17f); lineTo(15f, 20f); lineTo(9f, 17f); lineTo(2.5f, 20f)
        close()
        moveTo(9f, 4f); verticalLineTo(17f)
        moveTo(15f, 7f); verticalLineTo(20f)
    }

    /** **Perfil.** Uma cabeça e os ombros. */
    val Perfil: ImageVector = icone("perfil") {
        moveTo(8.4f, 7.8f)
        arcTo(3.6f, 3.6f, 0f, false, true, 15.6f, 7.8f)
        arcTo(3.6f, 3.6f, 0f, false, true, 8.4f, 7.8f)
        close()
        moveTo(4.8f, 21f); verticalLineTo(19.2f)
        arcTo(4.2f, 4.2f, 0f, false, true, 9f, 15f)
        horizontalLineTo(15f)
        arcTo(4.2f, 4.2f, 0f, false, true, 19.2f, 19.2f)
        verticalLineTo(21f)
    }

    // ── Linhas de permissão ───────────────────────────────────────────────────

    /** **Microfone.** Cápsula, berço e haste — o microfone de mesa, não o de lapela. */
    val Microfone: ImageVector = icone("microfone") {
        moveTo(9f, 6f)
        arcTo(3f, 3f, 0f, false, true, 15f, 6f)
        verticalLineTo(11.6f)
        arcTo(3f, 3f, 0f, false, true, 9f, 11.6f)
        close()
        moveTo(5f, 11.4f); verticalLineTo(12.6f)
        arcTo(7f, 7f, 0f, false, false, 19f, 12.6f)
        verticalLineTo(11.4f)
        moveTo(12f, 19.6f); verticalLineTo(22f)
        moveTo(8.5f, 22f); horizontalLineTo(15.5f)
    }

    /** **Bluetooth.** A runa Hagall/Bjarkan do padrão, em traço único. */
    val Bluetooth: ImageVector = icone("bluetooth") {
        moveTo(7.25f, 7.25f); lineTo(16.75f, 16.75f); lineTo(12f, 21.5f)
        verticalLineTo(2.5f); lineTo(16.75f, 7.25f); lineTo(7.25f, 16.75f)
    }

    /** **Local.** Pino com furo — o furo é o que o separa de uma gota. */
    val Local: ImageVector = icone("local") {
        moveTo(19.8f, 10.4f)
        curveToRelative(0f, 6.2f, -7.8f, 11.1f, -7.8f, 11.1f)
        reflectiveCurveToRelative(-7.8f, -4.9f, -7.8f, -11.1f)
        arcToRelative(7.8f, 7.8f, 0f, true, true, 15.6f, 0f)
        close()
        moveTo(9.1f, 10.4f)
        arcTo(2.9f, 2.9f, 0f, false, true, 14.9f, 10.4f)
        arcTo(2.9f, 2.9f, 0f, false, true, 9.1f, 10.4f)
        close()
    }

    /** **Câmera.** Corpo reto, ressalto do visor e a lente. */
    val Camera: ImageVector = icone("camera") {
        moveTo(2.5f, 7.5f); horizontalLineTo(21.5f); verticalLineTo(20.5f)
        horizontalLineTo(2.5f); close()
        moveTo(8.5f, 7.5f); lineTo(9.8f, 4.5f); horizontalLineTo(14.2f)
        lineTo(15.5f, 7.5f)
        moveTo(8.4f, 14f)
        arcTo(3.6f, 3.6f, 0f, false, true, 15.6f, 14f)
        arcTo(3.6f, 3.6f, 0f, false, true, 8.4f, 14f)
        close()
    }

    /** **Notificação.** Sino com badalo. */
    val Sino: ImageVector = icone("sino") {
        moveTo(18f, 9.5f)
        arcTo(6f, 6f, 0f, false, false, 6f, 9.5f)
        curveTo(6f, 16.5f, 3f, 18.5f, 3f, 18.5f)
        horizontalLineTo(21f)
        curveTo(21f, 18.5f, 18f, 16.5f, 18f, 9.5f)
        close()
        moveTo(13.7f, 21.3f)
        arcTo(2f, 2f, 0f, false, true, 10.3f, 21.3f)
    }

    /**
     * **Óculos.** Duas lentes largas e a ponte arqueada sobre o nariz.
     *
     * A primeira versão era dois retângulos de canto vivo com um traço reto entre
     * eles, e não lia como óculos: lia como duas caixas ligadas por um fio. Duas
     * correções, e as duas são de forma, não de tamanho — o raio de 1,5 nas lentes
     * e a **ponte em arco**. O arco é o que o olho usa para reconhecer a peça: é a
     * única curva de um par de óculos vista de frente, e sem ela sobram dois
     * retângulos.
     *
     * O canto redondo aqui é o raio de verdade da lente, que é a exceção que o
     * KDoc do objeto já nomeia — não é o canto de biblioteca que o sistema recusa.
     */
    val Oculos: ImageVector = icone("oculos") {
        moveTo(4f, 10f); horizontalLineTo(9f)
        arcTo(1.5f, 1.5f, 0f, false, true, 10.5f, 11.5f)
        verticalLineTo(14.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 9f, 16f)
        horizontalLineTo(4f)
        arcTo(1.5f, 1.5f, 0f, false, true, 2.5f, 14.5f)
        verticalLineTo(11.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4f, 10f)
        close()
        moveTo(15f, 10f); horizontalLineTo(20f)
        arcTo(1.5f, 1.5f, 0f, false, true, 21.5f, 11.5f)
        verticalLineTo(14.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20f, 16f)
        horizontalLineTo(15f)
        arcTo(1.5f, 1.5f, 0f, false, true, 13.5f, 14.5f)
        verticalLineTo(11.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 15f, 10f)
        close()
        moveTo(10.5f, 12f)
        arcTo(2f, 2f, 0f, false, true, 13.5f, 12f)
    }

    // ── Ações ─────────────────────────────────────────────────────────────────

    /** **Entrar.** Seta atravessando o batente — é o turno que começa, não um "ok". */
    val Entrar: ImageVector = icone("entrar") {
        moveTo(14f, 3.5f); horizontalLineTo(20.5f); verticalLineTo(20.5f)
        horizontalLineTo(14f)
        moveTo(3.5f, 12f); horizontalLineTo(14f)
        moveTo(9.5f, 7.5f); lineTo(14f, 12f); lineTo(9.5f, 16.5f)
    }

    /** **Permitir.** Cadeado de arco aberto. Ver a nota do objeto. */
    val Destravar: ImageVector = icone("destravar") {
        moveTo(4.5f, 11f); horizontalLineTo(19.5f); verticalLineTo(21f)
        horizontalLineTo(4.5f); close()
        moveTo(8f, 11f); verticalLineTo(7.2f)
        arcTo(4f, 4f, 0f, false, true, 15.6f, 6f)
    }

    /** **Ajustes.** Dois cursores — a página de ajustes do Android, não uma engrenagem. */
    val Ajustes: ImageVector = icone("ajustes") {
        moveTo(3.5f, 8f); horizontalLineTo(20.5f)
        moveTo(3.5f, 16f); horizontalLineTo(20.5f)
        moveTo(6.6f, 8f)
        arcTo(2.4f, 2.4f, 0f, false, true, 11.4f, 8f)
        arcTo(2.4f, 2.4f, 0f, false, true, 6.6f, 8f)
        close()
        moveTo(12.6f, 16f)
        arcTo(2.4f, 2.4f, 0f, false, true, 17.4f, 16f)
        arcTo(2.4f, 2.4f, 0f, false, true, 12.6f, 16f)
        close()
    }

    /** **Adiante.** Seguir sem resolver o que ficou para trás. */
    val Adiante: ImageVector = icone("adiante") {
        moveTo(3.5f, 12f); horizontalLineTo(19.5f)
        moveTo(13.5f, 6f); lineTo(19.5f, 12f); lineTo(13.5f, 18f)
    }

    /** **Sai do aplicativo.** Reservado a isso. Ver a nota do objeto. */
    val Externo: ImageVector = icone("externo") {
        moveTo(18.5f, 13.5f); verticalLineTo(20.5f); horizontalLineTo(3.5f)
        verticalLineTo(6f); horizontalLineTo(10.5f)
        moveTo(14f, 3.5f); horizontalLineTo(20.5f); verticalLineTo(10f)
        moveTo(11f, 13f); lineTo(20.5f, 3.5f)
    }

    /**
     * **Recarregar** — a seta circular do cabeçalho de frescor.
     *
     * Arco de ~270°, aberto no canto superior direito, com a ponta da seta fechando
     * o vão. O arco NÃO fecha de propósito: círculo completo lê como indicador de
     * carga, e este ícone é um **botão**, não um estado. A regra 1 de `Movimento`
     * vale para a forma também — nada aqui pode sugerir atividade que não existe.
     *
     * `arcTo` com `isMoreThanHalf = true` e `isPositiveArc = true`: assinatura
     * conferida em `ui-graphics-android 1.6.8`
     * (`PathBuilder.arcTo(float,float,float,boolean,boolean,float,float)`), a mesma
     * já usada pela fábrica abaixo.
     */
    val Recarregar: ImageVector = icone("recarregar") {
        moveTo(20.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, true, true, 17.9f, 5.9f)
        moveTo(20.5f, 4f); verticalLineTo(10f); horizontalLineTo(14.5f)
    }

    /**
     * A fábrica. **É o único lugar que decide traço, ponta e junta**, e é por isso
     * que os quatorze pertencem ao mesmo conjunto: nenhum ícone recebe esses três
     * como parâmetro, então nenhum pode divergir por digitação.
     *
     * `stroke` sai branco e `fill` sai nulo. O branco é irrelevante na prática — o
     * `tint` do local de uso o substitui inteiro —, e existe só para que um ícone
     * desenhado sem `tint` apareça em vez de sumir.
     *
     * Assinatura conferida por `javap` em `ui-android 1.6.8`
     * (`ImageVectorKt.path-R_LF-3I`) e `ui-graphics-android 1.6.8`
     * (`PathBuilder.arcTo(float,float,float,boolean,boolean,float,float)`).
     */
    private fun icone(nome: String, caminho: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = nome,
            defaultWidth = QUADRO,
            defaultHeight = QUADRO,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = TRACO,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            pathBuilder = caminho,
        ).build()
}
