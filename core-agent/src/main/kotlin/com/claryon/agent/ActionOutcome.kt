package com.claryon.agent

/**
 * **Resultado de uma ação que realmente aconteceu.**
 *
 * Este tipo existe para fechar a lacuna central do produto: até aqui, a frase
 * falada era escolhida a partir da *intenção* — o app dizia "Apoio solicitado"
 * sem ter enviado nada e "Gravação iniciada" sem gravar. Um copiloto de
 * segurança pública que afirma ter pedido apoio quando não pediu é **pior que a
 * ausência do produto**, porque o agente para de procurar o rádio.
 *
 * A regra, garantida por assinatura e não por disciplina: `utteranceFor` aceita
 * [ActionOutcome] e **não existe sobrecarga que aceite [Intent]**. Não há
 * caminho no código em que a fala seja construída antes de a ação ser executada.
 */
sealed interface ActionOutcome {

    /**
     * Apoio entregue agora.
     *
     * @param destinatarios quantas unidades receberam. **`null` = entregue, mas
     *   a contagem é desconhecida** — é o caso enquanto o servidor não devolve
     *   `{destinatarios: n}`. Inventar um número aqui (ou assumir zero) seria
     *   mentir sobre quem está a caminho, que é a única coisa que o agente
     *   precisa saber. A fala muda de acordo.
     */
    data class ApoioTransmitido(val destinatarios: Int?) : ActionOutcome

    /** Sem rede: guardado na fila durável. O agente **precisa** saber disso. */
    data object ApoioEnfileirado : ActionOutcome

    /** Cofre de evidência aberto; [id] identifica a ocorrência. */
    data class GravacaoIniciada(val id: String) : ActionOutcome

    /** Cofre fechado, manifesto de custódia emitido com [segmentos] segmentos. */
    data class GravacaoEncerrada(val segmentos: Int) : ActionOutcome

    /**
     * Placa consultada. O resultado é **sensível**, e desde 21/08 sai como earcon
     * codificado **mais** fala curta.
     *
     * O KDoc anterior dizia "nunca falado", pelo alto-falante *open-ear* vazar para
     * quem está ao lado. **A decisão de falar é humana** (§7), com a ponderação de
     * que o vazamento exige silêncio e volume alto — premissa que virou item medível
     * da Fase 5, para deixar de ser opinião dos dois lados.
     *
     * O earcon **não** saiu, e por medição: ele chega em 139 ms, enquanto a fala de
     * uma placa custa segundos (o Piper expande número por extenso — "Art. 306, Lei
     * 9.503" dá 3518 ms de áudio). Se um P1 do rádio preemptar a fala, o agente já
     * recebeu a resposta pelo som.
     */
    data class PlacaConsultada(val placa: String, val restricao: Restricao) : ActionOutcome

    /** Ocorrência narrada e registrada. */
    data class OcorrenciaRegistrada(val id: String) : ActionOutcome

    /** Modo de operação trocado de fato (energia, câmera e rede já reconfiguradas). */
    data class ModoTrocado(val modo: ModoOperacao) : ActionOutcome

    /**
     * Grupo trocado. Carrega o **nome de exibição** ("GTA-3 Alfa"), nunca o UUID:
     * a confirmação falada tem de nomear o grupo para o agente saber para onde a
     * voz dele passou a ir, e UUID falado é chave primária vazando por áudio.
     */
    data class GrupoTrocado(val nome: String) : ActionOutcome

    /**
     * O rótulo falado não está na lista deste agente.
     *
     * **Um único resultado para dois casos**, de propósito: "grupo não existe" e
     * "existe e você não é membro" produzem a mesma resposta. Distinguir vazaria a
     * estrutura da corporação pelo texto da recusa — mesma classe de erro que o
     * servidor evita ao devolver grandezas em vez de coordenada de terceiro.
     * Ver a spec, § *A recusa não revela o que não é do agente*.
     */
    data class GrupoNaoReconhecido(val rotuloFalado: String) : ActionOutcome

