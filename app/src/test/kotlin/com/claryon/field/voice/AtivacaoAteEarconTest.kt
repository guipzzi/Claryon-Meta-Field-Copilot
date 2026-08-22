package com.claryon.field.voice

import com.claryon.common.Telemetry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A métrica da palavra de ativação produz amostra?**
 *
 * A pergunta não é retórica. A linha que esta transição substituiu dizia *"wake
 * word: sem produtor"* e era verdadeira por anos; o risco agora é o inverso e é
 * pior — uma transição que existe, aparece no relatório e **nunca coleta nada**,
 * porque os marcos caem em ciclos diferentes. Isso não gera erro: gera um
 * "sem amostras" eterno que se parece com "ainda não medimos".
 *
 * O caminho real tem três produtores em dois objetos: `CopilotService` abre o
 * ciclo e marca `WAKE_DETECTED`; `SaidaUnica` marca `EARCON_PLAYED` no ciclo
 * CORRENTE quando o som entra no `AudioTrack`; e `CerebroDoCopiloto` reaproveita
 * o id. Basta um deles gerar id próprio para a métrica morrer em silêncio.
 *
 * Estes testes exercitam exatamente essa junção, sem Android.
 */
class AtivacaoAteEarconTest {

    private val META_MS = 500L

    @Test
    fun aTransicaoDaAtivacaoTemMetaDeclaradaDeMeioSegundo() {
        val t = TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON
        assertTrue(
            "a meta do roadmap para 'fim de Claryon → earcon' é 500 ms; achei ${t.metaMs}",
            t.metaMs == META_MS,
        )
    }

