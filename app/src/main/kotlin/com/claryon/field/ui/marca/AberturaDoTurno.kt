package com.claryon.field.ui.marca

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Movimento
import com.claryon.field.ui.tema.Espaco
import com.claryon.field.ui.tema.Tipo
import kotlin.math.absoluteValue
import kotlin.math.sin

/**
 * **A abertura do turno.**
 *
 * ## O que ela é, e o que ela deliberadamente não é
 *
 * É uma **assinatura de marca com duração própria** — dois segundos em que a
 * forma se constrói: primeiro a fonte, depois as três ondas se afastando dela.
 * Nada aqui afirma progresso. Não há barra, não há percentual, não há disco
 * girando. A distinção é do produto e não de gosto: uma abertura que se apresenta
 * é honesta; um indicador que enche sem nada enchendo é o aplicativo mentindo
 * para um agente que pode estar indo para uma ocorrência.
 *
 * A marca é o veículo certo porque **ela já é movimento**: as ondas emanam de uma
 * fonte, que é literalmente o que o produto faz — escuta e responde por som. Não
 * há nada a inventar; há a forma a executar.
 *
 * ## A única coisa que acompanha carga de verdade
 *
 * Existe **um** portão real, e ele já existia antes desta tela: enquanto
 * [com.claryon.field.auth.SessaoDoAgente] lê o cofre cifrado (Keystore, ~468 ms
 * medidos no primeiro acesso), o `MainActivity` não tinha o que compor — a
 * condição `Verificando -> Unit` desenhava **preto**. Essa espera existia e não
 * tinha rosto. Agora tem.
 *
 * Os dois segundos cobrem essa leitura com folga. Se por alguma razão ela passar
 * dos dois — Keystore frio, aparelho sob pressão —, a abertura **não corta para
 * um vazio**: a marca continua respirando e uma linha aparece dizendo o que
 * está sendo esperado. Aí sim a animação acompanha espera real, e por isso pode
 * continuar.
 *
 * O que **não** segura esta tela: o aquecimento do índice de norma (1157 ms) e a
 * decisão de degradação do redator, ambos disparados em `ClaryonApp.onCreate`.
 * Os dois rodam fora da Main, nenhum é pré-requisito para entrar no turno ou para
 * apertar o PTT, e prender o agente atrás deles seria roubar mais de um segundo
 * dele para adiantar uma capacidade que ele talvez não use neste turno. Quem
 * depende do índice é a primeira pergunta ao copiloto, e é lá — dentro do aceite
 * de 4 s — que o aquecimento já foi pago.
 *
 * ## O toque que pula
 *
 * Um toque em qualquer lugar salta o resto da cerimônia. Custa uma linha e
 * respeita o caso que define este produto: o agente que abre o aplicativo
 * **correndo**. O que o toque não faz — não pode fazer — é pular o cofre: se a
 * sessão ainda não resolveu, o toque leva direto à linha que diz isso, em vez de
 * levar a uma tela preta.
 *
 * E se o aparelho tem animação desligada nas opções de acessibilidade
 * (`ANIMATOR_DURATION_SCALE == 0`), a abertura não acontece: quem pediu ao
 * sistema para não animar não deveria precisar pedir de novo a cada aplicativo.
 */