    /**
     * Transmissão aberta por voz — *"guarnição 3 na escuta"*.
     *
     * O nome é o do CADASTRO, resolvido pelo rótulo falado. Ecoar de volta o que o
     * agente disse confirmaria a fala, não a ação: ele saberia que foi ouvido e
     * continuaria sem saber em qual guarnição está falando.
     */
    data class TransmissaoAberta(val nomeDoGrupo: String) : ActionOutcome

    /** Par localizado (C2). A fala sai de [FalaDePosicao], nunca de coordenadas. */
    data class PosicaoEncontrada(val posicao: PosicaoRelativa) : ActionOutcome

    /**
     * O par não foi localizado. **Não é falha do sistema** — é informação: pode
     * estar fora do talk group, ou sem posição recente. Inventar uma posição
     * plausível aqui seria dizer a um policial que o apoio está a 800 m quando
     * está a 6 km.
     */
    data class ParNaoLocalizado(val indicativo: String) : ActionOutcome

    /**
     * Alerta disparado (C3), com a contagem de quem recebeu.
     *
     * @param destinatarios `null` = entregue sem contagem conhecida. A mesma
     *   regra do apoio: contagem desconhecida não vira zero nem número inventado.
     */
    data class AlertaDisparado(
        val tipo: TipoDeOcorrencia,
        val prioridade: Prioridade,
        val destinatarios: Int?,
    ) : ActionOutcome

    /**
     * **A norma foi recuperada. [citacao] e [norma] são `String` pura, de propósito.**
     *
     * O `Trecho` fica do outro lado da fronteira: `core-agent` não tem
     * `core-knowledge` no classpath, e não deve ter. O que atravessa é o mínimo para
     * o agente saber **onde** está a resposta — `"Art. 28"` e `"Lei 9.503/1997"`.
     *
     * ## Por que o TEXTO do artigo não vem junto
     *
     * Não é economia: é o teto de **7 palavras** por fala operacional, que é
     * invariante deste produto e tem teste varrendo todos os ramos de
     * [utteranceFor]. Um artigo de lei tem dezenas de palavras, e num produto sem
     * display o agente não tem como pular o que está sendo lido — despejar um
     * parágrafo no ouvido de quem está em ocorrência é pior do que não responder.
     *
     * Ler o artigo inteiro é capacidade legítima e **está proposta em `specs/`**,
     * esperando decisão humana, porque sobrepor regra dura é decisão de gente (§7).
     * Enquanto isso, a citação é resposta honesta: diz onde está, sem fingir que
     * leu.
     */
    data class NormaEncontrada(val citacao: String, val norma: String) : ActionOutcome

    /**
     * **Nada no corpus ficou perto o bastante — e isto é resposta, não falha.**
     *
     * Devolver o vizinho mais próximo quando ninguém está perto é como o produto
     * inventaria lei. A recusa é o comportamento correto e precisa soar como
     * recusa: [utteranceFor] a transforma em fala curta, não em silêncio nem em
     * earcon de erro. O agente ouviu, o copiloto procurou, e não achou.
     */
    data object NormaNaoEncontrada : ActionOutcome

    /** Transcrição não casou com nenhuma intenção. Não é falha: é "repita". */
    data object NaoEntendi : ActionOutcome

    /** A ação foi tentada e falhou. Nunca silêncio — [falha] vira earcon + causa curta. */
    data class Falhou(val falha: FalhaOperacional) : ActionOutcome
}

/** Situação de uma placa consultada. */
enum class Restricao { SEM_RESTRICAO, ADMINISTRATIVA, FURTO_ROUBO }

/**
 * Causas de falha operacional, com a **causa em três palavras** que vai ao
 * ouvido junto do earcon. Código estável para telemetria e para o mapeamento
 * erro → earcon.
 */
enum class FalhaOperacional(val causaCurta: String) {
    SEM_ROTA_DE_AUDIO("Sem rota."),
    COFRE_INDISPONIVEL("Cofre falhou."),

