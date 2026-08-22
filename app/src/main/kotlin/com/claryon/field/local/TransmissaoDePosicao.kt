package com.claryon.field.local

import com.claryon.agent.PlanoDePosicao
import com.claryon.agent.PoliticaDePosicao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Por que a coleta local **não** está de pé. Nunca um booleano solto: um
 * indicador apagado sem causa faz o agente tentar de novo no meio da rua em vez
 * de trocar de plano.
 */
enum class MotivoDaColeta {
    /** Assinada e viva. */
    DE_PE,

    /** Nem `FINE` nem `COARSE` concedidas. */
    SEM_PERMISSAO,

    /** Nenhum provedor **ativo** no aparelho — GPS e rede desligados nos ajustes. */
    SEM_PROVEDOR,

    /** `requestLocationUpdates` lançou. Raro, e não pode virar silêncio. */
    ASSINATURA_RECUSADA,

    /** Ninguém ligou a coleta ainda, ou o serviço morreu. */
    PARADA,
}

/**
 * **O estado da TRANSMISSÃO da posição própria — o lado que não tinha instrumento.**
 *
 * A regra do produto é *falha nunca é silêncio*, e ela estava bem implementada na
 * camada de FALA e vazava na de estado. Uma auditoria mediu **delta de zero
 * linhas** em `agent_positions` durante 20 min de aplicativo aberto, com GPS
 * injetado e o Android confirmando a entrega das correções — e nada em lugar
 * nenhum da interface dizia isso. Pior: a gaveta do mapa lia a lista vazia e
 * escrevia *"Ninguém publicando · Nenhum par do talk group está enviando posição
 * agora"*. A causa era do portador e a frase acusava a guarnição.
 *
 * Este é o objeto que fecha esse buraco. Ele é escrito por dois donos e só por
 * eles — [ColetorDePosicao], que sabe do GPS e do resultado de cada POST, e o
 * `CopilotService`, que sabe do turno — e é lido pela tela do mapa e pelo
 * relatório de prontidão do perfil.
 *
 * **Por que um objeto de processo e não um campo de ViewModel.** A coleta vive no
 * serviço em primeiro plano e precisa sobreviver à tela: o agente guarda o
 * celular no bolso e a guarnição continua vendo onde ele está. Um estado preso ao
 * `viewModelScope` morreria com a composição — e o que a tela do mapa mostraria ao
 * reabrir seria o estado inicial, não o real. Mesmo recorte de
 * `CopilotService.estadoDaEscuta`, pela mesma razão.
 */
object TransmissaoDePosicao {

    private val _estado = MutableStateFlow(EstadoDaTransmissao())

    /** O estado corrente. A tela do mapa e o perfil observam. */
    val estado: StateFlow<EstadoDaTransmissao> = _estado.asStateFlow()

    /** O turno abriu (ou fechou). Sem turno o servidor recusa toda publicação. */
    fun turno(aberto: Boolean) = _estado.update { it.copy(turnoAberto = aberto) }

    /**
     * A coleta subiu, com a tolerância de silêncio que **este** plano justifica.
     *
     * A tolerância entra junto porque ela é uma propriedade do modo, não uma
     * constante: em Standby a correção chega de 5 em 5 minutos e 4 min de silêncio
     * é o funcionamento normal; em Ativo, 4 min já é o dobro do batimento e
     * significa que alguma coisa parou.
     */
    fun coletaDePe(plano: PlanoDePosicao) = _estado.update {
        it.copy(
            motivoDaColeta = MotivoDaColeta.DE_PE,
            provedorAtivo = true,
            silencioToleradoMs = silencioToleradoPara(plano),
        )
    }

    /** A coleta não subiu, e o porquê vai junto. */
    fun coletaParada(motivo: MotivoDaColeta) = _estado.update { it.copy(motivoDaColeta = motivo) }

    /**
     * Chegou correção do provedor — **mesmo a que a porta de qualidade vai
     * recusar**. Correção ruim ainda é prova de que o receptor está vivo, e o que
     * este carimbo mede é justamente o silêncio dele.
     */
    fun correcaoRecebida(agoraMs: Long) = _estado.update {
        it.copy(ultimaCorrecaoMs = agoraMs, provedorAtivo = true)
    }

    /** `onProviderDisabled`: o agente desligou o GPS nos ajustes. */
    fun provedorDesligado() = _estado.update { it.copy(provedorAtivo = false) }

    /** O POST subiu. É o único ponto que move o carimbo do caminho feliz. */
    fun publicacaoOk(agoraMs: Long) = _estado.update {
        it.copy(publicando = true, houveTentativa = true, ultimaPublicacaoOkMs = agoraMs)
    }

    /** O POST falhou. `publicando` cai, e a tela passa a dizer por quê. */
    fun publicacaoFalhou() = _estado.update {
        it.copy(publicando = false, houveTentativa = true)
    }

    /** Só para teste: devolve o objeto de processo ao estado de quem nunca ligou nada. */
    fun zerar() {
        _estado.value = EstadoDaTransmissao()
    }

