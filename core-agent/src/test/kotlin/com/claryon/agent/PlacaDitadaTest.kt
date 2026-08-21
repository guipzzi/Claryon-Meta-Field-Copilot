package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As regras do extrator, uma a uma — e cada uma com o **contra-teste** que prova
 * que ela é quem está segurando o resultado.
 *
 * O padrão do `CLAUDE.md` §6, pergunta 3: *se o teste passaria com o defeito de
 * volta, ele não testa o defeito.* Aqui isso significa que não basta afirmar "a
 * placa inventada foi recusada" — é preciso mostrar que ela **chegou** à validação
 * e que sem a validação ela teria passado.
 */
class PlacaDitadaTest {

    private fun placa(fala: String): String? =
        (PlacaDitada.ler(fala) as? PlacaDitada.Leitura.Reconhecida)?.placa

    // ── o contrato da spec ────────────────────────────────────────────────────

    @Test
    fun oCasoEscritoNaSpec() {
        // specs/consulta-de-placa-por-camera.spec.md, linha 85.
        assertEquals("TBU3D70", placa("Claryon, consultar placa tango bravo unido três delta sete zero."))
    }

    @Test
    fun ambosOsFormatosSaoReconhecidos() {
        val mercosul = PlacaDitada.ler("consultar placa bravo romeu alfa dois echo um nove")
        val antiga = PlacaDitada.ler("consultar placa alfa bravo charlie um dois três quatro")
        assertEquals(
            PlacaDitada.Leitura.Reconhecida("BRA2E19", PlacaValidator.Formato.MERCOSUL),
            mercosul,
        )
        assertEquals(
            PlacaDitada.Leitura.Reconhecida("ABC1234", PlacaValidator.Formato.ANTIGA),
            antiga,
        )
    }

    // ── contra-teste 1: a gramática é o que barra a placa inventada ────────────

    /** Sete caracteres, ordem que não existe em placa brasileira. */
    private val inventadas = listOf(
        "consultar placa alfa bravo um dois três quatro cinco" to "AB12345",
        "verificar placa um dois três alfa bravo charlie delta" to "123ABCD",
        "checar placa alfa bravo charlie delta um dois três" to "ABCD123",
        "rodar placa um alfa bravo charlie dois três quatro" to "1ABC234",
        "consultar placa alfa um bravo dois charlie três quatro" to "A1B2C34",
        "verificar placa alfa bravo charlie delta echo foxtrot golf" to "ABCDEFG",
        "consultar placa um dois três quatro cinco seis sete" to "1234567",
        "checar placa alfa bravo charlie um delta dois echo" to "ABC1D2E",
    )

    /**
     * **O contra-teste da validação.**
     *
     * Cada elocução inventada produz uma corrida de **exatamente sete caracteres** —
     * ou seja, âncora, contiguidade e contagem já disseram "sim". Um extrator sem a
     * gramática aceitaria **todas as oito**. Com ela, aceita **zero**.
     *
     * Os dois números são calculados aqui e o teste exige que **difiram**: se alguém
     * apagar a validação, a igualdade quebra e este teste falha. Um teste que só
     * verificasse `ler(...) == Recusada` continuaria verde se a corrida simplesmente
     * não se formasse — e não estaria testando a gramática coisa nenhuma.
     */
    @Test
    fun aGramaticaEQuemBarra_placaInventada_eSemElaTodasPassariam() {
        val chegaramNaValidacao = inventadas.count { (fala, esperada) ->
            PlacaDitada.corridasDe(fala).any { it.length == 7 && it == esperada }
        }
        val aceitasComGramatica = inventadas.count { (fala, _) -> placa(fala) != null }

        assertEquals("as 8 chegam inteiras à validação", inventadas.size, chegaramNaValidacao)
        assertEquals("com gramática, nenhuma passa", 0, aceitasComGramatica)
        assertNotEquals(
            "sem a gramática o extrator aceitaria todas — os números TÊM de diferir",
            chegaramNaValidacao,
            aceitasComGramatica,
        )
    }

