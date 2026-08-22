package com.claryon.agent

import java.text.Normalizer

/**
 * **O que o copiloto sabe procurar fora do aparelho. Vocabulário FECHADO.**
 *
 * Três categorias, e a lista é curta de propósito: cada entrada aqui é um termo
 * que **sai do aparelho pela rede**, e o que não está enumerado não tem como sair.
 * É o mesmo desenho do léxico de canais e do alfabeto fonético — o portão é a
 * lista, não a esperteza de quem escreve o filtro.
 *
 * @param termo o texto **exato** que vai para a fonte externa e para o registro
 *   de uso. Minúsculo, sem acento e sem dígito, porque essas três propriedades
 *   são o que [HigieneDaConsulta] consegue conferir sozinha.
 * @param falaDeAusencia a frase de "não achei". Fica no enum, e não montada por
 *   concatenação, porque português tem gênero: *"Nenhum hospital"* e *"Nenhuma
 *   delegacia"*. Um template só produziria uma das duas errada.
 */
enum class CategoriaDeLugar(
    val termo: String,
    val falaDeAusencia: String,
) {
    HOSPITAL("hospital", "Nenhum hospital por perto."),
    DELEGACIA("delegacia", "Nenhuma delegacia por perto."),
    POSTO_DE_SAUDE("posto de saude", "Nenhum posto por perto."),
}

/**
 * **Uma consulta que já passou pelo portão — e que não tem outro jeito de existir.**
 *
 * O construtor é privado e o único caminho é [de]. Não é cerimônia: é a mesma
 * garantia que `PlacaValidator` dá à placa e que `PontoDoRastro` dá ao rastro —
 * *o tipo não tem por onde carregar o que não pode sair*. Um `String` cru como
 * parâmetro da camada externa seria a porta por onde a transcrição vazaria, e
 * nenhuma revisão de código pega isso todas as vezes.
 *
 * [texto] tem **dois** consumidores e é a **mesma** string para os dois (decisão 5
 * da spec): o que a fonte externa recebe e o que o registro de uso guarda. Um
 * filtro, dois consumidores — não existe segundo lugar onde a higiene possa
 * divergir.
 */
class ConsultaHigienizada private constructor(
    val categoria: CategoriaDeLugar,
    val texto: String,
) {
    override fun toString(): String = texto

    companion object {
        /**
         * Reconstrói a consulta **a partir da intenção**, nunca da transcrição.
         *
         * Repare no parâmetro: [CategoriaDeLugar], e só. Não há sobrecarga que
         * aceite `String` — pelo mesmo motivo que `utteranceFor` não aceita
         * `Intent`. Enquanto a única entrada for o enum, é *impossível* que um
         * pedaço da fala do agente chegue à rede, e não apenas improvável.
         *
         * A autoconferência abaixo parece redundante — o termo vem de uma
         * constante. Ela existe porque o dia em que alguém acrescentar
         * `CategoriaDeLugar("rua do agente", …)` ao enum, o build quebra aqui,
         * dentro da própria fábrica, em vez de vazar em produção.
         */
        fun de(categoria: CategoriaDeLugar): ConsultaHigienizada {
            val vazou = HigieneDaConsulta.vazamentos(categoria.termo)
            check(vazou.isEmpty()) {
                "CategoriaDeLugar.${categoria.name} tem termo \"${categoria.termo}\", " +
                    "que vaza ${vazou.joinToString { it.explicacao }}. " +
                    "Termo de categoria é vocabulário fechado: sem dígito, sem nome, sem indicativo."
            }
            return ConsultaHigienizada(categoria, categoria.termo)
        }
    }
}

