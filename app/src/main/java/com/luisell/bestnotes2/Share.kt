package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/* ============================================================
   Share.kt — 📤 compartir una nota TAL COMO SE VE (con fotos,
   colores y dibujos) por Gmail, WhatsApp, mensajes…
   - Imagen: una foto larga de la nota
   - PDF: hojas tamaño carta, con el texto nítido (no es foto)
   - Solo texto / Mensaje de texto (SMS)
   ============================================================ */
object Share {
    /** Nombre de archivo sin signos raros. */
    fun fileName(title: String, ext: String): String {
        val t = title.trim().ifEmpty { L.t("untitled") }.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { "nota" }
        return "$t.$ext"
    }

    private fun out(a: Activity, name: String): File {
        val dir = File(a.cacheDir, "compartir").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        return File(dir, name)
    }

    private fun uri(a: Activity, f: File): Uri = FileProvider.getUriForFile(a, a.packageName + ".archivos", f)

    fun send(a: Activity, f: File, mime: String, subject: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri(a, f))
            .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, L.t("share")))
    }

    fun sendText(a: Activity, subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        a.startActivity(Intent.createChooser(i, L.t("share")))
    }

    fun sms(a: Activity, text: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", text)
        try { a.startActivity(i) } catch (_: Exception) { a.toast(L.t("cantOpen")) }
    }

    /** Texto de la nota, con ☐ / ☑ en los checklist y • en las viñetas. */
    fun text(title: String, blocks: List<Block>): String = buildString {
        if (title.isNotBlank()) { append(title.trim()); append("\n\n") }
        for (b in blocks) when (b) {
            is Block.Text -> { val t = runsPlain(b.runs); if (t.isNotEmpty()) { append(t); append('\n') } }
            is Block.ListB -> b.items.forEachIndexed { k, it ->
                append(when (b.kind) { "check" -> if (it.done) "☑ " else "☐ "; "ol" -> "${k + 1}. "; else -> "• " })
                append(runsPlain(it.runs)); append('\n')
            }
            is Block.Img -> if (b.beside.isNotEmpty()) { append(runsPlain(b.beside)); append('\n') }
            else -> {}
        }
    }.trimEnd()

    /** Dónde termina lo que tiene la nota (para no mandar espacio vacío abajo). */
    fun contentHeight(page: View, contentBottom: Int): Int = minOf(page.height, maxOf(contentBottom, 1))

    /**
     * Foto de la nota completa. [page] es la hoja (título + texto + dibujo) ya dibujada en pantalla.
     * Si la nota es muy larga se achica un poco para que el teléfono no se quede sin memoria.
     */
    fun image(a: Activity, page: View, height: Int, bg: Int, title: String): File {
        val w = page.width
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val total = height + pad * 2
        val k = minOf(1f, 14_000f / total)
        val bm = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (total * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(bg)
        c.scale(k, k)
        c.translate(0f, pad.toFloat())
        c.clipRect(0, 0, w, height)
        page.draw(c)
        val f = out(a, fileName(title, "jpg"))
        FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        bm.recycle()
        return f
    }

    /** PDF tamaño carta (8.5 × 11), con el texto nítido. */
    fun pdf(a: Activity, page: View, height: Int, bg: Int, title: String): File {
        val doc = PdfDocument()
        val pw = 612; val ph = 792                               // puntos (1/72 de pulgada)
        val margin = 24f
        val k = (pw - margin * 2) / page.width
        val sliceH = ((ph - margin * 2) / k).toInt()
        var y = 0; var n = 1
        while (y < height) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
            val c = p.canvas
            c.drawColor(bg)
            c.translate(margin, margin)
            c.scale(k, k)
            c.clipRect(0, 0, page.width, minOf(sliceH, height - y))
            c.translate(0f, -y.toFloat())
            page.draw(c)
            doc.finishPage(p)
            y += sliceH; n++
        }
        val f = out(a, fileName(title, "pdf"))
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
