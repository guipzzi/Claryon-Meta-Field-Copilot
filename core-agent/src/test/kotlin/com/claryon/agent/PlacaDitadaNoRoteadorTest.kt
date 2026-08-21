package com.claryon.agent

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A ditada tem porta — e o teste que prova isso precisa dos DOIS lados.**
 *
 * [PlacaDitada] existia, estava medida (recall 40/40, zero falso positivo em 44
 * negativos, zero placa fabricada sobre 1817 trechos de lei) e tinha **zero
 * chamadores em `src/main`**. Quem extraía placa em produção era
 * `DeterministicIntentRouter.extrairPlaca`, e ele só fazia casamento literal de sete
 * caracteres — então *"tango bravo unido três delta sete zero"*, que é como se dita
 * placa por rádio e é o exemplo de aceite da spec, virava
 * `Intent.ConsultarPlaca(placa = null)` e o app **abria a câmera** para ler a placa
 * que o agente acabara de falar.
 *
 * É o padrão que o `CLAUDE.md` §6 conta seis vezes: classe testada sem chamador é
 * **escrita**, não construída.
 *
 * ## Por que uma asserção só não serviria
 *
 * - **Só "a ditada passa a valer"**: passaria com um extrator que aceitasse qualquer
 *   coisa — e um extrator que aceita qualquer coisa consulta um veículo aleatório e
 *   devolve "sem restrição" ao agente, que libera o carro.
 * - **Só "o literal não regride"**: passaria com o código de ontem, sem mudança
 *   nenhuma. É o teste que aprova o defeito.
 *
 * Por isso as duas andam juntas em [oContraTeste_aDitadaPassaAValer_eOLiteralNaoRegride],
 * e o lado do falso positivo — fala corrente que não é placa — é varrido sobre o
 * banco inteiro em [falaQueNaoEPlaca_continuaSemPlaca_atravessandoORoteador].
 *
 * ## A ORDEM foi medida, e a medida contradisse o argumento
 *
 * O bloco foi pedido com esta justificativa: *"inverter faria o ditado reinterpretar
 * uma placa que já estava escrita"*. **Não é o que acontece.** A segunda tentativa só
 * roda quando a primeira devolve `null`, então as duas ordens só divergem quando as
 * duas devolvem placa e as placas diferem — e sobre 84 elocuções mais 1817 trechos de
 * lei isso acontece **zero vez**
 * ([aOrdemEntreOsDois_naoTemProvaPropriaSobreOMaterialMEDIDO]). O que existe é uma
 * divergência **construível**, a correção falada, e nela a ordem escolhida perde
 * ([aDivergenciaConstruivel_aCorrecaoFalada_eOCustoDaOrdemEscolhida]). Os dois estão
 * asseridos para que a escolha seja visível a quem a mudar.
 *
 * ## O roteador de ontem está reconstruído aqui, e é ele que dá sentido ao "hoje"
 *
 * [roteadorDeOntem] é a linha que existia antes desta ligação, copiada com a mesma
 * normalização do arquivo de produção. Sem ela, "hoje devolve `null`" seria memória;
 * com ela, é asserção que roda.
 */
class PlacaDitadaNoRoteadorTest {

    private val router = DeterministicIntentRouter()

    /** A placa que o roteador de PRODUÇÃO extrai da fala, ou `null`. */
    private fun placaDe(fala: String): String? =
        (router.route(fala) as? Intent.ConsultarPlaca)?.placa

