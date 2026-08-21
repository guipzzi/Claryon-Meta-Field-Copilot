package com.claryon.field.ui.tema

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * **Vocabulário de movimento do Claryon Field.**
 *
 * Uma regra acima de todas as outras, e ela é do produto, não do desenho:
 *
 * > **Animação que sugere atividade sem atividade é mentira visual.**
 *
 * Um giro que roda enquanto nada acontece é a versão animada do rótulo "na fila"
 * que este projeto removeu por não haver fila (`FalaNoGrupo.Entrega`). A mentira é
 * a mesma e a consequência é pior: o agente acredita no movimento sem ler,
 * justamente porque movimento é o que a visão periférica capta melhor.
 *
 * Disso saem três regras operacionais:
 *
 *  1. **Nada de movimento livre para representar trabalho.** Se há progresso
 *     verdadeiro, o movimento é função dele ([faseDoPulso]). Se não há número de
 *     progresso, não há animação — há um rótulo dizendo o que está acontecendo.
 *  2. **Nada de movimento otimista.** Estado que depende de resposta do servidor
 *     não anima na hora do toque. Ver [PisoConcedido] e a ausência deliberada de
 *     um "piso pendente".
 *  3. **Movimento só onde carrega informação.** Não há transição de tela, não há
 *     entrada escalonada de lista, não há brilho.
 *
 * ---
 * ### Os três momentos que o `ROADMAP` nomeia
 *
 * | momento | por que se move | token |
 * |---|---|---|
 * | o pulso do "no ar" | o agente precisa saber, **sem olhar**, que está transmitindo | [PulsoNoAr] + [faseDoPulso] |
 * | piso concedido/negado | é a diferença entre falar e falar no vazio | [PisoConcedido] / [PisoNegado] |
 * | esmaecimento por idade | posição velha tem de **parecer** velha | [DecaimentoPorIdade] |
 *
 * Fora desses três, o padrão é `IMEDIATO`.
 */
object Movimento {

    // ── Durações, em milissegundos ───────────────────────────────────────────

    /** Sem animação. **É o padrão do sistema**, não a exceção. */
    const val IMEDIATO = 0

    /** Retorno de toque, troca de cor, fio que cresce. */
    const val MICRO = 120

    /** Aparecer e sumir de superfície pequena. */
    const val CURTO = 180

    /** Remanejo de layout, gaveta, expansão. */
    const val MEDIO = 240

    /** Teto para interface de produto. Acima disto, precisa de justificativa escrita. */
    const val LONGO = 300

    /**
     * **Decaimento.** 600 ms — o dobro do teto, de propósito.
     *
     * Um esmaecimento rápido lê como *"algo aconteceu"*; um esmaecimento lento lê
     * como *"tempo passou"*. São leituras opostas, e aqui a segunda é a
     * verdadeira: nada aconteceu com o par, ele só ficou velho. A duração é o
     * que carrega esse significado, então ela não é excesso — é o conteúdo.
     */
    const val DECAIMENTO = 600

    /** Período de uma batida do "no ar" em condição nominal. Ver [faseDoPulso]. */
    const val BATIDA_NO_AR = 1_000

    // ── Curvas ───────────────────────────────────────────────────────────────

