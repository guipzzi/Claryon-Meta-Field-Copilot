package com.claryon.sound

/**
 * O protocolo de laconicidade mudou para `core-common` (ver `AudioSignals.kt`).
 *
 * Motivo: a regra passou a ser aplicada **na origem** — `utteranceFor` só
 * constrói fala a partir do resultado de uma ação, e é lá que o limite de sete
 * palavras precisa valer. `core-agent` não pode depender de `core-sound`, então
 * a política desceu para o módulo comum. O apelido mantém os chamadores atuais.
 */
typealias LaconicityPolicy = com.claryon.common.LaconicityPolicy
