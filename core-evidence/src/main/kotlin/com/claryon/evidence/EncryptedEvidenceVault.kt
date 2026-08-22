package com.claryon.evidence

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Implementação do [EvidenceVault] com repouso cifrado e custódia auditável.
 *
 * ## A conta que motivou esta versão
 *
 * A versão anterior selava **um [EncryptedFile] por quadro de 20 ms** e reescrevia
 * o manifesto **inteiro** a cada `append`. Medindo o que isso custa por minuto de
 * fala a 16 kHz / 16 bits / mono (50 quadros/s, 640 B de PCM por quadro):
 *
 * | | |
 * |---|---|
 * | segmento cifrado (Tink `AES256_GCM_HKDF_4KB`) | 40 B de cabeçalho + 640 B + 16 B de tag = **696 B** |
 * | arquivos por minuto | 3.000 |
 * | segmentos, em bytes lógicos | 2,09 MB |
 * | segmentos, ocupando blocos de 4 KiB | 12,29 MB |
 * | manifesto: 3.000 reescritas de um arquivo que cresce ~135 B/linha | **605,0 MB** |
 * | **total escrito em flash** | **≈ 617 MB/min** |
 * | total **em repouso** ao fim do minuto | 12,69 MB |
 *
 * Os 40 B de cabeçalho e os 16 B de tag não são estimativa: em
 * `AesGcmHkdfStreaming`, `getHeaderLength()` é `1 + keySizeInBytes + 7` e
 * `TAG_SIZE_IN_BYTES` é 16 (conferido no bytecode de `tink-android`), com
 * `keySizeInBytes = 32` para o template `AES256_GCM_HKDF_4KB`.
 *
 * Ou seja: **48 vezes mais bytes escritos do que guardados**, e o desperdício é
 * *quadrático* na duração — dobrar a gravação quadruplica o custo do manifesto.
 * Numa memória flash de celular isso não é só lentidão: é desgaste de célula e é
 * calor, no meio de uma ocorrência.
 *
 * ## As duas correções, e o que cada uma vale
 *
 * 1. **Manifesto append-only** ([Manifesto.Escritor]) — 605 MB/min viram 404 kB/min.
 * 2. **Janela de [janelaMs]** — um segmento a cada 10 s em vez de a cada 20 ms.
 *    3.000 arquivos/min viram 6; os blocos de 4 KiB parcialmente vazios somem
 *    (12,29 MB → 1,94 MB); e as 50 idas ao Keystore por segundo viram 0,1/s.
 *
 * Essa última é maior do que parece: `EncryptedFile.Builder.build()` chama
 * `AndroidKeysetManager.getKeysetHandle()` com `withSharedPref` e
 * `withMasterKeyUri` (conferido no bytecode de `security-crypto`). Cada segmento
 * custava uma leitura de `SharedPreferences` **mais** um desembrulho de chave no
 * Keystore. Cinquenta por segundo, durante toda a ocorrência.
 *
 * Resultado: **617 MB/min → 1,93 MB/min escritos**, 1,94 MB/min em repouso.
 *
 * ## O que a janela custa
 *
 * Até [janelaMs] de áudio ficam em RAM (320 kB a 16 kHz com 10 s) e se perdem se o
 * processo for **morto** (OOM, SIGKILL). Cancelamento e [finalize] selam a janela
 * parcial. É uma troca deliberada: 10 s de risco contra um desperdício de 48× que
 * hoje é permanente.
 *
 * ## Ordem de escrita: segmento primeiro, linha depois
 *
 * `EncryptedFile` **não permite abrir para acrescentar** — `openFileOutput()`
 * lança `IOException` se o arquivo já existe (bytecode conferido). Por isso a
 * janela é montada em RAM e selada de uma vez. E por isso a ordem importa:
 *
 *  - grava o segmento → acrescenta a linha no manifesto (com `fd.sync()`).
 *
 * Uma queda entre os dois passos deixa um segmento **sem linha** — que
 * [verificar] reporta como [Integridade.SegmentoNaoRegistrado], benigno. A ordem
 * inversa deixaria uma linha **sem segmento**, indistinguível de adulteração.
 *
 * Segmento no disco: `<filesDir>/evidence/<id>/seg_<seq>.enc`. O nome é *associated
 * data* do AES-GCM (`EncryptedFile` passa `mFile.getName()` ao Tink nos dois
 * sentidos): renomear um segmento o torna indescriptografável, e é por isso que a
 * v2 mantém o mesmo esquema de nomes da v1.
 *
 * @param janelaMs duração da janela acumulada em RAM antes de selar um segmento.
 * @param reservaDeDiscoBytes piso de espaço livre. Abaixo dele o cofre **para e
 *   avisa** em vez de falhar 50 vezes por segundo em silêncio.
 * ## O que a cadeia de hash não pegava
 *
 * Ela detecta **alteração** e é cega a **remoção no fim**: apagar os últimos
 * segmentos e as linhas `S` correspondentes deixava uma cadeia aritmeticamente
 * perfeita, e [verificar] respondia [Integridade.Integra] sobre uma gravação
 * decapitada. Desde a v3 do manifesto, [finalizar] sela uma **âncora de fim** com
 * chave do Keystore ([AncoraDeFim]) e [verificar] só chega a [Integridade.Integra]
 * com ela válida. Leia [AncoraDeFim] antes de citar isso em relatório: ela fecha o
 * ataque de quem tem acesso ao **disco**, não o de quem executa **como o app**.
 *
 * @param retencao ver [PoliticaDeRetencao]. O padrão não apaga nada.
 * @param clockMillis relógio injetável — mantém a classe testável.
 */
