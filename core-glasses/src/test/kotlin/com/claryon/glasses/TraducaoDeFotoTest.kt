package com.claryon.glasses

import com.claryon.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.nio.ByteBuffer
import org.junit.Test
import com.meta.wearable.dat.camera.types.PhotoData as FotoDoDat

/**
 * **O sucesso vazio, como asserção — não como comentário.**
 *
 * `capturePhoto()` devolvia `Result.success(PhotoData(ByteArray(0), photo.toString()))`.
 * Um teste que só olhasse `isSuccess` ficaria verde com o defeito inteiro de volta:
 * o defeito **era** um sucesso. Por isso toda asserção aqui é sobre o **conteúdo** —
 * bytes idênticos ao payload injetado, `mimeType` que é um MIME de imagem — e três
 * testes existem apenas como contra-teste explícito, no padrão da pergunta 3 do §6.
 *
 * Roda em **JVM**, sem óculos, sem emulador e sem MockDeviceKit: `PhotoData.HEIC` é
 * uma classe do artefato que só embrulha um `ByteBuffer`, e é o ramo que o aparelho
 * de verdade usa (ver [traduzirFotoDoDat] para a medição no bytecode). O ramo
 * `PhotoData.Bitmap` precisa de `android.graphics.Bitmap` e fica com o teste
 * instrumentado; o que dá para provar dele aqui é que **não é ele** que responde
 * pelo caminho real.
 */
class TraducaoDeFotoTest {

    // ── Payloads mínimos, mas válidos para o sniff ────────────────────────────

    /** SOI + APP0/JFIF + EOI: um JPEG degenerado, suficiente para os bytes mágicos. */
    private fun jpeg(marca: Byte = 0x11): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(),
        'F'.code.toByte(), 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01,
        0x00, 0x00, marca,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(16) { it.toByte() }

    /**
     * Caixa `ftyp` de 24 bytes: marca principal `mif1` e `heic` só entre as
     * compatíveis — que é como um HEIC de celular costuma se apresentar, e o caso
     * que um sniff olhando só a marca principal recusaria.
     */
    private fun heic(): ByteArray {
        val marcas = "mif1" + "mif1heic"
        val corpo = "ftyp".toByteArray(Charsets.US_ASCII) + marcas.toByteArray(Charsets.US_ASCII)
        val tamanho = 4 + corpo.size
        return byteArrayOf(
            (tamanho ushr 24).toByte(), (tamanho ushr 16).toByte(),
            (tamanho ushr 8).toByte(), tamanho.toByte(),
        ) + corpo
    }

    private fun doSdk(bytes: ByteArray): FotoDoDat.HEIC =
        FotoDoDat.HEIC(ByteBuffer.wrap(bytes))

    /** Sem `android.media` em JVM: a orientação entra por costura, nunca por EXIF real. */
    private fun traduzir(
        foto: FotoDoDat,
        orientacao: (ByteArray) -> Int? = { null },
    ): Result<PhotoData> = traduzirFotoDoDat(foto, orientacao)

    private fun exigirSucesso(r: Result<PhotoData>): PhotoData {
        assertTrue("esperava sucesso e veio $r", r is Result.Success)
        return (r as Result.Success).value
    }

    private fun exigirFalha(r: Result<PhotoData>): String {
        assertTrue("esperava falha tipada e veio $r", r is Result.Failure)
        return (r as Result.Failure).error.code
    }

    // ── O contra-teste do defeito de origem ───────────────────────────────────

