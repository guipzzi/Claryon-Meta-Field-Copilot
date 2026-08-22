package com.claryon.field.agent

import com.claryon.agent.DeterministicIntentRouter
import com.claryon.agent.Intent
import com.claryon.agent.PlacaDitada
import com.claryon.agent.PlacaValidator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DESCARTÁVEL — auditoria de 22/08. Apaga depois de medir.
 *
 * Corpus NOVO (não é o `BancoDePlacasDitadas`, que já roda em toda build): elocuções
 * escritas do zero para esta auditoria, atravessando o caminho de PRODUÇÃO
 * (`DeterministicIntentRouter.route`), não `PlacaDitada.ler` direto.
 */
class PlacaDitadaPeloRoteadorTest {

    private val router = DeterministicIntentRouter()

    /** O que o roteador de produção extrai, ou `null`. */
    private fun placaDe(fala: String): String? =
        (router.route(fala) as? Intent.ConsultarPlaca)?.placa

    private data class Caso(val fala: String, val esperado: String?, val grupo: String)

    private fun ok(g: String, f: String, p: String) = Caso(f, p, g)
    private fun nao(g: String, f: String) = Caso(f, null, g)

    // ── MERCOSUL (LLLNLNN) ────────────────────────────────────────────────────
    private val mercosul = listOf(
        ok("mercosul", "Claryon, consultar placa papa mike delta dois india zero quatro.", "PMD2I04"),
        ok("mercosul", "verificar placa foxtrot golf hotel cinco juliet um seis", "FGH5J16"),
        ok("mercosul", "checar placa november oscar papa três quebec oito dois", "NOP3Q82"),
        ok("mercosul", "rodar placa sierra tango uniforme sete victor nove um", "STU7V91"),
        ok("mercosul", "consultar placa whiskey xray yankee zero zulu cinco cinco", "WXY0Z55"),
        ok("mercosul", "verificar placa bravo charlie delta quatro echo dois sete", "BCD4E27"),
        ok("mercosul", "Claryon, consultar placa kilo lima mike meia november zero oito.", "KLM6N08"),
        ok("mercosul", "checar placa india juliet kilo um lima três nove", "IJK1L39"),
        ok("mercosul", "rodar placa alfa november tango oito romeu quatro dois", "ANT8R42"),
        ok("mercosul", "consultar placa vitor ianque xadrez dois delta sete um", "VYX2D71"),
        ok("mercosul", "verificar placa golf uniforme charlie nove alfa zero zero", "GUC9A00"),
        ok("mercosul", "checar placa echo romeu papa quatro sierra um dois", "ERP4S12"),
    )

    // ── PADRÃO ANTIGO (LLLNNNN) ───────────────────────────────────────────────
    private val antiga = listOf(
        ok("antiga", "consultar placa hotel juliet kilo dois quatro seis oito", "HJK2468"),
        ok("antiga", "verificar placa delta echo foxtrot um três cinco sete", "DEF1357"),
        ok("antiga", "Claryon, checar placa mike november oscar nove nove nove nove.", "MNO9999"),
        ok("antiga", "rodar placa papa quebec romeu zero zero um dois", "PQR0012"),
        ok("antiga", "consultar placa tango uniforme victor sete seis cinco quatro", "TUV7654"),
        ok("antiga", "verificar placa whiskey xray yankee um um um um", "WXY1111"),
        ok("antiga", "checar placa alfa bravo delta oito zero dois meia", "ABD8026"),
        ok("antiga", "rodar placa charlie hotel india três dois um zero", "CHI3210"),
        ok("antiga", "consultar placa juliet kilo lima cinco cinco quatro quatro", "JKL5544"),
        ok("antiga", "verificar placa oscar papa quebec dois oito sete três", "OPQ2873"),
        ok("antiga", "Claryon, consultar placa romeu sierra tango meia meia zero um.", "RST6601"),
        ok("antiga", "checar placa uniforme victor whiskey quatro nove três oito", "UVW4938"),
    )

