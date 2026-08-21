package com.claryon.field.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.PalavraDeAtivacaoNaFala
import com.claryon.common.Result
import com.claryon.field.voice.EscutaDoAgente
import com.claryon.voice.DetectorDeAtivacao
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20 ms a 16 kHz: a granularidade com que o rádio entrega quadro, e a mesma que a
 * `EscutaDeAtivacao` passa ao detector em produção. Medir com bloco diferente mediria
 * outro detector.
 */
private const val BLOCO = 320

/** 1,0 s — a janela do detector, e o pré-roll que a emenda entre fatias precisa ter. */
private const val UM_SEGUNDO = 16_000

/**
 * **O falso positivo, com horas de fala espontânea de verdade.**
 *
 * Esta é a tarefa mais antiga em aberto do projeto. O `ESTADO.md` a carrega assim:
 * *"`0` em 1,8 min retidos dá limite superior de ~99/h com 95%; a meta é 0,5/h, e
 * isso exige da ordem de 6 h"*. E os 1,8 min eram de leitura em voz alta de um
 * texto, por um locutor só.
 *
 * ## Por que silêncio e leitura não valiam
 *
 * O `ESTADO` já dizia: *"os 60 s de silêncio do emulador não contam — silêncio é o
 * negativo mais fácil que existe"*. Leitura contínua é melhor, e ainda é fácil: um
 * locutor, dicção cuidada, sem sobreposição, sem risada, sem ruído de fundo.
 *
 * Fala espontânea de podcast tem o que falta: múltiplos locutores, sobreposição,
 * interjeição, hesitação, música de abertura, variação de nível. É o negativo que
 * se parece com o mundo.
 *
 * ## O que este teste NÃO faz
 *
 * Não transcreve. O whisper sobre 54 min levaria horas e a pergunta não é sobre
 * transcrição: é sobre o **estágio 1**, o detector acústico que roda o turno
 * inteiro. Ele é quem dispara o earcon, e é o falso positivo DELE que o aceite
 * limita em 0,5/h.
 *
 * ## O arranjo é FLUXO CONTÍNUO — e o número piorou quando isso foi consertado
 *
 * O material chega em fatias de 5 min porque é assim que ele cabe no `adb push`.
 * Fatia é unidade de **arquivo**, não de fluxo. A primeira versão deste teste
 * chamava [DetectorDeAtivacao.reiniciar] na emenda entre fatias, o que estaria certo
 * se as fatias fossem gravações independentes — e elas são pedaços consecutivos do
 * mesmo áudio.
 *
 * Em produção a `EscutaDeAtivacao` mantém **um** fluxo o turno inteiro e só reinicia
 * o anel nas bordas da mudez (saída própria, PTT, ciclo). Cada `reiniciar()` a mais
 * custa duas coisas: **1 s de anel em que o detector não decide nada** (`preenchido`
 * volta a zero e a primeira decisão só sai 16 000 amostras depois) e um **deslocamento
 * de fase** das janelas de 80 ms para o resto da fatia — `desde` volta a zero, e as
 * janelas passam a cair em outros instantes do áudio.
 *
 * Consertar a emenda faz o número **subir**, e é esse o ponto: um arranjo que fica
 * surdo em pedaços do material conta menos disparos do que o turno real teria.
 * Medido no emulador, mesmo áudio (115,5 min, dois podcasts) e mesma cabeça:
 *
 * ```
 * fatiado, com reiniciar()    1 disparo    0,52/h    maior escore 0,508
 * contínuo, como no turno     3 disparos   1,56/h    maior escore 0,743
 * ```
 *
 * O maior escore é o que mais denuncia o arranjo: o fatiado não perdeu só evento na
 * fronteira, ele nunca chegou a **avaliar** a janela de 0,743 — 0,24 acima do limiar,
 * o oposto de um empate. Dos três, o primeiro cai em **1906,6 s**, o mesmo instante
 * que o arranjo fatiado reportava e o mesmo que o avaliador em Python
 * (`ferramentas/ativacao/avaliador_alinhado.py`) acha ao replicar o laço do aparelho.
 * É a coincidência que valida o alinhamento: os arranjos concordam sobre o disparo
 * que ambos veem e divergem sobre os que a emenda apagava.
 *
 * O Python e o aparelho ainda **não** contam igual, e a causa é aritmética: a decisão
 * sai quando `desde % 1280 == 0` com `desde` andando módulo 16 000, e 16 000 não é
 * múltiplo de 1280. A cada volta do anel a grade escorrega 640 amostras — o aparelho
 * decide 13 vezes por segundo, em instantes que derivam, e o avaliador decide 12,5 em
 * instantes fixos. Com eventos que cruzam o limiar por 0,008, grade diferente é
 * contagem diferente. **Quem manda é este teste**: é o laço que roda no bolso do
 * agente.
 *
 * **Entre PODCASTS o reinício continua correto**, e continua aqui: ali o áudio muda
 * de fonte de verdade, e emendar o fim de um no começo do outro avaliaria uma janela
 * que nunca existiu no mundo. O agrupamento é por prefixo de nome — ver [materiais].
 *
 * ## A comparação com os 89,85/h continua valendo
 *
 * Os 89,85/h de `02d9dad` saíram **deste mesmo teste, neste mesmo aparelho, com o
 * arranjo fatiado**: 81 disparos em 0,90 h. Os dois lados da comparação carregam,
 * portanto, o mesmo viés — e o viés é subcontagem. Se ele move o número antigo, move
 * para **cima**: 11 emendas × 1 s de anel são 0,3% do material, e com escore máximo
 * de 0,997 nenhum daqueles 81 disparos dependia de 80 ms de fase para existir.
 *
 * A comparação sobrevive porque o erro é conservador: o ganho do retreino é, no
 * mínimo, o que os números dizem. O que **não** sobreviveria é comparar o 0,52/h
 * fatiado com qualquer coisa medida em fluxo contínuo — no regime de 1 a 3 eventos,
 * em que o disparo cruza o limiar por 0,008, a fase decide se o evento existe. Por
 * isso o arranjo fatiado foi removido em vez de mantido "por compatibilidade".
 *
 * ## Sobre o material
 *
 * O áudio são podcasts em pt-BR indicados por quem conduz o projeto, usados **só como
 * conjunto negativo local**: nada deles é transcrito, redistribuído ou versionado —
 * o que entra no repositório é o número. Ficam fora do `git` pela mesma regra dos
 * áudios de treino.
 *
 * ```
 * yt-dlp -x --audio-format wav --postprocessor-args "-ar 16000 -ac 1"
 * ffmpeg -i negativo.wav -f segment -segment_time 300 -c copy fatia_%02d.wav
 * adb push fatia_*.wav .../files/bench/negativo/   # 2º podcast entra como p2_*.wav
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FalsoPositivoEmFalaEspontaneaTest {

    private val ctxDoTeste get() = InstrumentationRegistry.getInstrumentation().context

    private val pasta: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "bench/negativo",
        )

    private fun detector(): DetectorDeAtivacao? {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val bytes = runCatching {
            assets.open("${DetectorDeAtivacao.ASSETS}/cabeca.f32").use { it.readBytes() }
        }.getOrNull() ?: return null
        val (pesos, vies) = DetectorDeAtivacao.cabecaDeBytes(bytes) ?: return null
        val d = DetectorDeAtivacao(pesos, vies)
        return if (d.preparar(assets)) d else null.also { d.close() }
    }

    /**
     * As fatias agrupadas por **material**, cada grupo em ordem cronológica.
     *
     * O `ffmpeg -f segment` numera as fatias de um mesmo áudio sob um prefixo comum
     * (`fatia_00`, `fatia_01`, …) e o segundo podcast entrou com prefixo próprio
     * (`p2_00`, …). O prefixo é, portanto, a identidade do fluxo: **dentro** dele as
     * fatias são consecutivas e o detector não pode reiniciar; **entre** prefixos há
     * corte de verdade e reiniciar é obrigatório.
     *
     * Arquivo sem `_` no nome vira material de um só pedaço — que é o comportamento
     * certo para um negativo avulso.
     */
    private fun materiais(): List<Pair<String, List<File>>> =
        (pasta.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray())
            .sortedBy { it.name }
            .groupBy { it.name.substringBeforeLast('_') }
            .toList()
            .sortedBy { it.first }

    private fun amostrasDoWav(f: File): ShortArray {
        val b = f.readBytes()
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val tam = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(tam, b.size - i - 8) / 2
                return ShortArray(n) { k ->
                    val p = i + 8 + k * 2
                    (((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)).toShort()
                }
            }
            i += 8 + tam + (tam and 1)
        }
        return ShortArray(0)
    }

    @Test
    fun oDetectorFicaCalado_emHorasDeFalaEspontanea() {
        Assume.assumeTrue("sem o negativo em ${pasta.path}", pasta.isDirectory)
        val materiais = materiais()
        Assume.assumeTrue("sem fatias de áudio", materiais.isNotEmpty())

        val d = detector()
        Assume.assumeTrue("detector não subiu (assets ausentes?)", d != null)

        var disparos = 0
        var amostras = 0L
        var pedacos = 0
        var maiorEscore = 0f
        val quandoDisparou = mutableListOf<String>()

        try {
            for ((material, fatias) in materiais) {
                // Anel limpo entre MATERIAIS, e só aqui: o áudio muda de fonte, e
                // emendar o fim de um podcast no começo do outro avaliaria uma janela
                // que nunca existiu — o defeito que a `EscutaDeAtivacao` evita nas
                // bordas da mudez. Dentro do material o fluxo é UM só, como no turno.
                d!!.reiniciar()
                var consumidas = 0L
                var resto = ShortArray(0)
                for (fatia in fatias) {
                    pedacos++
                    val lido = amostrasDoWav(fatia)
                    val pcm = if (resto.isEmpty()) lido else resto + lido
                    val base = consumidas
                    var i = 0
                    while (i + BLOCO <= pcm.size) {
                        if (d.aceitar(pcm.copyOfRange(i, i + BLOCO))) {
                            disparos++
                            quandoDisparou += "$material@${"%.1f".format((base + i) / 16000.0)}s" +
                                " (${fatia.name}, escore ${"%.3f".format(d.ultimoEscore)})"
                        }
                        if (d.ultimoEscore > maiorEscore) maiorEscore = d.ultimoEscore
                        i += BLOCO
                    }
                    // As amostras que não fecharam um bloco de 20 ms não somem: elas são
                    // o começo do próximo bloco, que continua na fatia seguinte. Jogá-las
                    // fora na emenda seria a versão pequena do mesmo furo do `reiniciar()`.
                    consumidas = base + i
                    resto = pcm.copyOfRange(i, pcm.size)
                }
                // Só conta como medido o que o detector realmente consumiu.
                amostras += consumidas
            }
        } finally {
            d?.close()
        }

        val horas = amostras / 16000.0 / 3600.0
        val porHora = if (horas > 0) disparos / horas else 0.0
        // Regra de três de Rutherford: com zero eventos em n janelas, o limite
        // superior de 95% é 3/n. É o número honesto quando não houve disparo.
        val tetoPorHora = if (disparos == 0 && horas > 0) 3.0 / horas else porHora

        Log.i("ClaryonField", "FALSO POSITIVO — fala espontânea, múltiplos locutores")
        Log.i(
            "ClaryonField",
            "  material ................. ${materiais.size} podcast(s) em $pedacos fatias, " +
                "${"%.2f".format(horas)} h (${"%.1f".format(horas * 60)} min), fluxo contínuo\n" +
                "  disparos ................. $disparos\n" +
                "  taxa ..................... ${"%.2f".format(porHora)}/h" +
                (if (disparos == 0) "  (teto de 95%: ${"%.2f".format(tetoPorHora)}/h)" else "") + "\n" +
                "  maior escore ............. ${"%.3f".format(maiorEscore)} (limiar 0,500)\n" +
                "  meta do aceite ........... 0,5/h\n" +
                (if (quandoDisparou.isNotEmpty()) "  onde: ${quandoDisparou.take(10)}\n" else "") +
                "  ⚠️ É o estágio 1 (detector acústico) — o que dispara o earcon. " +
                "Falso aceite que ABRE CANAL é outro número, e depende da transcrição.",
        )

        org.junit.Assert.assertTrue(
            "o detector disparou $disparos vezes em ${"%.2f".format(horas)} h de fala " +
                "espontânea = ${"%.2f".format(porHora)}/h, contra a meta de 0,5/h. " +
                "Onde: ${quandoDisparou.take(10)}",
            porHora <= 0.5,
        )
    }

    /**
     * **O falso aceite que ABRE CANAL — a conjunção dos dois estágios.**
     *
     * O aceite da Fase 2 tem dois limites e eles medem coisas diferentes: earcon
     * falso ≤ 0,5/h (o teste acima) e **canal aberto por engano ≤ 1 por 8 h**. O
     * segundo é o grave: earcon falso irrita, canal aberto por engano difunde
     * ruído de ambiente para a guarnição inteira.
     *
     * Medir isto é barato e o motivo é a arquitetura: só as janelas em que o
     * estágio 1 disparou chegam ao estágio 2. Transcrever 54 min levaria horas;
     * transcrever os poucos segundos ao redor de cada disparo leva minutos.
     *
     * A janela é de 4 s a partir de 1 s ANTES do disparo — é o que a
     * `EscutaDeAtivacao` entregaria ao ciclo: o gatilho mais o comando que viria
     * depois. Transcrever só o instante do disparo mediria outra coisa.
     *
     * O fluxo é contínuo dentro de cada material, pelo mesmo motivo do teste acima, e
     * isso muda a janela: o segundo ANTES do disparo pode estar na fatia anterior, e
     * é justamente onde a palavra mora (o detector decide no FIM do anel de 1 s).
     * Por isso a `cauda` guardada — sem ela, um disparo perto da emenda seria
     * transcrito sem o gatilho e este teste diria "não confirmou" por defeito de
     * arranjo. O rabo da janela ainda é aparado no fim de cada material; quantas
     * vezes isso aconteceu sai no log como `janelas aparadas`, para a limitação ser
     * medida em vez de suposta.
     */
    @Test
    fun nenhumDisparoFalso_chegaAAbrirCanal(): Unit = runBlocking {
        Assume.assumeTrue("sem o negativo em ${pasta.path}", pasta.isDirectory)
        val materiais = materiais()
        Assume.assumeTrue("sem fatias", materiais.isNotEmpty())
        val d = detector()
        Assume.assumeTrue("detector não subiu", d != null)
        val whisper = EscutaDoAgente.de(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        Assume.assumeTrue("whisper indisponível", whisper != null)

        val router = DeterministicIntentRouter()
        var disparos = 0
        var confirmaramGatilho = 0
        var abriramCanal = 0
        var amostras = 0L
        var aparadas = 0
        val exemplos = mutableListOf<String>()

        try {
            for ((material, fatias) in materiais) {
                d!!.reiniciar() // só entre MATERIAIS — ver o KDoc da classe.
                var consumidas = 0L
                var resto = ShortArray(0)
                var cauda = ShortArray(0)
                for (fatia in fatias) {
                    val lido = amostrasDoWav(fatia)
                    val pcm = if (resto.isEmpty()) lido else resto + lido
                    val base = consumidas
                    var i = 0
                    while (i + BLOCO <= pcm.size) {
                        if (d.aceitar(pcm.copyOfRange(i, i + BLOCO))) {
                            disparos++
                            // O pré-roll de 1 s pode estar na fatia anterior: a janela
                            // é lida sobre `cauda + pcm`, que é o fluxo como o turno o
                            // teria entregado.
                            val ctx = if (cauda.isEmpty()) pcm else cauda + pcm
                            val ini = maxOf(0, cauda.size + i - UM_SEGUNDO)
                            val fim = minOf(ctx.size, ini + 4 * UM_SEGUNDO)
                            if (fim - ini < 4 * UM_SEGUNDO) aparadas++
                            val texto = (whisper!!.transcribe(ctx.copyOfRange(ini, fim), 16_000)
                                as? Result.Success)?.value?.text.orEmpty()
                            if (PalavraDeAtivacaoNaFala.conferir(texto).confirmada) confirmaramGatilho++
                            if (router.route(texto) is Intent.AbrirTransmissao) {
                                abriramCanal++
                                exemplos += "$material@${"%.1f".format((base + i) / 16000.0)}s " +
                                    "\"${texto.take(50)}\""
                            }
                        }
                        i += BLOCO
                    }
                    consumidas = base + i
                    // A cauda é o 1 s imediatamente ANTES do que a próxima fatia vai
                    // consumir — e para antes do `resto`, senão o pré-roll apareceria
                    // duas vezes e a janela sairia deslocada.
                    cauda = pcm.copyOfRange(maxOf(0, i - UM_SEGUNDO), i)
                    resto = pcm.copyOfRange(i, pcm.size)
                }
                amostras += consumidas
            }
        } finally {
            d?.close()
        }

        val horas = amostras / 16000.0 / 3600.0
        val porOitoHoras = if (horas > 0) abriramCanal * 8.0 / horas else 0.0
        Log.i("ClaryonField", "FALSO ACEITE QUE ABRE CANAL — conjunção dos dois estágios")
        Log.i(
            "ClaryonField",
            "  material ................. ${materiais.size} podcast(s), " +
                "${"%.2f".format(horas)} h, fluxo contínuo\n" +
                "  estágio 1 disparou ....... $disparos\n" +
                "  estágio 2 confirmou ...... $confirmaramGatilho\n" +
                "  ABRIU CANAL .............. $abriramCanal  (${"%.2f".format(porOitoHoras)} por 8 h)\n" +
                "  janelas aparadas ......... $aparadas  (fim de material; 0 = a limitação não bateu)\n" +
                "  meta do aceite ........... ≤ 1 por 8 h\n" +
                (if (exemplos.isNotEmpty()) "  onde: ${exemplos.take(5)}\n" else ""),
        )

        org.junit.Assert.assertTrue(
            "ruído de ambiente abriu canal $abriramCanal vez(es) em " +
                "${"%.2f".format(horas)} h = ${"%.2f".format(porOitoHoras)} por 8 h. " +
                "Cada uma difunde som ambiente para a guarnição inteira. ${exemplos.take(3)}",
            porOitoHoras <= 1.0,
        )
    }
}
