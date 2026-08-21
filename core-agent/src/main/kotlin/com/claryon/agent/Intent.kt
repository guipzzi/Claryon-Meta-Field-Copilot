package com.claryon.agent

/** Prioridade de um pedido de apoio. Nível 1 (emergência) interrompe tudo. */
enum class Prioridade { EMERGENCIA, ALTA, NORMAL }

/** Modos de operação — trocáveis por voz ou toque. Governam energia e câmera. */
enum class ModoOperacao { STANDBY, ATIVO, OCORRENCIA }

/**
 * Modelo fechado de intenções operacionais.
 *
 * Toda saída do reconhecimento de voz cai em UMA destas variantes — não há
 * "talvez". Intenção não reconhecida vira [NaoReconhecida] e responde com
 * earcon de falha + pedido curto de repetição; o sistema NUNCA age por
 * adivinhação (contexto de segurança pública).
 */
sealed interface Intent {

    /** Pedir apoio à guarnição. `resumo` é opcional (≤120 caracteres no template). */
    data class PedirApoio(val prioridade: Prioridade, val resumo: String?) : Intent

    /** Iniciar gravação de evidência. `motivo` opcional. */
    data class IniciarGravacao(val motivo: String?) : Intent

    /** Encerrar a gravação em curso. */
    data object EncerrarGravacao : Intent

    /** Consultar placa. `placa == null` ⇒ ler pela câmera (OCR). */
    data class ConsultarPlaca(val placa: String?) : Intent

    /** Narrar/ditar a ocorrência para o boletim preliminar. */
    data class NarrarOcorrencia(val texto: String) : Intent

    /** Emergência: prioridade máxima, interrompe qualquer coisa. */
    data object Emergencia : Intent

    /** Repetir o último resultado por voz (usado quando o agente já se afastou). */
    data object Detalhar : Intent

    /** Trocar o modo de operação. */
    data class TrocarModo(val modo: ModoOperacao) : Intent

    /**
     * Trocar de talk group pelo **rótulo falado** — "guarnição três", não o UUID.
     *
     * O rótulo é dado, não vocabulário do código: vem de `rotulo_falado` no
     * servidor, por agente (migração `0011`). Um município que chame suas unidades
     * de "viatura" ou "setor" funciona sem recompilar — e é por isso que a coluna
     * existe no banco em vez de um `enum` aqui.
     *
     * Carrega o rótulo **bruto**, como saiu do roteador. Quem normaliza e resolve
     * é o executor, com a mesma função usada no lado do servidor — normalizar aqui
     * faria a `Intent` mentir sobre o que o agente disse, e é a transcrição bruta
     * que o diagnóstico precisa.
     *
     * Ver `specs/troca-de-grupo-por-voz.spec.md`.
     */
    data class TrocarDeGrupo(val rotuloFalado: String) : Intent

    /**
     * **Abrir transmissão por voz** — *"guarnição 3 na escuta"*.
     *
     * Duas propriedades do desenho são invariantes, não preferências (D1):
     *
     * 1. **"guarnição N na escuta" ≠ "na escuta".** O número torna a frase
     *    específica e a tira do vocabulário corrente de rádio policial, onde "na
     *    escuta" é dito o tempo todo. Sem ele, o produto abriria canal ouvindo o
     *    próprio tráfego da guarnição.
     *
     * 2. **O casamento é INTEGRAL.** A frase inteira, e nada mais. Qualquer palavra
     *    extra recusa, porque "…na escuta, câmbio" e "diz pro pessoal da guarnição
     *    3 na escuta" não são comandos — são conversa que contém o comando.
     *
     * E a que mais importa: **o detector acústico NUNCA abre canal.** Ele antecipa
     * o earcon; quem abre é esta intenção, vinda da transcrição íntegra contra
     * léxico fechado, com o grupo resolvido e o piso concedido. Com o detector
     * desligado o sistema continua correto e perde só a sensação de resposta
     * imediata; com ele decidindo sozinho, o produto passa a difundir para a
     * guarnição inteira com tráfego de rádio ambiente.
     */
    data class AbrirTransmissao(val rotuloFalado: String) : Intent

    /**
     * Consultar a posição de um par pelo indicativo (C2).
     * A resposta sai como distância, rumo e estado — nunca coordenadas.
     */
    data class ConsultarPosicao(val indicativo: String) : Intent

    /**
     * Alertar uma ocorrência (C3). A [Ocorrencia] já vem classificada pelo
     * léxico determinístico — o executor não reclassifica, só despacha.
     */
    data class AlertarOcorrencia(val ocorrencia: Ocorrencia) : Intent

    /**
     * **Consultar a norma (Fase 4, Etapa A): pergunta entra, artigo de lei sai.**
     *
     * *"Hey Claryon, posso apreender moto sem placa?"* — o copiloto recupera o
     * trecho de lei e devolve a **citação**, não uma opinião.
     *
     * ## Três coisas que esta intenção deliberadamente NÃO faz
     *
     * **Não carrega o trecho.** `core-agent` não conhece `core-knowledge` e não
     * pode: a fronteira entre os módulos é o que garante que texto recuperado não
     * vire ação, e ela é testada dos dois lados (`FronteiraDoConhecimentoTest` lá,
     * `FronteiraDoConhecimentoEmAppTest` aqui). O que viaja daqui para o executor é
     * a **pergunta**; o que volta é `ActionOutcome.NormaEncontrada`, com citação e
     * norma em `String` pura. Quem conhece os dois lados é `app`, e só ele.
     *
     * **Não decide se sabe.** O limiar mora em `PortaDoConhecimento`, do outro lado.
     * Daqui não dá para saber se a resposta existe — e é bom que não dê, porque a
     * recusa (`NormaNaoEncontrada`) tem de ser tão real quanto o acerto.
     *
     * **Não é atalho para o LLM.** Na Etapa B um modelo vai reescrever o trecho em
     * fala corrida, mas essa camada fica **por cima** deste caminho, atrás de flag,
     * e a saída dela nunca reentra como `Intent`. A regra dura do `CLAUDE.md` §2 —
     * "LLM escolhendo ação" é proibido — se sustenta porque não existe construtor de
     * `Intent` alcançável a partir de texto gerado.
     */
    data class ConsultarNorma(val pergunta: String) : Intent

    /** Nada reconhecido — carrega a transcrição bruta para diagnóstico. */
    data class NaoReconhecida(val transcricao: String) : Intent
}