    /**
     * Disco no piso de reserva: o cofre parou de gravar **de propósito**.
     *
     * Tem código próprio, e não `COFRE_INDISPONIVEL`, porque a recuperação é
     * diferente e o agente precisa saber qual é. "Cofre falhou" manda procurar
     * defeito; "disco cheio" manda liberar espaço. Antes, esta condição não
     * produzia som nenhum: o cofre falhava cinquenta vezes por segundo e o
     * retorno era descartado.
     */
    SEM_ESPACO("Disco cheio."),
    GRAVACAO_JA_ATIVA("Já gravando."),
    SEM_GRAVACAO_ATIVA("Nada gravando."),
    PLACA_NAO_LIDA("Placa ilegível."),
    CONSULTA_INDISPONIVEL("Consulta indisponível."),

    // ── Falhas de CÂMERA, agrupadas por RECUPERAÇÃO ─────────────────────────────
    //
    // `ErroDeStream` (core-glasses) distingue oito causas com frases prontas, e até
    // 21/08 as oito chegavam ao agente como "Consulta indisponível." — a causa
    // tipada morria num `Log.w` um passo antes do alto-falante.
    //
    // **O critério aqui é a AÇÃO do agente, não o código do SDK.** Oito causas não
    // precisam de oito falas; precisam de tantas quantas forem as recuperações
    // distintas. Duas causas que levam ao mesmo gesto compartilham a fala; duas que
    // levam a gestos diferentes NÃO podem compartilhar — e era exatamente isso que
    // estava acontecendo.
    //
    // Por que valores de enum e não um `String?` em `Falhou`: `utteranceFor` aceitar
    // SÓ `ActionOutcome` é o que torna impossível o app falar o que não aconteceu.
    // Um texto livre que vira fala é porta por onde conteúdo arbitrário alcança o
    // alto-falante — e em 21/08 foi medido que 2 de 3 saídas do LLM, entregues ao
    // roteador, viram ação. Ver `specs/falha-de-camera-falada.spec.md`.

    /** Hastes fechadas. Dois segundos e o agente resolve sozinho. */
    CAMERA_OCULOS_DOBRADOS("Óculos dobrados. Abra as hastes."),

    /** Consentimento não concedido. Exige parar e mexer no celular. */
    CAMERA_SEM_PERMISSAO("Libere a câmera no Meta AI."),

    /** Freio térmico. **Insistir piora** — por isso não compartilha fala com falha genérica. */
    CAMERA_QUENTE("Óculos quentes. Câmera pausada."),

    /**
     * Energia dos óculos no fim. Recobre `BATTERY_LOW` e `PEAK_POWER_LIMIT`: a frase
     * do SDK difere ("sem energia para transmitir"), mas o gesto é o mesmo — pôr no
     * estojo. Colapso deliberado, registrado na spec.
     */
    CAMERA_SEM_BATERIA("Bateria dos óculos acabando."),

    /**
     * Falha de stream sem recuperação específica: tentar de novo é o que há.
     * Recobre `STREAM_ERROR`, `CRITICAL_STREAM_ERROR`, `TIMEOUT` e `DESCONHECIDO`.
     */
    CAMERA_INDISPONIVEL("Câmera falhou. Tente de novo."),
    SEM_REDE("Sem rede."),

    /**
     * A consulta de posição precisa da posição **própria** para dizer distância e
     * rumo. Sem ela não há resposta relativa — e a alternativa, entregar a
     * coordenada crua do par, é justamente o que a regra de C2 proíbe.
     */
    SEM_POSICAO_PROPRIA("Sem sinal de GPS."),

    /** Permissão de localização negada. Falha explícita, nunca degradação muda. */
    SEM_PERMISSAO_DE_LOCAL("Sem permissão de local."),
    /**
     * O piso do canal é de outro agente. Não é erro: é o rádio funcionando.
     *
     * Precisa de fala própria porque o desfecho silencioso é o pior — o agente que
     * disse "guarnição 3 na escuta" e não ouve nada assume que está no ar e fala
     * para ninguém, exatamente na hora em que precisava ser ouvido.
     */
    CANAL_OCUPADO("Canal ocupado."),

