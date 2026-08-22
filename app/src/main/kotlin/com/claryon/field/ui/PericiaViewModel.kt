package com.claryon.field.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.evidence.RegistroDeCustodia
import com.claryon.field.voice.CopilotoDoAgente
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * **A perícia da custódia, dentro do produto.**
 *
 * ## O que faltava
 *
 * `EncryptedEvidenceVault.verificar` e `Manifesto.ler` tinham **zero chamadores em
 * `src/main`** até 22/08. O cofre selava a âncora de fim em produção — caminho
 * alcançável por voz, HMAC do Keystore, manifesto v3 — e conferia **só em teste**.
 * A consequência prática: periciar uma ocorrência exigia `adb`/root sobre o
 * diretório privado do app, que é exatamente o acesso que o modelo de ameaça trata
 * como **atacante**. Pela régua do `CLAUDE.md §6`, a conferência estava *escrita,
 * não construída*.
 *
 * ## Duas escolhas que parecem detalhe e não são
 *
 * **Confere sob demanda, nunca em laço.** A conferência decifra e re-hasheia todos
 * os segmentos de todas as gravações; um laço faria disso trabalho de fundo
 * permanente sobre a flash e a bateria do aparelho de campo. Uma leitura por toque.
 *
 * **Usa o cofre DO PROCESSO** ([CopilotoDoAgente]), e não um novo. Um cofre novo
 * leria o mesmo diretório, mas não saberia que existe gravação aberta — e a
 * ocorrência em curso apareceria na tela como custódia interrompida. Ver
 * [RegistroDeCustodia.emAndamento].
 *
 * ## Lista vazia é afirmação
 *
 * "Nenhuma gravação selada neste aparelho" é informação — e é o estado normal, já
 * que só `Intent.IniciarGravacao` alimenta o cofre e **nada no caminho do PTT
 * escreve nele**. Um espaço em branco seria ambíguo entre isso e uma falha de
 * leitura, e por isso [Estado] separa os dois.
 */
class PericiaViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Estado {
        /** Ainda não conferiu nada. A conferência custa E/S e começa por um toque. */
        data object Ocioso : Estado

        /** Decifrando e re-hasheando. Não é instantâneo em aparelho sem AES em hardware. */
        data object Conferindo : Estado

        /**
         * @param em instante da conferência. Um veredito sem carimbo envelhece em
         *   silêncio: a tela ficaria afirmando "íntegra" sobre um estado de dez
         *   minutos atrás, que é o mesmo tipo de mentira que o esmaecimento do mapa
         *   existe para impedir.
         */
        data class Conferido(
            val registros: List<RegistroDeCustodia>,
            val em: Long,
        ) : Estado

        data class Indisponivel(val motivo: String) : Estado
    }

    private val _estado = MutableStateFlow<Estado>(Estado.Ocioso)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    fun conferir() {
        if (_estado.value is Estado.Conferindo) return
        _estado.value = Estado.Conferindo
        viewModelScope.launch(Dispatchers.IO) {
            _estado.value = runCatching {
                CopilotoDoAgente.de(getApplication()).cofre.periciar()
            }.fold(
                onSuccess = { Estado.Conferido(it, System.currentTimeMillis()) },
                onFailure = { e ->
                    Log.e(TAG, "perícia da custódia falhou", e)
                    // Falha de leitura NÃO pode virar lista vazia: "nada gravado" e
                    // "não consegui ler" levam a conclusões opostas numa tela cuja
                    // razão de existir é responder o que aconteceu com a prova.
                    Estado.Indisponivel("Não foi possível ler o cofre agora.")
                },
            )
        }
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
