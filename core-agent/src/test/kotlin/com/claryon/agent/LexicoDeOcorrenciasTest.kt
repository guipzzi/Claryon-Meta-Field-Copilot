package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O léxico traduz fala livre em ocorrência tipada — sem modelo de linguagem.
 *
 * O que estes testes protegem, acima de tudo, é a **classificação de
 * prioridade**: errar para baixo manda pouca gente para um tiroteio; errar para
 * cima esvazia o setor por uma perturbação de sossego. As duas custam caro, e a
 * primeira custa vidas.
 */
class LexicoDeOcorrenciasTest {

    @Before
    fun gazetteerDoPiloto() {
        LexicoDeOcorrencias.configurarGazetteer(
            listOf("Rui Barbosa", "Anhanguera", "T-63", "Praça Cívica", "Marginal Botafogo"),
        )
    }

    private fun classificar(fala: String) = LexicoDeOcorrencias.classificar(fala)

    // ── Reconhecimento ────────────────────────────────────────────────────────

    @Test
    fun reconheceOsTiposMaisComuns() {
        assertEquals(TipoDeOcorrencia.TIROTEIO, classificar("tem um tiroteio aqui")?.tipo)
        assertEquals(TipoDeOcorrencia.ROUBO, classificar("assalto em andamento")?.tipo)
        assertEquals(TipoDeOcorrencia.INCENDIO, classificar("incêndio numa casa")?.tipo)
        assertEquals(TipoDeOcorrencia.BRIGA, classificar("confusão no bar")?.tipo)
        assertEquals(TipoDeOcorrencia.TRAFICO, classificar("tráfico na esquina")?.tipo)
    }

    @Test
    fun funcionaSemAcento_comoOSttEntrega() {
        // O whisper nem sempre acentua. "agressao" tem de casar com "agressão".
        assertEquals(TipoDeOcorrencia.AGRESSAO, classificar("agressao em via publica")?.tipo)
        assertEquals(TipoDeOcorrencia.VIOLENCIA_DOMESTICA, classificar("violencia domestica")?.tipo)
    }

    @Test
    fun oEspecificoVenceOGenerico() {
        // "roubo de veículo" não pode virar "roubo": perseguir um carro é uma
        // resposta operacional diferente de atender uma vítima parada.
        assertEquals(TipoDeOcorrencia.ROUBO_DE_VEICULO, classificar("roubo de veículo na avenida")?.tipo)
        assertEquals(TipoDeOcorrencia.ACIDENTE_COM_VITIMA, classificar("acidente com vítima")?.tipo)
        assertEquals(TipoDeOcorrencia.ACIDENTE_SEM_VITIMA, classificar("acidente sem vítima")?.tipo)
    }

    @Test
    fun falaQueNaoEhOcorrencia_devolveNulo() {
        // Um alerta inventado é pior que um alerta não enviado.
        assertNull(classificar("bom dia pessoal"))
        assertNull(classificar(""))
        assertNull(classificar("   "))
    }

    // ── Prioridade ────────────────────────────────────────────────────────────

    @Test
    fun tiposGravesJaNascemComoEmergencia() {
        assertEquals(Prioridade.EMERGENCIA, classificar("tiroteio")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("atropelamento")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("vazamento de gás")?.prioridade)
    }

    @Test
    fun modificadorEleva_masNuncaRebaixa() {
        // "Roubo" é P2; "roubo à mão armada" é P1. E um tiroteio sem modificador
        // continua P1 — a escala só sobe.
        assertEquals(Prioridade.ALTA, classificar("roubo numa loja")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("roubo com refém")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("roubo, suspeito armado")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("tiroteio")?.prioridade)
    }

    @Test
    fun vitimaFeridaElevaParaEmergencia() {
        assertEquals(Prioridade.NORMAL, classificar("acidente sem vítima")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("briga com ferido")?.prioridade)
        assertEquals(Prioridade.EMERGENCIA, classificar("agressão, mulher sangrando")?.prioridade)
    }

    @Test
    fun urgenciaSemGravidade_elevaSoAteAlta() {
        // "Rápido" não transforma perturbação de sossego em emergência — senão a
        // palavra perderia sentido por inflação.
        val p = classificar("perturbação do sossego, vem rápido")?.prioridade
        assertEquals(Prioridade.ALTA, p)
    }