    // ── ORDINAIS — Portaria 071-CG/15 PMBA ────────────────────────────────────
    private val ordinais = listOf(
        // "JNO – 5448 - Juliet; November; Oscar. Quinto; Quarto Dobrado; Oitavo."
        ok("ordinal", "consultar placa juliet november oscar quinto quarto dobrado oitavo", "JNO5448"),
        ok("ordinal", "verificar placa papa mike delta segundo india negativo quarto", "PMD2I04"),
        ok("ordinal", "checar placa alfa bravo charlie primeiro segundo terceiro quarto", "ABC1234"),
        ok("ordinal", "rodar placa tango romeu sierra sétimo nono negativo primeiro", "TRS7901"),
        ok("ordinal", "consultar placa golf hotel india quinto dobrado terceiro oitavo", "GHI5538"),
        ok("ordinal", "verificar placa delta echo foxtrot oitavo dobrado quinto primeiro", "DEF8851"),
        ok("ordinal", "Claryon, checar placa kilo lima mike sexto alfa nono segundo.", "KLM6A92"),
        ok("ordinal", "rodar placa november oscar papa nulo quinto quarto terceiro", "NOP0543"),
        ok("ordinal", "consultar placa quebec romeu sierra primeiro dobrado nono oitavo", "QRS1198"),
        ok("ordinal", "verificar placa victor whiskey xray quarto delta sexto sétimo", "VWX4D67"),
    )

    // ── PMERJ: uno, meia, multiplicador ANTES ─────────────────────────────────
    private val pmerj = listOf(
        ok("pmerj", "consultar placa alfa bravo charlie uno duplo zero meia", "ABC1006"),
        ok("pmerj", "verificar placa delta echo foxtrot triplo dois uno", "DEF2221"),
        ok("pmerj", "checar placa golf hotel india uno delta duplo zero", "GHI1D00"),
        ok("pmerj", "rodar placa juliet kilo lima duplo meia uno nove", "JKL6619"),
        ok("pmerj", "consultar placa mike november oscar quadruplo sete", "MNO7777"),
        ok("pmerj", "Claryon, verificar placa papa quebec romeu uno nove meia dois.", "PQR1962"),
    )

    // ── Corrompidas pelo whisper ──────────────────────────────────────────────
    private val corrompidas = listOf(
        ok("corrompida", "consultar placa tangu bravu charli quatro delta dois um", "TBC4D21"),
        ok("corrompida", "verificar placa golfe quilo novembro sete alfa zero três", "GKN7A03"),
        ok("corrompida", "checar placa uisqui xis ianque um dois três quatro", "WXY1234"),
        ok("corrompida", "rodar placa romeo india oscar dois alfa um oito", "RIO2A18"),
        ok("corrompida", "consultar placa juliete quebeque uniforme cinco hotél nove zero", "JQU5H90"),
        ok("corrompida", "verificar placa foxtrote eco delta oito nove um dois", "FED8912"),
    )

    // ── Placa ESCRITA na transcrição (caminho literal) ────────────────────────
    private val literais = listOf(
        ok("literal", "consultar placa RIO2A18", "RIO2A18"),
        ok("literal", "verificar placa BRA 2E19", "BRA2E19"),
        ok("literal", "Claryon, checar placa MER-4C71.", "MER4C71"),
        ok("literal", "rodar placa QWE1234", "QWE1234"),
    )

    // ── NEGATIVAS: fala corrente que menciona placa ───────────────────────────
    private val negativas = listOf(
        nao("negativa", "Claryon, a placa estava coberta de barro."),
        nao("negativa", "consultar placa do Uno branco que subiu a serra"),
        nao("negativa", "checar placa, mas o motorista arrancou"),
        nao("negativa", "placa refletindo demais, não consigo ler"),
        nao("negativa", "verificar placa do papa-léguas ali na esquina"),
        nao("negativa", "verificar placa: tem um Golf e um Delta parados na via"),
        nao("negativa", "placa antiga, cinza, três letras"),
        nao("negativa", "consultar placa quando der, prioridade é o apoio"),
        nao("negativa", "rodar placa negativo, câmbio"),
        nao("negativa", "a placa é do quarto veículo, o segundo já passou"),
        nao("negativa", "verificar placa: alfa bravo, não deu pra ver o resto"),
        nao("negativa", "checar placa mike, ele tá na escuta"),
        nao("negativa", "placa do carro que bateu no poste da Lima"),
        nao("negativa", "consultar placa, o Victor tá com o número"),
    )

