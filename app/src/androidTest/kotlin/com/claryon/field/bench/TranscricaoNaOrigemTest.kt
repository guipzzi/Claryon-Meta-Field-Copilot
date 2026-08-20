package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.common.Result
import com.claryon.field.voice.EscutaDoAgente
import com.claryon.net.AcumuladorDePcm
import com.claryon.net.ClienteDePisoRemoto
import com.claryon.net.ConfigOpus
import com.claryon.net.ConfigRealtime
import com.claryon.net.MediaCodecOpus
import com.claryon.net.PreRollBuffer
import com.claryon.net.PrioridadeTransmissao
import com.claryon.net.SessaoPtt
import com.claryon.net.TransporteRealtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * **A corrente inteira da transcrição na origem, com fala humana e servidor real.**
 *
 * Até aqui o P1 estava provado por partes: o acumulador tem teste de unidade, o
 * quarto evento tem teste de protocolo, e o encadeamento tem `grep` mostrando
 * chamador. Nada disso prova que **falar produz texto do outro lado** — e este
 * projeto tem por regra que teste verde do caminho feliz não prova que os outros
 * caminhos existem.
 *
 * Aqui roda o caminho de verdade: PCM humano → `SessaoPtt` → codec Opus real →
 * canal privado com JWT → acumulador → whisper → `fala.transcricao` difundido.
 *
 * ## O que é simulado, e é uma coisa só
 *
 * **O microfone.** O emulador não tem captura de áudio utilizável, então o PCM entra
 * pelo mesmo `Flow<ShortArray>` que a `FonteUnicaDeMicrofone` alimentaria — lendo a
 * gravação real do agente. Do `SessaoPtt` em diante **nada** é simulado: codec de
 * verdade, socket de verdade, RLS de verdade, whisper de verdade.
 *
 * ## Credenciais e áudio
 *
 * ```
 * adb shell am instrument -w \
 *   -e par_email 123456789@claryon.invalid -e par_senha ... \
 *   -e par_grupo 22222222-... \
 *   -e class com.claryon.field.bench.TranscricaoNaOrigemTest ...
 * ```
 *
 * Sem os argumentos ou sem o WAV o teste **pula**, não falha.
 */
