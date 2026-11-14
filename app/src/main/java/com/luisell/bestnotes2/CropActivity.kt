package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import java.io.File

/* ============================================================
   CropActivity.kt — ✂️ Recortar una foto.
   Mueve las esquinas o los lados del cuadro; arrastra por dentro
   para moverlo. "Listo" guarda la foto recortada (archivo nuevo).
   ============================================================ */
class CropActivity : Activity() {
    companion object {
        const val FILE = "archivo"
        const val OUT = "nuevo"; const val OUT_W = "w"; const val OUT_H = "h"
    }

    private lateinit var cv: CropView
    private lateinit var file: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = intent.getStringExtra(FILE) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        root.setBackgroundColor(0xFF000000.toInt()); window.statusBarColor = 0xFF000000.toInt()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        top.addView(tv(L.t("cancel"), 16f, T.text).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { finish() } })
        top.addView(tv(L.t("crop"), 18f, T.text, true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        top.addView(tv(L.t("reset"), 15f, T.muted).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { cv.reset() } })
        top.addView(tv(L.t("done"), 16f, T.accent, true).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { done() } })
        root.addView(top, lp(MATCH, WRAP))
        cv = CropView(this)
        root.addView(cv, lp(MATCH, 0, 1f))
        setContentView(root)
        Loader.pool.execute {
            val bm = Thumbs.decode(File(Store.mediaDir, file), 1600)
            runOnUiThread { if (bm == null) finish() else cv.setImage(bm) }
        }
    }

    private fun done() {
        val r = cv.result()
        if (r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f) { finish(); return }
        Loader.pool.execute {
            val res = Photos.crop(file, r.left, r.top, r.right, r.bottom)
            runOnUiThread {
                if (res != null) setResult(RESULT_OK, Intent().putExtra(OUT, res.file).putExtra(OUT_W, res.w).putExtra(OUT_H, res.h))
                finish()
            }
        }
    }

    @SuppressLint("ViewConstructor")
    class CropView(ctx: Context) : View(ctx) {
        private var bm: Bitmap? = null
        private val img = RectF()            // dónde se dibuja la foto
        private val sel = RectF()            // el cuadro (en pantalla)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mode = 0                 // 0 nada, 1 mover, 2 esquina/lado
        private var hx = 0; private var hy = 0
        private var lx = 0f; private var ly = 0f

        fun setImage(b: Bitmap) { bm = b; requestLayout(); post { fit(); reset() } }

        private fun fit() {
            val b = bm ?: return
            val pad = dp(24).toFloat()
            val k = minOf((width - pad * 2) / b.width, (height - pad * 2) / b.height)
            val w = b.width * k; val h = b.height * k
            img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        fun reset() { sel.set(img); invalidate() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit(); reset() }

        fun result(): RectF = RectF(
            ((sel.left - img.left) / img.width()).coerceIn(0f, 1f), ((sel.top - img.top) / img.height()).coerceIn(0f, 1f),
            ((sel.right - img.left) / img.width()).coerceIn(0f, 1f), ((sel.bottom - img.top) / img.height()).coerceIn(0f, 1f),
        )

        override fun onDraw(c: Canvas) {
            val b = bm ?: return
            c.drawBitmap(b, null, img, p)
            // oscuro afuera del cuadro
            p.style = Paint.Style.FILL; p.color = 0xA0000000.toInt()
            val path = Path().apply { addRect(img, Path.Direction.CW); addRect(sel, Path.Direction.CCW) }
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = dp(2).toFloat()
            c.drawRect(sel, p)
            // líneas de tercios
            p.strokeWidth = dp(1).toFloat(); p.color = 0x66FFFFFF
            for (k in 1..2) {
                c.drawLine(sel.left + sel.width() * k / 3, sel.top, sel.left + sel.width() * k / 3, sel.bottom, p)
                c.drawLine(sel.left, sel.top + sel.height() * k / 3, sel.right, sel.top + sel.height() * k / 3, p)
            }
            // esquinas gruesas
            p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = dp(4).toFloat(); p.strokeCap = Paint.Cap.ROUND
            val a = dp(18).toFloat()
            for ((x, y) in listOf(sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom)) {
                val dx = if (x == sel.left) a else -a; val dy = if (y == sel.top) a else -a
                c.drawLine(x, y, x + dx, y, p); c.drawLine(x, y, x, y + dy, p)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val near = dp(28).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y
                    hx = when { Math.abs(e.x - sel.left) < near -> -1; Math.abs(e.x - sel.right) < near -> 1; else -> 0 }
                    hy = when { Math.abs(e.y - sel.top) < near -> -1; Math.abs(e.y - sel.bottom) < near -> 1; else -> 0 }
                    val inside = sel.contains(e.x, e.y)
                    mode = if ((hx != 0 || hy != 0) && e.y > sel.top - near && e.y < sel.bottom + near && e.x > sel.left - near && e.x < sel.right + near) 2 else if (inside) 1 else 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx; val dy = e.y - ly; lx = e.x; ly = e.y
                    val min = dp(40).toFloat()
                    if (mode == 1) {
                        val mx = dx.coerceIn(img.left - sel.left, img.right - sel.right)
                        val my = dy.coerceIn(img.top - sel.top, img.bottom - sel.bottom)
                        sel.offset(mx, my)
                    } else if (mode == 2) {
                        if (hx < 0) sel.left = (sel.left + dx).coerceIn(img.left, sel.right - min)
                        if (hx > 0) sel.right = (sel.right + dx).coerceIn(sel.left + min, img.right)
                        if (hy < 0) sel.top = (sel.top + dy).coerceIn(img.top, sel.bottom - min)
                        if (hy > 0) sel.bottom = (sel.bottom + dy).coerceIn(sel.top + min, img.bottom)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }
    }
}
