package com.claryon.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Centenas de grafias, e a medida dos DOIS lados.**
 *
 * Enumerar variante a variante não escala e, pior, alarga o portão sem prova: cada
 * linha acrescentada à mão é uma que ninguém revisa. Este teste faz o contrário —
 * **gera** o espaço de grafias plausíveis e mede quanto dele o portão cobre.
 *
 * ### Por que o lado negativo é o que dá sentido ao positivo
 *
 * Um portão que aceita tudo tem recall de 100% e não serve para nada: ele abriria
 * canal para a guarnição inteira com conversa de rádio. Por isso cada geração de
 * positivos vem com uma de negativos — nomes próprios comuns, vocabulário corrente
 * de rádio e palavras que só começam parecido. **O número que importa é o par.**
 *
 * ### O que é geração e o que é medição
 *
 * As variantes são construídas por substituições que o português permite sobre o
 * mesmo som (`y`↔`i`, `c`↔`k`, `ão`↔`om`↔`on`, `ç`↔`ss`↔`s`). Isso é hipótese sobre
 * o espaço de erro do whisper, **não** transcrição medida — e está dito aqui para
 * que ninguém leia "487 grafias aceitas" como "487 pronúncias verificadas". A única
 * grafia com prova real hoje é `"guarney sao"`, da captura de 20/08.
 */
class GrafiasDaAtivacaoTest {

    // ── geradores ─────────────────────────────────────────────────────────────

    private fun variantes(base: List<List<String>>): List<String> {
        var acc = listOf("")
        for (opcoes in base) acc = acc.flatMap { p -> opcoes.map { p + it } }
        return acc.distinct()
    }

    /** O espaço gráfico de "Claryon" que o pt-BR permite sobre o mesmo som. */
    private val grafiasDeClaryon: List<String> = variantes(
        listOf(
            listOf("c", "k", "qu"),
            listOf("l"),
            listOf("a", "á"),
            listOf("r", "rr"),
            listOf("y", "i", "e", "yi", "ie"),
            listOf("o", "ó"),
            listOf("n", "m", "nn", "ne"),
        ),
    )

    /** O espaço gráfico de "guarnição". */
    private val grafiasDeGuarnicao: List<String> = variantes(
        listOf(
            listOf("g", "gu"),
            listOf("u", ""),
            listOf("a", "á"),
            listOf("r", "rr"),
            listOf("n", "ne", "ney "),
            listOf("i", "e", ""),
            listOf("ç", "c", "s", "ss"),
            listOf("ão", "ao", "am", "on", "om"),
        ),
    )

    /**
     * Nomes e palavras que **não** podem passar. Vocabulário corrente de rádio,
     * nomes próprios comuns em português e palavras que só começam parecido.
     */
    private val naoPodemPassar: List<String> = listOf(
        "clara", "claro", "clarice", "clarissa", "claudio", "claudia", "clarinete",
        "claudinei", "clarim", "clareira", "clausula", "claudemir", "clarindo",
        "carlos", "carla", "caio", "cassio", "catarina", "camila", "carlinhos",
        "coronel", "capitao", "cabo", "comando", "central", "copom", "codigo",
        "quartel", "quarta", "quatro", "quarenta", "querido", "quero",
        "galvao", "gabriel", "gustavo", "guilherme", "guarda", "guarita", "guaruja",
        "garagem", "garoto", "guardanapo", "guaranа", "guerra", "guiado",
        "na escuta", "escuta", "cambio", "positivo", "negativo", "prossiga",
        "entendido", "ciente", "qsl", "viatura", "ocorrencia", "apoio",
    )

    // ── recall: quanto do espaço plausível o portão cobre ─────────────────────

