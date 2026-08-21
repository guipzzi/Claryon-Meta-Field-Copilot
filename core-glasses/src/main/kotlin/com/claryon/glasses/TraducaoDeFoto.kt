package com.claryon.glasses

import android.graphics.Bitmap
import android.media.ExifInterface
import com.claryon.common.ClaryonError
import com.claryon.common.Result
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.meta.wearable.dat.camera.types.PhotoData as FotoDoDat

/**
 * **A tradução da foto do DAT para o nosso tipo — e a razão de ela existir.**
 *
 * `DatGlassesFacade.capturePhoto()` devolvia
 * `Result.success(PhotoData(ByteArray(0), photo.toString()))`: **sucesso com zero
 * bytes**, com o `mimeType` recebendo o `toString()` do objeto do SDK. É o mesmo
 * defeito de classe que `startSession` tinha — um `Result.Success` que não
 * significa sucesso — e era inofensivo só porque `capturePhoto` não tem chamador
 * em `src/main`. O primeiro chamador receberia sucesso vazio e acreditaria.
 *
 * A lógica de decisão mora aqui, e não dentro da fachada, pelo mesmo motivo de
 * [VidaDoStream]: assim ela é exercitável **sem óculos, sem emulador e sem
 * MockDeviceKit** — [FotoDoDat.HEIC] é uma classe do artefato que só carrega um
 * `ByteBuffer`, então o ramo que o aparelho realmente usa roda em teste de JVM.
 *
 * ## O que o `javap` confirmou no artefato (Regra Zero)
 *
 * ```
 * javap -cp classes.jar com.meta.wearable.dat.camera.types.PhotoData
 *   public interface PhotoData { }
 *   public final class PhotoData$Bitmap implements PhotoData {
 *       public final android.graphics.Bitmap getBitmap(); }
 *   public final class PhotoData$HEIC implements PhotoData {
 *       public final java.nio.ByteBuffer getData(); }
 * ```
 *
 * Três coisas medidas no bytecode, e nenhuma delas é o que o nome sugere:
 *
 * 1. **Não existe campo de orientação em ramo nenhum.** Nem largura, nem altura,
 *    nem rotação. O que houver de orientação está **dentro dos bytes**, em EXIF.
 *
 * 2. **Em 0.9.0 o ramo `Bitmap` é inalcançável.** O único lugar que constrói o
 *    `PhotoData` interno é `WarpEventCoordinator.handlePhotoChunk`, e ele fixa
 *    `PhotoType.HEIC` com `rawBitmap = null` (`122: getstatic ...PhotoType.HEIC`,
 *    `130: aconst_null`). O `StreamImpl` chega a registrar
 *    `PhotoFormat.defaultFormat` — que é `BITMAP` — mas ninguém lê esse registro
 *    no caminho de recepção. Traduzimos os dois assim mesmo: o ramo existe na API
 *    pública, e um SDK em preview que passe a usá-lo não pode nos pegar devolvendo
 *    sucesso vazio de novo.
 *
 * 3. **`HEIC` não quer dizer HEIC.** São bytes codificados, e o formato depende de
 *    quem capturou. No MockDeviceKit a foto vem da câmera do celular por
 *    `camera2` e é **JPEG** (`CameraStreamProvider.capturePhoto` → `mirrorJpeg`);
 *    sem câmera, `createDefaultPhotoData()` comprime um bitmap em **JPEG**; e
 *    `setCapturedImage(uri)` entrega o arquivo **byte a byte, como estiver** — o
 *    próprio exemplo da Meta injeta um `test_image.png`. Por isso o `mimeType`
 *    sai de [formatoDeImagem], que olha os bytes, e não de uma constante.
 *
 * ## Rotação: medida onde dá, declarada onde não dá
 *
 * O stream é 504×896 retrato, e a foto pode não vir na mesma orientação. O que se
 * pôde medir sem óculos:
 *
 * - o tipo do SDK **não** carrega orientação (acima);
 * - o SDK **não** endireita a imagem antes de nos entregar. Ele até embarca um
 *   decodificador que faria isso — `ImageUtils.decodeHeicWithOrientation`, que lê
 *   o EXIF e aplica uma `Matrix` —, e esse método tem **zero chamadores** em todo
 *   o `mwdat-camera-0.9.0` (varredura de `javap -p -c` sobre todas as classes do
 *   `classes.jar`). Ou seja: os bytes chegam como saíram, EXIF intacto.
 *
 * O que **não** se mede sem óculos reais é o ângulo do sensor da câmera dos
 * Ray-Ban em relação ao retrato do stream — nem se a foto real traz a etiqueta
 * EXIF preenchida. Isso é linha de `docs/VERIFICACOES_COM_HARDWARE.md`.
 *
 * **Então não giramos pixel nenhum.** Girar por palpite tem um modo de falha pior
 * que não girar: se o aparelho já entregar de pé, a "correção" deita a imagem, e
 * ninguém percebe porque não há display. O que fazemos é **declarar**:
 * [PhotoData.orientacaoGraus] carrega o que o payload diz, e `null` quando o
 * payload não diz nada — `null` **não** é sinônimo de zero.
 *
 * ## Nada é persistido
 *
 * A tradução inteira acontece em RAM: `ByteBuffer` → `ByteArray`,
 * `Bitmap.compress` → `ByteArrayOutputStream`, EXIF lido de um
 * `ByteArrayInputStream`. Não há `File`, `filesDir`, `cacheDir` nem `FileProvider`
 * em caminho nenhum daqui — a mesma política de frame efêmero de
 * `specs/consulta-de-placa-por-camera.spec.md`, que vale igual para foto. É
 * asserção, não promessa: `FotoEfemeraTest` varre os três diretórios privados do
 * app com contra-teste.
 */

