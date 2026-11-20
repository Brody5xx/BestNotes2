package com.luisell.bestnotes2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/* ============================================================
   Export.kt — 📤 compartir VARIAS notas (o una) como:
   - PDF (hojas carta, letra nítida, con fotos)
   - Página con videos: un archivo .html que se abre en cualquier
     teléfono o computadora, con fotos, audios y videos adentro.
   ============================================================ */
object Export {
    class Item(val meta: NoteMeta, val blocks: List<Block>, val extra: JSONObject)

    /** Lee las notas (en segundo plano). Las bloqueadas no se mandan. */
    fun load(ids: Collection<String>): List<Item> = ids.mapNotNull { id ->
        val m = Store.meta(id) ?: return@mapNotNull null
        if (m.locked) return@mapNotNull null
        val (body, ex) = Store.readBody(id)
        Item(m, blocksFromJson(body), try { JSONObject(ex ?: "{}") } catch (_: Exception) { JSONObject() })
    }

    private fun titleRuns(it: Item): List<Run> =
        it.extra.optJSONArray("tr")?.let { a -> runsFromJson(a) }?.takeIf { r -> runsPlain(r).trim() == it.meta.title.trim() }
            ?: listOf(Run(it.meta.title, St.PLAIN.copy(color = it.meta.titleColor)))

    fun text(items: List<Item>): String = items.joinToString("\n\n— — —\n\n") { Share.text(it.meta.title, it.blocks) }

    fun sizeText(n: Long): String = when { n < 1048576 -> "${maxOf(1, n / 1024)} KB"; else -> String.format(L.locale, "%.1f MB", n / 1048576.0) }

    /** ¿Cabe por correo? */
    fun sizeAdvice(n: Long): String = when { n <= 20L * 1048576 -> L.t("sizeOk"); n <= 300L * 1048576 -> L.t("sizeBig"); else -> L.t("sizeHuge") }

    /* =========================================================
       🌐 PÁGINA CON VIDEOS (.html)
       ========================================================= */
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    private fun css(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun rgba(c: Int) = "rgba(${Color.red(c)},${Color.green(c)},${Color.blue(c)},${"%.2f".format(java.util.Locale.US, Color.alpha(c) / 255f)})"

    private fun runsHtml(runs: List<Run>): String = buildString {
        for (r in runs) {
            val st = r.st; val sb = StringBuilder()
            if (st.b) sb.append("font-weight:700;")
            if (st.i) sb.append("font-style:italic;")
            val dec = listOfNotNull(if (st.u) "underline" else null, if (st.s) "line-through" else null)
            if (dec.isNotEmpty()) sb.append("text-decoration:${dec.joinToString(" ")};")
            if (st.size > 0) sb.append("font-size:${st.size}px;")
            if (st.color != 0) sb.append("color:${css(st.color)};")
            if (st.bg != 0) sb.append("background:${rgba(st.bg)};border-radius:4px;")
            if (st.o != 0) sb.append("-webkit-text-stroke:0.6px ${css(st.o)};paint-order:stroke fill;text-shadow:0 0 1px ${css(st.o)};")
            val t = esc(r.text)
            if (st.href != null) append("<a href=\"${esc(st.href)}\" style=\"$sb\">$t</a>")
            else if (sb.isEmpty()) append(t) else append("<span style=\"$sb\">$t</span>")
        }
    }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"; "weba" -> "audio/webm"; "aac" -> "audio/aac"; else -> "application/octet-stream"
    }

