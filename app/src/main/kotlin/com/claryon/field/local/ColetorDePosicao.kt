package com.claryon.field.local

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.claryon.agent.ModoOperacao
import com.claryon.agent.Correcao
import com.claryon.agent.PlanoDePosicao
import com.claryon.agent.PoliticaDePosicao
import com.claryon.agent.PortaDeCorrecao
import com.claryon.agent.Veredito
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **Coleta de posição em segundo plano — o compartilhamento contínuo.**
 *
 * É o que faz a guarnição ver o agente com a tela apagada, no bolso, o turno
 * inteiro. O modelo é o do compartilhamento de localização ao vivo que as pessoas
 * já conhecem: liga uma vez, vale até o fim do turno.
 *
 * Quatro decisões carregam quase toda a economia de bateria, e nenhuma é
 * micro-otimização:
 *
 *  1. **O sistema operacional faz o estrangulamento, não um laço nosso.**
 *     `requestLocationUpdates(minTime, minDistance)` deixa o Android decidir
 *     quando acordar o rádio de GPS. Um `while(true) { getLastLocation(); delay() }`
 *     mantém o processo desperto e ignora o *batching* que o próprio sistema faz
 *     com outros apps — é a diferença entre pegar carona num despertar que já ia
 *     acontecer e criar um despertar novo.
 *
 *  2. **Provedor conforme o modo.** Em Standby usa a rede (torre e Wi-Fi), que
 *     custa quase nada e erra 100–1000 m — precisão irrelevante para um agente em
 *     pausa. GPS só em Ativo e Ocorrência, que é quando metro importa.
 *
 *  3. **Deslocamento como gatilho, não só tempo.** Agente parado em ponto fixo —
 *     boa parte do turno — quase não publica. O filtro mora em [aoReceber]: o
 *     `minDistance` do `requestLocationUpdates` fica em **zero**, de propósito.
 *
 *     Ele já esteve preenchido, e por 20/08 esta lista dizia que isso fazia "o
 *     próprio sistema não nos acordar, então parado custa zero". Custava mais que
 *     zero: custava o batimento inteiro. A moldura é explícita — *"if a potential
 *     location update is closer to the last location update than the minimum
 *     update distance, then the potential location update will not occur"* — então
 *     um agente parado **não recebia callback nenhum**, `aoReceber` nunca rodava,
 *     e a linha que testa o batimento era inalcançável. O parágrafo seguinte deste
 *     mesmo KDoc afirmava que o batimento existia para o agente parado. Os dois
 *     não podiam ser verdade, e o errado era este.
 *
 *     Quem sofria mais era o modo Ocorrência: o agente chega no local e fica
 *     parado, que é exatamente quando a guarnição precisa saber onde ele está — e
 *     era aí que ele esmaecia aos 2 min e virava "antigo" aos 10.
 *
 *     O que se perde com `minDistance = 0` é a supressão da ENTREGA, não o ciclo
 *     do rádio de GPS, que é governado por `minTime`. Para um agente em
 *     movimento, o tráfego no fio é idêntico ao de antes: o mesmo filtro, no
 *     mesmo metro, só que num lugar onde o batimento também pode votar.
 *
 *  4. **Posição NUNCA vai para fila offline.** Se a publicação falha, a posição é
 *     descartada e a próxima correção carrega o dado novo. Isto contraria o padrão
 *     do resto do produto — mensagem e alerta vão para a `outbox` durável — e é
 *     deliberado: uma posição de dez minutos atrás entregue agora não é
 *     informação atrasada, é **informação errada**. O mapa a mostraria como atual.
 *
 * O batimento existe para o caso do agente parado: sem ele, quem não se move
 * some do mapa por obsolescência, e companheiro que some parece em perigo.
 *
 * ## O que 21/08 corrigiu aqui, e por que tinha de ser corrigido junto
 *
 * Uma auditoria mediu **delta de zero linhas** em `agent_positions` em 20 min de
 * aplicativo aberto, com GPS injetado e o Android confirmando a entrega das
 * correções. Três defeitos desta classe conspiravam para que isso não aparecesse
 * em lugar nenhum:
 *
 *  - **O `onFailure` do envio era código morto.** Ele só dispararia se `publicar`
 *    LANÇASSE, e o publicador engolia tudo em `getOrDefault(false)` e devolvia
 *    `Unit`. A linha *"publicação de posição falhou"* era inalcançável, e nenhuma
 *    das 20 falhas apareceu no `logcat`. Agora [publicar] devolve **`Boolean`**, e
 *    o resultado é lido.
 *  - **O batimento avançava na falha.** `ultimaPublicada` e `ultimaPublicacaoMs`
 *    eram escritos ANTES do `launch`, então uma publicação que fracassou empurrava
 *    o relógio exatamente como uma que subiu — e a próxima correção era barrada
 *    pelo filtro de deslocamento como se a anterior tivesse chegado. Agora o
 *    carimbo é **cometido só no sucesso**.
 *  - **O provedor podia cair sem ninguém saber.** O ouvinte era a lambda SAM
 *    `LocationListener { ... }`, que implementa só `onLocationChanged`;
 *    `onProviderDisabled` ficava no default vazio. Medido: 70 s de silêncio
 *    absoluto com o GPS desligado. Agora o ouvinte é um objeto completo e a queda
 *    do provedor vira estado em [TransmissaoDePosicao].
 *
 * Nada disso vira informação sozinho: é [TransmissaoDePosicao] quem leva estes
 * três estados até a tela do mapa e o relatório de prontidão.
 */