    @Test
    fun aRecusaDizQueFoiErroDeLeitura_naoAusenciaDePlaca() {
        val r = PlacaDitada.ler("consultar placa alfa bravo um dois três quatro cinco")
        assertEquals(PlacaDitada.Leitura.Recusada(PlacaDitada.Motivo.FORA_DA_GRAMATICA, "AB12345"), r)
        // E o oposto: nada ditado é NADA_DITADO, que é o que abre a câmera.
        assertEquals(
            PlacaDitada.Leitura.Recusada(PlacaDitada.Motivo.NADA_DITADO),
            PlacaDitada.ler("Claryon, consultar placa."),
        )
    }

    // ── contra-teste 2: corrida de tamanho errado não é fatiada ────────────────

    /**
     * O defeito que o KDoc de [PlacaValidator.extrair] registra ter custado:
     * "código ABC12345" produzindo `ABC1234`.
     *
     * O contra-teste é o par: a corrida de 8 é descartada, e a MESMA fala com 7
     * caracteres é aceita. Um extrator que fatiasse devolveria placa nos dois casos,
     * e a segunda asserção sozinha não pegaria isso.
     */
    @Test
    fun corridaDeOitoEDescartadaInteira_masADeSeteEAceita() {
        assertEquals(null, placa("consultar placa alfa bravo charlie um dois três quatro cinco"))
        assertEquals("ABC1234", placa("consultar placa alfa bravo charlie um dois três quatro"))

        assertEquals(null, placa("consultar placa ABC12345"))
        assertEquals("ABC1234", placa("consultar placa ABC1234"))

        assertEquals(null, placa("consultar placa tango bravo unido três delta sete"))
        assertEquals("TBU3D70", placa("consultar placa tango bravo unido três delta sete zero"))
    }

    // ── contra-teste 3: dígito é dígito, não quantidade ────────────────────────

    /**
     * **`ABC0123` só existe se "zero um dois três" forem quatro caracteres.**
     *
     * Lidos como quantidade valem 123 — três caracteres — e a corrida fecha em 6,
     * que é recusada. Então a asserção positiva aqui só passa com a regra certa, e
     * a negativa mostra o que a regra errada produziria.
     */
    @Test
    fun digitoSoltoNaoViraQuantidade_oZeroAEsquerdaSobrevive() {
        assertEquals("ABC0123", placa("consultar placa alfa bravo charlie zero um dois três"))
        assertEquals("ABC7012", placa("consultar placa alfa bravo charlie sete zero um dois"))
        // Se "sete zero" virasse 70, a corrida teria 6 caracteres e seria recusada.
        assertEquals(listOf("ABC7012"), PlacaDitada.corridasDe("consultar placa alfa bravo charlie sete zero um dois"))
    }

    /**
     * E o inverso: quantidade por extenso **é** quantidade.
     *
     * "mil duzentos e trinta e quatro" tem cinco palavras de número e vale quatro
     * caracteres. Lidas uma a uma dariam `1000200304` — dez caracteres, recusa.
     */
    @Test
    fun quantidadePorExtensoViraOsDigitosDela() {
        assertEquals("ABC1234", placa("consultar placa ABC mil duzentos e trinta e quatro"))
        assertEquals("QWE1023", placa("consultar placa QWE mil e vinte e três"))
        assertEquals("BRA2E19", placa("consultar placa BRA dois E dezenove"))
    }

    @Test
    fun meiaESeis_comoNoRadio() {
        assertEquals("ABC6234", placa("consultar placa alfa bravo charlie meia dois três quatro"))
        assertEquals("LUZ6B01", placa("consultar placa lima uniforme zulu meia bravo zero um"))
    }

    // ── contra-teste 4: o casamento por som ganha alguma coisa? ────────────────

