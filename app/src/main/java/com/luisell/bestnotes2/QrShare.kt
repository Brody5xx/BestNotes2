package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/* ============================================================
   QrShare.kt — 📲 PASAR UNA NOTA POR QR (sin internet).
   El teléfono que manda enseña códigos QR que van cambiando
   solos; el otro teléfono los mira con la cámara (Best Notes →
   menú → Recibir nota por QR) hasta juntarlos todos.
   Lleva el texto, las listas y las fotos (en tamaño pequeño).
   Los videos y audios no caben en un QR.
   ============================================================ */
object QrShare {
    private const val TAG = "BQ1"
    private const val CHUNK = 520

    /* ---------- empacar y desempacar ---------- */
    /** La nota en un texto comprimido (con las fotos pequeñas adentro). */
    fun pack(title: String, blocks: List<Block>): String {
        val keep = blocks.filter { it !is Block.Media }
        val body = blocksJson(keep)
        val photos = JSONObject()
        val names = keep.filterIsInstance<Block.Img>().flatMap { b -> listOf(b.file) + Pins.parse(b.pins).map { it.f }.filter { it.isNotEmpty() } }.distinct()
        // si son muchas fotos, cada una más pequeña
        val side = when { names.size <= 2 -> 640; names.size <= 5 -> 520; else -> 420 }
        for (n in names) {
            val bm = Thumbs.decode(File(Store.mediaDir, n), side) ?: continue
            val k = side.toFloat() / maxOf(bm.width, bm.height)
            val s = if (k < 1f) Bitmap.createScaledBitmap(bm, (bm.width * k).toInt().coerceAtLeast(1), (bm.height * k).toInt().coerceAtLeast(1), true) else bm
            val out = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 50, out)
            photos.put(n, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
        val o = JSONObject().put("v", 1).put("t", title).put("b", body).put("p", photos)
        val z = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(z).use { it.write(o.toString().toByteArray()) }
        return Base64.encodeToString(z.toByteArray(), Base64.NO_WRAP)
    }

    /** Los pedacitos: "BQ1:sesión:número:total:datos". */
    fun chunks(data: String): List<String> {
        val sid = java.lang.Long.toString(System.currentTimeMillis() % 1_679_616, 36).padStart(4, '0')
        val parts = data.chunked(CHUNK)
        return parts.mapIndexed { i, p -> "$TAG:$sid:$i:${parts.size}:$p" }
    }

    class Piece(val sid: String, val i: Int, val n: Int, val data: String)
    fun parsePiece(s: String): Piece? {
        val a = s.split(':', limit = 5)
        if (a.size != 5 || a[0] != TAG) return null
        val i = a[2].toIntOrNull() ?: return null; val n = a[3].toIntOrNull() ?: return null
        if (n <= 0 || i !in 0 until n) return null
        return Piece(a[1], i, n, a[4])
    }

    /** Guarda la nota recibida (con sus fotos) y te da su id. */
    fun unpack(data: String): NoteMeta {
        val raw = java.util.zip.GZIPInputStream(Base64.decode(data, Base64.NO_WRAP).inputStream()).use { it.readBytes() }
        val o = JSONObject(String(raw))
        var body = o.optString("b")
        val p = o.optJSONObject("p") ?: JSONObject()
        for (old in p.keys()) {
            val nf = "f" + newId() + ".jpg"
            File(Store.mediaDir, nf).writeBytes(Base64.decode(p.getString(old), Base64.NO_WRAP))
            body = body.replace(old, nf)
        }
        val blocks = blocksFromJson(body)
        // las fotos recibidas: se mide su tamaño de verdad
        for (b in blocks) if (b is Block.Img) {
            val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(File(Store.mediaDir, b.file).path, opt)
            if (opt.outWidth > 0) { b.w = opt.outWidth; b.h = opt.outHeight }
        }
        return Store.createNote(o.optString("t"), blocks)
    }

    /** Un código QR en blanco y negro (cada cuadrito = 1 pixel; se agranda al dibujarlo). */
    fun qr(text: String): Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, com.google.zxing.EncodeHintType.MARGIN to 2)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /* ---------- 📤 mandar ---------- */
    fun send(act: Activity, title: String, blocks: List<Block>) {
        val wait = act.dialog().setMessage("📲 " + L.t("qrPacking")).setCancelable(false).show()
        Loader.pool.execute {
            val frames = try { chunks(pack(title, blocks)).map { qr(it) } } catch (_: Throwable) { null }
            act.runOnUiThread { wait.dismiss(); if (frames == null || frames.isEmpty()) act.toast(L.t("vFail")) else showSender(act, frames, blocks.any { it is Block.Media }) }
        }
    }