/**
 * **O filtro que transforma promessa de privacidade em coisa que um avaliador roda.**
 *
 * A spec (`specs/consulta-externa.spec.md` §5) diz que *a pergunta que sai não é a
 * transcrição*. Isso é sustentado em **duas camadas**, e as duas são necessárias
 * por razões diferentes:
 *
 *  1. **Construção.** [ConsultaHigienizada.de] só aceita [CategoriaDeLugar]. Nada
 *     da fala tem por onde entrar. Esta camada é a que de fato garante.
 *  2. **Inspeção.** [vazamentos] examina um texto qualquer e **nomeia** o que
 *     encontrou. Esta camada é a que de fato *prova* — ela é o instrumento com
 *     que o teste reprova a versão defeituosa (a que mandaria a transcrição) e
 *     aprova a correta. Sem ela, "não vaza" seria afirmação, não medição.
 *
 * ## Por que os detectores são generosos, e por que isso não custa nada
 *
 * Um detector que erra para o lado de acusar reprova texto legítimo — caro, se
 * ele estivesse no caminho de classificar a fala do agente. **Ele não está.** Ele
 * roda sobre a consulta *candidata*, que em produção é sempre uma constante do
 * enum. O falso positivo custa zero e o falso negativo custaria uma matrícula de
 * policial num servidor de terceiro. A régua é assimétrica de propósito, como a
 * de `ConsultaNaBaseVeicular`.
 *
 * ## O que ele NÃO faz
 *
 * Não anonimiza nem reescreve. Não existe `higienizar(texto: String): String`, e
 * a ausência é deliberada: uma função que "limpa" a transcrição convida a mandar
 * a transcrição limpa, e transcrição limpa continua sendo transcrição — a spec
 * proíbe a **literal** em §2, sem exceção. Aqui só há reconstruir ou recusar.
 */
object HigieneDaConsulta {

    /** O que foi encontrado num texto, com a explicação que vai à mensagem de falha. */
    enum class Vazamento(val explicacao: String) {
        PLACA("placa de veículo"),
        MATRICULA("matrícula, RG, CPF ou outro número de documento"),
        NOME_PROPRIO("nome próprio, ou patente que antecede nome"),
        INDICATIVO_DE_GUARNICAO("indicativo de guarnição, viatura ou alfabeto militar"),
        NUMERO_DE_ENDERECO("número — de endereço, de porta ou de qualquer coisa"),
    }

    /**
     * Tudo que [texto] carrega e não pode sair do aparelho. Vazio = pode sair.
     *
     * Devolve **conjunto**, não o primeiro achado: a mensagem de falha de um teste
     * que diz "vazou placa" quando também vazou matrícula manda quem for consertar
     * fazer meio conserto e declarar vitória.
     */
    fun vazamentos(texto: String): Set<Vazamento> {
        val achados = linkedSetOf<Vazamento>()
        val tokens = texto.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val normalizados = tokens.map(::semAcento)

        if (temPlaca(texto)) achados += Vazamento.PLACA
        if (temDocumento(texto, normalizados)) achados += Vazamento.MATRICULA
        if (temIndicativo(normalizados)) achados += Vazamento.INDICATIVO_DE_GUARNICAO
        if (temNomeProprio(tokens, normalizados)) achados += Vazamento.NOME_PROPRIO
        if (temNumero(texto)) achados += Vazamento.NUMERO_DE_ENDERECO

        return achados
    }

    /** Açúcar para leitura em asserção. */
    fun limpa(texto: String): Boolean = vazamentos(texto).isEmpty()

    // ── Detectores ────────────────────────────────────────────────────────────

    /**
     * Placa escrita **e** placa ditada — os dois caminhos, pela mesma razão que o
     * roteador usa os dois: *"tango bravo unido três delta sete zero"* é uma placa
     * tanto quanto `TBU3D70`, e um filtro que só olhasse a grafia deixaria a
     * ditada passar inteira.
     */
    private fun temPlaca(texto: String): Boolean =
        PlacaValidator.extrair(texto) != null ||
            PlacaDitada.ler(texto) is PlacaDitada.Leitura.Reconhecida

    private fun temDocumento(texto: String, normalizados: List<String>): Boolean =
        normalizados.any { it in DOCUMENTO } || CORRIDA_DE_DIGITOS.containsMatchIn(texto)

    private fun temIndicativo(normalizados: List<String>): Boolean =
        normalizados.any { t ->
            t in VIATURA ||
                t in PALAVRAS_DO_ALFABETO ||
                // A chave fonética cobre a variação gráfica que o whisper produz
                // ("guarnisão", "garnição"); a lista cobre o resto. Mesma divisão
                // de trabalho que `ePalavraDeGuarnicao` no roteador.
                (t.length >= 6 && ChaveFonetica.pareceCom(t, "guarnicao", 2))
        }

