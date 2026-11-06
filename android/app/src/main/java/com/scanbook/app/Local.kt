package com.scanbook.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bloque de contenido: párrafo de texto (img == null) o imagen recortada de la página. */
private class Blk(
    var text: String,
    val fh: Float,
    val y: Int,
    val centered: Boolean,
    var first: Boolean,
    val img: ByteArray? = null,
    val iw: Int = 0,
    val ih: Int = 0,
    val frac: Float = 0f,
)

private class Fig(val x: Int, val y: Int, val w: Int, val h: Int)

private const val DOCX_TYPE =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private val PAGE_NUM = Regex("^\\s*[\\dIVXLCivxlc.\\-–—]+\\s*$")
private val SENT_END = Regex("[.!?:»”\"]$")

// ------------------------------------------------------------------ imagen

private fun decode(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = max(info.size.width, info.size.height)
        if (m > maxSide) {
            val s = maxSide.toFloat() / m
            dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
    }
}

private fun rotate(b: Bitmap, deg: Int): Bitmap {
    val m = Matrix()
    m.postRotate(deg.toFloat())
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

private fun letters(t: Text): Int {
    var n = 0
    for (b in t.textBlocks) for (l in b.lines) n += l.text.count { it.isLetterOrDigit() }
    return n
}

/** Letras leídas ponderadas por confianza: sirve para saber cuál giro de la foto es el correcto. */
private fun score(t: Text): Float {
    var s = 0f
    for (b in t.textBlocks) for (l in b.lines) s += l.text.count { it.isLetterOrDigit() } * l.confidence
    return s
}

private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
    val tmp = BooleanArray(m.size)
    val out = BooleanArray(m.size)
    for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) {
        for (dx in -r..r) { val xx = x + dx; if (xx in 0 until w) tmp[y * w + xx] = true }
    }
    for (y in 0 until h) for (x in 0 until w) if (tmp[y * w + x]) {
        for (dy in -r..r) { val yy = y + dy; if (yy in 0 until h) out[yy * w + x] = true }
    }
    return out
}

