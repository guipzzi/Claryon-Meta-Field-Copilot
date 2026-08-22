package com.claryon.net

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * **O que se pode procurar lá fora. Enum, e não `String` — a ausência é a garantia.**
 *
 * Espelha `CategoriaDeLugar` de `core-agent`, que este módulo não pode enxergar
 * (os `core-*` só dependem de `core-common`). É o mesmo espelhamento declarado de
 * `ClasseDeRestricao` × `Restricao`, e quem costura os dois lados é `app`, num
 * arquivo só, auditável de olho.
 *
 * **Não existe parâmetro de texto livre em [ConsultaGeoespacial].** Essa é a razão
 * de este enum existir em vez de um `String tag`: enquanto a única entrada for uma
 * constante daqui, nenhum pedaço da transcrição do agente tem por onde chegar a um
 * servidor de terceiro. A promessa do §5 da spec vira propriedade da assinatura.
 *
 * @param filtroOsm o filtro Overpass, literal e constante. Os valores são tags do
 *   OpenStreetMap: `amenity=hospital`, `amenity=police`, e clínica/consultório
 *   para o posto de saúde — que no Brasil aparece nos dois.
 */
enum class LugarProcurado(val filtroOsm: String) {
    HOSPITAL("[\"amenity\"=\"hospital\"]"),
    DELEGACIA("[\"amenity\"=\"police\"]"),
    POSTO_DE_SAUDE("[\"amenity\"~\"^(clinic|doctors)$\"]"),
}

/**
 * **De onde veio a resposta — e ela acompanha TODA resposta que a fonte deu.**
 *
 * Aceite da spec §6: *"registrar a procedência — serviço, trecho e carimbo de
 * tempo — em **toda** resposta de fonte externa"*. Tipo próprio, e não cinco
 * campos repetidos em cada desfecho, porque foi assim que a base veicular
 * garantiu o mesmo: [Procedencia] em toda resposta **inclusive na que não acha
 * nada**, que é justamente onde não há linha de resultado para carregar o flag —
 * e é por isso que a versão anterior deste arquivo tinha `NadaPorPerto` como
 * `data object`, sem procedência nenhuma. O defeito é o mesmo, e o conserto
 * também.
 *
 * Nome distinto do dela de propósito: [Procedencia] responde *qual base* (oficial
 * ou demonstração) e é falada; esta responde *de onde veio este dado* e **não** é
 * falada. Reusar o nome faria duas regras diferentes parecerem a mesma.
 *
 * Ela viaja no tipo em vez de ficar num `Log` do lado de lá: um campo que existe
 * é um campo que o teste consegue inspecionar; uma linha de log some na primeira
 * refatoração.
 *
 * @param servico o endereço consultado — a "URL ou serviço de origem" do §4.
 * @param consultaEmitida **o que de fato saiu pela rede**, byte a byte. É o campo
 *   que torna a promessa do §5 auditável depois do fato, e não só no teste: se um
 *   dia alguém contestar que a transcrição vazou, a prova está aqui, guardada no
 *   aparelho do próprio agente.
 * @param trecho o **trecho exato** que fundamentou a resposta: o elemento OSM
 *   como veio, reduzido ao que sustenta o nome e a distância. Não é o corpo
 *   inteiro (que pode ter centenas de elementos) nem um resumo escrito por nós —
 *   é o dado que produziu a fala. Vazio quando a fonte não achou nada, que é o
 *   fato honesto: não houve trecho.
 * @param carimboMillis quando a consulta foi feita, **com precisão**. Este é o
 *   registro de AUDITORIA, do próprio agente, no aparelho. O registro de
 *   estatística de uso é OUTRO e tem granularidade de dia; ver `RegistroDeUso` e
 *   `RegistroDeAuditoria` em `core-agent`.
 */
data class ProcedenciaExterna(
    val servico: String,
    val consultaEmitida: String,
    val trecho: String,
    val carimboMillis: Long,
    val duracaoMs: Long,
)

