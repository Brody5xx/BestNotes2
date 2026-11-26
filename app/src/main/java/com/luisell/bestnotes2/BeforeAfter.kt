package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream

/* ============================================================
   BeforeAfter.kt — ↔️ ANTES Y DESPUÉS: dos fotos una encima de
   la otra; deslizas la rayita con el dedo para ver cómo estaba
   y cómo quedó. Se puede poner en la nota (lado a lado, con
   letreros) o compartir.
   ============================================================ */
object BeforeAfter {
    /** [pins] y [stamp] = lo que va ENCIMA de la foto de después (los 🗺️ pines y el sello), para que se vea todo. */
    fun show(act: Activity, before: String, after: String, pins: String = "", stamp: String = "", onInsert: (file: String, w: Int, h: Int) -> Unit) {
        val d = blackFullDialog(act)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val cv = CompareView(act).apply { afterPins = Pins.parse(pins); afterStamp = stamp }
        root.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(act.glassBtn(Icons.CLOSE, 9f, Color.WHITE) { d.dismiss() }, FrameLayout.LayoutParams(act.dp(46), act.dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(36), act.dp(14), 0) })
        root.addView(act.tv("↔️ " + L.t("baTitle"), 16f, Color.WHITE, true).apply { setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9)); background = Glass.make(act, 20f, strong = true) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = act.dp(40) })
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8)); background = Glass.make(act, 26f, strong = true) }
        fun pill(label: String, color: Int, go: () -> Unit) = bar.addView(act.tv(label, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11)); background = roundBg(color, act.dp(20).toFloat())
            setOnClickListener { v -> Anim.bump(v); go() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(4), 0, act.dp(4), 0) })
        pill(L.t("baSwap"), 0x33FFFFFF) { cv.swap() }
        pill(L.t("share"), 0xFF3A7BFF.toInt()) { render(act, cv) { f, _, _ -> share(act, f) } }
        pill(L.t("ocrPutShort"), 0xFF2FA866.toInt()) { render(act, cv) { f, w, h -> onInsert(f, w, h); d.dismiss() } }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(act.dp(12), 0, act.dp(12), act.dp(30)) })
        d.setContentView(root); d.show()
        Loader.pool.execute {
            val dm = act.resources.displayMetrics; val max = maxOf(dm.widthPixels, dm.heightPixels)
            val a = Thumbs.decode(File(Store.mediaDir, before), max); val b = Thumbs.decode(File(Store.mediaDir, after), max)
            act.runOnUiThread { if (a == null || b == null) { act.toast(L.t("cantOpen")); d.dismiss() } else cv.set(a, b) }
        }
    }

    /** Las dos fotos lado a lado, con sus letreros, en una sola foto. */
    private fun render(act: Activity, cv: CompareView, done: (String, Int, Int) -> Unit) {
        val a = cv.a ?: return; val b = cv.b ?: return
        Loader.pool.execute {
            val r = try {
                val h = 1200; val gap = 16
                val wa = (a.width * h / a.height.toFloat()).toInt().coerceAtMost(1600); val wb = (b.width * h / b.height.toFloat()).toInt().coerceAtMost(1600)
                val out = Bitmap.createBitmap(wa + wb + gap * 3, h + gap * 2, Bitmap.Config.ARGB_8888)
                val c = Canvas(out); c.drawColor(0xFF111114.toInt())
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                c.drawBitmap(a, null, Rect(gap, gap, gap + wa, gap + h), p)
                c.drawBitmap(b, null, Rect(gap * 2 + wa, gap, gap * 2 + wa + wb, gap + h), p)
                // lo de encima de la foto de después: el sello y los pines
                CompareView.overlay(c, RectF((gap * 2 + wa).toFloat(), gap.toFloat(), (gap * 2 + wa + wb).toFloat(), (gap + h).toFloat()), cv.afterPins, cv.afterStamp, h / 46f)
                fun label(t: String, x: Float, col: Int) {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 56f; isFakeBoldText = true; color = Color.WHITE }
                    val w = tp.measureText(t) + 48; val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
                    c.drawRoundRect(x, gap + 24f, x + w, gap + 24f + 84, 42f, 42f, bg); c.drawText(t, x + 24, gap + 24f + 60, tp)
                }
                label(L.t("baBefore").uppercase(L.locale), gap + 24f, 0xE6FF453A.toInt())
                label(L.t("baAfter").uppercase(L.locale), gap * 2 + wa + 24f, 0xE630D158.toInt())
                val name = "f" + newId() + ".jpg"
                FileOutputStream(File(Store.mediaDir, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                Triple(name, out.width, out.height)
            } catch (_: Throwable) { null }
            act.runOnUiThread { if (r == null) act.toast(L.t("vFail")) else done(r.first, r.second, r.third) }
        }
    }

    private fun share(act: Activity, f: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".archivos", File(Store.mediaDir, f))
        act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
}

