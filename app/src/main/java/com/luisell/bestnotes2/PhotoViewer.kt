package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import java.io.File

/* ============================================================
   PhotoViewer.kt — ⛶ VER UNA FOTO EN GRANDE (dentro de la app).
   - Con dos dedos la agrandas; con un dedo la mueves.
   - Dos toques: acerca / aleja (con animación).
   - Desliza hacia abajo para cerrar (o toca ✕).
   ============================================================ */
object PhotoViewer {
    /** [pins] = los 🗺️ pines de la foto (se pueden tocar); [onEditPins] = abrir el plano para cambiarlos. */
    fun show(act: Activity, file: String, pins: String = "", onEditPins: (() -> Unit)? = null) {
        val d = blackFullDialog(act)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val iv = ZoomImageView(act)
        root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = act.glassBtn(Icons.CLOSE, 9f, Color.WHITE) { d.dismiss() }
        root.addView(close, FrameLayout.LayoutParams(act.dp(46), act.dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(36), act.dp(14), 0) })
        // 🔎 "Texto": escoger y copiar las palabras de la foto
        val txt = act.tv("🔎  " + L.t("ocrBtn"), 15f, Color.WHITE, true).apply {
            background = Glass.make(act, 24f, strong = true); setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
            setOnClickListener { v -> Anim.bump(v); OcrPick.show(act, File(Store.mediaDir, file), null) }
        }
        root.addView(txt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, act.dp(40)) })
        iv.onSwipeDown = { k -> root.background.alpha = (255 * (1 - k)).toInt().coerceIn(0, 255); close.alpha = 1 - k; txt.alpha = 1 - k }
        // 🗺️ los pines: se ven encima de la foto y al tocarlos sale su información
        iv.pins = Pins.parse(pins)
        var card: View? = null
        fun hideCard() { card?.let { c -> c.animate().alpha(0f).translationY(act.dp(40).toFloat()).setDuration(180).withEndAction { root.removeView(c) }.start() }; card = null; iv.selected = null; txt.animate().alpha(1f).setDuration(200).start() }
        iv.onPinTap = { p ->
            if (p == null) hideCard() else {
                card?.let { root.removeView(it) }
                iv.selected = p
                val k = Pins.kind(p.k)
                val c = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; background = Glass.make(act, 26f, strong = true); setPadding(act.dp(18), act.dp(14), act.dp(14), act.dp(14)); elevation = act.dp(12).toFloat()
                    isClickable = true
                }
                val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(act.tv(k.emoji, 24f).apply { setPadding(0, 0, act.dp(10), 0) })
                val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                tcol.addView(act.tv(p.t.ifBlank { k.label }, 18f, Color.WHITE, true))
                tcol.addView(act.tv(k.label, 12.5f, k.color, true))
                head.addView(tcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (onEditPins != null) head.addView(act.tv("✏️ " + L.t("edit"), 14f, Color.WHITE, true).apply {
                    setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8)); background = roundBg(T.accent, act.dp(18).toFloat())
                    setOnClickListener { v -> Anim.bump(v); d.dismiss(); onEditPins() }
                })
                head.addView(act.iconBtn(Icons.CLOSE, 8f, Color.WHITE) { hideCard() }.apply { layoutParams = LinearLayout.LayoutParams(act.dp(40), act.dp(40)) })
                c.addView(head)
                if (p.d.isNotBlank()) c.addView(act.tv(p.d, 15f, 0xE6FFFFFF.toInt()).apply { setPadding(0, act.dp(10), 0, 0); setLineSpacing(0f, 1.2f); setTextIsSelectable(true) })
                if (p.f.isNotEmpty()) {
                    val im = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(0, act.dp(16).toFloat()) }
                    Thumbs.load(p.f, act.dp(360)) { bm -> im.setImageBitmap(bm) }
                    im.setOnClickListener { show(act, p.f) }
                    c.addView(im, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(180)).apply { topMargin = act.dp(12) })
                }
                root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(act.dp(12), 0, act.dp(12), act.dp(28)) })
                c.alpha = 0f; c.translationY = act.dp(60).toFloat()
                c.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
                txt.animate().alpha(0f).setDuration(150).start()
                card = c
            }
        }
        iv.onDismiss = { d.dismiss() }
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        // ✨ entra creciendo
        iv.scaleX = 0.85f; iv.scaleY = 0.85f; iv.alpha = 0f
        iv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        Loader.pool.execute {
            val dm = act.resources.displayMetrics
            val bm = Thumbs.decode(File(Store.mediaDir, file), maxOf(dm.widthPixels, dm.heightPixels) * 2)
            act.runOnUiThread { if (bm != null) iv.setBitmap(bm) else { act.toast(L.t("cantOpen")); d.dismiss() } }
        }
    }
}

