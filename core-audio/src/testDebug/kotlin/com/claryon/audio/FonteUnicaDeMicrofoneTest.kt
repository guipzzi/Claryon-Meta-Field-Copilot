package com.claryon.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * O que estes testes protegem é regra de compliance, não desempenho.
 *
 * Dois `AudioRecord` concorrentes foram o caminho por onde o pré-roll passava a
 * ouvir o microfone do celular; a rota conferida só na abertura foi o outro. Os
 * dois são invisíveis numa suíte que testa "o áudio chega" — chega dos dois jeitos.
 *
 * A fonte roda num escopo **próprio**, e não no do `runTest`: `shareIn` com
 * `WhileSubscribed` deixa uma corrotina viva esperando o próximo assinante, e
 * `runTest` espera os filhos terminarem. Filha desta seria `UncompletedCoroutinesError`
 * em todo teste — que foi exatamente o que aconteceu na primeira versão daqui.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FonteUnicaDeMicrofoneTest {

    private val rota = rotaDeTeste(42)

    /**
     * Captura falsa. `delay(20)` por quadro é o que dá à fonte o mesmo ritmo do
     * `AudioRecord` real — em tempo virtual, de graça. Sem ele o laço giraria sem
     * suspender e nenhum outro teste avançaria.
     */
    private class CapturaFalsa(private val fechamentos: AtomicInteger) :
        FonteUnicaDeMicrofone.CapturaBruta {
        override val duracaoDoQuadroMs = 20L
        private var n = 0
        override suspend fun ler(): ShortArray {
            delay(duracaoDoQuadroMs)
            return shortArrayOf(n++.toShort())
        }
        override fun fechar() {
            fechamentos.incrementAndGet()
        }
    }

    /** Roda [corpo] com uma fonte num escopo próprio, sempre encerrado no fim. */
    private suspend fun TestScope.comFonte(
        confere: (GlassesAudioRoute) -> Boolean = { true },
        aoDescartar: (Int) -> Unit = {},
        abrir: (() -> FonteUnicaDeMicrofone.CapturaBruta)? = null,
        corpo: suspend (
            fonte: FonteUnicaDeMicrofone,
            aberturas: AtomicInteger,
            fechamentos: AtomicInteger,
        ) -> Unit,
    ) {
        val aberturas = AtomicInteger()
        val fechamentos = AtomicInteger()
        val escopoDaFonte = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fonte = FonteUnicaDeMicrofone(
            escopo = escopoDaFonte,
            confereRota = confere,
            abrirCaptura = {
                aberturas.incrementAndGet()
                abrir?.invoke() ?: CapturaFalsa(fechamentos)
            },
            aoDescartar = aoDescartar,
        )
        try {
            corpo(fonte, aberturas, fechamentos)
        } finally {
            escopoDaFonte.cancel()
        }
    }

    @Test
    fun `dois consumidores simultaneos abrem um unico AudioRecord`() = runTest {
        comFonte { fonte, aberturas, _ ->
            // Exatamente o cenário do PTT: o pré-roll já está capturando quando a
            // captura ao vivo entra.
            val preRoll = launch { fonte.pcm(rota).take(5).toList() }
            yield()
            val aoVivo = launch { fonte.pcm(rota).take(5).toList() }

            preRoll.join()
            aoVivo.join()

            assertEquals("dois consumidores, um microfone", 1, aberturas.get())
        }
    }

    @Test
    fun `o microfone fecha quando o ultimo consumidor sai`() = runTest {
        comFonte { fonte, aberturas, fechamentos ->
            fonte.pcm(rota).take(3).toList()
            // `WhileSubscribed` sem timeout: a saída do último assinante fecha na hora.
            yield()

            assertEquals(1, aberturas.get())
            assertEquals(
                "microfone aberto sem ninguém ouvindo é gravação ambiente",
                1,
                fechamentos.get(),
            )
        }
    }

    @Test
    fun `rota que cai no meio do stream derruba a captura com erro tipado`() = runTest {
        var chamadas = 0
        // Verdadeira na abertura, falsa depois — é a queda de HFP no meio da fala.
        comFonte(confere = { chamadas++ < 1 }) { fonte, _, _ ->
            val erro = runCatching {
                // Reconferência a cada 200 ms / 20 ms por quadro: a falha chega no 10º.
                fonte.pcm(rota).take(50).toList()
            }.exceptionOrNull()

            assertTrue(
                "capturar por rota caída é captar terceiros: tem de lançar, não parar em silêncio",
                erro is RotaDeAudioPerdidaException,
            )
        }
    }

    @Test
    fun `falha da captura chega ao consumidor, e nao vira silencio`() = runTest {
        val quebrada = {
            object : FonteUnicaDeMicrofone.CapturaBruta {
                override val duracaoDoQuadroMs = 20L
                private var n = 0
                override suspend fun ler(): ShortArray {
                    delay(duracaoDoQuadroMs)
                    if (n++ >= 2) throw AudioCaptureException(-6)
                    return shortArrayOf(1)
                }
                override fun fechar() = Unit
            }
        }

        comFonte(abrir = quebrada) { fonte, _, _ ->
            // Um `SharedFlow` engole exceção: sem a conversão em valor e de volta
            // em exceção na borda, isto seria um `collect` que simplesmente para
            // de emitir — e o agente ouviria "ninguém está falando" no lugar de
            // "os óculos caíram".
            val erro = runCatching { fonte.pcm(rota).toList() }.exceptionOrNull()
            assertTrue("esperava AudioCaptureException, veio $erro", erro is AudioCaptureException)
            assertEquals(-6, (erro as AudioCaptureException).codigo)
        }
    }

    @Test
    fun `consumidor lento perde quadro dele, e o descarte e contado`() = runTest {
        val descartes = AtomicInteger()
        comFonte(aoDescartar = { descartes.addAndGet(it) }) { fonte, _, _ ->
            // Um consumidor que some por 2 s: passam ~100 quadros e o buffer dele
            // guarda 50. Ao voltar a ler, a lacuna na sequência é o descarte.
            val lento = launch {
                var primeiro = true
                fonte.pcm(rota).take(60).collect {
                    if (primeiro) {
                        primeiro = false
                        delay(2_000)
                    }
                }
            }
            lento.join()

            assertTrue(
                "descarte silencioso é a versão sutil de falha em silêncio",
                descartes.get() > 0,
            )
        }
    }
}
