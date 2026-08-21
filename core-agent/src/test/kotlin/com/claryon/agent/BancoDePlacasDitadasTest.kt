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
    //
    // Os dados moram em `BancoDePlacasDitadas`, e não mais aqui, desde que
    // `PlacaDitadaNoRoteadorTest` passou a precisar dos MESMOS negativos: duas
    // cópias divergem no primeiro negativo que alguém acrescenta a uma só delas. A
    // proveniência de cada grupo está no KDoc de lá; a medição continua aqui.

    private val foneticasLimpas = BancoDePlacasDitadas.foneticasLimpas
    private val foneticasCorrompidas = BancoDePlacasDitadas.foneticasCorrompidas
    private val semAlfabeto = BancoDePlacasDitadas.semAlfabeto
    private val ordinaisERepetidores = BancoDePlacasDitadas.ordinaisERepetidores
    private val negativas = BancoDePlacasDitadas.negativas
    private val gramaticaErrada = BancoDePlacasDitadas.gramaticaErrada
    private val contagemErrada = BancoDePlacasDitadas.contagemErrada

    private val banco: List<BancoDePlacasDitadas.Caso> = BancoDePlacasDitadas.banco

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
        casos: List<BancoDePlacasDitadas.Caso>,
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
