package com.claryon.glasses

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **A permissão de câmera do DAT — que não é uma permissão do Android.**
 *
 * Duas permissões diferentes se chamam "câmera" neste produto, e confundi-las é
 * a origem do item de roadmap que este arquivo fecha:
 *
 *  - `android.permission.CAMERA` é do **celular**, concedida no diálogo do
 *    sistema. O DAT **não a exige**: `PermissionsManager.areRequiredPermissionsGranted()`
 *    do artefato `mwdat-camera-0.9.0` verifica `android.permission.BLUETOOTH` e
 *    `android.permission.BLUETOOTH_CONNECT`, e nada mais — confirmado por
 *    `javap -p -c` (`getBTCPermissions()` carrega exatamente essas duas strings).
 *  - a permissão do **DAT** é concedida no **app Meta AI**, por aparelho, e é
 *    esta. Sem ela `addCamera` devolve sucesso e nenhum frame chega — o modo de
 *    falha silencioso que `CaosDoDatTest` mede.
 *
 * ## Como se pede — confirmado, não lembrado
 *
 * Não existe `Wearables.requestPermission(...)` no Android. O pedido é um
 * `ActivityResultContract`, porque o fluxo sai do processo e vai para o Meta AI.
 * `javap -cp classes.jar com.meta.wearable.dat.core.Wearables` e
 * `'com.meta.wearable.dat.core.Wearables$RequestPermissionContract'` no artefato
 * `mwdat-core-0.9.0`:
 *
 * ```
 * public final java.lang.Object checkPermissionStatus(
 *     com.meta.wearable.dat.core.types.Permission,
 *     kotlin.coroutines.Continuation<? super DatResult<PermissionStatus, PermissionError>>);
 *
 * public final class Wearables$RequestPermissionContract
 *     extends androidx.activity.result.contract.ActivityResultContract<
 *         Permission, DatResult<PermissionStatus, PermissionError>>
 * ```
 *
 * `Permission` tem **um único valor**, `CAMERA` — por isso a entrada do contrato
 * daqui é `Unit`: não há o que escolher, e um parâmetro sugeriria que há.
 *
 * ## O que a doc oficial escreve errado
 *
 * O guia "Step 5: Manage camera permissions" mostra
 * `permissionContinuation?.resume(result)` sobre um
 * `CancellableContinuation<PermissionStatus>`, como se o contrato devolvesse
 * `PermissionStatus`. Ele devolve `DatResult<PermissionStatus, PermissionError>`
 * — a assinatura do artefato manda, e é ela que está traduzida aqui.
 *
 * ## O que o contrato faz por baixo
 *
 * `createIntent` monta `Intent(ACTION_VIEW, "fb-viewapp://device/permissions/request?…")`
 * (`javap -p -c`, constantes `fb-viewapp`, `device`, `permissions`, `request`).
 * É um **deeplink para o app Meta AI**: sem ele instalado, `launch` estoura
 * `ActivityNotFoundException` — e é por isso que [pedir] existe em vez de o
 * chamador usar o launcher cru.
 *
 * `parseResult` **nunca devolve falha**: lê o extra `permission_granted` e
 * responde `success(Granted)` ou `success(Denied)`. `PermissionError` só sai de
 * [status].
 */
object PermissaoDaCameraDoDat {

    /**
     * Pergunta ao DAT se a câmera já está liberada, **sem abrir diálogo nenhum**.
     *
     * Suspende porque a resposta vem dos óculos, não de um registro local: o
     * Meta AI consulta o aparelho. Sem óculos por perto a resposta é
     * [RespostaDePermissao.Indisponivel], nunca "negada" — dizer "negada" quando
     * ninguém negou faria a tela pedir ao agente que conserte o que não está
     * quebrado.
     */
    suspend fun status(): RespostaDePermissao =
        runCatching {
            // **Teto próprio.** A resposta atravessa Bluetooth até os óculos, e
            // esta chamada fica no caminho do onboarding, que é o único portão
            // do app. Sem teto, um aparelho pareado e mudo prenderia a tela de
            // abertura para sempre — falha pior que a que este arquivo conserta.
            withTimeoutOrNull(PRAZO_DE_CONSULTA_MS) {
                Wearables.checkPermissionStatus(Permission.CAMERA)
            } ?: return RespostaDePermissao.Indisponivel(CausaSemPermissao.REQUEST_TIMEOUT)
        }
            .fold(
                onSuccess = { resultado ->
                    resultado.fold(
                        { estado -> estado.paraNos() },
                        { erro, _ ->
                            RespostaDePermissao.Indisponivel(
                                erro.name.toEnumOrDesconhecida(),
                            )
                        },
                    )
                },
                onFailure = { e ->
                    // `Wearables.initialize` não chamado, SDK em estado inválido.
                    // Falha nunca é silêncio: vira causa tipada, não exceção solta.
                    Log.w(TAG, "checkPermissionStatus lançou: ${e.message}")
                    RespostaDePermissao.Indisponivel(CausaSemPermissao.DESCONHECIDA)
                },
            )

    /**
     * O contrato de pedido, para `rememberLauncherForActivityResult`.
     *
     * Envolve o do SDK em vez de expô-lo: nenhum símbolo do DAT atravessa a
     * fronteira de `core-glasses`, que é a regra da fachada.
     */
    class Contrato : ActivityResultContract<Unit, RespostaDePermissao>() {

        private val interno = Wearables.RequestPermissionContract()

        override fun createIntent(context: Context, input: Unit): Intent =
            interno.createIntent(context, Permission.CAMERA)

