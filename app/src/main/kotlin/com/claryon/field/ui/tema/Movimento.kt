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
 *     verdadeiro, o movimento é função dele. Se não há número de progresso, não há
 *     animação — há um rótulo dizendo o que está acontecendo.
 *  2. **Nada de movimento otimista.** Estado que depende de resposta do servidor
 *     não anima na hora do toque. Ver [PisoConcedido] e a ausência deliberada de
 *     um "piso pendente".
 *  3. **Movimento só onde carrega informação.** Não há entrada escalonada de
 *     lista e não há brilho.
 *
 * ---
 * ### Os quatro momentos, e quem os desenha
 *
 * | momento | por que se move | token | chamador em `src/main` |
 * |---|---|---|---|
 * | piso concedido | é a diferença entre falar e falar no vazio | [PisoConcedido] | `Casco.kt`, `BarraDePtt.kt` |
 * | piso negado / devolvido | o âmbar tem de **sair** antes de a mão reagir | [PisoNegado] | `Casco.kt`, `BarraDePtt.kt` |
 * | esmaecimento por idade | posição velha tem de **parecer** velha | [DecaimentoPorIdade] | `TelaDoMapa.kt` |
 * | retorno de toque | o dedo desceu, e o aparelho sabe disso com certeza | [MICRO] + [Sutil] | `Comuns.kt` (`Modifier.tocavel`) |
 *
 * Fora desses quatro e da passagem entre páginas, o padrão é [IMEDIATO].
 *
 * ---
 * ### 22/08 — o pulso do "no ar" saiu daqui, e a razão é a regra
 *
 * Este arquivo definia `PulsoNoAr()`, `BATIDA_NO_AR` e `faseDoPulso()` para a
 * batida do âmbar. Auditados em 22/08: **zero chamadores em `src/main`**, os três.
 * Escritos, nunca construídos — o oitavo caso do padrão que o `CLAUDE.md` §6 nomeia.
 *
 * Não foram ligados, e sim removidos, porque **não existe produtor honesto para
 * eles neste aparelho**. `faseDoPulso` pedia `quadrosNoAr` — quadros de 20 ms
 * confirmados no ar —, e a única grandeza que chega à barra é
 * `EstadoDoPtt.NoAr.decorridoMs`, que `RadioViewModel` calcula por
 * `System.currentTimeMillis()`. Alimentar a batida com o relógio da tela é
 * exatamente a mentira que o KDoc de `PulsoNoAr` já denunciava sobre si mesmo: o
 * relógio continua andando com o rádio mudo, e o agente leria "estou transmitindo"
 * de um canal que morreu.
 *
 * **O que os traz de volta**, e é uma coisa só: um contador de quadros publicado
 * por `RadioTatico` e carregado em `EstadoDoPtt.NoAr`. Enquanto ele não existir, a
 * ausência de batida é a informação correta — o "no ar" já tem cor, moldura de tela
 * cheia, cronômetro e forma de onda com amplitude real, que são quatro canais
 * verdadeiros.
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

    /**
     * Teto para interface de produto. Acima disto, precisa de justificativa escrita.
     *
     * **Zero chamadores, e de propósito — este é um LIMITE, não uma capacidade.**
     * Auditado em 22/08 junto com os símbolos que foram removidos por não terem
     * chamador, e mantido porque a pergunta 2 do §6 separa as duas coisas: função
     * pública sem chamador dá a impressão de uma capacidade que não existe; uma
     * régua sem chamador continua sendo a régua. Ele é o número contra o qual
     * [RespiroDePresenca] e [DECAIMENTO] se justificam por escrito, e o dia em que
     * uma animação o alcançar é o dia em que ela precisa de justificativa.
     *
     * Se um dia alguém o "ligar" só para tirá-lo desta lista, o teto vira duração
     * comum e perde a função.
     */
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

    // ── Os momentos, como specs prontos ──────────────────────────────────────

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
     * **Piso negado — e piso devolvido.** Mais rápido que o concedido, e em outra
     * propriedade.
     *
     * Deliberadamente **não** é o concedido em câmera lenta. Se negar fosse
     * conceder devagar, o intervalo entre os dois seria ambíguo — e é exatamente
     * nesse intervalo que o agente decide começar a falar. Negar recolhe; a
     * recusa termina antes de a mão reagir.
     *
     * ---
     * ### Por que ele também desenha a SAÍDA do ar, e não só a recusa
     *
     * Ligado em 22/08 nos dois sentidos do sinal âmbar: [PisoConcedido] quando ele
     * acende, este quando ele apaga. Soltar o botão não é "recusa", e mesmo assim
     * o recolhimento é a leitura certa — pelo motivo mais forte que existe nesta
     * interface.
     *
     * Âmbar que sai devagar diz *"talvez você ainda esteja no ar"*, e essa é a
     * única ambiguidade que o produto não pode ter: uma transmissão acidental
     * difunde a fala do agente **e de quem está ao lado dele** para a guarnição
     * inteira. O sinal que anuncia isso precisa aparecer com convicção e sumir com
     * pressa. Os dois casos — o canal foi negado e o canal deixou de ser seu —
     * pedem a mesma frase visual, porque significam a mesma coisa para o dedo:
     * **não fale.**
     */
    fun <T> PisoNegado(): FiniteAnimationSpec<T> =
        tween(durationMillis = 90, easing = Saida)

    /**
     * **Esmaecimento do marcador por idade.**
     *
     * [Constante], porque envelhecer é tempo, e [DECAIMENTO] porque o lento é o
     * significado. Ver a nota em [DECAIMENTO].
     *
     * Ligado em `TelaDoMapa.kt`, no `alpha` da linha de par: é o token do
     * `Frescor` — `ATUAL` a 1, `ESMAECIDO` a 0,45, `ANTIGO` a 0,28. A tela já
     * fazia esse esmaecimento com um `tween(600)` digitado à mão e sem curva; os
     * 600 ms coincidiam com [DECAIMENTO] por acaso, que é como dois números iguais
     * divergem no primeiro ajuste.
     */
    fun <T> DecaimentoPorIdade(): FiniteAnimationSpec<T> =
        tween(durationMillis = DECAIMENTO, easing = Constante)

    /**
     * **Respiro de presença** — o único movimento perpétuo que este sistema tem.
     *
     * Vive em `PontoDeEstado(pulsando = true)`, e em nenhum outro lugar: marca a
     * linha de **quem está no ar agora**, na tela da guarnição e na do mapa.
     *
     * ---
     * ### Por que ele sobrevive à regra que matou o pulso do "no ar"
     *
     * A regra 1 proíbe movimento livre para representar trabalho, e foi ela que
     * removeu `PulsoNoAr()` em 22/08. A distinção não é de desenho, é de **o que
     * cada um afirma**:
     *
     *  - o pulso removido afirmava *"quadros estão saindo agora"* — uma afirmação
     *    sobre a saída do rádio, que o relógio da tela não sustenta e que
     *    continuaria animando alegremente sobre um canal morto;
     *  - este afirma *"esta é a linha viva"*, e quem sustenta a afirmação **não é
     *    o movimento — é a existência do ponto**. `quemFala` vira `null` no fim da
     *    transmissão e o ponto some inteiro. Não há estado em que ele respire sobre
     *    algo falso, porque não há estado em que ele esteja na tela sobre algo falso.
     *
     * ### Os 900 ms
     *
     * Herdados do `tween(900)` que estava digitado dentro de `PontoDeEstado`, e
     * mantidos: mais lento que [LONGO] o bastante para ler como respiração em vez
     * de alarme, e a auditoria não tinha motivo medido para mexer num número que já
     * estava na tela. O que mudou foi o endereço — número de movimento mora no
     * vocabulário de movimento, senão o próximo ajuste acha metade dos lugares.
     *
     * [Sutil] e não [Constante]: respiração acelera e desacelera; o linear daria a
     * serra de um indicador de carga, que é a leitura errada.
     */
    fun <T> RespiroDePresenca(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = tween(durationMillis = RESPIRO_DE_PRESENCA, easing = Sutil),
        repeatMode = RepeatMode.Reverse,
    )

    /** Meia respiração do ponto de presença. Ver [RespiroDePresenca]. */
    const val RESPIRO_DE_PRESENCA = 900

    /**
     * **Passagem entre páginas** — a pilha local da tela da guarnição.
     *
     * Este arquivo dizia *"não há transição de tela"*. A regra caiu em 22/08, por
     * decisão do dono do produto, e cai **por escrito** porque tinha precedência.
     *
     * O argumento que a derruba não é estético: as três páginas da guarnição
     * (conversa, grupo, guarnições) são uma **pilha**, não um seletor. Sem
     * movimento, abrir o grupo e fechá-lo produzem o mesmo quadro — a tela nova
     * simplesmente substitui a velha —, e o agente perde a única pista de qual
     * gesto o traz de volta. A direção É a informação: o que entra pela direita
     * sai pela direita, e o polegar aprende o caminho de volta sem ler.
     *
     * A regra 3 continua valendo onde ela nasceu, e não foi afrouxada: **a barra
     * inferior de destinos não anima.** Aquilo é seletor de nível um — guarnição,
     * mapa e perfil não estão empilhados, nenhum "volta" para outro, e uma
     * transição ali inventaria uma hierarquia que não existe.
     *
     * [MEDIO] com [Morfose]: é remanejo de superfície que já está na tela, que é
     * exatamente o que [Morfose] existe para descrever.
     */
    fun <T> PassagemDePagina(): FiniteAnimationSpec<T> =
        tween(durationMillis = MEDIO, easing = Morfose)
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