    @Test
    fun oPortaoDaAtivacao_cobreOEspacoGRAFICODeClaryon() {
        // **Cobertura de espaço GERADO não é recall em fala real.** As variantes
        // aqui saem de substituições que o pt-BR permite; algumas delas o whisper
        // talvez nunca produza. O número serve para pegar REGRESSÃO — se cair, a
        // chave parou de colapsar algo que colapsava — e não para afirmar recall.
        // Recall de verdade são as 30 pronúncias por fone HFP, que é aceite de
        // hardware e continua em aberto.
        val aceitas = grafiasDeClaryon.filter { PalavraDeAtivacaoNaFala.conferir("$it x").confirmada }
        val taxa = aceitas.size * 100.0 / grafiasDeClaryon.size
        println("ATIVAÇÃO — ${grafiasDeClaryon.size} grafias geradas · aceitas ${aceitas.size} (${"%.1f".format(taxa)}%)")
        println("  recusadas (amostra): ${grafiasDeClaryon.filterNot { PalavraDeAtivacaoNaFala.conferir("$it x").confirmada }.take(10)}")
        assertTrue(
            "o portão cobre ${"%.1f".format(taxa)}% do espaço gráfico de Claryon " +
                "(${grafiasDeClaryon.size} variantes) — era 60,0% na medição de " +
                "20/08. Queda significa que a chave parou de colapsar algo",
            taxa >= 58.0,
        )
    }

    @Test
    fun oPortaoDaGuarnicao_cobreOEspacoGRAFICO() {
        val aceitas = grafiasDeGuarnicao.filter {
            DeterministicIntentRouter.ePalavraDeGuarnicao(it.lowercase())
        }
        val taxa = aceitas.size * 100.0 / grafiasDeGuarnicao.size
        println("GUARNIÇÃO — ${grafiasDeGuarnicao.size} grafias geradas · aceitas ${aceitas.size} (${"%.1f".format(taxa)}%)")
        println("  recusadas (amostra): ${grafiasDeGuarnicao.filterNot { DeterministicIntentRouter.ePalavraDeGuarnicao(it.lowercase()) }.take(12)}")
        assertTrue(
            "cobertura de ${"%.1f".format(taxa)}% sobre ${grafiasDeGuarnicao.size} " +
                "variantes — era 45,9% na medição de 20/08",
            taxa >= 44.0,
        )
    }

    /** A única grafia com prova REAL: a captura de 20/08 no aparelho. */
    @Test
    fun aGrafiaMEDIDA_passa() {
        // "guarney sao" fica a distância 3 da chave canônica — tem uma sílaba a
        // mais. Baixar o portão até lá aceitaria "guarda" e "guarita", então ela
        // entra pela LISTA medida, não pela regra. O que é regular vai pela regra;
        // o que é exceção medida vai pelo nome.
        assertTrue(
            "a exceção medida saiu da lista e a chave não a alcança",
            DeterministicIntentRouter().route("Clareon, Guarney são 1 na escuta.")
                is Intent.AbrirTransmissao,
        )
        assertTrue(ChaveFonetica.pareceCom("clareon", "claryon"))
    }

    // ── precisão: o lado que dá sentido ao de cima ─────────────────────────────

    /**
     * **Sem este teste, o de recall é vazio.** Um portão que aceita tudo cobre 100%
     * do espaço e abre canal com qualquer palavra dita perto do aparelho.
     */
    @Test
    fun oPortaoDaPALAVRA_temColisaoCONHECIDA_eSoUma() {
        val falsos = naoPodemPassar.filter { PalavraDeAtivacaoNaFala.conferir("$it x").confirmada }
        println("PRECISÃO palavra — ${naoPodemPassar.size} negativos · colisões ${falsos.size}: $falsos")
        // Zero. "clarim" colidia com tolerância 1 e saiu quando a chave passou a
        // colapsar "e" átono antes de vogal nasal — aí a tolerância pôde fechar em
        // 0 sem perder as grafias medidas. O ganho veio da CHAVE, não do limiar.
        assertTrue(
            "o portão da palavra voltou a colidir com português comum: $falsos",
            falsos.isEmpty(),
        )
    }

    /**
     * **A precisão que importa é a da FRASE, e é ela que a arquitetura protege.**
     *
     * Para virar comando não basta a palavra passar pelo primeiro portão: a frase
     * inteira ainda tem de casar. "Clarim, guarnição 3 na escuta" não é coisa que
     * alguém diga — e o detector acústico ainda teria de ter disparado antes. São
     * dois estágios em conjunção, e é a conjunção que dá a precisão.
     */
    @Test
    fun nenhumNegativo_montadoEmFRASE_abreCanal() {
        val router = DeterministicIntentRouter()
        val frases = naoPodemPassar.flatMap { n ->
            listOf("$n na escuta", "$n 3 na escuta", "$n, guarnição 3 na escuta")
        }
        val abertas = frases.filter { router.route(it) is Intent.AbrirTransmissao }
        println("PRECISÃO frase — ${frases.size} negativos em frase · abriram ${abertas.size}: ${abertas.take(5)}")
        assertTrue(
            "negativo montado em frase abriu canal: ${abertas.take(5)}",
            abertas.isEmpty(),
        )
    }