    /**
     * Saída — `ease-out-quart`. Aceleração inicial rápida, pouso calmo.
     *
     * Para o que entra ou sai da tela. É a curva que o cérebro lê como resposta
     * imediata, porque o grosso do deslocamento acontece nos primeiros 30%.
     */
    val Saida: Easing = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)

    /**
     * Morfose — `ease-in-out-cubic`. Acelera e desacelera.
     *
     * Para o que já está na tela e muda de posição ou tamanho. Lê como movimento
     * físico, e é o certo para gaveta, expansão e remanejo.
     */
    val Morfose: Easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

    /**
     * Constante. Para o que representa **tempo ou progresso**.
     *
     * Tempo é linear; qualquer curva faz o progresso parecer irregular e, num
     * instrumento, irregular parece defeito.
     */
    val Constante: Easing = LinearEasing

    /** Sutil — o `ease` do CSS. Só para mudança de cor de estado. */
    val Sutil: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    // ── Os três momentos, como specs prontos ─────────────────────────────────

    /**
     * **O pulso do "no ar".**
     *
     * Batida, não respiração: [Constante], porque o que ele representa é
     * passagem de tempo de transmissão — o mesmo dado que o cronômetro mostra em
     * número. O período de 1 s lê como pulso em repouso: vivo, sem alarme.
     *
     * **Este spec sozinho é uma mentira.** Ele roda pelo relógio da tela, e o
     * relógio da tela continua andando com o rádio mudo. Use-o **apenas** com a
     * fase vinda de [faseDoPulso], que é contada em quadros que de fato saíram.
     */
    fun <T> PulsoNoAr(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = tween(durationMillis = BATIDA_NO_AR, easing = Constante),
        repeatMode = RepeatMode.Reverse,
    )

    /**
     * **Piso concedido.** O sinal de "pode falar".
     *
     * Curto e seco: é uma autorização, e autorização que chega devagar vira
     * dúvida. [MICRO] com [Saida] põe o grosso da mudança nos primeiros 40 ms.
     *
     * **Só componha isto depois da resposta do servidor.** O piso é resolvido em
     * `floor_grants`, não no aparelho. Animar no toque diria "você tem o canal"
     * antes de alguém ter dito — e este projeto já publicou 168 quadros para um
     * canal em que não havia entrado, com o indicador aceso o tempo todo.
     * Por isso **não existe** um `PisoPendente` neste arquivo: entre o toque e a
     * resposta, o retorno é a barra de pressão, que informa sobre o **dedo**, que
     * é a única coisa que o aparelho sabe com certeza naquele instante.
     */
    fun <T> PisoConcedido(): FiniteAnimationSpec<T> =
        tween(durationMillis = MICRO, easing = Saida)

    /**
     * **Piso negado.** Mais rápido que o concedido, e em outra propriedade.
     *
     * Deliberadamente **não** é o concedido em câmera lenta. Se negar fosse
     * conceder devagar, o intervalo entre os dois seria ambíguo — e é exatamente
     * nesse intervalo que o agente decide começar a falar. Negar recolhe; a
     * recusa termina antes de a mão reagir.
     */
    fun <T> PisoNegado(): FiniteAnimationSpec<T> =
        tween(durationMillis = 90, easing = Saida)

    /**
     * **Esmaecimento do marcador por idade.**
     *
     * [Constante], porque envelhecer é tempo, e [DECAIMENTO] porque o lento é o
     * significado. Ver a nota em [DECAIMENTO].
     */
    fun <T> DecaimentoPorIdade(): FiniteAnimationSpec<T> =
        tween(durationMillis = DECAIMENTO, easing = Constante)

    // ── A honestidade do pulso, como função pura ─────────────────────────────

    /**
     * **A fase do pulso do "no ar", contada em quadros que saíram.**
     *
     * Devolve uma onda triangular de 0 a 1 derivada de [quadrosNoAr]. O pulso
     * **é o contador de quadros tornado visível**: a 50 quadros/s medidos no
     * aparelho, 50 quadros fecham uma batida de 1 s.
     *
     * A propriedade que importa é a que ele tem **quando dá errado**: se o rádio
     * parar de emitir, [quadrosNoAr] para de subir e o pulso congela. O âmbar
     * continua aceso — o canal segue aberto e o dedo segue no botão —, mas o
     * movimento morre, e a visão periférica capta a parada sem que o agente
     * precise olhar um número. Um `infiniteRepeatable` no lugar disto continuaria
     * pulsando alegremente sobre um rádio mudo.
     *
     * Função pura de propósito: dá para testar a parada sem tela, sem rádio e sem
     * relógio.
     *
     * @param quadrosNoAr quadros de 20 ms confirmados no ar nesta transmissão.
     * @param quadrosPorBatida quadros que fecham um ciclo. 50 = 1 s a 50 quadros/s.
     */
    fun faseDoPulso(quadrosNoAr: Long, quadrosPorBatida: Int = 50): Float {
        require(quadrosPorBatida > 0) { "quadrosPorBatida tem de ser positivo" }
        if (quadrosNoAr <= 0L) return 0f
        val meio = quadrosPorBatida / 2f
        val dentroDoCiclo = (quadrosNoAr % quadrosPorBatida).toFloat()
        // Triângulo: sobe até o meio do ciclo, desce até o fim. Sem descontinuidade
        // na virada, senão a batida "estala" a cada volta.
        return 1f - abs(dentroDoCiclo - meio) / meio
    }
}

/**
 * Duração ajustada à preferência de acessibilidade do aparelho.
 *
 * Quem desligou animações nos ajustes do Android — por enjoo de movimento, por
 * bateria ou por preferência — recebe `0`, e todo `tween` vira salto. Nenhuma das
 * informações deste sistema depende de movimento para existir: o "no ar" tem cor,
 * moldura e cronômetro; o piso tem rótulo; a idade tem carimbo em texto. O
 * movimento **reforça**, e reforço é exatamente o que pode ser desligado.
 *
 * Lê `Settings.Global.ANIMATOR_DURATION_SCALE` (verificado por `javap` no
 * `android.jar` do compileSdk 35, junto com a sobrecarga de `getFloat` com valor
 * padrão — a que não lança `SettingNotFoundException`).
 */
@Composable
fun duracaoAcessivel(duracaoMs: Int): Int {
    val contexto = LocalContext.current
    val escala = remember(contexto) {
        Settings.Global.getFloat(
            contexto.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    return (duracaoMs * escala).toInt()
}
