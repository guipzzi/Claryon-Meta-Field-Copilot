package com.claryon.agent

/**
 * **O PRIMEIRO dos dois registros da consulta externa: a auditoria, no aparelho.**
 *
 * A spec (`specs/consulta-externa.spec.md`) pede duas coisas que parecem
 * contraditórias e não são — porque são **dois registros com propósitos
 * opostos**, e a separação é o ponto:
 *
 * | | auditoria (este) | estatística de uso ([RegistroDeUso]) |
 * |---|---|---|
 * | de onde vem | §4 — *"a URL ou o serviço, o trecho exato, o carimbo de tempo"* | §7.5 — *"a granularidade é o dia, não o segundo"* |
 * | para quê | o agente conferir depois; a resposta ser auditável se contestada | o corpus saber onde crescer |
 * | fica onde | **no aparelho, do próprio agente** | pode sair do aparelho |
 * | tempo | **preciso** | o dia |
 * | serviço e trecho | **sim** | não |
 *
 * Implementá-los como **um** registro só faria a precisão deste vazar para o
 * outro — que é exatamente a propriedade que [RegistroDeUso] existe para negar.
 * Por isso são dois tipos, e por isso **não há conversão entre eles**: nenhuma
 * função aqui produz um [RegistroDeUso], e nenhuma lá produz um destes. Duas
 * chamadas separadas, dois destinos separados.
 *
 * ## Por que ele carrega a consulta emitida
 *
 * [consultaEmitida] é o que de fato saiu pela rede, byte a byte. O §5 promete que
 * *a pergunta que sai não é a transcrição*; o teste prova isso na bancada, e este
 * campo prova isso **no aparelho, depois do fato**. São coisas diferentes: um
 * teste verde diz que o código estava certo quando alguém rodou a suíte; o
 * registro diz o que este aparelho mandou naquela consulta.
 *
 * ## O que ele NÃO carrega
 *
 * Nem identificador de agente, nem guarnição, nem aparelho, nem posição. Não é
 * omissão de preenchimento — **é ausência de lugar onde guardar**, o mesmo desenho
 * de `PontoDoRastro`. Um registro de auditoria que ficasse no aparelho do agente e
 * dissesse *quem* ele é seria redundante (é o aparelho dele) e perigoso (viraria
 * um histórico exportável no dia em que alguém exportasse o log).
 *
 * A **posição** é o caso que mais tenta: ela está na consulta emitida, arredondada
 * a quatro casas, porque sem ela a consulta não existe. O que não existe é um
 * campo `posicao` — que seria o lugar por onde a coordenada crua entraria depois.
 */
data class RegistroDeAuditoria(
    /** A **mesma** string que foi para a fonte externa. Ver [ConsultaHigienizada]. */
    val consulta: String,
    /** A categoria de intenção reconhecida — o nome do enum. */
    val categoria: String,
    /** A URL ou o serviço de origem. §4. */
    val servico: String,
    /** O que saiu pela rede, literal. */
    val consultaEmitida: String,
    /**
     * O **trecho exato** que fundamentou a resposta. Vazio quando a fonte
     * respondeu que não há nada — e vazio é o fato, não um buraco: não houve
     * trecho. A resposta continua registrada, com serviço e carimbo, porque
     * *"nada por perto"* também é resposta de fonte externa e o aceite do §6 diz
     * **toda**.
     */
    val trecho: String,
    /** Quando, com precisão. Este registro não sai do aparelho. */
    val carimboMillis: Long,
    /** Quanto custou, para a régua de latência do §6 ter o que medir. */
    val duracaoMs: Long,
) {

    companion object {

        /**
         * Só aceita [ConsultaHigienizada], pela mesma razão de [RegistroDeUso.de]:
         * o §5 manda *um filtro, dois consumidores*. Uma sobrecarga que aceitasse
         * `String` criaria o segundo lugar onde a higiene pode divergir.
         */
        fun de(
            consulta: ConsultaHigienizada,
            servico: String,
            consultaEmitida: String,
            trecho: String,
            carimboMillis: Long,
            duracaoMs: Long,
        ): RegistroDeAuditoria = RegistroDeAuditoria(
            consulta = consulta.texto,
            categoria = consulta.categoria.name,
            servico = servico,
            consultaEmitida = consultaEmitida,
            trecho = trecho,
            carimboMillis = carimboMillis,
            duracaoMs = duracaoMs,
        )
    }
}