    // ── GRAMÁTICA ERRADA: sete símbolos fora de forma ─────────────────────────
    private val gramatica = listOf(
        nao("gramatica", "consultar placa alfa bravo um dois charlie três quatro"),   // AB12C34
        nao("gramatica", "verificar placa um dois três quatro alfa bravo charlie"),   // 1234ABC
        nao("gramatica", "checar placa alfa bravo charlie delta um dois três"),       // ABCD123
        nao("gramatica", "rodar placa alfa um dois três quatro cinco seis"),          // A123456
        nao("gramatica", "consultar placa alfa bravo charlie um delta dois echo"),    // ABC1D2E
        nao("gramatica", "verificar placa zulu yankee xray whiskey victor uniforme tango"), // ZYXWVUT
    )

    // ── CONTAGEM ERRADA: 6, 8, 9 ──────────────────────────────────────────────
    private val contagem = listOf(
        nao("contagem", "consultar placa alfa bravo charlie um dois três quatro cinco"),
        nao("contagem", "verificar placa papa mike delta dois india zero"),
        nao("contagem", "checar placa ABC123456"),
        nao("contagem", "rodar placa golf oscar lima quatro delta meia sete um"),
    )

    private val positivos = mercosul + antiga + ordinais + pmerj + corrompidas + literais
    private val negativos = negativas + gramatica + contagem

    /**
     * O relatório pedido: quantas extraem certo, quantas são recusadas, quantas
     * produzem placa ERRADA.
     */
    @Test
    fun corpusNovo_atravessandoORoteadorDeProducao() {
        val certas = mutableListOf<Caso>()
        val recusadas = mutableListOf<Caso>()
        val erradas = mutableListOf<Pair<Caso, String>>()

        for (c in positivos + negativos) {
            val p = placaDe(c.fala)
            when {
                p == c.esperado -> certas += c            // inclui negativo → null
                p == null -> recusadas += c               // positivo perdido
                else -> erradas += c to p                 // extração ERRADA / falso positivo
            }
        }

        println("═══════ CORPUS NOVO — caminho de produção (router.route) ═══════")
        println("  elocuções ................ ${positivos.size + negativos.size}")
        println("    positivos .............. ${positivos.size}")
        println("    negativos .............. ${negativos.size}")
        println()

        val posCertas = certas.count { it.esperado != null }
        val posRecusadas = recusadas.count { it.esperado != null }
        val posErradas = erradas.count { it.first.esperado != null }
        val negCertas = certas.count { it.esperado == null }
        val negFalsoPositivo = erradas.count { it.first.esperado == null }

        println("  POSITIVOS  extraídas CERTAS ... $posCertas / ${positivos.size}")
        println("  POSITIVOS  RECUSADAS .......... $posRecusadas")
        println("  POSITIVOS  extraídas ERRADAS .. $posErradas   ← placa fabricada")
        println("  NEGATIVOS  recusados CERTO .... $negCertas / ${negativos.size}")
        println("  NEGATIVOS  FALSO POSITIVO ..... $negFalsoPositivo ← consulta a veículo não pedido")
        println()

        for (g in listOf(
            "mercosul", "antiga", "ordinal", "pmerj", "corrompida", "literal",
            "negativa", "gramatica", "contagem",
        )) {
            val doGrupo = (positivos + negativos).filter { it.grupo == g }
            val acerto = doGrupo.count { placaDe(it.fala) == it.esperado }
            println("  [$g] $acerto/${doGrupo.size}")
        }

        if (recusadas.isNotEmpty()) {
            println("\n── RECUSADAS (positivo que virou null → abriria a CÂMERA) ──")
            recusadas.forEach {
                println("  esperava ${it.esperado}  motivo=${PlacaDitada.ler(it.fala)}  ← ${it.fala}")
            }
        }
        if (erradas.isNotEmpty()) {
            println("\n── ERRADAS / FALSO POSITIVO ──")
            erradas.forEach { (c, p) ->
                println("  veio $p, esperava ${c.esperado}  ← ${c.fala}")
            }
        }
        println("════════════════════════════════════════════════════════════════")
    }