    /**
     * **Ligar o casamento por som tem de mudar o resultado, ou não serve.**
     *
     * Com ele desligado, a ditada corrompida pelo whisper é perdida; com ele ligado,
     * é lida. Se os dois números fossem iguais, a camada inteira seria peso morto —
     * e o teste falha, em vez de dar a impressão de que ela funciona.
     */
    @Test
    fun oCasamentoPorSom_recuperaADitadaCorrompida() {
        val corrompidas = listOf(
            "consultar placa tangu bravu unido três delta sete zero" to "TBU3D70",
            "consultar placa alfa bravu charli um dois três quatro" to "ABC1234",
            "consultar placa siera papa oscar nove quilo zero zero" to "SPO9K00",
        )
        val comSom = corrompidas.count { (f, p) ->
            (PlacaDitada.ler(f, aproximar = true) as? PlacaDitada.Leitura.Reconhecida)?.placa == p
        }
        val semSom = corrompidas.count { (f, p) ->
            (PlacaDitada.ler(f, aproximar = false) as? PlacaDitada.Leitura.Reconhecida)?.placa == p
        }
        assertEquals("com o casamento por som, todas", corrompidas.size, comSom)
        assertNotEquals("sem ele o resultado TEM de piorar", comSom, semSom)
    }

    // ── contra-teste 5: a âncora ───────────────────────────────────────────────

    /**
     * A âncora existe? O par mostra que sim: a mesma corrida, antes e depois da
     * palavra "placa", dá resultados diferentes.
     */
    @Test
    fun aAncoraLimitaAJanela() {
        val antes = "alfa bravo charlie um dois três quatro, consultar placa"
        assertEquals(null, placa(antes))
        assertEquals(
            "ABC1234",
            (PlacaDitada.ler(antes, ancorar = false) as PlacaDitada.Leitura.Reconhecida).placa,
        )
    }

    // ── as recusas de fala corrente ───────────────────────────────────────────

    @Test
    fun falaQueNaoEPlacaNaoViraPlaca() {
        listOf(
            "Claryon, vou verificar a placa depois.",
            "a placa do carro tá suja",
            "rodar placa desse Golf preto na esquina",
            "consultar placa do carro da serra, subiu pela delta",
        ).forEach { assertEquals("'$it' não pode virar placa", null, placa(it)) }
    }

    @Test
    fun duasPlacasNaMesmaFala_recusaEmVezDeEscolher() {
        val r = PlacaDitada.ler("consultar placa ABC1234 e placa XYZ5678")
        // A âncora fica na última menção, então só XYZ5678 é candidata.
        assertEquals("XYZ5678", (r as PlacaDitada.Leitura.Reconhecida).placa)

        val duas = PlacaDitada.ler("consultar placa ABC1234 ou XYZ5678")
        assertTrue("duas corridas válidas ⇒ recusa", duas is PlacaDitada.Leitura.Recusada)
        assertEquals(PlacaDitada.Motivo.AMBIGUA, (duas as PlacaDitada.Leitura.Recusada).motivo)
    }

    /**
     * **O achado que a medição entregou, e que eu não teria escrito de cabeça.**
     *
     * A primeira versão deste arquivo esperava recusa por ambiguidade em *"consultar
     * placa ABC1234, ou será XYZ5678"*. O teste falhou, e a razão é melhor que a
     * expectativa: **"será" vira a letra S**, porque a chave fonética colapsa o `rr`
     * de "serra" e as duas palavras ficam a distância ZERO. A segunda corrida virou
     * `SXYZ5678`, oito caracteres, e foi descartada inteira.
     *
     * Ou seja: a contiguidade não é só um filtro de ruído — ela também **cola** ruído
     * numa corrida boa e a derruba. É perda de recall, não de precisão, e por isso
     * fica: o modo de falhar é pedir repetição, nunca consultar veículo errado.
     *
     * Está registrado como teste, e não como comentário, para que uma mudança futura
     * no corte por comprimento de [AlfabetoFonetico] apareça aqui em vez de aparecer
     * em campo.
     */
    @Test
    fun palavraComumQueSoaComoAlfabeto_colaNaCorridaEDerrubaALeitura() {
        assertEquals("'será' soa como 'serra', que é S", 'S', AlfabetoFonetico.letraAproximada("sera"))
        assertEquals(
            listOf("ABC1234", "SXYZ5678"),
            PlacaDitada.corridasDe("consultar placa ABC1234, ou será XYZ5678"),
        )
        // E o resultado é a leitura sobrevivente, não uma placa fabricada.
        assertEquals("ABC1234", placa("consultar placa ABC1234, ou será XYZ5678"))
    }
}
