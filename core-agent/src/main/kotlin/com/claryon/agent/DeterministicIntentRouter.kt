package com.claryon.agent

import java.text.Normalizer

/**
 * Roteador de intenções **determinístico** — o cérebro do copiloto.
 *
 * Correspondência por padrão + verbos-chave sobre a transcrição normalizada
 * (minúsculas, sem acento). **Sem LLM**: latência previsível, sem rede, auditável
 * — requisitos inegociáveis em decisão operacional de segurança pública. O que
 * não casa vira [Intent.NaoReconhecida] (earcon de falha + pedido de repetição);
 * o sistema nunca age por adivinhação.
 *
 * A ordem de avaliação vai do mais específico (emergência) ao mais genérico.
 */
class DeterministicIntentRouter : IntentRouter {

    override fun route(transcricao: String): Intent {
        // **O gatilho sai daqui, e a saída dele é o segundo estágio da ativação.**
        //
        // O whisper transcreve tudo que ouviu, inclusive a palavra de ativação — é
        // assim na Alexa também, onde o áudio enviado CONTÉM o gatilho justamente
        // para a segunda etapa poder conferi-lo. Sem tirar o prefixo, todo padrão
        // ancorado recusa fala real; medido em 20/08: "Clareon, Guarney são 1 na
        // escuta." não casava com nada.
        val semGatilho = PalavraDeAtivacaoNaFala.conferir(transcricao)
        val texto = normalizar(if (semGatilho.confirmada) semGatilho.resto else transcricao)

        // Classificado uma vez só: a varredura de gatilhos é a parte mais cara do
        // roteador e ele está no caminho crítico de 2 s entre fala e resposta.
        val ocorrencia = LexicoDeOcorrencias.classificar(transcricao)
        if (texto.isBlank()) return Intent.NaoReconhecida(transcricao)

        return when {
            matches(texto, EMERGENCIA) -> Intent.Emergencia

            matches(texto, ENCERRAR_GRAVACAO) -> Intent.EncerrarGravacao
            matches(texto, INICIAR_GRAVACAO) -> Intent.IniciarGravacao(motivo = null)

            // Verbos explícitos primeiro. Só depois o termo solto "placa" —
            // senão "narrar ocorrência: veículo de placa ABC1234" viraria
            // consulta e a narração do agente seria perdida.
            matches(texto, CONSULTAR_PLACA_EXPLICITO) ->
                Intent.ConsultarPlaca(placa = extrairPlaca(texto))

            // Comandos explícitos ANTES do léxico de ocorrências. "Modo
            // abordagem" contém "abordagem", que é tipo de ocorrência — sem esta
            // ordem, trocar de modo dispararia um alerta para a guarnição.
            matches(texto, DETALHAR) -> Intent.Detalhar
            matches(texto, MODO_STANDBY) -> Intent.TrocarModo(ModoOperacao.STANDBY)
            matches(texto, MODO_OCORRENCIA) -> Intent.TrocarModo(ModoOperacao.OCORRENCIA)
            matches(texto, MODO_ATIVO) -> Intent.TrocarModo(ModoOperacao.ATIVO)

            // C2: "onde está Alfa Dois?" — o indicativo sai da própria fala.
            matches(texto, CONSULTAR_POSICAO) ->
                extrairIndicativo(texto)?.let { Intent.ConsultarPosicao(it) }
                    ?: Intent.NaoReconhecida(transcricao)

            // Troca de grupo DEPOIS de NARRAR e dos modos, e a ordem é a mesma
            // lição de "modo abordagem": "narrar ocorrência: o suspeito mudou para
            // a rua tal" é ditado, não comando de canal. Quem disse o verbo
            // explícito de ditado ganha.
            //
            // Rótulo vazio NÃO vira troca: "claryon mudar para" sem destino é
            // comando incompleto, e adivinhar o destino é exatamente o que a spec
            // proíbe. Cai em `NaoReconhecida`, que pede repetição.
            // **ANTES da troca de grupo, e o casamento é INTEGRAL.**
            //
            // `matches` usa `contains`, que serve para verbo de comando solto no
            // meio da fala. Aqui não serve: "na escuta" é vocabulário corrente de
            // rádio, e abrir canal por conter a frase faria o produto transmitir
            // ouvindo a própria guarnição. Âncoras `^…$`, e palavra extra recusa.
            ABRIR_TRANSMISSAO.matchEntire(texto)?.let { ePalavraDeGuarnicao(it.groupValues[1]) } == true ->
                // Grafia CANÔNICA no rótulo, sempre. O resolvedor compara contra o
                // `rotulo_falado` do cadastro, e "guarney sao 1" não casa com nada.
                Intent.AbrirTransmissao(
                    "guarnicao " + ABRIR_TRANSMISSAO.matchEntire(texto)!!.groupValues[2].trim(),
                )

            matches(texto, TROCAR_DE_GRUPO) ->
                extrairRotuloDeGrupo(texto)?.let { Intent.TrocarDeGrupo(it) }
                    ?: Intent.NaoReconhecida(transcricao)

            // NARRAR antes do léxico: "narrar ocorrência: abordagem na rua X" é
            // ditado para o boletim, não alerta. O verbo explícito do agente
            // vence a classificação automática — ele disse o que queria.
            matches(texto, NARRAR) ->
                Intent.NarrarOcorrencia(texto = transcricao.trim())

            // C3 antes de PEDIR_APOIO: "tiroteio na Rui Barbosa, manda apoio" é
            // um alerta de ocorrência, não um pedido genérico. A diferença é
            // operacional — o alerta carrega tipo, prioridade e local, e o
            // fan-out por raio depende deles. Só entra aqui se o léxico
            // determinístico reconhecer o tipo; senão, segue o fluxo antigo.
            ocorrencia != null -> Intent.AlertarOcorrencia(ocorrencia)

            matches(texto, PEDIR_APOIO) ->
                Intent.PedirApoio(prioridade = prioridadeDe(texto), resumo = null)

            matches(texto, CONSULTAR_PLACA_SOLTO) ->
                Intent.ConsultarPlaca(placa = extrairPlaca(texto))

            else -> Intent.NaoReconhecida(transcricao)
        }
    }

