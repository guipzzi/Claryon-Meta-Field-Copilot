package com.claryon.agent

import com.claryon.common.Earcon
import com.claryon.common.Priority

/**
 * O que o agente ouve. Sempre derivado de um [ActionOutcome] — ver [utteranceFor].
 */
sealed interface Utterance {

    val priority: Priority

    /** Fala sintetizada. Sujeita ao protocolo de laconicidade (≤ 7 palavras). */
    data class Falar(val texto: String, override val priority: Priority) : Utterance

    /**
     * Só sinal, sem fala. Usado onde a fala atrapalharia — `GRAVANDO` é tom de 2 s
     * que avisa o AMBIENTE, e locução ali seria ruído.
     *
     * Este KDoc dizia "resultado sensível **nunca** é falado". Deixou de valer em
     * 21/08 por decisão humana: consulta de placa passou a `SinalizarEFalar`. Ver o
     * ramo de `PlacaConsultada` em [utteranceFor].
     */
    data class Sinalizar(val earcon: Earcon, override val priority: Priority) : Utterance

    /** Sinal seguido de fala curta — usado em falha (earcon + causa em três palavras). */
    data class SinalizarEFalar(
        val earcon: Earcon,
        val texto: String,
        override val priority: Priority,
    ) : Utterance
}

/**
 * **Constrói a resposta a partir do resultado da ação — jamais do comando.**
 *
 * Esta é a função que fecha a regra de honestidade do produto. Repare no que
 * *não* existe neste arquivo: nenhuma sobrecarga `utteranceFor(intent: Intent)`.
 * Isso é deliberado e não deve ser acrescentado. Enquanto o único parâmetro for
 * [ActionOutcome], é **impossível** — não improvável, impossível — que o app
 * fale "Apoio solicitado" sem que o apoio tenha sido de fato despachado, porque
 * não há como obter um [ActionOutcome] sem passar pelo [IntentExecutor].
 *
 * Regras de saída aplicadas aqui:
 *  - resultado de consulta sensível sai como earcon codificado **e** fala curta —
 *    ver o ramo de `PlacaConsultada`, onde a mudança de 21/08 está justificada;
 *  - "gravando" é tom contínuo de 2 s **sem fala** (avisa o agente e o ambiente);
 *  - toda falha tem earcon próprio — falha nunca é silêncio;
 *  - toda fala respeita ≤ 7 palavras, sem cortesia (há teste que varre todos os
 *    ramos deste `when`).
 */
