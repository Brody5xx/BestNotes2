package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/* ============================================================
   AiOrb.kt — 💫 "Nova": el logo vivo de la IA de Best Notes 2.
   Una estrella de 4 puntas con colores que giran (violeta →
   celeste → rosa → ámbar), un aro fino con una lunita que le
   da la vuelta y un brillo que "respira". Siempre se mueve.
   ============================================================ */
class AiOrb(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Path()
    private val m = Matrix()
    private val colors = intArrayOf(0xFFB388FF.toInt(), 0xFF4FC3F7.toInt(), 0xFFF48FB1.toInt(), 0xFFFFD54F.toInt(), 0xFFB388FF.toInt())
    private var t = 0f            // 0..1 una vuelta
    private var press = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    init { isClickable = true; contentDescription = "IA" }

    /** Se mueve solo cuando se ve; al volver a la app (desde recientes) sigue donde iba. */
    private fun sync() {
        val show = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (show) { if (!anim.isStarted) anim.start() else if (anim.isPaused) anim.resume() }
        else if (anim.isStarted && !anim.isPaused) anim.pause()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); post { sync() } }
    override fun onWindowVisibilityChanged(vis: Int) { super.onWindowVisibilityChanged(vis); post { sync() } }
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) post { sync() } }

    /** Un toque: la estrella da un salto y gira rápido. */
    fun burst() {
        ValueAnimator.ofFloat(1f, 0f).apply { duration = 700; addUpdateListener { press = it.animatedValue as Float; invalidate() } }.start()
    }

    private fun starPath(cx: Float, cy: Float, r: Float, inner: Float) {
        star.reset()
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0 - 90.0)
            val b = Math.toRadians(i * 90.0 - 45.0)
            val px = cx + (r * Math.cos(a)).toFloat(); val py = cy + (r * Math.sin(a)).toFloat()
            val qx = cx + (inner * Math.cos(b)).toFloat(); val qy = cy + (inner * Math.sin(b)).toFloat()
            if (i == 0) star.moveTo(px, py) else star.quadTo(qx0, qy0, px, py)
            qx0 = qx; qy0 = qy
        }
        star.quadTo(qx0, qy0, cx, cy - r)
        star.close()
    }
    private var qx0 = 0f; private var qy0 = 0f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val R = minOf(width, height) / 2f - d
        val breathe = (Math.sin(t * Math.PI * 4).toFloat() + 1f) / 2f          // 0..1, respira 2 veces por vuelta
        // brillo de atrás
        glow.shader = RadialGradient(cx, cy, R, intArrayOf(Color.argb((70 + 60 * breathe + 80 * press).toInt(), 179, 136, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, glow)
        // aro fino con colores que giran
        val sw = SweepGradient(cx, cy, colors, null)
        m.setRotate(t * 360f, cx, cy); sw.setLocalMatrix(m)
        ring.shader = sw; ring.strokeWidth = 1.4f * d; ring.alpha = 170
        val rr = R * 0.86f
        c.drawCircle(cx, cy, rr, ring)
        // la lunita que da vueltas
        val a = Math.toRadians((t * 720.0) % 360.0)
        dot.color = Color.WHITE; dot.setShadowLayer(4 * d, 0f, 0f, 0xFF4FC3F7.toInt())
        c.drawCircle(cx + (rr * Math.cos(a)).toFloat(), cy + (rr * Math.sin(a)).toFloat(), 1.9f * d, dot)
        // la estrella (crece un poquito al respirar; gira despacio)
        c.save()
        c.rotate(Math.sin(t * Math.PI * 2).toFloat() * 12f + press * 180f, cx, cy)
        val sr = R * (0.52f + 0.05f * breathe + 0.12f * press)
        starPath(cx, cy, sr, sr * 0.26f)
        val sg = SweepGradient(cx, cy, colors, null)
        m.setRotate(-t * 720f, cx, cy); sg.setLocalMatrix(m)
        fill.shader = sg
        c.drawPath(star, fill)
        // centro blanco brillante
        fill.shader = RadialGradient(cx, cy, sr * 0.55f, intArrayOf(0xEEFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, sr * 0.55f, fill)
        c.restore()
        // estrellita chiquita que titila
        val tw = ((Math.sin(t * Math.PI * 6).toFloat() + 1f) / 2f)
        c.save()
        starPath(cx + R * 0.5f, cy - R * 0.5f, R * 0.16f * tw, R * 0.04f * tw)
        fill.shader = null; fill.color = Color.WHITE; fill.alpha = (255 * tw).toInt()
        c.drawPath(star, fill); fill.alpha = 255
        c.restore()
    }
}
