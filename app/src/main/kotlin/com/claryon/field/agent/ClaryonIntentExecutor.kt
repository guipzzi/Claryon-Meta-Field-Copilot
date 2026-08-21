package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.ModoOperacao
import com.claryon.agent.BuscaDePar
import com.claryon.agent.Ocorrencia
import com.claryon.agent.PlacaValidator
import com.claryon.agent.Prioridade
import com.claryon.agent.Restricao
import com.claryon.common.Result
import com.claryon.evidence.EvidenceVault
import com.claryon.evidence.OccurrenceContext
import com.claryon.evidence.RecordingHandle
import com.claryon.sync.Despacho
import com.claryon.sync.RecipientType
import com.claryon.sync.TacticalDispatcher
import com.claryon.sync.TacticalMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordenada própria, com a precisão que o GPS informou. */
data class Coordenada(
    val latitude: Double,
    val longitude: Double,
    val precisaoM: Float,
    /**
     * Para onde o portador está apontando, em graus a partir do norte. `null`
     * quando o aparelho não sabe — GPS só entrega rumo em movimento, e parado
     * ele não tem como saber para onde o corpo está virado.
     *
     * **É o único dado de posição que nunca sai do aparelho.** Ele existe para
     * girar a seta na tela do próprio agente e não tem serventia nenhuma para
     * mais ninguém — então não entra em `PublicadorDePosicao`, não entra no
     * esquema do servidor, e não há caminho por onde vaze. Um dado que só é
     * útil localmente deve ficar local; é a versão barata de uma garantia que
     * em outros pontos deste produto custou muito mais para obter.
     */
    val rumoGraus: Float? = null,
)

/**
 * O que o cofre fez com um bloco de evidência.
 *
 * Existe porque o `Boolean` anterior não distinguia "não há gravação aberta" —
 * situação normal — de "o disco encheu e a evidência está se perdendo agora". E o
 * `Boolean` era **descartado** pelo único chamador, o que fazia as duas
 * desaparecerem juntas em silêncio. Regra dura do produto: falha nunca é silêncio.
 */
enum class AnexoDeEvidencia {
    /** Bloco aceito (na janela ou já selado). */
    ACEITO,

    /** Não há gravação aberta. Não é falha — o pipeline pode estar só rodando. */
    SEM_GRAVACAO,

    /** Disco no piso de reserva. Áudio está sendo perdido **agora**. */
    SEM_ESPACO,

    /** Qualquer outra falha do cofre. */
    FALHOU,
}

/** Identidade operacional do portador — preenche o template da mensagem tática. */
data class Identidade(
    val agentId: String,
    val unitId: String,
    val vehiclePrefix: String?,
    val destinoPadrao: String,
)

/**
 * Executor real: liga cada intenção ao módulo que faz a coisa acontecer.
 *
 * É aqui que `core-evidence` e `core-sync` — prontos e testados, mas até agora
 * nunca importados pelo código de produção — passam a ser exercitados.
 *
 * Três invariantes que este código sustenta:
 *
 *  1. **Nunca lança.** Toda exceção vira [ActionOutcome.Falhou] com causa
 *     tipada. Num sistema sem display, exceção que sobe é silêncio, e silêncio
 *     é indistinguível de aplicativo morto.
 *  2. **Serializa.** Um `Mutex` protege o handle de gravação. Dois comandos
 *     concorrentes ("gravar" repetido sob estresse) abririam duas ocorrências e
 *     a segunda sobregravaria a primeira — a auditoria já pegou essa classe de
 *     defeito dentro do cofre, e ela reaparece aqui se o executor não serializar.
 *  3. **Não inventa.** Onde a dependência não foi injetada, ou onde a base não
 *     autoriza afirmar nada, devolve [FalhaOperacional.CONSULTA_INDISPONIVEL] em
 *     vez de um resultado plausível.
 *
 *     A frase aqui era *"consulta a base oficial está fora do escopo"*, e ela
 *     deixou de valer em 21/08: a base existe (`core-net/BaseVeicular.kt`,
 *     migração `0023`), e o que continua **não** existindo é convênio com Detran ou
 *     Sinesp — por isso toda resposta carrega procedência, e só a oficial pode
 *     virar *"sem restrição"*. Ver [consultarPlacaDe].
 */