fun utteranceFor(outcome: ActionOutcome): Utterance = when (outcome) {

    is ActionOutcome.ApoioTransmitido -> when (outcome.destinatarios) {
        // Entregue, sem contagem: afirma só o que se sabe.
        null -> Utterance.Falar("Apoio enviado.", Priority.EMERGENCIA)
        // Honestidade: saiu, mas não havia ninguém por perto. Dizer só "Apoio
        // solicitado" faria o agente contar com uma unidade que não existe.
        0 -> Utterance.Falar("Enviado. Nenhuma unidade próxima.", Priority.EMERGENCIA)
        1 -> Utterance.Falar("Uma unidade recebeu.", Priority.EMERGENCIA)
        else -> Utterance.Falar(
            "${porExtenso(outcome.destinatarios)} unidades receberam.",
            Priority.EMERGENCIA,
        )
    }

    ActionOutcome.ApoioEnfileirado ->
        Utterance.SinalizarEFalar(Earcon.FALHA, "Sem rede. Na fila.", Priority.EMERGENCIA)

    is ActionOutcome.GravacaoIniciada ->
        // Tom de 2 s, sem fala: avisa quem está ao redor de que há gravação.
        Utterance.Sinalizar(Earcon.GRAVANDO, Priority.RESPOSTA)

    is ActionOutcome.GravacaoEncerrada ->
        Utterance.SinalizarEFalar(Earcon.ACAO_EXECUTADA, "Gravação encerrada.", Priority.RESPOSTA)

    // ── Earcon PRIMEIRO, fala depois — e os dois números que justificam ─────────
    //
    // **A decisão de falar é humana, de 21/08.** O KDoc anterior dizia "NUNCA
    // falado: o alto-falante open-ear vaza para o abordado", e o risco é real: quem
    // está a um metro ouve. A ponderação do usuário foi que o vazamento exige
    // silêncio e volume alto, e ele é quem decide (§7). A premissa entrou como item
    // MEDÍVEL na Fase 5 — volume operacional, 1 m e 2 m —, para parar de depender de
    // opinião dos dois lados.
    //
    // **Mas o earcon não sai, e isso não é conservadorismo — é medição.** Em 21/08:
    //
    //   earcon .................... 139 ms  (fim da fala → som)
    //   síntese do Piper .......... 124 ms  para uma frase curta
    //   "Art. 306, Lei 9.503" .... 1574 ms de síntese, 3518 ms de ÁUDIO
    //
    // O Piper expande número por extenso, e uma placa é sete caracteres: falá-la
    // custa segundos. `SinalizarEFalar` dá ao agente a **categoria** em 139 ms e o
    // **detalhe** depois — e degrada bem, porque um P1 do rádio que preempte a fala
    // não apaga a resposta que ele já recebeu pelo som. Fala sozinha perderia tudo.
    //
    // A frase fica em ≤7 palavras porque o teto vale aqui como em todo o resto: a
    // placa e a condição, sem cortesia. Quem quiser o detalhe pede `Detalhar`.
    is ActionOutcome.PlacaConsultada ->
        Utterance.SinalizarEFalar(
            when (outcome.restricao) {
                Restricao.SEM_RESTRICAO -> Earcon.CONSULTA_SEM_RESTRICAO
                Restricao.ADMINISTRATIVA -> Earcon.CONSULTA_RESTRICAO_ADMIN
                Restricao.FURTO_ROUBO -> Earcon.CONSULTA_FURTO_ROUBO
            },
            when (outcome.restricao) {
                Restricao.SEM_RESTRICAO -> "${outcome.placa}, sem restrição."
                Restricao.ADMINISTRATIVA -> "${outcome.placa}, restrição administrativa."
                Restricao.FURTO_ROUBO -> "${outcome.placa}, furto ou roubo."
            },
            Priority.RESPOSTA,
        )

    is ActionOutcome.OcorrenciaRegistrada ->
        Utterance.SinalizarEFalar(Earcon.ACAO_EXECUTADA, "Ocorrência registrada.", Priority.RESPOSTA)

    is ActionOutcome.ModoTrocado -> Utterance.Falar(
        when (outcome.modo) {
            ModoOperacao.STANDBY -> "Modo standby."
            ModoOperacao.ATIVO -> "Modo ativo."
            ModoOperacao.OCORRENCIA -> "Modo ocorrência."
        },
        Priority.RESPOSTA,
    )

    is ActionOutcome.PosicaoEncontrada ->
        Utterance.Falar(FalaDePosicao.para(outcome.posicao), Priority.RESPOSTA)

    is ActionOutcome.ParNaoLocalizado ->
        // Não é earcon de falha: o sistema funcionou, o par é que não está
        // localizável. Tratar como erro faria o agente duvidar do rádio.
        Utterance.Falar(FalaDePosicao.naoEncontrado(outcome.indicativo), Priority.RESPOSTA)

    is ActionOutcome.AlertaDisparado -> when (outcome.destinatarios) {
        null -> Utterance.Falar("Alerta enviado.", Priority.EMERGENCIA)
        0 -> Utterance.Falar("Alerta enviado. Ninguém próximo.", Priority.EMERGENCIA)
        1 -> Utterance.Falar("Uma unidade recebeu.", Priority.EMERGENCIA)
        else -> Utterance.Falar(
            "${porExtenso(outcome.destinatarios)} unidades receberam.",
            Priority.EMERGENCIA,
        )
    }

    is ActionOutcome.GrupoTrocado ->
        // Earcon ANTES da fala (aceite 8 da spec): o efeito aqui é redirecionar a
        // voz do agente, e confirmação cega ("pronto") não serve — ele precisa
        // ouvir PARA ONDE foi.
        Utterance.SinalizarEFalar(
            Earcon.ACAO_EXECUTADA,
            "Agora na ${nomeFalavelDeGrupo(outcome.nome)}.",
            Priority.RESPOSTA,
        )

    is ActionOutcome.TransmissaoAberta ->
        // **`GRAVANDO`, não `ACAO_EXECUTADA`.** O roadmap chama isto de "BIP de
        // confirmação: está gravando e transmitindo", e é um estado que COMEÇA e
        // dura — não uma ação que terminou. Um earcon de conclusão diria ao agente
        // que acabou, quando na verdade acabou de começar.
        //
        // E o nome vem do CADASTRO, não do que ele falou: ecoar a fala confirmaria
        // que foi ouvido e o deixaria sem saber em qual guarnição está no ar.
        Utterance.SinalizarEFalar(
            Earcon.GRAVANDO,
            "No ar na ${nomeFalavelDeGrupo(outcome.nomeDoGrupo)}.",
            Priority.RESPOSTA,
        )

    is ActionOutcome.GrupoNaoReconhecido ->
        // **Não** distingue "não existe" de "existe e você não é membro": a
        // distinção é informação sobre a estrutura da corporação. Mesmo princípio
        // do servidor, que devolve grandezas e nunca coordenada de terceiro.
        // O rótulo dito NÃO é repetido de volta — repetir confirmaria ao ouvido de
        // quem está por perto qual grupo foi tentado.
        Utterance.SinalizarEFalar(
            Earcon.FALHA,
            "Não conheço essa guarnição.",
            Priority.RESPOSTA,
        )

    // **A citação é a resposta, e ela cabe no teto.**
    //
    // "Artigo 28, Lei 9.503" tem 4 palavras. O TEXTO do artigo não vem aqui de
    // propósito: são dezenas de palavras contra o teto de 7, e num produto sem
    // display o agente não consegue pular o que está sendo lido. A leitura verbatim
    // está proposta em `specs/` esperando decisão humana — sobrepor regra dura é
    // decisão de gente, não diff (§7).
    //
    // Dizer ONDE está sem fingir que leu é a resposta honesta que cabe hoje.
    is ActionOutcome.NormaEncontrada ->
        Utterance.Falar("${outcome.citacao}, ${outcome.norma}", Priority.RESPOSTA)

    // **Recusa é resposta, e precisa SOAR como resposta.**
    //
    // Não é `SinalizarEFalar(FALHA, ...)`: nada falhou. O copiloto procurou no
    // corpus e nada ficou perto o bastante do limiar. Vestir isso de erro ensinaria
    // o agente a desconfiar do aparelho quando o aparelho acertou.
    ActionOutcome.NormaNaoEncontrada ->
        Utterance.Falar("Não achei na norma.", Priority.RESPOSTA)

    // ── Fonte EXTERNA: responde, e NÃO se apresenta ────────────────────────────
    //
    // *"Hospital Getúlio Vargas, a 800 metros."* — sem "segundo a internet", sem
    // "encontrei na web", sem "aproximadamente". A proposta original da spec era o
    // contrário; o dono do projeto a inverteu em 22/08, e o teto de sete palavras
    // torna o argumento concreto:
    //
    //   - ressalva custa sílaba dentro de um orçamento de SETE palavras;
    //   - ressalva repetida vira ruído que o agente aprende a ignorar — e ele
    //     ignora junto a informação que vinha depois dela;
    //   - o sinal de credibilidade JÁ EXISTE e é grátis: a **citação**. Resposta
    //     interna diz "Art. 306, Lei 9.503"; resposta externa não diz nada disso.
    //     **A ausência da citação é o sinal.**
    //
    // `Falar` puro, sem earcon, e isso também é decisão: earcon distinto marcaria a
    // origem no som — seria a mesma ressalva, só que instrumental. O agente aprende
    // a distinção pelo conteúdo, como já aprende com a `Procedencia` da base
    // veicular.
    //
    // Há teste varrendo estes dois ramos atrás de termo de rebaixamento
    // (`FalaDeFonteExternaTest`), porque uma regra de produto que depende de
    // ninguém acrescentar "provavelmente" numa string é uma regra que não existe.
    is ActionOutcome.LugarEncontrado ->
        Utterance.Falar(FalaDeLugar.para(outcome.lugar), Priority.RESPOSTA)

    // Teve rede e não há nada no raio. **Não é earcon de falha**: nada falhou —
    // mesmo raciocínio de `ParNaoLocalizado` e de `NormaNaoEncontrada`. Tratar
    // ausência como erro ensina o agente a duvidar do aparelho quando ele acertou.
    is ActionOutcome.LugarNaoEncontrado ->
        Utterance.Falar(outcome.categoria.falaDeAusencia, Priority.RESPOSTA)

    ActionOutcome.NaoEntendi ->
        Utterance.SinalizarEFalar(Earcon.FALHA, "Não entendi, repita.", Priority.RESPOSTA)

    is ActionOutcome.Falhou ->
        Utterance.SinalizarEFalar(Earcon.FALHA, outcome.falha.causaCurta, Priority.RESPOSTA)
}

