package com.claryon.knowledge

/**
 * **A palavra do agente traduzida para a palavra da lei.**
 *
 * ## Por que isto existe, com o número que obrigou
 *
 * Das 88 perguntas anotadas, **55 dividem uma única palavra ou nenhuma** com o
 * artigo que as responde. O agente diz *"o cara me xingou"*; o Código Penal diz
 * *"Desacatar funcionário público"*. Diz *"racha na avenida"*; o CTB diz
 * *"Disputar corrida"*. Diz *"atropelou e matou"*; o CTB diz *"homicídio
 * culposo na direção de veículo automotor"* — **zero** palavras em comum.
 *
 * Isso não é flexão, e nenhum radicalizador nem n-grama atravessa: é vocabulário
 * diferente para o mesmo fato. Sem esta camada o índice fica em **35/88
 * (39,8%)** de recall@1; com ela, **46/88 (52,3%)**. Ver `RecuperacaoMedidaTest`.
 *
 * ## Como foi construído, e por que dá para acreditar no número
 *
 * As entradas foram escritas olhando **só a metade par** do conjunto de
 * perguntas. A metade ímpar e as 28 perguntas escritas depois nunca foram
 * consultadas durante a construção — e é nelas que o ganho tem de aparecer para
 * ser ganho de verdade, e não eco do próprio conjunto de avaliação.
 *
 * Apareceu, e igual: **16/30** na metade que o léxico viu, **16/30** na que ele
 * não viu, **14/28** nas escritas depois. Um léxico ajustado ao conjunto teria
 * a primeira coluna muito acima das outras duas;
 * `oRecallSeSustentaNasTresParticoes` derruba o build se isso passar a
 * acontecer.
 *
 * ## O que este mapa não é
 *
 * Não é uma ontologia jurídica e não pretende ser exaustivo. É a mesma figura
 * que `LexicoDeOcorrencias` já é para o gatilho de voz: uma lista curta,
 * legível, que um humano do domínio corrige em minutos. Termo que falta custa
 * uma recusa — nunca uma norma errada, porque quem decide falar é
 * [PortaDoConhecimento].
 *
 * **A expansão vale menos que o que o agente disse de fato** (ver o
 * parâmetro `pesoDaExpansao` de `IndiceLexical`, medido em 0,5): ela é palpite
 * sobre a intenção, não evidência.
 */
internal object LexicoDeDominio {

