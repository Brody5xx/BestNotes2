package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/* ============================================================
   Fx.kt — ✨ las estrellitas de Best Notes.
   Se prenden y se apagan (titilan) a destiempo y se van moviendo,
   como el rastro del menú al cerrarse y el círculo al abrir una nota.
   ============================================================ */
class Star(
    val x: Float, val y: Float, val size: Float, val rot: Float,
    /** cuándo aparece y cuánto dura (0..1 de la animación) */
    val start: Float, val life: Float,
    val dx: Float, val dy: Float, val alt: Boolean,
)

@SuppressLint("ViewConstructor")
class SparkleView(ctx: Context, private val stars: List<Star>) : View(ctx) {
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()

    init { isClickable = false; setLayerType(LAYER_TYPE_HARDWARE, null) }

    /** Brillo de cada estrellita en el momento [k] de su vida (0..1): titila dos veces y se apaga. */
    private fun bright(s: Star, k: Float): Float {
        val a = if (s.alt) floatArrayOf(0f, 0.9f, 1f, 0.3f, 0.8f, 0f) else floatArrayOf(0f, 1f, 0.25f, 1f, 0.55f, 0f)
        val stops = floatArrayOf(0f, 0.12f, 0.28f, 0.44f, 0.62f, 1f)
        for (i in 1 until stops.size) if (k <= stops[i]) {
            val f = (k - stops[i - 1]) / (stops[i] - stops[i - 1])
            return a[i - 1] + (a[i] - a[i - 1]) * f
        }
        return 0f
    }

    override fun onDraw(c: Canvas) {
        for (s in stars) {
            val k = (t - s.start) / s.life
            if (k <= 0f || k >= 1f) continue
            val o = bright(s, k)
            if (o <= 0.01f) continue
            p.alpha = (o * 255).toInt()
            val cx = s.x + s.dx * k; val cy = s.y + s.dy * k
            val r = s.size / 2
            // ✦ estrella de 4 puntas
            c.save(); c.rotate(s.rot, cx, cy)
            path.reset()
            path.moveTo(cx, cy - r)
            path.quadTo(cx + r * 0.16f, cy - r * 0.16f, cx + r, cy)
            path.quadTo(cx + r * 0.16f, cy + r * 0.16f, cx, cy + r)
            path.quadTo(cx - r * 0.16f, cy + r * 0.16f, cx - r, cy)
            path.quadTo(cx - r * 0.16f, cy - r * 0.16f, cx, cy - r)
            c.drawPath(path, p)
            c.restore()
        }
    }

    /** Corre la animación [ms] y se quita sola al terminar. */
    fun play(ms: Long) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { (parent as? ViewGroup)?.removeView(this@SparkleView) }
            })
            start()
        }
    }
}

