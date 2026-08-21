package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **O banco de elocuções — e os dois números que importam são de lados opostos.**
 *
 * Um extrator que aceita tudo tem recall de 100% e é o pior produto possível deste
 * fluxo: ele consulta um veículo aleatório e devolve "sem restrição" ao agente, que
 * libera o carro. Por isso o banco tem **dois lados**, e nenhum número aqui vale
 * sozinho.
 *
 * ### O que é medido e o que é hipótese
 *
 * As elocuções são escritas **como o whisper as devolveria**, com a pontuação e a
 * caixa que ele produz. Isso é hipótese sobre o espaço de erro do modelo, informada
 * pelos erros que este projeto já viu de verdade ("Guarney são" por "guarnição",
 * "Blerium" por "Claryon"), **não** transcrição capturada. Está dito aqui para que
 * ninguém leia "N de N extraídas" como "N pronúncias verificadas em campo" — a
 * medição com fala humana pelo fone HFP é outro trabalho, e está nos quebrados do
 * `ESTADO.md`.
 *
 * O que o banco mede de verdade é a **lógica do extrator**: contiguidade, âncora,
 * sete exatos e gramática. Essas quatro não dependem de qual voz falou.
 */
class BancoDePlacasDitadasTest {

    // ── o banco ───────────────────────────────────────────────────────────────

    /** Uma elocução e o que ela DEVE produzir. `null` = tem de ser recusada. */
    private data class Caso(val fala: String, val esperado: String?)

    private fun ok(fala: String, placa: String) = Caso(fala, placa)
    private fun nao(fala: String) = Caso(fala, null)

    /**
     * Placas ditadas em alfabeto fonético, do jeito limpo.
     *
     * O caso da spec (*"tango bravo unido três delta sete zero"* → `TBU3D70`) é o
     * primeiro, porque ele é o contrato escrito.
     */
    private val foneticasLimpas = listOf(
        ok("Claryon, consultar placa tango bravo unido três delta sete zero.", "TBU3D70"),
        ok("Claryon, verificar placa alfa bravo charlie um dois três quatro.", "ABC1234"),
        ok("checar placa romeu índia oscar sete alfa dois um", "RIO7A21"),
        ok("rodar placa sierra papa oscar nove kilo zero zero", "SPO9K00"),
        ok("consultar placa lima uniforme zulu meia bravo zero um", "LUZ6B01"),
        ok("Claryon, consultar placa golf oscar lima quatro delta meia sete.", "GOL4D67"),
        ok("verificar placa mike novembro papa oito hotel quatro cinco", "MNP8H45"),
        ok("consultar placa echo delta uniforme dois foxtrot nove nove", "EDU2F99"),
        ok("rodar placa quebec romeu sierra zero tango um oito", "QRS0T18"),
        ok("checar placa whisky xis yankee três zulu dois seis", "WXY3Z26"),
        ok("consultar placa juliet kilo lima cinco mike sete quatro", "JKL5M74"),
        ok("verificar placa alfa alfa alfa zero alfa zero zero", "AAA0A00"),
    )

    /**
     * As mesmas ditadas, agora com o erro do whisper.
     *
     * Vogal final trocada ("tangu"), consoante comida, palavra estrangeira
     * aportuguesada. É o caso real: o modelo transcreve pt-BR e o alfabeto tem
     * palavra de outra língua.
     */
    private val foneticasCorrompidas = listOf(
        ok("Claryon, consultar placa tangu bravu unido três delta sete zero.", "TBU3D70"),
        ok("verificar placa alfa bravu charli um dois três quatro", "ABC1234"),
        ok("consultar placa romeo india oscar sete alfa dois um", "RIO7A21"),
        ok("rodar placa siera papa oscar nove quilo zero zero", "SPO9K00"),
        ok("checar placa lima uniform zulu meia bravo zero um", "LUZ6B01"),
        ok("consultar placa golfe oscar lima quatro delta meia sete", "GOL4D67"),
        ok("verificar placa maike november papa oito hotél quatro cinco", "MNP8H45"),
        ok("consultar placa eco delta unido dois foxtrote nove nove", "EDU2F99"),
        ok("rodar placa quebeque romeu serra zero tangô um oito", "QRS0T18"),
        ok("checar placa uísque xis ianque três zulu dois seis", "WXY3Z26"),
    )