/**
 * O que a fonte externa devolveu, **com a procedência junto**.
 *
 * Quatro desfechos e nenhum colapso, pela mesma razão de `BuscaDePar`: *nada por
 * perto* é o sistema funcionando; *sem rede* e *prazo estourado* são falhas com
 * recuperações opostas.
 *
 * Repare em quais carregam [ProcedenciaExterna] e quais não: as duas em que **a fonte
 * respondeu**. Sem rede e prazo estourado não têm procedência porque não houve
 * resposta de fonte nenhuma — inventar um serviço e um carimbo ali seria
 * registrar uma consulta que não aconteceu.
 */
sealed interface RespostaGeoespacial {

    data class Encontrado(
        val nome: String,
        val distanciaM: Int,
        val procedencia: ProcedenciaExterna,
    ) : RespostaGeoespacial

    /**
     * Teve rede, a fonte respondeu, e não há nada no raio. Não é falha — e
     * **carrega procedência**, porque é resposta.
     */
    data class NadaPorPerto(val procedencia: ProcedenciaExterna) : RespostaGeoespacial

    /** Sem rede, DNS fora, servidor recusando. O caso normal em campo. */
    data object SemRede : RespostaGeoespacial

    /** Passou de [ConsultaGeoespacial.prazoMs]. **Recusa, não espera.** */
    data object PrazoEstourado : RespostaGeoespacial
}

/**
 * **A porta pela qual `app` alcança a fonte externa — e ela só aceita enum e números.**
 *
 * Existe para uma razão de teste e uma de desenho, e as duas importam:
 *
 *  - **desenho:** a assinatura é o contrato de privacidade. Um `String` aqui seria
 *    a porta por onde a transcrição sairia, e nenhuma revisão de código pega isso
 *    todas as vezes. Com [LugarProcurado] e três números, não há por onde;
 *  - **teste:** o `app` costura `CategoriaDeLugar → LugarProcurado` num arquivo só,
 *    e o teste dessa costura precisa capturar **o que atravessou a fronteira** sem
 *    subir um servidor HTTP dentro de `app` (que não tem `org.json` de verdade no
 *    classpath de teste). Uma implementação de mentira captura os argumentos; o
 *    teste de `core-net` prova o que esses argumentos viram no fio.
 */
interface FonteGeoespacial {
    /**
     * Repare que **o raio não é parâmetro**. Ele é regra de produto (3 km — ver
     * `ConsultaGeoespacial.RAIO_PADRAO_M`), não escolha de quem chama: um raio por
     * chamada seria um botão para "tentar de novo mais longe", e a resposta certa
     * para *nada num raio operacional* é dizer que não há, não alargar até achar
     * qualquer coisa.
     */
    suspend fun maisProximo(
        lugar: LugarProcurado,
        latitude: Double,
        longitude: Double,
    ): RespostaGeoespacial
}

/**
 * **Um `OkHttpClient` por processo — e o timeout é explícito.**
 *
 * `HistoricoDoCanal` já registra o argumento e ele vale igual aqui: cada
 * `OkHttpClient()` novo traz `ConnectionPool` e `Dispatcher` próprios, e o pool
 * segura socket ocioso por cinco minutos num app que roda em serviço de primeiro
 * plano o turno inteiro. O cliente de lá é privado ao arquivo dele; unificar os
 * dois num ponto compartilhado do módulo é conserto legítimo e fica anotado.
 *
 * **Sem timeout explícito, o OkHttp usa zero — que significa "nunca desiste".**
 * Este projeto já tem esse defeito em outros pontos; aqui ele seria fatal, porque
 * o prazo de 2 s é a regra de produto, não uma otimização.
 */
private val CLIENTE_COMPARTILHADO: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
}