    /** Escribe "data:…;base64,…" sin cargar el video entero en la memoria. */
    private fun dataUri(w: java.io.Writer, f: File) {
        w.write("data:${mime(f.name)};base64,"); w.flush()
        val bo = object : java.io.OutputStream() {
            override fun write(b: Int) { w.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) w.write(b[i].toInt()) }
        }
        android.util.Base64OutputStream(bo, Base64.NO_WRAP or Base64.NO_CLOSE).use { b -> f.inputStream().use { it.copyTo(b, 1 shl 16) } }
    }

    /** El dibujo de la nota como imagen PNG (para la página y el PDF). */
    private fun inkBitmap(it: Item, width: Int): Bitmap? {
        val strokes = Stroke.listFrom(it.extra.optJSONArray("ink")); if (strokes.isEmpty()) return null
        val W = width.toFloat(); val h = Stroke.bottom(strokes, W).toInt().coerceIn(1, 6000)
        val bm = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); for (s in strokes) Stroke.draw(c, s, W)
        return bm
    }

    fun page(ctx: Context, items: List<Item>, out: File) {
        java.io.BufferedWriter(java.io.OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8), 1 shl 16).use { w ->
            val title = if (items.size == 1) items[0].meta.title.ifBlank { L.t("untitled") } else "Best Notes · ${items.size}"
            w.write("""<!DOCTYPE html><html lang="${L.lang}"${if (L.isRtl) " dir=\"rtl\"" else ""}><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>
body{margin:0;background:linear-gradient(160deg,#1c1c22,#101014);color:#fff;font:17px/1.5 -apple-system,Roboto,Segoe UI,sans-serif;padding:16px}
.n{max-width:720px;margin:0 auto 22px;padding:18px 20px;border-radius:24px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.14)}
h1{font-size:24px;margin:0 0 10px}.d{color:rgba(255,255,255,.55);font-size:13px;margin-bottom:12px}
img,video{max-width:100%;border-radius:14px;display:block;margin:10px 0}audio{width:100%;margin:8px 0}
ul,ol{padding-left:24px;margin:6px 0}li.ck{list-style:none;margin-left:-22px}li.done{opacity:.55;text-decoration:line-through}
p{margin:6px 0;white-space:normal}a{color:#64d2ff}.f{text-align:center;color:rgba(255,255,255,.4);font-size:12px;margin:24px 0}
</style></head><body>""")
            val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, L.locale)
            for (it in items) {
                w.write("<div class=\"n\">")
                if (it.meta.title.isNotBlank()) w.write("<h1>${runsHtml(titleRuns(it))}</h1>")
                w.write("<div class=\"d\">${esc(df.format(java.util.Date(it.meta.updated)))}</div>")
                inkBitmap(it, 900)?.let { bm ->
                    val bo = java.io.ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.PNG, 100, bo); bm.recycle()
                    w.write("<img src=\"data:image/png;base64,${Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)}\">")
                }
                for (b in Share.noTitleEcho(it.meta.title, it.blocks)) when (b) {
                    is Block.Text -> if (runsPlain(b.runs).isNotBlank()) w.write("<p>${runsHtml(b.runs)}</p>")
                    is Block.ListB -> {
                        val tag = if (b.kind == "ol") "ol" else "ul"
                        w.write("<$tag>")
                        for (x in b.items) {
                            val cls = if (b.kind == "check") (if (x.done) " class=\"ck done\"" else " class=\"ck\"") else ""
                            val mark = if (b.kind == "check") (if (x.done) "☑ " else "☐ ") else ""
                            w.write("<li$cls>$mark${runsHtml(x.runs)}</li>")
                        }
                        w.write("</$tag>")
                    }
                    is Block.Img -> {
                        val f = File(Store.mediaDir, b.file)
                        if (f.isFile) { w.write("<img src=\""); dataUri(w, f); w.write("\" style=\"width:${b.pct.toInt()}%${if (b.ar > 0f) ";aspect-ratio:1/" + b.ar + ";object-fit:fill" else ""}\">") }
                        if (b.beside.isNotEmpty()) w.write("<p>${runsHtml(b.beside)}</p>")
                    }
                    is Block.Media -> {
                        val f = File(Store.mediaDir, b.file)
                        if (f.isFile) {
                            if (b.kind == "video") { w.write("<video controls playsinline preload=\"metadata\"${if (b.muted) " muted" else ""} src=\""); dataUri(w, f); w.write("\"></video>") }
                            else { w.write("<audio controls preload=\"metadata\" src=\""); dataUri(w, f); w.write("\"></audio>") }
                        }
                    }
                }
                w.write("</div>")
            }
            w.write("<div class=\"f\">Best Notes</div></body></html>")
        }
    }

    /* =========================================================
       📄 PDF de varias notas (letra nítida, con fotos)
       ========================================================= */
    fun pdf(ctx: Context, items: List<Item>, out: File) {
        val doc = PdfDocument()
        val pw = 612; val ph = 792; val m = 40f; val cw = pw - m * 2
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f; var n = 0
        fun newPage() {
            page?.let { doc.finishPage(it) }
            n++; page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create()); c = page!!.canvas; y = m
        }
        fun need(h: Float) { if (c == null || y + h > ph - m) newPage() }
        fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF1C1C1E.toInt()) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        /** Texto con estilos; si no cabe en la hoja, sigue en la otra (renglón por renglón). */
        fun drawRuns(runs: List<Run>, tp: TextPaint, indent: Float = 0f, prefix: String = "") {
            val sb = Rich.build(if (prefix.isEmpty()) runs else listOf(Run(prefix)) + runs)
            sb.getSpans(0, sb.length, SizeS::class.java).forEach { sp ->
                val a = sb.getSpanStart(sp); val b = sb.getSpanEnd(sp); sb.removeSpan(sp)
                sb.setSpan(AbsoluteSizeSpan((sp.size * 0.72f).toInt().coerceAtLeast(6)), a, b, 0)
            }
            // letra blanca en papel blanco no se ve: se pone oscura
            sb.getSpans(0, sb.length, ColorS::class.java).forEach { sp ->
                val col = sp.foregroundColor
                if (Color.red(col) > 225 && Color.green(col) > 225 && Color.blue(col) > 225) {
                    val a = sb.getSpanStart(sp); val b = sb.getSpanEnd(sp); sb.removeSpan(sp); sb.setSpan(ForegroundColorSpan(0xFF1C1C1E.toInt()), a, b, 0)
                }
            }
            sb.getSpans(0, sb.length, LinkS::class.java).forEach { sb.removeSpan(it) }
            val lay = StaticLayout.Builder.obtain(sb, 0, sb.length, tp, (cw - indent).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            for (line in 0 until lay.lineCount) {
                val top = lay.getLineTop(line).toFloat(); val bot = lay.getLineBottom(line).toFloat()
                need(bot - top)
                val cv = c!!
                cv.save(); cv.translate(m + indent, y - top); cv.clipRect(0f, top, cw, bot); lay.draw(cv); cv.restore()
                y += bot - top
            }
        }
        fun drawBitmap(bm: Bitmap, maxH: Float, wPct: Float = 100f, ar: Float = 0f) {
            val k = if (ar > 0f) ar else bm.height.toFloat() / bm.width       // estirada: con su forma nueva
            var w = cw * (wPct / 100f).coerceIn(0.2f, 1f); var h = w * k
            if (h > maxH) { h = maxH; w = h / k }
            need(h + 8); y += 4
            c!!.drawBitmap(bm, null, android.graphics.RectF(m, y, m + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 6
        }
        fun loadBm(f: File, maxSide: Int): Bitmap? {
            if (!f.isFile) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
        val body = paint(12f); val muted = paint(9.5f, color = 0xFF8E8E93.toInt())
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, L.locale)
        for ((k, it) in items.withIndex()) {
            if (k > 0) { need(30f); y += 10; c!!.drawLine(m, y, pw - m, y, Paint().apply { color = 0x22000000 }); y += 18 }
            if (it.meta.title.isNotBlank()) drawRuns(titleRuns(it), paint(18f, bold = true))
            drawRuns(listOf(Run(df.format(java.util.Date(it.meta.updated)))), muted); y += 6
            inkBitmap(it, 1000)?.let { bm -> drawBitmap(bm, 300f); bm.recycle() }
            for (b in Share.noTitleEcho(it.meta.title, it.blocks)) when (b) {
                is Block.Text -> if (runsPlain(b.runs).isNotBlank()) { drawRuns(b.runs, body); y += 4 }
                is Block.ListB -> { b.items.forEachIndexed { i, x ->
                    val pre = when (b.kind) { "check" -> if (x.done) "☑  " else "☐  "; "ol" -> "${i + 1}.  "; else -> "•  " }
                    drawRuns(x.runs, body, 10f, pre)
                }; y += 4 }
                is Block.Img -> { loadBm(File(Store.mediaDir, b.file), 1400)?.let { bm -> drawBitmap(bm, 360f, b.pct, b.ar); bm.recycle() }; if (b.beside.isNotEmpty()) drawRuns(b.beside, body) }
                is Block.Media -> {
                    if (b.kind == "video") loadBm(File(Store.mediaDir, MediaFiles.thumbName(b.file)), 900)?.let { bm -> drawBitmap(bm, 220f, 60f); bm.recycle() }
                    drawRuns(listOf(Run((if (b.kind == "video") "🎬 " + L.t("video") else "🎙️ " + L.t("audio")) + " · " + MediaFiles.timeText(b.dur), St.PLAIN.copy(i = true))), muted)
                    y += 4
                }
            }
        }
        if (c == null) newPage()
        page?.let { doc.finishPage(it) }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}

/** 📤 El panel para compartir una o varias notas. */
fun android.app.Activity.shareNotes(ids: List<String>, onDone: () -> Unit = {}) {
    val many = ids.size > 1
    fun run(kind: String) {
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(10)) }
        box.addView(android.widget.ProgressBar(this))
        box.addView(tv(L.t("working"), 15f, T.text).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        val d = dialog().setView(box).setCancelable(false).show()
        Thread {
            val items = try { Export.load(ids) } catch (_: Throwable) { emptyList() }
            val name = if (items.size == 1) items[0].meta.title else "Best Notes (${items.size})"
            val dir = File(cacheDir, "compartir").apply { mkdirs() }
            val res: Any? = try {
                when (kind) {
                    "text", "sms" -> Export.text(items)
                    "pdf" -> File(dir, Share.fileName(name, "pdf")).also { Export.pdf(this, items, it) }
                    else -> File(dir, Share.fileName(name, "html")).also { Export.page(this, items, it) }
                }
            } catch (_: Throwable) { null }
            runOnUiThread {
                d.dismiss(); onDone()
                when {
                    items.isEmpty() || res == null -> toast(L.t(if (items.isEmpty()) "shareLocked" else "vFail"))
                    kind == "text" -> Share.sendText(this, name, res as String)
                    kind == "sms" -> Share.sms(this, res as String)
                    kind == "pdf" -> Share.send(this, res as File, "application/pdf", name)
                    else -> {
                        val f = res as File
                        Sheet(this, "🌐 " + L.t("sharePage"), L.t("sizeSend", "s" to Export.sizeText(f.length())))
                            .custom(tv(Export.sizeAdvice(f.length()), 14f, T.text).apply { gravity = android.view.Gravity.CENTER; setPadding(dp(18), dp(2), dp(18), dp(12)) })
                            .quick(Icons.SEND, L.t("share"), Sheet.BLUE) { Share.send(this, f, "text/html", name) }
                            .show()
                    }
                }
            }
        }.start()
    }
    Sheet(this, "📤 " + L.t("share"), if (many) L.t("shareMany", "n" to ids.size) else L.t("shareHint"))
        .item(MoreIcons.VIDEO, L.t("sharePage"), Sheet.ORANGE, L.t("sharePageSub")) { run("page") }
        .item(Icons.BOOK, L.t("sharePdf"), Sheet.RED, "Gmail, Outlook…") { run("pdf") }
        .item(MoreIcons.TEXT_T, L.t("shareText"), Sheet.BLUE) { run("text") }
        .item(Icons.BUBBLE, L.t("shareSms"), Sheet.GREEN) { run("sms") }
        .show()
}
