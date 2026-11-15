package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/* ============================================================
   ScanActivity.kt — 📄 ESCANEAR UN DOCUMENTO.
   1) La app busca sola las 4 esquinas del papel (puedes moverlas).
   2) "Siguiente": lo endereza como si lo hubieras puesto en un escáner.
   3) Escoges cómo se ve: Documento (blanco y negro, bien claro),
      Color (más vivo) u Original. "Guardar" lo pone en la nota.
   ============================================================ */
class ScanActivity : Activity() {
    companion object { const val FILE = "archivo"; const val OUT = "nuevo"; const val OUT_W = "w"; const val OUT_H = "h" }

    private lateinit var file: String
    private var full: Bitmap? = null
    private lateinit var quad: QuadView
    private lateinit var preview: ImageView
    private lateinit var stage: FrameLayout
    private lateinit var bottom: LinearLayout
    private var flat: Bitmap? = null
    private var shown: Bitmap? = null
    private var mode = "doc"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = intent.getStringExtra(FILE) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        root.setBackgroundColor(0xFF000000.toInt()); window.statusBarColor = 0xFF000000.toInt()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        top.addView(glassBtn(Icons.BACK) { finish() }.apply { layoutParams = lp(dp(44), dp(44)) })
        top.addView(tv("📄 " + L.t("scanTitle"), 18f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        top.addView(View(this), lp(dp(44), dp(44)))
        root.addView(top, lp(MATCH, WRAP))
        stage = FrameLayout(this)
        quad = QuadView(this)
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        stage.addView(quad, FrameLayout.LayoutParams(MATCH, MATCH)); stage.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(stage, lp(MATCH, 0, 1f))
        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(14)) }
        root.addView(bottom, lp(MATCH, WRAP))
        setContentView(root)
        stepCorners()
        Loader.pool.execute {
            val bm = Thumbs.decode(File(Store.mediaDir, file), 2400)
            val pts = bm?.let { Scan.findCorners(it) }
            runOnUiThread { if (bm == null) finish() else { full = bm; quad.setImage(bm, pts) } }
        }
    }

    private fun pill(text: String, primary: Boolean, on: () -> Unit) = tv(text, 16f, if (primary) 0xFF16161A.toInt() else 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
        pressBg(if (primary) 0xFFFFFFFF.toInt() else 0, 24f, if (primary) 0 else 0x55FFFFFF)
        setOnClickListener { v -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start(); on() }
    }

    private fun stepCorners() {
        quad.visibility = View.VISIBLE; preview.visibility = View.GONE
        bottom.removeAllViews()
        bottom.addView(tv(L.t("scanHint"), 13.5f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(10)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill(L.t("scanAll"), false) { quad.all() }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
        row.addView(pill(L.t("scanNext") + "  →", true) { flatten() }, lp(0, WRAP, 1.4f).apply { marginStart = dp(6) })
        bottom.addView(row)
    }

    private fun flatten() {
        val bm = full ?: return
        val pts = quad.points()
        bottom.alpha = 0.5f
        Loader.pool.execute {
            val f = Scan.warp(bm, pts)
            runOnUiThread { bottom.alpha = 1f; flat = f; stepFilter() }
        }
    }

    private fun stepFilter() {
        quad.visibility = View.GONE; preview.visibility = View.VISIBLE
        preview.alpha = 0f; preview.scaleX = 0.9f; preview.scaleY = 0.9f
        preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        bottom.removeAllViews()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        val opts = listOf("doc" to "📄 " + L.t("scanDoc"), "color" to "🌈 " + L.t("scanColor"), "orig" to "📷 " + L.t("scanOrig"))
        fun paint() { for (k in 0 until chips.childCount) (chips.getChildAt(k) as TextView).let { c -> val on = c.tag == mode; c.setTextColor(if (on) 0xFF16161A.toInt() else 0xFFFFFFFF.toInt()); c.pressBg(if (on) 0xFFFFFFFF.toInt() else 0, 18f, if (on) 0 else 0x55FFFFFF) } }
        for ((k, label) in opts) chips.addView(tv(label, 14f, 0xFFFFFFFF.toInt(), true).apply {
            tag = k; setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { mode = k; paint(); applyMode() }
        }, lp(WRAP, WRAP).apply { marginStart = dp(4); marginEnd = dp(4) })
        paint()
        bottom.addView(chips)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("←  " + L.t("scanCorners"), false) { stepCorners() }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
        row.addView(pill("✓  " + L.t("save"), true) { save() }, lp(0, WRAP, 1.4f).apply { marginStart = dp(6) })
        bottom.addView(row)
        applyMode()
    }

    private fun applyMode() {
        val f = flat ?: return
        Loader.pool.execute {
            val r = Scan.filter(f, mode)
            runOnUiThread { shown = r; preview.setImageBitmap(r) }
        }
    }

    private fun save() {
        val bm = shown ?: flat ?: return
        bottom.alpha = 0.5f
        Loader.pool.execute {
            val r = try { Photos.saveNew(bm) } catch (_: Throwable) { null }
            runOnUiThread {
                if (r != null) setResult(RESULT_OK, Intent().putExtra(OUT, r.file).putExtra(OUT_W, r.w).putExtra(OUT_H, r.h))
                finish()
            }
        }
    }

    /** Muestra la foto con las 4 esquinas que se pueden mover. */
    @SuppressLint("ViewConstructor")
    class QuadView(ctx: Context) : View(ctx) {
        private var bm: Bitmap? = null
        private val img = RectF()
        private val pts = Array(4) { PointF() }        // en % de la foto (0..1): arriba-izq, arriba-der, abajo-der, abajo-izq
        private var drag = -1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var appear = 0f

        fun setImage(b: Bitmap, found: Array<PointF>?) {
            bm = b
            if (found != null) for (i in 0..3) pts[i].set(found[i]) else all(false)
            fit()
            // las esquinas "llegan" con una animacioncita
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 600; interpolator = android.view.animation.OvershootInterpolator(1.4f); addUpdateListener { appear = it.animatedValue as Float; invalidate() } }.start()
        }

        fun all(redraw: Boolean = true) {
            val m = 0.03f
            pts[0].set(m, m); pts[1].set(1 - m, m); pts[2].set(1 - m, 1 - m); pts[3].set(m, 1 - m)
            if (redraw) invalidate()
        }

        fun points(): Array<PointF> = Array(4) { PointF(pts[it].x, pts[it].y) }

        private fun fit() {
            val b = bm ?: return
            if (width == 0) return
            val pad = dp(28).toFloat()
            val k = minOf((width - pad * 2) / b.width, (height - pad * 2) / b.height)
            val w = b.width * k; val h = b.height * k
            img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

        private fun sx(i: Int) = img.left + pts[i].x * img.width()
        private fun sy(i: Int) = img.top + pts[i].y * img.height()

        override fun onDraw(c: Canvas) {
            val b = bm ?: return
            c.drawBitmap(b, null, img, p)
            val cx = img.centerX(); val cy = img.centerY()
            fun ax(i: Int) = cx + (sx(i) - cx) * appear
            fun ay(i: Int) = cy + (sy(i) - cy) * appear
            val path = Path().apply { moveTo(ax(0), ay(0)); for (i in 1..3) lineTo(ax(i), ay(i)); close() }
            // oscuro afuera del papel
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt()
            c.drawPath(Path().apply { addRect(img, Path.Direction.CW); addPath(path); fillType = Path.FillType.EVEN_ODD }, p)
            p.color = 0x334FC3F7; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.color = 0xFF4FC3F7.toInt(); p.strokeWidth = dp(3).toFloat(); p.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, p)
            for (i in 0..3) {
                val r = dp(if (i == drag) 16 else 12).toFloat()
                p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawCircle(ax(i), ay(i), r, p)
                p.style = Paint.Style.STROKE; p.color = 0xFF4FC3F7.toInt(); p.strokeWidth = dp(3).toFloat(); c.drawCircle(ax(i), ay(i), r, p)
            }
            // lupa: cuando mueves una esquina se ve de cerca
            if (drag >= 0) {
                val r = dp(52).toFloat(); val zx = sx(drag); val zy = sy(drag)
                val mx = if (zx < width / 2f) width - r - dp(16) else r + dp(16); val my = r + dp(16)
                c.save()
                c.clipPath(Path().apply { addCircle(mx, my, r, Path.Direction.CW) })
                c.drawColor(0xFF000000.toInt())
                c.translate(mx - zx * 2.2f, my - zy * 2.2f); c.scale(2.2f, 2.2f)
                c.drawBitmap(b, null, img, p)
                c.restore()
                p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = dp(3).toFloat(); c.drawCircle(mx, my, r, p)
                p.color = 0xFF4FC3F7.toInt(); p.strokeWidth = dp(2).toFloat()
                c.drawLine(mx - dp(10), my, mx + dp(10), my, p); c.drawLine(mx, my - dp(10), mx, my + dp(10), p)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = (0..3).minByOrNull { Math.hypot((e.x - sx(it)).toDouble(), (e.y - sy(it)).toDouble()) } ?: -1
                    if (drag >= 0 && Math.hypot((e.x - sx(drag)).toDouble(), (e.y - sy(drag)).toDouble()) > dp(60)) drag = -1
                    invalidate(); return drag >= 0
                }
                MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                    pts[drag].set(((e.x - img.left) / img.width()).coerceIn(0f, 1f), ((e.y - img.top) / img.height()).coerceIn(0f, 1f))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; invalidate() }
            }
            return true
        }
    }
}