    /**
     * Prioridade do pedido de apoio, com a **mesma régua** do léxico de
     * ocorrências.
     *
     * Antes havia duas escalas: "policial baleado" era emergência pelo léxico e
     * prioridade normal pelo pedido de apoio. A mesma frase, dois despachos
     * diferentes, conforme o caminho que o roteador tomasse — o pior tipo de
     * inconsistência, porque é invisível até acontecer em campo.
     */
    private fun prioridadeDe(texto: String): Prioridade =
        LexicoDeOcorrencias.escalarPrioridade(Prioridade.NORMAL, texto)

    /** Extrai placa Mercosul (ABC1D23) ou padrão antigo (ABC1234), se houver. */
    private fun extrairPlaca(texto: String): String? = PlacaValidator.extrair(texto)

    /**
     * Indicativo militar após o gatilho: "onde está **Alfa Dois**".
     *
     * Até três palavras, porque indicativos reais chegam a isso ("Alfa Dois
     * Zero").
     *
     * **Pontuação e artigo saem aqui, não antes.** O comentário anterior dizia
     * que a pontuação "já saiu na normalização" — e não saía: `normalizar` só
     * tira acento e colapsa espaço. O Whisper devolve pontuação por padrão, então
     * "onde está Alfa Dois?" produzia o indicativo `"Alfa Dois?"`. A RPC casa por
     * igualdade exata, então a interrogação fazia o agente ouvir **"Alfa Dois?
     * não localizado"** — uma afirmação falsa sobre o companheiro, causada por um
     * caractere.
     */
    private fun extrairIndicativo(texto: String): String? {
        for (g in CONSULTAR_POSICAO) {
            val i = texto.indexOf(g)
            if (i < 0) continue
            val palavras = LexicoDeOcorrencias.normalizarTexto(texto.substring(i + g.length))
                .split(" ")
                .filter { it.isNotBlank() }
                // "onde está o Alfa Dois" não pede pelo par chamado "O Alfa Dois".
                .dropWhile { it in ARTIGOS }
                .take(3)
            if (palavras.isNotEmpty()) {
                return palavras.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
            }
        }
        return null
    }

