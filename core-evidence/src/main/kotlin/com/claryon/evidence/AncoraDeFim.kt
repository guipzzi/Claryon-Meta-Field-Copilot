package com.claryon.evidence

import java.security.MessageDigest

/**
 * Âncora de fim: a prova de que a gravação **terminou onde o manifesto diz**.
 *
 * ## O ataque que esta classe existe para pegar
 *
 * A cadeia de hash de [HashChain] detecta **alteração** e é cega a **remoção no
 * fim**. O ataque não precisa de chave nenhuma: apagar `seg_00003.enc` e
 * `seg_00004.enc`, apagar as duas últimas linhas `S` do manifesto, e o que sobra é
 * aritmeticamente perfeito — cada elo ancora no anterior. A conferência responderia
 * [Integridade.Integra] sobre uma ocorrência da qual o **fim** foi removido.
 *
 * Isso importa porque quem quer esconder alguma coisa numa gravação de rádio
 * raramente edita o meio: apaga o final. Uma custódia que sustenta "auditável pela
 * corregedoria" e não distingue "acabou aqui" de "cortaram aqui" não sustenta o
 * argumento.
 *
 * ## O que a âncora é
 *
 * Uma linha `A` no fim do manifesto, com **HMAC-SHA256** sobre a declaração
 * canônica de como a gravação terminou: quantos segmentos, qual o último hash, com
 * que taxa e formato, quando e por quê parou, e o que a política de retenção
 * apagou. A chave é gerada no **Android Keystore** e nunca sai de lá — o disco não
 * a contém, então quem só tem acesso de escrita ao diretório não consegue produzir
 * uma âncora nova para a cadeia encurtada.
 *
 * Como `hash_i = SHA256(hash_{i-1} ‖ bytes_i)`, o par (quantidade, último hash)
 * compromete **toda** a sequência: não é preciso assinar segmento por segmento.
 * Um HMAC por gravação, sobre ~200 bytes.
 *
 * ## Por que a âncora declara e depois é comparada
 *
 * O MAC cobre os números que a **própria âncora** afirma, não os que o manifesto
 * apresenta. A comparação vem depois. É o que permite responder
 * [Integridade.Truncada] com os dois números — "foram selados 5, o manifesto
 * apresenta 3" — em vez do genérico "MAC não confere", que é verdadeiro e inútil
 * para quem periciar.
 *
 * ## O que ela NÃO fecha — leia antes de citar em relatório
 *
 * A chave vive no Keystore do aparelho e é usável **pelo próprio app**. Quem
 * conseguir executar código como o app (aparelho com root mais injeção, ou uma
 * compilação adulterada) sela uma âncora válida para qualquer cadeia que queira.
 * A âncora move a barra de "qualquer acesso de escrita ao diretório trunca sem
 * deixar rastro" para "é preciso executar como o app" — e não além disso.
 *
 * Fechar o resto exige âncora **externa**: publicar (n, último hash) num servidor
 * ou HSM da corregedoria, que o aparelho não pode reescrever. Não está construído.
 *
 * Também **não foi medido** se a chave é respaldada por hardware neste ou em
 * qualquer aparelho: `setIsStrongBoxBacked` não é pedido e `getSecurityLevel` não é
 * consultado. No emulador o keymint é software, então medir lá não responderia nada.
 */
object AncoraDeFim {

    /** Algoritmo do MAC. Conferido em `javax.crypto.Mac` do `android.jar` da API 35. */
    const val ALGORITMO = "HmacSHA256"

    /**
     * Separa este uso de qualquer outro MAC que o app venha a ter com a mesma
     * chave. Muda junto com o formato da mensagem — nunca sozinho.
     */
    const val DOMINIO = "claryon/ancora-de-fim/v1"

    /** Primeira versão de manifesto que **exige** âncora. Ver [Manifesto]. */
    const val VERSAO_COM_ANCORA = 3

    /**
     * Assina com uma chave que o disco não contém.
     *
     * Existe como interface por dois motivos: mantém a lógica desta classe pura e
     * testável em JVM (o Keystore não roda fora do aparelho) e deixa o teste
     * instrumentado forjar uma âncora com chave própria, que é como se prova que
     * uma âncora forjada é **recusada**.
     */
    fun interface Assinador {
        /** @return o MAC de [mensagem], ou `null` se a chave não está disponível. */
        fun mac(mensagem: ByteArray): ByteArray?
    }

    /**
     * O que a linha `A` carrega.
     *
     * @param segmentos quantos segmentos a gravação tinha quando foi selada.
     * @param ultimoHashHex hash do último segmento — o que compromete a cadeia toda.
     * @param macHex HMAC-SHA256 da declaração canônica, em hexadecimal.
     */
    data class Ancora(
        val segmentos: Int,
        val ultimoHashHex: String?,
        val macHex: String,
    )

    /** Tudo o que entra no MAC. Um objeto para que selar e conferir não divirjam. */
    data class Declaracao(
        val handleId: String,
        val versao: Int,
        val sampleRateHz: Int,
        val formato: String,
        val janelaMs: Int,
        val fimEpochMillis: Long,
        val motivoDoFim: String?,
        val purgados: List<Purga>,
    )

