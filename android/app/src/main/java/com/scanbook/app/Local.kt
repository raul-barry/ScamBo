package com.scanbook.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
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

private class OcrLine(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

private class Blk(var text: String, val fh: Float, val y: Int, val centered: Boolean, var first: Boolean)

private const val DOCX_TYPE =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private val PAGE_NUM = Regex("^\\s*[\\dIVXLCivxlc.\\-–—]+\\s*$")

private fun decode(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = maxOf(info.size.width, info.size.height)
        if (m > maxSide) {
            val s = maxSide.toFloat() / m
            dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
    }
}

private fun pdfEsc(t: String) = buildString {
    for (c in t) when {
        c == '\\' || c == '(' || c == ')' -> { append('\\'); append(c) }
        c < ' ' -> append(' ')
        else -> append(c)
    }
}

/** Escritor mínimo de PDF: una foto JPEG por página + texto invisible (modo 3) encima. */
private class Pdf(private val out: OutputStream) {
    private var pos = 0L
    private val offs = HashMap<Int, Long>()
    private var n = 0

    private fun w(b: ByteArray) { out.write(b); pos += b.size }
    private fun w(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))
    private fun obj(num: Int, body: String) { offs[num] = pos; w("$num 0 obj\n$body\nendobj\n") }

    init {
        w("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    }

    fun page(jpg: ByteArray, iw: Int, ih: Int, lines: List<OcrLine>) {
        val pw = 420f
        val s = pw / iw
        val ph = ih * s
        val base = 4 + 3 * n
        val sb = StringBuilder("q $pw 0 0 $ph 0 0 cm /Im0 Do Q\nBT 3 Tr\n")
        val p = Paint().apply { typeface = Typeface.SANS_SERIF }
        for (l in lines) {
            val size = (l.h * s * 0.8f).coerceAtLeast(3f)
            p.textSize = size
            val nat = p.measureText(l.text).coerceAtLeast(1f)
            val tz = (l.w * s / nat * 100f).coerceIn(20f, 300f)
            val yy = ph - (l.y + l.h - l.h * 0.2f) * s
            sb.append("/F1 $size Tf $tz Tz 1 0 0 1 ${l.x * s} $yy Tm (${pdfEsc(l.text)}) Tj\n")
        }
        sb.append("ET")
        val content = sb.toString().toByteArray(charset("windows-1252"))

        obj(
            base,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                "/Resources << /XObject << /Im0 ${base + 2} 0 R >> /Font << /F1 3 0 R >> >> " +
                "/Contents ${base + 1} 0 R >>"
        )
        offs[base + 1] = pos
        w("${base + 1} 0 obj\n<< /Length ${content.size} >>\nstream\n"); w(content); w("\nendstream\nendobj\n")
        offs[base + 2] = pos
        w(
            "${base + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width $iw /Height $ih " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n"
        )
        w(jpg); w("\nendstream\nendobj\n")
        n++
    }

    fun finish() {
        obj(2, "<< /Type /Pages /Count $n /Kids [${(0 until n).joinToString(" ") { "${4 + 3 * it} 0 R" }}] >>")
        val total = 4 + 3 * n
        val xref = pos
        w("xref\n0 $total\n0000000000 65535 f \n")
        for (i in 1 until total) w(String.format(Locale.US, "%010d %05d n \n", offs[i] ?: 0L, 0))
        w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        out.flush()
    }
}

private fun xml(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun merged(all: List<Blk>): Pair<List<Blk>, Float> {
    val sizes = all.map { it.fh }.sorted()
    val base = if (sizes.isEmpty()) 1f else sizes[sizes.size / 2]

    // une párrafos partidos entre páginas
    val items = ArrayList<Blk>()
    for (b in all) {
        val prev = items.lastOrNull()
        if (b.first && prev != null && prev.fh / base < 1.3f &&
            !Regex("[.!?:»”\"]$").containsMatchIn(prev.text) &&
            b.text.firstOrNull()?.isLowerCase() == true
        ) {
            prev.text = if (prev.text.endsWith("-")) prev.text.dropLast(1) + b.text else prev.text + " " + b.text
        } else items.add(b)
    }
    return items to base
}

private fun writeDocx(out: OutputStream, items: List<Blk>, base: Float) {
    val body = StringBuilder()
    for (b in items) {
        val r = b.fh / base
        val jc = if (b.centered) "center" else "both"
        if (r >= 1.35f && b.text.length < 120) {
            val lvl = if (r >= 1.8f) 0 else 1
            val sz = if (r >= 1.8f) 36 else 28
            body.append("<w:p><w:pPr><w:keepNext/><w:outlineLvl w:val=\"$lvl\"/><w:jc w:val=\"${if (b.centered) "center" else "left"}\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:b/><w:sz w:val=\"$sz\"/></w:rPr><w:t xml:space=\"preserve\">${xml(b.text)}</w:t></w:r></w:p>")
        } else {
            body.append("<w:p><w:pPr><w:jc w:val=\"$jc\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">${xml(b.text)}</w:t></w:r></w:p>")
        }
    }

    val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    val doc = head + """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
        body + """<w:sectPr><w:pgSz w:w="8392" w:h="11907"/><w:pgMar w:top="850" w:right="850" w:bottom="850" w:left="850" w:header="400" w:footer="400" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    val types = head + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
    val rels = head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    ZipOutputStream(out).use { z ->
        for ((name, data) in listOf("[Content_Types].xml" to types, "_rels/.rels" to rels, "word/document.xml" to doc)) {
            z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
    }
}

private fun newDownload(ctx: Context, name: String, mime: String): Uri {
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/ScanBook")
    }
    return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
}

/** PDF de texto reflujo (hoja blanca A5, como el Word): texto real, seleccionable, con títulos y párrafos. */
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

/** Modo local: OCR en el teléfono (ML Kit) -> texto limpio -> Word, y de ese mismo texto -> PDF en hojas en blanco. */
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
            val bmp = decode(ctx, uri)
            val res = rec.process(InputImage.fromBitmap(bmp, 0)).await()
            val entries = ArrayList<Blk>()
            for (b in res.textBlocks) {
                val bb = b.boundingBox ?: continue
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
            entries.sortBy { it.y }
            if (i > 0 && entries.isNotEmpty()) entries[0].first = true
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