/**
 * Formatos que sabemos **nomear a partir dos bytes**, com o MIME que o consumidor
 * vai usar para escolher decodificador.
 *
 * A lista é curta de propósito: só o que o caminho do DAT produz hoje (JPEG pelo
 * mock e pela câmera do celular, HEIC pelo aparelho real) mais PNG, que é o que a
 * documentação da própria Meta injeta em `setCapturedImage`. Formato fora da lista
 * vira **falha tipada**, e não um MIME inventado: quem recebe `image/heic` e lê
 * PNG erra em silêncio, que é o defeito que esta tradução existe para fechar.
 */
enum class FormatoDeFoto(val mimeType: String) {
    HEIC("image/heic"),
    JPEG("image/jpeg"),
    PNG("image/png"),
}

/**
 * Identifica o formato pelos **bytes mágicos**. Função pura: sem Android, sem E/S.
 *
 * @return o formato, ou `null` se os bytes não forem uma imagem que saibamos
 *   nomear — inclusive quando são poucos demais para decidir.
 */
fun formatoDeImagem(bytes: ByteArray): FormatoDeFoto? = when {
    ehJpeg(bytes) -> FormatoDeFoto.JPEG
    ehPng(bytes) -> FormatoDeFoto.PNG
    ehHeif(bytes) -> FormatoDeFoto.HEIC
    else -> null
}

/** `FF D8 FF` — SOI seguido do primeiro marcador. */
private fun ehJpeg(b: ByteArray): Boolean =
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

/** `89 50 4E 47 0D 0A 1A 0A`. */
private fun ehPng(b: ByteArray): Boolean =
    b.size >= 8 && ASSINATURA_PNG.indices.all { b[it] == ASSINATURA_PNG[it] }

/**
 * ISO-BMFF com caixa `ftyp` e marca da família HEIF.
 *
 * A marca principal fica nos bytes 8..11 e as compatíveis seguem de 16 em diante,
 * até o fim da caixa. Conferimos as duas: um HEIC gravado por celular costuma
 * trazer `mif1` como principal e `heic` só entre as compatíveis, e olhar apenas a
 * principal recusaria um arquivo perfeitamente válido.
 */
private fun ehHeif(b: ByteArray): Boolean {
    if (b.size < 12) return false
    if (b[4] != 'f'.code.toByte() || b[5] != 't'.code.toByte() ||
        b[6] != 'y'.code.toByte() || b[7] != 'p'.code.toByte()
    ) {
        return false
    }
    if (marcaEm(b, 8) in MARCAS_HEIF) return true
    val tamanhoDaCaixa = ((b[0].toInt() and 0xFF) shl 24) or
        ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or
        (b[3].toInt() and 0xFF)
    val fim = if (tamanhoDaCaixa in 16..b.size) tamanhoDaCaixa else b.size
    var i = 16
    while (i + 4 <= fim) {
        if (marcaEm(b, i) in MARCAS_HEIF) return true
        i += 4
    }
    return false
}

private fun marcaEm(b: ByteArray, off: Int): String =
    String(b, off, 4, Charsets.US_ASCII)

private val ASSINATURA_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

/** Marcas da família HEIF que carregam imagem estática (ISO/IEC 23008-12). */
private val MARCAS_HEIF = setOf(
    "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1",
)

/**
 * Qualidade do JPEG quando o SDK entrega [FotoDoDat.Bitmap].
 *
 * Alta de propósito: um bitmap já decodificado passou por um codec uma vez, e
 * recomprimir é a segunda geração de perda. 95 é onde o artefato de bloco some
 * para leitura de texto — e este ramo não roda em 0.9.0 de qualquer forma.
 */
private const val QUALIDADE_JPEG = 95