    /**
     * **Com o defeito de volta, este teste reprova.**
     *
     * O defeito era literalmente `Result.success(PhotoData(ByteArray(0), photo.toString()))`.
     * As três asserções cobrem as três partes dele: bytes não vazios, bytes
     * **iguais ao que entrou** (um payload fabricado passaria pela primeira), e um
     * `mimeType` que é MIME — não o `toString()` de um objeto do SDK, que para
     * `PhotoData$HEIC` é `HEIC(data=java.nio.HeapByteBuffer[pos=0 lim=23 cap=23])`.
     */
    @Test
    fun oSucessoVazio_naoPassa_eOsBytesSaoOsQueEntraram() {
        val payload = jpeg(marca = 0x42)
        val foto = exigirSucesso(traduzir(doSdk(payload)))

        assertFalse("voltou o sucesso com zero bytes", foto.bytes.isEmpty())
        assertEquals(
            "o payload foi trocado por outro em vez de traduzido",
            payload.toList(),
            foto.bytes.toList(),
        )
        assertTrue("o mimeType não é um MIME: \"${foto.mimeType}\"", foto.mimeType.startsWith("image/"))
        assertFalse(
            "o mimeType é o toString() do objeto do SDK: \"${foto.mimeType}\"",
            foto.mimeType.contains("PhotoData") ||
                foto.mimeType.contains("HEIC(") ||
                foto.mimeType.contains("ByteBuffer"),
        )
    }

    /**
     * **Payload vazio é falha, não sucesso.**
     *
     * Este é o caso em que o defeito e o conserto ficam mais próximos: o SDK
     * devolve `DatResult.success` com um buffer sem nada. Repassar isso como
     * `Result.Success` seria reintroduzir o defeito com a bênção do SDK.
     */
    @Test
    fun bufferVazio_viraFalhaTipada_eNaoSucesso() {
        assertEquals("glasses.photo_empty", exigirFalha(traduzir(doSdk(ByteArray(0)))))
    }

    /**
     * **Bytes que não são imagem não ganham `mimeType` inventado.**
     *
     * O ramo se chama `HEIC` e não garante HEIC nenhum — no MockDeviceKit vem
     * JPEG, e `setCapturedImage` entrega o arquivo como estiver. Devolver
     * `image/heic` fixo faria o consumidor escolher o decodificador errado sem
     * nenhum erro aparecendo.
     */
    @Test
    fun bytesQueNaoSaoImagem_viramFalha_emVezDeMimeInventado() {
        val lixo = ByteArray(64) { (it * 7).toByte() }
        assertEquals("glasses.photo_unknown_format", exigirFalha(traduzir(doSdk(lixo))))
    }