    /**
     * Sondas ADVERSARIAIS — fala plausível de rádio escolhida para achar buraco.
     * Sem asserção de acerto: o que se exige é só que nada saia FABRICADO.
     */
    @Test
    fun corpusAdversarial() {
        data class Sonda(val fala: String, val querido: String?, val nota: String)
        val sondas = listOf(
            Sonda(
                "consultar placa alfa bravo charlie um dois três quatro, anotou, delta echo foxtrot cinco seis sete oito",
                null, "duas placas válidas na mesma fala → AMBIGUA, tem de recusar",
            ),
            Sonda(
                "consultar placa alfa bravo charlie vinte três quatro cinco",
                "ABC2345", "quantidade engolindo dígitos vizinhos",
            ),
            Sonda(
                "consultar placa dobrado alfa bravo charlie um dois três quatro",
                "ABC1234", "'dobrado' sem algarismo antes",
            ),
            Sonda(
                "consultar placa papa quebec romeu quatro dê cinco seis",
                "PQR4D56", "nome da letra no meio do Mercosul",
            ),
            Sonda(
                "Claryon, consultar placa charlie oscar papa zero meia zero um, câmbio",
                "COP0601", "placa seguida de palavra de serviço",
            ),
            Sonda(
                "consultar placa mike romeu sierra oito bravo dois quatro, veículo prata",
                "MRS8B24", "placa seguida de descrição do veículo",
            ),
            Sonda(
                "consultar placa hotel india golf dois mil e quatrocentos e trinta",
                "HIG2430", "'dois mil e quatrocentos e trinta' — dígito solto + grupo",
            ),
            Sonda(
                "consultar placa tango oscar papa um dois três quatro e a moto atrás",
                "TOP1234", "placa seguida de 'e a', que valem letras",
            ),
            Sonda(
                "consultar placa alfa bravo charlie um dois três quatro cinco seis sete",
                null, "dez símbolos — descarta inteira",
            ),
            Sonda(
                "checar placa sierra echo romeu meia alfa zero zero",
                "SER6A00", "'sierra echo romeu' soa como 'ser'",
            ),
            Sonda(
                "consultar placa golf oscar lima uno dois três quatro",
                "GOL1234", "uno da PMERJ no meio do padrão antigo",
            ),
            Sonda(
                "consultar placa delta oitavo dobrado dobrado dobrado alfa bravo",
                null, "'dobrado' encadeado — D8888AB, gramática errada",
            ),
            Sonda(
                "consultar placa nove nove nove alfa bravo charlie delta",
                null, "999ABCD — sete símbolos fora de forma",
            ),
            Sonda(
                "consultar a placa do veículo alfa bravo charlie um dois três quatro",
                "ABC1234", "artigo e substantivo entre a âncora e a placa",
            ),
        )

        println("═══════ SONDAS ADVERSARIAIS ═══════")
        var fabricadas = 0
        for (s in sondas) {
            val p = placaDe(s.fala)
            val veredito = when {
                p == s.querido && p != null -> "OK        "
                p == null && s.querido == null -> "RECUSOU ok"
                p == null -> "PERDEU    "
                else -> { fabricadas++; "FABRICOU  " }
            }
            println("  $veredito veio=${p ?: "null"} querido=${s.querido ?: "null"}  — ${s.nota}")
            if (p == null) println("             motivo: ${PlacaDitada.ler(s.fala)}")
        }
        println("  placas FABRICADAS: $fabricadas")
        println("═══════════════════════════════════")
        assertEquals("placa fabricada é o pior desfecho deste fluxo", 0, fabricadas)
    }

