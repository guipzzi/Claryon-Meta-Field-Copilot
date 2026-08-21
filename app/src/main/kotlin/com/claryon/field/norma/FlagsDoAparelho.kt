package com.claryon.field.norma

import android.content.Context
import android.provider.Settings
import com.claryon.common.FeatureFlag
import com.claryon.common.FeatureFlags

/**
 * **A primeira implementação de [FeatureFlags] deste projeto.**
 *
 * Até 2026-08-21 o `grep` por `FeatureFlag` fora de `core-common` devolvia
 * **zero**: a interface, o enum e as três chaves existiam sem implementação e
 * sem chamador — a mesma família de defeito que o `CLAUDE.md` §6 persegue, e que
 * já fez `fecharCiclo()` e `relatorioDeTelemetria()` nascerem mortos. A Etapa B
 * precisa de uma chave humana; em vez de inventar um mecanismo novo ao lado de
 * um mecanismo morto, esta classe acorda o que já estava escrito.
 *
 * ## Por que `Settings.Global`, e não `SharedPreferences`
 *
 * Porque a chave precisa ser virável **sem tela e sem recompilar**, no aparelho,
 * no dia da demonstração:
 *
 * ```
 * adb shell settings put global knowledge.llm 0   # desliga a Etapa B
 * adb shell settings delete global knowledge.llm  # volta ao padrão declarado
 * ```
 *
 * Ler `Settings.Global` não exige permissão nenhuma; **escrever** exige
 * `WRITE_SECURE_SETTINGS`, que o app não tem e o shell do `adb` tem. O
 * arranjo é deliberado: o aplicativo nunca muda a própria flag, então não existe
 * caminho em que ele se auto-habilite.
 *
 * Chave ausente ou ilegível cai no `default` declarado no enum — nunca em
 * exceção, e nunca num terceiro estado.
 */
class FlagsDoAparelho(context: Context) : FeatureFlags {

    private val resolver = context.applicationContext.contentResolver

    override fun isEnabled(flag: FeatureFlag): Boolean {
        val bruto = runCatching { Settings.Global.getString(resolver, flag.key) }.getOrNull()
            ?: return flag.default
        return when (bruto.trim().lowercase()) {
            "1", "true", "on", "sim" -> true
            "0", "false", "off", "nao", "não" -> false
            // Valor que ninguém sabe interpretar não pode virar "ligado" por
            // acidente: volta ao default declarado, que é a decisão de projeto.
            else -> flag.default
        }
    }
}