    /**
     * Nome próprio por **dois** sinais independentes, porque os dois casos reais
     * são diferentes:
     *
     *  - a transcrição **crua** do whisper vem com caixa — *"na Rui Barbosa"* —, e
     *    a maiúscula fora de início de frase é a evidência;
     *  - a transcrição **normalizada** pelo roteador chega sem caixa nenhuma, e aí
     *    o que sobra é a patente: *"sgt paiva"*, *"cabo silva"*.
     *
     * Só a caixa deixaria passar todo o caminho normalizado; só a patente deixaria
     * passar *"Rui Barbosa"*, que não tem patente e é o exemplo da própria spec.
     */
    private fun temNomeProprio(tokens: List<String>, normalizados: List<String>): Boolean {
        if (normalizados.any { it in PATENTES }) return true
        for (i in tokens.indices) {
            // A primeira palavra é maiúscula por gramática, não por ser nome — e é
            // onde o gatilho "Claryon" cai. As demais posições respondem pela
            // lista de palavras comuns, logo abaixo; a defesa real continua sendo o
            // vocabulário fechado, não esta heurística.
            if (i == 0) continue
            val t = tokens[i]
            if (t.length < 3) continue
            if (!t[0].isUpperCase()) continue
            if (t.drop(1).any { it.isUpperCase() }) continue // sigla, tratada em outro detector
            if (semAcento(t) in PALAVRAS_COMUNS_CAPITALIZAVEIS) continue
            return true
        }
        return false
    }

    /**
     * **Qualquer dígito.** Não é exagero — é a regra da spec §5 dita como código:
     * *"a categoria pode sair, o número não"*.
     *
     * O termo de uma categoria nunca tem número. Então distinguir "250 da Rua Rui
     * Barbosa" de "998877 de matrícula" seria trabalho para separar dois casos que
     * têm exatamente o mesmo veredito, e cada distinção a mais é uma borda a mais
     * por onde algo passa.
     */
    private fun temNumero(texto: String): Boolean = texto.any { it.isDigit() }

    // ── Léxicos ───────────────────────────────────────────────────────────────

    private fun semAcento(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Quatro dígitos seguidos: matrícula, RG, CPF, protocolo. Nunca uma categoria. */
    private val CORRIDA_DE_DIGITOS = Regex("\\d{4,}")

    private val DOCUMENTO = setOf(
        "matricula", "cpf", "rg", "identidade", "funcional", "registro", "protocolo", "cnh",
    )

    private val PATENTES = setOf(
        "sgt", "sargento", "cb", "cabo", "sd", "soldado", "ten", "tenente",
        "cap", "capitao", "maj", "major", "cel", "coronel", "subtenente", "st",
        "delegado", "delegada", "inspetor", "investigador", "comandante", "aspirante",
    )

    private val VIATURA = setOf(
        "viatura", "vtr", "prefixo", "guarnicao", "guarnicoes", "gta", "qra", "qtr",
    )

    /**
     * As palavras do alfabeto fonético, tiradas da **tabela**, não escritas à mão.
     *
     * `AlfabetoFonetico.letra` não serve aqui: ela aceita letra sozinha por regra
     * ("a", "e", "o" valem a si mesmas), e "de" da categoria *posto de saúde*
     * cairia perto demais dessa regra. O que interessa é a palavra do código —
     * "alfa", "bravo", "tango" —, que é o que forma indicativo.
     */
    private val PALAVRAS_DO_ALFABETO: Set<String> =
        ('A'..'Z').flatMap { AlfabetoFonetico.palavrasDe(it) }.toSet()

    /**
     * Palavras que aparecem capitalizadas sem serem nome de gente ou de rua.
     *
     * Lista curta e ela **não** é a defesa: a defesa é o vocabulário fechado. Isto
     * existe só para o detector não acusar a própria pergunta do agente quando ele
     * for usado para diagnóstico.
     */
    private val PALAVRAS_COMUNS_CAPITALIZAVEIS = setOf(
        "hospital", "delegacia", "posto", "saude", "claryon", "onde", "qual", "quando",
    )
}
