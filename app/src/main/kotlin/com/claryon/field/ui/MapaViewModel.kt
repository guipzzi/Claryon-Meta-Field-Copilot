package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.agent.FalaDePosicao
import android.os.SystemClock
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.field.local.ProvedorDeLocal
import com.claryon.field.local.TransmissaoDePosicao
import com.claryon.field.radio.CanaisDoAgente
import com.claryon.field.radio.CanalDoPiloto
import com.claryon.field.mapa.TracoDoRastro
import com.claryon.agent.Rumo
import com.claryon.field.mapa.EstadoDoMapa
import com.claryon.field.mapa.MapaDePares
import com.claryon.net.HistoricoDoCanal
import com.claryon.net.RespostaDePosicao
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val _estado = MutableStateFlow(
        EstadoDoMapa.indisponivel("Abra o mapa para ver a guarnição."),
    )
    val estado: StateFlow<EstadoDoMapa> = _estado.asStateFlow()

    private var bomba: Job? = null

    /** Chamado pelo `ON_START` da tela do mapa. */
    /** Id da sessão de acesso aberta no servidor, para poder fechá-la. */
    @Volatile
    private var sessaoDeMapa: Long? = null

    /** Guardado para o fechamento, que acontece fora do laço. */
    @Volatile
    private var historicoDoMapa: HistoricoDoCanal? = null

    /**
     * Escopo do fechamento da sessão — **de aplicação, não da tela**.
     *
     * `fecharMapa()` é chamado no `ON_STOP`, quando `viewModelScope` está indo
     * embora. Fechar por lá deixaria a sessão aberta para sempre em metade das
     * saídas, e o registro diria que o agente ficou olhando o mapa indefinidamente.
     */
    private val escopoDoFechamento = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * **Sonda agora, fora do intervalo de 5 s.**
     *
     * O botão de recarregar do cabeçalho de frescor precisa de uma ação que
     * realmente recarregue: [abrirMapa] começa com `if (bomba != null) return`, e
     * ligar o botão nele daria uma função vazia com cara de ação — o mesmo gênero de
     * mentira do rótulo "na fila" que este projeto removeu por não haver fila.
     *
     * Cancela a bomba e a recria, o que dispara a primeira sondagem na hora. Não é
     * "esvaziar e recarregar": o estado atual **fica na tela** até a resposta chegar,
     * porque limpar aqui faria o mapa piscar em branco a cada toque e o agente
     * perderia a posição dos pares por um instante — num deslocamento, é o instante
     * que importa.
     *
     * Idempotente sob toque repetido: cada chamada cancela a anterior antes de
     * lançar, então não há como acumular duas bombas sondando o mesmo canal.
     */
    fun recarregar() {
        bomba?.cancel()
        bomba = null
        abrirMapa()
    }

    fun abrirMapa() {
        if (bomba != null) return
        bomba = viewModelScope.launch {
            if (!SessaoDoAgente.redeConfigurada ||
                SessaoDoAgente.tokenValido(getApplication()) == null
            ) {
                // A causa da transmissão vai junto **também aqui**: sem sessão as
                // duas metades caem, e a tela precisa dizer as duas. Medido no
                // aparelho — sem esta linha o cabeçalho acendia "fora do mapa" em
                // vermelho e não havia onde ler por quê.
                val t = TransmissaoDePosicao.estado.value
                _estado.value = EstadoDoMapa.indisponivel(
                    "Sem sessão. Entre para ver a guarnição.",
                    minhaPosicaoSobe = t.viva,
                    motivoDaMinhaPosicao = t.causa(SystemClock.elapsedRealtime()),
                )
                return@launch
            }

            val historico = HistoricoDoCanal(
                config = SessaoDoAgente.config,
                tokenDeSessao = { SessaoDoAgente.tokenValido(getApplication()) },
            )
            historicoDoMapa = historico

            // **A porta de alto volume abre uma SESSÃO, não uma linha por quadro.**
            // Ver a `0017`: o laço redesenha a cada 5 s, e registrar cada redesenho
            // daria ~720 linhas/hora por agente — afogando a consulta pontual, que é
            // a que interessa auditar. Falha aqui não fecha o mapa: o registro é
            // acessório e o agente não pode perder a guarnição de vista por causa
            // dele. Mas a falha é anotada, porque log que some em silêncio é o mesmo
            // que log nenhum.
            sessaoDeMapa = historico.abrirMapa(CanaisDoAgente.grupoCorrenteId)
                .onFailure { Log.w(TAG, "sessão de mapa não registrada", it) }
                .getOrNull()

            while (true) {
                val r = historico.posicoesDoGrupo(CanaisDoAgente.grupoCorrenteId)
                // **O rastro sobrevive ao redesenho.** `montarMapa` devolve um
                // `EstadoDoMapa` NOVO, e os campos de rastro nasceriam vazios nele —
                // o rastro carregava e era apagado em menos de cinco segundos, sem
                // erro nenhum no caminho. Foi assim que ele não apareceu na tela na
                // primeira medição.
                val anterior = _estado.value
                _estado.value = r.fold(
                    onSuccess = { lista ->
                        montarMapa(lista).copy(
                            rastro = anterior.rastro,
                            rastroDe = anterior.rastroDe,
                        )
                    },
                    onFailure = {
                        // **Receber e transmitir falham separado.** A sondagem
                        // caiu, e isso não diz nada sobre a minha posição estar
                        // subindo — a causa da transmissão vai junto para a tela
                        // não trocar uma falha pela outra.
                        val t = TransmissaoDePosicao.estado.value
                        EstadoDoMapa.indisponivel(
                            "Não foi possível ler as posições da guarnição.",
                            minhaPosicaoSobe = t.viva,
                            motivoDaMinhaPosicao = t.causa(SystemClock.elapsedRealtime()),
                        )
                    },
                )
                // Redesenhar por tempo, e não só quando chega dado: o esmaecimento
                // depende do relógio. Um par que **parou** de publicar precisa
                // esmaecer sozinho — é esse o caso que a regra existe para cobrir.
                delay(INTERVALO_DE_REDESENHO_MS)
            }
        }
    }

    /**
     * **O rastro de 30 minutos do par focado.** Camada 2 da retenção (`0019`).
     *
     * A função `rastro_do_par` existia no banco desde 19/08 e passou dias sem um
     * único chamador Kotlin — capacidade sem porta, que é o padrão que este
     * projeto já cometeu seis vezes. Aqui está a porta: tocar num par da gaveta.
     *
     * Só grandeza. Cada ponto é distância e azimute **a partir de onde eu estou
     * agora**, então o rastro reconstrói o trajeto do par *relativo a mim* — o que
     * serve para ir ao encontro dele e não serve para vigiá-lo depois. Coordenada
     * de terceiro não atravessa esta função, e `PontoDoRastro` nem tem campo onde
     * guardá-la.
     *
     * `null` limpa. Tocar de novo no mesmo par desfoca, e o rastro tem de sumir
     * junto: rastro de um par que não está mais em foco é dado de vigilância
     * pendurado na tela.
     */
    fun focarPar(indicativo: String?) {
        rastroJob?.cancel()
        rastroJob = null
        if (indicativo == null) {
            _estado.value = _estado.value.copy(rastro = emptyList(), rastroDe = null)
            return
        }
        val h = historicoDoMapa ?: run {
            // Falha nunca é silêncio: a gaveta acende o foco e nada carrega. Sem
            // isto o agente toca, não vê rastro, e não tem como saber se o par não
            // andou ou se o mapa está fechado por baixo.
            Log.w(TAG, "rastro de $indicativo pedido com o mapa fechado")
            return
        }
        rastroJob = viewModelScope.launch {
            val pontos = h.rastroDoPar(indicativo)
                .onFailure { Log.w(TAG, "rastro de $indicativo indisponível", it) }
                .getOrDefault(emptyList())
            _estado.value = _estado.value.copy(
                rastro = pontos.map { p ->
                    TracoDoRastro(
                        distanciaFalada = FalaDePosicao.distanciaFalada(p.distanciaM.toInt()),
                        rumoFalado = p.azimuteGraus?.let { Rumo.deGraus(it)?.falado }
                            // `ST_Azimuth` devolve nulo para pontos coincidentes —
                            // dupla na mesma viatura. Forçar "norte" inventaria rumo.
                            ?: "rumo indefinido",
                        idadeFalada = idadeDoTraco(p.idadeS),
                    )
                },
                rastroDe = indicativo,
            )
        }
    }

    private var rastroJob: Job? = null

    /**
     * "agora", "há 4 min". Curto de propósito: são até 30 linhas na gaveta, e
     * "posição de 4 minutos" (a forma que o marcador usa) viraria parede de texto.
     */
    private fun idadeDoTraco(segundos: Int): String = when {
        segundos < 60 -> "agora"
        else -> "há ${segundos / 60} min"
    }

    /** Chamado pelo `ON_STOP` da tela. Fecha a sondagem e descarta o espelho. */
    fun fecharMapa() {
        // **Agora HÁ o que avisar do outro lado.** A sessão de acesso fica aberta
        // até alguém fechá-la, e sessão que nunca fecha vira registro de vigilância
        // sem fim — o oposto do que a `0017` existe para provar. O escopo é o da
        // aplicação: `viewModelScope` morre junto com a tela, e é exatamente ao
        // sair da tela que este fechamento precisa acontecer.
        // **Fora do `let`.** `historicoDoMapa` era anulado DENTRO dele, e `abrirMapa`
        // tolera sessão nula de propósito ("o registro é acessório"). Quando
        // `abrir_mapa` falhava, o `HistoricoDoCanal` — com o `OkHttpClient` dentro —
        // sobrevivia à tela, e `focarPar` continuava puxando rastros de 30 minutos
        // **sem nenhuma linha de sessão no log de acesso**. Era o buraco que a `0017`
        // existe para fechar, reaberto sobre o dado mais sensível dos dois.
        val h = historicoDoMapa
        historicoDoMapa = null
        sessaoDeMapa?.let { id ->
            sessaoDeMapa = null
            if (h != null) {
                escopoDoFechamento.launch {
                    h.fecharMapa(id).onFailure { Log.w(TAG, "sessão de mapa não fechada", it) }
                }
            }
        }
        rastroJob?.cancel()
        rastroJob = null
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
        // **O estado da TRANSMISSÃO própria, lido a cada redesenho.** É o que
        // separa "ninguém publicou" de "eu não publiquei" — duas causas que a
        // resposta do servidor não distingue, porque `posicoes_do_grupo` faz
        // `cross join minha` e devolve zero linhas nos dois casos.
        val transmissao = TransmissaoDePosicao.estado.value
        val agora = SystemClock.elapsedRealtime()
        val sobe = transmissao.viva
        val causa = transmissao.causa(agora)

        val minhaIdade = lista.minOfOrNull { it.idadeDoSolicitanteS } ?: Int.MAX_VALUE
        if (lista.isNotEmpty() && minhaIdade > FalaDePosicao.IDADE_MAXIMA_S) {
            return EstadoDoMapa.indisponivel(
                "Sua posição está desatualizada. As distâncias seriam medidas do lugar errado.",
                minhaPosicaoSobe = sobe,
                motivoDaMinhaPosicao = causa,
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
            minhaPosicaoSobe = sobe,
            motivoDaMinhaPosicao = causa,
        )
    }

    override fun onCleared() {
        fecharMapa()
        super.onCleared()
    }

    private companion object {
        const val TAG = "ClaryonField"


        /** 5 s: o esmaecimento por idade precisa de relógio, não só de dado novo. */
        const val INTERVALO_DE_REDESENHO_MS = 5_000L
    }
}
