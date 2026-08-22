package com.claryon.field.local

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.claryon.agent.ModoOperacao
import com.claryon.agent.PoliticaDePosicao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **A classe que decidia se a posição sobe, e não tinha um único teste JVM.**
 *
 * Ela não era testável por acidente de construção: `LocationManager` não é
 * instanciável fora do sistema — construtor oculto, ausente do `android.jar` — e
 * o coletor falava direto com ele. O que ficava sem cobertura era exatamente o
 * caminho de FALHA, que é o único que interessa aqui: o que acontece quando o POST
 * não sobe, quando o provedor cai, quando não há permissão. [FonteDeCorrecoes] é a
 * costura que abriu esses caminhos.
 *
 * Uma auditoria mediu **delta de zero linhas** em `agent_positions` em 20 min de
 * aplicativo aberto, com GPS injetado e o Android confirmando a entrega das
 * correções. Três defeitos desta classe conspiravam, e cada um tem contra-teste
 * abaixo — o critério é sempre o mesmo: **se o teste passaria com o defeito de
 * volta, ele não testa o defeito.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColetorDePosicaoTest {

    // ── Bancada ───────────────────────────────────────────────────────────────

    /**
     * A fonte falsa. Guarda o ouvinte para o teste poder ser o Android: entregar
     * correção, derrubar o provedor, religá-lo.
     */
    private class FonteFalsa(
        var permissao: Boolean = true,
        var provedores: List<String> = listOf(LocationManager.GPS_PROVIDER),
        var aceitaAssinatura: Boolean = true,
    ) : FonteDeCorrecoes {
        var ouvinte: LocationListener? = null
        var cancelamentos = 0

        override fun temPermissao() = permissao
        override fun provedoresAtivos() = provedores
        override fun assinar(provedor: String, intervaloMs: Long, ouvinte: LocationListener): Boolean {
            if (!aceitaAssinatura) return false
            this.ouvinte = ouvinte
            return true
        }

        override fun cancelar(ouvinte: LocationListener) {
            cancelamentos++
            if (this.ouvinte === ouvinte) this.ouvinte = null
        }
    }

    /**
     * `Location` é subclassificável no `android.jar` mockável (`returnDefaultValues`),
     * e `distanceTo` é implementado de verdade porque o filtro de deslocamento
     * depende dele — um `distanceTo` devolvendo o zero padrão faria o filtro nunca
     * disparar e os testes mediriam outra coisa.
     */
    private fun correcao(
        lat: Double = -16.6799,
        lon: Double = -49.2550,
        precisao: Float = 8f,
        nanos: Long = 1_000_000_000L,
        velocidade: Float? = null,
    ): Location = object : Location("teste") {
        override fun getLatitude() = lat
        override fun getLongitude() = lon
        override fun hasAccuracy() = true
        override fun getAccuracy() = precisao
        override fun getElapsedRealtimeNanos() = nanos
        override fun hasSpeed() = velocidade != null
        override fun getSpeed() = velocidade ?: 0f
        override fun distanceTo(dest: Location): Float {
            // Haversine, o mesmo de `PortaDeCorrecao.distanciaM`.
            val rad = Math.PI / 180.0
            val dLat = (dest.latitude - latitude) * rad
            val dLon = (dest.longitude - longitude) * rad
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(latitude * rad) * Math.cos(dest.latitude * rad) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return (2 * 6_371_008.8 * Math.asin(Math.min(1.0, Math.sqrt(h)))).toFloat()
        }
    }

    /** Relógio manual: o batimento é medido em minutos e nenhum teste espera. */
    private var relogio = 0L

    private val publicacoes = mutableListOf<Triple<Double, Double, Long>>()
    private var proximoResultado = true

    private fun coletor(fonte: FonteFalsa) = ColetorDePosicao(
        // `UnconfinedTestDispatcher` faz o `launch` rodar inline: a confirmação do
        // POST chega antes de o teste seguir, que é o que permite medir o carimbo.
        escopo = CoroutineScope(UnconfinedTestDispatcher()),
        publicar = { lat, lon, _, _, nanos ->
            publicacoes += Triple(lat, lon, nanos)
            proximoResultado
        },
        fonte = fonte,
        agoraMs = { relogio },
    )

    @Before
    fun limpar() {
        TransmissaoDePosicao.zerar()
        // **O turno não é do coletor.** Quem o abre é o `CopilotService`, e ele é a
        // primeira causa na precedência — sem esta linha toda causa medida aqui
        // sairia "o turno não abriu" e os testes mediriam a precedência em vez do
        // que prometem no nome.
        TransmissaoDePosicao.turno(true)
        publicacoes.clear()
        proximoResultado = true
        relogio = 0L
    }

    @After
    fun devolver() = TransmissaoDePosicao.zerar()

    private val plano get() = PoliticaDePosicao.planoPara(ModoOperacao.ATIVO, mapaVisivel = false)

    // ── Defeito 2: o `onFailure` era código morto ─────────────────────────────

    /**
     * **Contra-teste do retorno.** O `publicar` era `suspend (...) -> Unit` e o
     * `onFailure` do `runCatching` só dispararia se ele LANÇASSE — coisa que o
     * publicador nunca faz, porque engole tudo em `getOrDefault(false)`. A linha
     * "publicação de posição falhou" era **inalcançável**, e nenhuma das 20 falhas
     * da auditoria apareceu no `logcat`.
     *
     * Com o defeito de volta (assinatura `-> Unit`), este teste não compila — que é
     * a forma mais forte de contra-teste que existe. Com o retorno mas sem a
     * leitura dele, `publicando` ficaria `true` e a asserção quebra.
     */
    @Test
    fun publicacaoRecusada_viraEstado_naoSilencio() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        proximoResultado = false
        fonte.ouvinte!!.onLocationChanged(correcao())

        assertEquals("a tentativa aconteceu", 1, publicacoes.size)
        val estado = TransmissaoDePosicao.estado.value
        assertFalse("o servidor recusou, e o estado tem de dizer isso", estado.publicando)
        assertTrue("houve tentativa — 'falhou' não é 'ainda não tentou'", estado.houveTentativa)
        assertTrue(
            "a causa precisa chegar à tela: ${estado.causa(relogio)}",
            estado.causa(relogio).contains("sem rede"),
        )
    }

    /** O mesmo caminho no sucesso — as duas rodadas têm de DIFERIR. */
    @Test
    fun publicacaoAceita_viraOCaminhoFeliz() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        relogio = 500_000L
        proximoResultado = true
        fonte.ouvinte!!.onLocationChanged(correcao())

        val estado = TransmissaoDePosicao.estado.value
        assertTrue(estado.publicando)
        assertEquals(500_000L, estado.ultimaPublicacaoOkMs)
        assertTrue(estado.viva)
        assertTrue(
            "veio: ${estado.causa(relogio + 40_000L)}",
            estado.causa(relogio + 40_000L).contains("Última posição enviada há 40 s"),
        )
    }

    // ── Defeito 3: o batimento avançava na falha ──────────────────────────────

    /**
     * **O contra-teste que vale mais desta classe.**
     *
     * `ultimaPublicada` e `ultimaPublicacaoMs` eram escritos ANTES do `launch`: uma
     * publicação fracassada empurrava o relógio do batimento exatamente como uma que
     * subiu, e a correção seguinte era barrada pelo filtro de deslocamento contra um
     * ponto que o servidor **nunca recebeu**.
     *
     * As duas rodadas usam a MESMA sequência de correções, no mesmo lugar, dentro do
     * mesmo minuto. A única diferença é o resultado do POST — e o número de
     * tentativas tem de divergir. Com o carimbo movido de volta para antes do envio,
     * as duas dão 1 e o teste reprova.
     */
    @Test
    fun aFalhaNaoAvancaOBatimento_eOSucessoAvanca() {
        val mesmoPonto = { nanos: Long -> correcao(nanos = nanos) }

        // Rodada A — o servidor recusa.
        limpar()
        val fonteA = FonteFalsa()
        val a = coletor(fonteA)
        a.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        proximoResultado = false
        fonteA.ouvinte!!.onLocationChanged(mesmoPonto(1_000_000_000L))
        relogio += 5_000L // muito abaixo do batimento de 60 s
        fonteA.ouvinte!!.onLocationChanged(mesmoPonto(6_000_000_000L))
        val tentativasComFalha = publicacoes.size

        // Rodada B — o servidor aceita. Mesmas correções, mesmo relógio.
        limpar()
        val fonteB = FonteFalsa()
        val b = coletor(fonteB)
        b.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        proximoResultado = true
        fonteB.ouvinte!!.onLocationChanged(mesmoPonto(1_000_000_000L))
        relogio += 5_000L
        fonteB.ouvinte!!.onLocationChanged(mesmoPonto(6_000_000_000L))
        val tentativasComSucesso = publicacoes.size

        assertEquals(
            "a que falhou não pode ter avançado o batimento: a correção seguinte, " +
                "no mesmo ponto, precisa tentar de novo",
            2,
            tentativasComFalha,
        )
        assertEquals(
            "a que subiu avançou: parado e dentro do batimento, não se republica",
            1,
            tentativasComSucesso,
        )
        assertNotEquals(
            "se as duas rodadas dessem o mesmo número, o carimbo estaria avançando " +
                "na falha — que é exatamente o defeito",
            tentativasComFalha,
            tentativasComSucesso,
        )
    }

    /** Parado, o batimento vencido publica — é o caso que ele existe para cobrir. */
    @Test
    fun paradoEComOBatimentoVencido_publicaDeNovo() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        fonte.ouvinte!!.onLocationChanged(correcao(nanos = 1_000_000_000L))
        assertEquals(1, publicacoes.size)

        relogio += plano.batimentoMs
        fonte.ouvinte!!.onLocationChanged(correcao(nanos = 61_000_000_000L))
        assertEquals("o batimento venceu: republica no mesmo ponto", 2, publicacoes.size)
    }

    /** Andou mais que o mínimo do plano: publica antes do batimento. */
    @Test
    fun andouAcimaDoMinimo_publicaAntesDoBatimento() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        fonte.ouvinte!!.onLocationChanged(correcao(nanos = 1_000_000_000L))
        relogio += 5_000L
        // ~110 m ao norte: acima dos 50 m de `ATIVO`.
        fonte.ouvinte!!.onLocationChanged(correcao(lat = -16.6789, nanos = 6_000_000_000L))
        assertEquals(2, publicacoes.size)
    }

    // ── Defeito 4: cego a provedor caindo ─────────────────────────────────────

    /**
     * **Contra-teste do ouvinte completo, com o defeito reposto no próprio corpo.**
     *
     * O ouvinte era a lambda SAM `LocationListener { local -> ... }`, que implementa
     * só `onLocationChanged`; `onProviderDisabled` ficava no default vazio da
     * interface. Medido no aparelho: 70 s de silêncio absoluto com o GPS desligado.
     *
     * A segunda metade deste teste **é** o defeito: constrói a lambda SAM, chama
     * `onProviderDisabled` nela e mostra que nada acontece. As duas metades usam a
     * mesma chamada e precisam divergir.
     */
    @Test
    fun provedorDesligado_derrubaOEstado_eALambdaSamNaoDerrubaria() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        assertTrue(TransmissaoDePosicao.estado.value.provedorAtivo)

        fonte.ouvinte!!.onProviderDisabled(LocationManager.GPS_PROVIDER)
        val comOuvinteCompleto = TransmissaoDePosicao.estado.value
        assertFalse("o GPS caiu e o estado tem de cair junto", comOuvinteCompleto.provedorAtivo)
        assertTrue(
            "veio: ${comOuvinteCompleto.causa(relogio)}",
            comOuvinteCompleto.causa(relogio).contains("GPS foi desligado"),
        )

        // O defeito, reposto: a lambda SAM só implementa `onLocationChanged`.
        TransmissaoDePosicao.zerar()
        TransmissaoDePosicao.turno(true)
        TransmissaoDePosicao.coletaDePe(plano)
        val sam = LocationListener { /* onLocationChanged, e mais nada */ }
        sam.onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertTrue(
            "com a lambda SAM o provedor cai e o app não fica sabendo — é o defeito " +
                "de 70 s de silêncio, e é por isso que o ouvinte é um objeto completo",
            TransmissaoDePosicao.estado.value.provedorAtivo,
        )
    }

    /** Religou: o estado volta, e a próxima correção move o carimbo de verdade. */
    @Test
    fun provedorReligado_devolveOEstado() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        fonte.ouvinte!!.onProviderDisabled(LocationManager.GPS_PROVIDER)
        relogio = 30_000L
        fonte.ouvinte!!.onProviderEnabled(LocationManager.GPS_PROVIDER)

        val estado = TransmissaoDePosicao.estado.value
        assertTrue(estado.provedorAtivo)
        assertEquals(30_000L, estado.ultimaCorrecaoMs)
    }

    // ── As três saídas antecipadas de `ajustarPara` ───────────────────────────

    /**
     * As três eram `Log.w` e `return`. No aparelho, a coleta simplesmente não
     * existia e a interface não tinha como saber — e as três frases levam a **três
     * ações diferentes** do agente.
     */
    @Test
    fun asTresSaidasAntecipadas_deixamCausasDiferentes() {
        val causas = mutableListOf<String>()

        limpar()
        coletor(FonteFalsa(permissao = false)).ajustarPara(ModoOperacao.ATIVO, false)
        causas += TransmissaoDePosicao.estado.value.causa(relogio)
        assertEquals(MotivoDaColeta.SEM_PERMISSAO, TransmissaoDePosicao.estado.value.motivoDaColeta)

        limpar()
        coletor(FonteFalsa(provedores = emptyList())).ajustarPara(ModoOperacao.ATIVO, false)
        causas += TransmissaoDePosicao.estado.value.causa(relogio)
        assertEquals(MotivoDaColeta.SEM_PROVEDOR, TransmissaoDePosicao.estado.value.motivoDaColeta)

        limpar()
        coletor(FonteFalsa(aceitaAssinatura = false)).ajustarPara(ModoOperacao.ATIVO, false)
        causas += TransmissaoDePosicao.estado.value.causa(relogio)
        assertEquals(
            MotivoDaColeta.ASSINATURA_RECUSADA,
            TransmissaoDePosicao.estado.value.motivoDaColeta,
        )

        assertEquals("três saídas, três frases: $causas", 3, causas.toSet().size)
    }

    /** Sem GPS ativo, degrada para a rede em vez de sumir do mapa. */
    @Test
    fun semGps_degradaParaARede_emVezDeSumir() {
        val fonte = FonteFalsa(provedores = listOf(LocationManager.NETWORK_PROVIDER))
        coletor(fonte).ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        assertEquals(MotivoDaColeta.DE_PE, TransmissaoDePosicao.estado.value.motivoDaColeta)
    }

    // ── O silêncio do provedor, e o que prova que ele está vivo ───────────────

    /**
     * **Correção recusada pela porta de qualidade ainda prova que o receptor está
     * vivo.** Anotar só as aceitas faria "GPS ruim" ser reportado como "GPS mudo" —
     * duas causas diferentes, duas ações diferentes.
     */
    @Test
    fun correcaoRecusadaPelaPorta_aindaMoveOCarimboDeSilencio() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        fonte.ouvinte!!.onLocationChanged(correcao(precisao = 8f, nanos = 1_000_000_000L))
        assertEquals(1, publicacoes.size)

        // 4 000 m contra 8 m: degradação de precisão, recusada pela porta.
        relogio = 90_000L
        fonte.ouvinte!!.onLocationChanged(correcao(precisao = 4_000f, nanos = 91_000_000_000L))

        val estado = TransmissaoDePosicao.estado.value
        assertEquals("a recusada não foi publicada", 1, publicacoes.size)
        assertEquals(
            "mas o provedor entregou, e o carimbo de silêncio tem de saber disso",
            90_000L,
            estado.ultimaCorrecaoMs,
        )
        assertFalse(
            "com o carimbo movido, a causa não pode ser 'GPS mudo': ${estado.causa(relogio)}",
            estado.causa(relogio).contains("Sem correção de GPS"),
        )
    }

    /** Coordenada impossível não é correção: nem publica nem prova provedor vivo. */
    @Test
    fun coordenadaInvalida_naoContaComoCorrecao() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)

        relogio = 12_000L
        fonte.ouvinte!!.onLocationChanged(correcao(lat = Double.NaN, lon = 200.0))
        assertEquals(0, publicacoes.size)
        assertEquals(null, TransmissaoDePosicao.estado.value.ultimaCorrecaoMs)
    }

    // ── Parar solta o GPS e diz que parou ─────────────────────────────────────

    @Test
    fun parar_cancelaAAssinatura_eAnotaACausa() {
        val fonte = FonteFalsa()
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        assertTrue(c.coletando)

        c.parar()
        assertFalse(c.coletando)
        assertEquals(1, fonte.cancelamentos)
        assertEquals(MotivoDaColeta.PARADA, TransmissaoDePosicao.estado.value.motivoDaColeta)
    }

    /**
     * Reconfigurar não acumula assinatura. Três modos deixariam três assinaturas
     * vivas, cada uma acordando o GPS na sua cadência, e o consumo viraria a soma.
     */
    @Test
    fun trocarDeModo_naoAcumulaAssinatura() {
        val fonte = FonteFalsa(provedores = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ))
        val c = coletor(fonte)
        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        c.ajustarPara(ModoOperacao.STANDBY, mapaVisivel = false)
        c.ajustarPara(ModoOperacao.OCORRENCIA, mapaVisivel = false)
        // Duas, não três: a primeira subida não tem o que cancelar. É a contagem
        // que prova que nenhuma assinatura sobreviveu à troca — três assinaturas
        // vivas somariam três ciclos de GPS.
        assertEquals("cada troca cancela a anterior", 2, fonte.cancelamentos)
        assertEquals("e só a última fica de pé", 1, listOfNotNull(fonte.ouvinte).size)
    }

    /**
     * A tolerância de silêncio publicada acompanha o plano do modo — é ela que
     * impede o alarme falso em Standby, onde 5 min entre correções é o normal.
     */
    @Test
    fun aToleranciaPublicada_acompanhaOModo() {
        val fonte = FonteFalsa(provedores = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ))
        val c = coletor(fonte)

        c.ajustarPara(ModoOperacao.ATIVO, mapaVisivel = false)
        val ativo = TransmissaoDePosicao.estado.value.silencioToleradoMs
        c.ajustarPara(ModoOperacao.STANDBY, mapaVisivel = false)
        val standby = TransmissaoDePosicao.estado.value.silencioToleradoMs

        assertEquals(2 * plano.batimentoEfetivoMs, ativo)
        assertNotEquals("uma constante única daria alarme falso em Standby", ativo, standby)
        assertTrue(standby > ativo)
    }
}