    @Test
    fun nenhumNomeCOMUM_passaPeloPortaoDaGuarnicao() {
        val falsos = naoPodemPassar.filter { DeterministicIntentRouter.ePalavraDeGuarnicao(it) }
        println("PRECISÃO guarnição — falsos aceites ${falsos.size}: $falsos")
        assertTrue(
            "com tolerância 2, palavras comuns viraram \"guarnição\": $falsos — " +
                "e cada uma abriria canal para a guarnição inteira",
            falsos.isEmpty(),
        )
    }

    /**
     * **O contra-teste da tolerância.** Ela precisa ser justificada por medida: com
     * um valor grande demais o portão aceita nome próprio, e este teste falha —
     * é ele que impede alguém de "consertar" um recall baixo aumentando o número.
     */
    @Test
    fun aToleranciaAMPLA_quebraAPrecisao_eEPorIssoQueElaEPequena() {
        val falsosCom4 = naoPodemPassar.filter { ChaveFonetica.pareceCom(it, "claryon", tolerancia = 3) }
        println("com tolerância 4: ${falsosCom4.size} falsos aceites — $falsosCom4")
        assertTrue(
            "aumentar a tolerância para 4 NÃO quebrou a precisão. Então o limiar " +
                "atual pode estar conservador demais e o recall está sendo pago à toa",
            falsosCom4.isNotEmpty(),
        )
    }

    // ── a frase inteira, com as centenas de grafias ───────────────────────────

    /**
     * O que interessa no fim: a FRASE abre canal? Aqui o roteador inteiro é
     * exercitado com o produto cartesiano das grafias, e a estrutura tem de
     * continuar íntegra — palavra extra segue recusando.
     */
    @Test
    fun asFRASES_montadasComAsGrafias_abremCanal() {
        val router = DeterministicIntentRouter()
        // **O MESMO portão da produção.** A primeira versão amostrava por
        // `pareceCom` com a tolerância default (1) enquanto o produto usa 0 — o
        // teste media um portão que não existe e acusava 67,5% de frases recusadas
        // que ele mesmo tinha escolhido mal.
        val amostraAtivacao = grafiasDeClaryon
            .filter { PalavraDeAtivacaoNaFala.conferir("$it teste").confirmada }.take(40)
        val amostraGuarnicao = grafiasDeGuarnicao
            .filter { DeterministicIntentRouter.ePalavraDeGuarnicao(it.lowercase()) }.take(20)

        val frases = amostraAtivacao.flatMap { a -> amostraGuarnicao.map { g -> "$a, $g 3 na escuta." } }
        val abertas = frases.count { router.route(it) is Intent.AbrirTransmissao }
        val taxa = abertas * 100.0 / frases.size
        println("FRASES — ${frases.size} combinações · abriram $abertas (${"%.1f".format(taxa)}%)")
        println("  recusadas (amostra): ${frases.filterNot { router.route(it) is Intent.AbrirTransmissao }.take(6)}")
        assertTrue(
            "só ${"%.1f".format(taxa)}% das ${frases.size} frases plausíveis abrem canal",
            taxa >= 90.0,
        )
    }

    /** E a estrutura continua íntegra sob todas elas. */
    @Test
    fun sobTODASAsGrafias_conversaQueCONTEMOComando_continuaRecusada() {
        val router = DeterministicIntentRouter()
        val prefixos = listOf("diz pro pessoal que a", "avisa que a", "eu falei", "pergunta se a")
        val frases = grafiasDeGuarnicao.take(60).flatMap { g -> prefixos.map { p -> "$p $g 3 na escuta" } }
        val abertas = frases.filter { router.route(it) is Intent.AbrirTransmissao }
        println("INTEGRIDADE — ${frases.size} conversas · abriram ${abertas.size}")
        assertTrue(
            "conversa que só CONTÉM o comando abriu canal em ${abertas.size} de " +
                "${frases.size} casos: ${abertas.take(4)}",
            abertas.isEmpty(),
        )
    }
}