    /**
     * Placa dita **sem** alfabeto: soletrada letra a letra, ou com o número por
     * extenso, que é como um agente fala quando o canal está limpo.
     */
    private val semAlfabeto = listOf(
        ok("consultar placa ABC mil duzentos e trinta e quatro", "ABC1234"),
        ok("verificar placa ABC 1D23", "ABC1D23"),
        ok("Claryon, consultar placa ABC-1234.", "ABC1234"),
        ok("checar placa a bê cê um dois três quatro", "ABC1234"),
        ok("rodar placa QWE mil e vinte e três", "QWE1023"),
        ok("consultar placa BRA dois E dezenove", "BRA2E19"),
        ok("verificar placa RIO 2 A 18", "RIO2A18"),
        ok("consultar placa FGH nove sete meia dois", "FGH9762"),
        ok("checar placa JKL zero zero zero um", "JKL0001"),
        ok("rodar placa PQR quatro S cinco seis", "PQR4S56"),
    )

    /**
     * **Os algarismos ORDINAIS — o jeito que a PM manda ditar, e que eu não sabia.**
     *
     * A Portaria 071-CG/15 da PM da Bahia traz a ditada de `5448` como *"Quinto;
     * Quarto Dobrado; Oitavo"*. O primeiro caso abaixo é essa sequência, com as três
     * letras que a acompanham no próprio texto da norma.
     *
     * Os últimos são o sistema concorrente da PMERJ, com `uno`, `meia` e o
     * multiplicador **antes** do algarismo.
     */
    private val ordinaisERepetidores = listOf(
        // "JNO – 5448": o exemplo textual da norma, virado placa de sete.
        ok("consultar placa juliet november oscar quinto quarto dobrado oitavo", "JNO5448"),
        ok("verificar placa alfa bravo charlie primeiro segundo terceiro quarto", "ABC1234"),
        ok("checar placa alfa bravo charlie quinto negativo negativo primeiro", "ABC5001"),
        ok("rodar placa lima uniforme zulu sexto bravo negativo primeiro", "LUZ6B01"),
        // PMERJ: multiplicador ANTES.
        ok("consultar placa alfa bravo charlie uno duplo zero sete", "ABC1007"),
        ok("verificar placa triplo alfa negativo alfa duplo zero", "AAA0A00"),
        ok("consultar placa golf oscar lima quatro delta meia sete", "GOL4D67"),
        ok("checar placa tango bravo uniforme terceiro delta setimo negativo", "TBU3D70"),
    )

    /**
     * **Fala que NÃO é placa.** Cada uma tem de ser recusada.
     *
     * As três primeiras são as do enunciado; o resto é vocabulário de rádio e de
     * abordagem escolhido para ser **adversarial**: contém palavra do alfabeto
     * ("papa", "lima", "serra", "delta"), contém número, ou as duas coisas.
     */
    private val negativas = listOf(
        nao("Claryon, vou verificar a placa depois."),
        nao("a placa do carro tá suja"),
        nao("Claryon, consultar placa."),
        nao("verificar placa do veículo que passou agora"),
        nao("a placa tá ilegível daqui"),
        nao("consultar placa de moto, não deu pra ver o número"),
        nao("Claryon, a placa não bate com o documento."),
        nao("checar placa mas o carro já saiu"),
        nao("placa dianteira arrancada, traseira intacta"),
        nao("rodar placa desse Golf preto na esquina"),
        nao("a placa é de outro estado, acho que é do Rio"),
        nao("consultar placa amanhã de manhã, agora não dá"),
        nao("tem uma lima e um papa na viatura"),
        nao("placa parcialmente coberta por lama"),
        nao("Claryon, verificar placa: não consegui ler nada."),
        nao("a placa some atrás do para-choque"),
        nao("veículo sem placa, abordagem em andamento"),
        nao("placa de papel colada no vidro"),
        nao("consultar placa do carro da serra, subiu pela delta"),
        nao("Claryon, a placa tem três letras e quatro números."),
        nao("placa velha, dessas antigas cinza"),
        nao("verificar placa, câmbio"),
        // Adversariais para os ORDINAIS: são palavras corriqueiras de rádio.
        nao("consultar placa, é o segundo carro da fila"),
        nao("verificar placa do quarto veículo, o primeiro já passou"),
        nao("Claryon, negativo, a placa não confere."),
        nao("checar placa em segundo plano, prioridade é o apoio"),
        nao("placa do terceiro andar do estacionamento, quinto box"),
        nao("consultar placa, negativo negativo, não deu pra ver"),
    )