    private fun showSender(act: Activity, frames: List<Bitmap>, droppedMedia: Boolean) {
        val d = blackFullDialog(act)
        d.window?.attributes = d.window?.attributes?.apply { screenBrightness = 1f }        // ☀️ pantalla al máximo: se lee mejor
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Color.BLACK); setPadding(act.dp(20), act.dp(46), act.dp(20), act.dp(30)) }
        root.addView(act.tv("📲 " + L.t("qrSendTitle"), 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        root.addView(act.tv(L.t("qrSendHow"), 14f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(8), 0, act.dp(18)); setLineSpacing(0f, 1.2f) })
        val card = FrameLayout(act).apply { background = roundBg(Color.WHITE, act.dp(26).toFloat()); setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14)); elevation = act.dp(10).toFloat() }
        val qv = QrView(act)
        card.addView(qv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val side = minOf(act.resources.displayMetrics.widthPixels - act.dp(40), act.dp(380))
        root.addView(card, LinearLayout.LayoutParams(side, side))
        val prog = act.tv("", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(16), 0, 0) }
        root.addView(prog)
        val dots = Dots(act, frames.size)
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(18)).apply { topMargin = act.dp(10) })
        if (droppedMedia) root.addView(act.tv("ℹ️ " + L.t("qrNoMedia"), 12.5f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(12), 0, 0) })
        root.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(act.tv(L.t("close"), 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(30), act.dp(12), act.dp(30), act.dp(12)); background = Glass.make(act, 22f, strong = true)
            setOnClickListener { d.dismiss() }
        })
        d.setContentView(root); d.show()
        Anim.popIn(card)
        // los códigos van pasando solos (y vuelven a empezar)
        var i = 0; var laps = 0
        val tick = object : Runnable { override fun run() {
            if (!d.isShowing) return
            qv.bm = frames[i]; dots.at = i
            prog.text = if (frames.size == 1) L.t("qrOne") else "${i + 1} / ${frames.size}" + (if (laps > 0) "  ·  🔁 ${laps + 1}" else "")
            i++; if (i >= frames.size) { i = 0; laps++ }
            qv.postDelayed(this, if (frames.size == 1) 1000 else 300)
        } }
        qv.post(tick)
    }

    /** El QR dibujado con cuadritos nítidos. */
    private class QrView(ctx: Context) : View(ctx) {
        var bm: Bitmap? = null
            set(v) { field = v; invalidate() }
        private val p = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        override fun onDraw(c: Canvas) { bm?.let { c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), p) } }
    }

    /** Las bolitas de avance (una por código; se pintan las que ya se mandaron o recibieron). */
    private class Dots(ctx: Context, val n: Int) : View(ctx) {
        var at = -1
            set(v) { field = v; invalidate() }
        val got = HashSet<Int>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            if (n <= 1) return
            val w = width.toFloat() / n; val r = minOf(w * 0.36f, height / 2.6f)
            for (k in 0 until n) {
                p.color = when { k == at -> T.accent; k in got -> 0xFF30D158.toInt(); else -> 0x33FFFFFF }
                c.drawCircle(w * k + w / 2, height / 2f, if (k == at) r * 1.4f else r, p)
            }
        }
    }

    /* ---------- 📥 recibir ---------- */
    fun receive(act: Activity, open: (NoteMeta) -> Unit) {
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 49); act.toast(L.t("qrCamAgain")); return
        }
        val d = blackFullDialog(act)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val cam = CameraBg(act).apply { clear() }
        root.addView(cam, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val finder = Finder(act)
        root.addView(finder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(act.glassBtn(Icons.CLOSE, 9f, Color.WHITE) { d.dismiss() }, FrameLayout.LayoutParams(act.dp(46), act.dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(36), act.dp(14), 0) })
        val info = act.tv("📷 " + L.t("qrPoint"), 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(act.dp(18), act.dp(10), act.dp(18), act.dp(10)); background = Glass.make(act, 20f, strong = true) }
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dp(60) })
        d.setContentView(root); d.show()

        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build())
        var sid: String? = null
        val got = HashMap<Int, String>()
        var total = 0
        var busy = false; var done = false
        fun finish() {
            done = true
            info.text = "📥 " + L.t("qrSaving")
            val all = (0 until total).joinToString("") { got[it] ?: "" }
            Loader.pool.execute {
                val m = try { unpack(all) } catch (_: Throwable) { null }
                act.runOnUiThread {
                    if (m == null) { act.toast(L.t("vFail")); d.dismiss(); return@runOnUiThread }
                    Store.changed(); Confetti.burst(act); act.toast("📥 " + L.t("qrGot"))
                    d.dismiss(); open(m)
                }
            }
        }
        val loop = object : Runnable { override fun run() {
            if (!d.isShowing || done) return
            if (!busy) {
                val w = 720; val h = (w * cam.height.toFloat() / maxOf(1, cam.width)).toInt().coerceAtLeast(1)
                val bm = cam.frame(w, h)
                if (bm != null) {
                    busy = true
                    scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bm, 0))
                        .addOnSuccessListener { codes ->
                            for (c in codes) {
                                val p = parsePiece(c.rawValue ?: continue) ?: run { if (!c.rawValue.isNullOrEmpty() && got.isEmpty()) info.text = "⚠️ " + L.t("qrNotOurs"); null } ?: continue
                                if (sid != p.sid) { sid = p.sid; got.clear(); total = p.n; finder.total = p.n; finder.got.clear() }
                                if (got.put(p.i, p.data) == null) {
                                    finder.got.add(p.i); finder.invalidate()
                                    act.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                                info.text = "📥 " + got.size + " / " + total
                                if (got.size == total && !done) finish()
                            }
                        }
                        .addOnCompleteListener { busy = false }
                }
            }
            root.postDelayed(this, 90)
        } }
        root.postDelayed(loop, 600)
        d.setOnDismissListener { done = true; try { scanner.close() } catch (_: Exception) {} }
    }

    /** El cuadro para apuntar (con esquinas que laten) y el anillo de lo que ya llegó. */
    private class Finder(ctx: Context) : View(ctx) {
        var total = 0
        val got = HashSet<Int>()
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val shade = Paint().apply { color = 0x88000000.toInt() }
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height) * 0.72f
            val l = (width - s) / 2; val t = (height - s) / 2 - 20 * d
            val r = RectF(l, t, l + s, t + s)
            // oscuro alrededor del cuadro
            c.drawRect(0f, 0f, width.toFloat(), r.top, shade); c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), shade)
            c.drawRect(0f, r.top, r.left, r.bottom, shade); c.drawRect(r.right, r.top, width.toFloat(), r.bottom, shade)
            // las esquinas
            val k = (0.5 + 0.5 * Math.sin(android.os.SystemClock.uptimeMillis() / 300.0)).toFloat()
            p.color = Color.WHITE; p.strokeWidth = 5 * d
            val e = 34 * d + 6 * d * k
            for ((x, y, sx, sy) in listOf(floatArrayOf(r.left, r.top, 1f, 1f), floatArrayOf(r.right, r.top, -1f, 1f), floatArrayOf(r.left, r.bottom, 1f, -1f), floatArrayOf(r.right, r.bottom, -1f, -1f)).map { Quad(it[0], it[1], it[2], it[3]) }) {
                c.drawLine(x, y, x + sx * e, y, p); c.drawLine(x, y, x, y + sy * e, p)
            }
            // 🟢 el anillo de avance (un pedacito por código)
            if (total > 1) {
                val ring = RectF(r.centerX() - s * 0.62f, r.centerY() - s * 0.62f, r.centerX() + s * 0.62f, r.centerY() + s * 0.62f)
                val seg = 360f / total
                p.strokeWidth = 6 * d
                for (i in 0 until total) { p.color = if (i in got) 0xFF30D158.toInt() else 0x33FFFFFF; c.drawArc(ring, -90f + i * seg + 0.6f, maxOf(0.5f, seg - 1.2f), false, p) }
            }
            postInvalidateOnAnimation()
        }
        private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
    }
}
