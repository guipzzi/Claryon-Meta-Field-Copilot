package com.claryon.field.agent

import com.claryon.agent.ActionOutcome
import com.claryon.agent.FalhaOperacional
import com.claryon.agent.Intent
import com.claryon.agent.IntentExecutor
import com.claryon.agent.ModoOperacao
import com.claryon.agent.BuscaDePar
import com.claryon.agent.Ocorrencia
import com.claryon.agent.Prioridade
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
 *  3. **Não inventa.** Onde a capacidade não existe (consulta a base oficial
 *     está fora do escopo), devolve [FalhaOperacional.CONSULTA_INDISPONIVEL] em
 *     vez de um resultado plausível.
 */
class ClaryonIntentExecutor(
    private val cofre: EvidenceVault,
    private val despachante: TacticalDispatcher,
    private val identidade: Identidade,
    private val agora: () -> Long,
    private val aoTrocarModo: suspend (ModoOperacao) -> Unit,
    /**
     * Onde estou. `null` = sem correção de GPS **ou** sem permissão — a distinção
     * é feita por [permissaoDeLocal], porque as duas causas exigem recuperações
     * diferentes do agente (esperar sinal × abrir os ajustes).
     */
    private val minhaPosicao: suspend () -> Coordenada? = { null },
    /** Onde está um par, já relativo a mim. Ver [BuscaDePar]. */
    private val localizarPar: suspend (String) -> BuscaDePar = { BuscaDePar.Indisponivel },
    private val permissaoDeLocal: () -> Boolean = { true },
) : IntentExecutor {

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

        is Intent.ConsultarPlaca ->
            // Consulta a bases oficiais (Detran/Sinesp) está fora do escopo por
            // dependência externa inviável no prazo. Sem base, a resposta honesta
            // é dizer que não dá — jamais um "sem restrição" inventado, que num
            // contexto de abordagem seria uma informação de segurança falsa.
            ActionOutcome.Falhou(FalhaOperacional.CONSULTA_INDISPONIVEL)

        // C2 — consulta de posição. Devolve distância, rumo e estado; a coordenada
        // bruta do par **nunca** chega ao aparelho de outro agente.
        is Intent.ConsultarPosicao -> consultarPosicao(intent.indicativo)

        // C3 — alerta de ocorrência classificada.
        is Intent.AlertarOcorrencia -> alertar(intent.ocorrencia)

        is Intent.TrocarModo -> {
            aoTrocarModo(intent.modo)
            ActionOutcome.ModoTrocado(intent.modo)
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
    suspend fun anexarEvidencia(chunk: ByteArray): Boolean = mutex.withLock {
        val handle = gravacaoAtual ?: return@withLock false
        cofre.append(handle, chunk) is Result.Success
    }

    /** `true` se há gravação aberta (para o painel e para o encerramento do app). */
    suspend fun gravando(): Boolean = mutex.withLock { gravacaoAtual != null }

    private companion object {
        /** Limite do campo de situação no template aprovado. */
        const val SITUACAO_MAX = 120

        /**
         * Três tentativas de fechar o cofre antes de liberar o handle. Uma só
         * seria pouco (falha transitória de I/O acontece); sem limite, o app
         * perde a gravação de evidência pelo resto do turno.
         */
        const val MAX_FALHAS_AO_FECHAR = 3
    }
}