class EncryptedEvidenceVault(
    private val appContext: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val janelaMs: Int = JANELA_PADRAO_MS,
    private val reservaDeDiscoBytes: Long = RESERVA_DE_DISCO_PADRAO,
    private val retencao: PoliticaDeRetencao = PoliticaDeRetencao.TudoRetido,
) : EvidenceVault {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Não é injetável de propósito. Um cofre que aceita outro assinador aceita ser
     * construído com um que assina qualquer coisa — e o único chamador disso seria
     * um teste, que provaria então o caminho que não roda em produção.
     */
    private val assinador: AncoraDeFim.Assinador = AssinadorDoKeystore()

    private val mutex = Mutex()
    private val sessoes = HashMap<String, Sessao>()

    private class Sessao(
        val dir: File,
        val ctx: OccurrenceContext,
        val bytesPorJanela: Int,
        val manifesto: Manifesto.Escritor,
        val janelasRetidas: Int?,
        val motivoDaPurga: String,
    ) {
        val janela = ByteArrayOutputStream(bytesPorJanela + 4_096)
        var seq: Int = 0
        var ultimoHash: String? = null
        val cadeia: MutableList<ChunkHash> = mutableListOf()
        val purgados: MutableList<Purga> = mutableListOf()
    }

    // ── Abertura ──────────────────────────────────────────────────────────────

    override suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val id = "${context.unitId}_${context.agentId}_${context.startedAtEpochMillis}"
                    val dir = File(File(appContext.filesDir, "evidence"), id)
                    if (!dir.exists() && !dir.mkdirs()) {
                        return@runCatching Result.failure(
                            ClaryonError.Evidence("EVID_MKDIR", "não criou diretório da ocorrência"),
                        )
                    }
                    // Não adianta abrir o cofre para ele falhar no primeiro
                    // segmento: o agente ouviria "gravando" e não estaria.
                    espacoLivre(dir)?.let { return@runCatching it }

                    // Reabrir uma ocorrência já existente reiniciaria `seq` em 0 e
                    // sobregravaria seg_00000.enc — destruindo evidência já
                    // gravada. O id é (unidade, agente, início): colisão só
                    // acontece por engano de orquestração, e falhar é o certo.
                    if (sessoes.containsKey(id) || segmentosExistentes(dir)) {
                        return@runCatching Result.failure(
                            ClaryonError.Evidence(
                                "EVID_JA_ABERTA",
                                "já existe gravação para esta ocorrência — finalize antes de reabrir",
                            ),
                        )
                    }

                    val handle = RecordingHandle(id)
                    val bytesPorJanela = bytesPorJanela(context.sampleRateHz)
                    val escritor = Manifesto.Escritor(File(dir, Manifesto.NOME))
                    escritor.cabecalho(context, handle, janelaMs)
                    sessoes[id] = Sessao(
                        dir = dir,
                        ctx = context,
                        bytesPorJanela = bytesPorJanela,
                        manifesto = escritor,
                        janelasRetidas = janelasRetidas(),
                        motivoDaPurga = (retencao as? PoliticaDeRetencao.UltimosMinutos)?.motivo
                            ?: "RETENCAO_CONFIGURADA",
                    )
                    Result.success(handle)
                }.getOrElse { e ->
                    Result.failure(ClaryonError.Evidence("EVID_BEGIN", "falha ao abrir gravação"), e)
                }
            }
        }

    // ── Acúmulo e selagem ─────────────────────────────────────────────────────

    /**
     * Acumula [chunk] na janela corrente e sela um segmento quando ela enche.
     *
     * **Toda** a operação roda sob o `mutex`. Ler a sequência fora do lock e gravar
     * dentro parece suficiente, mas não é: dois `append` concorrentes no mesmo
     * handle leriam `seq=0` e `prev=null`, gravariam ambos em `seg_00000.enc` — o
     * segundo sobrescrevendo o primeiro — e a cadeia ficaria com duas entradas
     * `sequence=0`. Perda silenciosa de evidência, que é exatamente o que este
     * módulo existe para impedir.
     */
    override suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<Anexado> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessao = sessoes[handle.id]
                    ?: return@withLock Result.failure(
                        ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                    )
                runCatching {
                    sessao.janela.write(chunk)
                    if (sessao.janela.size() < sessao.bytesPorJanela) {
                        Result.success(Anexado.NaJanela(sessao.janela.size()))
                    } else {
                        selar(sessao)
                    }
                }.getOrElse { e ->
                    Result.failure(ClaryonError.Evidence("EVID_APPEND", "falha ao anexar segmento"), e)
                }
            }
        }

    /** Sela a janela corrente: cifra, grava, encadeia, registra e aplica retenção. */
    private fun selar(sessao: Sessao): Result<Anexado> {
        espacoLivre(sessao.dir)?.let {
            // Descartar a janela é a única saída: mantê-la faria o acumulador
            // crescer sem limite até o OOM. Perde-se áudio — e o chamador é
            // obrigado a transformar isso em earcon.
            sessao.janela.reset()
            return it
        }

        val bytes = sessao.janela.toByteArray()
        sessao.janela.reset()
        if (bytes.isEmpty()) return Result.success(Anexado.NaJanela(0))

        val seq = sessao.seq
        val prev = sessao.ultimoHash
        val arquivo = segmentFile(sessao.dir, seq)
        // Uma tentativa anterior pode ter deixado um arquivo pela metade, e
        // `openFileOutput` recusa arquivo existente.
        if (arquivo.exists()) arquivo.delete()
        encryptedFile(arquivo).openFileOutput().use { it.write(bytes) }

        val hashHex = HashChain.sha256Hex(bytes, prev)
        val ch = ChunkHash(seq, hashHex, prev, bytes.size)

        // Segmento no disco ANTES da linha: ver a nota de ordem de escrita.
        sessao.manifesto.segmento(ch)

        sessao.seq += 1
        sessao.ultimoHash = hashHex
        sessao.cadeia.add(ch)

        aplicarRetencao(sessao)
        return Result.success(Anexado.JanelaSelada(ch))
    }

    /**
     * Apaga o segmento que saiu da janela de retenção e **registra a purga**.
     *
     * O registro não é burocracia: sem ele, [verificar] reportaria o segmento
     * ausente como adulterado, e a política de retenção do produto passaria a
     * forjar sozinha um sinal de fraude em toda gravação antiga.
     *
     * A cadeia continua verificável depois da purga porque cada elo precisa apenas
     * do **hash anterior**, que o manifesto guarda — não dos bytes anteriores. O
     * que se perde, e não volta, é a possibilidade de provar o conteúdo daquele
     * segmento.
     */
    private fun aplicarRetencao(sessao: Sessao) {
        val retidas = sessao.janelasRetidas ?: return
        val alvo = sessao.seq - retidas - 1
        if (alvo < 0) return
        if (sessao.purgados.any { it.sequence == alvo }) return
        val arquivo = segmentFile(sessao.dir, alvo)
        if (!arquivo.exists()) return
        if (!arquivo.delete()) return
        val purga = Purga(alvo, clockMillis(), sessao.motivoDaPurga)
        sessao.purgados.add(purga)
        sessao.manifesto.purga(purga)
    }

    // ── Fechamento ────────────────────────────────────────────────────────────

    override suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest> =
        finalizar(handle, motivo = null)

    /**
     * Fecha a gravação registrando **por que** ela terminou.
     *
     * `motivo = null` é encerramento normal. `DISCO_CHEIO` (ou outro) fica no
     * manifesto: uma gravação que parou sozinha e uma que foi encerrada pelo
     * agente não podem parecer a mesma coisa para quem periciar depois.
     */
    suspend fun finalizar(handle: RecordingHandle, motivo: String?): Result<CustodyManifest> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessao = sessoes.remove(handle.id)
                    ?: return@withLock Result.failure(
                        ClaryonError.Evidence("EVID_HANDLE", "handle desconhecido ou já finalizado"),
                    )
                runCatching {
                    // A janela parcial vale: são os últimos segundos da ocorrência,
                    // e é onde costuma estar o que importa.
                    val restante = selar(sessao)
                    // Se a janela final não coube, o manifesto tem de dizer isso:
                    // "encerrada" e "encerrada sem espaço" não podem parecer iguais.
                    val motivoFinal = motivo ?: (restante as? Result.Failure)?.error?.code
                    val agora = clockMillis()
                    sessao.manifesto.fim(agora, motivoFinal)

                    // Depois do `F`: o horário e o motivo do fim entram no MAC.
                    // Uma âncora nula sai do manifesto como linha ausente — nunca
                    // como âncora inventada. Ver AncoraDeFim.selar.
                    val ancora = AncoraDeFim.selar(
                        assinador = assinador,
                        declaracao = declaracao(handle, sessao, agora, motivoFinal),
                        segmentos = sessao.cadeia.size,
                        ultimoHashHex = sessao.ultimoHash,
                    )
                    if (ancora != null) sessao.manifesto.ancora(ancora)
                    sessao.manifesto.close()

                    val manifesto = CustodyManifest(
                        handle = handle,
                        chain = sessao.cadeia.toList(),
                        finalizedAtEpochMillis = agora,
                        versao = Manifesto.VERSAO_ATUAL,
                        sampleRateHz = sessao.ctx.sampleRateHz,
                        formato = sessao.ctx.formato,
                        janelaMs = janelaMs,
                        purgados = sessao.purgados.toList(),
                        motivoDoFim = motivoFinal,
                        ancora = ancora,
                    )
                    Result.success(manifesto)
                }.getOrElse { e ->
                    runCatching { sessao.manifesto.close() }
                    Result.failure(ClaryonError.Evidence("EVID_FINALIZE", "falha ao emitir manifesto"), e)
                }
            }
        }

    // ── Conferência ───────────────────────────────────────────────────────────

    /**
     * **Todas as gravações do aparelho, cada uma com o seu veredito.**
     *
     * É o caminho de perícia, e ele não existia: [verificar] e [Manifesto.ler]
     * tinham **zero chamadores em `src/main`** até 22/08. O cofre selava a âncora
     * de fim em produção — caminho alcançável por voz, HMAC do Keystore, manifesto
     * v3 — e conferia **só em teste**. Periciar exigia `adb`/root sobre o diretório
     * privado, que é o acesso que o modelo de ameaça trata como atacante.
     *
     * ## O que esta função custa
     *
     * A conferência **decifra e re-hasheia todos os segmentos** de todas as
     * gravações finalizadas. Não é sondagem: é a operação inteira, e por isso ela
     * nasce de um toque do agente e não de um laço. Um turno de 10 min de gravação
     * ocupa ~1,94 MB de segmentos; vinte ocorrências dessas são ~39 MB de AES-GCM
     * mais SHA-256 — abaixo de um segundo com aceleração de hardware, e ainda assim
     * a tela mostra estado de espera, porque um aparelho sem aceleração existe.
     *
     * A gravação **em curso** é a exceção: ela entra na lista com
     * [RegistroDeCustodia.emAndamento] e **não** é conferida. Decifrar o que o
     * cofre está escrevendo custaria E/S no meio de uma ocorrência para produzir um
     * veredito que muda no segmento seguinte.
     *
     * ## Diretório sem manifesto legível some da lista
     *
     * `Manifesto.ler` devolve `null` quando não há arquivo, quando ele está vazio
     * ou quando nem o `handle=` foi escrito — um `mkdirs` que aconteceu e um
     * `beginRecording` que morreu logo depois. Não há custódia ali para periciar, e
     * inventar uma linha "quebrada" faria a tela acusar adulteração onde houve
     * apenas um diretório vazio.
     */
    suspend fun periciar(): List<RegistroDeCustodia> = withContext(Dispatchers.IO) {
        // O lock só para o retrato das sessões abertas. Segurá-lo durante a
        // conferência inteira travaria `append` por todo o tempo de decifração —
        // e o `append` que trava é evidência que se perde.
        val abertas = mutex.withLock { sessoes.keys.toSet() }

        val raiz = File(appContext.filesDir, "evidence")
        val dirs = raiz.listFiles { f: File -> f.isDirectory }?.toList().orEmpty()

        dirs.mapNotNull { dir ->
            val lido = Manifesto.ler(dir) ?: return@mapNotNull null
            val emAndamento = lido.handle.id in abertas
            RegistroDeCustodia(
                handle = lido.handle,
                versao = lido.versao,
                inicioEpochMillis = lido.inicioEpochMillis,
                fimEpochMillis = lido.fimEpochMillis,
                motivoDoFim = lido.motivoDoFim,
                segmentos = lido.cadeia.size,
                purgados = lido.purgados.size,
                bytesRetidos = lido.paraCustodia().bytesRetidos,
                emAndamento = emAndamento,
                // `verificar(handle)` e não `conferir(...)` direto: o caminho que a
                // perícia executa tem de ser o MESMO que os testes exercitam, senão
                // "conferência construída" volta a ser duas implementações parecidas
                // — a testada e a que roda.
                veredito = if (emAndamento) {
                    Integridade.SemAncoraDeFim(Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA)
                } else {
                    verificar(lido.handle)
                },
            )
        }.sortedByDescending { it.inicioEpochMillis }
    }

    /** Confere a custódia lendo o manifesto do próprio diretório — o que um perito faz. */
    suspend fun verificar(handle: RecordingHandle): Integridade = withContext(Dispatchers.IO) {
        val dir = diretorio(handle)
        val lido = Manifesto.ler(dir)
            ?: return@withContext Integridade.Quebrada(0)
        // `finalizado` vem do leitor, não de `finalizedAtEpochMillis == 0`: um
        // manifesto sem linha `F` não tem fim para ancorar, e inferir isso de um
        // horário zerado seria adivinhar.
        conferir(dir, lido.paraCustodia(), lido.finalizado)
    }

    /**
     * Reabre os segmentos cifrados, descriptografa e reconfere a cadeia contra o
     * [manifesto].
     */
    suspend fun verificar(handle: RecordingHandle, manifesto: CustodyManifest): Integridade =
        withContext(Dispatchers.IO) {
            // Só existe CustodyManifest depois de `finalizar`.
            conferir(diretorio(handle), manifesto, finalizada = true)
        }

    private fun conferir(
        dir: File,
        manifesto: CustodyManifest,
        finalizada: Boolean,
    ): Integridade {
        // A âncora vem primeiro porque responde à pergunta anterior a todas: este
        // manifesto é o que foi selado? Sem isso, conferir segmento por segmento
        // confere fielmente uma lista que o atacante escolheu.
        AncoraDeFim.conferir(
            assinador = assinador,
            declaracao = declaracao(manifesto),
            cadeia = manifesto.chain,
            finalizada = finalizada,
            ancora = manifesto.ancora,
        )?.let { return it }

        val purgadas = manifesto.purgados.map { it.sequence }.toSet()
        var anterior: String? = null
        for (ch in manifesto.chain) {
            if (ch.sequence in purgadas) {
                // Não há o que conferir. O elo seguinte continua ancorado no hash
                // declarado — a cadeia só precisa do hash anterior, não dos bytes.
                anterior = ch.sha256Hex
                continue
            }
            val bytes = try {
                encryptedFile(segmentFile(dir, ch.sequence)).openFileInput().use { it.readBytes() }
            } catch (_: Throwable) {
                // GCM não autenticou (byte adulterado) ou o segmento sumiu sem
                // registro de purga.
                return Integridade.Quebrada(ch.sequence)
            }
            if (HashChain.sha256Hex(bytes, anterior) != ch.sha256Hex) {
                return Integridade.Quebrada(ch.sequence)
            }
            anterior = ch.sha256Hex
        }
        // Segmento no disco além do último registrado: queda entre gravar o
        // arquivo e anexar a linha. É o modo de falha que a ordem de escrita
        // escolheu ter, e não é adulteração.
        val excedente = manifesto.chain.size
        if (segmentFile(dir, excedente).exists()) {
            return Integridade.SegmentoNaoRegistrado(excedente)
        }
        return if (purgadas.isEmpty()) {
            Integridade.Integra
        } else {
            Integridade.ExpurgadaPorPolitica(purgadas.sorted())
        }
    }

    // ── Espaço em disco ───────────────────────────────────────────────────────

    /**
     * `null` se há espaço; [Result.Failure] tipada se não há.
     *
     * Antes desta checagem, disco cheio produzia o pior desfecho possível: o
     * `EncryptedFile` lançava `IOException(ENOSPC)`, o `runCatching` a convertia em
     * `Result.failure`, o chamador **descartava o retorno**, e o cofre falhava
     * cinquenta vezes por segundo até o fim do turno — sem um único som. Pior: o
     * manifesto seguia crescendo com hashes de segmentos que nunca foram
     * escritos, e a conferência posterior acusaria **adulteração** onde houve
     * apenas falta de espaço.
     *
     * `File.usableSpace` (`java.io.File`, presente desde a API 9) respeita a quota
     * do processo. Existe API mais precisa no Android
     * (`StorageManager.getAllocatableBytes`) — **não conferi a assinatura dela** e
     * por isso não a uso aqui.
     */
    private fun espacoLivre(dir: File): Result<Nothing>? {
        val livre = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (livre >= reservaDeDiscoBytes) return null
        return Result.failure(
            ClaryonError.Evidence(
                "EVID_SEM_ESPACO",
                "espaço livre ${livre / 1_048_576} MiB abaixo da reserva " +
                    "${reservaDeDiscoBytes / 1_048_576} MiB",
            ),
        )
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────

    /**
     * O que a âncora assina, montado na selagem (a partir da sessão viva).
     *
     * Existem duas construções porque selar e conferir enxergam fontes diferentes —
     * e é justamente por isso que elas produzem o **mesmo** [AncoraDeFim.Declaracao]:
     * um MAC que só confere quando calculado do mesmo lado não prova nada.
     */
    private fun declaracao(
        handle: RecordingHandle,
        sessao: Sessao,
        fimEpochMillis: Long,
        motivoDoFim: String?,
    ) = AncoraDeFim.Declaracao(
        handleId = handle.id,
        versao = Manifesto.VERSAO_ATUAL,
        sampleRateHz = sessao.ctx.sampleRateHz,
        formato = sessao.ctx.formato,
        janelaMs = janelaMs,
        fimEpochMillis = fimEpochMillis,
        motivoDoFim = motivoDoFim,
        purgados = sessao.purgados.toList(),
    )

    /** O mesmo, montado na conferência (a partir do manifesto apresentado). */
    private fun declaracao(m: CustodyManifest) = AncoraDeFim.Declaracao(
        handleId = m.handle.id,
        versao = m.versao,
        sampleRateHz = m.sampleRateHz,
        formato = m.formato,
        janelaMs = m.janelaMs,
        fimEpochMillis = m.finalizedAtEpochMillis,
        motivoDoFim = m.motivoDoFim,
        purgados = m.purgados,
    )

    private fun bytesPorJanela(sampleRateHz: Int): Int =
        (sampleRateHz.toLong() * BYTES_POR_AMOSTRA * janelaMs / 1_000).toInt()

    /**
     * Quantas janelas cabem na política. Sempre ≥ 1: uma retenção configurada tão
     * curta que não guarda nem uma janela viraria "apagar tudo imediatamente" — um
     * erro de configuração destruindo prova em silêncio.
     */
    private fun janelasRetidas(): Int? = when (val p = retencao) {
        PoliticaDeRetencao.TudoRetido -> null
        is PoliticaDeRetencao.UltimosMinutos -> maxOf(1, p.minutos * 60_000 / janelaMs)
    }

    private fun diretorio(handle: RecordingHandle): File =
        File(File(appContext.filesDir, "evidence"), handle.id)

    private fun segmentFile(dir: File, seq: Int): File =
        File(dir, "seg_%05d.enc".format(seq))

    private fun segmentosExistentes(dir: File): Boolean =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".enc") }?.isNotEmpty() == true

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    companion object {
        /**
         * 10 s. Escolhido por três limites, não por gosto:
         *
         *  - **RAM:** 320 kB a 16 kHz. 30 s seriam ~1 MB retidos o tempo todo.
         *  - **Perda no `kill -9`:** 10 s é o que se perde no pior caso. 30 s
         *    triplicariam isso para economizar 0,01 MB/min — o ganho já foi todo
         *    colhido em 5–10 s, porque o custo dominante era o manifesto.
         *  - **Granularidade probatória:** um bloco de 10 s ainda é uma unidade
         *    citável ("segmento 47, entre 7:50 e 8:00 da gravação").
         */
        const val JANELA_PADRAO_MS = 10_000

        /**
         * 64 MiB de piso. Não é o ponto em que o disco acaba: é o ponto em que
         * ainda dá para fechar o manifesto, tocar o earcon e o sistema continuar
         * funcionando. Reserva menor troca aviso por travamento.
         */
        const val RESERVA_DE_DISCO_PADRAO: Long = 64L * 1_048_576

        private const val BYTES_POR_AMOSTRA = 2 // PCM 16 bits, mono
    }
}