    /**
     * Tira o rótulo falado de depois do verbo de troca.
     *
     * Devolve o rótulo **normalizado do jeito que o léxico compara** — o executor
     * confronta por igualdade contra `rotulo_falado`, e normalizar de formas
     * diferentes nos dois lados é como não normalizar: a comparação falha em
     * silêncio e a conclusão vira "o servidor está errado".
     *
     * Sem `take(n)`: rótulo é dado do servidor e pode ter qualquer número de
     * palavras ("guarnição três alfa", "setor centro sul"). Truncar aqui limitaria
     * o cadastro a partir do cliente.
     */
    private fun extrairRotuloDeGrupo(texto: String): String? {
        for (g in TROCAR_DE_GRUPO) {
            val i = texto.indexOf(g)
            if (i < 0) continue
            val resto = texto.substring(i + g.length)
                .split(" ")
                .filter { it.isNotBlank() }
                // "mudar para a guarnição três" não pede o grupo "a guarnição três".
                .dropWhile { it in ARTIGOS }
                .joinToString(" ")
            if (resto.isNotBlank()) return resto
        }
        return null
    }

    private fun matches(texto: String, padroes: List<String>): Boolean =
        padroes.any { texto.contains(it) }

    companion object {
        // Verbos-chave já normalizados (sem acento, minúsculo).
        // Só gatilhos de pânico explícito. "tiros" e "homem caído" saíram daqui
        // de propósito: o léxico os classifica com tipo, prioridade E logradouro,
        // que é estritamente mais informativo para quem recebe o alerta — mesma
        // urgência, mais contexto.
        // "socorro" saiu daqui e ficou só como modificador de escalada no léxico.
        // Aqui, "tiroteio na Rui Barbosa, socorro" virava `Emergencia` genérica e
        // o despacho saía com "Emergência acionada" e sem endereço — perdendo
        // exatamente o que o léxico tinha acabado de extrair.
        val EMERGENCIA = listOf("emergencia", "codigo vermelho")
        val PEDIR_APOIO = listOf("apoio", "reforco", "reforcar", "solicitar apoio", "preciso de apoio")
        val INICIAR_GRAVACAO = listOf("gravar", "iniciar gravacao", "comecar gravacao", "registrar video")
        val ENCERRAR_GRAVACAO = listOf("encerrar gravacao", "parar gravacao", "parar de gravar", "finalizar gravacao")
        // Verbo + objeto: intenção inequívoca de consulta.
        val CONSULTAR_PLACA_EXPLICITO =
            listOf("consultar placa", "verificar placa", "checar placa", "rodar placa")
        // Termo solto: só vale se nada mais específico casou antes.
        val CONSULTAR_PLACA_SOLTO = listOf("placa")
        val NARRAR = listOf("narrar", "ditar", "registrar ocorrencia", "anotar ocorrencia", "boletim")
        // "de novo" saiu daqui. É locução comum em rádio, não comando: "tiroteio
        // de novo na Rui Barbosa" virava `Detalhar` e o app repetia a última
        // resposta — ou dizia "Nada a repetir." — enquanto nenhum alerta saía.
        val DETALHAR = listOf("detalhar", "repetir", "repita")
        val MODO_STANDBY = listOf("modo standby", "modo espera", "modo descanso")
        val MODO_OCORRENCIA = listOf("modo ocorrencia", "modo abordagem")
        val MODO_ATIVO = listOf("modo ativo", "modo patrulha")

        val CONSULTAR_POSICAO = listOf("onde esta", "onde ta", "posicao de", "localizar", "cade a", "cade o")

        /**
         * Troca de talk group. **Verbo + preposição**, não o verbo solto: "mudar"
         * e "trocar" aparecem em fala corrente ("o suspeito mudou de rua"), e o
         * comando é a locução inteira. Ver `specs/troca-de-grupo-por-voz.spec.md`.
         */
        /**
         * *"guarnição 3 na escuta"* — a frase inteira, sem sobra.
         *
         * O rótulo capturado inclui a palavra "guarnição" porque é assim que ele
         * está no `rotulo_falado` do cadastro (migração `0011`): comparar só o
         * número contra a coluna falharia em toda linha.
         *
         * `{1,24}` no meio, e não `.*`: o rótulo é um nome curto de guarnição. Com
         * `.*` a frase "diz pro pessoal que a guarnição 3 tá na escuta" não casaria
         * por causa das âncoras, mas "guarnição 3 e a 4 e a 5 na escuta" casaria —
         * e resolveria para um rótulo que não existe, gastando uma ida ao resolvedor
         * para recusar depois.
         *
         * **A pontuação no fim não é detalhe: sem ela a feature nunca dispara.**
         * O whisper devolve "Guarnição 3 na escuta." com o ponto, e `normalizar`
         * tira acento e espaço, não pontuação — este mesmo arquivo já registra, no
         * KDoc de `extrairRotuloDeGrupo`, que supor o contrário custou um defeito.
         * Um `matchEntire` sem a classe final recusaria toda transcrição real, e o
         * sintoma seria "a frase não funciona" sem nenhum erro no caminho.
         */
        /**
         * Grafias que o whisper pt-BR produz para "guarnição".
         *
         * **"guarney sao" não é chute.** Foi o que voltou da captura de 20/08 com
         * fala humana: *"Clareon, Guarney são 1 na escuta."* O modelo parte a
         * palavra em duas porque "guarnição" é rara no corpus dele, e o erro é
         * sistemático, não aleatório.
         *
         * Cada entrada aqui precisa de uma transcrição REAL que a justifique.
         * Acrescentar variante plausível alarga o portão sem prova — e este portão
         * abre canal para a guarnição inteira.
         */
        val GRAFIAS_DE_GUARNICAO = listOf("guarnicao", "guarney sao", "guarnicoes", "guarnicão")

        /**
         * *"guarnição 3 na escuta"* — a frase inteira, sem sobra.
         *
         * A alternação no início mantém o casamento INTEGRAL: a frase tem de
         * começar por uma grafia conhecida de "guarnição" e terminar em "na
         * escuta". Trocar isso por `.*` aceitaria "diz pro pessoal que a guarnição
         * 3 na escuta" — conversa que CONTÉM o comando e não é o comando.
         *
         * O rótulo capturado é reescrito para a grafia canônica antes de ir ao
         * resolvedor, que compara contra o `rotulo_falado` do cadastro (migração
         * `0011`). Sem a reescrita, "guarney sao 1" nunca casaria com "guarnicao 1".
         *
         * **A pontuação no fim não é detalhe: sem ela a feature nunca dispara.**
         * O whisper devolve "Guarnição 3 na escuta." com o ponto, e `normalizar`
         * tira acento e espaço, não pontuação — este mesmo arquivo já registra, no
         * KDoc de `extrairRotuloDeGrupo`, que supor o contrário custou um defeito.
         */
        val ABRIR_TRANSMISSAO = Regex("^(\\S+(?: \\S+)?) (.{1,16}?) na escuta[.!?…,;\\s]*$")

        /**
         * A primeira parte da frase é mesmo "guarnição"?
         *
         * **Fonético mais lista medida, e os dois têm papel distinto.** A chave
         * cobre a variação gráfica sistemática ("guarniçam", "guarnisão",
         * "garnição"); a lista cobre o caso que a chave não alcança — "guarney sao"
         * tem uma sílaba a mais e fica a distância 3, e baixar o portão até lá
         * aceitaria "guarda" e "guarita".
         *
         * Ou seja: o que é regular vai pela regra, o que é exceção medida vai pelo
         * nome. Enumerar tudo não escala; generalizar tudo abre o portão.
         */
        fun ePalavraDeGuarnicao(t: String): Boolean =
            GRAFIAS_DE_GUARNICAO.any { it == t } || ChaveFonetica.pareceCom(t, "guarnicao", TOLERANCIA_GUARNICAO)

        /**
         * **2, e a diferença para a ativação é justificada.**
         *
         * A palavra de ativação usa tolerância ZERO porque ela sozinha decide se o
         * agente falou com o copiloto — e com 1 a frase *"clarim, guarnição 3 na
         * escuta"* abria canal (medido).
         *
         * Aqui não: para chegar neste ponto a fala JÁ passou pelo detector acústico
         * e pela conferência do gatilho. O que sobra é escolher qual guarnição, e o
         * erro final é barrado pelo léxico FECHADO do cadastro — um rótulo que não
         * existe não vira canal nenhum. Então 2 compra cobertura sem custo de
         * segurança: medido, zero falsos aceites em 58 negativos.
         */
        const val TOLERANCIA_GUARNICAO = 2

        val TROCAR_DE_GRUPO = listOf(
            "mudar para", "trocar para", "mudar pra", "trocar pra",
            "muda para", "troca para", "muda pra", "troca pra",
        )

        val ARTIGOS = setOf("o", "a", "os", "as", "do", "da", "de")

    }
}

/** Normaliza para minúsculas sem acento (para casar "gravação" com "gravacao"). */
private fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