    @Test
    fun agentEmRisco_ehSempreEmergencia() {
        assertEquals(Prioridade.EMERGENCIA, classificar("abordagem, guarnição em risco")?.prioridade)
        // "apoio" saiu do léxico (é `Intent.PedirApoio`), mas a régua de escalada
        // é a mesma — verificada aqui pela função compartilhada.
        assertEquals(
            Prioridade.EMERGENCIA,
            LexicoDeOcorrencias.escalarPrioridade(Prioridade.NORMAL, "apoio, policial baleado"),
        )
    }

    // ── Logradouro ────────────────────────────────────────────────────────────

    @Test
    fun encontraLogradouroDoGazetteer() {
        val o = classificar("tiroteio na Rui Barbosa")
        assertNotNull(o)
        assertTrue("esperava Rui Barbosa, veio ${o!!.logradouro}", o.logradouro?.contains("Rui", ignoreCase = true) == true)
    }

    @Test
    fun encontraLogradouroPeloPrefixo_semGazetteer() {
        // O piloto pode começar sem gazetteer completo, e o alerta não pode
        // esperar o cadastro de ruas ficar pronto.
        LexicoDeOcorrencias.configurarGazetteer(emptyList())
        val o = classificar("roubo na avenida Independência")
        assertNotNull(o)
        assertTrue("veio: ${o!!.logradouro}", o.logradouro?.contains("Independ", ignoreCase = true) == true)
    }

    @Test
    fun logradouroNaoEngoleOResto() {
        // "avenida Rui Barbosa perto do posto" não pode virar rua
        // "Rui Barbosa perto do".
        LexicoDeOcorrencias.configurarGazetteer(emptyList())
        val o = classificar("tiroteio na avenida Contorno perto do posto")
        assertTrue("veio: ${o?.logradouro}", o?.logradouro?.contains("perto") != true)
    }

    @Test
    fun semLogradouro_aOcorrenciaAindaVale() {
        // Sob estresse o agente diz só "tiroteio". Exigir endereço faria o
        // produto recusar exatamente o alerta mais urgente.
        val o = classificar("tiroteio")
        assertNotNull(o)
        assertNull(o!!.logradouro)
        assertEquals(Prioridade.EMERGENCIA, o.prioridade)
    }

    @Test
    fun capturaReferenciaDeApoio() {
        val o = classificar("roubo perto do posto de gasolina")
        assertTrue("veio: ${o?.referencia}", o?.referencia?.contains("posto") == true)
    }

    // ── Cenários adversos ─────────────────────────────────────────────────────

    @Test
    fun falaLongaEDesconexa_aindaClassifica() {
        // Como o agente realmente fala correndo.
        val o = classificar(
            "atenção atenção tem um tiroteio aqui na Rui Barbosa dois caras armados " +
                "correndo pro lado do posto manda apoio",
        )
        assertEquals(TipoDeOcorrencia.TIROTEIO, o?.tipo)
        assertEquals(Prioridade.EMERGENCIA, o?.prioridade)
    }

    @Test
    fun textoOriginalEhPreservado() {
        // O despacho recebe a fala inteira como contexto; a classificação é
        // metadado, não substituto.
        val fala = "Tiroteio na Rui Barbosa, dois suspeitos"
        assertEquals(fala, classificar(fala)?.textoOriginal)
    }

    @Test
    fun transcricaoRuidosa_naoInventaOcorrencia() {
        // STT em ambiente barulhento produz salada. Não pode virar alerta.
        assertNull(classificar("aaa bbb ccc ddd"))
        assertNull(classificar("1 2 3 4 5"))
    }

    @Test
    fun todosOsTiposTemGatilho() {
        // Um tipo sem gatilho é código morto que dá falsa sensação de cobertura.
        val comGatilho = TipoDeOcorrencia.entries.filter { tipo ->
            LexicoDeOcorrencias.classificar(tipo.rotulo)?.tipo != null
        }
        val sem = TipoDeOcorrencia.entries - comGatilho.toSet()
        assertTrue("tipos sem gatilho reconhecível: ${sem.map { it.name }}", sem.isEmpty())
    }

    @Test
    fun oLexicoCobreQuarentaTipos() {
        // O aditivo dimensionou ~40 tipos como cobertura da maioria dos casos.
        assertTrue("apenas ${TipoDeOcorrencia.entries.size} tipos", TipoDeOcorrencia.entries.size >= 40)
    }
}
