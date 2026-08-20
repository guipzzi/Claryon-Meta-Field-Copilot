package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.FalaDePosicao
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.local.ProvedorDeLocal
import com.claryon.field.radio.CanalDoPiloto
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.MapaDePares
import com.claryon.net.HistoricoDoCanal
import com.claryon.net.PublicadorDePosicao
import com.claryon.net.PublicadorDePosicaoSupabase
import com.claryon.net.RespostaDePosicao
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * **O mapa da guarnição, fora do `DiagnosticsViewModel`.**
 *
 * Primeiro corte da decomposição, e o mais limpo dos três: o mapa não
 * compartilha estado mutável com o copiloto nem com a evidência. O que ele
 * precisava e parecia bloqueio — a sessão do agente — deixou de ser campo de
 * ViewModel e virou [SessaoDoAgente], dono de processo.
 *
 * `ProvedorDeLocal` é instanciado aqui e também lá: ele é um invólucro **sem
 * estado** sobre o `LocationManager` (só `getLastKnownLocation` e checagem de
 * permissão), então duas instâncias custam um campo e não criam duas verdades.
 * Compartilhá-lo obrigaria a um quarto objeto de processo sem ganho nenhum.
 *
 * ## O que este ViewModel sustenta
 *
 * **A assinatura nasce com a tela e morre com ela.** Numa guarnição de oito,
 * manter a sondagem aberta o turno inteiro seria 8 × 8 de tráfego permanente
 * para uma tela fechada 95% do tempo. Por isso [abrirMapa]/[fecharMapa] são
 * amarrados ao ciclo de vida da tela, não ao do ViewModel.
 *
 * **Reciprocidade:** publicar a própria posição é pré-condição de ver as dos
 * outros — o servidor devolve *grandezas* (distância, rumo) e precisa saber de
 * onde medir. Quem vê é visto.
 */
class MapaViewModel(app: Application) : AndroidViewModel(app) {

    private val local = ProvedorDeLocal(app)

    private val publicador = PublicadorDePosicaoSupabase(
        config = SessaoDoAgente.config,
        tokenDeSessao = { SessaoDoAgente.tokenValido(app) },
    )

    /** Exposto para o serviço em primeiro plano, que coleta com o app fechado. */
    val publicadorDePosicao: PublicadorDePosicao get() = publicador

    private val _estado = MutableStateFlow(
        EstadoDoMapa.indisponivel("Abra o mapa para ver a guarnição."),
    )
    val estado: StateFlow<EstadoDoMapa> = _estado.asStateFlow()

    private var bomba: Job? = null

    /** Chamado pelo `ON_START` da tela do mapa. */
    fun abrirMapa() {
        if (bomba != null) return
        bomba = viewModelScope.launch {
            if (!SessaoDoAgente.redeConfigurada ||
                SessaoDoAgente.tokenValido(getApplication()) == null
            ) {
                _estado.value =
                    EstadoDoMapa.indisponivel("Sem sessão. Entre para ver a guarnição.")
                return@launch
            }

            val historico = HistoricoDoCanal(
                config = SessaoDoAgente.config,
                tokenDeSessao = { SessaoDoAgente.tokenValido(getApplication()) },
            )

            while (true) {
                val r = historico.posicoesDoGrupo(CanalDoPiloto.ID)
                _estado.value = r.fold(
                    onSuccess = { lista -> montarMapa(lista) },
                    onFailure = {
                        EstadoDoMapa.indisponivel("Não foi possível ler as posições da guarnição.")
                    },
                )
                // Redesenhar por tempo, e não só quando chega dado: o esmaecimento
                // depende do relógio. Um par que **parou** de publicar precisa
                // esmaecer sozinho — é esse o caso que a regra existe para cobrir.
                delay(INTERVALO_DE_REDESENHO_MS)
            }
        }
    }

    /** Chamado pelo `ON_STOP` da tela. Fecha a sondagem e descarta o espelho. */
    fun fecharMapa() {
        // Sondagem, não assinatura: cancelar o laço já para o tráfego por
        // completo. Não há nada aberto do outro lado para avisar.
        bomba?.cancel()
        bomba = null
        _estado.value = EstadoDoMapa.indisponivel("Abra o mapa para ver a guarnição.")
    }

    /**
     * **O mapa NÃO publica posição.** `ColetorDePosicao` é o dono único da escrita.
     *
     * Até 18/08 este arquivo publicava na abertura e a cada 5 s dentro do laço, e
     * isso causava quatro defeitos de uma vez:
     *
     * 1. **~720 escritas/hora**, contra 20–60/h do coletor — 12× a 60× mais, o que
     *    contradizia toda a economia de bateria que `PoliticaDePosicao` documenta.
     * 2. **Apagava `speed_mps`.** Passava `null` no quarto argumento, e
     *    `publicar_posicao` é UPSERT total (`0008:55`): a velocidade que o coletor
     *    tinha gravado sumia em no máximo 5 s. Com o mapa aberto, "deslocando"
     *    praticamente nunca era exibido.
     * 3. **Falsificava frescor.** Usava `ultimaPosicao()`, que aceita correção em
     *    cache de até 120 s (`ProvedorDeLocal:66`), e o servidor a carimbava com
     *    `now()` (`0008:61`). Uma medição de dois minutos atrás era republicada
     *    como `idade_s = 0`, repetidamente, para a guarnição inteira.
     * 4. **Matava a própria guarda de posição velha.** A checagem logo abaixo
     *    compara a idade do solicitante com 120 s — e ela nunca disparava, porque
     *    o laço acabava de publicar. A mensagem "sua posição está desatualizada"
     *    era inalcançável. Removendo a publicação, ela volta a existir.
     *
     * **Reciprocidade continua valendo**, e era o argumento do código antigo: o
     * servidor só devolve distância se souber de onde medir. Mas quem satisfaz isso
     * é o coletor, que publica na primeira correção e depois por cadência e
     * batimento. Se ele ainda não publicou, o mapa **diz isso** em vez de forjar
     * uma posição para si mesmo — que é a diferença entre informar e inventar.
     */

    /**
     * Monta o estado do mapa a partir das grandezas que o servidor devolveu.
     *
     * A idade da **própria** posição é verificada primeiro: as distâncias foram
     * todas medidas a partir dela, então uma posição própria velha torna a tela
     * inteira falsa — não só uma linha.
     */
    private fun montarMapa(lista: List<RespostaDePosicao>): EstadoDoMapa {
        val minhaIdade = lista.minOfOrNull { it.idadeDoSolicitanteS } ?: Int.MAX_VALUE
        if (lista.isNotEmpty() && minhaIdade > FalaDePosicao.IDADE_MAXIMA_S) {
            return EstadoDoMapa.indisponivel(
                "Sua posição está desatualizada. As distâncias seriam medidas do lugar errado.",
            )
        }
        // A origem do mapa vem da coleta local, não do servidor: o servidor
        // devolve grandezas relativas justamente para nunca ter que devolver
        // coordenada, e a única coordenada que este aparelho pode ter é a dele.
        val minha = local.ultimaPosicao()
        return MapaDePares.montarDeGrandezas(
            lista,
            assinado = true,
            minhaLatitude = minha?.latitude,
            minhaLongitude = minha?.longitude,
            meuRumoGraus = minha?.rumoGraus,
        )
    }

    override fun onCleared() {
        fecharMapa()
        super.onCleared()
    }

    private companion object {

        /** 5 s: o esmaecimento por idade precisa de relógio, não só de dado novo. */
        const val INTERVALO_DE_REDESENHO_MS = 5_000L
    }
}
