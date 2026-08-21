package com.claryon.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Quando parar a câmera, provado por sequência.**
 *
 * O item de roadmap pede tratar `STOPPED` como terminal. Escrito ao pé da letra,
 * isso quebra a câmera de duas maneiras que só aparecem em sequência — e por
 * isso os testes aqui alimentam trilhas inteiras, não estados avulsos.
 *
 * A trilha de [aberturaComRetentativa] **não é inventada**: foi medida no
 * emulador com o MockDeviceKit 0.9.0, com a primeira versão do conserto no
 * lugar, e é a razão de o conserto ter mudado.
 */
class VidaDoStreamTest {

    private fun trilha(vararg estados: StreamStatus): List<AcaoDeStream> {
        val vida = VidaDoStream()
        return estados.map { vida.aoMudarPara(it) }
    }

    @Test
    fun oStoppedInicial_naoParaACameraRecemCriada() {
        // `StreamImpl` nasce em STOPPED e `state` é StateFlow: este é o primeiro
        // valor que o coletor recebe, sempre, antes de `start()`.
        assertEquals(listOf(AcaoDeStream.NADA), trilha(StreamStatus.STOPPED))
    }

    @Test
    fun aberturaComRetentativa_naoParaACamera() {
        // **O contra-teste do defeito medido.** Trilha real do emulador:
        // [STOPPED, STARTING, STOPPED, STARTING, STOPPED]. Com a versão que
        // marcava "vivo" em STARTING, o terceiro elemento virava PARAR_CAMERA e
        // abortava a retentativa do SDK — a câmera nunca chegava a STREAMING.
        //
        // Se alguém puser STARTING de volta entre os estados que marcam vida,
        // este teste falha. Um teste que só verificasse "STOPPED depois de
        // STREAMING para a câmera" passaria com o defeito de volta.
        val acoes = trilha(
            StreamStatus.STOPPED,
            StreamStatus.STARTING,
            StreamStatus.STOPPED,
            StreamStatus.STARTING,
            StreamStatus.STOPPED,
        )
        assertEquals(
            "uma tentativa de partida que falha não é fim de vida — o SDK retenta sozinho",
            List(5) { AcaoDeStream.NADA },
            acoes,
        )
    }

    @Test
    fun streamQueViveuEMorre_paraACameraUmaVezSo() {
        // O caso que o item existe para consertar: o agente dobra as hastes com a
        // câmera aberta. Ninguém do lado do app pediu para parar.
        val acoes = trilha(
            StreamStatus.STOPPED,
            StreamStatus.STARTING,
            StreamStatus.STARTED,
            StreamStatus.STREAMING,
            StreamStatus.STOPPED,
        )
        assertEquals(
            listOf(
                AcaoDeStream.NADA,
                AcaoDeStream.NADA,
                AcaoDeStream.NADA,
                AcaoDeStream.NADA,
                AcaoDeStream.PARAR_CAMERA,
            ),
            acoes,
        )
    }

    @Test
    fun stoppedRepetido_naoRepeteAOrdem() {
        // O SDK pode emitir STOPPED mais de uma vez ao encerrar; mandar parar
        // duas vezes é ruído em cima de uma câmera que já se foi.
        val acoes = trilha(
            StreamStatus.STREAMING,
            StreamStatus.STOPPED,
            StreamStatus.STOPPED,
            StreamStatus.STOPPED,
        )
        assertEquals(
            listOf(
                AcaoDeStream.NADA,
                AcaoDeStream.PARAR_CAMERA,
                AcaoDeStream.NADA,
                AcaoDeStream.NADA,
            ),
            acoes,
        )
    }

    @Test
    fun pausaPorTapNaHaste_naoEncerraACamera() {
        // PAUSED é o tap na haste; o stream volta sozinho. Parar aqui
        // transformaria uma pausa de dois segundos em fim de operação, e o agente
        // teria de reabrir a leitura de placa sem saber por quê.
        val acoes = trilha(
            StreamStatus.STREAMING,
            StreamStatus.PAUSED,
            StreamStatus.STREAMING,
        )
        assertEquals(List(3) { AcaoDeStream.NADA }, acoes)
    }

    @Test
    fun encerramentoOrdenado_paraNoStoppedEnaoNoStopping() {
        val acoes = trilha(
            StreamStatus.STREAMING,
            StreamStatus.STOPPING,
            StreamStatus.STOPPED,
            StreamStatus.CLOSED,
        )
        assertEquals(
            listOf(
                AcaoDeStream.NADA,
                AcaoDeStream.NADA,
                AcaoDeStream.PARAR_CAMERA,
                AcaoDeStream.SOLTAR_REFERENCIAS,
            ),
            acoes,
        )
    }

    @Test
    fun cadaStreamTemVidaPropria() {
        // O guarda é por stream. Se fosse de processo, a segunda leitura de placa
        // do turno encontraria o guarda já fechado e nunca desanexaria a câmera —
        // que é o defeito original, de volta pela porta dos fundos.
        val primeira = VidaDoStream()
        primeira.aoMudarPara(StreamStatus.STREAMING)
        assertEquals(AcaoDeStream.PARAR_CAMERA, primeira.aoMudarPara(StreamStatus.STOPPED))

        val segunda = VidaDoStream()
        segunda.aoMudarPara(StreamStatus.STREAMING)
        assertEquals(AcaoDeStream.PARAR_CAMERA, segunda.aoMudarPara(StreamStatus.STOPPED))
    }
}
