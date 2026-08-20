package com.claryon.field.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.claryon.net.ConfigRealtime
import com.claryon.net.PublicadorDePosicaoSupabase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A fiação do turno, no código que o serviço de fato chama.**
 *
 * A migração `0019` faz `publicar_posicao` recusar fora de turno aberto. Isso não é
 * aditivo: o cliente que não abrir turno **para de publicar posição**. O lado do
 * servidor está provado por `curl` e pelo verificador `0008`; o que falta é a ponta
 * Kotlin — `PublicadorDePosicaoSupabase.iniciarTurno()`, que `CopilotService.onCreate`
 * invoca antes de o coletor começar.
 *
 * Tentei provar isto pela tela de login e desisti: `adb input text` engole `#`, o
 * teclado empurra o layout e as coordenadas dançam. Nada disso diz respeito ao item,
 * e insistir seria medir a minha automação em vez do produto. Aqui o caminho medido
 * é exatamente o do serviço, com sessão real contra o servidor real.
 *
 * ```
 * -e par_email 123456789@claryon.invalid -e par_senha ...
 * ```
 */
@RunWith(AndroidJUnit4::class)
class TurnoDoClienteTest {

    private fun arg(n: String): String? =
        InstrumentationRegistry.getArguments().getString(n)?.takeIf { it.isNotBlank() }

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
    fun oClienteAbreTurnoEDepoisConseguePublicar(): Unit = runBlocking {
        val email = arg("par_email")
        val senha = arg("par_senha")
        Assume.assumeTrue("faltam -e par_email/par_senha", email != null && senha != null)

        val url = com.claryon.field.BuildConfig.SUPABASE_URL
        val anon = com.claryon.field.BuildConfig.SUPABASE_ANON_KEY
        Assume.assumeTrue("sem projeto", url.isNotBlank() && anon.isNotBlank())
        val token = autenticar(url, anon, email!!, senha!!)
        Assume.assumeTrue("login falhou", token != null)

        val publicador = PublicadorDePosicaoSupabase(
            config = ConfigRealtime(projetoUrl = url, apiKey = anon),
            tokenDeSessao = { token },
        )

        // Estado conhecido: sem turno aberto.
        publicador.encerrarTurno()

        // **Sem turno, a publicação não sobe.** `publicando()` é o indicador que a
        // reciprocidade usa, e ele tem de refletir a recusa — senão o mapa acharia
        // que está publicando e pediria distâncias que o servidor não vai dar.
        publicador.publicar(-16.68, -49.25, 12f, 3.5f)
        assertFalse(
            "o publicador se declarou publicando SEM turno aberto — o servidor recusou " +
                "com 42501 e o cliente não percebeu",
            publicador.publicando(),
        )

        assertTrue("iniciarTurno() falhou com sessão válida", publicador.iniciarTurno())

        publicador.publicar(-16.68, -49.25, 12f, 3.5f)
        assertTrue(
            "com turno aberto a publicação ainda não subiu",
            publicador.publicando(),
        )

        // Limpa: turno aberto por teste não pode ficar contando tempo de coleta.
        publicador.encerrarTurno()
    }
}
