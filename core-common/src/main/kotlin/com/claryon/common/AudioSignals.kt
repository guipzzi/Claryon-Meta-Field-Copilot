package com.claryon.common

/**
 * Vocabulário da saída sonora — **compartilhado** entre quem decide o que o
 * agente vai ouvir (`core-agent`) e quem renderiza (`core-sound`).
 *
 * Mora em `core-common` porque os `core-*` não dependem uns dos outros: sem isto,
 * o roteador/executor não teria como dizer "isto sai como earcon, não como fala"
 * sem inventar um segundo enum e abrir espaço para as duas listas divergirem.
 */

/**
 * Prioridade de reprodução.
 *  - [EMERGENCIA] (nível 1): interrompe qualquer coisa.
 *  - [RESPOSTA]   (nível 2): resposta a comando do próprio agente; aguarda nível 1.
 *  - [INFORMATIVO](nível 3): suprimido inteiramente em Modo Tático.
 */
enum class Priority { EMERGENCIA, RESPOSTA, INFORMATIVO }

/**
 * Biblioteca fixa de earcons — sinal não-verbal curto, significado fixo.
 *
 * ## A gramática de três tempos (22/08, decisão do dono)
 *
 * ```
 * "Claryon"                  → DESPERTAR      BOMMM     identidade
 * "guarnição N na escuta"    → CANAL_ABERTO   bipbip    convenção (a comunicação ABRIU)
 * [o agente fala]
 * parou de falar, ou 30 s    → CANAL_FECHADO  trimtrim  convenção (a comunicação FECHOU)
 * ```
 *
 * **Despertar é identidade; abrir e fechar canal é convenção.** O primeiro é um
 * som que só existe neste produto — golpe de sino inarmônico, o único earcon do
 * vocabulário com ataque de golpe e decaimento — e é ele que a marca registra. Os
 * outros dois imitam de propósito o chirp do rádio Nextel/iDEN, porque o policial
 * já sabe o que eles querem dizer e convenção conhecida não precisa de
 * treinamento. **Inverter os dois papéis custaria as duas coisas**: uma marca que
 * ninguém reconhece e um par de sons que ele teria de aprender.
 *
 * ## A restrição que decide o desenho
 *
 * O elo até os óculos é **HFP/SCO 8 kHz** (o barramento interno é 16 kHz — ver
 * `VoiceOutput.TAXA_SAIDA_HZ` —, mas o que atravessa o Bluetooth é banda estreita).
 * Nada acima de ~3,4 kHz sobrevive. E ruído de viatura é de baixa frequência:
 * abaixo de ~400 Hz o motor come o sinal. **A janela é de 400 a 3400 Hz**, e
 * `DistinguibilidadeDosEarconsTest.todoEarconCabeNaJanelaDoHfp` a sustenta.
 *
 * ## O que separa um earcon do outro — e por que não é a frequência
 *
 * Sob ruído de banda estreita, o que sobrevive é **morfologia** (quantos elementos,
 * separados por quanto silêncio) e **contorno** (sobe, desce, plano). Altura
 * absoluta é a pista mais frágil das três. Por isso cada earcon tem uma
 * **assinatura morfológica única** — `(nº de elementos, contornos, ataque)` —
 * calculada do PCM, não declarada; e por isso o teste de distinguibilidade compara
 * também os **primeiros 120 ms**, que é onde o agente decide se aquilo interessa.
 *
 * Até 22/08 `GRAVANDO` e `CONSULTA_FURTO_ROUBO` eram **idênticos bit a bit por
 * 115 ms** (1845 de 1920 amostras), e `ACAO_EXECUTADA` e `CONSULTA_RESTRICAO_ADMIN`
 * tinham a mesma morfologia a 4,5 semitons de distância. Nenhum teste guardava
 * isso.
 */
enum class Earcon(val significado: String) {

    // ---------------------------------------------------- a gramática do canal

    /**
     * **BOMMM.** A marca sonora — só nossa, característica, registrável.
     *
     * Golpe de sino: parciais **inarmônicos** (1 · 2,76 · 5,40 sobre 466 Hz, as
     * razões de um sino de verdade), ataque de 2 ms e decaimento exponencial de
     * 520 ms. É o **único** earcon do vocabulário classificado como `GOLPE` — os
     * outros dez são tons sustentados —, e é essa a propriedade que o torna
     * reconhecível já nos primeiros 20 ms e difícil de confundir com um bipe de
     * qualquer outro aparelho da viatura.
     *
     * 466 Hz e não os ~250 Hz de um gongo: abaixo de 400 Hz o ruído de motor
     * mascara a fundamental, e num elo de 8 kHz não há grave que se pague.
     */
    DESPERTAR("BOMMM — golpe de sino inarmônico: 'Claryon acordou'"),