    /**
     * Sela a âncora. `null` quando a chave não está disponível — e nesse caso o
     * manifesto sai **sem** linha `A`, o que [conferir] reporta como
     * [Integridade.SemAncoraDeFim]. Fingir uma âncora que não foi assinada seria o
     * defeito que este arquivo existe para impedir.
     */
    fun selar(
        assinador: Assinador,
        declaracao: Declaracao,
        segmentos: Int,
        ultimoHashHex: String?,
    ): Ancora? {
        val mac = assinador.mac(mensagem(declaracao, segmentos, ultimoHashHex)) ?: return null
        return Ancora(segmentos, ultimoHashHex, paraHex(mac))
    }

    /**
     * Confere a âncora contra o manifesto apresentado.
     *
     * @return `null` **somente** quando a âncora é autêntica e cobre exatamente a
     *   cadeia apresentada — o único caso em que o veredito pode chegar a
     *   [Integridade.Integra]. Qualquer outro caso devolve o estado que o explica.
     */
    fun conferir(
        assinador: Assinador,
        declaracao: Declaracao,
        cadeia: List<ChunkHash>,
        finalizada: Boolean,
        ancora: Ancora?,
    ): Integridade? {
        val faltou = Integridade::SemAncoraDeFim
        if (declaracao.versao < VERSAO_COM_ANCORA) {
            // Rebaixar `versao=` é um caminho para escapar da exigência de âncora.
            // Ele não leva a `Integra`: leva aqui, que também não é íntegra.
            return faltou(Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR)
        }
        if (!finalizada) return faltou(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA)
        if (ancora == null) return faltou(Integridade.SemAncoraDeFim.Motivo.AUSENTE)

        val esperado = assinador.mac(mensagem(declaracao, ancora.segmentos, ancora.ultimoHashHex))
            ?: return faltou(Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL)
        val apresentado = deHex(ancora.macHex)
            ?: return faltou(Integridade.SemAncoraDeFim.Motivo.INVALIDA)
        // `isEqual` é a comparação de tempo constante do JDK; `==` de String
        // curto-circuita no primeiro byte diferente.
        if (!MessageDigest.isEqual(esperado, apresentado)) {
            return faltou(Integridade.SemAncoraDeFim.Motivo.INVALIDA)
        }

        // Daqui para baixo a âncora é autêntica, e passa a ser a régua.
        if (ancora.segmentos != cadeia.size) {
            return Integridade.Truncada(ancora.segmentos, cadeia.size)
        }
        if (ancora.ultimoHashHex != cadeia.lastOrNull()?.sha256Hex) {
            return Integridade.Quebrada(maxOf(0, cadeia.size - 1))
        }
        return null
    }

    /**
     * Serialização canônica do que é assinado.
     *
     * **Todo** campo entra prefixado pelo seu tamanho em bytes UTF-8, inclusive os
     * de dentro da lista de purgas. `handleId` (montado a partir de `unitId` e
     * `agentId`) e os `motivo` vêm do chamador e podem conter qualquer caractere;
     * sem o prefixo, mover o separador produziria duas gravações **diferentes** com
     * a mesma mensagem assinada.
     *
     * O caso concreto que reprovou a primeira versão desta função: com as purgas
     * serializadas como `seq,millis,motivo` unidas por `;`, uma purga cujo motivo
     * fosse `A;3,4,B` gerava exatamente a mesma cadeia de caracteres que **duas**
     * purgas. Uma âncora selada sobre uma valeria para a outra.
     *
     * [Declaracao.sampleRateHz] e [Declaracao.formato] entram porque reescrever
     * `taxaHz=16000` para `taxaHz=8000` não apaga nada e ainda assim adultera a
     * prova: os mesmos bytes reproduzidos na metade da taxa são outra gravação.
     */
    fun mensagem(declaracao: Declaracao, segmentos: Int, ultimoHashHex: String?): ByteArray {
        val campos = buildList {
            add(DOMINIO)
            add(declaracao.handleId)
            add(declaracao.versao.toString())
            add(declaracao.sampleRateHz.toString())
            add(declaracao.formato)
            add(declaracao.janelaMs.toString())
            add(declaracao.fimEpochMillis.toString())
            add(declaracao.motivoDoFim ?: "")
            add(segmentos.toString())
            add(ultimoHashHex ?: "")
            // A contagem antes dos itens: sem ela, campos de uma purga poderiam ser
            // relidos como campos de outra. Ordenado porque a mesma purga lida em
            // outra ordem daria outro MAC. Incluído porque uma linha `P` forjada
            // explicaria, sozinha, um segmento removido.
            val purgados = declaracao.purgados.sortedBy { it.sequence }
            add(purgados.size.toString())
            for (p in purgados) {
                add(p.sequence.toString())
                add(p.epochMillis.toString())
                add(p.motivo)
            }
        }
        val sb = StringBuilder()
        for (c in campos) sb.append(c.toByteArray(Charsets.UTF_8).size).append(':').append(c)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun paraHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun deHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val alto = Character.digit(hex[i * 2], 16)
            val baixo = Character.digit(hex[i * 2 + 1], 16)
            if (alto < 0 || baixo < 0) return null
            out[i] = ((alto shl 4) or baixo).toByte()
        }
        return out
    }
}