    /**
     * **Dois batimentos.** É o mesmo limiar por onde o marcador de qualquer par
     * começa a esmaecer para os outros: em Ativo o batimento efetivo é 60 s, o
     * dobro é 120 s, e 120 s é [PoliticaDePosicao.OBSOLETO_S]. Ou seja, o agente é
     * avisado no instante exato em que ele começa a esmaecer no mapa da guarnição
     * — e não antes, senão o aviso acenderia no funcionamento normal e ensinaria a
     * ser ignorado.
     */
    private fun silencioToleradoPara(plano: PlanoDePosicao): Long = 2 * plano.batimentoEfetivoMs
}

/**
 * O retrato da transmissão num instante. Puro: nenhuma dependência de Android,
 * nenhum relógio próprio — quem pergunta traz o `agoraMs`.
 */
data class EstadoDaTransmissao(
    val motivoDaColeta: MotivoDaColeta = MotivoDaColeta.PARADA,
    val turnoAberto: Boolean = false,
    /** `false` depois de `onProviderDisabled`. */
    val provedorAtivo: Boolean = true,
    /** Última correção recebida do provedor, em `elapsedRealtime`. */
    val ultimaCorrecaoMs: Long? = null,
    /** Último POST que o servidor aceitou. */
    val ultimaPublicacaoOkMs: Long? = null,
    /** Já houve ao menos uma tentativa de POST. Distingue "falhou" de "ainda não tentou". */
    val houveTentativa: Boolean = false,
    /** O resultado do último POST. É o `publicando()` do publicador, refletido aqui. */
    val publicando: Boolean = false,
    /** Quanto silêncio do provedor este modo tolera antes de virar aviso. */
    val silencioToleradoMs: Long = 2 * 60 * 1000L,
) {

    val coletando: Boolean get() = motivoDaColeta == MotivoDaColeta.DE_PE

    /**
     * **A minha posição está no mapa dos outros?**
     *
     * `publicando && coletando`, e os dois são necessários: publicar sem coletar é
     * um carimbo velho que não vai mais se mover, e coletar sem publicar é o GPS
     * acordando para nada — que é exatamente o defeito que esta camada existe para
     * tornar visível.
     */
    val viva: Boolean get() = publicando && coletando

    /**
     * A causa, **derivada**, nunca fixa.
     *
     * A ordem não é arbitrária, e cada degrau é a causa-raiz do seguinte:
     *
     *  1. **Turno.** Sem turno o servidor recusa toda publicação (`0019`), então
     *     nada abaixo importa.
     *  2. **Coleta.** Sem assinatura não chega correção, e sem correção não há o
     *     que publicar.
     *  3. **Provedor.** GPS desligado ou mudo: as tentativas param, e o resultado
     *     do último POST — que pode ter sido um sucesso de minutos atrás —
     *     continuaria dizendo que está tudo bem.
     *  4. **Rede.** Chega correção, o POST sai e o servidor não aceita.
     *  5. **O caminho feliz**, que também é uma frase: "está subindo" precisa
     *     dizer *quando* subiu, senão é o mesmo tipo de afirmação sem lastro que
     *     o resto deste arquivo existe para eliminar.
     */
    fun causa(agoraMs: Long): String = when {
        !turnoAberto -> "Sua posição não sobe: o turno não abriu."

        motivoDaColeta == MotivoDaColeta.SEM_PERMISSAO ->
            "Sua posição não sobe: sem permissão de localização."

        motivoDaColeta == MotivoDaColeta.SEM_PROVEDOR ->
            "Sua posição não sobe: GPS e rede desligados nos ajustes."

        motivoDaColeta == MotivoDaColeta.ASSINATURA_RECUSADA ->
            "Sua posição não sobe: o sistema recusou a assinatura de GPS."

        motivoDaColeta == MotivoDaColeta.PARADA ->
            "Sua posição não sobe: a coleta não está ligada."

        !provedorAtivo -> "Sua posição não sobe: o GPS foi desligado."

        ultimaCorrecaoMs == null -> "Aguardando a primeira correção de GPS."

        agoraMs - ultimaCorrecaoMs > silencioToleradoMs ->
            "Sem correção de GPS há ${haQuantoTempo(agoraMs - ultimaCorrecaoMs)}."

        houveTentativa && !publicando -> "Sua posição não sobe: sem rede."

        ultimaPublicacaoOkMs != null ->
            "Última posição enviada há ${haQuantoTempo(agoraMs - ultimaPublicacaoOkMs)}."

        else -> "Aguardando a primeira publicação."
    }
}

/**
 * "40 s", "4 min", "2 h".
 *
 * Sem "0 s": zero sugere instantâneo, e o que o agente lê como instantâneo ele
 * usa como certeza. Abaixo de 15 s a frase inteira muda de forma no chamador; aqui
 * o piso é 1 s.
 */
internal fun haQuantoTempo(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(1L)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}