object Fx {
    private var seed = System.nanoTime()
    private fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 10000) / 10000f }

    /**
     * Rastro del menú al cerrarse: el borde se va hacia la izquierda y va dejando
     * estrellitas que titilan y se van a la izquierda. [left] = dónde empieza el panel en la pantalla.
     */
    fun menuTrail(ctx: Context, left: Float, top: Float, panelW: Float, h: Float, screenW: Float): List<Star> {
        val d = ctx.resources.displayMetrics.density
        val out = ArrayList<Star>()
        val bands = 14
        for (g in 0 until bands) {
            val x1 = panelW * (1 - g.toFloat() / bands); val x0 = panelW * (1 - (g + 1f) / bands)
            val passed = (1 - x1 / panelW).coerceIn(0.02f, 0.95f) * 0.62f
            for (k in 0 until 8) out.add(Star(left + x0 + rnd() * (x1 - x0), top + 8 * d + rnd() * (h - 16 * d), (4 + rnd() * 6) * d, rnd() * 45,
                passed + rnd() * 0.06f, 0.33f + rnd() * 0.15f, -(6 + rnd() * 12) * d, 0f, rnd() > 0.5f))
        }
        // lado derecho de la pantalla: salen al empezar a cerrar
        for (k in 0 until 40) out.add(Star(left + panelW * 0.55f + rnd() * (screenW - panelW * 0.55f - 20 * d), top + rnd() * h, (4 + rnd() * 6) * d, rnd() * 45,
            0.01f + rnd() * 0.3f, 0.33f + rnd() * 0.15f, -(8 + rnd() * 16) * d, 0f, rnd() > 0.5f))
        return out
    }

    /**
     * Rastro de una fila que se abre o se cierra (como el menú lateral, pero de lado):
     * una barrita de luz la recorre y va dejando estrellitas que titilan y se quedan atrás.
     * [forward] = de izquierda a derecha (abrir); si no, de derecha a izquierda (cerrar).
     */
    fun rowTrail(ctx: Context, left: Float, top: Float, w: Float, h: Float, forward: Boolean): List<Star> {
        val d = ctx.resources.displayMetrics.density
        val out = ArrayList<Star>()
        val bands = 12
        for (g in 0 until bands) {
            val k = g.toFloat() / bands
            val x0 = if (forward) left + w * k else left + w * (1 - k - 1f / bands)
            val passed = k * 0.55f
            for (j in 0 until 5) out.add(Star(x0 + rnd() * w / bands, top + rnd() * h, (3.5f + rnd() * 5) * d, rnd() * 45,
                passed + rnd() * 0.06f, 0.35f + rnd() * 0.18f, (if (forward) -1f else 1f) * (4 + rnd() * 10) * d, (rnd() - 0.5f) * 8 * d, rnd() > 0.5f))
        }
        return out
    }

    /**
     * ✦ La barrita de luz que recorre la fila en [ms] (y al final un destello).
     * [onPos] recibe dónde va la luz (0..1) para ir mostrando o escondiendo los botones a su paso.
     */
    fun sweep(root: ViewGroup, left: Float, top: Float, w: Float, h: Float, forward: Boolean, ms: Long, onPos: (Float) -> Unit, onEnd: () -> Unit) {
        val ctx = root.context; val d = ctx.resources.displayMetrics.density
        val bar = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0xEEFFFFFF.toInt(), 0x00FFFFFF)).apply { cornerRadius = 3 * d }
            isClickable = false
        }
        val bw = 14 * d
        root.addView(bar, android.widget.FrameLayout.LayoutParams(bw.toInt(), h.toInt()))
        bar.translationY = top
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                val x = if (forward) left + w * p else left + w * (1 - p)
                bar.translationX = x - bw / 2
                bar.alpha = if (p < 0.1f) p * 10 else if (p > 0.85f) (1 - p) / 0.15f else 1f
                bar.scaleY = 0.6f + 0.4f * Math.sin(p * Math.PI).toFloat()
                onPos(p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    root.removeView(bar)
                    // ✦ destello donde terminó la luz
                    val ex = if (forward) left + w else left
                    val sp = SparkleView(ctx, listOf(Star(ex, top + h / 2, 22 * d, 0f, 0f, 1f, 0f, 0f, false), Star(ex, top + h / 2, 12 * d, 45f, 0.1f, 0.8f, 0f, 0f, true)))
                    root.addView(sp, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    sp.play(520)
                    onEnd()
                }
            })
            start()
        }
    }

    /** Estrellitas alrededor del círculo que se abre (al abrir una nota). */
    fun ring(ctx: Context, cx: Float, cy: Float, rMax: Float): List<Star> {
        val d = ctx.resources.displayMetrics.density
        val out = ArrayList<Star>()
        for (g in 0 until 40) {
            val t = rnd() * 0.75f
            val e = 1 - Math.pow((1 - t).toDouble(), 3.0).toFloat()          // donde va el borde del círculo en ese momento
            val a = rnd() * Math.PI.toFloat() * 2
            val rr = e * rMax * (0.92f + rnd() * 0.12f)
            val dr = (10 + rnd() * 20) * d
            out.add(Star(cx + Math.cos(a.toDouble()).toFloat() * rr, cy + Math.sin(a.toDouble()).toFloat() * rr, (4 + rnd() * 7) * d, rnd() * 45,
                t, 0.3f + rnd() * 0.2f, Math.cos(a.toDouble()).toFloat() * dr, Math.sin(a.toDouble()).toFloat() * dr, rnd() > 0.5f))
        }
        return out
    }
}