    /**
     * **Os dois ramos do SDK são exatamente os que a tradução cobre.**
     *
     * Mesmo padrão de [TraducaoDoDatTest]: lê os ramos **do próprio artefato** e
     * exige que a nossa tradução dê conta deles. Subir o mwdat e ganhar um
     * `PhotoData.Raw` passa a quebrar o build aqui — o único lugar em que a
     * divergência não passa despercebida.
     *
     * A leitura é por `Class.getClasses()`, e não por `KClass.sealedSubclasses`,
     * porque a segunda exige `kotlin-reflect` no classpath de teste — dependência
     * nova para responder a uma pergunta que o `InnerClasses` do bytecode já
     * responde.
     *
     * **Nota de medição**, contra o que o comentário original supunha: `PhotoData`
     * *é* selada no Kotlin (a selagem está no `kotlin.Metadata`, não no atributo
     * `PermittedSubclasses` do JVM, que o `javap -v` não mostra). Foi medido pelo
     * caminho mais direto possível: a primeira versão deste teste construía um
     * ramo falso com `object : PhotoData {}` e o compilador recusou —
     * *"Anonymous object cannot extend a sealed interface"*. Por isso o caminho
     * `else` de [traduzirFotoDoDat] não tem como ser exercitado a partir de
     * Kotlin, e o que se pode afirmar é o contrato de ramos, que é isto.
     */
    @Test
    fun oSdkAindaTemDoisRamosDeFoto() {
        val ramos = FotoDoDat::class.java.classes
            .filter { FotoDoDat::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .sorted()
        assertEquals(
            "o mwdat mudou os ramos de PhotoData — revisar traduzirFotoDoDat",
            listOf("Bitmap", "HEIC"),
            ramos,
        )
    }

    // ── O buffer do SDK não pode ser consumido ────────────────────────────────

    /**
     * **Ler o payload não move a posição do buffer do SDK.**
     *
     * `ByteBuffer.get` avança a posição. Sem `duplicate()`, a primeira leitura
     * esvazia o buffer e uma segunda — pelo painel de diagnóstico, por um retry —
     * traduziria **zero bytes com sucesso**: o defeito de origem, ressuscitado por
     * outro caminho. O contra-teste é traduzir duas vezes o mesmo objeto.
     */
    @Test
    fun traduzirDuasVezes_daOMesmoPayload() {
        val payload = png()
        val foto = doSdk(payload)
        val primeira = exigirSucesso(traduzir(foto))
        val segunda = exigirSucesso(traduzir(foto))

        assertEquals("a posição do buffer do SDK foi consumida", 0, foto.data.position())
        assertEquals(payload.toList(), primeira.bytes.toList())
        assertEquals(
            "a segunda tradução veio diferente — o buffer foi consumido na primeira",
            primeira.bytes.toList(),
            segunda.bytes.toList(),
        )
    }

    // ── Formato: o MIME sai dos bytes ─────────────────────────────────────────

    @Test
    fun oMimeSaiDosBytes_eNaoDoNomeDoRamo() {
        // O ramo é o MESMO nos três casos — `PhotoData.HEIC` — e os MIMEs têm de
        // diferir. É o contra-teste do `image/heic` fixo: com a constante de volta,
        // duas destas três asserções falham.
        assertEquals("image/jpeg", exigirSucesso(traduzir(doSdk(jpeg()))).mimeType)
        assertEquals("image/png", exigirSucesso(traduzir(doSdk(png()))).mimeType)
        assertEquals("image/heic", exigirSucesso(traduzir(doSdk(heic()))).mimeType)
    }

    @Test
    fun oSniff_reconheceOsTres_eRecusaOResto() {
        assertEquals(FormatoDeFoto.JPEG, formatoDeImagem(jpeg()))
        assertEquals(FormatoDeFoto.PNG, formatoDeImagem(png()))
        assertEquals(FormatoDeFoto.HEIC, formatoDeImagem(heic()))

        assertNull("bytes curtos demais decidiram formato", formatoDeImagem(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(formatoDeImagem(ByteArray(0)))
        assertNull(formatoDeImagem("nao sou imagem nenhuma".toByteArray()))
        // `ftyp` com marca que não é da família HEIF — um MP4 comum.
        val mp4 = byteArrayOf(0, 0, 0, 0x14) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            "isomiso2".toByteArray(Charsets.US_ASCII)
        assertNull("um MP4 passou por HEIC", formatoDeImagem(mp4))
    }

    // ── Orientação: declarada, nunca presumida ────────────────────────────────

    /**
     * **`null` não é zero, e é isso que o tipo precisa preservar.**
     *
     * O SDK não tem campo de orientação em ramo nenhum, e não endireita a imagem
     * antes de entregar (`ImageUtils.decodeHeicWithOrientation` existe no artefato
     * e tem zero chamadores). Se a tradução colapsasse "não declarado" em `0`, o
     * consumidor leria "já está de pé" — uma afirmação que ninguém mediu.
     */
    @Test
    fun orientacaoNaoDeclarada_ficaNula_eNaoViraZero() {
        val foto = exigirSucesso(traduzir(doSdk(jpeg())) { null })
        assertNull("\"não declarado\" virou 0 — afirmação que ninguém mediu", foto.orientacaoGraus)
    }

    @Test
    fun orientacaoDeclarada_chegaAoNossoTipo_semGirarPixel() {
        val payload = jpeg()
        val foto = exigirSucesso(traduzir(doSdk(payload)) { 90 })
        assertEquals(90, foto.orientacaoGraus)
        assertEquals(
            "os bytes foram alterados — a tradução girou a imagem por conta própria",
            payload.toList(),
            foto.bytes.toList(),
        )
    }
}