/** Las dos fotos con la rayita que se desliza. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CompareView(ctx: Context) : View(ctx) {
    var afterPins: List<Pins.P> = emptyList()
    var afterStamp: String = ""
    var a: Bitmap? = null; private set
    var b: Bitmap? = null; private set
    private var k = 0.5f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.BLACK; isFakeBoldText = true }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(x: Bitmap, y: Bitmap) {
        a = x; b = y; invalidate()
        // ✨ la rayita barre la foto para que se vea cómo funciona
        ValueAnimator.ofFloat(0.92f, 0.08f, 0.5f).apply { duration = 1600; interpolator = android.view.animation.AccelerateDecelerateInterpolator(); addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() }
    }
    fun swap() { val t = a; a = b; b = t; invalidate(); Anim.bump(this, 1.02f) }

    companion object {
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.RIGHT; setShadowLayer(3f, 0f, 1f, Color.BLACK) }
        /** Dibuja encima de la foto [r] el sello (abajo, a la derecha) y los pines (con su nombre). [s] = tamaño de los pines. */
        fun overlay(c: Canvas, r: RectF, pins: List<Pins.P>, stamp: String, s: Float) {
            if (stamp.isNotEmpty()) {
                val th = s * 1.05f
                val strip = Paint().apply { shader = android.graphics.LinearGradient(0f, r.bottom - th * 3f, 0f, r.bottom, 0x00000000, 0x9E000000.toInt(), android.graphics.Shader.TileMode.CLAMP) }
                c.drawRect(r.left, r.bottom - th * 3f, r.right, r.bottom, strip)
                sp.textSize = th; c.drawText(stamp, r.right - th * 0.7f, r.bottom - th * 0.6f, sp)
            }
            for (pn in pins) Pins.draw(c, r.left + pn.x * r.width(), r.top + pn.y * r.height(), s, pn, true)
        }
    }

    /** Dónde va la foto (las dos del mismo tamaño, del tamaño de la de "después"). */
    private fun area(): RectF {
        val y = b ?: return RectF()
        val top = 100 * d; val bottom = 110 * d; val h = height - top - bottom
        val s = minOf(width.toFloat() / y.width, h / y.height)
        val w = y.width * s; val hh = y.height * s
        return RectF((width - w) / 2, top + (h - hh) / 2, (width + w) / 2, top + (h + hh) / 2)
    }
    /** La foto llenando [r] sin estirarse (se recorta lo que sobra). */
    private fun src(bm: Bitmap, r: RectF): Rect {
        val ra = r.width() / r.height(); val ba = bm.width.toFloat() / bm.height
        return if (ba > ra) { val w = (bm.height * ra).toInt(); val x = (bm.width - w) / 2; Rect(x, 0, x + w, bm.height) } else { val h = (bm.width / ra).toInt(); val y = (bm.height - h) / 2; Rect(0, y, bm.width, y + h) }
    }

    override fun onDraw(c: Canvas) {
        val x = a ?: return; val y = b ?: return
        val r = area()
        c.drawBitmap(y, src(y, r), r, p)
        val cut = r.left + r.width() * k
        // lo de encima de la foto de después (sello y pines), solo del lado de "después"
        c.save(); c.clipRect(cut, r.top, r.right, r.bottom); overlay(c, r, afterPins, afterStamp, (r.height() / 40f).coerceIn(9 * d, 16 * d)); c.restore()
        c.save(); c.clipRect(r.left, r.top, cut, r.bottom); c.drawBitmap(x, src(x, r), r, p); c.restore()
        // la rayita con su bolita
        line.strokeWidth = 3 * d; c.drawLine(cut, r.top, cut, r.bottom, line)
        line.setShadowLayer(8 * d, 0f, 2 * d, 0x88000000.toInt()); c.drawCircle(cut, r.centerY(), 20 * d, line); line.clearShadowLayer()
        tp.textSize = 15 * d; c.drawText("◀ ▶", cut, r.centerY() + 5 * d, tp)
        // los letreros
        fun tag(t: String, x0: Float, col: Int, right: Boolean) {
            lbl.textSize = 12.5f * d; val w = lbl.measureText(t) + 20 * d
            val l = if (right) x0 - w else x0
            bg.color = col; c.drawRoundRect(l, r.top + 10 * d, l + w, r.top + 36 * d, 13 * d, 13 * d, bg)
            c.drawText(t, l + 10 * d, r.top + 28 * d, lbl)
        }
        if (k > 0.12f) tag(L.t("baBefore").uppercase(L.locale), r.left + 10 * d, 0xE6FF453A.toInt(), false)
        if (k < 0.88f) tag(L.t("baAfter").uppercase(L.locale), r.right - 10 * d, 0xE630D158.toInt(), true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = area(); if (r.width() <= 0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val nk = ((e.x - r.left) / r.width()).coerceIn(0f, 1f)
                if ((nk < 0.02f) != (k < 0.02f) || (nk > 0.98f) != (k > 0.98f)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                k = nk; invalidate()
            }
        }
        return true
    }
}