/**
 * **O segundo degrau da cascata: geoespacial estruturada, sem chave e sem modelo.**
 *
 * `specs/consulta-externa.spec.md` §3 — local primeiro; se o local não responde e
 * a pergunta é geoespacial, vem aqui; se aqui não responde, recusa falada com
 * motivo, **exatamente como hoje**.
 *
 * ## Por que estruturada, e não um modelo de linguagem
 *
 * *"Hospital mais próximo"* tem resposta exata: nome e distância. Passá-la por um
 * modelo troca dado por prosa e abre espaço para endereço alucinado. É como os
 * assistentes grandes fazem — o modelo reconhece a intenção e **chama a
 * ferramenta**; ele formata, não sabe. Aqui não há sequer o formatador: a fala sai
 * de `FalaDeLugar`, determinística, com o nome vindo do dado byte a byte.
 *
 * ## Por que sem chave
 *
 * Decisão 2 da spec. OSM/Overpass é aberto. Busca textual exige chave, e **chave em
 * APK é chave vazada**; intermediar pelo Supabase acrescentaria um salto e uma
 * dependência na hora da demonstração.
 *
 * ## O que sai daqui pela rede, e só isso
 *
 * Um filtro constante do enum, um raio, e um centro **arredondado a quatro casas
 * decimais** (~11 m — abaixo do erro típico do GPS de celular, portanto sem custo
 * de precisão na resposta, e estritamente menos que a correção crua). Não sai
 * transcrição, nem placa, nem matrícula, nem indicativo, nem identificador de
 * agente, nem token de sessão — repare que esta classe **não recebe** o token, ao
 * contrário de todo o resto de `core-net`. Um serviço de terceiro não tem por que
 * saber quem é o agente, e o jeito de garantir isso é ele não estar no construtor.
 *
 * ## Sem geoespacial offline na v1
 *
 * Decisão 4 da spec: os dados da região no APK fariam esta camada funcionar sem
 * rede, e custam tamanho num APK que já tem 384 MB. Fica registrado como o primeiro
 * candidato a v2 — é o que faria a camada sobreviver à região sem sinal que o
 * relato da PMERJ descreve.
 *
 * @param prazoMs teto da consulta inteira, DNS incluído. Padrão 2 000 ms
 *   (decisão 1). Estourar é [RespostaGeoespacial.PrazoEstourado], que vira recusa
 *   falada — **nunca** espera silenciosa: num produto sem display, calado é
 *   indistinguível de morto.
 */