        override fun parseResult(resultCode: Int, intent: Intent?): RespostaDePermissao =
            interno.parseResult(resultCode, intent)
                .fold(
                    { estado -> estado.paraNos() },
                    { erro, _ -> RespostaDePermissao.Indisponivel(erro.name.toEnumOrDesconhecida()) },
                )

        /**
         * `getSynchronousResult` do SDK chama `Wearables.getInstance()`, que
         * depende de `initialize` ter rodado. Envolvido em `runCatching` porque
         * um estouro aqui aconteceria **dentro do `launch`**, na main thread, e
         * derrubaria a tela de onboarding — a única janela que este item tem.
         */
        override fun getSynchronousResult(
            context: Context,
            input: Unit,
        ): SynchronousResult<RespostaDePermissao>? =
            runCatching { interno.getSynchronousResult(context, Permission.CAMERA) }
                .getOrNull()
                ?.let { pronto ->
                    SynchronousResult(
                        pronto.value.fold(
                            { estado -> estado.paraNos() },
                            { erro, _ ->
                                RespostaDePermissao.Indisponivel(erro.name.toEnumOrDesconhecida())
                            },
                        ),
                    )
                }
    }

    /**
     * Dispara o pedido **sem derrubar o app quando o Meta AI não está instalado**.
     *
     * `ActivityResultLauncher.launch` chama `startActivityForResult` de forma
     * síncrona, então a `ActivityNotFoundException` do deeplink `fb-viewapp://`
     * volta para cá e pode virar resposta tipada. Emulador sem Meta AI é
     * exatamente este caminho, e é o estado em que este código foi exercitado.
     *
     * @return `null` quando o pedido saiu e a resposta virá pelo callback do
     *   launcher; uma [RespostaDePermissao] já resolvida quando nem deu para
     *   pedir.
     */
    fun pedir(launcher: ActivityResultLauncher<Unit>): RespostaDePermissao? =
        try {
            launcher.launch(Unit)
            null
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "deeplink de permissão sem quem atenda: ${e.message}")
            RespostaDePermissao.Indisponivel(CausaSemPermissao.META_AI_NOT_INSTALLED)
        }

    private const val TAG = "ClaryonField"

    /**
     * Generoso o bastante para Bluetooth Classic frio, curto o bastante para o
     * agente não achar que a tela de abertura travou.
     */
    private const val PRAZO_DE_CONSULTA_MS = 5_000L
}

/** O que o DAT respondeu sobre a câmera dos óculos. */
sealed interface RespostaDePermissao {

    /** Liberada — a leitura de placa pode abrir a câmera. */
    data object Concedida : RespostaDePermissao

    /** O agente respondeu "não" no diálogo do Meta AI. */
    data object Negada : RespostaDePermissao

    /**
     * Não deu para perguntar. **Distinto de negada**, e a distinção é o produto:
     * "sem óculos por perto" pede que o agente ligue os óculos; "negada" pede que
     * ele mude de ideia. Trocar um pelo outro manda o agente consertar a coisa
     * errada.
     */
    data class Indisponivel(val causa: CausaSemPermissao) : RespostaDePermissao
}

/**
 * Espelha `PermissionError` do DAT 0.9 — enum, por nome, como os demais.
 * Confirmado por `javap -cp classes.jar com.meta.wearable.dat.core.types.PermissionError`
 * em `mwdat-core-0.9.0`: NO_DEVICE, NO_DEVICE_WITH_CONNECTION, META_AI_NOT_INSTALLED,
 * CONNECTION_ERROR, REQUEST_IN_PROGRESS, REQUEST_TIMEOUT, INTERNAL_ERROR.
 *
 * [frase] respeita o teto de 7 palavras da fala operacional — sustentado por teste.
 */
enum class CausaSemPermissao(val frase: String) {
    NO_DEVICE("Óculos não encontrados."),
    NO_DEVICE_WITH_CONNECTION("Óculos desconectados."),
    META_AI_NOT_INSTALLED("Instale o app Meta AI."),
    CONNECTION_ERROR("Sem conexão com os óculos."),
    REQUEST_IN_PROGRESS("Pedido já em andamento."),
    REQUEST_TIMEOUT("Os óculos não responderam."),
    INTERNAL_ERROR("Falha no app Meta AI."),

    /** O SDK acrescentou um valor que esta versão não conhece. */
    DESCONHECIDA("Não deu para perguntar."),
    ;

    val codigo: String get() = "glasses.permission.$name"
}

/**
 * `PermissionStatus` é **interface**, não enum: as duas implementações são os
 * objetos `PermissionStatus.Granted` e `PermissionStatus.Denied`. Por isso a
 * tradução é por identidade de objeto, e não por nome como nos enums.
 *
 * `javap` mostra só `public interface PermissionStatus { }` mais
 * `PermissionStatus$Granted implements PermissionStatus { ... INSTANCE; }` — o
 * bytecode não tem como registrar selamento nesta forma. **Quem completa a
 * assinatura é o compilador**: o `when` abaixo com um `else` compilou com
 * *"'when' is exhaustive so 'else' is redundant"*, o que prova pelos metadados
 * Kotlin que a interface é `sealed` e que Granted/Denied são as duas únicas
 * implementações. Sendo selada, um valor novo quebraria a compilação em vez de
 * cair num ramo silencioso — que era o risco que o `else` cobria.
 */
private fun PermissionStatus.paraNos(): RespostaDePermissao = when (this) {
    is PermissionStatus.Granted -> RespostaDePermissao.Concedida
    is PermissionStatus.Denied -> RespostaDePermissao.Negada
}

private fun String.toEnumOrDesconhecida(): CausaSemPermissao =
    runCatching { enumValueOf<CausaSemPermissao>(this) }
        .getOrDefault(CausaSemPermissao.DESCONHECIDA)