class ClaryonIntentExecutor(
    private val cofre: EvidenceVault,
    private val despachante: TacticalDispatcher,
    private val identidade: Identidade,
    private val agora: () -> Long,
    private val aoTrocarModo: suspend (ModoOperacao) -> Unit,
    /**
     * Taxa do microfone que vai alimentar o cofre — vem do `GlassesAudioManager`
     * que realmente captura, e entra no manifesto.
     *
     * **Sem valor padrão de propósito.** O padrão anterior era 16 kHz nos dois
     * lados: casava por coincidência, e um manager construído com outra taxa faria
     * o manifesto declarar uma taxa que o áudio não tem — o perito receberia PCM
     * que toca na velocidade errada, sem nada no arquivo denunciando o erro.
     */
    private val taxaDeAmostragemHz: () -> Int,
    /**
     * Onde estou. `null` = sem correção de GPS **ou** sem permissão — a distinção
     * é feita por [permissaoDeLocal], porque as duas causas exigem recuperações
     * diferentes do agente (esperar sinal × abrir os ajustes).
     */
    private val minhaPosicao: suspend () -> Coordenada? = { null },
    /** Onde está um par, já relativo a mim. Ver [BuscaDePar]. */
    private val localizarPar: suspend (String) -> BuscaDePar = { BuscaDePar.Indisponivel },
    private val permissaoDeLocal: () -> Boolean = { true },
    /**
     * Troca de talk group. Devolve o **nome de exibição** do grupo em que ficamos,
     * ou uma falha tipada.
     *
     * Injetado como lambda pelo mesmo motivo dos outros: `core-agent` não conhece
     * `RadioTatico`, e não pode — o executor decide *o quê*, o app sabe *como*.
     * Padrão que recusa tudo: um executor construído sem esta dependência não pode
     * silenciosamente parecer capaz de trocar de canal.
     */
    /**
     * **Consulta à norma: pergunta entra, `(citação, documento)` sai — ou `null`.**
     *
     * Repare no tipo: `String`, não `Trecho`. **Este executor não nomeia
     * `core-knowledge` em lugar nenhum**, e é de propósito. A regra dura é que texto
     * recuperado não vire ação; a forma barata de sustentá-la é o executor
     * literalmente não ter vocabulário para falar de conhecimento. Quem conhece os
     * dois lados é a raiz de composição, que costura `Trecho → (citacao, norma)` uma
     * vez só, num lugar que dá para auditar de olho.
     *
     * É o mesmo padrão de [localizarPar] e [minhaPosicao]: o executor declara o que
     * precisa, não de quem.
     *
     * **O padrão é `null`, e isso não é adiar — é dizer a verdade.** Enquanto não
     * houver índice, não há resposta, e o produto responde "Não achei na norma".
     * O contrário — devolver algo plausível para o caminho "parecer pronto" — é
     * exatamente a falha que este projeto cometeu seis vezes e que o §6 do
     * `CLAUDE.md` existe para impedir.
     */
    private val consultarNorma: suspend (String) -> Pair<String, String>? = { null },
    private val trocarDeGrupo: suspend (String) -> TrocaDeGrupo =
        { TrocaDeGrupo.Falhou(FalhaOperacional.SEM_LEXICO_DE_CANAIS) },
    /**
     * Abre a transmissão no grupo já resolvido. `false` quando o piso é de outro.
     *
     * Padrão que recusa, pela mesma razão de [trocarDeGrupo]: um executor
     * construído sem esta dependência não pode parecer capaz de pôr o agente no ar.
     * Recusar é audível; parecer capaz e não abrir é o agente falando para ninguém.
     */
    private val abrirTransmissao: suspend (String) -> Boolean = { false },
    /**
     * **Lê a placa pela câmera dos óculos**, para quando o agente não a ditou.
     *
     * Repare no tipo: nenhum parâmetro, e o retorno não é `String?`. Sem parâmetro
     * porque não há o que escolher — quem sabe o perfil de câmera, a janela de 5 s e
     * a frase de instrução é o lado do app, e o executor decide *o quê*, não *como*.
     * Sem `String?` porque "não consegui ler" e "a câmera nem abriu" pedem coisas
     * diferentes do agente, e um nulo as colapsaria.
     *
     * Padrão que recusa, como [trocarDeGrupo] e [abrirTransmissao]: um executor
     * construído sem esta dependência **não pode parecer capaz de ver**.
     */
    private val lerPlacaPelaCamera: suspend () -> LeituraDePlaca =
        { LeituraDePlaca.SemCamera(FalhaOperacional.CAMERA_INDISPONIVEL) },
    /**
     * **Consulta a base veicular.** Placa entra, situação sai — ou nada.
     *
     * Mesmo padrão de [consultarNorma]: o executor não nomeia `core-net` em lugar
     * nenhum, e quem costura `ConsultaVeicular → ConsultaDePlaca` é a raiz de
     * composição. A tradução é onde mora a regra que o produto não pode perder — que
     * "não sei" nunca vire "está limpo" —, e ela fica num lugar só, auditável de
     * olho, em vez de espalhada por ramos de `when` aqui dentro.
     *
     * O padrão `NaoRespondeu` não é adiamento: enquanto não houver base, a resposta
     * honesta é que não deu. Um "sem restrição" inventado numa abordagem é o pior
     * desfecho possível deste fluxo.
     */
    private val consultarPlaca: suspend (String) -> ConsultaDePlaca =
        { ConsultaDePlaca.NaoRespondeu },
) : IntentExecutor {

    /**
     * O que a câmera conseguiu ler. Espelha `oculos.LeituraDePlaca` reduzido ao que
     * muda a resposta — o executor não precisa saber quantos frames custou.
     */
    sealed interface LeituraDePlaca {
        /** Sete caracteres. **Nunca** uma imagem: o que sai do fluxo é string. */
        data class Lida(val placa: String) : LeituraDePlaca

        /** A câmera funcionou e nenhum frame tinha placa legível. */
        data object Ilegivel : LeituraDePlaca

        /** A câmera não abriu, ou não entregou imagem. */
        /**
         * A câmera não abriu, e [falha] diz **por quê** em termos do que o agente faz
         * a seguir — não em termos do código do SDK.
         *
         * Era `data object` até 21/08, e por isso as oito causas distintas de
         * `ErroDeStream` chegavam ao ouvido como uma só. A tradução vive em
         * `oculos/FalhaDaCamera`, porque `core-agent` não conhece `core-glasses` e
         * não deve conhecer.
         */
        data class SemCamera(val falha: FalhaOperacional) : LeituraDePlaca
    }

    /**
     * O que a base respondeu, reduzido ao que a fala precisa.
     *
     * **Dois estados, e não `Restricao?`.** Um nulo teria "desconhecido" como valor
     * de repouso, e valor de repouso é exatamente por onde "não sei" vira "está
     * limpo" — o defeito que `core-net/BaseVeicular.kt` inteiro existe para impedir.
     */
    sealed interface ConsultaDePlaca {
        /** A base declarou a situação, e ela pode ser dita ao agente. */
        data class Respondeu(val placa: String, val restricao: Restricao) : ConsultaDePlaca

        /** Base ausente, base de demonstração sem restrição, transporte caído. */
        data object NaoRespondeu : ConsultaDePlaca
    }

    /**
     * O que o app devolve ao executor depois de tentar a troca.
     *
     * Tipo próprio em vez de `Boolean`: "não trocou" tem três causas com
     * recuperações diferentes (não conheço o grupo / sem lista / transmissão em
     * curso), e um booleano as colapsaria numa só — que é como se constrói uma
     * mensagem de erro que não ajuda ninguém.
     */
    sealed interface TrocaDeGrupo {
        /** [nome] é o de exibição, para a fala. Nunca o UUID. */
        data class Trocado(val nome: String) : TrocaDeGrupo
        data object NaoReconhecido : TrocaDeGrupo
        data class Falhou(val falha: FalhaOperacional) : TrocaDeGrupo
    }

    private val mutex = Mutex()
    private var gravacaoAtual: RecordingHandle? = null

    /** Falhas consecutivas ao fechar o cofre. Ver [encerrarGravacao]. */
    private var falhasAoFechar = 0

    /** Último resultado, para [Intent.Detalhar]. Não guarda o próprio repetir. */
    private var ultimoResultado: ActionOutcome? = null

    override suspend fun execute(intent: Intent): ActionOutcome {
        // Detalhar não é ação: relê o que já aconteceu. Fora do mutex e fora do
        // registro, senão repetir passaria a ser "o último resultado".
        if (intent is Intent.Detalhar) {
            return ultimoResultado ?: ActionOutcome.Falhou(FalhaOperacional.NADA_A_REPETIR)
        }

        val outcome = mutex.withLock {
            runCatching { executarInterno(intent) }
                .getOrElse { ActionOutcome.Falhou(FalhaOperacional.INTERNA) }
        }
        ultimoResultado = outcome
        return outcome
    }

    private suspend fun executarInterno(intent: Intent): ActionOutcome = when (intent) {

        is Intent.PedirApoio -> despachar(intent.prioridade, intent.resumo ?: "Apoio solicitado")

        Intent.Emergencia -> despachar(Prioridade.EMERGENCIA, "Emergência acionada")

        is Intent.IniciarGravacao -> iniciarGravacao()

        Intent.EncerrarGravacao -> encerrarGravacao()

        is Intent.NarrarOcorrencia -> registrarOcorrencia(intent.texto)

        is Intent.ConsultarPlaca -> consultarPlacaDe(intent.placa)

        // C2 — consulta de posição. Devolve distância, rumo e estado; a coordenada
        // bruta do par **nunca** chega ao aparelho de outro agente.
        is Intent.ConsultarPosicao -> consultarPosicao(intent.indicativo)

        // C3 — alerta de ocorrência classificada.
        is Intent.AlertarOcorrencia -> alertar(intent.ocorrencia)

        is Intent.TrocarModo -> {
            aoTrocarModo(intent.modo)
            ActionOutcome.ModoTrocado(intent.modo)
        }

        is Intent.TrocarDeGrupo -> when (val r = trocarDeGrupo(intent.rotuloFalado)) {
            is TrocaDeGrupo.Trocado -> ActionOutcome.GrupoTrocado(r.nome)
            TrocaDeGrupo.NaoReconhecido -> ActionOutcome.GrupoNaoReconhecido(intent.rotuloFalado)
            is TrocaDeGrupo.Falhou -> ActionOutcome.Falhou(r.falha)
        }

        // **Abrir transmissão por voz.** Resolve o grupo pelo MESMO caminho da
        // troca por voz — o rótulo vem do cadastro, nunca do que o agente falou —
        // e só então manda abrir. Duas etapas, e a ordem importa: abrir primeiro e
        // resolver depois deixaria o agente no ar numa guarnição não confirmada.
        is Intent.AbrirTransmissao -> when (val r = trocarDeGrupo(intent.rotuloFalado)) {
            is TrocaDeGrupo.Trocado ->
                if (abrirTransmissao(r.nome)) {
                    ActionOutcome.TransmissaoAberta(r.nome)
                } else {
                    // O piso é de quem pediu primeiro. Não abrir é resultado
                    // legítimo, e o agente precisa ouvir que NÃO está no ar —
                    // achar que está e falar para ninguém é o pior desfecho.
                    ActionOutcome.Falhou(FalhaOperacional.CANAL_OCUPADO)
                }
            TrocaDeGrupo.NaoReconhecido -> ActionOutcome.GrupoNaoReconhecido(intent.rotuloFalado)
            is TrocaDeGrupo.Falhou -> ActionOutcome.Falhou(r.falha)
        }

        is Intent.ConsultarNorma -> when (val achado = consultarNorma(intent.pergunta)) {
            null -> ActionOutcome.NormaNaoEncontrada
            else -> ActionOutcome.NormaEncontrada(achado.first, achado.second)
        }

        is Intent.NaoReconhecida -> ActionOutcome.NaoEntendi

        // Tratado antes do mutex; o ramo existe para o `when` ser exaustivo.
        Intent.Detalhar -> ultimoResultado ?: ActionOutcome.Falhou(FalhaOperacional.NADA_A_REPETIR)
    }

    // ── Despacho tático ───────────────────────────────────────────────────────

    private suspend fun despachar(prioridade: Prioridade, situacao: String): ActionOutcome {
        val msg = TacticalMessage(
            recipientType = RecipientType.GROUP,
            recipient = identidade.destinoPadrao,
            agentId = identidade.agentId,
            vehiclePrefix = identidade.vehiclePrefix,
            location = null,
            latitude = null,
            longitude = null,
            situation = situacao.take(SITUACAO_MAX),
            priority = prioridade.name,
            evidenceStatus = gravacaoAtual?.let { "gravando" },
        )
        return when (val d = despachante.despachar(msg)) {
            is Despacho.Enviada -> ActionOutcome.ApoioTransmitido(d.destinatarios)
            Despacho.Enfileirada -> ActionOutcome.ApoioEnfileirado
        }
    }

    private suspend fun registrarOcorrencia(texto: String): ActionOutcome {
        val msg = TacticalMessage(
            recipientType = RecipientType.GROUP,
            recipient = identidade.destinoPadrao,
            agentId = identidade.agentId,
            vehiclePrefix = identidade.vehiclePrefix,
            location = null,
            latitude = null,
            longitude = null,
            situation = texto.take(SITUACAO_MAX),
            priority = Prioridade.NORMAL.name,
            evidenceStatus = gravacaoAtual?.let { "gravando" },
        )
        // Enfileirada também é "registrada": a fila é durável e sobrevive à morte
        // do processo, então a ocorrência não se perde. O que muda é a entrega.
        return when (despachante.despachar(msg)) {
            is Despacho.Enviada, Despacho.Enfileirada ->
                ActionOutcome.OcorrenciaRegistrada(identidade.agentId + "@" + agora())
        }
    }

    // ── C2: consulta de placa ─────────────────────────────────────────────────

    /**
     * **Placa ditada ou lida pela câmera — e um único portão de formato para as duas.**
     *
     * @param ditada o que o roteador extraiu da fala, ou `null` quando o agente
     *   disse só *"verifica a placa desse carro"*. Nulo **não** é falha: é o pedido
     *   de captura, e era o ramo que não existia — `withCamera` e `PlacaOcr` tinham
     *   zero chamadores em `src/main`.
     *
     * ## Por que a validação se repete aqui
     *
     * `PlacaOcr` já filtra por `PlacaValidator`, e o roteador também. A conferência
     * neste ponto é de propósito, e não é redundância defensiva: este é o **único
     * lugar por onde uma placa entra na consulta**, venha do reconhecedor de texto,
     * do roteador determinístico ou — quando a Etapa B ligar `PlacaDitada` — de um
     * modelo de linguagem normalizando alfabeto fonético. A regra dura do
     * `CLAUDE.md` §2 diz que o LLM só preenche campo de intenção já definida; o que
     * torna essa regra verificável é o formato ser conferido **depois**, num ponto
     * que nenhuma dessas fontes pode contornar.
     *
     * Saída que não case com Mercosul (`LLLNLNN`) ou com o padrão antigo
     * (`LLLNNNN`) é **erro de leitura, não consulta**. É o que impede o pior modo de
     * falha deste fluxo: consultar — e liberar — um veículo que o OCR fabricou.
     */
    private suspend fun consultarPlacaDe(ditada: String?): ActionOutcome {
        val bruta = ditada ?: when (val leitura = lerPlacaPelaCamera()) {
            is LeituraDePlaca.Lida -> leitura.placa
            // Apontou e não deu. "Placa ilegível." manda o agente aproximar-se,
            // que é a recuperação certa para este caso e só para este.
            LeituraDePlaca.Ilegivel ->
                return ActionOutcome.Falhou(FalhaOperacional.PLACA_NAO_LIDA)
            // A câmera não abriu. Dizer "placa ilegível" aqui mandaria o agente
            // aproximar-se de um veículo que o aparelho nunca viu.
            is LeituraDePlaca.SemCamera ->
                // A causa tipada chega até aqui e VIRA FALA. Antes morria num Log.w.
                return ActionOutcome.Falhou(leitura.falha)
        }

        val placa = PlacaValidator.normalizar(bruta)
        if (!PlacaValidator.isValida(placa)) {
            return ActionOutcome.Falhou(FalhaOperacional.PLACA_NAO_LIDA)
        }

        return when (val r = consultarPlaca(placa)) {
            is ConsultaDePlaca.Respondeu -> ActionOutcome.PlacaConsultada(r.placa, r.restricao)
            // Sem base, com base de demonstração que não autoriza, ou com o
            // transporte caído: a resposta é que não deu. Jamais "sem restrição".
            ConsultaDePlaca.NaoRespondeu ->
                ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL)
        }
    }

    // ── C2: posição de um par ─────────────────────────────────────────────────

    private suspend fun consultarPosicao(indicativo: String): ActionOutcome {
        // Permissão é a única pré-condição local que faz sentido aqui.
        if (!permissaoDeLocal()) {
            return ActionOutcome.Falhou(FalhaOperacional.SEM_PERMISSAO_DE_LOCAL)
        }
        // NÃO se barra mais pela correção de GPS local. O servidor calcula a
        // distância a partir da última posição **publicada** do solicitante, não
        // da que o celular tem agora, e as duas divergem nos dois sentidos:
        //
        //  - falso negativo: dentro de um prédio o GPS local não tem correção
        //    recente e a consulta era recusada, embora o servidor pudesse
        //    respondê-la a partir da posição publicada minutos antes;
        //  - falso positivo, pior: correção local fresca com publicação atrasada
        //    (sem rede há 40 min) deixava a consulta passar, e a resposta saía
        //    afirmada como atual a partir de onde o agente **estava**.
        //
        // A idade que importa vem do servidor, e é o `BuscaDePar` que a carrega.
        return when (val b = localizarPar(indicativo)) {
            is BuscaDePar.Encontrado -> ActionOutcome.PosicaoEncontrada(b.posicao)
            // Par ausente NÃO é falha: o sistema funcionou, o par é que não está
            // localizável. Tratar como erro faria o agente duvidar do rádio.
            BuscaDePar.NaoLocalizado -> ActionOutcome.ParNaoLocalizado(indicativo)
            // Já isto É falha, e precisa soar como falha. Dizer "Alfa Dois não
            // localizado" quando a consulta está fora do ar faz o agente concluir
            // que o companheiro sumiu — e agir a partir disso.
            BuscaDePar.Indisponivel -> ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL)
            // A distância existe, mas foi medida de onde eu estava. Falar o
            // número seria pior que não responder.
            BuscaDePar.PosicaoPropriaVelha ->
                ActionOutcome.Falhou(FalhaOperacional.SEM_POSICAO_PROPRIA)
        }
    }

    // ── C3: alerta de ocorrência ──────────────────────────────────────────────

    private suspend fun alertar(o: Ocorrencia): ActionOutcome {
        // Posição é opcional no alerta, e de propósito: "tiroteio" sem GPS ainda
        // precisa sair. Exigir coordenada faria o produto recusar exatamente o
        // alerta mais urgente — o que o agente grita quando não dá tempo de nada.
        val coord = if (permissaoDeLocal()) minhaPosicao() else null
        val msg = TacticalMessage(
            recipientType = RecipientType.GROUP,
            recipient = identidade.destinoPadrao,
            agentId = identidade.agentId,
            vehiclePrefix = identidade.vehiclePrefix,
            location = o.logradouro ?: o.referencia,
            latitude = coord?.latitude,
            longitude = coord?.longitude,
            // A fala original vai junto da classificação: o tipo é metadado para
            // roteamento e prioridade, não substituto do que o agente disse.
            situation = "${o.tipo.rotulo}: ${o.textoOriginal}".take(SITUACAO_MAX),
            priority = o.prioridade.name,
            evidenceStatus = gravacaoAtual?.let { "gravando" },
        )
        return when (val d = despachante.despachar(msg)) {
            is Despacho.Enviada ->
                ActionOutcome.AlertaDisparado(o.tipo, o.prioridade, d.destinatarios)
            // Enfileirado não é "alerta disparado". O agente precisa ouvir que
            // ninguém foi avisado ainda — senão conta com um apoio que não sabe
            // que existe uma ocorrência.
            Despacho.Enfileirada -> ActionOutcome.ApoioEnfileirado
        }
    }

    // ── Cofre de evidência ────────────────────────────────────────────────────

    private suspend fun iniciarGravacao(): ActionOutcome {
        if (gravacaoAtual != null) {
            return ActionOutcome.Falhou(FalhaOperacional.GRAVACAO_JA_ATIVA)
        }
        val ctx = OccurrenceContext(
            agentId = identidade.agentId,
            unitId = identidade.unitId,
            startedAtEpochMillis = agora(),
            sampleRateHz = taxaDeAmostragemHz(),
        )
        return when (val r = cofre.beginRecording(ctx)) {
            is Result.Success -> {
                gravacaoAtual = r.value
                ActionOutcome.GravacaoIniciada(r.value.id)
            }
            is Result.Failure -> ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL)
        }
    }

    private suspend fun encerrarGravacao(): ActionOutcome {
        val handle = gravacaoAtual
            ?: return ActionOutcome.Falhou(FalhaOperacional.SEM_GRAVACAO_ATIVA)
        return when (val r = cofre.finalize(handle)) {
            is Result.Success -> {
                gravacaoAtual = null
                falhasAoFechar = 0
                ActionOutcome.GravacaoEncerrada(r.value.chain.size)
            }
            is Result.Failure -> {
                falhasAoFechar++
                // Preservar o handle é o certo na primeira falha: o cofre pode ter
                // falhado só ao escrever o manifesto, e zerar deixaria a gravação
                // órfã e impossível de fechar por voz.
                //
                // Mas preservar PARA SEMPRE era um beco sem saída. Numa falha
                // persistente — disco cheio, Keystore inacessível — `iniciar`
                // respondia "Já gravando." e `encerrar` respondia "Cofre falhou."
                // pelo resto do turno, e o agente perdia a capacidade de gravar
                // evidência sem nenhum caminho de volta por comando de voz.
                //
                // Depois de [MAX_FALHAS_AO_FECHAR] tentativas, o handle é
                // liberado. Os segmentos já gravados continuam no disco e
                // cifrados; o que se perde é o manifesto — e uma evidência sem
                // manifesto ainda pode ser periciada, enquanto um app que não grava
                // mais nada não produz evidência nenhuma.
                if (falhasAoFechar >= MAX_FALHAS_AO_FECHAR) {
                    gravacaoAtual = null
                    falhasAoFechar = 0
                }
                ActionOutcome.Falhou(FalhaOperacional.COFRE_INDISPONIVEL)
            }
        }
    }

    /**
     * Anexa um segmento à gravação em curso, se houver. Usado pelo pipeline de
     * áudio.
     *
     * O `append` roda **dentro** do mutex, não só a leitura do handle.
     *
     * A versão anterior pegava o handle sob o lock e escrevia fora dele, e o
     * pipeline de áudio anexa continuamente enquanto o agente pode dizer
     * "encerrar gravação": `finalize` e `append` corriam em paralelo sobre o mesmo
     * handle, e o segmento podia entrar **depois** do manifesto. Uma cadeia de
     * custódia com um bloco fora da cadeia é exatamente o que este módulo existe
     * para impedir.
     *
     * O custo é serializar a escrita com os comandos de voz. Aceitável: o `append`
     * é I/O curto em arquivo local, e a alternativa é evidência que um advogado
     * derruba.
     */
    suspend fun anexarEvidencia(chunk: ByteArray): AnexoDeEvidencia = mutex.withLock {
        val handle = gravacaoAtual ?: return@withLock AnexoDeEvidencia.SEM_GRAVACAO
        when (val r = cofre.append(handle, chunk)) {
            is Result.Success -> AnexoDeEvidencia.ACEITO
            is Result.Failure ->
                // O código vem do cofre; a distinção existe porque disco cheio e
                // cofre quebrado pedem coisas diferentes do agente.
                if (r.error.code == CODIGO_SEM_ESPACO) {
                    AnexoDeEvidencia.SEM_ESPACO
                } else {
                    AnexoDeEvidencia.FALHOU
                }
        }
    }

    /** `true` se há gravação aberta (para o painel e para o encerramento do app). */
    suspend fun gravando(): Boolean = mutex.withLock { gravacaoAtual != null }

    private companion object {
        /** Limite do campo de situação no template aprovado. */
        const val SITUACAO_MAX = 120

        /** Espelha `ClaryonError.Evidence("EVID_SEM_ESPACO", …)` do cofre. */
        const val CODIGO_SEM_ESPACO = "EVID_SEM_ESPACO"

        /**
         * Três tentativas de fechar o cofre antes de liberar o handle. Uma só
         * seria pouco (falha transitória de I/O acontece); sem limite, o app
         * perde a gravação de evidência pelo resto do turno.
         */
        const val MAX_FALHAS_AO_FECHAR = 3
    }
}
