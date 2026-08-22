package com.claryon.field.agent

import com.claryon.agent.BuscaDeLugar
import com.claryon.agent.CategoriaDeLugar
import com.claryon.agent.ConsultaHigienizada
import com.claryon.agent.FonteDaResposta
import com.claryon.agent.LugarProximo
import com.claryon.agent.RegistroDeAuditoria
import com.claryon.agent.RegistroDeUso
import com.claryon.net.FonteGeoespacial
import com.claryon.net.LugarProcurado
import com.claryon.net.ProcedenciaExterna
import com.claryon.net.RespostaGeoespacial
import java.time.ZoneId

/**
 * **A costura que faltava: `CategoriaDeLugar` → Overpass → `BuscaDeLugar`.**
 *
 * Sem este arquivo a capacidade inteira era *escrita e não construída*, que é o
 * defeito que o `CLAUDE.md` §6 conta seis vezes neste projeto: `ConsultaGeoespacial`
 * existia, `HigieneDaConsulta` existia, `RegistroDeUso` existia, e o executor tinha
 * um `procurarLugar` cujo padrão **recusava** — então em produção a cascata externa
 * terminava sempre em "Sem rede para consultar.", com ou sem rede.
 *
 * ## Por que a costura vive num arquivo só
 *
 * `core-agent` e `core-net` não se enxergam (os `core-*` só dependem de
 * `core-common`), e por isso os dois lados têm enums espelhados —
 * [CategoriaDeLugar] e [LugarProcurado]. É o mesmo desenho de `ClasseDeRestricao` ×
 * `Restricao`, e a regra é a mesma: **quem espelha é `app`, num arquivo só,
 * auditável de olho**. Espalhar a tradução por ramos de `when` em três lugares é
 * como se constrói a divergência que ninguém percebe.
 *
 * O `when` de [paraFonte] **não tem `else`**. Categoria nova quebra o build aqui,
 * dentro da costura, em vez de virar silenciosamente uma busca por hospital.
 *
 * ## O que atravessa esta fronteira, e só isso
 *
 * Uma constante de enum e a **minha** coordenada. Não atravessa transcrição, não
 * atravessa placa, não atravessa matrícula, não atravessa indicativo, e não
 * atravessa posição de par — repare que este objeto **não recebe** nada disso; o
 * construtor não tem por onde. O arredondamento a quatro casas (~11 m) acontece do
 * lado de `core-net`, imediatamente antes de virar bytes.
 *
 * ## Os dois registros, e por que são duas chamadas
 *
 * [DiarioDaConsultaExterna] recebe **auditoria** e **uso** por caminhos separados
 * (spec §4 × §7.5). Aqui isso aparece como duas chamadas em pontos diferentes:
 * `auditar` só onde houve resposta da fonte, `contabilizar` em **todo** desfecho —
 * inclusive nos que falharam, porque a pergunta que a rede não respondeu é
 * exatamente a que o corpus precisa aprender.
 */
