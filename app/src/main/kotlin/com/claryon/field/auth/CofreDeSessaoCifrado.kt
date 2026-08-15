package com.claryon.field.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.claryon.net.CofreDeSessao
import com.claryon.net.Sessao

/**
 * Guarda a sessão do agente cifrada, com chave no Android Keystore.
 *
 * O token de acesso e o *refresh token* são credenciais: quem os tem fala pelo
 * agente, publica a posição dele e consulta os pares dele. Guardá-los em
 * `SharedPreferences` comum os deixaria em texto claro no diretório do app —
 * legível por qualquer processo com root, e recuperável de um aparelho perdido.
 *
 * A mesma proteção que o cofre de evidência usa (`EncryptedFile` + Keystore),
 * aplicada ao que dá acesso a ela.
 *
 * **A senha nunca chega aqui.** Só o par de tokens, que é revogável do lado do
 * servidor; uma senha em repouso não é.
 */
class CofreDeSessaoCifrado(context: Context) : CofreDeSessao {

    private val prefs: SharedPreferences by lazy {
        val chave = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            ARQUIVO,
            chave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun ler(): Sessao? {
        val acesso = prefs.getString(ACESSO, null) ?: return null
        val renovacao = prefs.getString(RENOVACAO, null) ?: return null
        return Sessao(
            accessToken = acesso,
            refreshToken = renovacao,
            expiraEmMs = prefs.getLong(EXPIRA, 0L),
            agentId = prefs.getString(AGENTE, null),
        )
    }

    override fun gravar(sessao: Sessao) {
        // `commit`, não `apply`. A gravação da sessão acontece logo antes de o
        // agente sair da tela e o app começar a operar; `apply` é assíncrono, e um
        // processo morto no intervalo perderia a sessão sem sinal nenhum — o
        // agente teria de logar de novo sem entender por quê.
        prefs.edit()
            .putString(ACESSO, sessao.accessToken)
            .putString(RENOVACAO, sessao.refreshToken)
            .putLong(EXPIRA, sessao.expiraEmMs)
            .putString(AGENTE, sessao.agentId)
            .commit()
    }

    override fun apagar() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val ARQUIVO = "claryon.sessao"
        const val ACESSO = "access_token"
        const val RENOVACAO = "refresh_token"
        const val EXPIRA = "expira_em_ms"
        const val AGENTE = "agent_id"
    }
}