class ConsultaGeoespacial(
    private val endpoint: String = OVERPASS,
    private val client: OkHttpClient = CLIENTE_COMPARTILHADO,
    val prazoMs: Long = PRAZO_PADRAO_MS,
    private val raioM: Int = RAIO_PADRAO_M,
    private val agora: () -> Long = { System.currentTimeMillis() },
) : FonteGeoespacial {

    /**
     * O [lugar] mais próximo de ([latitude], [longitude]) dentro de [raioM].
     *
     * **O "mais próximo" é calculado aqui, não pedido ao servidor.** O Overpass não
     * ordena por distância; pedir "o primeiro" devolveria um qualquer do raio. A
     * conta é uma harversine sobre o que voltou, e ela é barata — dezenas de
     * elementos, não milhares.
     */
    override suspend fun maisProximo(
        lugar: LugarProcurado,
        latitude: Double,
        longitude: Double,
    ): RespostaGeoespacial = withContext(Dispatchers.IO) {
        val lat = arredondar(latitude)
        val lon = arredondar(longitude)
        val consulta = montar(lugar, lat, lon, raioM)

        val req = Request.Builder()
            .url(endpoint)
            // `data=` como parâmetro de formulário: é o que a doc oficial da
            // Overpass API mostra no quickstart. Corpo cru também funciona em
            // alguns espelhos; escrever o que está documentado é o que o
            // `CLAUDE.md` §2 pede para assinatura de API de terceiro.
            .post(FormBody.Builder().add("data", consulta).build())
            .build()

        val t0 = agora()
        // `callTimeout` cobre a chamada INTEIRA — resolução de DNS, conexão,
        // envio, leitura e redirecionamento. `readTimeout` sozinho não cobriria o
        // caso que mais acontece em campo, que é o DNS pendurado sem rede.
        val comPrazo = client.newBuilder()
            .callTimeout(prazoMs, TimeUnit.MILLISECONDS)
            .build()

        val corpo = try {
            comPrazo.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext RespostaGeoespacial.SemRede
                resp.body?.string().orEmpty()
            }
        } catch (e: InterruptedIOException) {
            // `SocketTimeoutException` herda daqui, e `callTimeout` estourado
            // chega como `InterruptedIOException("timeout")`. Prazo é prazo.
            return@withContext RespostaGeoespacial.PrazoEstourado
        } catch (e: IOException) {
            // Sem rede, DNS fora, TLS recusado. Recuperação diferente do prazo:
            // aqui o agente anda até pegar sinal; lá ele repete o comando.
            return@withContext RespostaGeoespacial.SemRede
        }

        interpretar(corpo, consulta, lat, lon, agora() - t0)
    }

    /**
     * A consulta Overpass QL — **montada só de constantes e de números**.
     *
     * Nenhum argumento desta função vem da fala. [lugar] é um enum, o raio é um
     * `Int` e o centro é um par de `Double` já arredondado. É isso que
     * `ConsultaGeoespacialTest` inspeciona: o corpo que sai não contém placa, nome,
     * matrícula, indicativo nem número de endereço, porque não há por onde eles
     * entrarem.
     *
     * `[timeout:2]` diz ao servidor o mesmo prazo que o cliente aplica: sem isso o
     * Overpass pode gastar minutos numa consulta que já foi abandonada aqui, e
     * gastar recurso de um serviço público de graça é má vizinhança.
     *
     * `out center` acrescenta o centro da caixa envolvente a `way` e `relation` —
     * hospital costuma ser polígono, não ponto, e sem isso a maioria dos resultados
     * viria sem coordenada. A doc oficial descreve o modificador; **o formato exato
     * do campo em JSON ela não confirma**, então [coordenadaDe] tenta as três
     * formas documentadas em vez de apostar numa.
     */
    internal fun montar(lugar: LugarProcurado, lat: String, lon: String, raioM: Int): String =
        "[out:json][timeout:2];" +
            "(" +
            "node(around:$raioM,$lat,$lon)${lugar.filtroOsm};" +
            "way(around:$raioM,$lat,$lon)${lugar.filtroOsm};" +
            "relation(around:$raioM,$lat,$lon)${lugar.filtroOsm};" +
            ");" +
            "out center;"

    private fun interpretar(
        corpo: String,
        consultaEmitida: String,
        latConsulta: String,
        lonConsulta: String,
        duracaoMs: Long,
    ): RespostaGeoespacial {
        val elementos = runCatching { JSONObject(corpo).optJSONArray("elements") }.getOrNull()
            ?: return RespostaGeoespacial.SemRede
        val lat0 = latConsulta.toDouble()
        val lon0 = lonConsulta.toDouble()

        /**
         * A procedência de QUALQUER desfecho em que a fonte respondeu — inclusive
         * o "não achei". Fechada aqui, uma vez, para que não exista o caminho em
         * que alguém a monta num ramo e esquece no outro.
         */
        fun procedencia(trecho: String) = ProcedenciaExterna(
            servico = endpoint,
            consultaEmitida = consultaEmitida,
            trecho = trecho,
            carimboMillis = agora(),
            duracaoMs = duracaoMs,
        )

        var melhor: JSONObject? = null
        var melhorNome = ""
        var melhorDistancia = Double.MAX_VALUE

        for (i in 0 until minOf(elementos.length(), MAX_ELEMENTOS)) {
            val e = elementos.optJSONObject(i) ?: continue
            val (lat, lon) = coordenadaDe(e) ?: continue
            // Nó sem `name` é dado, não defeito — e vale menos que um com nome. Só
            // entra se não houver nada nomeado, porque "Sem nome, a 400 metros" é
            // resposta pior que "Hospital Municipal, a 900 metros".
            val nome = e.optJSONObject("tags")?.optString("name").orEmpty()
            val d = distanciaM(lat0, lon0, lat, lon) + if (nome.isBlank()) PENALIDADE_SEM_NOME else 0.0
            if (d < melhorDistancia) {
                melhorDistancia = d
                melhor = e
                melhorNome = nome
            }
        }

        // Sem elemento utilizável: a fonte respondeu, e a resposta é "nada". Ela
        // carrega procedência como qualquer outra — **é o caso em que não há linha
        // de resultado para pendurar o flag, e por isso é o que se esquece.**
        val escolhido = melhor ?: return RespostaGeoespacial.NadaPorPerto(procedencia(""))
        val (lat, lon) = coordenadaDe(escolhido)!!
        return RespostaGeoespacial.Encontrado(
            nome = melhorNome,
            distanciaM = distanciaM(lat0, lon0, lat, lon).roundToInt(),
            // O trecho que FUNDAMENTOU, não o corpo inteiro: é o elemento escolhido,
            // como veio do servidor. É o que o agente confere depois se a resposta
            // for contestada.
            procedencia = procedencia(escolhido.toString()),
        )
    }

    /**
     * Coordenada de um elemento, **nas três formas que a doc do OSM descreve**.
     *
     * `node` traz `lat`/`lon` no topo (confirmado na doc). `way` e `relation` com
     * `out center` trazem o centro; a doc confirma que o modificador existe, mas
     * não o nome do campo em JSON — então tenta-se `center`, e depois o `bounds`
     * (`minlat`/`minlon`/`maxlat`/`maxlon`), que a doc **confirma**.
     *
     * Escrever uma só forma seria assinatura de API de terceiro escrita de
     * memória, que é exatamente o que o `CLAUDE.md` §2 proíbe. Três tentativas em
     * ordem custam nada e não dependem de eu ter lembrado certo.
     */
    private fun coordenadaDe(e: JSONObject): Pair<Double, Double>? {
        if (e.has("lat") && e.has("lon")) return e.getDouble("lat") to e.getDouble("lon")
        e.optJSONObject("center")?.let { c ->
            if (c.has("lat") && c.has("lon")) return c.getDouble("lat") to c.getDouble("lon")
        }
        e.optJSONObject("bounds")?.let { b ->
            if (b.has("minlat") && b.has("maxlat") && b.has("minlon") && b.has("maxlon")) {
                return (b.getDouble("minlat") + b.getDouble("maxlat")) / 2 to
                    (b.getDouble("minlon") + b.getDouble("maxlon")) / 2
            }
        }
        return null
    }

    internal companion object {
        const val OVERPASS = "https://overpass-api.de/api/interpreter"

        /** Decisão 1 da spec: o mesmo prazo da consulta de posição. */
        const val PRAZO_PADRAO_MS = 2_000L

        /**
         * 3 km. Além disso a resposta deixa de ser operacional — um hospital a
         * cinco quilômetros não é "o hospital mais próximo", é uma viagem, e a
         * decisão de fazê-la não se toma por uma frase de sete palavras.
         */
        const val RAIO_PADRAO_M = 3_000

        /** Teto de elementos lidos. Bairro denso devolve muitos; a fala usa um. */
        const val MAX_ELEMENTOS = 200

        /**
         * Elemento sem nome perde por 500 m "virtuais".
         *
         * Não é descarte: se só houver anônimo, ele é a resposta, e "Sem nome, a
         * 400 metros" ainda orienta. Mas entre um anônimo a 400 m e um "Hospital
         * Municipal" a 700 m, o nomeado serve mais a quem vai chegar lá e
         * perguntar por ele.
         */
        const val PENALIDADE_SEM_NOME = 500.0

        /** Raio médio da Terra, em metros. */
        private const val RAIO_DA_TERRA_M = 6_371_000.0

        /**
         * **Quatro casas decimais — ~11 m.**
         *
         * Abaixo do erro típico do GPS de celular, então não custa precisão na
         * resposta; e estritamente menos informação do que a correção crua, que é
         * o ponto. `Locale.ROOT` porque `%.4f` em pt-BR escreve vírgula, e vírgula
         * dentro de `around:R,LAT,LON` faz a consulta virar outra consulta.
         */
        fun arredondar(v: Double): String = String.format(Locale.ROOT, "%.4f", v)

        /** Harversine. Puro, e por isso testável sem rede. */
        fun distanciaM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return RAIO_DA_TERRA_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
