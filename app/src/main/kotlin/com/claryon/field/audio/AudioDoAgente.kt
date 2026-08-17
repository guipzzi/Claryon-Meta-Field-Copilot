package com.claryon.field.audio

import android.content.Context
import com.claryon.audio.GlassesAudioManagerImpl
import com.claryon.field.BuildConfig

/**
 * **Dono único da rota de áudio do processo.**
 *
 * Existe para consertar uma violação de compliance, não para arrumar arquitetura.
 *
 * Até aqui, `RadioViewModel` e `DiagnosticsViewModel` construíam **cada um a sua**
 * `GlassesAudioManagerImpl`. O KDoc daquela classe descreve uma contagem de
 * referência e promete que "só o último `liberar()` desfaz" — mas `lock`,
 * `usuarios`, `previousMode` e `routedDevice` são campos de **instância**. Duas
 * instâncias, duas contagens, e nenhuma delas sabe da outra: o `liberar()` de uma
 * chama `clearCommunicationDevice()` sobre o estado global do aparelho enquanto o
 * `AudioRecord` da outra continua aberto.
 *
 * O que isso produz, em cadeia, e por que é grave:
 *
 * 1. `RadioTatico.entrarEmModoAtivo` abre **um** fluxo de captura de longa duração
 *    que alimenta o pré-roll enquanto o modo ativo durar.
 * 2. `microfonePcm` conferia a rota **uma vez**, na abertura do fluxo. (Isto
 *    mudou na Fase 1: `FonteUnicaDeMicrofone` reconfere a cada 200 ms durante o
 *    stream. O relato abaixo descreve o defeito como ele era.)
 * 3. Quando o `clearCommunicationDevice()` do outro dono cai no meio do stream,
 *    ninguém reconfere. O `AudioRecord` segue lendo do que o sistema escolher —
 *    o **microfone omnidirecional do celular**.
 * 4. Esse pré-roll contaminado é difundido para a guarnição inteira no PTT
 *    seguinte.
 *
 * Ou seja: captação de terceiros, que é proibição absoluta deste produto,
 * alcançada **por trás** do `GlassesAudioRoute` — o value class que existe
 * justamente para tornar isso impossível de compilar. O tipo continua íntegro; o
 * furo estava embaixo dele, no estado global do `AudioManager`.
 *
 * **Por que um objeto de processo e não injeção.** O recurso disputado é global
 * do aparelho, não do grafo de objetos: existe um `AudioManager` por processo e um
 * modo de áudio por sistema. Um dono por processo é a forma que espelha o recurso.
 * Injetar exigiria que todo caminho novo lembrasse de receber a mesma instância —
 * e "lembrar" é exatamente o que falhou aqui.
 *
 * A costura de teste é [instalar]: instrumentado troca a implementação no `@Before`
 * e devolve `null` no `@After`. Sem ela, um objeto de processo seria intestável, e
 * intestável é como este defeito sobreviveu.
 */
object AudioDoAgente {

    @Volatile
    private var instancia: GlassesAudioManagerImpl? = null

    /**
     * A instância única. Criada na primeira chamada.
     *
     * `allowFallbackToDefault = BuildConfig.DEBUG` é o mesmo argumento que as duas
     * construções antigas passavam — a unificação **preserva comportamento por
     * construção**, e o único efeito é fundir a contagem, que era o defeito.
     */
    fun de(context: Context): GlassesAudioManagerImpl =
        instancia ?: synchronized(this) {
            instancia ?: GlassesAudioManagerImpl(
                context.applicationContext,
                allowFallbackToDefault = BuildConfig.DEBUG,
            ).also { instancia = it }
        }

    /**
     * Substitui a instância — só para teste instrumentado.
     *
     * `null` devolve o objeto ao estado virgem, e o `@After` **precisa** chamar
     * com `null`: instância vazada de um teste para o seguinte carrega a rota do
     * anterior, e o segundo teste passa ou falha por motivo que não é o dele.
     */
    fun instalar(substituta: GlassesAudioManagerImpl?) {
        synchronized(this) { instancia = substituta }
    }
}