    /**
     * **Placas inventadas: sete símbolos na ordem errada.**
     *
     * Este é o lado que a gramática existe para pegar. Se a validação sumir, TODAS
     * estas passam a ser aceitas — e é isso que o contra-teste prova.
     */
    private val gramaticaErrada = listOf(
        nao("consultar placa alfa bravo um dois três quatro cinco"),   // AB12345
        nao("verificar placa um dois três alfa bravo charlie delta"),  // 123ABCD
        nao("checar placa alfa bravo charlie delta um dois três"),     // ABCD123
        nao("rodar placa um alfa bravo charlie dois três quatro"),     // 1ABC234
        nao("consultar placa alfa um bravo dois charlie três quatro"), // A1B2C34
        nao("verificar placa alfa bravo charlie delta echo foxtrot golf"), // ABCDEFG
        nao("consultar placa um dois três quatro cinco seis sete"),    // 1234567
        nao("checar placa alfa bravo charlie um delta dois echo"),     // ABC1D2E
    )

    /**
     * **Contagem errada: seis, oito, nove símbolos.** Descartadas inteiras, sem
     * procurar uma placa boa lá dentro.
     */
    private val contagemErrada = listOf(
        nao("consultar placa alfa bravo charlie um dois três"),                    // 6
        nao("verificar placa alfa bravo charlie um dois três quatro cinco"),       // 8
        nao("checar placa alfa bravo charlie delta um dois três quatro cinco"),    // 9
        nao("consultar placa ABC12345"),
        nao("verificar placa 12ABC1234"),
        nao("rodar placa ABC1D234"),
        nao("consultar placa tango bravo unido três delta sete"),                  // 6
        nao("checar placa tango bravo unido três delta sete zero nove"),           // 8
    )

    private val banco: List<Caso> =
        foneticasLimpas + foneticasCorrompidas + semAlfabeto + ordinaisERepetidores +
            negativas + gramaticaErrada + contagemErrada

    // ── medição ───────────────────────────────────────────────────────────────

    private data class Placar(
        val extraidas: Int,
        val erradas: Int,
        val perdidas: Int,
        val recusadas: Int,
        val falsosPositivos: Int,
    ) {
        val positivos get() = extraidas + erradas + perdidas
        val negativos get() = recusadas + falsosPositivos
    }

    private fun medir(
        casos: List<Caso>,
        aproximar: Boolean = true,
        ancorar: Boolean = true,
    ): Placar {
        var extraidas = 0; var erradas = 0; var perdidas = 0
        var recusadas = 0; var falsos = 0
        for (c in casos) {
            val lida = (PlacaDitada.ler(c.fala, aproximar, ancorar) as? PlacaDitada.Leitura.Reconhecida)?.placa
            when {
                c.esperado != null && lida == c.esperado -> extraidas++
                c.esperado != null && lida != null -> erradas++
                c.esperado != null -> perdidas++
                lida == null -> recusadas++
                else -> falsos++
            }
        }
        return Placar(extraidas, erradas, perdidas, recusadas, falsos)
    }