    /** Uma palavra do agente → os termos da lei que ela costuma querer dizer. */
    private val PALAVRA: Map<String, String> = mapOf(
        // ---------------------------------------------------- veículo e trânsito
        "moto" to "motocicleta motoneta ciclomotor veiculo",
        "motoca" to "motocicleta motoneta ciclomotor veiculo",
        "motoqueiro" to "motocicleta motoneta ciclomotor condutor",
        "carro" to "veiculo automotor",
        "carreta" to "veiculo carga reboque semirreboque",
        "caminhao" to "veiculo carga",
        "onibus" to "veiculo transporte coletivo passageiros",
        "motorista" to "condutor",
        "carteira" to "carteira nacional habilitacao permissao dirigir",
        "cnh" to "carteira nacional habilitacao permissao dirigir",
        "habilitacao" to "carteira nacional habilitacao permissao dirigir",
        "documento" to "certificado registro licenciamento porte obrigatorio",
        "documentos" to "certificado registro licenciamento porte obrigatorio",
        "placa" to "placa identificacao veiculo",
        "chassi" to "chassi monobloco sinal identificador",
        "guincho" to "remocao veiculo deposito",
        "patio" to "deposito veiculo removido",
        "rebocar" to "remocao veiculo deposito",
        "remover" to "remocao veiculo deposito",
        "apreender" to "apreensao veiculo medida administrativa",
        "bebado" to "embriaguez alcool influencia capacidade psicomotora alterada",
        "bebada" to "embriaguez alcool influencia capacidade psicomotora alterada",
        "alcoolizado" to "embriaguez alcool influencia capacidade psicomotora alterada",
        "embriagado" to "embriaguez alcool influencia capacidade psicomotora alterada",
        "briagado" to "embriaguez alcool influencia capacidade psicomotora alterada",
        "bafometro" to "teste alcoolemia etilometro exame clinico pericia alcool",
        "bafo" to "teste alcoolemia etilometro exame clinico alcool",
        "racha" to "disputar corrida competicao velocidade via",
        "pega" to "disputar corrida competicao velocidade via",
        "capacete" to "capacete seguranca motocicleta",
        "acidente" to "sinistro transito",
        "batida" to "sinistro transito",
        "atropelou" to "homicidio culposo lesao corporal direcao veiculo",
        "atropelamento" to "homicidio culposo lesao corporal direcao veiculo",
        "blitz" to "fiscalizacao transito bloqueio viario policial",
        "bloqueio" to "bloqueio viario policial transpor",

        // ------------------------------------ crimes: a palavra da rua → o nome legal
        "xingou" to "desacatar funcionario publico",
        "xingar" to "desacatar funcionario publico",
        "ofendeu" to "desacatar funcionario publico injuria",
        "desacatou" to "desacatar funcionario publico",
        "desobedeceu" to "desobedecer ordem legal funcionario publico",
        "reagiu" to "resistencia opor execucao ato legal violencia ameaca",
        "resistiu" to "resistencia opor execucao ato legal violencia ameaca",
        "empurrou" to "resistencia opor execucao ato legal violencia",
        "matou" to "matar homicidio",
        "morte" to "matar homicidio morte",
        "assassinou" to "matar homicidio",
        "bateu" to "ofender integridade corporal lesao",
        "agrediu" to "ofender integridade corporal lesao violencia",
        "roubaram" to "subtrair coisa alheia movel grave ameaca violencia roubo",
        "roubou" to "subtrair coisa alheia movel grave ameaca violencia roubo",
        "roubado" to "produto crime receptacao subtrair",
        "assalto" to "subtrair grave ameaca violencia roubo",
        "levaram" to "subtrair coisa alheia movel",
        "furtaram" to "subtrair coisa alheia movel furto",
        "furtou" to "subtrair coisa alheia movel furto",
        "ameacou" to "ameacar mal injusto grave",
        "ameaca" to "ameacar mal injusto grave",
        "quebrou" to "destruir inutilizar deteriorar coisa alheia dano",
        "estragou" to "destruir inutilizar deteriorar coisa alheia dano",
        "invadiu" to "entrar permanecer casa alheia domicilio",
        "combinados" to "associarem associacao criminosa cometer crimes",
        "quadrilha" to "associarem associacao criminosa",
        "bando" to "associarem associacao criminosa",
        "receptacao" to "adquirir receber ocultar produto crime",
        "raspado" to "adulterar remarcar suprimir sinal identificador",
        "adulterado" to "adulterar remarcar suprimir sinal identificador",

        // ------------------------------------------------------------ procedimento
        "revistar" to "busca pessoal fundada suspeita",
        "revista" to "busca pessoal fundada suspeita",
        "baculejo" to "busca pessoal fundada suspeita",
        "enquadrar" to "busca pessoal fundada suspeita abordagem",
        "prender" to "prisao flagrante delito",
        "prendi" to "prisao flagrante delito",
        "voz" to "prisao flagrante delito",
        "delegado" to "autoridade policial",
        "delegacia" to "autoridade policial reparticao",
        "arbitrar" to "conceder fianca",
        "custodia" to "audiencia custodia auto prisao flagrante juiz",
        "algema" to "algemas uso",
        "algemar" to "algemas uso",
        "preservar" to "local nao alterem estado conservacao coisas peritos",
        "pericia" to "peritos exame corpo delito",
        "lavrar" to "auto lavrando autoridade",
        "boletim" to "auto ocorrencia registro",

        // ------------------------------------------------------------------ drogas
        "maconha" to "drogas substancia entorpecente",
        "cocaina" to "drogas substancia entorpecente",
        "crack" to "drogas substancia entorpecente",
        "droga" to "drogas substancia entorpecente",
        "entorpecente" to "drogas substancia",
        "traficar" to "trafico vender expor venda fornecer drogas",
        "trafico" to "vender expor venda fornecer ter deposito drogas",
        "traficante" to "vender expor venda fornecer drogas",
        "usuario" to "consumo pessoal drogas",
        "consumo" to "consumo pessoal drogas",
        "laudo" to "laudo constatacao natureza quantidade droga perito",

        // ------------------------------------------------------------------- armas
        "arma" to "arma fogo acessorio municao",
        "armado" to "portar arma fogo",
        "pistola" to "arma fogo",
        "revolver" to "arma fogo",
        "fuzil" to "arma fogo uso restrito",
        "espingarda" to "arma fogo",
        "bala" to "municao",
        "municao" to "municao acessorio arma fogo",
        "atirou" to "disparar arma fogo acionar municao",
        "disparo" to "disparar arma fogo acionar municao",
        "tiro" to "disparar arma fogo acionar municao",
        "porte" to "portar deter transportar arma fogo",
        "portar" to "portar deter transportar arma fogo",
        "posse" to "possuir manter guarda residencia",
        "registro" to "registro sinarm autorizacao",
    )

    /**
     * Pares que só juntos significam o que significam.
     *
     * `"voz"` sozinha não é prisão e `"prisao"` sozinha não é a voz; *"voz de
     * prisão"* é o art. 301 do CPP. Casar a palavra isolada aqui espalharia o
     * escore por todo artigo que fala em voz ou em prisão.
     */
    private val PAR: Map<String, String> = mapOf(
        "voz prisao" to "prender flagrante delito qualquer povo autoridades policiais",
        "nota culpa" to "nota culpa recibo motivo prisao condutor testemunhas",
        "uso restrito" to "uso restrito proibido",
        "uso permitido" to "uso permitido",
        "consumo proprio" to "consumo pessoal drogas",
        "legitima defesa" to "legitima defesa repele injusta agressao moderadamente",
        "corpo delito" to "exame corpo delito pericia",
    )

    /** Quantas entradas o léxico tem. Existe para o teste conferir que não esvaziou. */
    val tamanho: Int get() = PALAVRA.size + PAR.size

    /**
     * Os termos da lei sugeridos por [pergunta] — vazio quando nada é
     * reconhecido, que é o caso normal de pergunta fora do domínio.
     */
    fun expansaoDe(pergunta: String): String {
        val ws = AnalisadorPtBr.palavras(pergunta)
        val fora = ArrayList<String>()
        for (w in ws) PALAVRA[w]?.let(fora::add)
        for (i in 0 until ws.size - 1) PAR["${ws[i]} ${ws[i + 1]}"]?.let(fora::add)
        return fora.joinToString(" ")
    }
}
