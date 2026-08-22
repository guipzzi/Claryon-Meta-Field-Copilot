package com.claryon.agent

import java.time.Instant
import java.time.ZoneId

/** Quem respondeu. Registrado sempre; **falado nunca** — ver `Utterance`. */
enum class FonteDaResposta {
    /** Corpus de normas, base veicular, posição. O que já está no aparelho. */
    LOCAL,

    /** Geoespacial estruturada (OSM/Overpass). Sem chave, sem modelo, sem prosa. */
    EXTERNA_ESTRUTURADA,

    /** Ninguém respondeu: recusa falada com motivo. */
    NENHUMA,
}

/**
 * **A estatística de uso — o registro que pode sair do aparelho, e o andaime da
 * decisão 5.**
 *
 * A consulta externa não é uma dependência: é um andaime. Cada pergunta que a
 * rede responde é uma pergunta que o corpus **deveria** responder, e este
 * registro é o mapa de para onde ele precisa crescer — construído com uso real,
 * não com palpite sobre o que o policial perguntaria. A curva pretendida está na
 * spec: v1 pergunta a rede em ~2 s e falha sem sinal; vN responde local em 618 ms
 * e funciona sem sinal.
 *
 * ## Este é o SEGUNDO dos dois registros, e a separação é o ponto
 *
 * A spec pede carimbo de tempo da consulta (§4) **e** granularidade de dia (§7.5).
 * Isso não é contradição: são **dois registros com propósitos opostos**.
 *
 * | | auditoria da resposta | estatística de uso (este) |
 * |---|---|---|
 * | para quê | o agente conferir depois | o corpus saber onde crescer |
 * | fica onde | no aparelho, do próprio agente | pode sair do aparelho |
 * | tempo | preciso | **o dia** |
 * | fonte e trecho | sim | não |
 *
 * Implementá-los como **um** registro só faria a precisão do primeiro vazar para
 * o segundo — que é exatamente a propriedade que este tipo existe para negar.
 *
 * ## O que NÃO existe aqui, e a ausência é a garantia
 *
 * Não há campo para agente, guarnição, aparelho, posição, nem carimbo com hora.
 * **Não é omissão de preenchimento: é ausência de lugar onde guardar** — o mesmo
 * desenho de `PontoDoRastro`, que não tem onde pôr latitude, e por isso nenhum
 * caminho futuro passa a carregá-la por acidente.
 *
 * Sem agente, sem posição e sem hora exata, **duas perguntas do mesmo turno não
 * são ligáveis entre si**. É essa propriedade, e só ela, que separa "estatística
 * de uso" de "histórico de um policial".
 *
 * ## Por que a fábrica só aceita [ConsultaHigienizada]
 *
 * Porque a spec diz *"um filtro, dois consumidores"*: o que sai para a fonte
 * externa e o que entra no registro são a **mesma** string. Uma sobrecarga que
 * aceitasse `String` criaria o segundo lugar onde a higiene pode divergir, e o
 * segundo lugar é sempre o que fica para trás no próximo conserto.
 *
 * **Consequência aceita e escrita:** a pergunta de **norma** recusada NÃO entra
 * aqui hoje. Ela é texto livre do agente, não tem [ConsultaHigienizada], e
 * inventar um segundo caminho de higiene para ela é precisamente o que a decisão
 * 5 proíbe. Alimentar o corpus com as perguntas de norma recusadas é capacidade
 * legítima e **começa por diff de spec** (`CLAUDE.md` §7), não por uma sobrecarga
 * aqui.
 */
data class RegistroDeUso(
    /** A **mesma** string que foi para a fonte externa. Ver [ConsultaHigienizada]. */
    val consulta: String,
    /** A categoria de intenção reconhecida — o nome do enum, estável para agregar. */
    val categoria: String,
    val respondida: Boolean,
    val fonte: FonteDaResposta,
    /**
     * `AAAA-MM-DD`, e nada mais fino.
     *
     * O tipo é `String` e não um instante justamente para não sobrar precisão:
     * um `Instant` aqui guardaria o segundo mesmo que ninguém o lesse, e o que
     * está guardado acaba sendo lido.
     */
    val dia: String,
) {

    /**
     * Uma linha JSON, para o arquivo que alimenta a expansão do corpus.
     *
     * Escrita à mão em vez de `org.json`: `core-agent` é Kotlin/JVM puro, sem
     * Android e sem dependência além de `core-common` — arrastar um serializador
     * para produzir cinco campos contraria a regra de dependência nova do
     * `CLAUDE.md` §2. Os valores são de vocabulário fechado ou `AAAA-MM-DD`, então
     * não há o que escapar; o `replace` de aspas fica como cinto, não como plano.
     */
    fun paraLinha(): String = buildString {
        append("{\"consulta\":\"").append(escapar(consulta))
        append("\",\"categoria\":\"").append(escapar(categoria))
        append("\",\"respondida\":").append(respondida)
        append(",\"fonte\":\"").append(fonte.name)
        append("\",\"dia\":\"").append(dia).append("\"}")
    }

    companion object {

        /**
         * @param epochMillis o instante da consulta. **Entra e é descartado**: só o
         *   dia sobrevive à chamada, porque o tipo não tem onde guardar o resto.
         * @param zona o fuso em que o dia é calculado. Explícito, e não
         *   `ZoneId.systemDefault()` embutido, porque um teste que não controla o
         *   fuso mede o relógio da máquina de CI em vez de medir o código.
         */
        fun de(
            consulta: ConsultaHigienizada,
            respondida: Boolean,
            fonte: FonteDaResposta,
            epochMillis: Long,
            zona: ZoneId,
        ): RegistroDeUso = RegistroDeUso(
            consulta = consulta.texto,
            categoria = consulta.categoria.name,
            respondida = respondida,
            fonte = fonte,
            dia = dia(epochMillis, zona),
        )

        /** `AAAA-MM-DD` no fuso dado. O resto do instante não volta de dentro daqui. */
        fun dia(epochMillis: Long, zona: ZoneId): String =
            Instant.ofEpochMilli(epochMillis).atZone(zona).toLocalDate().toString()

        private fun escapar(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
