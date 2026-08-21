package com.claryon.glasses

/**
 * O que fazer diante de uma transição de estado do stream de câmera.
 *
 * Existe como tipo, e não como `if` dentro do coletor, por um motivo medido: a
 * regra "STOPPED é terminal" está errada de duas maneiras diferentes, e as duas
 * só aparecem em sequências de estados — não em um estado isolado. Como função
 * pura, a sequência inteira entra num teste de JVM e a decisão fica provável sem
 * óculos, sem emulador e sem o decodificador do MockDeviceKit, que é justamente
 * o que não se consegue reproduzir de forma determinística.
 */
internal enum class AcaoDeStream {
    /** Nada a fazer. */
    NADA,

    /** Fim de vida: desanexar a câmera da sessão, senão o próximo `addCamera` falha. */
    PARAR_CAMERA,

    /** A câmera já se foi; só soltar as referências locais. */
    SOLTAR_REFERENCIAS,
}

/**
 * **Quando `STOPPED` é fim de vida — e quando é ruído de partida.**
 *
 * Dois enganos que este objeto existe para não cometer, ambos confirmados por
 * medição e não por leitura:
 *
 *  1. **`STOPPED` é o estado INICIAL de um stream do DAT.** `StreamImpl` nasce
 *     com `_state = MutableStateFlow(StreamState.STOPPED)` — `javap -p -c` no
 *     construtor. Como `state` é `StateFlow` e assinamos antes de `start()`, o
 *     primeiro valor entregue é sempre esse. Tratá-lo como terminal pararia a
 *     câmera recém-criada.
 *
 *  2. **`STARTING → STOPPED` é uma tentativa falha, não o fim.** O SDK tenta de
 *     novo sozinho: há um
 *     `StreamEventCoordinator$handleErrorLocked$1$retryRunnable$1` no artefato
 *     `mwdat-camera-0.9.0`. Medido no emulador com o MockDeviceKit, a trilha real
 *     de uma abertura foi `[STOPPED, STARTING, STOPPED, STARTING, STOPPED]` — e
 *     uma versão anterior deste código, que marcava "vivo" já em `STARTING`,
 *     chamava `Camera.stop()` no primeiro STOPPED e **abortava a retentativa do
 *     SDK**. O conserto do roadmap, escrito do jeito óbvio, impedia a câmera de
 *     abrir em vez de liberar a próxima.
 *
 * Só `STARTED` e `STREAMING` provam que houve vida. Uma instância por stream —
 * o estado é o do stream corrente, não do processo.
 */
internal class VidaDoStream {

    private var jaSubiu = false
    private var jaParou = false

    fun aoMudarPara(estado: StreamStatus): AcaoDeStream = when (estado) {
        StreamStatus.STARTED, StreamStatus.STREAMING -> {
            jaSubiu = true
            AcaoDeStream.NADA
        }

        StreamStatus.CLOSED -> AcaoDeStream.SOLTAR_REFERENCIAS

        StreamStatus.STOPPED ->
            // `jaParou` evita repetir a ordem quando o SDK emite STOPPED mais de
            // uma vez no encerramento. O guarda de `Camera.stop()` na fachada é
            // por câmera; este é por transição, e os dois medem coisas
            // diferentes: aquele impede a chamada dupla ao SDK, este impede a
            // decisão dupla.
            if (jaSubiu && !jaParou) {
                jaParou = true
                AcaoDeStream.PARAR_CAMERA
            } else {
                AcaoDeStream.NADA
            }

        // STARTING: tentativa em curso. PAUSED: o agente deu tap na haste, e o
        // stream volta sozinho — parar aqui transformaria uma pausa em fim.
        // STOPPING: o encerramento já está a caminho.
        StreamStatus.STARTING, StreamStatus.PAUSED, StreamStatus.STOPPING -> AcaoDeStream.NADA
    }
}