    /**
     * **O roteador de ontem: literal e nada mais.**
     *
     * Reproduz `extrairPlaca` como era — `PlacaValidator.extrair` sobre o texto
     * normalizado, com o gatilho já retirado —, para que este teste possa afirmar
     * *"isto devolvia `null`"* medindo em vez de lembrando.
     */
    private fun roteadorDeOntem(fala: String): String? {
        val semGatilho = PalavraDeAtivacaoNaFala.conferir(fala)
        val texto = Normalizer
            .normalize((if (semGatilho.confirmada) semGatilho.resto else fala).lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
        return PlacaValidator.extrair(texto)
    }

    // ── o contra-teste ────────────────────────────────────────────────────────

    /** O exemplo de aceite da `specs/consulta-de-placa-por-camera.spec.md`, linha 85. */
    private val ditadaDaSpec = "Claryon, consultar placa tango bravo unido três delta sete zero."

    /**
     * **Os dois lados, na mesma asserção — a ditada passa a valer E o literal fica.**
     *
     * O primeiro bloco é a capacidade nova: a fala em alfabeto fonético devolvia
     * `null` (e o `null` abre a câmera) e passa a devolver `TBU3D70`. O segundo é a
     * proteção contra o conserto que quebra o que funcionava: placa **escrita** na
     * transcrição — que é o que o whisper produz quando o agente lê a placa em vez de
     * soletrá-la — continua saindo idêntica, pelos dois formatos e com pontuação.
     */
    @Test
    fun oContraTeste_aDitadaPassaAValer_eOLiteralNaoRegride() {
        // ── lado 1: hoje era null, e a ditada tem de virar placa ──────────────
        assertNull(
            "se isto já devolvesse placa, o outro lado do contra-teste não provaria nada",
            roteadorDeOntem(ditadaDaSpec),
        )
        assertEquals(
            "a ditada em alfabeto fonético tem de virar consulta, não abertura de câmera",
            "TBU3D70",
            placaDe(ditadaDaSpec),
        )

        // ── lado 2: o literal não pode regredir ───────────────────────────────
        val literais = mapOf(
            "consultar placa ABC 1D23" to "ABC1D23",   // Mercosul, em dois tokens
            "consultar placa ABC1D23" to "ABC1D23",    // Mercosul, em um token
            "checar placa ABC-1234" to "ABC1234",      // antiga, com hífen
            "Claryon, verificar placa BRA2E19." to "BRA2E19", // com gatilho e ponto
        )
        for ((fala, esperada) in literais) {
            assertEquals("o literal era $esperada antes desta mudança", esperada, roteadorDeOntem(fala))
            assertEquals("e tem de continuar sendo depois", esperada, placaDe(fala))
        }
    }

    /**
     * **O literal não é dispensável: a ditada sozinha perde a placa escrita.**
     *
     * Isto é sobre a **presença** do literal na cadeia, não sobre a ordem — os dois
     * são coisas diferentes e o teste seguinte trata da outra.
     *
     * O montador de corridas **não para na placa**: em *"consultar placa ABC 1D23 e o
     * carro é um Gol"*, "e" e "o" valem a si mesmas como letras
     * ([AlfabetoFonetico.letra]), a corrida chega a `ABC1D23EO` e é descartada
     * **inteira** — sete exatos, sem fatiar, que é justamente o desenho que impede
     * fabricar placa dentro de corrida longa. O literal, no mesmo texto, acerta.
     */
    @Test
    fun oLiteralNaoEDispensavel_aDitadaSozinhaPerdeAPlacaEscrita() {
        val fala = "consultar placa ABC 1D23 e o carro é um Gol"

        val soDitada = PlacaDitada.ler(fala)
        assertTrue(
            "a ditada SOZINHA tem de falhar aqui, senão o teste não mede nada — veio $soDitada",
            soDitada is PlacaDitada.Leitura.Recusada,
        )
        val corridaLonga = PlacaDitada.corridasDe(fala).maxByOrNull { it.length }
        assertTrue(
            "e tem de falhar por corrida LONGA (a placa mais as letras soltas), " +
                "não por corrida nenhuma — veio $corridaLonga",
            (corridaLonga?.length ?: 0) > 7,
        )

        assertEquals("o literal acerta o que a ditada perde", "ABC1D23", roteadorDeOntem(fala))
        assertEquals("e o caminho de produção tem de preservar isso", "ABC1D23", placaDe(fala))
    }

    /**
     * **A ORDEM entre os dois não tem prova própria — e a medição contradisse o
     * argumento com que ela foi pedida.**
     *
     * O argumento era: *"inverter faria o ditado reinterpretar uma placa que já estava
     * escrita"*. Ele não se sustenta como está, porque a segunda tentativa só roda
     * quando a primeira **devolve `null`**. Para as duas ordens divergirem é preciso
     * que as duas devolvam placa, e placas **diferentes**.
     *
     * Medido, e o número é zero: sobre as 84 elocuções do banco e sobre os 1817
     * trechos de lei do corpus, as duas ordens produzem **exatamente o mesmo
     * resultado em todos os casos**. O literal fica primeiro por custo — casamento de
     * regex contra montar corrida palavra a palavra — e porque, entre duas leituras
     * discordantes, a que o whisper de fato escreveu em caracteres é a menos
     * interpretada das duas. Não porque a medida o exija.
     *
     * Este teste existe para que a afirmação continue verdadeira ou apareça: se um dia
     * der diferente de zero, a ordem passa a ser decisão com consequência e alguém
     * precisa reler qual delas quer. É o mesmo registro honesto que
     * `PlacaEmCorpusRealTest.aAncoraReduzASuperficieExaminada` faz da âncora.
     */
    @Test
    fun aOrdemEntreOsDois_naoTemProvaPropriaSobreOMaterialMEDIDO() {
        // **Uma passada só, e não duas.** `a ?: b` e `b ?: a` são funções do MESMO
        // par: divergem exatamente quando os dois lados devolvem placa e as placas
        // diferem. Calcular o par uma vez corta o custo pela metade sobre um corpus
        // de 1817 trechos — e este teste roda em toda build.
        val material = BancoDePlacasDitadas.banco.map { it.fala } + CorpusDeLei.textos()
        val divergem = material.mapNotNull { t ->
            val literal = PlacaValidator.extrair(t)
            val ditada = (PlacaDitada.ler(t) as? PlacaDitada.Leitura.Reconhecida)?.placa
            if (literal != null && ditada != null && literal != ditada) Triple(literal, ditada, t) else null
        }

        println("── as duas ordens, sobre ${material.size} textos ──")
        println("  divergências: ${divergem.size}")
        assertEquals(
            "a ordem passou a ter consequência medida, e a escolha precisa ser relida:\n" +
                divergem.take(10).joinToString("\n") { (l, d, t) ->
                    "  literal=$l ditada=$d ← ${t.take(120)}"
                },
            0,
            divergem.size,
        )
    }

    /**
     * **A divergência CONSTRUÍVEL, e ela custa: a correção falada perde.**
     *
     * Zero divergências no material medido não é o mesmo que "não pode divergir". A
     * construção existe e é fala plausível de rádio — o agente se corrige:
     *
     * > *"consultar placa ABC1234, não — placa XYZ5678"*
     *
     * O literal devolve a **primeira** placa do texto; [PlacaDitada] só lê depois da
     * **última** menção a "placa" (é o desenho da âncora, e está no KDoc dela). As
     * duas discordam — e é justamente por isso que a ordem foi escolhida como está.
     *
     * **A DITADA vem primeiro.** Eu tinha instruído o contrário ("o literal é o caso
     * barato e inequívoco"), e a construção deste teste mostrou que a instrução
     * estava errada: numa autocorreção falada, o literal devolve a placa que o agente
     * ACABOU DE DESCARTAR. A ditada lê depois da última menção a "placa", que é onde
     * mora a intenção.
     *
     * O risco da inversão — interpretação passando na frente de dado literal — é real
     * e está medido: 84 elocuções com **zero extrações erradas**, e zero placa
     * fabricada sobre 1817 trechos de lei. E o literal continua como segundo caminho,
     * então nada que ele resolvia deixou de ser resolvido.
     *
     * Quem trocar a ordem vê este teste falhar e lê o custo antes de decidir.
     */
    @Test
    fun aDivergenciaConstruivel_aCorrecaoFalada_eOCustoDaOrdemEscolhida() {
        val correcao = "consultar placa ABC1234, não — placa XYZ5678"

        assertEquals(
            "a ditada lê depois da ÚLTIMA menção a 'placa' — é a correção",
            PlacaDitada.Leitura.Reconhecida("XYZ5678", PlacaValidator.Formato.ANTIGA),
            PlacaDitada.ler(correcao),
        )
        assertEquals(
            "o literal lê a PRIMEIRA placa do texto — é a que foi corrigida",
            "ABC1234",
            roteadorDeOntem(correcao),
        )
        assertEquals(
            "a DITADA vem primeiro no roteador, então ela ganha — e aqui isso é o " +
                "comportamento certo: o agente se corrigiu, e o que ele quis dizer é a " +
                "segunda placa. Trocar a ordem faria o app consultar o veículo que ele " +
                "acabou de descartar.",
            "XYZ5678",
            placaDe(correcao),
        )
    }

    // ── o lado do falso positivo ──────────────────────────────────────────────

    /**
     * **Fala corrente que não é placa continua sem placa — e o número não subiu.**
     *
     * Os 44 negativos são os de `BancoDePlacasDitadas` (fala corrente adversarial,
     * gramática errada e contagem errada), reusados e não reescritos: banco de
     * referência em duas cópias diverge no primeiro negativo acrescentado a uma só
     * delas.
     *
     * A comparação é contra o roteador de ontem, e não contra zero em abstrato: o que
     * este bloco precisa provar é que **ligar a ditada não acrescentou nenhum falso
     * positivo ao que já existia**.
     *
     * E há um controle de instrumento: se nenhuma dessas falas chegasse a
     * `Intent.ConsultarPlaca`, o zero seria do léxico do roteador e não do extrator, e
     * o teste não estaria medindo nada.
     */
    @Test
    fun falaQueNaoEPlaca_continuaSemPlaca_atravessandoORoteador() {
        val negativos = BancoDePlacasDitadas.negativos

        val chegaramNaConsulta = negativos.count { router.route(it.fala) is Intent.ConsultarPlaca }
        assertTrue(
            "controle de instrumento: se nenhum negativo virasse ConsultarPlaca, o zero " +
                "abaixo seria do léxico do roteador, não do extrator — chegaram $chegaramNaConsulta",
            chegaramNaConsulta > 0,
        )

        val ontem = negativos.filter { roteadorDeOntem(it.fala) != null }
        val hoje = negativos.filter { placaDe(it.fala) != null }

        println("── falso positivo através do roteador, sobre ${negativos.size} negativos ──")
        println("  chegaram a ConsultarPlaca ... $chegaramNaConsulta")
        println("  falsos positivos ONTEM ...... ${ontem.size}")
        println("  falsos positivos HOJE ....... ${hoje.size}")

        assertEquals(
            "cada uma destas é consulta a um veículo que ninguém pediu:\n" +
                hoje.joinToString("\n") { "  ${placaDe(it.fala)} ← ${it.fala}" },
            0,
            hoje.size,
        )
        assertEquals("e ligar a ditada não podia acrescentar nenhum", ontem.size, hoje.size)
    }

    /**
     * **O banco inteiro atravessando o roteador — e o que a porta acrescentou.**
     *
     * O `BancoDePlacasDitadasTest` mede [PlacaDitada] direto. Aqui a mesma medida
     * passa pelo caminho de produção — gatilho, normalização, léxico, `extrairPlaca`
     * —, que é o único que existe em runtime. A diferença entre as duas colunas
     * impressas é literalmente o tamanho da capacidade que estava escrita e não
     * construída.
     */
    @Test
    fun oBancoInteiroAtravessaORoteador_eADiferencaEImpressa() {
        val positivos = BancoDePlacasDitadas.positivos

        val certasOntem = positivos.count { roteadorDeOntem(it.fala) == it.esperado }
        val certasHoje = positivos.count { placaDe(it.fala) == it.esperado }
        val erradasHoje = positivos.filter {
            val p = placaDe(it.fala)
            p != null && p != it.esperado
        }

        println("── o banco de ditadas, pelo caminho de produção ──")
        println("  positivos ................. ${positivos.size}")
        println("  extraídas ONTEM (literal) . $certasOntem")
        println("  extraídas HOJE ............ $certasHoje")
        println("  extraídas ERRADAS hoje .... ${erradasHoje.size}")

        assertEquals(
            "extraída ERRADA é pior que perdida: é consulta com dado fabricado — " +
                erradasHoje.joinToString { "${placaDe(it.fala)} ≠ ${it.esperado}" },
            0,
            erradasHoje.size,
        )
        assertTrue(
            "se a porta não acrescentasse recall, ela não seria porta nenhuma " +
                "($certasOntem → $certasHoje)",
            certasHoje > certasOntem,
        )
        assertEquals("o roteador tem de entregar o banco inteiro", positivos.size, certasHoje)
    }

    /**
     * **A porta é a de `ConsultarPlaca`, e só ela.**
     *
     * Ligar um extrator novo num roteador que casa por `contains` é jeito conhecido de
     * sequestrar outra intenção — o próprio arquivo registra que "modo abordagem" e
     * "narrar ocorrência: veículo de placa ABC1234" já custaram isso. A ditada entra
     * **dentro** do ramo de placa, depois de toda a ordem de precedência, então a
     * narração que menciona placa continua narração.
     */
    @Test
    fun aDitadaNaoSequestraOutraIntencao() {
        val narracao = "narrar ocorrência veículo de placa tango bravo unido três delta sete zero"
        assertTrue(
            "ditado tem de vencer o termo solto 'placa' — veio ${router.route(narracao)}",
            router.route(narracao) is Intent.NarrarOcorrencia,
        )
        // E o contra-controle: a MESMA ditada, com o verbo explícito, vira consulta.
        assertNotNull(placaDe("verificar placa tango bravo unido três delta sete zero"))
    }
}