/** Detecta imágenes grandes (fotos, dibujos, logos, tablas con bordes) que no son texto. */
private fun findFigures(bmp: Bitmap, boxes: List<Rect>): List<Fig> {
    val gw = 320
    val gh = (bmp.height.toLong() * gw / bmp.width).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
    val px = IntArray(gw * gh)
    small.getPixels(px, 0, gw, 0, 0, gw, gh)
    if (small !== bmp) small.recycle()

    val lum = IntArray(px.size)
    val sat = IntArray(px.size)
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        lum[i] = (r * 299 + g * 587 + b * 114) / 1000
        sat[i] = max(r, max(g, b)) - min(r, min(g, b))
    }
    val sorted = lum.sortedArray()
    val bg = sorted[(sorted.size * 0.85).toInt().coerceAtMost(sorted.size - 1)]
    val mask = BooleanArray(px.size) { lum[it] < bg - 45 || sat[it] > 55 }

    val sx = gw.toFloat() / bmp.width
    for (r in boxes) {
        val x0 = ((r.left * sx).toInt() - 1).coerceAtLeast(0)
        val x1 = ((r.right * sx).toInt() + 1).coerceAtMost(gw - 1)
        val y0 = ((r.top * sx).toInt() - 1).coerceAtLeast(0)
        val y1 = ((r.bottom * sx).toInt() + 1).coerceAtMost(gh - 1)
        for (y in y0..y1) for (x in x0..x1) mask[y * gw + x] = false
    }

    val d = dilate(mask, gw, gh, 2)
    val seen = BooleanArray(d.size)
    val stack = IntArray(d.size)
    val figs = ArrayList<Fig>()
    for (s in d.indices) {
        if (!d[s] || seen[s]) continue
        var sp = 0
        stack[sp++] = s
        seen[s] = true
        var minX = gw; var maxX = 0; var minY = gh; var maxY = 0; var cnt = 0
        while (sp > 0) {
            val c = stack[--sp]
            val x = c % gw
            val y = c / gw
            cnt++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (x > 0 && d[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
            if (x < gw - 1 && d[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
            if (y > 0 && d[c - gw] && !seen[c - gw]) { seen[c - gw] = true; stack[sp++] = c - gw }
            if (y < gh - 1 && d[c + gw] && !seen[c + gw]) { seen[c + gw] = true; stack[sp++] = c + gw }
        }
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < gw * 0.12f || bh < gh * 0.06f) continue
        if (cnt.toFloat() / (bw * bh) < 0.5f) continue
        if (bw.toFloat() * bh > 0.9f * gw * gh) continue

        val inset = 2
        val x0 = (((minX + inset) / sx).toInt()).coerceIn(0, bmp.width - 1)
        val y0 = (((minY + inset) / sx).toInt()).coerceIn(0, bmp.height - 1)
        val x1 = ((((maxX - inset + 1) / sx).toInt())).coerceIn(x0 + 1, bmp.width)
        val y1 = ((((maxY - inset + 1) / sx).toInt())).coerceIn(y0 + 1, bmp.height)
        val rect = Rect(x0, y0, x1, y1)
        // una "figura" con mucho texto dentro es una página con fondo, no una imagen
        if (boxes.count { rect.contains(it.centerX(), it.centerY()) } >= 6) continue
        figs.add(Fig(x0, y0, x1 - x0, y1 - y0))
    }
    return figs.sortedBy { it.y }
}

// ------------------------------------------------------------------ texto

private fun merged(all: List<Blk>): Pair<List<Blk>, Float> {
    val sizes = all.filter { it.img == null }.map { it.fh }.sorted()
    val base = if (sizes.isEmpty()) 1f else sizes[sizes.size / 2]
    val items = ArrayList<Blk>()
    for (b in all) {
        val prev = items.lastOrNull()
        if (b.img == null && b.first && prev != null && prev.img == null &&
            prev.fh / base < 1.3f && !SENT_END.containsMatchIn(prev.text) &&
            b.text.firstOrNull()?.isLowerCase() == true
        ) {
            prev.text = if (prev.text.endsWith("-")) prev.text.dropLast(1) + b.text else prev.text + " " + b.text
        } else items.add(b)
    }
    return items to base
}

private fun xml(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun writeDocx(out: OutputStream, items: List<Blk>, base: Float) {
    val body = StringBuilder()
    val media = ArrayList<ByteArray>()
    val textW = 4249000L // ancho útil A5 en EMU
    for (b in items) {
        val img = b.img
        if (img != null) {
            media.add(img)
            val id = media.size
            var cx = (textW * min(1f, b.frac / 0.8f)).toLong().coerceAtLeast(500000L)
            var cy = cx * b.ih / b.iw
            if (cy > 6000000L) { cx = cx * 6000000L / cy; cy = 6000000L }
            body.append(
                """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Imagen $id"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="$id" name="img$id.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rId$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
            )
            continue
        }
        val r = b.fh / base
        if (r >= 1.35f && b.text.length < 120) {
            val lvl = if (r >= 1.8f) 0 else 1
            val sz = if (r >= 1.8f) 36 else 28
            body.append("<w:p><w:pPr><w:keepNext/><w:outlineLvl w:val=\"$lvl\"/><w:jc w:val=\"${if (b.centered) "center" else "left"}\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:b/><w:sz w:val=\"$sz\"/></w:rPr><w:t xml:space=\"preserve\">${xml(b.text)}</w:t></w:r></w:p>")
        } else {
            body.append("<w:p><w:pPr><w:jc w:val=\"${if (b.centered) "center" else "both"}\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">${xml(b.text)}</w:t></w:r></w:p>")
        }
    }

    val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    val doc = head + """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>""" +
        body + """<w:sectPr><w:pgSz w:w="8392" w:h="11907"/><w:pgMar w:top="850" w:right="850" w:bottom="850" w:left="850" w:header="400" w:footer="400" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    val types = head + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
    val rels = head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    val docRels = StringBuilder(head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
    for (i in 1..media.size) {
        docRels.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img$i.jpg"/>""")
    }
    docRels.append("</Relationships>")

    ZipOutputStream(out).use { z ->
        for ((name, data) in listOf(
            "[Content_Types].xml" to types,
            "_rels/.rels" to rels,
            "word/_rels/document.xml.rels" to docRels.toString(),
            "word/document.xml" to doc,
        )) {
            z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        media.forEachIndexed { i, bytes ->
            z.putNextEntry(ZipEntry("word/media/img${i + 1}.jpg")); z.write(bytes); z.closeEntry()
        }
    }
}

/** PDF en hojas blancas A5 desde el mismo texto e imágenes que el Word. Texto real y seleccionable. */
private fun writePdf(out: OutputStream, items: List<Blk>, base: Float) {
    val pw = 420
    val ph = 595
    val m = 36f
    val tw = (pw - 2 * m).toInt()
    val doc = PdfDocument()
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = m
    var num = 0

    fun newPage() {
        page?.let { doc.finishPage(it) }
        num++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, num).create())
        page = p
        canvas = p.canvas
        y = m
    }

    fun layout(text: String, tp: TextPaint, align: Layout.Alignment, justify: Boolean) =
        StaticLayout.Builder.obtain(text, 0, text.length, tp, tw)
            .setAlignment(align)
            .setLineSpacing(0f, 1.15f)
            .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .build()

    newPage()
    for (b in items) {
        val img = b.img
        if (img != null) {
            val bm = BitmapFactory.decodeByteArray(img, 0, img.size) ?: continue
            var w = tw * min(1f, b.frac / 0.8f)
            var h = w * b.ih / b.iw
            val maxH = ph - 2 * m
            if (h > maxH) { w *= maxH / h; h = maxH }
            if (y + h > ph - m) newPage()
            val left = m + (tw - w) / 2
            canvas!!.drawBitmap(bm, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 8f
            bm.recycle()
            continue
        }
        val r = b.fh / base
        val head = r >= 1.35f && b.text.length < 120
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = if (head) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SERIF
            textSize = if (head && r >= 1.8f) 20f else if (head) 15f else 10.5f
        }
        val align = if (b.centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val justify = !head && !b.centered
        val full = layout(b.text, tp, align, justify)
        if (head && y > ph - m - 90) newPage()

        var start = 0
        while (start < full.lineCount) {
            val avail = ph - m - y
            val top = full.getLineTop(start)
            var end = start
            while (end < full.lineCount && full.getLineBottom(end) - top <= avail) end++
            if (end == start) {
                if (y > m) { newPage(); continue }
                end = start + 1
            }
            val chunk = b.text.substring(full.getLineStart(start), full.getLineEnd(end - 1)).trimEnd()
            val sub = layout(chunk, tp, align, justify)
            val c = canvas!!
            c.save()
            c.translate(m, y)
            sub.draw(c)
            c.restore()
            y += sub.height
            start = end
            if (start < full.lineCount) newPage()
        }
        y += if (head) 10f else 6f
    }
    page?.let { doc.finishPage(it) }
    doc.writeTo(out)
    doc.close()
}

private fun newDownload(ctx: Context, name: String, mime: String): Uri {
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/ScanBook")
    }
    return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
}

// ------------------------------------------------------------------ proceso

/**
 * Modo local: por página -> corrige giro -> OCR (ML Kit) -> detecta y recorta imágenes ->
 * texto + imágenes en orden de lectura -> Word, y de ese mismo contenido -> PDF.
 */
suspend fun processLocal(
    ctx: Context,
    pages: List<Uri>,
    onMsg: (String) -> Unit,
): List<Pair<String, Uri>> = withContext(Dispatchers.IO) {
    val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    val all = ArrayList<Blk>()
    try {
        pages.forEachIndexed { i, uri ->
            onMsg("Leyendo página ${i + 1}/${pages.size}")
            var bmp = decode(ctx, uri)
            var res = rec.process(InputImage.fromBitmap(bmp, 0)).await()

            // ¿página volteada o de lado? prueba 90/180/270 y conserva el giro que lee mejor
            val s0 = score(res)
            val n0 = letters(res)
            if (s0 < 400f || (n0 > 0 && s0 / n0 < 0.6f)) {
                var bestS = s0
                var bestBmp: Bitmap? = null
                var bestRes = res
                for (deg in intArrayOf(90, 180, 270)) {
                    val rb = rotate(bmp, deg)
                    val r = rec.process(InputImage.fromBitmap(rb, 0)).await()
                    val sc = score(r)
                    if (sc > bestS) { bestBmp?.recycle(); bestBmp = rb; bestS = sc; bestRes = r } else rb.recycle()
                }
                val chosen = bestBmp
                if (chosen != null && bestS >= 40f && bestS > s0 * 1.6f) {
                    bmp.recycle(); bmp = chosen; res = bestRes
                } else chosen?.recycle()
            }

            val boxes = ArrayList<Rect>()
            for (b in res.textBlocks) for (l in b.lines) l.boundingBox?.let { boxes.add(it) }
            val figs = findFigures(bmp, boxes)

            val entries = ArrayList<Blk>()
            for (b in res.textBlocks) {
                val bb = b.boundingBox ?: continue
                if (figs.any { bb.centerX() in it.x..(it.x + it.w) && bb.centerY() in it.y..(it.y + it.h) }) continue
                var t = ""
                val hs = ArrayList<Int>()
                for (l in b.lines) {
                    val s = l.text.trim()
                    if (s.isEmpty()) continue
                    l.boundingBox?.let { hs.add(it.height()) }
                    t = if (t.endsWith("-") && s.first().isLowerCase()) t.dropLast(1) + s else (t + " " + s).trim()
                }
                if (t.isEmpty() || hs.isEmpty()) continue
                hs.sort()
                val edge = bb.bottom < bmp.height * 0.08 || bb.top > bmp.height * 0.92
                if (edge && PAGE_NUM.matches(t)) continue
                val centered = abs((bb.left + bb.right) / 2f - bmp.width / 2f) < bmp.width * 0.04f &&
                    bb.left > bmp.width * 0.1f
                entries.add(Blk(t, hs[hs.size / 2].toFloat(), bb.top, centered, false))
            }

            for (f in figs) {
                var crop = Bitmap.createBitmap(bmp, f.x, f.y, f.w, f.h)
                val mx = max(crop.width, crop.height)
                if (mx > 1100) {
                    val s = 1100f / mx
                    val sc = Bitmap.createScaledBitmap(
                        crop, (crop.width * s).toInt().coerceAtLeast(1), (crop.height * s).toInt().coerceAtLeast(1), true
                    )
                    crop.recycle()
                    crop = sc
                }
                val jb = ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                entries.add(Blk("", 0f, f.y, true, false, jb, crop.width, crop.height, f.w.toFloat() / bmp.width))
                crop.recycle()
            }

            entries.sortBy { it.y }
            if (i > 0 && entries.isNotEmpty() && entries[0].img == null) entries[0].first = true
            all.addAll(entries)
            bmp.recycle()
        }
    } finally {
        rec.close()
    }
    onMsg("Creando Word y PDF…")
    val (items, base) = merged(all)
    val docxUri = newDownload(ctx, "${stamp}_libro.docx", DOCX_TYPE)
    ctx.contentResolver.openOutputStream(docxUri)!!.buffered().use { writeDocx(it, items, base) }
    val pdfUri = newDownload(ctx, "${stamp}_libro.pdf", "application/pdf")
    ctx.contentResolver.openOutputStream(pdfUri)!!.buffered().use { writePdf(it, items, base) }
    listOf("libro.docx" to docxUri, "libro.pdf" to pdfUri)
}