/** Las cuentas del escáner (encontrar el papel, enderezarlo y aclararlo). */
object Scan {
    /** Busca el papel: la mancha clara más grande de la foto. Devuelve las 4 esquinas (en % de la foto) o null. */
    fun findCorners(bm: Bitmap): Array<PointF>? {
        val k = 180f / maxOf(bm.width, bm.height)
        val w = maxOf(8, (bm.width * k).toInt()); val h = maxOf(8, (bm.height * k).toInt())
        val small = Bitmap.createScaledBitmap(bm, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100 }
        // umbral de Otsu (separa lo claro de lo oscuro)
        val hist = IntArray(256); lum.forEach { hist[it]++ }
        val total = lum.size; var sum = 0.0; for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var th = 128
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; th = i }
        }
        // la mancha clara más grande
        val lab = IntArray(w * h); var bestId = 0; var bestN = 0; var id = 0
        val q = IntArray(w * h)
        for (s in 0 until w * h) {
            if (lum[s] <= th || lab[s] != 0) continue
            id++; var head = 0; var tail = 0; q[tail++] = s; lab[s] = id; var n = 0
            while (head < tail) {
                val v = q[head++]; n++
                val x = v % w; val y = v / w
                if (x > 0 && lab[v - 1] == 0 && lum[v - 1] > th) { lab[v - 1] = id; q[tail++] = v - 1 }
                if (x < w - 1 && lab[v + 1] == 0 && lum[v + 1] > th) { lab[v + 1] = id; q[tail++] = v + 1 }
                if (y > 0 && lab[v - w] == 0 && lum[v - w] > th) { lab[v - w] = id; q[tail++] = v - w }
                if (y < h - 1 && lab[v + w] == 0 && lum[v + w] > th) { lab[v + w] = id; q[tail++] = v + w }
            }
            if (n > bestN) { bestN = n; bestId = id }
        }
        val frac = bestN.toFloat() / (w * h)
        if (frac < 0.12f || frac > 0.97f) return null
        // esquinas: los puntos más extremos de la mancha
        var tl = Int.MAX_VALUE; var br = Int.MIN_VALUE; var tr = Int.MIN_VALUE; var bl = Int.MAX_VALUE
        val c = Array(4) { PointF() }
        for (v in 0 until w * h) if (lab[v] == bestId) {
            val x = v % w; val y = v / w
            if (x + y < tl) { tl = x + y; c[0].set(x.toFloat(), y.toFloat()) }
            if (x - y > tr) { tr = x - y; c[1].set(x.toFloat(), y.toFloat()) }
            if (x + y > br) { br = x + y; c[2].set(x.toFloat(), y.toFloat()) }
            if (x - y < bl) { bl = x - y; c[3].set(x.toFloat(), y.toFloat()) }
        }
        return Array(4) { PointF((c[it].x + 0.5f) / w, (c[it].y + 0.5f) / h) }
    }

    /** Endereza el papel (lo que está entre las 4 esquinas queda rectangular). */
    fun warp(bm: Bitmap, p: Array<PointF>): Bitmap {
        val q = Array(4) { PointF(p[it].x * bm.width, p[it].y * bm.height) }
        fun d(a: PointF, b: PointF) = Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        var ow = maxOf(d(q[0], q[1]), d(q[3], q[2])); var oh = maxOf(d(q[0], q[3]), d(q[1], q[2]))
        val k = minOf(1f, 2400f / maxOf(ow, oh)); ow *= k; oh *= k
        val out = Bitmap.createBitmap(maxOf(1, ow.toInt()), maxOf(1, oh.toInt()), Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y), 0,
            floatArrayOf(0f, 0f, ow, 0f, ow, oh, 0f, oh), 0, 4)
        Canvas(out).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(bm, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        return out
    }

    /** "doc" = blanco y negro bien claro (quita sombras); "color" = más vivo; "orig" = igual. */
    fun filter(src: Bitmap, mode: String): Bitmap {
        if (mode == "orig") return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        // el "fondo" (la luz del papel): la foto chiquita y borrosa
        val bw = maxOf(1, w / 24); val bh = maxOf(1, h / 24)
        val bg = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, bw, bh, true), w, h, true)
        val bp = IntArray(w * h); bg.getPixels(bp, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]; val b = bp[i]
            if (mode == "doc") {
                val l = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100
                val bl = maxOf(40, ((b shr 16 and 255) * 30 + (b shr 8 and 255) * 59 + (b and 255) * 11) / 100)
                var v = l * 255f / bl                    // el papel queda blanco aunque tenga sombra
                v = ((v - 255f) * 1.8f + 255f)           // las letras más negras
                val g = v.toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            } else {
                fun ch(s: Int, sh: Int, bgc: Int): Int { val v = ((s shr sh and 255) * 255f / maxOf(40, bgc shr sh and 255)); return (((v - 255f) * 1.25f + 255f).toInt()).coerceIn(0, 255) }
                px[i] = (0xFF shl 24) or (ch(c, 16, b) shl 16) or (ch(c, 8, b) shl 8) or ch(c, 0, b)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (mode == "color") {
            val o2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(o2).drawBitmap(out, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) }) })
            return o2
        }
        return out
    }
}
