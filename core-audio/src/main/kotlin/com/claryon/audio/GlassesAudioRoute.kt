package com.claryon.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.claryon.common.ClaryonError
import com.claryon.common.Result

/**
 * **Prova de que o áudio está roteado pelos óculos.**
 *
 * Existe para tornar impossível — por construção, não por convenção — o pior
 * defeito que este projeto pode ter: capturar pelo microfone omnidirecional do
 * celular. Os óculos aplicam *beamforming* e isolam a voz de quem os veste; o
 * microfone do celular capta **todo mundo ao redor**. Transcrever a fala de
 * terceiros é proibição absoluta do projeto, e com o PTT a violação deixa de ser
 * local e passa a ser **difundida para a guarnição inteira**.
 *
 * A auditoria já encontrou esse defeito uma vez, numa função de diagnóstico. A
 * correção de então foi pontual; esta é estrutural: [GlassesAudioManager.microfonePcm]
 * exige uma instância deste tipo, e o único jeito de obter uma é passar por
 * [acquire], que verifica a rota de fato. **Gravar pelo microfone do celular
 * deixa de compilar.**
 *
 * @property deviceId `AudioDeviceInfo.getId()` do dispositivo roteado — permite
 *   reconferir, no instante da captura, que a rota não caiu no meio do caminho.
 */
@JvmInline
value class GlassesAudioRoute private constructor(val deviceId: Int) {

    companion object {

        /**
         * **Único caminho de produção.** Só devolve sucesso se o dispositivo de
         * comunicação ativo for `TYPE_BLUETOOTH_SCO` — o canal de voz dos óculos.
         *
         * Chame **depois** de `setCommunicationDevice()`: esta função confirma o
         * estado efetivado, não o pretendido.
         */
        fun acquire(am: AudioManager): Result<GlassesAudioRoute> {
            val atual = am.communicationDevice
                ?: return Result.failure(
                    ClaryonError.Audio(
                        "audio.sem_rota",
                        "Nenhum dispositivo de comunicação ativo — roteie antes de capturar.",
                    ),
                )
            if (atual.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return Result.failure(
                    ClaryonError.Audio(
                        "audio.rota_nao_e_sco",
                        "Rota ativa é tipo ${atual.type}, não TYPE_BLUETOOTH_SCO. " +
                            "Capturar aqui gravaria pelo microfone do celular e captaria terceiros.",
                    ),
                )
            }
            return Result.success(GlassesAudioRoute(atual.id))
        }

        /**
         * Rota de **desenvolvimento**, sem óculos nem fone Bluetooth.
         *
         * Necessária porque o MockDeviceKit não simula áudio e o emulador não tem
         * rádio: sem isto, nenhum caminho de voz seria exercitável até existir
         * hardware. O nome é longo e desagradável de propósito — não se chega
         * aqui por distração, só por decisão.
         *
         * @param buildDebug passe `BuildConfig.DEBUG`. Em release devolve falha
         *   **mesmo que chamada**: o guarda é de execução, não só de convenção,
         *   porque um APK de release capturando pelo microfone do celular é
         *   exatamente o cenário que este tipo existe para impedir.
         */
        fun acquireParaDesenvolvimento(
            am: AudioManager,
            buildDebug: Boolean,
        ): Result<GlassesAudioRoute> {
            if (!buildDebug) {
                return Result.failure(
                    ClaryonError.Audio(
                        "audio.rota_dev_em_release",
                        "Rota de desenvolvimento é proibida em release.",
                    ),
                )
            }
            val atual = am.communicationDevice
                ?: return Result.failure(
                    ClaryonError.Audio("audio.sem_rota", "Nenhum dispositivo de comunicação ativo."),
                )
            return Result.success(GlassesAudioRoute(atual.id))
        }

        /**
         * Prova falsa. **Não existe no build de release** — ver
         * `core-audio/src/debug/.../RotaDeTeste.kt`, que é o único chamador.
         *
         * `internal` mantém a garantia de compilação para o resto do projeto:
         * fora de `core-audio` continua sendo impossível fabricar uma rota, e é
         * fora daqui que vivem os caminhos que capturam. Aqui dentro a garantia
         * nunca existiu — é este arquivo que a constrói.
         *
         * Existe porque a política que ela destrava é **regra de compliance** e
         * precisa de teste que rode em toda máquina, não só na que tem fone
         * Bluetooth pareado: quantos `AudioRecord` abrem, em que intervalo a rota
         * é reconferida, quem perde quadro ao atrasar. `acquire` exige um
         * `AudioManager`, e em unit test do Android ele lança "not mocked" antes
         * da primeira linha do que se quer testar.
         */
        internal fun fabricarSemRotear(deviceId: Int): GlassesAudioRoute =
            GlassesAudioRoute(deviceId)
    }
}

/**
 * `true` se [route] ainda corresponde ao dispositivo de comunicação ativo.
 *
 * A prova é obtida no roteamento, mas a captura pode começar segundos depois — e
 * o HFP cai (óculos dobrados, fone desligado, ligação entrando). Sem reconferir,
 * a captura seguiria pelo microfone que o sistema escolher como substituto, que
 * é justamente o do celular.
 */
internal fun AudioManager.confereRota(route: GlassesAudioRoute): Boolean =
    communicationDevice?.id == route.deviceId