@Composable
fun AberturaDoTurno(
    sessaoResolvida: Boolean,
    aoConcluir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contexto = LocalContext.current
    // Lido uma vez: é E/S de `Settings.Global` e não muda no meio de uma abertura.
    val semAnimacao = remember {
        Settings.Global.getFloat(
            contexto.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) < 0.01f
    }

    var pulada by remember { mutableStateOf(false) }
    val relogio = remember { Animatable(0f) }
    val saida = remember { Animatable(1f) }
    val concluir by rememberUpdatedState(aoConcluir)
    // Relógio de parede, separado do relógio da cena: a cena para em 2000 ms, e a
    // pergunta que interessa é quanto tempo a abertura REALMENTE ficou na frente
    // do agente. Sem este número, uma espera de cofre de três segundos ficaria
    // indistinguível de uma abertura normal — que é o defeito que este projeto
    // corrige com log desde o gazetteer: o número, nunca um "ok".
    val nasceuEm = remember { SystemClock.elapsedRealtime() }

    // O instante em que o cofre respondeu. Medido, e não deduzido da diferença
    // entre o total e a assinatura: a primeira versão fazia isso e chamava de
    // "espera pelo cofre" os 196 ms que eram, na verdade, agendamento de quadro
    // da própria fusão de saída. Número com nome errado é pior que número
    // nenhum — ele responde a pergunta errada com autoridade.
    var cofreEm by remember { mutableStateOf(0L) }
    LaunchedEffect(sessaoResolvida) {
        if (sessaoResolvida && cofreEm == 0L) cofreEm = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(semAnimacao) {
        if (semAnimacao) return@LaunchedEffect
        relogio.animateTo(
            targetValue = ASSINATURA_MS,
            // Linear, e é a única coisa aqui que é linear: este é o relógio da
            // cena, não um movimento. Cada elemento aplica a própria curva sobre
            // ele — do contrário todos herdariam a mesma aceleração e a cena
            // inteira pulsaria junto.
            animationSpec = tween(ASSINATURA_MS.toInt(), easing = LinearEasing),
        )
    }

    // Com animação desligada no sistema, a cena nasce no fim: a marca aparece
    // pronta, parada, e a abertura vira uma tela de identidade em vez de
    // desaparecer. Quem pediu ao sistema para não animar pediu isso — não pediu
    // para ficar sem rosto enquanto o cofre é lido.
    val t = if (semAnimacao) ASSINATURA_MS else relogio.value
    // Pular **não adianta o relógio**. A primeira versão fazia isso e a marca
    // saltava para a forma final antes de sumir — o salto é mais chamativo que a
    // animação que ele veio encurtar. Pular apenas antecipa a saída; a cena
    // continua correndo por trás dela durante os 180 ms de fusão.
    val cerimoniaCumprida = t >= INICIO_DA_SAIDA || pulada
    val esperandoOCofre = cerimoniaCumprida && !sessaoResolvida

    // **O cofre tem precedência sobre o relógio, e não o contrário.** Se a sessão
    // ainda não resolveu, a saída não começa: a tela seguinte não teria o que
    // compor, e o agente veria preto — que é exatamente o defeito que esta tela
    // veio cobrir.
    val prontaParaSair = cerimoniaCumprida && sessaoResolvida
    LaunchedEffect(prontaParaSair, semAnimacao) {
        if (!prontaParaSair) return@LaunchedEffect
        if (semAnimacao) {
            // Sem fusão de saída: fusão É animação, e o pedido do sistema vale
            // para a saída também. Mas o cofre continua tendo precedência — a
            // primeira versão concluía na hora e devolvia o agente ao preto que
            // esta tela veio cobrir.
            Log.i(TAG, "abertura: sem animação (pedido do sistema)")
            concluir()
            return@LaunchedEffect
        }
        saida.animateTo(0f, tween(SAIDA_DURACAO.toInt(), easing = SAIDA_FORTE))
        val total = SystemClock.elapsedRealtime() - nasceuEm
        val cofre = cofreEm - nasceuEm
        // Quanto o cofre SEGUROU a saída: o que ele custou além do ponto em que a
        // cerimônia já teria terminado. Zero significa que a abertura não roubou
        // nada do agente — a assinatura cobriu a leitura inteira.
        val segurou = (cofre - INICIO_DA_SAIDA).toLong().coerceAtLeast(0L)
        Log.i(
            TAG,
            "abertura: $total ms na tela · cofre respondeu em $cofre ms · " +
                "segurou a saída por $segurou ms" +
                if (pulada) " · pulada pelo agente" else "",
        )
        concluir()
    }

    // ── Curvas ───────────────────────────────────────────────────────────────
    //
    // `ease-out-quart` em tudo que entra: aceleração inicial forte e pouso
    // calmo. É a curva de uma frente de onda perdendo energia — a mesma coisa que
    // a marca desenha —, e é o padrão do sistema de movimento para entrada.

    val alfaDaPupila = SAIDA_FORTE.transform(fracao(t, PUPILA_EM, PUPILA_DURACAO))
    val escala = 0.94f + 0.06f * SAIDA_FORTE.transform(fracao(t, PUPILA_EM, ESCALA_DURACAO))

    // A fase da respiração corre sempre; quem a **usa** é só o ramo de espera
    // real, logo abaixo. Fora dele a marca fica parada, e parada é o estado normal
    // de um instrumento pronto — respirar sem estar esperando seria o movimento
    // decorativo que esta tela existe para não ter.
    val respiracao = rememberInfiniteTransition(label = "respiracao")
    val fase by respiracao.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RESPIRO_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fase-da-respiracao",
    )

    val presencas = GeometriaDaMarca.ONDAS.indices.map { i ->
        val entrada = SAIDA_FORTE.transform(
            fracao(t, ONDA_EM + i * ONDA_ATRASO, ONDA_DURACAO),
        )
        if (!esperandoOCofre || semAnimacao) {
            entrada
        } else {
            // Amplitude pequena de propósito (6%): é respiração, não pulsação. A
            // pulsação neste sistema tem dono — `PontoDeEstado(pulsando = true)`
            // significa "recebendo agora", e duas coisas com o mesmo movimento
            // deixam de significar qualquer uma.
            val onda = sin(((fase - i * 0.16f) * 2f * Math.PI).toFloat())
            entrada * (0.94f + 0.06f * onda.absoluteValue)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Cores.Vazio)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { pulada = true }
            .alpha(saida.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth(0.52f)
                    .height(160.dp)
                    .graphicsLayer { scaleX = escala; scaleY = escala },
            ) {
                desenharMarca(
                    cor = Cores.Tinta,
                    presencaDasOndas = presencas,
                    alfaDaPupila = alfaDaPupila,
                )
            }

            Box(Modifier.height(Espaco.Secao))

            // O nome entra com a entreletra **fechando**. É o gesto tipográfico do
            // sistema (caixa-alta com entreletra larga) executado em vez de
            // exibido: as letras chegam espalhadas, como as ondas, e assentam.
            val revelacaoDoNome = SAIDA_FORTE.transform(fracao(t, NOME_EM, NOME_DURACAO))
            Text(
                "CLARYON",
                style = Tipo.Titulo.copy(
                    letterSpacing = (0.52f - 0.36f * revelacaoDoNome).em,
                ),
                color = Cores.Tinta,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(revelacaoDoNome),
            )

            Box(Modifier.height(Espaco.Curto))

            val revelacaoDaLinha = SAIDA_FORTE.transform(fracao(t, LINHA_EM, LINHA_DURACAO))
            Text(
                "COPILOTO DE CAMPO",
                style = Tipo.Etiqueta,
                color = Cores.TintaFraca,
                modifier = Modifier.alpha(revelacaoDaLinha),
            )
        }

        // A linha honesta. Só aparece quando a espera é real E já passou da
        // carência — uma sessão que resolve em 2,1 s não pode acender um aviso
        // que some antes de ser lido.
        val esperaVisivel = esperandoOCofre && t >= INICIO_DA_SAIDA + CARENCIA_MS
        if (esperaVisivel) {
            Text(
                "LENDO O COFRE DA SESSÃO",
                style = Tipo.Etiqueta,
                color = Cores.TintaFraca,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Espaco.Secao),
            )
        }
    }
}

