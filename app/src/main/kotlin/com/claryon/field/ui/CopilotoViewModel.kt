package com.claryon.field.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.claryon.field.voice.CerebroDoCopiloto
import com.claryon.field.voice.CopilotoDoAgente
import kotlinx.coroutines.flow.StateFlow

/**
 * **A casca de tela do copiloto.** O cérebro mora em [CerebroDoCopiloto].
 *
 * Até 20/08 era o contrário: o executor de intenções, o roteador, a captura de
 * evidência e o ciclo de voz inteiro viviam aqui. Isso fazia a palavra de ativação
 * entregar metade da promessa — a escuta e o earcon rodam no `CopilotService` e
 * sobrevivem à tela apagada, mas quem executava o comando morria com a Activity.
 * O agente que guardasse o celular no bolso, que é o caso de uso inteiro, ouviria
 * o bipe de "estou ouvindo" e nada aconteceria. **Bipe que não leva a nada é pior
 * que silêncio: ele afirma que foi ouvido.**
 *
 * ## Por que a evidência foi junto
 *
 * Copiloto e evidência não são dois subsistemas que compartilham uma dependência —
 * são **duas metades da mesma máquina de estado**. `ClaryonIntentExecutor` guarda
 * `gravacaoAtual` com mutex próprio: quem ABRE a gravação é o ciclo de voz, quem a
 * ALIMENTA é a captura de evidência, e `gravacaoJob` é a exclusão mútua entre os
 * dois. Duplicar o executor daria dois `gravacaoAtual` — a instância A abriria o
 * manifesto e a B devolveria `SEM_GRAVACAO` para todo bloco de áudio, produzindo
 * manifesto aberto e vazio. Exatamente a mentira que o cofre foi construído para
 * não contar.
 *
 * E a gravação rodava em `viewModelScope`: custódia que para porque o agente fechou
 * a tela é defeito, não recorte.
 *
 * ## O que sobrou aqui
 *
 * Nada além de encaminhamento e dos fluxos que a composição observa. Se este
 * arquivo voltar a crescer, é sinal de que alguma regra de negócio entrou pela tela.
 */
class CopilotoViewModel(app: Application) : AndroidViewModel(app) {

    private val cerebro: CerebroDoCopiloto = CopilotoDoAgente.de(app)

    /** O texto de status. Continua existindo sem tela — só ninguém lê. */
    val commandStatus: StateFlow<String> = cerebro.status

    val copilotoOcupado: StateFlow<Boolean> = cerebro.ocupado

    fun anunciarCapacidadesPerdidas() = cerebro.anunciarCapacidadesPerdidas()

    /** Comando por TEXTO (bypassa o STT): roteador → resposta lacônica → TTS. */
    fun runCommand(text: String) = cerebro.runCommand(text)

    fun falarComando() = cerebro.falarComando()

    /**
     * O ciclo de voz por toque de botão.
     *
     * O mesmo ciclo que a palavra de ativação dispara pelo serviço — e é essa
     * identidade que importa: dois caminhos para o mesmo `cicloDeVoz` significam
     * que o botão prova o caminho falado, e que a guarda de reentrância vale entre
     * os dois. Fossem duas cópias, o agente poderia disparar o ciclo pela voz e
     * pelo botão ao mesmo tempo, com duas capturas concorrendo pelo microfone.
     */
    fun cicloDeVoz() = cerebro.cicloDeVoz()
}