class LugarPelaRede(
    private val fonte: FonteGeoespacial,
    /**
     * Onde estou. `null` = sem correção recente de GPS.
     *
     * A ausência **não** vira [BuscaDeLugar.SemRede]. *"O hospital mais próximo"* é
     * pergunta relativa: sem centro não há busca, e a recuperação é esperar sinal
     * de GPS, não andar atrás de rede. Colapsar as duas repetiria o defeito do
     * rádio consertado em 22/08, em que falta de rede se disfarçava de canal
     * ocupado.
     */
    private val minhaPosicao: suspend () -> Coordenada?,
    private val diario: DiarioDaConsultaExterna = DiarioDaConsultaExterna.DO_PROCESSO,
    private val agora: () -> Long = { System.currentTimeMillis() },
    /**
     * O fuso em que o **dia** do registro de uso é calculado. Explícito, e não
     * `ZoneId.systemDefault()` enterrado lá dentro, porque um teste que não
     * controla o fuso mede o relógio da máquina de CI em vez de medir o código.
     */
    private val zona: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /**
     * O último degrau da cascata, executado.
     *
     * `procurarLugar` do [ClaryonIntentExecutor] aponta para cá — é esta função que
     * transforma a dependência injetada que recusava por omissão em capacidade com
     * chamador alcançável em runtime.
     */
    suspend fun procurar(categoria: CategoriaDeLugar): BuscaDeLugar {
        // **A higiene acontece ANTES de qualquer outra coisa**, e o resultado tem
        // dois consumidores que são a mesma string (spec §5): o que sai e o que é
        // registrado. Não existe caminho em que um deles receba outro texto.
        val consulta = ConsultaHigienizada.de(categoria)

        val onde = minhaPosicao() ?: run {
            contabilizar(consulta, respondida = false, fonte = FonteDaResposta.NENHUMA)
            return BuscaDeLugar.SemPosicaoPropria
        }

        // O prazo de 2 s (decisão 1 da spec) é do `ConsultaGeoespacial`, aplicado
        // como `callTimeout` — a chamada INTEIRA, DNS incluído. Não há `withTimeout`
        // aqui em cima, e isso é de propósito: dois prazos concorrentes produzem
        // dois desfechos para o mesmo estouro, e o de fora cancelaria a corrotina
        // sem que o de dentro pudesse dizer *por quê*.
        return when (val r = fonte.maisProximo(categoria.paraFonte(), onde.latitude, onde.longitude)) {

            is RespostaGeoespacial.Encontrado -> {
                auditar(consulta, r.procedencia)
                contabilizar(consulta, respondida = true, fonte = FonteDaResposta.EXTERNA_ESTRUTURADA)
                // Só nome e distância atravessam de volta. Latitude, longitude,
                // endereço e telefone ficam do outro lado — `LugarProximo` não tem
                // onde pô-los, que é a garantia barata contra alguém lê-los em voz
                // alta dentro do teto de sete palavras.
                BuscaDeLugar.Encontrado(LugarProximo(r.nome, r.distanciaM))
            }

            is RespostaGeoespacial.NadaPorPerto -> {
                // **Auditada também.** A fonte respondeu; a resposta foi "nada". É o
                // caso em que não há linha de resultado para carregar a procedência,
                // e por isso é o que se esquece — foi assim na base veicular.
                auditar(consulta, r.procedencia)
                contabilizar(consulta, respondida = false, fonte = FonteDaResposta.EXTERNA_ESTRUTURADA)
                BuscaDeLugar.NadaPorPerto
            }

            // As duas abaixo NÃO são auditadas: não houve resposta de fonte nenhuma,
            // e inventar serviço e carimbo seria registrar consulta que não
            // aconteceu. Contabilizadas, sim — a pergunta foi feita.
            RespostaGeoespacial.SemRede -> {
                contabilizar(consulta, respondida = false, fonte = FonteDaResposta.NENHUMA)
                BuscaDeLugar.SemRede
            }

            RespostaGeoespacial.PrazoEstourado -> {
                contabilizar(consulta, respondida = false, fonte = FonteDaResposta.NENHUMA)
                BuscaDeLugar.PrazoEstourado
            }
        }
    }

    private fun auditar(consulta: ConsultaHigienizada, p: ProcedenciaExterna) {
        diario.auditar(
            RegistroDeAuditoria.de(
                consulta = consulta,
                servico = p.servico,
                consultaEmitida = p.consultaEmitida,
                trecho = p.trecho,
                carimboMillis = p.carimboMillis,
                duracaoMs = p.duracaoMs,
            ),
        )
    }

    private fun contabilizar(
        consulta: ConsultaHigienizada,
        respondida: Boolean,
        fonte: FonteDaResposta,
    ) {
        diario.contabilizar(
            RegistroDeUso.de(
                consulta = consulta,
                respondida = respondida,
                fonte = fonte,
                // Entra com precisão e **sai como dia**: `RegistroDeUso` não tem
                // onde guardar o resto. Ver o KDoc dele.
                epochMillis = agora(),
                zona = zona(),
            ),
        )
    }
}

/**
 * A tradução entre os dois enums espelhados. **Sem `else`**, de propósito.
 *
 * Uma [CategoriaDeLugar] nova sem [LugarProcurado] correspondente quebra a
 * compilação nesta linha. Com `else`, ela viraria uma busca por hospital em
 * silêncio — e o agente ouviria a resposta certa para a pergunta errada, que é
 * pior que não responder.
 */
internal fun CategoriaDeLugar.paraFonte(): LugarProcurado = when (this) {
    CategoriaDeLugar.HOSPITAL -> LugarProcurado.HOSPITAL
    CategoriaDeLugar.DELEGACIA -> LugarProcurado.DELEGACIA
    CategoriaDeLugar.POSTO_DE_SAUDE -> LugarProcurado.POSTO_DE_SAUDE
}