    /**
     * O caminho feliz, na ordem exata em que o runtime executa: abrir → marcar a
     * ativação → tocar o earcon. Se isso não fecha, nada fecha.
     */
    @Test
    fun ativacaoSeguidaDeEarcon_noMesmoCiclo_produzAmostra() {
        val tel = TelemetriaDoCicloDeVoz()
        tel.abrirCiclo("ativacao-1")
        tel.mark("ativacao-1", Telemetry.Stage.WAKE_DETECTED, 1_000)
        tel.mark("ativacao-1", Telemetry.Stage.EARCON_PLAYED, 1_320)

        val m = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON)
        assertNotNull("a métrica não coletou a amostra — nasceu morta", m)
        assertTrue("uma amostra, não zero: $m", m!!.amostras == 1)
        assertTrue("os 320 ms deviam ser a medição: $m", m.p50 == 320L)
    }

    /**
     * **O contra-teste, e é ele que dá sentido ao de cima.**
     *
     * Com ids diferentes — que é o defeito plausível, porque `cicloDeVoz` gerava o
     * próprio id antes de 20/08 — a métrica tem de ficar SEM amostra. Se ela
     * coletasse mesmo assim, estaria somando instantes de ciclos distintos e o
     * número seria lixo com cara de medição.
     */
    @Test
    fun ativacaoEEarconEmCiclosDIFERENTES_naoProduzemAmostra() {
        val tel = TelemetriaDoCicloDeVoz()
        tel.abrirCiclo("ativacao-1")
        tel.mark("ativacao-1", Telemetry.Stage.WAKE_DETECTED, 1_000)
        // O ciclo de voz gerou id próprio: o earcon cai noutro balde.
        tel.abrirCiclo("ciclo-2")
        tel.mark("ciclo-2", Telemetry.Stage.EARCON_PLAYED, 1_320)

        assertNull(
            "com ids divergentes a métrica NÃO pode coletar — se coletou, está " +
                "somando instantes de ciclos diferentes",
            tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON),
        )
    }

    /**
     * A ativação tem zero próprio e **não** pode depender do VAD.
     *
     * O fecho da transição foi posto antes da guarda `VAD_WINDOW_CLOSED ?: return`
     * justamente por isto: um ciclo que dispara o earcon e depois falha — sem fala,
     * sem rota, timeout — é dos que mais interessam medir, e ele nunca fecha janela
     * de VAD nenhuma.
     */
    @Test
    fun aAmostraExisteMesmoQuandoOCicloFALHA_semVadNemResposta() {
        val tel = TelemetriaDoCicloDeVoz()
        tel.abrirCiclo("ativacao-1")
        tel.mark("ativacao-1", Telemetry.Stage.WAKE_DETECTED, 5_000)
        tel.mark("ativacao-1", Telemetry.Stage.EARCON_PLAYED, 5_410)
        // E acabou: o agente não falou, o VAD nunca fechou janela.

        assertNotNull(
            "a métrica da ativação morreu junto com o ciclo — mas é o ciclo que " +
                "falha o que mais interessa medir",
            tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON),
        )
        assertNull(
            "sem VAD não pode haver amostra de 'fim da fala → earcon'",
            tel.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_EARCON),
        )
    }

    /**
     * `marcarNoCicloCorrente` é o caminho REAL do earcon: `SaidaUnica` não carrega
     * o id, marca no corrente. Se `abrirCiclo` não tiver rodado antes, não há
     * corrente e o marco se perde — que é a corrida que o serviço evita abrindo o
     * ciclo ANTES de emitir.
     */
    @Test
    fun oEarconMarcaNoCicloCORRENTE_queEOCaminhoDeVerdade() {
        val tel = TelemetriaDoCicloDeVoz()
        tel.abrirCiclo("ativacao-1")
        tel.mark("ativacao-1", Telemetry.Stage.WAKE_DETECTED, 2_000)
        tel.marcarNoCicloCorrente(Telemetry.Stage.EARCON_PLAYED, 2_450)

        assertNotNull(
            "o earcon marcou fora do ciclo corrente — é assim que ele chega em produção",
            tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON),
        )
    }

    @Test
    fun semCicloAberto_oEarconSePerde_eIssoTemDeSerVisivel() {
        val tel = TelemetriaDoCicloDeVoz()
        // Nada de `abrirCiclo`: é o que aconteceria se o serviço emitisse o earcon
        // antes de abrir o ciclo. O marco não tem onde cair.
        tel.marcarNoCicloCorrente(Telemetry.Stage.EARCON_PLAYED, 100)
        assertNull(tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON))
    }

    /**
     * **A regressão que a revisão adversarial achou, escrita como cenário.**
     *
     * No caminho da palavra de ativação há DOIS earcons que marcam ciclo:
     * `DESPERTAR` na detecção e `CANAL_FECHADO` no fechamento do VAD (até 22/08 os
     * dois eram o MESMO som, `OUVI_VOCE`, o que já era um defeito de produto por
     * si). Os dois continuam chegando pelo mesmo estágio `EARCON_PLAYED`. E
     * o ciclo é UM só, porque `cicloDeVoz` reaproveita o id da ativação — sem
     * isso a métrica dos 500 ms não fecharia.
     *
     * Consequência: `EARCON_PLAYED` é primeiro-marco-vence, fica com o instante do
     * earcon #1, e `FIM_DA_FALA_ATE_EARCON` calcula `earcon1 − fimDaFala`, que é
     * **negativo** por segundos. Pior, `registrar` fazia `jaRegistradas.add` ANTES
     * de checar o negativo: o par ficava envenenado e a transição não podia mais
     * ser registrada nunca, nem por um ciclo posterior saudável.
     *
     * **E não há botão.** O produto é acionado só por voz, então "a métrica sobrevive
     * pelo outro caminho" não era consolo nenhum: seria zero amostra em produção,
     * para sempre. O desempate está em `mark` — o primeiro earcon de um ciclo que já
     * acordou e ainda não fechou o VAD é o da ativação; o seguinte é o do comando.
     */
    @Test
    fun noCaminhoDaVoz_asDUASMetricasMedem_cadaUmaComSeuEarcon() {
        val tel = TelemetriaDoCicloDeVoz()

        // Ciclo pela voz: wake → earcon#1 → fala → VAD fecha → earcon#2 (engolido).
        tel.abrirCiclo("ativacao-1")
        tel.mark("ativacao-1", Telemetry.Stage.WAKE_DETECTED, 1_000)
        tel.mark("ativacao-1", Telemetry.Stage.EARCON_PLAYED, 1_300)
        tel.mark("ativacao-1", Telemetry.Stage.VAD_WINDOW_CLOSED, 4_000)
        tel.mark("ativacao-1", Telemetry.Stage.EARCON_PLAYED, 4_120)

        // **Agora as DUAS medem, no mesmo ciclo por voz.** Antes o earcon da
        // ativação consumia `EARCON_PLAYED` e esta métrica ficava vazia — o que,
        // num produto SEM BOTÃO, significa zero amostras para sempre.
        val doComando = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_EARCON)
        assertNotNull("a métrica do comando ficou sem amostra no caminho da voz", doComando)
        assertTrue("4120 − 4000 = 120 ms: $doComando", doComando!!.p50 == 120L)

        val daAtivacao = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.ATIVACAO_ATE_EARCON)
        assertNotNull(daAtivacao)
        assertTrue(
            "a métrica da ativação pegou o earcon errado: $daAtivacao (devia ser 300)",
            daAtivacao!!.p50 == 300L,
        )

        // O botão continua medindo: é o que prova que o envenenamento é por ciclo.
        tel.abrirCiclo("ciclo-2")
        tel.mark("ciclo-2", Telemetry.Stage.VAD_WINDOW_CLOSED, 10_000)
        tel.mark("ciclo-2", Telemetry.Stage.EARCON_PLAYED, 10_305)

        val depois = tel.medicao(TelemetriaDoCicloDeVoz.Transicao.FIM_DA_FALA_ATE_EARCON)
        assertNotNull(
            "o ciclo pelo botão não registrou depois de um ciclo por voz — o " +
                "envenenamento vazou entre ciclos e a métrica da Fase 1 morreria",
            depois,
        )
        assertTrue("a medição do botão devia ser 305 ms: $depois", depois!!.p50 == 305L)
    }

    /**
     * O relatório não pode mais afirmar que não há produtor: a frase fixa foi
     * removida justamente porque envelheceu para mentira. Quem informa ausência é
     * o "sem amostras" calculado.
     */
    @Test
    fun oRelatorioNaoAfirmaMaisQueNaoHaProdutor() {
        val texto = TelemetriaDoCicloDeVoz().relatorio()
        assertTrue(
            "a frase fixa 'sem produtor' voltou ao relatório: ela vira mentira no " +
                "dia em que alguém liga o produtor, sem ninguém mexer nela\n$texto",
            !texto.contains("sem produtor"),
        )
        assertTrue(
            "a transição da ativação sumiu do relatório\n$texto",
            texto.contains("palavra de ativação → earcon"),
        )
    }
}
