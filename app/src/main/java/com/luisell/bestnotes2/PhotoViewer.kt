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
import java.io.File

/* ============================================================
   PhotoViewer.kt — ⛶ VER UNA FOTO EN GRANDE (dentro de la app).
   - Con dos dedos la agrandas; con un dedo la mueves.
   - Dos toques: acerca / aleja (con animación).
   - Desliza hacia abajo para cerrar (o toca ✕).
   ============================================================ */
object PhotoViewer {
    fun show(act: Activity, file: String) {
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