@RunWith(AndroidJUnit4::class)
class TranscricaoNaOrigemTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val taxa = 16_000

    private fun arg(n: String): String? =
        InstrumentationRegistry.getArguments().getString(n)?.takeIf { it.isNotBlank() }

    private val pasta: File
        get() = File(ctx.getExternalFilesDir(null), "bench")

    private fun lerWav(f: File): ShortArray {
        val b = f.readBytes()
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val tam = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(tam, b.size - i - 8) / 2
                return ShortArray(n) { k ->
                    val p = i + 8 + k * 2
                    (((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)).toShort()
                }
            }
            i += 8 + tam + (tam and 1)
        }
        return ShortArray(0)
    }

    private fun autenticar(url: String, anon: String, email: String, senha: String): String? =
        runCatching {
            val req = Request.Builder()
                .url("${url.trimEnd('/')}/auth/v1/token?grant_type=password")
                .addHeader("apikey", anon)
                .post(
                    JSONObject().put("email", email).put("password", senha).toString()
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            OkHttpClient().newCall(req).execute().use { r ->
                JSONObject(r.body?.string().orEmpty()).optString("access_token").ifEmpty { null }
            }
        }.getOrNull()

    @Test
    fun falarProduzTextoDifundidoNoCanalPrivado(): Unit = runBlocking {
        val email = arg("par_email")
        val senha = arg("par_senha")
        val grupo = arg("par_grupo")
        Assume.assumeTrue("faltam -e par_email/par_senha/par_grupo", email != null && senha != null && grupo != null)

        val url = com.claryon.field.BuildConfig.SUPABASE_URL
        val anon = com.claryon.field.BuildConfig.SUPABASE_ANON_KEY
        Assume.assumeTrue("sem projeto configurado", url.isNotBlank() && anon.isNotBlank())

        val token = autenticar(url, anon, email!!, senha!!)
        Assume.assumeTrue("login falhou para $email", token != null)

        val wav = File(pasta, "claryon_comandos.wav")
        Assume.assumeTrue("áudio ausente — adb push para ${pasta.absolutePath}", wav.exists())
        val pcmHumano = lerWav(wav)
        Assume.assumeTrue("WAV vazio", pcmHumano.size > taxa)

        val whisper = EscutaDoAgente.de(ctx)
        Assume.assumeTrue("whisper indisponível", whisper != null)

        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = ConfigRealtime(
            projetoUrl = url,
            apiKey = anon,
            tokenDoAgente = { token },
            privado = true,
        )
        val transporte = TransporteRealtime(config, escopo)
        transporte.conectar(grupo!!)
        // O join é assíncrono depois de o socket abrir; sem a espera o primeiro
        // quadro sairia antes de o servidor autorizar.
        repeat(40) { if (!transporte.conectado()) delay(250) }
        assertTrue("o canal privado não autorizou — sem canal não há o que medir", transporte.conectado())

        val acumulador = AcumuladorDePcm()
        var textoProduzido: String? = null
        var amostrasNoAcumulador = 0
        var difusaoOk = false
        val transmissaoId = UUID.randomUUID().toString()

        val sessao = SessaoPtt(
            talkGroupId = grupo,
            agenteId = "33333333-0000-0000-0000-000000000003",
            preRoll = PreRollBuffer(taxa),
            codec = MediaCodecOpus(ConfigOpus(sampleRateHz = taxa)),
            transporte = transporte,
            piso = ClienteDePisoRemoto(
                config = config,
                jwt = { token!! },
                agenteIdLocal = "33333333-0000-0000-0000-000000000003",
            ),
            amostrasPorQuadro = taxa / 50,
            agoraMs = { System.currentTimeMillis() },
            acumulador = acumulador,
            aoAudioTransmitido = { id, pcm ->
                // Exatamente o que `RadioTatico.transcreverEDifundir` faz.
                amostrasNoAcumulador = pcm.size
                val t = (whisper!!.transcribe(pcm, taxa) as? Result.Success)?.value?.text?.trim()
                textoProduzido = t
                if (!t.isNullOrBlank()) {
                    difusaoOk = transporte.transcrever(id, t) is Result.Success
                }
            },
        )

        // O microfone: o mesmo formato de fluxo que a fonte real entrega, com o
        // áudio humano de verdade em blocos de 20 ms.
        val bloco = taxa / 50
        val quadros = minOf(pcmHumano.size / bloco, 250) // ≤5 s, dentro do teto de 30 s
        val fonte = flow {
            for (i in 0 until quadros) {
                emit(pcmHumano.copyOfRange(i * bloco, (i + 1) * bloco))
                delay(20)
            }
        }

        sessao.transmitir(transmissaoId, PrioridadeTransmissao.P2_APOIO, "Bravo Um", fonte) {}
        // A transcrição roda no `finally`, e o whisper leva centenas de ms.
        repeat(60) { if (textoProduzido == null) delay(500) }

        android.util.Log.i(
            "ClaryonField",
            """
            |TRANSCRIÇÃO NA ORIGEM — corrente inteira, fala humana, servidor real
            |  áudio de entrada ......... ${"%.1f".format(quadros * 0.02)} s (gravação do agente)
            |  canal .................... PRIVADO, autorizado por JWT
            |  acumulador ............... $amostrasNoAcumulador amostras = ${
                "%.1f".format(amostrasNoAcumulador / taxa.toDouble())
            } s
            |  texto transcrito ......... "${textoProduzido ?: "(nenhum)"}"
            |  difundido no canal ....... $difusaoOk
            |
            |  ⚠️ O microfone é a única peça simulada: o PCM entra pelo mesmo Flow que
            |     a FonteUnicaDeMicrofone alimentaria. Do SessaoPtt em diante nada é.
            """.trimMargin(),
        )

        assertTrue(
            "o acumulador ficou vazio: o áudio não chegou ao funil de `enviar`",
            amostrasNoAcumulador > 0,
        )
        // O acumulado tem de bater com o que entrou, não com o que foi capturado.
        assertTrue(
            "acumulado ($amostrasNoAcumulador) destoa do transmitido (${quadros * bloco})",
            amostrasNoAcumulador in (quadros * bloco / 2)..(quadros * bloco),
        )
        assertTrue("o whisper não devolveu texto", !textoProduzido.isNullOrBlank())
        assertTrue("o texto não foi difundido no canal", difusaoOk)

        transporte.desconectar()
    }
}
