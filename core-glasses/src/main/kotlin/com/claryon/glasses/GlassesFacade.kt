package com.claryon.glasses

import com.claryon.common.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fachada única sobre o Meta Wearables Device Access Toolkit.
 *
 * **Por que existe:** isola o único ponto do código que depende de uma API em
 * *developer preview*. Quando a 0.9 quebrar assinaturas, conserta-se um arquivo,
 * não a base inteira. Nenhum outro módulo importa símbolos do DAT.
 *
 * Contrato fixado no M0. A implementação (registro via deeplink do app Meta AI,
 * `createSession`, `addStream`, `capturePhoto`) chega no M1/M2 e é sempre
 * consultada na doc viva antes de ser escrita.
 */
interface GlassesFacade {

    /** Estado de registro dos óculos (Developer Mode registra 1 app por vez). */
    val registration: StateFlow<RegistrationStatus>

    /** Estado da sessão do DAT (cascading stop: sessão parou ⇒ streams pararam). */
    val session: StateFlow<SessionStatus>

    /** Garante registro; se necessário, dispara o deeplink e aguarda o retorno. */
    suspend fun ensureRegistered(): Result<Unit>

    /** Cria e inicia a sessão. Pré-condição: HFP já configurado (ver boot). */
    suspend fun startSession(): Result<Unit>

    /**
     * Abre um stream de câmera sob demanda, entrega os frames a [block] e
     * garante o encerramento do stream ao final (câmera desce ao terminar).
     * Usar perfis baixos (LOW/MEDIUM, FPS baixo) — melhora a qualidade por frame.
     */
    suspend fun withCamera(
        config: CameraProfile,
        block: suspend (Flow<Frame>) -> Unit,
    ): Result<Unit>

    /** Captura pontual de foto (exige stream ativo; uma por vez). */
    suspend fun capturePhoto(): Result<PhotoData>
}