    /** **bipbip.** Dois chirps curtos ASCENDENTES — a comunicação abriu (estilo Nextel). */
    CANAL_ABERTO("bipbip — dois chirps ascendentes: 'canal aberto, pode falar'"),

    /** **trimtrim.** Dois chirps DESCENDENTES, o segundo mais longo — a comunicação fechou. */
    CANAL_FECHADO("trimtrim — dois chirps descendentes: 'canal fechado'"),

    // ------------------------------------------------------ resposta e estado

    ACAO_EXECUTADA("dois degraus curtos subindo — 'ação executada'"),
    FALHA("varredura grave descendente — 'não entendi / falhou'"),

    /**
     * **Trem de pulsos** de 2 s (8 pulsos de 145 ms), não mais um tom contínuo.
     *
     * O tom contínuo de 500 Hz abria **exatamente igual** ao antigo
     * `CONSULTA_FURTO_ROUBO` — mesmo `tone(500.0, …)`, 115 ms bit a bit. Pulsar
     * resolve o ataque e é melhor no que este earcon existe para fazer: avisar o
     * ambiente de que há gravação em curso. Som que pulsa é notado; som contínuo
     * vira paisagem em segundos. A duração total continua 2 s.
     */
    GRAVANDO("trem de 8 pulsos em 2 s — 'gravando' (avisa agente e ambiente)"),
    PRIORITARIA("três bipes rápidos e planos — 'mensagem prioritária chegando'"),

    // Resultado de consulta sensível: codificado no som, E falado em ≤7 palavras
    // desde 21/08 (decisão humana). Os três earcons continuam DISTINTOS de
    // propósito: eles chegam em 139 ms e a fala pode ser preemptada por P1.
    CONSULTA_SEM_RESTRICAO("1 bipe curto, claro e neutro"),

    /**
     * Duas **díades** (dois tons simultâneos, uma quinta) — a segunda descendente.
     *
     * A díade é o que separa este earcon do `ACAO_EXECUTADA` no **ataque**: os dois
     * eram dois bipes planos de tom puro a 4,5 semitons um do outro, e altura
     * absoluta é a pista que o ruído de viatura destrói primeiro. Timbre e contorno
     * não se destroem junto.
     */
    CONSULTA_RESTRICAO_ADMIN("duas díades, a segunda descendente — restrição administrativa"),

    /** Sirene hi-lo de 4 elementos — o desfecho mais grave da consulta tem a morfologia mais insistente. */
    CONSULTA_FURTO_ROUBO("sirene hi-lo, 4 elementos — furto/roubo"),
}

/**
 * Protocolo de laconicidade — regra dura do design de áudio, verificável.
 *
 * Fala sintetizada é cara em atenção. Em contexto operacional, nenhuma resposta
 * de TTS pode exceder [MAX_WORDS] palavras. Sem cortesia ("por favor",
 * "desculpe", "tudo bem"). "Apoio solicitado, guarnição avisada." — não a versão
 * longa e educada.
 *
 * Puro e testável. Vive em `core-common` porque agora é aplicado **na origem**
 * (o executor, ao construir a fala a partir do resultado da ação) e não só na
 * saída.
 */
object LaconicityPolicy {

    const val MAX_WORDS = 7

    /**
     * Palavras que **sozinhas** já são cortesia, sem ambiguidade operacional.
     * "por", "tudo" e "bem" NÃO entram aqui: são preposição e advérbios comuns
     * em fala operacional legítima ("Apoio solicitado **por** rádio.", "Sem
     * restrição, **tudo** limpo.") — barrá-las reprovaria respostas válidas.
     */
    private val CORTESIA_ISOLADA = setOf(
        "favor", "desculpe", "desculpa", "obrigado", "obrigada",
    )

    /** Cortesia que só existe como locução — casada na frase inteira. */
    private val CORTESIA_LOCUCAO = listOf("por favor", "tudo bem", "com licenca", "com licença")

    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    fun isWithinLimit(text: String): Boolean = wordCount(text) <= MAX_WORDS

    fun hasCourtesy(text: String): Boolean {
        val minusculo = text.lowercase()
        if (CORTESIA_LOCUCAO.any { minusculo.contains(it) }) return true
        return minusculo.split(Regex("\\W+")).any { it in CORTESIA_ISOLADA }
    }

    /** `true` se a fala respeita o protocolo (curta e sem cortesia). */
    fun isCompliant(text: String): Boolean = isWithinLimit(text) && !hasCourtesy(text)
}