    private fun relatorio(nome: String, p: Placar): String = buildString {
        appendLine("── $nome ──")
        appendLine("  positivos: ${p.positivos} | extraídas certas: ${p.extraidas} " +
            "| extraídas ERRADAS: ${p.erradas} | perdidas: ${p.perdidas}")
        appendLine("  negativos: ${p.negativos} | recusadas: ${p.recusadas} " +
            "| FALSOS POSITIVOS: ${p.falsosPositivos}")
    }

    // ── os aceites ────────────────────────────────────────────────────────────

    @Test
    fun oBancoTemPeloMenos60Elocucoes() {
        assertTrue("banco tem ${banco.size} elocuções", banco.size >= 60)
    }

    /**
     * **Falso positivo é o número que mais importa, e o aceite dele é ZERO.**
     *
     * Não é rigor gratuito: cada falso positivo aqui é uma consulta a um veículo que
     * ninguém pediu, e o resultado dela chega ao agente como se fosse do carro à
     * frente dele. Recall abaixo de 100% custa uma repetição; precisão abaixo de
     * 100% custa uma decisão errada em abordagem.
     */
    @Test
    fun nenhumaFalaQueNaoEPlacaViraPlaca() {
        val p = medir(negativas + gramaticaErrada + contagemErrada)
        val culpadas = (negativas + gramaticaErrada + contagemErrada).filter {
            PlacaDitada.ler(it.fala) is PlacaDitada.Leitura.Reconhecida
        }
        assertEquals(
            "falso positivo em: ${culpadas.map { it.fala }}\n" + relatorio("negativos", p),
            0,
            p.falsosPositivos,
        )
    }

    /**
     * **As duas hipóteses, ligadas e desligadas, sobre o banco inteiro.**
     *
     * Nenhuma das duas foi escolhida por gosto. A tabela impressa aqui é o que
     * justifica o padrão de cada uma, e é ela que precisa ser refeita se alguém
     * mudar o padrão. Se uma das hipóteses não melhorar nada, ela é peso morto e
     * deve sair — o teste existe para tornar isso visível, não para aprovar.
     */
    @Test
    fun asDuasHipoteses_medidasNasQuatroCombinacoes() {
        val positivos = foneticasLimpas + foneticasCorrompidas + semAlfabeto + ordinaisERepetidores
        val negativos = negativas + gramaticaErrada + contagemErrada
        println("── som × âncora, sobre ${banco.size} elocuções ──")
        for (som in listOf(true, false)) {
            for (ancora in listOf(true, false)) {
                val p = medir(positivos, som, ancora)
                val n = medir(negativos, som, ancora)
                println(
                    "  som=$som âncora=$ancora → recall ${p.extraidas}/${p.positivos} " +
                        "| erradas ${p.erradas} | falsos positivos ${n.falsosPositivos}/${n.negativos}",
                )
            }
        }
    }

    @Test
    fun oBancoInteiro_eImpresso() {
        val todos = medir(banco)
        println(relatorio("BANCO INTEIRO (${banco.size} elocuções)", todos))
        println(relatorio("fonéticas limpas", medir(foneticasLimpas)))
        println(relatorio("fonéticas corrompidas pelo STT", medir(foneticasCorrompidas)))
        println(relatorio("sem alfabeto", medir(semAlfabeto)))
        println(relatorio("ordinais e repetidores", medir(ordinaisERepetidores)))
        println(relatorio("negativos (fala corrente)", medir(negativas)))
        println(relatorio("gramática errada", medir(gramaticaErrada)))
        println(relatorio("contagem errada", medir(contagemErrada)))
        // Extraída ERRADA é pior que perdida: é consulta com dado fabricado.
        assertEquals("nenhuma elocução pode produzir placa diferente da ditada", 0, todos.erradas)
    }
}