/**
 * Fração decorrida de um trecho da cena, saturada em `[0,1]`.
 *
 * A cena inteira é escrita em milissegundos absolutos, e não em animações
 * encadeadas por callback. É o que torna o "pular" trivial — pular é mover o
 * relógio — e o que garante que a soma dos trechos seja exatamente a duração
 * declarada, em vez de "mais ou menos dois segundos".
 */
private fun fracao(agora: Float, comeco: Float, duracao: Float): Float =
    ((agora - comeco) / duracao).coerceIn(0f, 1f)

// ── O roteiro, em milissegundos ──────────────────────────────────────────────

/** Duração da assinatura. Decisão humana registrada em 21/08. */
private const val ASSINATURA_MS = 2000f

private const val PUPILA_EM = 0f
private const val PUPILA_DURACAO = 380f
private const val ESCALA_DURACAO = 520f

/**
 * As ondas começam **depois** da fonte e uma atrás da outra. O atraso de 180 ms é
 * o que faz a leitura ser "emanou daí" em vez de "apareceram juntas": três coisas
 * que surgem no mesmo quadro não têm causa entre si.
 */
private const val ONDA_EM = 240f
private const val ONDA_ATRASO = 180f
private const val ONDA_DURACAO = 560f

private const val NOME_EM = 1160f
private const val NOME_DURACAO = 420f
private const val LINHA_EM = 1420f
private const val LINHA_DURACAO = 320f

/** A saída é curta: quem sai, sai rápido. 180 ms é o teto de saída do sistema. */
private const val SAIDA_DURACAO = 180f
private const val INICIO_DA_SAIDA = ASSINATURA_MS - SAIDA_DURACAO

/** Carência antes de acusar espera. Ver o uso. */
private const val CARENCIA_MS = 400f

/** Um ciclo de respiração. Lento de propósito: 1,6 s é ritmo de repouso. */
private const val RESPIRO_MS = 1600

private const val TAG = "ClaryonField"

/**
 * `ease-out-quart` — `cubic-bezier(.165, .84, .44, 1)`.
 *
 * Curva de entrada do sistema de movimento: arranca forte e pousa devagar.
 */
// **A curva da saída vem do sistema, não daqui.**
//
// Esta linha era `CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)` — e o mesmo quarteto,
// bit a bit, apareceu em TRÊS lugares hoje, escritos por três agentes que não se
// falaram: aqui, na conversa da guarnição, e em `ui/tema/Movimento.kt`.
//
// A convergência diz algo bom sobre a escolha. Três cópias continuam sendo três
// cópias, e é no primeiro ajuste que elas divergem. As DURAÇÕES abaixo são da
// abertura e ficam aqui; a curva é vocabulário do sistema.
private val SAIDA_FORTE: Easing = Movimento.Saida