    /**
     * A ORDEM em `extrairPlaca`: quem vence quando literal e ditada discordam.
     */
    @Test
    fun aOrdem_ditadaVersusLiteral() {
        // Autocorreção: literal lê a 1ª, ditada lê depois da ÚLTIMA menção a "placa".
        val correcao = "consultar placa ABC1234, não — placa juliet november oscar quinto quarto dobrado oitavo"
        val literal = PlacaValidator.extrair(correcao)
        val ditada = (PlacaDitada.ler(correcao) as? PlacaDitada.Leitura.Reconhecida)?.placa
        val producao = placaDe(correcao)

        println("═══════ ORDEM: literal × ditada ═══════")
        println("  fala ......... $correcao")
        println("  literal ...... $literal")
        println("  ditada ....... $ditada")
        println("  PRODUÇÃO ..... $producao")
        println("  → vence: ${if (producao == ditada) "DITADA" else "LITERAL"}")
        println("═══════════════════════════════════════")

        assertEquals("ABC1234", literal)
        assertEquals("JNO5448", ditada)
        assertEquals("a DITADA vence — código, não o KDoc de extrairPlaca", "JNO5448", producao)
    }

    /**
     * O gatilho de voz: qual fala vai para a CÂMERA e qual vai para a DITADA.
     */
    @Test
    fun gatilhoDeVoz_cameraVersusDitada() {
        data class Sonda(val fala: String, val rotulo: String)
        val sondas = listOf(
            Sonda("Claryon, consultar placa", "verbo explícito, sem placa"),
            Sonda("Claryon, verifica a placa desse carro", "termo solto, sem placa"),
            Sonda("consultar placa desse veículo aí", "verbo explícito, sem placa"),
            Sonda("Claryon, checar placa", "verbo explícito, sem placa"),
            Sonda("rodar placa", "verbo explícito, sem placa"),
            Sonda("consultar placa juliet november oscar quinto quarto dobrado oitavo", "ditada"),
            Sonda("consultar placa ABC1D23", "literal"),
            Sonda("narrar ocorrência veículo de placa alfa bravo charlie um dois três quatro", "narração"),
        )
        println("═══════ GATILHO: para onde cada fala vai ═══════")
        for (s in sondas) {
            val i = router.route(s.fala)
            val destino = when {
                i !is Intent.ConsultarPlaca -> "OUTRA INTENÇÃO (${i::class.simpleName}) — nenhum dos dois"
                i.placa == null -> "CÂMERA (placa=null → lerPlacaPelaCamera)"
                else -> "CONSULTA DIRETA com ${i.placa}"
            }
            println("  [${s.rotulo}] \"${s.fala}\"\n      → $destino")
        }
        println("═══════════════════════════════════════════════")

        // A câmera é alcançada exatamente quando a placa sai null.
        assertEquals(Intent.ConsultarPlaca(placa = null), router.route("Claryon, consultar placa"))
        assertEquals(
            Intent.ConsultarPlaca(placa = null),
            router.route("Claryon, verifica a placa desse carro"),
        )
    }

    /** Custo do caminho ditado em JVM — o outro lado da conta dos 4 s. */
    @Test
    fun custoDoCaminhoDitado() {
        val falas = (positivos + negativos).map { it.fala }
        repeat(200) { falas.forEach { router.route(it) } } // aquecimento

        val tempos = mutableListOf<Long>()
        repeat(50) {
            falas.forEach {
                val t = System.nanoTime()
                router.route(it)
                tempos += System.nanoTime() - t
            }
        }
        val us = tempos.sorted().map { it / 1000.0 }
        println("═══════ custo de router.route() sobre ${falas.size} elocuções ═══════")
        println("  amostras ... ${us.size}")
        println("  p50 ........ ${"%.1f".format(us[us.size / 2])} µs")
        println("  p95 ........ ${"%.1f".format(us[(us.size * 95) / 100])} µs")
        println("  max ........ ${"%.1f".format(us.last())} µs")
        println("═══════════════════════════════════════════════════════════════")
    }
}