class ColetorDePosicao(
    private val escopo: CoroutineScope,
    /**
     * Publica no servidor. Recebe também a velocidade, que alimenta "deslocando",
     * e o `elapsedRealtimeNanos` da correção, de onde sai a idade real.
     *
     * **Devolve se o servidor aceitou**, e este `Boolean` é a correção do defeito
     * mais caro da classe. Enquanto era `Unit`, o único jeito de a falha aparecer
     * aqui era o publicador lançar — e ele nunca lança.
     */
    private val publicar: suspend (
        lat: Double,
        lon: Double,
        precisaoM: Float,
        velocidadeMs: Float?,
        nanosDaCorrecao: Long,
    ) -> Boolean,
    private val fonte: FonteDeCorrecoes,
    private val agoraMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /**
     * Recriada a cada `ajustarPara`: a porta guarda a última correção aceita, e
     * mudar de provedor troca a escala de precisão inteira (GPS 8 m ↔ rede
     * 800 m). Carregar a referência do GPS para dentro do Standby faria a
     * primeira correção de rede ser recusada por "degradação" três vezes seguidas
     * até a válvula ceder — recusa correta pela regra, errada pelo mundo.
     */
    private var porta = PortaDeCorrecao()

    private var ouvinte: LocationListener? = null
    private var modoAtual: ModoOperacao? = null
    private var mapaVisivelAtual = false

    /**
     * **Só avançam em SUCESSO.** `@Volatile` porque o callback do provedor roda na
     * thread do looper e a confirmação do POST chega pela corrotina.
     */
    @Volatile
    private var ultimaPublicada: Location? = null

    @Volatile
    private var ultimaPublicacaoMs = 0L

    /**
     * Uma publicação por vez.
     *
     * Sem isto, mover o carimbo para depois do POST abriria a porta que ele
     * fechava: com o envio demorando, cada correção nova passaria pelo filtro de
     * deslocamento (que compara com um `ultimaPublicada` ainda não atualizado) e
     * dispararia outro POST. Numa rede ruim — que é justamente quando o envio
     * demora — isso viraria uma rajada.
     *
     * Descartar a correção nova em vez de enfileirá-la é a regra 4 do KDoc da
     * classe, aplicada aqui: a próxima correção carrega dado melhor.
     */
    private val envioEmVoo = AtomicBoolean(false)

    /**
     * `true` enquanto a coleta está de pé.
     *
     * O KDoc anterior dizia *"Alimenta a prontidão no perfil"* e era **mentira**:
     * `grep` do símbolo devolvia zero chamadores em `src/main`. Quem alimenta a
     * prontidão é [TransmissaoDePosicao], que esta classe escreve a cada mudança
     * de estado; esta propriedade sobrou para o próprio `ajustarPara` decidir se
     * precisa reconfigurar.
     */
    val coletando: Boolean get() = ouvinte != null

    /**
     * Liga ou reconfigura a coleta para [modo].
     *
     * Reconfigurar em vez de acumular: `cancelar` antes de qualquer nova
     * assinatura. Sem isso, trocar de modo três vezes deixaria três assinaturas
     * vivas, cada uma acordando o GPS na sua cadência — e o consumo viraria a
     * soma delas.
     *
     * **Toda saída antecipada anota a causa** em [TransmissaoDePosicao]. Antes,
     * as três eram `Log.w` e `return`: no aparelho, a coleta simplesmente não
     * existia e a interface não tinha como saber disso.
     */
    fun ajustarPara(modo: ModoOperacao, mapaVisivel: Boolean) {
        if (!fonte.temPermissao()) {
            Log.w(TAG, "sem permissão de localização — coleta não sobe")
            TransmissaoDePosicao.coletaParada(MotivoDaColeta.SEM_PERMISSAO)
            return
        }
        if (modo == modoAtual && mapaVisivel == mapaVisivelAtual && coletando) return

        parar()
        val plano = PoliticaDePosicao.planoPara(modo, mapaVisivel)
        val provedor = provedorPara(modo) ?: run {
            Log.w(TAG, "nenhum provedor de localização disponível")
            TransmissaoDePosicao.coletaParada(MotivoDaColeta.SEM_PROVEDOR)
            return
        }

        val novo = OuvinteDoProvedor(plano)
        if (!fonte.assinar(provedor, plano.intervaloMs, novo)) {
            Log.w(TAG, "assinatura de correções recusada pelo sistema")
            TransmissaoDePosicao.coletaParada(MotivoDaColeta.ASSINATURA_RECUSADA)
            return
        }

        porta = PortaDeCorrecao()
        ouvinte = novo
        modoAtual = modo
        mapaVisivelAtual = mapaVisivel
        TransmissaoDePosicao.coletaDePe(plano)
        Log.i(
            TAG,
            "coleta em $modo por $provedor: ${plano.intervaloMs}ms, publica a cada " +
                "${plano.deslocamentoMinimoM}m ou ${plano.batimentoEfetivoMs / 1000}s parado",
        )
    }

    fun parar() {
        ouvinte?.let { fonte.cancelar(it) }
        ouvinte = null
        modoAtual = null
        envioEmVoo.set(false)
        TransmissaoDePosicao.coletaParada(MotivoDaColeta.PARADA)
    }

    /**
     * O ouvinte **completo**, e não a lambda SAM.
     *
     * `LocationListener { local -> ... }` implementa só `onLocationChanged`; os
     * outros três métodos ficam no default vazio da interface. Com o GPS desligado
     * nos ajustes, o Android chama `onProviderDisabled` e mais nada — e o app
     * media 70 s de silêncio absoluto, sem uma linha de log e sem nenhuma mudança
     * na tela. O agente sumia do mapa da guarnição achando que estava visível.
     */
    private inner class OuvinteDoProvedor(private val plano: PlanoDePosicao) : LocationListener {

        override fun onLocationChanged(local: Location) = aoReceber(local, plano)

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "provedor $provider DESLIGADO — a posição para de subir")
            TransmissaoDePosicao.provedorDesligado()
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "provedor $provider religado")
            // Sem correção ainda: só o provedor voltou. `correcaoRecebida` é quem
            // move o carimbo, e ela só é chamada quando um ponto chega de verdade.
            TransmissaoDePosicao.correcaoRecebida(agoraMs())
        }
    }

    private fun aoReceber(local: Location, plano: PlanoDePosicao) {
        if (!coordenadaValida(local)) return

        // **Antes da porta de qualidade, de propósito.** Uma correção que vai ser
        // recusada por imprecisão ainda é prova de que o receptor está vivo, e o
        // que este carimbo mede é o SILÊNCIO do provedor. Anotar só as aceitas
        // faria "GPS ruim" ser reportado como "GPS mudo" — duas causas diferentes,
        // duas ações diferentes.
        TransmissaoDePosicao.correcaoRecebida(agoraMs())

        // **A porta de qualidade vem antes da porta de cadência**, e a ordem
        // importa: julgar a cadência primeiro faria uma correção-lixo "resetar" o
        // relógio do batimento sem nunca ser publicada, e o agente sumiria do mapa
        // exatamente enquanto o GPS estivesse ruim — que é quando ele mais precisa
        // aparecer.
        val correcao = Correcao(
            latitude = local.latitude,
            longitude = local.longitude,
            precisaoM = if (local.hasAccuracy()) local.accuracy else Correcao.PRECISAO_DESCONHECIDA,
            nanos = local.elapsedRealtimeNanos,
        )
        when (val v = porta.avaliar(correcao)) {
            is Veredito.Recusada -> {
                // Recusa silenciosa é a pior: some do mapa sem ninguém saber por quê.
                Log.i(TAG, "correção recusada (${v.motivo}): ${v.detalhe}")
                return
            }
            Veredito.Aceita -> Unit
        }

        val anterior = ultimaPublicada
        val idadeDaUltima = agoraMs() - ultimaPublicacaoMs
        val andou = anterior == null ||
            anterior.distanceTo(local) >= plano.deslocamentoMinimoM
        val batimentoVencido = idadeDaUltima >= plano.batimentoMs

        // Publicar só quando andou **ou** quando o batimento venceu. O filtro de
        // deslocamento mora aqui — e não mais no `requestLocationUpdates` — porque
        // lá ele suprimia o callback inteiro e o batimento nunca era avaliado.
        // Para um agente EM MOVIMENTO o comportamento no fio é idêntico ao de
        // antes; a diferença aparece só quando ele para, que é o caso que o
        // batimento existe para cobrir.
        if (!andou && !batimentoVencido) return

        if (!envioEmVoo.compareAndSet(false, true)) {
            Log.i(TAG, "publicação anterior ainda em voo — descartando esta correção")
            return
        }

        escopo.launch {
            val ok = runCatching {
                publicar(
                    local.latitude,
                    local.longitude,
                    if (local.hasAccuracy()) local.accuracy else Float.MAX_VALUE,
                    if (local.hasSpeed()) local.speed else null,
                    // A idade real da correção. O servidor faz `now() - idade`;
                    // ver `0020_idade_real_da_correcao.sql` para por que não pode
                    // ser um instante.
                    local.elapsedRealtimeNanos,
                )
            }.getOrElse {
                Log.w(TAG, "publicação de posição lançou: ${it.message}")
                false
            }

            if (ok) {
                // **O carimbo só avança aqui.** Avançá-lo antes do envio fazia uma
                // publicação fracassada empurrar o relógio do batimento igual a uma
                // que subiu — e a correção seguinte era barrada pelo filtro de
                // deslocamento contra um ponto que o servidor nunca recebeu.
                ultimaPublicada = local
                ultimaPublicacaoMs = agoraMs()
                TransmissaoDePosicao.publicacaoOk(agoraMs())
            } else {
                // Falhou: **descarta**. A próxima correção carrega dado novo, e uma
                // posição velha entregue depois não é informação atrasada — é
                // informação errada, que o mapa mostraria como atual. Mas descartar
                // não é calar: o estado cai, e a tela passa a dizer "sem rede".
                Log.w(TAG, "publicação de posição recusada, descartando — o batimento NÃO avança")
                TransmissaoDePosicao.publicacaoFalhou()
            }
            envioEmVoo.set(false)
        }
    }

    /**
     * Em Standby, rede. Em operação, GPS.
     *
     * O provedor de rede consome perto de zero — resolve por torre e Wi-Fi, sem
     * ligar o receptor — e erra de 100 m a 1 km. Para um agente em pausa isso é
     * suficiente: a guarnição precisa saber o setor, não a esquina.
     */
    private fun provedorPara(modo: ModoOperacao): String? {
        val preferido = when (modo) {
            ModoOperacao.STANDBY -> LocationManager.NETWORK_PROVIDER
            ModoOperacao.ATIVO, ModoOperacao.OCORRENCIA -> LocationManager.GPS_PROVIDER
        }
        val disponiveis = fonte.provedoresAtivos()
        return when {
            preferido in disponiveis -> preferido
            // Degrada em vez de sumir: sem GPS (garagem, subsolo), a posição de
            // rede ainda coloca o agente no mapa. Some do mapa é o pior estado.
            LocationManager.GPS_PROVIDER in disponiveis -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in disponiveis -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun coordenadaValida(l: Location): Boolean =
        l.latitude.isFinite() && l.longitude.isFinite() &&
            l.latitude in -90.0..90.0 && l.longitude in -180.0..180.0

    private companion object {
        const val TAG = "ClaryonField"
    }
}