/** Una foto que se agranda con dos dedos, se mueve con uno y se acerca con dos toques. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    /** 🗺️ Pines encima de la foto. */
    var pins: List<Pins.P> = emptyList()
        set(v) { field = v; invalidate() }
    var selected: Pins.P? = null
        set(v) { field = v; invalidate() }
    /** Tocaste un pin (o null = tocaste fuera de los pines). */
    var onPinTap: ((Pins.P?) -> Unit)? = null
    private fun pinSize(): Float { val d = resources.displayMetrics.density; return (13 * d * Math.sqrt((scaleNow() / base).toDouble()).toFloat()).coerceIn(12 * d, 22 * d) }
    private fun pinAt(p: Pins.P): FloatArray { val v = floatArrayOf(p.x * bw, p.y * bh); m.mapPoints(v); return v }
    private fun hitPin(x: Float, y: Float): Pins.P? {
        val s = pinSize()
        return pins.lastOrNull { p -> val q = pinAt(p); Math.hypot((x - q[0]).toDouble(), (y - s * 1.5f - q[1]).toDouble()) < s * 1.9f }
    }
    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        if (pins.isEmpty() || bw == 0) return
        val s = pinSize()
        for (p in pins) { val q = pinAt(p); Pins.draw(c, q[0], q[1], s, p, true, 1f, p === selected) }
    }
    private val m = Matrix()
    private var base = 1f          // la escala que hace que la foto quepa
    private var bw = 0; private var bh = 0
    var onSwipeDown: ((Float) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    private var pullY = 0f

    init { scaleType = ScaleType.MATRIX }

    fun setBitmap(b: Bitmap) { bw = b.width; bh = b.height; setImageBitmap(b); post { fit() } }

    private fun fit() {
        if (bw == 0 || width == 0) return
        base = minOf(width.toFloat() / bw, height.toFloat() / bh)
        m.reset(); m.postScale(base, base); m.postTranslate((width - bw * base) / 2, (height - bh * base) / 2)
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun scaleNow(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    /** Que la foto no se salga de la pantalla (centrada si es más chica). */
    private fun clamp() {
        val r = RectF(0f, 0f, bw.toFloat(), bh.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        dx = if (r.width() <= width) (width - r.width()) / 2 - r.left else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
        dy = if (r.height() <= height) (height - r.height()) / 2 - r.top else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
        m.postTranslate(dx, dy)
    }

    private val sg = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val now = scaleNow(); val k = (now * d.scaleFactor).coerceIn(base, base * 6) / now
            m.postScale(k, k, d.focusX, d.focusY); clamp(); imageMatrix = m
            return true
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleNow() <= base * 1.01f) {
                // sin zoom: arrastrar hacia abajo cierra
                pullY -= dy; if (pullY < 0) pullY = 0f
                translationY = pullY; onSwipeDown?.invoke((pullY / (height * 0.4f)).coerceIn(0f, 1f))
                return true
            }
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m; return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (pins.isEmpty()) return false
            val p = hitPin(e.x, e.y)
            if (p != null) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onPinTap?.invoke(p); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val from = scaleNow(); val to = if (from > base * 1.2f) base else base * 2.6f
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260; interpolator = android.view.animation.DecelerateInterpolator()
                var last = from
                addUpdateListener { a ->
                    val target = from + (to - from) * (a.animatedValue as Float)
                    val k = target / last; last = target
                    m.postScale(k, k, e.x, e.y); clamp(); imageMatrix = m
                }
            }.start()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e); if (!sg.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (pullY > height * 0.18f) {
                animate().translationY(height.toFloat()).alpha(0f).setDuration(200).withEndAction { onDismiss?.invoke() }.start()
            } else if (pullY > 0) {
                animate().translationY(0f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
                onSwipeDown?.invoke(0f)
            }
            pullY = 0f
        }
        return true
    }
}