    /**
     * **O pedido de canal não alcançou o árbitro.**
     *
     * Tem código próprio, e não [CANAL_OCUPADO], porque a ação do agente é a
     * **oposta**: canal ocupado se resolve esperando o colega soltar o botão;
     * pedido sem resposta se resolve andando até pegar sinal. Até 22/08 as duas
     * saíam com o mesmo evento e o mesmo tom, e o agente embaixo de um viaduto
     * esperava por uma vez que nunca chegaria.
     *
     * Também não é [SEM_REDE], que é a queda genérica: aqui o rádio pode estar de
     * pé — o RPC do piso é HTTP e o áudio é WebSocket, e um cai sem o outro.
     */
    PEDIDO_DE_CANAL_SEM_RESPOSTA("Sem sinal. Nada foi transmitido."),

    /**
     * O árbitro respondeu **não**: sem autorização neste canal.
     *
     * Nem ocupado, nem sem rede — houve rede e houve resposta. Manda o agente
     * conferir credencial e guarnição, não procurar torre.
     */
    CANAL_SEM_AUTORIZACAO("Sem acesso a este canal."),

    /**
     * **A fala acabou e o canal não voltou ao grupo.**
     *
     * Do lado de quem falou está tudo normal: a voz saiu, o botão foi solto. A
     * guarnição inteira fica muda até o TTL de 30 s vencer, e só uma P1 fura.
     * Quem causou é o único que pode agir — repetindo a soltura com sinal.
     */
    CANAL_NAO_DEVOLVIDO("Canal preso. Confira a rede."),

    /**
     * **A fala do colega foi cortada no meio pela rede** — o final não chegou.
     *
     * Não é falha deste aparelho, e ainda assim é earcon: quem ouviu precisa saber
     * que o que ele tem está incompleto antes de decidir uma abordagem com base
     * nisso. Uma fala inteira e uma fala truncada chegavam à tela como o mesmo
     * evento, campo a campo.
     */
    FALA_DO_COLEGA_CORTADA("Transmissão do colega cortada."),

    /**
     * **O piso está sendo arbitrado em RAM deste aparelho, não pelo servidor.**
     *
     * Acontece quando não há sessão: `RadioViewModel` cai em `ClienteDePisoLocal`,
     * e dois aparelhos podem se achar donos do mesmo canal e falar por cima. O
     * rádio precisa funcionar em túnel e subsolo, então a degradação fica — o que
     * não pode ficar é ela sendo **silenciosa**, como era até 22/08, quando o
     * único sinal era uma linha de `Log.w`.
     */
    PISO_SEM_ARBITRO("Sem servidor. Piso local."),

    NADA_A_REPETIR("Nada a repetir."),

    /**
     * Pediu troca de grupo com transmissão aberta.
     *
     * Trocar no meio de uma transmissão mandaria o fim da frase para a guarnição
     * errada — e o agente não teria como saber, porque do lado dele o áudio
     * continuou saindo. Recusar é a única resposta honesta.
     */
    TRANSMISSAO_EM_CURSO("Fale depois de encerrar."),

    /**
     * O léxico de canais não carregou (sem sessão, ou falha de rede no login).
     *
     * Falha explícita, e **nunca** cair para `CanalDoPiloto` em silêncio: um
     * fallback muto faria o agente crer que trocou de guarnição enquanto continua
     * falando na anterior. Ver a spec, aceite 6.
     */
    SEM_LEXICO_DE_CANAIS("Sem lista. Entre de novo."),

    /**
     * Pediu troca de grupo sem rádio no ar.
     *
     * Existe porque a alternativa é pior: dizer "Agora na guarnição três" com o
     * rádio fechado faria o agente passar a falar acreditando estar em outro canal.
     * Recusa audível, sempre.
     */
    RADIO_FECHADO("Abra o rádio primeiro."),
    INTERNA("Falha interna."),
}