/**
 * Números falados por extenso até doze. Acima disso, o dígito a dígito da regra
 * geral seria pior aqui ("um-cinco unidades" soa como código, não como
 * contagem); o limite prático de uma guarnição não passa disso.
 */
private fun porExtenso(n: Int): String = when (n) {
    2 -> "Duas"
    3 -> "Três"
    4 -> "Quatro"
    5 -> "Cinco"
    6 -> "Seis"
    7 -> "Sete"
    8 -> "Oito"
    9 -> "Nove"
    10 -> "Dez"
    11 -> "Onze"
    12 -> "Doze"
    else -> n.toString()
}

/**
 * Recorta o nome de um talk group para caber no teto de sete palavras.
 *
 * O nome vem do **cadastro**, não do código: "GTA-3 Alfa" cabe, "Grupamento
 * Tático de Ações Especiais Três" não. Deixar o teto depender de os cadastros
 * serem curtos é confiar em disciplina de terceiro para sustentar um invariante do
 * produto — e o invariante existe porque fala longa por HFP em viatura não é
 * ouvida, é ruído.
 *
 * Quatro palavras: com "Agora na " na frente, o total fecha em seis.
 *
 * **Risco declarado, a medir com fone real:** nomes de unidade brasileiros são
 * cheios de sigla ("GTA", "ROTAM", "GATE"), e sigla falada letra por letra vive
 * acima do corte de 4 kHz do HFP em banda estreita — é o mesmo motivo pelo qual
 * "Claryon" foi escolhida como palavra de ativação. Se em hardware real a sigla
 * não for compreensível, a saída é uma coluna `nome_falado` no cadastro, e não
 * mudar isto aqui. Fica registrado em `specs/troca-de-grupo-por-voz.spec.md`.
 */
private fun nomeFalavelDeGrupo(nome: String): String =
    nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4).joinToString(" ")