/**
 * Traduz a foto do DAT para o nosso [PhotoData], ou devolve **falha tipada**.
 *
 * Nunca devolve sucesso com payload vazio — é a única invariante que este arquivo
 * existe para sustentar, e há contra-teste para ela.
 *
 * @param orientacaoDe costura para teste: em JVM não existe `android.media`, e o
 *   default é o leitor de EXIF de verdade.
 */
internal fun traduzirFotoDoDat(
    doSdk: FotoDoDat,
    orientacaoDe: (ByteArray) -> Int? = ::orientacaoDeclarada,
): Result<PhotoData> = when (doSdk) {

    is FotoDoDat.HEIC -> {
        // `duplicate()` como em `VideoFrame.toFrame`: ler o buffer do SDK move a
        // posição dele, e quem o entregou pode não esperar isso.
        val fonte = doSdk.data.duplicate()
        val bytes = ByteArray(fonte.remaining())
        fonte.get(bytes)
        val formato = formatoDeImagem(bytes)
        when {
            // O `getData()` pode vir com buffer esgotado ou vazio. Sucesso aqui
            // seria exatamente o defeito de origem, só que por outro caminho.
            bytes.isEmpty() -> falha(
                "glasses.photo_empty",
                "A câmera não devolveu imagem.",
            )
            formato == null -> falha(
                "glasses.photo_unknown_format",
                "Formato de foto não reconhecido.",
            )
            else -> Result.success(
                PhotoData(bytes, formato.mimeType, orientacaoDe(bytes)),
            )
        }
    }

    is FotoDoDat.Bitmap -> {
        val saida = ByteArrayOutputStream()
        val comprimiu = runCatching {
            doSdk.bitmap.compress(Bitmap.CompressFormat.JPEG, QUALIDADE_JPEG, saida)
        }.getOrDefault(false)
        val bytes = saida.toByteArray()
        if (!comprimiu || bytes.isEmpty()) {
            falha("glasses.photo_encode_failed", "Falha ao codificar a foto.")
        } else {
            // **`null`, e não `0`.** Um bitmap decodificado não carrega metadado
            // nenhum: os pixels são o que são, e nós não sabemos se estão de pé.
            // Dizer `0` seria afirmar "já está na vertical" sem ter medido.
            Result.success(PhotoData(bytes, FormatoDeFoto.JPEG.mimeType, orientacaoGraus = null))
        }
    }

    // **Não há `else`, e a medição é a razão.**
    //
    // `javap -v` na interface não mostra `PermittedSubclasses` — o atributo do JVM
    // que declara selagem —, e por isso eu tinha escrito um `else` defensivo,
    // supondo que um ramo novo do SDK cairia nele calado. Errado: a selagem do
    // Kotlin vive no `kotlin.Metadata`, e o compilador a respeita. Medido pelo
    // caminho mais direto: um `object : FotoDoDat {}` de teste é recusado com
    // *"Anonymous object cannot extend a sealed interface"*, e o `else` rendia
    // *"'when' is exhaustive so 'else' is redundant here"*.
    //
    // Então isto pertence ao §4 do `CLAUDE.md` — invariante que o **compilador**
    // sustenta: subir o mwdat com um `PhotoData.Raw` quebra o build aqui, que é
    // melhor do que uma falha tipada em runtime. `TraducaoDeFotoTest
    // .oSdkAindaTemDoisRamosDeFoto` guarda o mesmo contrato pelo lado do artefato.
}

private fun falha(codigo: String, frase: String): Result<PhotoData> =
    Result.failure(ClaryonError.Glasses(codigo, frase))

/**
 * Lê a orientação **declarada** pelo payload, em graus horários a aplicar.
 *
 * `android.media.ExifInterface(InputStream)` existe desde a API 24 e lê HEIF desde
 * a 28 — confirmado por `javap` no `android.jar` da compileSdk 35:
 * `public android.media.ExifInterface(java.io.InputStream) throws IOException` e
 * `public int getAttributeInt(java.lang.String, int)`. O minSdk é 31, então não há
 * dependência nova a adicionar: o `androidx.exifinterface` que o próprio SDK usa
 * seria redundante aqui.
 *
 * Espelhamentos (`FLIP_*`, `TRANSPOSE`, `TRANSVERSE`) devolvem `null` de propósito:
 * não são rotação, e reduzi-los a um ângulo seria inventar informação.
 *
 * @return 0, 90, 180, 270 — ou `null` quando o payload não declara. `null` **não**
 *   é zero: é "não sabemos", e o consumidor precisa poder distinguir os dois.
 */
internal fun orientacaoDeclarada(bytes: ByteArray): Int? = runCatching {
    val exif = ExifInterface(ByteArrayInputStream(bytes))
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
        ExifInterface.ORIENTATION_NORMAL -> 0
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> null
    }
}.getOrNull()
