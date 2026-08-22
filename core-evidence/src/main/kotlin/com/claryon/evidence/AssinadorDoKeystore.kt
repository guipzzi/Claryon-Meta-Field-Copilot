package com.claryon.evidence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import java.security.KeyStore

/**
 * [AncoraDeFim.Assinador] com chave HMAC-SHA256 no **Android Keystore**.
 *
 * A chave é gerada na primeira selagem e reusada depois. Ela nunca sai do
 * Keystore: `Mac.init(Key)` recebe uma referência opaca, e o MAC é calculado do
 * outro lado da fronteira. É isso que dá à âncora o valor que a cadeia de hash
 * sozinha não tem — o disco não contém nada com que forjá-la.
 *
 * ## Ciclo de vida
 *
 * A chave morre com a desinstalação do app, junto com a `MasterKey` que cifra os
 * próprios segmentos. Não é perda nova: sem a `MasterKey` os segmentos já são
 * indescriptografáveis, então uma âncora que sobrevivesse a ela não ancoraria coisa
 * alguma.
 *
 * ## Assinaturas conferidas por `javap` no `android.jar` da API 35
 *
 * ```
 * KeyStore.getInstance(String) · load(InputStream, char[]) · getKey(String, char[])
 * KeyGenerator.getInstance(String, String) · init(AlgorithmParameterSpec) · generateKey()
 * KeyGenParameterSpec.Builder(String, int) · setKeySize(int) · build()
 * KeyProperties.KEY_ALGORITHM_HMAC_SHA256 · PURPOSE_SIGN
 * Mac.getInstance(String) · init(Key) · doFinal(byte[])
 * ```
 *
 * `Mac.getInstance` sem provedor explícito é deliberado: o JCA adia a escolha do
 * provedor até o `init(Key)`, e é a chave do Keystore que a decide. Pedir o
 * provedor pelo nome aqui seria escrever de memória o nome dele.
 *
 * ## Por que nada aqui lança
 *
 * `null` em vez de exceção porque o chamador é o fechamento de uma gravação de
 * evidência. Keystore indisponível não pode derrubar o `finalize` e levar junto o
 * manifesto: sem âncora, a conferência responde
 * [Integridade.SemAncoraDeFim] — que é pior que assinada e muito melhor que
 * nenhum manifesto.
 */
internal class AssinadorDoKeystore(
    private val alias: String = ALIAS_PADRAO,
) : AncoraDeFim.Assinador {

    @Volatile
    private var cache: SecretKey? = null

    override fun mac(mensagem: ByteArray): ByteArray? = runCatching {
        val mac = Mac.getInstance(AncoraDeFim.ALGORITMO)
        mac.init(chave())
        mac.doFinal(mensagem)
    }.getOrNull()

    private fun chave(): SecretKey {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: obterOuGerar().also { cache = it }
        }
    }

    private fun obterOuGerar(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        gerador.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setKeySize(CHAVE_BITS)
                .build(),
        )
        return gerador.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PADRAO = "claryon_ancora_de_fim_hmac"

        /** 256 bits: o tamanho do bloco de saída do SHA-256. Chave maior não acrescenta. */
        const val CHAVE_BITS = 256
    }
}
