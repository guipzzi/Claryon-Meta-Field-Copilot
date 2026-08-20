package com.claryon.net

import android.util.Log

/**
 * **Guarda o PCM que foi ao ar — não o que foi capturado.**
 *
 * A transcrição na origem é o pilar P1: todos os receptores exibem exatamente o
 * mesmo texto, e o servidor nunca precisa transcrever. Para isso valer, o texto tem
 * de derivar **do mesmo áudio que os outros ouviram**. Transcrever o que o microfone
 * captou produziria um texto que ninguém pode conferir: incluiria o pré-roll
 * descartado, incluiria o que veio depois do corte por silêncio, e incluiria quadros
 * que a codificação recusou.
 *
 * Por isso o acúmulo acontece num ponto só — dentro de `SessaoPtt.enviar`, depois de
 * a codificação ter dado certo. É o funil por onde **todo** quadro passa, tanto o do
 * pré-roll quanto o do `collect` ao vivo, e é onde ainda existe PCM (a codificação
 * vem logo depois).
 *
 * ## O que ele deliberadamente NÃO promete
 *
 * Que os bytes acumulados correspondam pacote a pacote ao que saiu na rede. O codec
 * é um pipeline: um quadro de entrada pode render zero pacotes no aquecimento e mais
 * de um depois. O que este acumulador registra é **o PCM entregue à codificação para
 * transmissão**, que é a fronteira honesta e verificável. A diferença é a cauda de
 * alguns milissegundos no fim de cada fala, e ela está declarada aqui em vez de
 * virar uma precisão que o KDoc afirma e o código não sustenta.
 *
 * ## Vive em RAM, e some
 *
 * `CLAUDE.md` §2: o pré-roll do PTT vive em RAM e nunca é persistido. O mesmo vale
 * aqui, e por um motivo mais forte — este buffer contém a voz do agente em claro. Ele
 * é limpo no início de cada transmissão e depois de a transcrição consumi-lo.
 *
 * @param limiteAmostras teto duro. Em 16 kHz, 30 s são 480 000 amostras (≈960 KB), e
 *   30 s é o teto de duração que `SessaoPtt` já impõe. Passar disso significa que
 *   alguma outra invariante quebrou, e crescer sem limite transformaria um defeito de
 *   duração num `OutOfMemory` — falha muito pior e num lugar sem relação com a causa.
 */
class AcumuladorDePcm(private val limiteAmostras: Int = TETO_PADRAO) {

    private val blocos = ArrayList<ShortArray>()
    private var total = 0
    private var avisouDoTeto = false

    /** Quantas amostras foram ao ar até agora. */
    val amostras: Int get() = total

    /**
     * Acrescenta um quadro que acabou de ser aceito para transmissão.
     *
     * Silencioso ao atingir o teto — exceto por um aviso, uma vez só. Lançar aqui
     * derrubaria a transmissão em curso por causa da transcrição, que é acessória:
     * o agente perderia o rádio para não perder o texto, que é a troca errada.
     */
    fun acrescentar(pcm: ShortArray) {
        if (total + pcm.size > limiteAmostras) {
            if (!avisouDoTeto) {
                Log.w(TAG, "acumulador no teto de $limiteAmostras amostras; o resto não entra")
                avisouDoTeto = true
            }
            return
        }
        blocos.add(pcm)
        total += pcm.size
    }

    /** Tudo que foi ao ar, em ordem, num único vetor. */
    fun tudo(): ShortArray {
        val saida = ShortArray(total)
        var i = 0
        for (b in blocos) {
            b.copyInto(saida, i)
            i += b.size
        }
        return saida
    }

    /**
     * Devolve o acumulado e esvazia na mesma operação.
     *
     * Uma operação só, e não `tudo()` seguido de `limpar()`, porque a voz em claro
     * não pode depender de o chamador lembrar da segunda chamada. Quem consome, apaga.
     */
    fun consumir(): ShortArray = tudo().also { limpar() }

    fun limpar() {
        blocos.clear()
        total = 0
        avisouDoTeto = false
    }

    private companion object {
        const val TAG = "ClaryonField"

        /** 30 s a 16 kHz — o mesmo teto de duração que a sessão de PTT impõe. */
        const val TETO_PADRAO = 30 * 16_000
    }
}
