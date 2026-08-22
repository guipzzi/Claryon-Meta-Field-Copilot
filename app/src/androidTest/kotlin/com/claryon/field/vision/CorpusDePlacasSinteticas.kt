package com.claryon.field.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.claryon.glasses.Frame
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * **O banco de imagens de placa — sintético, determinístico e degradado de propósito.**
 *
 * Não existe foto de placa no repositório, e não pode existir: imagem de veículo real
 * carrega dado de terceiro, e o `CLAUDE.md` §2 proíbe base indexada de gente. O corpus
 * é gerado por `Canvas` a cada execução, vive em RAM enquanto o teste roda, e some com
 * o processo — o mesmo contrato dos frames de verdade.
 *
 * ## Por que Canvas no aparelho, e não PIL no Mac
 *
 * A alternativa era gerar PNG em Python e versioná-los como asset. Foi descartada por
 * três motivos, nesta ordem:
 *
 *  1. **Um teste permanente não pode depender de binário que ninguém sabe reproduzir.**
 *     31 PNG versionados envelhecem sem que nada acuse: mudou o gerador, os arquivos
 *     ficam. Aqui a imagem e a receita são o mesmo objeto.
 *  2. **A semente é fixa** ([Random] com semente por condição), então a mesma corrida
 *     produz o mesmo pixel — a medida é comparável entre sessões sem guardar imagem.
 *  3. Zero byte novo no repositório e zero byte novo no aparelho.
 *
 * ## O que estas imagens NÃO provam
 *
 * Placa sintética não é fotografia. O que está modelado aqui é **geometria e
 * fotometria** — perspectiva, arrasto, ganho, oclusão, sub-amostragem —, e não a
 * física do sensor dos óculos: sem demosaicing, sem compressão H.265 com perda, sem
 * o ruído real de um sensor pequeno com pouca luz, sem a fonte oficial (a *Mandatory*
 * das placas brasileiras, que aqui vira uma condensada do sistema). O número que sai
 * daqui é o **teto** do que o ML Kit consegue: em campo ele só pode ser pior.
 * Ver `docs/VERIFICACOES_COM_HARDWARE.md`.
 */
object CorpusDePlacasSinteticas {

    /**
     * A resolução do stream, medida no artefato e não lembrada: `javap -p -c` em
     * `mwdat-camera-0.9.0`, `VideoFormat.Companion.getDefaultFormat()` →
     * `H265, 504x896, 30 fps, colorFormat 19`. Retrato, porque os óculos são.
     */
    const val LARGURA = 504
    const val ALTURA = 896

    enum class Formato { MERCOSUL, ANTIGA }

    /**
     * Uma imagem do corpus.
     *
     * [esperada] `null` marca **negativo**: cena sem placa nenhuma. Eles não são
     * enfeite — a métrica que mais importa neste teste é placa **errada aceita**, e
     * fabricar placa onde não há é a forma mais cara de errar.
     */
    data class Cena(
        val condicao: String,
        val grupo: String,
        val esperada: String?,
        val formato: Formato?,
        val ruidoSigma: Double,
        val bitmap: Bitmap,
    )

    // ── Placas ────────────────────────────────────────────────────────────────

    /** Mercosul `LLL9L99`. */
    private const val MERCOSUL_LIMPA = "ABC1D23"

    /**
     * Mercosul com caracteres que o OCR confunde: `J`/`I`, `5`/`S`, `0`/`O`, `9`/`g`.
     * Uma placa só de letras redondas mediria o caso fácil e chamaria de medida.
     */
    private const val MERCOSUL_CONFUSAVEL = "JKL5M09"

    /** Antiga `LLL9999`. */
    private const val ANTIGA_LIMPA = "DEF4567"

    /** Antiga com o pior par possível: `O`×`0`, `S`×`5`, `B`×`8`. */
    private const val ANTIGA_CONFUSAVEL = "OSB1058"

    private const val PLACA_W = 448
    private const val PLACA_H = 146 // 400×130 mm ⇒ 3,08:1

    private fun condensada(): Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /** Fonte de fato resolvida — vai ao log para que "condensada" não seja promessa. */
    fun fonteResolvida(): String {
        val c = condensada()
        val padrao = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        return "sans-serif-condensed bold; difere da sans-serif padrão: ${c != padrao}"
    }

    /**
     * A placa em si: fundo cinza-claro, caracteres pretos, faixa azul no topo quando
     * Mercosul e tarja de município quando antiga.
     *
     * A tarja **tem texto** (`BAHIA · SALVADOR`, `BRASIL`) de propósito: é texto extra
     * dentro do mesmo retângulo, e é ele que dá ao `PlacaValidator` a chance de casar
     * a coisa errada. Uma placa com só sete caracteres no meio de um retângulo liso
     * seria um alvo mais fácil do que qualquer placa que existe.
     */
    private fun desenharPlaca(texto: String, formato: Formato): Bitmap {
        val bmp = Bitmap.createBitmap(PLACA_W, PLACA_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = PLACA_W.toFloat()
        val h = PLACA_H.toFloat()

        val faixaH: Float
        when (formato) {
            Formato.MERCOSUL -> {
                faixaH = 26f
                c.drawColor(Color.rgb(0xEA, 0xEA, 0xE4)) // cinza-claro do corpo
                c.drawRect(0f, 0f, w, faixaH, Paint().apply { color = Color.rgb(0x12, 0x33, 0x8A) })
                val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 16f
                    typeface = condensada()
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("BRASIL", w / 2f, faixaH - 7f, rotulo)
                c.drawText("MERCOSUL", w - 62f, faixaH - 7f, rotulo)
                // A bandeirinha à esquerda da faixa — mancha de cor, não texto.
                c.drawRect(10f, 5f, 40f, faixaH - 5f, Paint().apply { color = Color.rgb(0, 0x8A, 0x3C) })
            }
            Formato.ANTIGA -> {
                faixaH = 28f
                c.drawColor(Color.rgb(0xD0, 0xD0, 0xC9)) // cinza-claro
                c.drawRect(0f, 0f, w, faixaH, Paint().apply { color = Color.rgb(0xB6, 0xB6, 0xAE) })
                val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(0x20, 0x20, 0x20)
                    textSize = 17f
                    typeface = condensada()
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("BAHIA", w * 0.28f, faixaH - 7f, rotulo)
                c.drawText("SALVADOR", w * 0.72f, faixaH - 7f, rotulo)
            }
        }

        c.drawRect(
            2f, 2f, w - 2f, h - 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0x18, 0x18, 0x18)
                style = Paint.Style.STROKE
                strokeWidth = 5f
            },
        )

        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x10, 0x10, 0x10)
            typeface = condensada()
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.07f
            textSize = 100f
        }
        // Ajuste de corpo por medição, não por chute: a condensada do sistema pode
        // mudar de aparelho para aparelho, e um `textSize` fixo estouraria a borda
        // em um deles — o que mediria recorte, não condição de campo.
        tinta.textSize = 100f * (w * 0.84f) / tinta.measureText(texto)
        val fm = tinta.fontMetrics
        val meio = faixaH + (h - faixaH) / 2f
        c.drawText(texto, w / 2f, meio - (fm.ascent + fm.descent) / 2f, tinta)
        return bmp
    }

    // ── Cena ──────────────────────────────────────────────────────────────────

    /** Traseira de veículo escuro: para-choque, vinco e sombra. Sem placa ainda. */
    private fun carroceria(claridade: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(LARGURA, ALTURA, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun tom(v: Int) = (v * claridade).toInt().coerceIn(0, 255)
        c.drawColor(Color.rgb(tom(64), tom(66), tom(72)))
        c.drawRect(
            0f, 300f, LARGURA.toFloat(), 640f,
            Paint().apply { color = Color.rgb(tom(84), tom(86), tom(92)) },
        )
        c.drawRect(
            0f, 296f, LARGURA.toFloat(), 302f,
            Paint().apply { color = Color.rgb(tom(38), tom(40), tom(44)) },
        )
        c.drawRect(
            0f, 700f, LARGURA.toFloat(), ALTURA.toFloat(),
            Paint().apply { color = Color.rgb(tom(28), tom(29), tom(33)) },
        )
        return bmp
    }

    /**
     * Cola a placa na cena com perspectiva de verdade ([Matrix.setPolyToPoly]), não com
     * um `scale` disfarçado.
     *
     * @param yawGraus rotação em torno do eixo VERTICAL — o carro visto de lado. A
     *   aresta que se afasta encolhe, e é esse encolhimento que mata o OCR, não a
     *   largura menor.
     * @param rotGraus rotação NO PLANO da imagem — o veículo em declive.
     */
    private fun colar(
        cena: Bitmap,
        placa: Bitmap,
        cx: Float,
        cy: Float,
        escala: Float,
        yawGraus: Float = 0f,
        rotGraus: Float = 0f,
    ) {
        val yaw = Math.toRadians(yawGraus.toDouble())
        val w = PLACA_W * escala * cos(yaw).toFloat()
        val hPerto = PLACA_H * escala * (1f + 0.20f * sin(yaw).toFloat())
        val hLonge = PLACA_H * escala * (1f - 0.34f * sin(yaw).toFloat())

        val bruto = floatArrayOf(
            cx - w / 2f, cy - hPerto / 2f,
            cx + w / 2f, cy - hLonge / 2f,
            cx + w / 2f, cy + hLonge / 2f,
            cx - w / 2f, cy + hPerto / 2f,
        )
        val rot = Math.toRadians(rotGraus.toDouble())
        val destino = FloatArray(8)
        for (i in 0 until 4) {
            val dx = bruto[i * 2] - cx
            val dy = bruto[i * 2 + 1] - cy
            destino[i * 2] = cx + (dx * cos(rot) - dy * sin(rot)).toFloat()
            destino[i * 2 + 1] = cy + (dx * sin(rot) + dy * cos(rot)).toFloat()
        }

        val origem = floatArrayOf(
            0f, 0f,
            PLACA_W.toFloat(), 0f,
            PLACA_W.toFloat(), PLACA_H.toFloat(),
            0f, PLACA_H.toFloat(),
        )
        val m = Matrix()
        check(m.setPolyToPoly(origem, 0, destino, 0, 4)) { "perspectiva degenerada" }
        Canvas(cena).drawBitmap(
            placa,
            m,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }

    /** Cena pronta: carroceria + placa colada no centro da traseira. */
    private fun comPlaca(
        texto: String,
        formato: Formato,
        escala: Float = 1f,
        yawGraus: Float = 0f,
        rotGraus: Float = 0f,
        claridade: Float = 1f,
    ): Bitmap = carroceria(claridade).also {
        colar(it, desenharPlaca(texto, formato), LARGURA / 2f, 470f, escala, yawGraus, rotGraus)
    }

    // ── Degradações ───────────────────────────────────────────────────────────

    /** Desfoque por sub-amostragem: caixa de raio ~[fator] px, barata e determinística. */
    private fun borrar(src: Bitmap, fator: Int): Bitmap {
        val pequeno = Bitmap.createScaledBitmap(src, src.width / fator, src.height / fator, true)
        return Bitmap.createScaledBitmap(pequeno, src.width, src.height, true)
    }

    /**
     * Arrasto de movimento — a MÉDIA de [passos] cópias deslocadas, não um borrão.
     *
     * O peso uniforme sai de desenhar a i-ésima camada com alfa `1/(i+1)`: a primeira
     * entra inteira, a segunda com meio, a terceira com um terço. O resultado é a média
     * exata das camadas. Com alfa constante o resultado seria dominado pela última —
     * um teste de desfoque que mediria a cópia final, não o arrasto.
     */
    private fun arrastar(src: Bitmap, dx: Float, dy: Float, passos: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until passos) {
            p.alpha = (255f / (i + 1)).toInt().coerceIn(1, 255)
            val f = i.toFloat() / (passos - 1) - 0.5f
            c.drawBitmap(src, dx * f, dy * f, p)
        }
        return out
    }

    /** Ganho e deslocamento de exposição (pouca luz, estouro). */
    private fun exposicao(src: Bitmap, ganho: Float, offset: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, 0f, 0f,
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            ganho, 0f, 0f, 0f, offset,
                            0f, ganho, 0f, 0f, offset,
                            0f, 0f, ganho, 0f, offset,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            },
        )
        return out
    }

    /** Contraste em torno do cinza médio. */
    private fun contraste(src: Bitmap, c: Float): Bitmap =
        exposicao(src, c, 128f * (1f - c))

    /** Mancha de luz: farol, sol, reflexo especular. */
    private fun clarao(alvo: Bitmap, cx: Float, cy: Float, raio: Float, alfa: Int) {
        Canvas(alvo).drawCircle(
            cx, cy, raio,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, raio,
                    Color.argb(alfa, 255, 255, 250),
                    Color.argb(0, 255, 255, 250),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /** Barro: manchas irregulares grudadas na parte de baixo da placa. */
    private fun barro(alvo: Bitmap, semente: Long, manchas: Int, xDe: Float, xAte: Float) {
        val r = Random(semente)
        val c = Canvas(alvo)
        repeat(manchas) {
            val x = xDe + r.nextFloat() * (xAte - xDe)
            val y = 470f + (r.nextFloat() - 0.25f) * 70f
            val rx = 16f + r.nextFloat() * 34f
            val ry = 12f + r.nextFloat() * 26f
            c.drawOval(
                x - rx, y - ry, x + rx, y + ry,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(150 + r.nextInt(90), 84 + r.nextInt(30), 62, 40)
                },
            )
        }
    }

    /** Gotas de chuva sobre a placa, com o filme de água que borra o que está atrás. */
    private fun chuva(src: Bitmap, semente: Long, gotas: Int): Bitmap {
        val out = borrar(src, 2)
        val r = Random(semente)
        val c = Canvas(out)
        repeat(gotas) {
            val x = 40f + r.nextFloat() * (LARGURA - 80f)
            val y = 380f + r.nextFloat() * 190f
            val raio = 3f + r.nextFloat() * 9f
            c.drawCircle(
                x, y, raio,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 245, 245, 255) },
            )
            c.drawCircle(
                x, y, raio,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(120, 40, 40, 50)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                },
            )
        }
        repeat(gotas / 4) {
            val x = 40f + r.nextFloat() * (LARGURA - 80f)
            val y = 360f + r.nextFloat() * 120f
            c.drawLine(
                x, y, x + 2f, y + 30f + r.nextFloat() * 40f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(70, 235, 240, 255)
                    strokeWidth = 2.5f
                },
            )
        }
        return out
    }

    /** Sub-amostragem: a placa a 8 m tem menos pixel, e nenhum filtro devolve isso. */
    private fun distancia(src: Bitmap, fator: Int): Bitmap = borrar(src, fator)

    // ── Negativos: cena com texto e SEM placa ─────────────────────────────────

    private fun letreiro(vararg linhas: String): Bitmap {
        val bmp = carroceria()
        val c = Canvas(bmp)
        c.drawRect(
            26f, 350f, LARGURA - 26f, 350f + 62f * linhas.size + 26f,
            Paint().apply { color = Color.rgb(0xD4, 0xD4, 0xCE) },
        )
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x16, 0x16, 0x1E)
            textSize = 46f
            typeface = condensada()
            textAlign = Paint.Align.CENTER
        }
        linhas.forEachIndexed { i, l -> c.drawText(l, LARGURA / 2f, 408f + 62f * i, tinta) }
        return bmp
    }

    // ── O corpus ──────────────────────────────────────────────────────────────

    /**
     * 26 imagens com placa + 5 negativos. A ordem é a do relatório, e é estável.
     *
     * Cada condição aparece **uma vez**: com uma imagem por condição, "taxa de acerto
     * por condição" é acerto ou erro, e o que agrega de verdade é o GRUPO — por isso
     * [Cena.grupo] existe.
     */
    fun corpus(): List<Cena> = listOf(
        // ── controle ──────────────────────────────────────────────────────────
        Cena(
            "nitida_frontal_mercosul", "controle", MERCOSUL_LIMPA, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL),
        ),
        Cena(
            "nitida_frontal_antiga", "controle", ANTIGA_LIMPA, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_LIMPA, Formato.ANTIGA),
        ),
        Cena(
            "nitida_caracteres_confusaveis", "controle", ANTIGA_CONFUSAVEL, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_CONFUSAVEL, Formato.ANTIGA),
        ),

        // ── ângulo (o carro visto de lado) ────────────────────────────────────
        Cena(
            "angulo_lateral_15", "ângulo", MERCOSUL_LIMPA, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, yawGraus = 15f),
        ),
        Cena(
            "angulo_lateral_30", "ângulo", MERCOSUL_LIMPA, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, yawGraus = 30f),
        ),
        Cena(
            "angulo_lateral_45", "ângulo", MERCOSUL_LIMPA, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, yawGraus = 45f),
        ),
        Cena(
            "angulo_lateral_30_antiga", "ângulo", ANTIGA_LIMPA, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_LIMPA, Formato.ANTIGA, yawGraus = 30f),
        ),

        // ── desfoque de movimento ─────────────────────────────────────────────
        Cena(
            "desfoque_movimento_leve", "desfoque", MERCOSUL_CONFUSAVEL, Formato.MERCOSUL, 2.0,
            arrastar(comPlaca(MERCOSUL_CONFUSAVEL, Formato.MERCOSUL), 14f, 2f, 8),
        ),
        Cena(
            "desfoque_movimento_forte", "desfoque", MERCOSUL_CONFUSAVEL, Formato.MERCOSUL, 2.0,
            arrastar(comPlaca(MERCOSUL_CONFUSAVEL, Formato.MERCOSUL), 46f, 6f, 12),
        ),
        Cena(
            "desfoque_de_foco", "desfoque", ANTIGA_LIMPA, Formato.ANTIGA, 2.0,
            borrar(comPlaca(ANTIGA_LIMPA, Formato.ANTIGA), 5),
        ),

        // ── luz ───────────────────────────────────────────────────────────────
        Cena(
            "baixa_luz", "luz", ANTIGA_LIMPA, Formato.ANTIGA, 7.0,
            exposicao(comPlaca(ANTIGA_LIMPA, Formato.ANTIGA), 0.22f, 6f),
        ),
        Cena(
            "alto_contraste", "luz", ANTIGA_LIMPA, Formato.ANTIGA, 3.0,
            contraste(comPlaca(ANTIGA_LIMPA, Formato.ANTIGA), 2.6f),
        ),
        Cena(
            "contraluz", "luz", MERCOSUL_LIMPA, Formato.MERCOSUL, 3.0,
            // O céu estoura por trás e o veículo vira silhueta: a placa perde o
            // branco de fundo, que é justamente o que dá contraste ao caractere.
            exposicao(comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, claridade = 0.9f), 0.40f, 4f)
                .also { clarao(it, LARGURA / 2f, 120f, 420f, 235) },
        ),

        // ── reflexo ───────────────────────────────────────────────────────────
        Cena(
            "reflexo_de_farol", "reflexo", MERCOSUL_LIMPA, Formato.MERCOSUL, 3.0,
            comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL)
                .also { clarao(it, 300f, 470f, 130f, 250) },
        ),
        Cena(
            "reflexo_de_sol", "reflexo", ANTIGA_LIMPA, Formato.ANTIGA, 3.0,
            comPlaca(ANTIGA_LIMPA, Formato.ANTIGA)
                .also { clarao(it, LARGURA / 2f, 440f, 260f, 205) },
        ),

        // ── sujeira ───────────────────────────────────────────────────────────
        Cena(
            "suja_barro_parcial", "sujeira", ANTIGA_CONFUSAVEL, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_CONFUSAVEL, Formato.ANTIGA).also { barro(it, 11, 7, 70f, 200f) },
        ),
        Cena(
            "suja_barro_forte", "sujeira", ANTIGA_CONFUSAVEL, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_CONFUSAVEL, Formato.ANTIGA).also { barro(it, 23, 22, 60f, 440f) },
        ),
        Cena(
            // **O caso que separa "não li" de "li errado".** O barro come SÓ o último
            // caractere; sobram seis legíveis. A regra estrita recusa por comprimento,
            // e é isso que impede `DEF456` de virar consulta. É esta imagem que o
            // contra-teste usa para mostrar o preço de aceitar 6 caracteres.
            "suja_barro_no_ultimo_digito", "sujeira", ANTIGA_LIMPA, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_LIMPA, Formato.ANTIGA).also { barro(it, 47, 6, 392f, 436f) },
        ),

        // ── oclusão ───────────────────────────────────────────────────────────
        Cena(
            "ocluida_por_parafuso", "oclusão", MERCOSUL_CONFUSAVEL, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_CONFUSAVEL, Formato.MERCOSUL).also { bmp ->
                val c = Canvas(bmp)
                listOf(170f to 448f, 330f to 448f).forEach { (x, y) ->
                    c.drawCircle(
                        x, y, 17f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x3A, 0x3E) },
                    )
                    c.drawCircle(
                        x, y, 17f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.rgb(0x9A, 0x9A, 0xA0)
                            style = Paint.Style.STROKE
                            strokeWidth = 3f
                        },
                    )
                }
            },
        ),
        Cena(
            "ocluida_por_moldura_de_concessionaria", "oclusão", ANTIGA_LIMPA, Formato.ANTIGA, 2.0,
            comPlaca(ANTIGA_LIMPA, Formato.ANTIGA).also { bmp ->
                val c = Canvas(bmp)
                // A moldura come as bordas E acrescenta texto que não é placa — o pior
                // dos dois mundos, e o caso mais comum em veículo de frota.
                c.drawRect(
                    36f, 386f, LARGURA - 36f, 396f,
                    Paint().apply { color = Color.rgb(0x1A, 0x1A, 0x1E) },
                )
                c.drawRect(
                    36f, 528f, LARGURA - 36f, 566f,
                    Paint().apply { color = Color.rgb(0x1A, 0x1A, 0x1E) },
                )
                c.drawText(
                    "AUTO CENTRO SALVADOR",
                    LARGURA / 2f, 556f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(0xE0, 0xE0, 0xE0)
                        textSize = 28f
                        typeface = condensada()
                        textAlign = Paint.Align.CENTER
                    },
                )
            },
        ),

        // ── chuva ─────────────────────────────────────────────────────────────
        Cena(
            "chuva_com_gotas", "chuva", MERCOSUL_LIMPA, Formato.MERCOSUL, 4.0,
            chuva(comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, claridade = 0.75f), 31, 90),
        ),

        // ── distância ─────────────────────────────────────────────────────────
        Cena(
            "distancia_media_placa_a_45pct", "distância", MERCOSUL_LIMPA, Formato.MERCOSUL, 3.0,
            distancia(comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, escala = 0.45f), 2),
        ),
        Cena(
            "distancia_longa_placa_a_22pct", "distância", MERCOSUL_LIMPA, Formato.MERCOSUL, 3.0,
            distancia(comPlaca(MERCOSUL_LIMPA, Formato.MERCOSUL, escala = 0.22f), 3),
        ),

        // ── inclinação no plano ───────────────────────────────────────────────
        Cena(
            "inclinada_no_plano_14_graus", "inclinação", MERCOSUL_CONFUSAVEL, Formato.MERCOSUL, 2.0,
            comPlaca(MERCOSUL_CONFUSAVEL, Formato.MERCOSUL, rotGraus = -14f),
        ),

        // ── noite ─────────────────────────────────────────────────────────────
        Cena(
            "noturna_com_farol", "noite", ANTIGA_LIMPA, Formato.ANTIGA, 10.0,
            exposicao(comPlaca(ANTIGA_LIMPA, Formato.ANTIGA), 0.14f, 2f)
                .also { clarao(it, LARGURA / 2f, 470f, 230f, 190) },
        ),
        Cena(
            "noturna_sem_farol", "noite", ANTIGA_LIMPA, Formato.ANTIGA, 12.0,
            exposicao(comPlaca(ANTIGA_LIMPA, Formato.ANTIGA), 0.07f, 2f),
        ),

        // ── negativos: NÃO HÁ PLACA. Aceitar qualquer coisa aqui é fabricar ───
        Cena(
            "negativo_letreiro_de_rua", "negativo", null, null, 2.0,
            letreiro("RUA DAS FLORES", "1234 CENTRO"),
        ),
        Cena(
            "negativo_adesivo_de_modelo", "negativo", null, null, 2.0,
            letreiro("FLEX 1.6 16V", "TURBO AWD"),
        ),
        Cena(
            // **O caso que o `PlacaValidator` existe para recusar.** Oito caracteres
            // corridos contêm `ABC1234` dentro. A regra de token de 7 recusa; uma
            // janela deslizante sobre o texto todo consultaria uma placa que ninguém
            // tem. Ver o KDoc de `PlacaValidator.extrair`.
            "negativo_codigo_de_peca_8_caracteres", "negativo", null, null, 2.0,
            letreiro("COD ABC12345", "MONTADORA"),
        ),
        Cena(
            "negativo_chassi", "negativo", null, null, 2.0,
            letreiro("9BWZZZ377VT", "004251"),
        ),
        Cena(
            "negativo_sinalizacao_de_via", "negativo", null, null, 2.0,
            letreiro("PARE", "BR 324 KM 15"),
        ),
    )

    // ── Cena → Frame do DAT ───────────────────────────────────────────────────

    /**
     * Converte para I420 com **ruído de sensor** por cima do plano Y.
     *
     * O ruído não é enfeite: sem ele a imagem "de noite" é um degradê perfeito, e o
     * limiar do detector de texto vê uma borda limpa que sensor nenhum entrega. O
     * sigma vem da condição ([Cena.ruidoSigma]) e a semente é fixa, então a imagem é
     * a mesma em toda execução.
     */
    fun frameDe(cena: Cena, ts: Long): Frame {
        val bmp = cena.bitmap
        val pixels = IntArray(LARGURA * ALTURA)
        bmp.getPixels(pixels, 0, LARGURA, 0, 0, LARGURA, ALTURA)
        val r = Random(ts * 7919L)
        val y = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val vermelho = (p shr 16) and 0xFF
            val verde = (p shr 8) and 0xFF
            val azul = p and 0xFF
            val luma = (66 * vermelho + 129 * verde + 25 * azul + 128 shr 8) + 16
            val comRuido = luma + (r.nextGaussian() * cena.ruidoSigma).toInt()
            y[i] = comRuido.coerceIn(0, 255).toByte()
        }
        val croma = ByteArray(y.size / 2) { 128.toByte() }
        return Frame(LARGURA, ALTURA, ts, y + croma)
    }
}
